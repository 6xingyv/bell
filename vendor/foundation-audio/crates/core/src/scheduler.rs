use super::{PlaybackSettings, PlayerState};

pub struct TimelineScheduler {
    pub current_state: PlayerState,
    pub current_frame: u64,
    pub settings: PlaybackSettings,
}

impl TimelineScheduler {
    pub fn new() -> Self {
        Self {
            current_state: PlayerState::Stopped,
            current_frame: 0,
            settings: PlaybackSettings::default(),
        }
    }
}

impl Default for TimelineScheduler {
    fn default() -> Self {
        Self::new()
    }
}

