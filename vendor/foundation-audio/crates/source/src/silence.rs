//! Streaming silence removal for the decode thread.
//!
//! The decoder drops silent audio *before* it reaches the ring buffer, so the
//! rendered frame count is automatically the trimmed timeline — playback
//! position needs no translation. Only two things need the container timeline
//! back: the trimmed duration (known once decoding ends) and seeking, which is
//! what [`SilenceTimeline`] provides.

use std::sync::{Arc, Mutex, PoisonError};

/// Maps the trimmed (output) timeline back to the container timeline so a seek
/// against a silence-trimmed track lands on the right source position.
///
/// Checkpoints are appended by the decode thread as it advances, so the map is
/// exact for everything decoded so far and extrapolates 1:1 beyond it (the
/// silence ahead is not known yet); it sharpens as decoding catches up.
#[derive(Debug, Default)]
pub struct SilenceTimeline {
    /// `(trimmed_frame, container_frame)` checkpoints, strictly increasing.
    points: Mutex<Vec<(u64, u64)>>,
}

impl SilenceTimeline {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Append a checkpoint. Ignored if it does not advance the trimmed timeline.
    pub fn record(&self, trimmed_frame: u64, container_frame: u64) {
        let mut points = self.points.lock().unwrap_or_else(PoisonError::into_inner);
        if points.last().is_some_and(|&(last, _)| trimmed_frame <= last) {
            return;
        }
        points.push((trimmed_frame, container_frame));
    }

    /// Container-timeline frame for a trimmed-timeline frame.
    #[must_use]
    pub fn container_frame_for(&self, trimmed_frame: u64) -> u64 {
        let points = self.points.lock().unwrap_or_else(PoisonError::into_inner);
        if points.is_empty() {
            return trimmed_frame;
        }
        match points.binary_search_by_key(&trimmed_frame, |&(trimmed, _)| trimmed) {
            Ok(index) => points[index].1,
            // Before the first checkpoint: scale from the origin.
            Err(0) => {
                let (trimmed, container) = points[0];
                if trimmed == 0 {
                    container
                } else {
                    (trimmed_frame as u128 * container as u128 / trimmed as u128) as u64
                }
            }
            Err(index) if index < points.len() => {
                let (lo_trimmed, lo_container) = points[index - 1];
                let (hi_trimmed, hi_container) = points[index];
                let progress = trimmed_frame - lo_trimmed;
                ((hi_container - lo_container) * progress)
                    .checked_div(hi_trimmed - lo_trimmed)
                    .map_or(lo_container, |offset| lo_container + offset)
            }
            // Past everything decoded: assume no further silence (1:1).
            Err(_) => {
                let (lo_trimmed, lo_container) = points[points.len() - 1];
                lo_container + (trimmed_frame - lo_trimmed)
            }
        }
    }

    /// Trimmed frames observed so far — the trimmed duration once decoding ends.
    #[must_use]
    pub fn trimmed_frames(&self) -> Option<u64> {
        self.points
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .last()
            .map(|&(trimmed, _)| trimmed)
    }
}

/// Streaming silence filter applied to resampled interleaved f32 frames.
///
/// - Leading silence is dropped until the first audible frame.
/// - A mid-track silent run longer than `min_silence_frames` is collapsed.
/// - Shorter gaps are kept verbatim so natural pauses survive.
/// - Trailing silence is dropped (never flushed at EOF).
pub struct SilenceSkipper {
    threshold: f32,
    min_silence_frames: u64,
    channels: usize,
    /// Silence buffered while we do not yet know if the run is long enough.
    pending: Vec<f32>,
    pending_frames: u64,
    started: bool,
    collapsing: bool,
    in_frames: u64,
    out_frames: u64,
    out_buf: Vec<f32>,
    timeline: Option<Arc<SilenceTimeline>>,
    checkpoint_interval: u64,
    last_checkpoint: u64,
}

impl SilenceSkipper {
    #[must_use]
    pub fn new(threshold_linear: f32, min_silence_frames: u64, channels: usize) -> Self {
        Self {
            threshold: threshold_linear.max(0.0),
            min_silence_frames: min_silence_frames.max(1),
            channels: channels.max(1),
            pending: Vec::new(),
            pending_frames: 0,
            started: false,
            collapsing: false,
            in_frames: 0,
            out_frames: 0,
            out_buf: Vec::new(),
            timeline: None,
            checkpoint_interval: 1,
            last_checkpoint: 0,
        }
    }

    /// Publish `(trimmed, container)` checkpoints roughly every
    /// `checkpoint_interval` output frames so seeks can be mapped back.
    #[must_use]
    pub fn with_timeline(mut self, timeline: Arc<SilenceTimeline>, checkpoint_interval: u64) -> Self {
        self.timeline = Some(timeline);
        self.checkpoint_interval = checkpoint_interval.max(1);
        self
    }

