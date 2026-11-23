use super::{PlaybackEngine, TAG, TrackSession};
use queue::QueueEntry;
use renderer::AudioOutput;
use ringbuf::HeapRb;
use ringbuf::traits::Split;
use source::{AudioSource, SilenceTimeline, StreamOpenParams};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use types::{PlayerState, TrackMetadata};
use util::fd_io::{close_fd, duplicate_fd};
use util::log_i;
use util::stream_io::MediaByteSource;

impl PlaybackEngine {
    pub fn pause(&self) {
        let _transition = self.transition.lock().unwrap();
        if !matches!(self.state(), PlayerState::Playing | PlayerState::Buffering) {
            return;
        }
        if let Some(out) = self.output.lock().unwrap().as_ref() {
            let frames = fade_frames(out.sample_rate(), PAUSE_FADE_MS);
            let renderer = out.renderer();
            if let Ok(mut renderer) = renderer.lock() {
                // Ramp down over ~100 ms and auto-pause at silence (no click).
                renderer.fade_out_and_pause(frames);
            }
        }
        *self.state.write().unwrap() = PlayerState::Paused;
        self.shared_state.is_paused.store(1, Ordering::Relaxed);
    }

    pub fn resume(&self) {
        let _transition = self.transition.lock().unwrap();
        if self.state() != PlayerState::Paused || self.current_session.lock().unwrap().is_none() {
            return;
        }
        if let Some(out) = self.output.lock().unwrap().as_ref() {
            let frames = fade_frames(out.sample_rate(), PAUSE_FADE_MS);
            let renderer = out.renderer();
            if let Ok(mut renderer) = renderer.lock() {
                // Un-pause and ramp back up from the current gain (no click).
                renderer.resume_faded(frames);
            }
        }
        *self.state.write().unwrap() = PlayerState::Playing;
        self.shared_state.is_paused.store(0, Ordering::Relaxed);
    }

    pub fn stop(&self) {
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
    }

    pub(super) fn stop_locked(&self) {
        self.cancel_current_session();
        if let Some(out) = self.output.lock().unwrap().as_ref() {
            let renderer = out.renderer();
            if let Ok(mut renderer) = renderer.lock() {
                renderer.stop();
            }
        }
        *self.state.write().unwrap() = PlayerState::Stopped;
        *self.current_metadata.write().unwrap() = None;
        self.shared_state.is_paused.store(1, Ordering::Relaxed);
        self.shared_state.current_frame.store(0, Ordering::Relaxed);
        self.shared_state.duration_ms.store(0, Ordering::Relaxed);
    }

    pub fn is_playing(&self) -> bool {
        matches!(self.state(), PlayerState::Playing)
    }

    /// Seek to absolute position in ms on the current track. Tears down
    /// and rebuilds the decoder thread.
    pub fn seek(&self, position_ms: u64) -> bool {
        let _transition = self.transition.lock().unwrap();
        let item = match self.queue.lock().unwrap().current_entry() {
            Some(it) => it,
            None => return false,
        };
        let duration_ms = self
            .current_metadata()
            .map(|metadata| metadata.duration_ms)
            .unwrap_or(0);
        let position_ms = if duration_ms > 0 {
            position_ms.min(duration_ms)
        } else {
            position_ms
        };
        // With silence removal the UI timeline is the trimmed one; translate it
        // back to the container position the decoder has to seek to.
        let container_ms = self.container_ms_for(position_ms);
        let display_ms = (container_ms != position_ms).then_some(position_ms);
        self.start_playback_at_locked(item, container_ms, display_ms)
    }

    /// Rebase the playback position after an out-of-band seek on a progressive
    /// stream (the fabric source repositions the stream itself; this engine
    /// cannot). Drops the buffered pre-seek audio and jumps the frame counter so
    /// reported position matches the content that is about to arrive. Returns
    /// false when nothing is playing.
    pub fn rebase_stream_position(&self, position_ms: u64) -> bool {
        let _transition = self.transition.lock().unwrap();
        if self.current_session.lock().unwrap().is_none() {
            return false;
        }
        let Some(out) = self.output.lock().unwrap().as_ref().map(|out| {
            (out.renderer(), out.sample_rate())
        }) else {
            return false;
        };
        let (renderer, sample_rate) = out;
        let frame = position_ms.saturating_mul(u64::from(sample_rate)) / 1000;
        renderer
            .lock()
            .unwrap()
            .rebase_lead(frame, fade_frames(sample_rate, SEEK_FADE_MS));
        self.shared_state.current_frame.store(frame, Ordering::Relaxed);
        true
    }

