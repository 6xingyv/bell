mod level;
mod output;
mod recipes;

pub use output::AudioOutput;
use recipes::{AudioRecipe, CrossfadeRecipe, FadeRecipe, ReplayGainRecipe};
use ringbuf::HeapCons;
use ringbuf::traits::{Consumer, Observer};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

/// Live renderer state published lock-free from the audio callback so the
/// engine's watcher threads can read it without contending the `Renderer`
/// mutex with the realtime callback (which drops a buffer to silence on a
/// failed `try_lock`).
#[derive(Debug)]
pub struct RendererShared {
    current_frame: AtomicU64,
    lead_empty: AtomicBool,
    paused: AtomicBool,
    follow_frame: AtomicU64,
    /// Decoded-but-unrendered samples buffered in the lead ring.
    lead_occupied: AtomicU64,
    /// Callbacks that could not be fully served from the lead ring.
    underruns: AtomicU64,
}

impl RendererShared {
    /// Sentinel in `follow_frame` meaning "no crossfade is active".
    const NO_CROSSFADE: u64 = u64::MAX;

    fn new() -> Self {
        Self {
            current_frame: AtomicU64::new(0),
            lead_empty: AtomicBool::new(true),
            paused: AtomicBool::new(false),
            follow_frame: AtomicU64::new(Self::NO_CROSSFADE),
            lead_occupied: AtomicU64::new(0),
            underruns: AtomicU64::new(0),
        }
    }

    pub fn current_frame(&self) -> u64 {
        self.current_frame.load(Ordering::Relaxed)
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Relaxed)
    }

    pub fn lead_is_empty(&self) -> bool {
        self.lead_empty.load(Ordering::Relaxed)
    }

    /// Follow-track frame during a crossfade, or `None` when none is active.
    pub fn crossfade_follow_frame(&self) -> Option<u64> {
        let value = self.follow_frame.load(Ordering::Relaxed);
        (value != Self::NO_CROSSFADE).then_some(value)
    }

    /// Decoded-but-unrendered samples buffered in the lead ring.
    pub fn lead_occupied_samples(&self) -> u64 {
        self.lead_occupied.load(Ordering::Relaxed)
    }

    /// Output callbacks that could not be fully served from the lead ring.
    pub fn underruns(&self) -> u64 {
        self.underruns.load(Ordering::Relaxed)
    }
}

pub struct Renderer {
    lead_consumer: Option<HeapCons<f32>>,
    recipe_chain: Vec<Box<dyn AudioRecipe>>,
    current_frame: u64,
    channels: u16,
    is_paused: bool,
    /// Handle to the current track's decoder thread. When the audio callback
    /// frees ring-buffer space, it unparks this thread so decoding resumes
    /// immediately instead of after a fixed poll interval (task 5).
    producer_thread: Option<std::thread::Thread>,
    /// Decoder thread feeding the crossfade follow track; promoted to
    /// `producer_thread` when the crossfade finishes.
    follow_producer_thread: Option<std::thread::Thread>,
    /// Lock-free mirror of the state the watcher threads poll.
    shared: Arc<RendererShared>,
    /// Active play/pause/seek gain ramp applied to the final mix, if any.
    fade: Option<FadeRecipe>,
}

impl Renderer {
    pub fn new(channels: u16) -> Self {
        Self {
            lead_consumer: None,
            recipe_chain: Vec::new(),
            current_frame: 0,
            channels: channels.max(1),
            is_paused: false,
            producer_thread: None,
            follow_producer_thread: None,
            shared: Arc::new(RendererShared::new()),
            fade: None,
        }
    }

    /// Lock-free handle the engine watcher threads read instead of locking the
    /// renderer on every poll tick.
    pub fn shared(&self) -> Arc<RendererShared> {
        self.shared.clone()
    }