    /// Feed one decoded chunk; audio that survives trimming goes to `emit`.
    /// Returns `false` when `emit` asked to stop.
    pub fn push<F>(&mut self, data: &[f32], emit: &mut F) -> bool
    where
        F: FnMut(&[f32]) -> bool,
    {
        let channels = self.channels;
        self.out_buf.clear();

        for frame in data.chunks(channels) {
            self.in_frames += 1;
            let silent = frame.iter().all(|sample| sample.abs() < self.threshold);
            if silent {
                // Leading silence, or a run we already decided to collapse.
                if !self.started || self.collapsing {
                    continue;
                }
                self.pending.extend_from_slice(frame);
                self.pending_frames += 1;
                if self.pending_frames >= self.min_silence_frames {
                    self.pending.clear();
                    self.pending_frames = 0;
                    self.collapsing = true;
                }
            } else {
                self.started = true;
                self.collapsing = false;
                if self.pending_frames > 0 {
                    // Short gap: a natural pause, keep it.
                    let pending = std::mem::take(&mut self.pending);
                    self.out_buf.extend_from_slice(&pending);
                    self.pending = pending;
                    self.pending.clear();
                    self.pending_frames = 0;
                }
                self.out_buf.extend_from_slice(frame);
            }
        }

        if self.out_buf.is_empty() {
            return true;
        }
        self.out_frames += (self.out_buf.len() / channels) as u64;
        self.checkpoint();
        emit(&self.out_buf)
    }

    /// Trailing silence is dropped, so nothing is flushed; records a final
    /// checkpoint so the trimmed duration is exact.
    pub fn finish(&mut self) {
        self.pending.clear();
        self.pending_frames = 0;
        if let Some(timeline) = &self.timeline {
            timeline.record(self.out_frames, self.in_frames);
        }
    }

    fn checkpoint(&mut self) {
        if self.out_frames.saturating_sub(self.last_checkpoint) < self.checkpoint_interval {
            return;
        }
        if let Some(timeline) = &self.timeline {
            timeline.record(self.out_frames, self.in_frames);
        }
        self.last_checkpoint = self.out_frames;
    }

    /// Frames seen (the container timeline, at the output sample rate).
    #[must_use]
    pub fn in_frames(&self) -> u64 {
        self.in_frames
    }

    /// Frames emitted (the trimmed timeline).
    #[must_use]
    pub fn out_frames(&self) -> u64 {
        self.out_frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Collect everything a skipper emits for one mono input.
    fn run(input: &[f32], min_silence_frames: u64) -> (Vec<f32>, SilenceSkipper) {
        let mut skipper = SilenceSkipper::new(0.01, min_silence_frames, 1);
        let mut out = Vec::new();
        skipper.push(input, &mut |chunk: &[f32]| {
            out.extend_from_slice(chunk);
            true
        });
        skipper.finish();
        (out, skipper)
    }

    #[test]
    fn trims_ends_keeps_short_gaps_and_collapses_long_ones() {
        // leading(6) · tone · short gap(2) · tone · long gap(6) · tone · trailing(3)
        let input = [
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, // leading silence -> trimmed
            1.0, 1.0, // audible
            0.0, 0.0, // short gap (< 4) -> kept
            1.0, 1.0, // audible
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, // long gap (>= 4) -> collapsed
            1.0, 1.0, // audible
            0.0, 0.0, 0.0, // trailing silence -> trimmed
        ];
        let (out, skipper) = run(&input, 4);
        assert_eq!(out, vec![1.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0]);
        assert_eq!(skipper.in_frames(), 23);
        assert_eq!(skipper.out_frames(), 8);
    }

    #[test]
    fn all_silence_produces_nothing() {
        let (out, skipper) = run(&[0.0; 32], 4);
        assert!(out.is_empty());
        assert_eq!(skipper.out_frames(), 0);
    }

    #[test]
    fn audio_without_silence_passes_through_untouched() {
        let input: Vec<f32> = (0..16).map(|i| 0.5 + i as f32 * 0.01).collect();
        let (out, skipper) = run(&input, 4);
        assert_eq!(out, input);
        assert_eq!(skipper.out_frames(), 16);
    }

    #[test]
    fn timeline_maps_trimmed_frames_back_to_container_frames() {
        let timeline = SilenceTimeline::new();
        // 100 trimmed frames correspond to 100 container frames, then a 400
        // frame silence is skipped: trimmed 200 -> container 600.
        timeline.record(100, 100);
        timeline.record(200, 600);

        assert_eq!(timeline.container_frame_for(100), 100);
        assert_eq!(timeline.container_frame_for(200), 600);
        // Interpolated inside the mapped span.
        assert_eq!(timeline.container_frame_for(150), 350);
        // Past the decoded region: assume no further silence (1:1).
        assert_eq!(timeline.container_frame_for(250), 650);
        assert_eq!(timeline.trimmed_frames(), Some(200));
    }

    #[test]
    fn empty_timeline_is_identity() {
        let timeline = SilenceTimeline::new();
        assert_eq!(timeline.container_frame_for(1234), 1234);
        assert_eq!(timeline.trimmed_frames(), None);
    }
}