    /// Translate a trimmed-timeline position into a container position using the
    /// current track's silence map. Identity when silence removal is off.
    fn container_ms_for(&self, trimmed_ms: u64) -> u64 {
        let session = self.current_session.lock().unwrap().clone();
        let Some(timeline) = session.and_then(|session| session.silence_timeline()) else {
            return trimmed_ms;
        };
        let sample_rate = u64::from(self.shared_state.sample_rate.load(Ordering::Relaxed).max(1));
        let trimmed_frame = trimmed_ms.saturating_mul(sample_rate) / 1000;
        timeline
            .container_frame_for(trimmed_frame)
            .saturating_mul(1000)
            / sample_rate
    }

    pub fn play_path(&self, path: String) -> bool {
        if path.trim().is_empty() {
            return false;
        }
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
        self.clear_queue_locked();
        self.queue.lock().unwrap().add_path(path.clone());
        self.queue.lock().unwrap().set_current_index(0);
        let entry = self.queue.lock().unwrap().current_entry().unwrap();
        self.start_playback_locked(entry)
    }

    pub fn play_fd(&self, fd: i32) -> bool {
        let Ok(duped) = duplicate_fd(fd) else {
            return false;
        };
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
        self.clear_queue_locked();
        {
            let mut q = self.queue.lock().unwrap();
            q.add_fd(duped);
            q.set_current_index(0);
        }
        let entry = self.queue.lock().unwrap().current_entry().unwrap();
        self.start_playback_locked(entry)
    }

    /// Play compressed media from a progressive byte stream (fabric remote).
    ///
    /// `overlay` carries application/protocol metadata (title, artist, album,
    /// cover, lyrics, …). Container probe still supplies technical fields when
    /// the overlay leaves them empty. Playback does not require a local fd.
    /// `start_position_ms` lets a fabric source hand over at its own playhead
    /// instead of restarting the track on the renderer.
    pub fn play_media_stream(
        &self,
        source: Box<dyn MediaByteSource>,
        overlay: Option<TrackMetadata>,
        start_paused: bool,
        start_position_ms: u64,
    ) -> bool {
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
        self.clear_queue_locked();

        let (media, mut metadata) = match AudioSource::open_stream(source) {
            Ok(value) => value,
            Err(error) => {
                log_i!(TAG, "play_media_stream: open failed: {:?}", error);
                return false;
            }
        };
        if let Some(overlay) = overlay {
            metadata = merge_track_metadata(metadata, overlay);
        }
        self.start_open_source_locked(media, metadata, start_position_ms, start_paused, true)
    }

    /// Zero-probe variant of [`play_media_stream`](Self::play_media_stream): the
    /// caller passes the exact container/codec parameters announced by the
    /// source so FFmpeg starts without a blind stream probe (task 1).
    pub fn play_media_stream_with_params(
        &self,
        source: Box<dyn MediaByteSource>,
        params: &StreamOpenParams,
        overlay: Option<TrackMetadata>,
        start_paused: bool,
        start_position_ms: u64,
    ) -> bool {
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
        self.clear_queue_locked();

        let (media, mut metadata) = match AudioSource::open_stream_with_params(source, params) {
            Ok(value) => value,
            Err(error) => {
                log_i!(TAG, "play_media_stream_with_params: open failed: {:?}", error);
                return false;
            }
        };
        if let Some(overlay) = overlay {
            metadata = merge_track_metadata(metadata, overlay);
        }
        self.start_open_source_locked(media, metadata, start_position_ms, start_paused, true)
    }

