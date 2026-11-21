//! Per-track session bookkeeping + crossfade execution. Split out of
//! `lib.rs` to keep the public engine surface readable.

use crate::{PlaybackEngine, TAG, open_source_for_item};
use renderer::{Renderer, RendererShared};
use ringbuf::HeapRb;
use source::SilenceTimeline;
use ringbuf::traits::Split;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use types::PlayerState;
use util::log_i;

pub struct TrackSession {
    pub duration_ms: u64,
    cancel: Arc<AtomicBool>,
    decoder_done: AtomicBool,
    /// Trimmed↔container map when silence removal is active, so seeks against
    /// the trimmed timeline can be translated back to a container position.
    silence_timeline: Option<Arc<SilenceTimeline>>,
}

impl TrackSession {
    pub fn new(
        cancel: Arc<AtomicBool>,
        duration_ms: u64,
        silence_timeline: Option<Arc<SilenceTimeline>>,
    ) -> Self {
        Self {
            duration_ms,
            cancel,
            decoder_done: AtomicBool::new(false),
            silence_timeline,
        }
    }

    pub fn silence_timeline(&self) -> Option<Arc<SilenceTimeline>> {
        self.silence_timeline.clone()
    }
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Release);
    }
    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }
    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        self.cancel.clone()
    }
    pub fn mark_decoder_done(&self) {
        self.decoder_done.store(true, Ordering::Release);
    }
    pub fn is_decoder_done(&self) -> bool {
        self.decoder_done.load(Ordering::Acquire)
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn trigger_crossfade(
    engine: &PlaybackEngine,
    session: &Arc<TrackSession>,
    renderer: &Arc<Mutex<Renderer>>,
    renderer_shared: &Arc<RendererShared>,
    sample_rate: u32,
    channels: u16,
    cross_start_frame: u64,
    xfade_ms: u64,
) -> bool {
    let _transition = engine.transition.lock().unwrap();
    if session.is_cancelled() || !engine.is_current_session(session) {
        return false;
    }
    log_i!(
        TAG,
        "trigger_crossfade: sr={}, xfade_ms={}",
        sample_rate,
        xfade_ms
    );

    let mode = engine.play_mode();
    let next_item = {
        let mut q = engine.queue.lock().unwrap();
        q.peek_next_entry(mode)
    };
    let Some(next_item) = next_item else {
        log_i!(TAG, "trigger_crossfade: no next, letting track finish");
        return false;
    };

    let (mut source_b, mut meta_b) = match open_source_for_item(&next_item) {
        Ok(v) => v,
        Err(e) => {
            log_i!(TAG, "trigger_crossfade: open_source failed: {:?}", e);
            return false;
        }
    };
    source_b.set_targets(sample_rate, channels);
    meta_b.sample_rate = sample_rate;
    meta_b.channels = channels;

    let silence = engine.settings.read().unwrap().silence;
    let silence_timeline = silence.enabled.then(|| Arc::new(SilenceTimeline::new()));
    if let Some(timeline) = &silence_timeline {
        source_b.set_silence_removal(silence, timeline.clone());
    }

    let cap = (sample_rate as usize)
        .saturating_mul(channels.max(1) as usize)
        .saturating_mul(10)
        .max(1024);
    let (mut prod_b, cons_b) = HeapRb::<f32>::new(cap).split();

    let xfade_cancel = Arc::new(AtomicBool::new(false));
    let new_session = Arc::new(TrackSession::new(
        xfade_cancel.clone(),
        meta_b.duration_ms,
        silence_timeline,
    ));
    let xfade_cancel_dec = xfade_cancel.clone();
    let decode_session = new_session.clone();
    let follow_decoder = thread::spawn(move || {
        let _ = source_b.decode_and_push(&mut prod_b, xfade_cancel_dec);
        decode_session.mark_decoder_done();
    });
    let follow_producer = follow_decoder.thread().clone();

    let cross_frames = (sample_rate as u64 * xfade_ms) / 1000;
    let replay_gain = if engine.settings.read().unwrap().replay_gain_enabled {
        meta_b.replay_gain_db
    } else {
        None
    };
    renderer.lock().unwrap().begin_crossfade(
        cross_start_frame,
        cross_frames,
        cons_b,
        replay_gain,
        follow_producer,
    );

    // Advance queue index NOW (not after xfade completes) so UI and the
    // engine's notion of "current track" stay consistent with the audio.
    // When peek_next was shuffle, this next() call will land on the same
    // precomputed index.
    {
        let mut q = engine.queue.lock().unwrap();
        let _ = q.next_entry(mode);
    }
    *engine.current_metadata.write().unwrap() = Some(meta_b.clone());
    engine
        .shared_state
        .duration_ms
        .store(meta_b.duration_ms, Ordering::Relaxed);
    engine
        .shared_state
        .current_frame
        .store(0, Ordering::Relaxed);
    engine
        .shared_state
        .playback_generation
        .fetch_add(1, Ordering::Release);

    *engine.current_session.lock().unwrap() = Some(new_session.clone());

    let renderer_c = renderer.clone();
    let renderer_shared_c = renderer_shared.clone();
    let engine_handle = engine.arc_self();
    let new_session_c = new_session.clone();
    let old_session = session.clone();
    thread::spawn(move || {
        let crossfade_end = cross_start_frame.saturating_add(cross_frames.max(1));
        loop {
            if new_session_c.is_cancelled() {
                old_session.cancel();
                return;
            }
            // Lock-free reads; do not contend the renderer mutex with the
            // realtime callback during the crossfade.
            let lead_frame = renderer_shared_c.current_frame();
            let follow_frame = renderer_shared_c.crossfade_follow_frame().unwrap_or(0);
            let is_paused = renderer_shared_c.is_paused();
            engine_handle
                .shared_state
                .current_frame
                .store(follow_frame, Ordering::Relaxed);
            engine_handle
                .shared_state
                .is_paused
                .store(is_paused as u32, Ordering::Relaxed);
            if lead_frame >= crossfade_end {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }

        let _transition = engine_handle.transition.lock().unwrap();
        if new_session_c.is_cancelled() || !engine_handle.is_current_session(&new_session_c) {
            old_session.cancel();
            return;
        }
        let (follow_frames, is_paused) = {
            let mut renderer = renderer_c.lock().unwrap();
            let is_paused = renderer.is_paused();
            let Some(follow_frames) = renderer.finish_crossfade(replay_gain) else {
                new_session_c.cancel();
                old_session.cancel();
                return;
            };
            (follow_frames, is_paused)
        };
        old_session.cancel();
        engine_handle
            .shared_state
            .current_frame
            .store(follow_frames, Ordering::Relaxed);
        *engine_handle.state.write().unwrap() = if is_paused {
            PlayerState::Paused
        } else {
            PlayerState::Playing
        };

        engine_handle.spawn_watcher(
            new_session_c.clone(),
            renderer_c,
            renderer_shared_c,
            sample_rate,
            channels,
        );
    });
    true
}
