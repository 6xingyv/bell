use ringbuf::HeapCons;
use ringbuf::traits::Consumer;
use std::sync::LazyLock;

/// Resolution of the equal-power crossfade gain table. 1024 steps over a
/// multi-second fade is far below any audible stepping, and with linear
/// interpolation the error versus exact `sqrt` is < 1e-4.
const CROSSFADE_GAIN_LUT_SIZE: usize = 1024;

/// Equal-power (constant-power) crossfade gain curve `sqrt(x)` on `[0, 1]`,
/// precomputed once. The audio callback then does a table lookup + lerp per
/// frame instead of two `sqrt` per sample.
static EQUAL_POWER_GAIN: LazyLock<[f32; CROSSFADE_GAIN_LUT_SIZE + 1]> =
    LazyLock::new(|| std::array::from_fn(|i| (i as f32 / CROSSFADE_GAIN_LUT_SIZE as f32).sqrt()));

#[inline]
fn equal_power_gain(fraction: f32) -> f32 {
    let position = fraction.clamp(0.0, 1.0) * CROSSFADE_GAIN_LUT_SIZE as f32;
    let index = position as usize;
    let frac = position - index as f32;
    let lo = EQUAL_POWER_GAIN[index];
    let hi = EQUAL_POWER_GAIN[(index + 1).min(CROSSFADE_GAIN_LUT_SIZE)];
    lo + (hi - lo) * frac
}

pub(super) trait AudioRecipe: Send {
    /// Processes a hardware buffer and returns how many samples the recipe
    /// supplied independently of the lead track.
    fn process(&mut self, buffer: &mut [f32], current_frame: u64, channels: u16) -> usize;
    fn take_follow_consumer(&mut self) -> Option<HeapCons<f32>> {
        None
    }
    fn is_crossfade(&self) -> bool {
        false
    }
    fn follow_frames_consumed(&self) -> u64 {
        0
    }
}

pub(super) struct ReplayGainRecipe {
    scale: f32,
}

impl ReplayGainRecipe {
    pub(super) fn new(gain_db: f32) -> Self {
        let gain_db = if gain_db.is_finite() {
            gain_db.clamp(-60.0, 24.0)
        } else {
            0.0
        };
        Self {
            scale: 10.0_f32.powf(gain_db / 20.0),
        }
    }
}

impl AudioRecipe for ReplayGainRecipe {
    fn process(&mut self, buffer: &mut [f32], _current_frame: u64, _channels: u16) -> usize {
        for sample in buffer.iter_mut() {
            *sample *= self.scale;
        }
        0
    }
}

pub(super) struct CrossfadeRecipe {
    start_frame: u64,
    duration_frames: u64,
    follow_consumer: Option<HeapCons<f32>>,
    scratch_buffer: Vec<f32>,
    follow_scale: f32,
    follow_frames_consumed: u64,
}

impl CrossfadeRecipe {
    #[cfg(test)]
    pub(super) fn new(
        start_frame: u64,
        duration_frames: u64,
        follow_consumer: HeapCons<f32>,
    ) -> Self {
        Self::with_follow_gain(start_frame, duration_frames, follow_consumer, None)
    }

    pub(super) fn with_follow_gain(
        start_frame: u64,
        duration_frames: u64,
        follow_consumer: HeapCons<f32>,
        follow_gain_db: Option<f32>,
    ) -> Self {
        let follow_scale = ReplayGainRecipe::new(follow_gain_db.unwrap_or(0.0)).scale;
        Self {
            start_frame,
            duration_frames: duration_frames.max(1),
            follow_consumer: Some(follow_consumer),
            scratch_buffer: Vec::with_capacity(4096),
            follow_scale,
            follow_frames_consumed: 0,
        }
    }
}

impl AudioRecipe for CrossfadeRecipe {
    fn process(&mut self, buffer: &mut [f32], current_frame: u64, channels: u16) -> usize {
        let channels = channels.max(1) as usize;
        let len = buffer.len();
        if self.scratch_buffer.len() < len {
            self.scratch_buffer.resize(len, 0.0);
        }

        self.scratch_buffer.fill(0.0);
        let start_sample = self
            .start_frame
            .saturating_sub(current_frame)
            .saturating_mul(channels as u64)
            .min(len as u64) as usize;
        let follow_read = if let Some(cons) = &mut self.follow_consumer {
            cons.pop_slice(&mut self.scratch_buffer[start_sample..len])
        } else {
            0
        };

        self.follow_frames_consumed += (follow_read / channels) as u64;

        // `fade_factor` and both gains change only per frame, so compute them
        // once per frame (via the precomputed equal-power table) and reuse them
        // across the frame's channels instead of two `sqrt` per sample.
        let follow_end = start_sample + follow_read;
        for (frame, chunk) in buffer.chunks_mut(channels).enumerate() {
            let frame_idx = current_frame + frame as u64;

            let fade_factor = if frame_idx < self.start_frame {
                0.0
            } else if frame_idx >= self.start_frame + self.duration_frames {
                1.0
            } else {
                (frame_idx - self.start_frame) as f32 / self.duration_frames as f32
            };

            let lead_gain = equal_power_gain(1.0 - fade_factor);
            let follow_gain = equal_power_gain(fade_factor);

            let base = frame * channels;
            for (channel, sample) in chunk.iter_mut().enumerate() {
                let i = base + channel;
                // Outside the follow window scratch_buffer[i] is silence (filled).
                let follow_part = if i >= start_sample && i < follow_end {
                    self.scratch_buffer[i] * self.follow_scale * follow_gain
                } else {
                    0.0
                };
                *sample = *sample * lead_gain + follow_part;
            }
        }

        if follow_read == 0 {
            0
        } else {
            start_sample + follow_read
        }
    }