    fn start_open_source_locked(
        &self,
        mut source: AudioSource,
        mut metadata: TrackMetadata,
        seek_ms: u64,
        preserve_paused: bool,
        new_logical_play: bool,
    ) -> bool {
        let preferred_rate = (metadata.sample_rate > 0).then_some(metadata.sample_rate);
        let mut replacement = None;
        let mut out_guard = self.output.lock().unwrap();
        if out_guard.is_none() {
            match AudioOutput::new(preferred_rate, self.shared_state.clone()) {
                Ok(output) => replacement = Some(output),
                Err(error) => {
                    log_i!(TAG, "AudioOutput::new failed: {:?}", error);
                    return false;
                }
            }
        }
        let out = replacement
            .as_mut()
            .or_else(|| out_guard.as_mut())
            .expect("audio output must be available");
        let sample_rate = out.sample_rate();
        let channels = out.channels();
        metadata.sample_rate = sample_rate;
        metadata.channels = channels;

        // Targets first: the progressive-stream fallback below counts output
        // frames, so it needs the resampler configuration.
        source.set_targets(sample_rate, channels);
        if seek_ms > 0 && source.seek_to_ms(seek_ms).is_err() {
            // Fabric media streams have no seek callback. Decode-and-discard is
            // the only way to honour a start offset there, and it is what makes
            // "cast from where this device is playing" work at all.
            match source.skip_forward_ms(seek_ms) {
                Ok(reached) => log_i!(TAG, "start_open_source: skipped forward to {}ms", reached),
                Err(error) => {
                    log_i!(TAG, "start_open_source: seek and skip failed: {:?}", error);
                    return false;
                }
            }
        }

        // Decode-side silence removal (opt-in). Only audible audio reaches the
        // ring buffer, so the rendered frame count is already the trimmed
        // timeline; the map lets seeks translate back to container positions.
        let silence = self.settings.read().unwrap().silence;
        let silence_timeline = silence.enabled.then(|| Arc::new(SilenceTimeline::new()));
        if let Some(timeline) = &silence_timeline {
            source.set_silence_removal(silence, timeline.clone());
        }

        let cap = playback_buffer_capacity(sample_rate, channels);
        let (mut prod, cons) = HeapRb::<f32>::new(cap).split();
        let cancel = Arc::new(AtomicBool::new(false));
        let session = Arc::new(TrackSession::new(
            cancel,
            metadata.duration_ms,
            silence_timeline.clone(),
        ));
        let replay_gain = if self.settings.read().unwrap().replay_gain_enabled {
            metadata.replay_gain_db
        } else {
            None
        };
        let renderer = out.renderer();
        let renderer_shared = out.renderer_shared();
        renderer.lock().unwrap().begin_track(
            cons,
            (seek_ms * sample_rate as u64) / 1000,
            replay_gain,
            0, // fresh/stream start: no seek fade
        );
        if preserve_paused {
            renderer.lock().unwrap().set_paused(true);
        }

        let cancel_dec = session.cancel_handle();
        let decode_session = session.clone();
        let decode_shared = self.shared_state.clone();
        let decode_timeline = silence_timeline.clone();
        let decoder = thread::spawn(move || {
            let _ = source.decode_and_push(&mut prod, cancel_dec);
            // With silence trimmed away the real length is only known now, so
            // publish the trimmed duration instead of the container's.
            if let Some(timeline) = &decode_timeline
                && !decode_session.is_cancelled()
                && let Some(frames) = timeline.trimmed_frames()
            {
                let trimmed_ms = frames.saturating_mul(1000) / u64::from(sample_rate.max(1));
                decode_shared
                    .duration_ms
                    .store(trimmed_ms, Ordering::Relaxed);
            }
            decode_session.mark_decoder_done();
        });
        // Let the audio callback wake this decoder when it frees ring space (task 5).
        renderer
            .lock()
            .unwrap()
            .set_producer_thread(Some(decoder.thread().clone()));

        self.cancel_current_session();
        if let Some(output) = replacement.take() {
            *out_guard = Some(output);
        }
        if !preserve_paused {
            renderer.lock().unwrap().set_paused(false);
        }

        *self.current_metadata.write().unwrap() = Some(metadata.clone());
        *self.state.write().unwrap() = if preserve_paused {
            PlayerState::Paused
        } else {
            PlayerState::Playing
        };
        self.shared_state
            .sample_rate
            .store(sample_rate, Ordering::Relaxed);
        self.shared_state
            .duration_ms
            .store(metadata.duration_ms, Ordering::Relaxed);
        self.shared_state
            .is_paused
            .store(preserve_paused as u32, Ordering::Relaxed);
        self.shared_state
            .current_frame
            .store((seek_ms * sample_rate as u64) / 1000, Ordering::Relaxed);
        if new_logical_play {
            self.shared_state
                .playback_generation
                .fetch_add(1, Ordering::Release);
        }
        *self.current_session.lock().unwrap() = Some(session.clone());
        drop(out_guard);
        self.spawn_watcher(session, renderer, renderer_shared, sample_rate, channels);
        true
    }

    pub(super) fn start_playback_locked(&self, item: QueueEntry) -> bool {
        self.start_playback_at_locked_with_output(item, 0, false, false, true, 0, None)
    }