    /// Publish the current lead/crossfade state to the lock-free mirror.
    fn publish_state(&self) {
        self.shared
            .current_frame
            .store(self.current_frame, Ordering::Relaxed);
        self.shared
            .lead_empty
            .store(self.lead_is_empty(), Ordering::Relaxed);
        self.shared.paused.store(self.is_paused, Ordering::Relaxed);
        let occupied = self
            .lead_consumer
            .as_ref()
            .map(|consumer| consumer.occupied_len() as u64)
            .unwrap_or(0);
        self.shared
            .lead_occupied
            .store(occupied, Ordering::Relaxed);
        let follow = self
            .crossfade_follow_frame()
            .unwrap_or(RendererShared::NO_CROSSFADE);
        self.shared.follow_frame.store(follow, Ordering::Relaxed);
    }

    pub fn begin_track(
        &mut self,
        consumer: HeapCons<f32>,
        start_frame: u64,
        replay_gain_db: Option<f32>,
        fade_in_frames: u64,
    ) {
        self.lead_consumer = Some(consumer);
        self.recipe_chain.clear();
        if let Some(gain) = replay_gain_db {
            self.recipe_chain
                .push(Box::new(ReplayGainRecipe::new(gain)));
        }
        self.current_frame = start_frame;
        self.is_paused = false;
        // Cleared here; the engine installs the new decoder thread right after.
        self.producer_thread = None;
        self.follow_producer_thread = None;
        // Fade the new track in (e.g. after a seek) so the discontinuity at the
        // splice point does not click; `0` starts at full volume.
        self.fade = (fade_in_frames > 0).then(|| FadeRecipe::ramp(0.0, 1.0, fade_in_frames, false));
        self.publish_state();
    }

    /// Fade the output down over `frames` and pause once it reaches silence
    /// (play/pause fade). Keeps rendering — must not already be paused.
    pub fn fade_out_and_pause(&mut self, frames: u64) {
        let from = self.fade.as_ref().map_or(1.0, FadeRecipe::current_gain);
        self.fade = Some(FadeRecipe::ramp(from, 0.0, frames, true));
    }

    /// Un-pause and fade the output back up over `frames`, continuing from the
    /// current (possibly mid-fade-out) gain so play/pause never clicks.
    pub fn resume_faded(&mut self, frames: u64) {
        let from = self.fade.as_ref().map_or(0.0, FadeRecipe::current_gain);
        self.is_paused = false;
        self.fade = Some(FadeRecipe::ramp(from, 1.0, frames, false));
        self.publish_state();
    }

    /// Register the decoder thread that fills the lead consumer so the audio
    /// callback can wake it when it drains the ring buffer (task 5).
    pub fn set_producer_thread(&mut self, thread: Option<std::thread::Thread>) {
        self.producer_thread = thread;
    }

    /// Rebase the lead timeline to `frame` after an out-of-band seek on a
    /// progressive stream: the source repositions the stream itself, so the
    /// buffered pre-seek audio is dropped and the frame counter jumps to the
    /// new position. A short fade-in masks the splice discontinuity.
    pub fn rebase_lead(&mut self, frame: u64, fade_in_frames: u64) {
        if let Some(consumer) = &mut self.lead_consumer {
            consumer.clear();
        }
        self.current_frame = frame;
        self.fade = (fade_in_frames > 0).then(|| FadeRecipe::ramp(0.0, 1.0, fade_in_frames, false));
        // The decoder may be parked on a full ring it can now refill.
        if let Some(thread) = &self.producer_thread {
            thread.unpark();
        }
        self.publish_state();
    }

    pub fn begin_crossfade(
        &mut self,
        start_frame: u64,
        duration_frames: u64,
        follow_consumer: HeapCons<f32>,
        follow_gain_db: Option<f32>,
        follow_producer: std::thread::Thread,
    ) {
        self.recipe_chain.retain(|recipe| !recipe.is_crossfade());
        self.recipe_chain
            .push(Box::new(CrossfadeRecipe::with_follow_gain(
                start_frame,
                duration_frames,
                follow_consumer,
                follow_gain_db,
            )));
        // So the audio callback can wake the follow decoder while it feeds the
        // crossfade (task 5); promoted to the lead producer in finish_crossfade.
        self.follow_producer_thread = Some(follow_producer);
        self.publish_state();
    }