    fn take_follow_consumer(&mut self) -> Option<HeapCons<f32>> {
        self.follow_consumer.take()
    }

    fn is_crossfade(&self) -> bool {
        true
    }

    fn follow_frames_consumed(&self) -> u64 {
        self.follow_frames_consumed
    }
}

/// A linear gain ramp applied to the final mix — play/pause fades (default
/// 100 ms) and post-seek fade-in (default 50 ms), to avoid the click a hard
/// mute/unmute or a seek discontinuity produces.
///
/// Held as a first-class step on the `Renderer` (not in the generic recipe
/// chain) because a fade-out also drives the auto-pause once it reaches silence.
pub(super) struct FadeRecipe {
    gain: f32,
    target: f32,
    step: f32,
    pause_on_complete: bool,
}

impl FadeRecipe {
    /// Ramp `from` → `to` over `duration_frames`. When `pause_on_complete`, the
    /// renderer pauses once the ramp reaches its target (a fade-out to silence).
    pub(super) fn ramp(from: f32, to: f32, duration_frames: u64, pause_on_complete: bool) -> Self {
        let frames = duration_frames.max(1) as f32;
        Self {
            gain: from,
            target: to,
            step: (to - from) / frames,
            pause_on_complete,
        }
    }

    pub(super) fn current_gain(&self) -> f32 {
        self.gain
    }

    pub(super) fn pause_on_complete(&self) -> bool {
        self.pause_on_complete
    }

    /// Multiply one output buffer by the advancing ramp. Returns `true` once the
    /// target gain has been reached (the fade is finished).
    pub(super) fn apply(&mut self, buffer: &mut [f32], channels: u16) -> bool {
        let channels = channels.max(1) as usize;
        for frame in buffer.chunks_mut(channels) {
            for sample in frame.iter_mut() {
                *sample *= self.gain;
            }
            self.gain += self.step;
            if (self.step >= 0.0 && self.gain >= self.target)
                || (self.step <= 0.0 && self.gain <= self.target)
            {
                self.gain = self.target;
                self.step = 0.0;
            }
        }
        self.step == 0.0
    }
}

#[cfg(test)]
mod tests {
    use super::{FadeRecipe, equal_power_gain};

    #[test]
    fn fade_in_ramps_up_and_reports_incomplete_mid_fade() {
        let mut fade = FadeRecipe::ramp(0.0, 1.0, 8, false);
        let mut buffer = [1.0f32; 8]; // 4 stereo frames
        let done = fade.apply(&mut buffer, 2);
        assert_eq!(buffer, [0.0, 0.0, 0.125, 0.125, 0.25, 0.25, 0.375, 0.375]);
        assert!(!done);
        assert_eq!(fade.current_gain(), 0.5);
    }

    #[test]
    fn fade_out_reaches_zero_and_requests_pause() {
        let mut fade = FadeRecipe::ramp(1.0, 0.0, 2, true);
        let mut buffer = [1.0f32; 6]; // 3 stereo frames
        let done = fade.apply(&mut buffer, 2);
        assert_eq!(buffer, [1.0, 1.0, 0.5, 0.5, 0.0, 0.0]);
        assert!(done);
        assert!(fade.pause_on_complete());
    }

    #[test]
    fn equal_power_gain_table_tracks_sqrt_and_stays_constant_power() {
        // Endpoints are exact table entries.
        assert_eq!(equal_power_gain(0.0), 0.0);
        assert_eq!(equal_power_gain(1.0), 1.0);
        // Interpolated values track sqrt closely, and lead²+follow² ≈ 1 across
        // the fade (the point of the equal-power curve).
        for i in 0..=100 {
            let f = i as f32 / 100.0;
            assert!((equal_power_gain(f) - f.sqrt()).abs() < 1e-3, "f={f}");
            let lead = equal_power_gain(1.0 - f);
            let follow = equal_power_gain(f);
            assert!((lead * lead + follow * follow - 1.0).abs() < 3e-3, "f={f}");
        }
    }
}
