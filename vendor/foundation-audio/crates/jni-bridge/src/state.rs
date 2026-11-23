use engine::PlaybackEngine;
use jni::objects::GlobalRef;
use std::sync::atomic::{AtomicU32, AtomicU64};
use std::sync::{Arc, OnceLock};

pub(crate) const ENGINE_TAG: &str = "mf-jni";

pub(crate) static ENGINE: OnceLock<Arc<PlaybackEngine>> = OnceLock::new();
pub(crate) static ANDROID_CONTEXT: OnceLock<GlobalRef> = OnceLock::new();
pub(crate) static CLOCK_READ_CALLS: AtomicU64 = AtomicU64::new(0);
pub(crate) static CLOCK_READ_SUCCESSES: AtomicU64 = AtomicU64::new(0);
pub(crate) static CLOCK_LAST_POSITION_MS: AtomicU64 = AtomicU64::new(0);
pub(crate) static CLOCK_LAST_PAUSED: AtomicU32 = AtomicU32::new(1);

pub(crate) fn engine() -> &'static Arc<PlaybackEngine> {
    ENGINE.get_or_init(PlaybackEngine::new)
}

pub(crate) fn jboolean(value: bool) -> jni::sys::jboolean {
    value as jni::sys::jboolean
}