    /// `container_ms` addresses the source; `display_ms` is the position on the
    /// trimmed timeline when silence removal makes the two differ.
    pub(super) fn start_playback_at_locked(
        &self,
        item: QueueEntry,
        container_ms: u64,
        display_ms: Option<u64>,
    ) -> bool {
        // Fade the audio back in after the seek discontinuity (default 50 ms).
        self.start_playback_at_locked_with_output(
            item,
            container_ms,
            false,
            false,
            false,
            SEEK_FADE_MS,
            display_ms,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn start_playback_at_locked_with_output(
        &self,
        item: QueueEntry,
        seek_ms: u64,
        rebuild_output: bool,
        preserve_paused: bool,
        new_logical_play: bool,
        fade_in_ms: u32,
        // Position to show on the (trimmed) playback timeline when it differs
        // from `seek_ms`, which addresses the container.
        display_ms: Option<u64>,
    ) -> bool {
        log_i!(
            TAG,
            "start_playback_at: item={:?}, seek_ms={}",
            item.item(),
            seek_ms
        );

        let (mut source, mut metadata) = match open_source_for_item(&item) {
            Ok(v) => v,
            Err(e) => {
                log_i!(TAG, "start_playback_at: open failed: {:?}", e);
                return false;
            }
        };

        let preferred_rate = (metadata.sample_rate > 0).then_some(metadata.sample_rate);
        // Open a replacement before disturbing the active stream. If opening
        // the new default route fails, the current playback remains intact and
        // the monitor retries on its next pass.
        let mut replacement = if rebuild_output {
            match AudioOutput::new(preferred_rate, self.shared_state.clone()) {
                Ok(output) => Some(output),
                Err(error) => {
                    log_i!(TAG, "replacement AudioOutput::new failed: {:?}", error);
                    return false;
                }
            }
        } else {
            None
        };

        let mut out_guard = self.output.lock().unwrap();
        if out_guard.is_none() && replacement.is_none() {
            match AudioOutput::new(preferred_rate, self.shared_state.clone()) {
                Ok(output) => replacement = Some(output),
                Err(error) => {
                    log_i!(TAG, "AudioOutput::new failed: {:?}", error);
                    return false;
                }
            }
        }
        let replacing_output = replacement.is_some();
        let out = replacement
            .as_mut()
            .or_else(|| out_guard.as_mut())
            .expect("audio output must be available");
        let sample_rate = out.sample_rate();
        let channels = out.channels();
        metadata.sample_rate = sample_rate;
        metadata.channels = channels;

        if seek_ms > 0 && source.seek_to_ms(seek_ms).is_err() {
            log_i!(TAG, "start_playback_at: seek failed");
            return false;
        }
        source.set_targets(sample_rate, channels);

        // Decode-side silence removal (opt-in). Only audible audio reaches the
        // ring buffer, so the rendered frame count is already the trimmed
        // timeline; the map lets seeks translate back to container positions.
        let silence = self.settings.read().unwrap().silence;
        let silence_timeline = silence.enabled.then(|| Arc::new(SilenceTimeline::new()));
        if let Some(timeline) = &silence_timeline {
            source.set_silence_removal(silence, timeline.clone());
        }

        let cap = playback_buffer_capacity(sample_rate, channels);
        let (mut prod, cons) = HeapRb::<f32>::new(cap).split();

        let cancel = Arc::new(AtomicBool::new(false));
        let session = Arc::new(TrackSession::new(
            cancel,
            metadata.duration_ms,
            silence_timeline.clone(),
        ));

        let replay_gain = if self.settings.read().unwrap().replay_gain_enabled {
            metadata.replay_gain_db
        } else {
            None
        };
        let renderer = out.renderer();
        let renderer_shared = out.renderer_shared();
        renderer.lock().unwrap().begin_track(
            cons,
            (display_ms.unwrap_or(seek_ms) * sample_rate as u64) / 1000,
            replay_gain,
            fade_frames(sample_rate, fade_in_ms),
        );
        if replacing_output || preserve_paused {
            // A newly opened stream starts immediately. Hold it silent until
            // the old stream has been cancelled and dropped.
            renderer.lock().unwrap().set_paused(true);
        }

        let cancel_dec = session.cancel_handle();
        let decode_session = session.clone();
        let decode_shared = self.shared_state.clone();
        let decode_timeline = silence_timeline.clone();
        let decoder = thread::spawn(move || {
            let _ = source.decode_and_push(&mut prod, cancel_dec);
            // With silence trimmed away the real length is only known now, so
            // publish the trimmed duration instead of the container's.
            if let Some(timeline) = &decode_timeline
                && !decode_session.is_cancelled()
                && let Some(frames) = timeline.trimmed_frames()
            {
                let trimmed_ms = frames.saturating_mul(1000) / u64::from(sample_rate.max(1));
                decode_shared
                    .duration_ms
                    .store(trimmed_ms, Ordering::Relaxed);
            }
            decode_session.mark_decoder_done();
        });
        // Let the audio callback wake this decoder when it frees ring space (task 5).
        renderer
            .lock()
            .unwrap()
            .set_producer_thread(Some(decoder.thread().clone()));

        // Keep the old track and output alive until their replacements have
        // opened successfully. Failed route switches therefore do not stop
        // playback.
        self.cancel_current_session();
        if let Some(output) = replacement.take() {
            *out_guard = Some(output);
        }
        if !preserve_paused {
            renderer.lock().unwrap().set_paused(false);
        }

        *self.current_metadata.write().unwrap() = Some(metadata.clone());
        *self.state.write().unwrap() = if preserve_paused {
            PlayerState::Paused
        } else {
            PlayerState::Playing
        };

        self.shared_state
            .sample_rate
            .store(sample_rate, Ordering::Relaxed);
        self.shared_state
            .duration_ms
            .store(metadata.duration_ms, Ordering::Relaxed);
        self.shared_state
            .is_paused
            .store(preserve_paused as u32, Ordering::Relaxed);
        self.shared_state
            .current_frame
            .store((seek_ms * sample_rate as u64) / 1000, Ordering::Relaxed);
        if new_logical_play {
            self.shared_state
                .playback_generation
                .fetch_add(1, Ordering::Release);
        }

        *self.current_session.lock().unwrap() = Some(session.clone());

        drop(out_guard);

        self.spawn_watcher(session, renderer, renderer_shared, sample_rate, channels);
        true
    }
}

impl Drop for PlaybackEngine {
    fn drop(&mut self) {
        if let Some(session) = self
            .current_session
            .get_mut()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take()
        {
            session.cancel();
        }
        let queue = self
            .queue
            .get_mut()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        for item in queue.drain() {
            if let Some(fd) = item.as_fd() {
                close_fd(fd);
            }
        }
    }
}

pub(crate) fn open_source_for_item(
    entry: &QueueEntry,
) -> Result<(AudioSource, TrackMetadata), ffmpeg_next::Error> {
    let item = entry.item();
    let (source, mut metadata) = if let Some(path) = item.as_path() {
        AudioSource::open_path(path)?
    } else if let Some(fd) = item.as_fd() {
        AudioSource::open_fd(fd)?
    } else {
        return Err(ffmpeg_next::Error::InvalidData);
    };
    if entry.has_replay_gain_override() {
        metadata.replay_gain_db = entry.replay_gain_db();
    }
    Ok((source, metadata))
}

/// Default play/pause gain-fade duration.
const PAUSE_FADE_MS: u32 = 100;
/// Default post-seek fade-in duration.
const SEEK_FADE_MS: u32 = 50;

/// Convert a fade duration in milliseconds to output frames.
fn fade_frames(sample_rate: u32, ms: u32) -> u64 {
    (u64::from(sample_rate) * u64::from(ms)) / 1000
}

fn playback_buffer_capacity(sample_rate: u32, channels: u16) -> usize {
    (sample_rate as usize)
        .saturating_mul(channels.max(1) as usize)
        .saturating_mul(10)
        .max(1024)
}

/// Prefer explicit protocol/application metadata for presentation fields while
/// keeping technical probe data when the overlay omits it.
pub(crate) fn merge_track_metadata(
    mut base: TrackMetadata,
    overlay: TrackMetadata,
) -> TrackMetadata {
    if overlay.title.is_some() {
        base.title = overlay.title;
    }
    if overlay.artist.is_some() {
        base.artist = overlay.artist;
    }
    if overlay.album.is_some() {
        base.album = overlay.album;
    }
    if overlay.album_artist.is_some() {
        base.album_artist = overlay.album_artist;
    }
    if overlay.genre.is_some() {
        base.genre = overlay.genre;
    }
    if overlay.year.is_some() {
        base.year = overlay.year;
    }
    if overlay.track_number.is_some() {
        base.track_number = overlay.track_number;
    }
    if overlay.disc_number.is_some() {
        base.disc_number = overlay.disc_number;
    }
    if overlay.duration_ms > 0 {
        base.duration_ms = overlay.duration_ms;
    }
    if overlay.sample_rate > 0 {
        base.sample_rate = overlay.sample_rate;
    }
    if overlay.channels > 0 {
        base.channels = overlay.channels;
    }
    if overlay.bit_rate > 0 {
        base.bit_rate = overlay.bit_rate;
    }
    if overlay.replay_gain_db.is_some() {
        base.replay_gain_db = overlay.replay_gain_db;
    }
    if overlay.cover_art.is_some() {
        base.cover_art = overlay.cover_art;
    }
    if overlay.lyrics.is_some() {
        base.lyrics = overlay.lyrics;
    }
    base
}