    pub fn finish_crossfade(&mut self, replay_gain_db: Option<f32>) -> Option<u64> {
        let recipe = self
            .recipe_chain
            .iter_mut()
            .find(|recipe| recipe.is_crossfade())?;
        let follow_frames = recipe.follow_frames_consumed();
        let consumer = recipe.take_follow_consumer()?;

        self.recipe_chain.clear();
        if let Some(gain) = replay_gain_db {
            self.recipe_chain
                .push(Box::new(ReplayGainRecipe::new(gain)));
        }
        self.lead_consumer = Some(consumer);
        // The follow track is now the lead track: its decoder becomes the
        // producer the audio callback unparks.
        self.producer_thread = self.follow_producer_thread.take();
        self.current_frame = follow_frames;
        self.publish_state();
        Some(follow_frames)
    }

    pub fn stop(&mut self) {
        self.is_paused = true;
        self.lead_consumer = None;
        self.recipe_chain.clear();
        self.current_frame = 0;
        self.producer_thread = None;
        self.follow_producer_thread = None;
        self.fade = None;
        self.publish_state();
    }

    pub fn set_paused(&mut self, paused: bool) {
        self.is_paused = paused;
        self.publish_state();
    }

    pub fn is_paused(&self) -> bool {
        self.is_paused
    }

    pub fn current_frame(&self) -> u64 {
        self.current_frame
    }

    /// Position of the follow track while a crossfade is active.
    pub fn crossfade_follow_frame(&self) -> Option<u64> {
        self.recipe_chain
            .iter()
            .find(|recipe| recipe.is_crossfade())
            .map(|recipe| recipe.follow_frames_consumed())
    }

    pub fn lead_is_empty(&self) -> bool {
        self.lead_consumer
            .as_ref()
            .map(|consumer| consumer.is_empty())
            .unwrap_or(true)
    }

    pub fn process_output(&mut self, output_buffer: &mut [f32]) {
        output_buffer.fill(0.0);

        if self.is_paused {
            return;
        }

        let read_len = if let Some(consumer) = &mut self.lead_consumer {
            consumer.pop_slice(output_buffer)
        } else {
            0
        };
        // Diagnostic: a partially served callback means the decoder fell behind
        // (also counts the natural drain at EOF — treat as a trend, not exact).
        if self.lead_consumer.is_some() && read_len < output_buffer.len() {
            self.shared.underruns.fetch_add(1, Ordering::Relaxed);
        }

        // Freed ring-buffer space: wake the decoder if it parked on a full
        // buffer (task 5). `unpark` is a lock-free token post, safe to call from
        // the realtime audio callback; it is a cheap no-op when not parked.
        if read_len > 0 && let Some(thread) = &self.producer_thread {
            thread.unpark();
        }

        let mut supplied_len = 0;
        for recipe in &mut self.recipe_chain {
            supplied_len =
                supplied_len.max(recipe.process(output_buffer, self.current_frame, self.channels));
        }

        // A crossfade recipe just pulled from the follow track — wake its
        // decoder too, on the same lock-free path (task 5).
        if supplied_len > 0 && let Some(thread) = &self.follow_producer_thread {
            thread.unpark();
        }

        // Play/pause/seek gain ramp, applied last to the final mix. A fade-out
        // that reaches silence pauses the renderer.
        if let Some(fade) = &mut self.fade {
            let finished = fade.apply(output_buffer, self.channels);
            if finished {
                if fade.pause_on_complete() {
                    self.is_paused = true;
                }
                self.fade = None;
            }
        }

        let channels = self.channels as usize;
        let rendered_len = read_len.max(supplied_len) / channels * channels;
        self.current_frame += (rendered_len / channels) as u64;
        // Publish the fresh lead frame / drained flag for the watcher threads.
        self.publish_state();
    }
}

