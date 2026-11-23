use std::sync::atomic::{AtomicU64, AtomicU32};

#[repr(C)]
pub struct SharedEngineState {
    pub current_frame: AtomicU64,
    pub sample_rate: AtomicU32,
    pub is_paused: AtomicU32, // 1 for true, 0 for false
    pub duration_ms: AtomicU64,
    pub rms_level: AtomicU32, // f32 from bits
    pub peak_level: AtomicU32, // f32 from bits
}

impl SharedEngineState {
    pub const fn new() -> Self {
        Self {
            current_frame: AtomicU64::new(0),
            sample_rate: AtomicU32::new(44100),
            is_paused: AtomicU32::new(1),
            duration_ms: AtomicU64::new(0),
            rms_level: AtomicU32::new(0),
            peak_level: AtomicU32::new(0),
        }
    }
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

pub struct PlaybackSettings {
    pub crossfade_duration_ms: u32,
    pub analysis_enabled: bool,
    pub replay_gain_enabled: bool,
    pub play_mode: PlayMode,
}

impl Default for PlaybackSettings {
    fn default() -> Self {
        Self {
            crossfade_duration_ms: 5000, // 5s default
            analysis_enabled: false,
            replay_gain_enabled: false,
            play_mode: PlayMode::None,
        }
    }
}

