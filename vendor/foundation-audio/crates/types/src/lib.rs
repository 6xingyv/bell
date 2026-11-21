use std::sync::atomic::{AtomicU32, AtomicU64};

#[derive(Debug, Clone)]
pub enum QueueItem {
    Path(String),
    Fd(i32),
}

impl QueueItem {
    pub fn path(path: String) -> Self {
        Self::Path(path)
    }

    pub fn fd(fd: i32) -> Self {
        Self::Fd(fd)
    }

    pub fn is_path(&self) -> bool {
        matches!(self, Self::Path(_))
    }

    pub fn is_fd(&self) -> bool {
        matches!(self, Self::Fd(_))
    }

    pub fn as_path(&self) -> Option<&str> {
        match self {
            Self::Path(path) => Some(path),
            Self::Fd(_) => None,
        }
    }

    pub fn as_fd(&self) -> Option<i32> {
        match self {
            Self::Fd(fd) => Some(*fd),
            Self::Path(_) => None,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct TrackMetadata {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub genre: Option<String>,
    pub year: Option<i32>,
    pub track_number: Option<i32>,
    pub disc_number: Option<i32>,
    pub duration_ms: u64,
    pub sample_rate: u32,
    pub channels: u16,
    pub bit_rate: u32,
    pub replay_gain_db: Option<f32>,
    pub cover_art: Option<Vec<u8>>,
    pub lyrics: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayerState {
    Stopped,
    Playing,
    Paused,
    Buffering,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum PlayMode {
    None = 0,
    ListLoop = 1,
    SingleLoop = 2,
    Shuffle = 3,
}

impl PlayMode {
    pub const fn as_i32(self) -> i32 {
        self as i32
    }
}

impl TryFrom<i32> for PlayMode {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::None),
            1 => Ok(Self::ListLoop),
            2 => Ok(Self::SingleLoop),
            3 => Ok(Self::Shuffle),
            _ => Err(()),
        }
    }
}

pub const MAX_CROSSFADE_DURATION_MS: u32 = 60_000;

/// Silence removal ("skip silence") configuration.
///
/// Leading and trailing silence are trimmed outright; a mid-track silent run
/// longer than [`min_silence_ms`](Self::min_silence_ms) is collapsed, while
/// shorter gaps are kept so natural pauses survive. Off by default — when
/// disabled the decode path is byte-identical to plain playback.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SilenceSettings {
    pub enabled: bool,
    /// Samples quieter than this count as silence.
    pub threshold_dbfs: f32,
    /// Mid-track silence longer than this is collapsed.
    pub min_silence_ms: u32,
}

impl Default for SilenceSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            threshold_dbfs: -50.0,
            min_silence_ms: 500,
        }
    }
}

impl SilenceSettings {
    /// Linear amplitude corresponding to [`threshold_dbfs`](Self::threshold_dbfs).
    #[must_use]
    pub fn threshold_linear(&self) -> f32 {
        10.0_f32.powf(self.threshold_dbfs / 20.0)
    }

    /// Minimum collapsible silence expressed in frames at `sample_rate`.
    #[must_use]
    pub fn min_silence_frames(&self, sample_rate: u32) -> u64 {
        (u64::from(sample_rate) * u64::from(self.min_silence_ms)) / 1000
    }
}

#[derive(Debug, Clone)]
pub struct PlaybackSettings {
    pub crossfade_duration_ms: u32,
    pub replay_gain_enabled: bool,
    pub play_mode: PlayMode,
    pub silence: SilenceSettings,
}

impl Default for PlaybackSettings {
    fn default() -> Self {
        Self {
            crossfade_duration_ms: 5000,
            replay_gain_enabled: false,
            play_mode: PlayMode::None,
            silence: SilenceSettings::default(),
        }
    }
}

/// Zero-copy playback counters exposed to the JVM via a direct ByteBuffer.
#[repr(C)]
pub struct SharedEngineState {
    pub current_frame: AtomicU64,
    pub sample_rate: AtomicU32,
    pub is_paused: AtomicU32,
    pub duration_ms: AtomicU64,
    /// Increments whenever a new logical track play starts. Seeking and output
    /// device reconstruction deliberately retain the current generation.
    pub playback_generation: AtomicU64,
    /// RMS of the PCM handed to the output device, encoded as f32 bits.
    pub rms_level: AtomicU32,
    /// Decoded-but-unrendered samples in the lead ring buffer.
    pub buffered_samples: AtomicU64,
    /// Output callbacks that could not be fully served from the ring buffer.
    pub underruns: AtomicU64,
}

impl SharedEngineState {
    pub const fn new() -> Self {
        Self {
            current_frame: AtomicU64::new(0),
            sample_rate: AtomicU32::new(44100),
            is_paused: AtomicU32::new(1),
            duration_ms: AtomicU64::new(0),
            playback_generation: AtomicU64::new(0),
            rms_level: AtomicU32::new(0.0f32.to_bits()),
            buffered_samples: AtomicU64::new(0),
            underruns: AtomicU64::new(0),
        }
    }
}

impl Default for SharedEngineState {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn play_mode_rejects_unknown_values() {
        assert_eq!(PlayMode::try_from(3), Ok(PlayMode::Shuffle));
        assert!(PlayMode::try_from(-1).is_err());
        assert!(PlayMode::try_from(4).is_err());
    }
}