#[cfg(test)]
mod tests {
    use super::level::normalized_visual_level;
    use super::*;
    use ringbuf::HeapRb;
    use ringbuf::traits::{Producer, Split};

    #[test]
    fn visual_level_uses_the_musical_db_range() {
        let silence = normalized_visual_level(0.0, 0.0);
        let quiet = normalized_visual_level(0.01, 0.02);
        let normal = normalized_visual_level(0.10, 0.35);
        let loud = normalized_visual_level(0.30, 0.90);
        assert_eq!(silence, 0.0);
        assert!(quiet > 0.0);
        assert!(normal > quiet);
        assert!(loud > normal);
        assert!(loud <= 1.0);
    }

    #[test]
    fn underrun_does_not_advance_timeline() {
        let (_producer, consumer) = HeapRb::<f32>::new(8).split();
        let mut renderer = Renderer::new(2);
        renderer.lead_consumer = Some(consumer);
        renderer.process_output(&mut [0.0; 8]);
        assert_eq!(renderer.current_frame, 0);
    }

    #[test]
    fn timeline_advances_only_for_rendered_frames() {
        let (mut producer, consumer) = HeapRb::<f32>::new(8).split();
        producer.push_slice(&[1.0, 1.0, 1.0, 1.0]);
        let mut renderer = Renderer::new(2);
        renderer.lead_consumer = Some(consumer);
        renderer.process_output(&mut [0.0; 8]);
        assert_eq!(renderer.current_frame, 2);
    }

    #[test]
    fn replay_gain_rejects_non_finite_values() {
        let mut recipe = ReplayGainRecipe::new(f32::NAN);
        let mut samples = [0.5];
        recipe.process(&mut samples, 0, 2);
        assert_eq!(samples[0], 0.5);
    }

    #[test]
    fn crossfade_counts_follow_frames() {
        let (mut producer, consumer) = HeapRb::<f32>::new(8).split();
        producer.push_slice(&[1.0; 8]);
        let mut recipe = CrossfadeRecipe::new(0, 4, consumer);
        let mut output = [1.0; 8];
        assert_eq!(recipe.process(&mut output, 0, 2), 8);
        assert_eq!(recipe.follow_frames_consumed(), 4);
    }

    #[test]
    fn crossfade_starts_follow_at_the_boundary_inside_a_buffer() {
        let (mut lead_producer, lead_consumer) = HeapRb::<f32>::new(16).split();
        lead_producer.push_slice(&[1.0; 8]);
        let (mut follow_producer, follow_consumer) = HeapRb::<f32>::new(16).split();
        follow_producer.push_slice(&[1.0; 8]);

        let mut renderer = Renderer::new(2);
        renderer.begin_track(lead_consumer, 0, None, 0);
        renderer.begin_crossfade(2, 4, follow_consumer, None, std::thread::current());
        let mut output = [0.0; 8];
        renderer.process_output(&mut output);

        assert_eq!(renderer.current_frame(), 4);
        assert_eq!(renderer.crossfade_follow_frame(), Some(2));
        assert_eq!(follow_producer.occupied_len(), 4);
        assert_eq!(output[0], 1.0);
        assert_eq!(output[1], 1.0);
    }

    #[test]
    fn finishing_crossfade_promotes_follow_timeline() {
        let (mut lead_producer, lead_consumer) = HeapRb::<f32>::new(16).split();
        lead_producer.push_slice(&[1.0; 8]);
        let (mut follow_producer, follow_consumer) = HeapRb::<f32>::new(16).split();
        follow_producer.push_slice(&[1.0; 8]);

        let mut renderer = Renderer::new(2);
        renderer.begin_track(lead_consumer, 0, None, 0);
        renderer.begin_crossfade(0, 4, follow_consumer, None, std::thread::current());
        renderer.process_output(&mut [0.0; 8]);

        assert_eq!(renderer.finish_crossfade(None), Some(4));
        assert_eq!(renderer.current_frame(), 4);
        assert_eq!(renderer.crossfade_follow_frame(), None);
    }
}
