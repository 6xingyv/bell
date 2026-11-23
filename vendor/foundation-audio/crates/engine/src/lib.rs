//! foundation.audio playback engine.
//!
//! Owns the audio output, the current queue, and all orchestration
//! (track lifecycle, crossfade, watcher loop). Exposes a flat API that
//! JNI / CLI bindings can call without having to reason about threads
//! or ffmpeg.

mod monitor;
mod orchestration;
mod playback;

use queue::Queue;
use renderer::AudioOutput;
use std::sync::{Arc, Mutex, RwLock, Weak};
use types::{
    MAX_CROSSFADE_DURATION_MS, PlayMode, PlaybackSettings, PlayerState, SharedEngineState,
    SilenceSettings, TrackMetadata,
};
use util::fd_io::{close_fd, duplicate_fd};

const TAG: &str = "mf-engine";

use orchestration::TrackSession;
pub(crate) use playback::open_source_for_item;

pub struct PlaybackEngine {
    output: Mutex<Option<AudioOutput>>,
    /// Serializes user-initiated track changes with watcher/crossfade changes.
    pub(crate) transition: Mutex<()>,
    pub(crate) queue: Mutex<Queue>,
    settings: RwLock<PlaybackSettings>,
    pub(crate) current_metadata: RwLock<Option<TrackMetadata>>,
    pub(crate) current_session: Mutex<Option<Arc<TrackSession>>>,
    pub(crate) shared_state: Arc<SharedEngineState>,
    pub(crate) state: RwLock<PlayerState>,
    self_weak: Weak<PlaybackEngine>,
}

impl PlaybackEngine {
    pub fn new() -> Arc<Self> {
        let engine = Arc::new_cyclic(|self_weak| Self {
            output: Mutex::new(None),
            transition: Mutex::new(()),
            queue: Mutex::new(Queue::new()),
            settings: RwLock::new(PlaybackSettings::default()),
            current_metadata: RwLock::new(None),
            current_session: Mutex::new(None),
            shared_state: Arc::new(SharedEngineState::new()),
            state: RwLock::new(PlayerState::Stopped),
            self_weak: self_weak.clone(),
        });
        Self::spawn_output_device_monitor(&engine);
        engine
    }

    fn arc_self(&self) -> Arc<PlaybackEngine> {
        self.self_weak
            .upgrade()
            .expect("engine dropped while in use")
    }

    pub fn shared_state(&self) -> Arc<SharedEngineState> {
        self.shared_state.clone()
    }

    pub fn current_metadata(&self) -> Option<TrackMetadata> {
        self.current_metadata.read().unwrap().clone()
    }

    pub fn queue_index(&self) -> Option<i32> {
        self.queue
            .lock()
            .unwrap()
            .current_index()
            .and_then(|index| i32::try_from(index).ok())
    }

    pub fn queue_len(&self) -> usize {
        self.queue.lock().unwrap().len()
    }

    pub fn play_mode(&self) -> PlayMode {
        self.settings.read().unwrap().play_mode
    }

    pub fn set_play_mode(&self, mode: PlayMode) {
        self.settings.write().unwrap().play_mode = mode;
    }

    pub fn set_crossfade_duration(&self, ms: u32) -> bool {
        if ms > MAX_CROSSFADE_DURATION_MS {
            return false;
        }
        self.settings.write().unwrap().crossfade_duration_ms = ms;
        true
    }

    pub fn crossfade_duration_ms(&self) -> u32 {
        self.settings.read().unwrap().crossfade_duration_ms
    }

    pub fn set_replay_gain_enabled(&self, enabled: bool) {
        self.settings.write().unwrap().replay_gain_enabled = enabled;
    }

    /// Configure decode-side silence removal. Off by default; takes effect on
    /// the next track/seek (the current decoder keeps its existing filter).
    pub fn set_silence_removal(&self, enabled: bool, threshold_dbfs: f32, min_silence_ms: u32) {
        self.settings.write().unwrap().silence = SilenceSettings {
            enabled,
            threshold_dbfs,
            min_silence_ms,
        };
    }

    pub fn silence_settings(&self) -> SilenceSettings {
        self.settings.read().unwrap().silence
    }

    /// Set the calculated gain carried by a queued item. The value is consumed
    /// whenever that item is opened, including automatic advance and crossfade.
    pub fn set_queue_replay_gain(&self, index: usize, gain_db: Option<f32>) -> bool {
        self.queue.lock().unwrap().set_replay_gain(index, gain_db)
    }

    pub fn move_queue_item(&self, from: usize, to: usize) -> bool {
        self.queue.lock().unwrap().move_item(from, to)
    }

    pub fn state(&self) -> PlayerState {
        *self.state.read().unwrap()
    }

    pub fn add_to_queue_path(&self, path: String) -> bool {
        if path.trim().is_empty() {
            return false;
        }
        self.queue.lock().unwrap().add_path(path);
        true
    }

    /// Ownership: engine dups the fd and stores the duped copy.
    /// Callers may close their fd immediately after this returns.
    pub fn add_to_queue_fd(&self, fd: i32) -> bool {
        let Ok(duped) = duplicate_fd(fd) else {
            return false;
        };
        self.queue.lock().unwrap().add_fd(duped);
        true
    }

    pub fn clear_queue(&self) {
        let _transition = self.transition.lock().unwrap();
        self.stop_locked();
        self.clear_queue_locked();
    }

    fn clear_queue_locked(&self) {
        let mut q = self.queue.lock().unwrap();
        for item in q.drain() {
            if let Some(fd) = item.as_fd() {
                close_fd(fd);
            }
        }
    }

    pub fn set_queue_index(&self, index: usize) -> bool {
        let _transition = self.transition.lock().unwrap();
        let (previous, item) = {
            let mut q = self.queue.lock().unwrap();
            let previous = q.current_index();
            let Some(item) = q.get_entry(index) else {
                return false;
            };
            q.set_current_index(index);
            (previous, item)
        };
        if self.start_playback_locked(item) {
            true
        } else {
            self.queue.lock().unwrap().restore_current_index(previous);
            false
        }
    }

    pub fn skip_next(&self) -> bool {
        let _transition = self.transition.lock().unwrap();
        let mode = self.play_mode();
        let (previous, item) = {
            let mut queue = self.queue.lock().unwrap();
            (queue.current_index(), queue.next_entry(mode))
        };
        match item {
            Some(item) => {
                if self.start_playback_locked(item) {
                    true
                } else {
                    self.queue.lock().unwrap().restore_current_index(previous);
                    false
                }
            }
            None => false,
        }
    }

    pub fn skip_previous(&self) -> bool {
        let _transition = self.transition.lock().unwrap();
        let mode = self.play_mode();
        let (previous, item) = {
            let mut queue = self.queue.lock().unwrap();
            (queue.current_index(), queue.previous_entry(mode))
        };
        match item {
            Some(item) => {
                if self.start_playback_locked(item) {
                    true
                } else {
                    self.queue.lock().unwrap().restore_current_index(previous);
                    false
                }
            }
            None => false,
        }
    }
}
