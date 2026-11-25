use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jstring;
use std::sync::atomic::Ordering;

use super::state::{
    CLOCK_LAST_PAUSED, CLOCK_LAST_POSITION_MS, CLOCK_READ_CALLS, CLOCK_READ_SUCCESSES, engine,
};

/// Lock-free playback snapshot for other in-process native renderers.
///
/// This deliberately has a plain C ABI rather than a JNI entry point: a renderer
/// such as lyrics-ui can read the audio clock on its render thread without a
/// Kotlin poll or a Java object allocation. `duration_ms == 0` means no active
/// track and is reported as `false` by [mf_get_playback_clock].
///
/// Layout is append-only: new fields must be added at the end so older readers
/// that only touch the prefix remain binary-compatible when rebuilt together.
#[repr(C)]
pub struct MfPlaybackClock {
    pub position_ms: u64,
    pub is_paused: u8,
    /// Track duration in milliseconds (0 when no active track).
    pub duration_ms: u64,
}

#[unsafe(no_mangle)]
pub extern "C" fn mf_get_playback_clock(out: *mut MfPlaybackClock) -> bool {
    CLOCK_READ_CALLS.fetch_add(1, Ordering::Relaxed);
    if out.is_null() {
        return false;
    }
    let state = engine().shared_state();
    let duration_ms = state.duration_ms.load(Ordering::Relaxed);
    if duration_ms == 0 {
        return false;
    }
    let sample_rate = state.sample_rate.load(Ordering::Relaxed);
    let current_frame = state.current_frame.load(Ordering::Relaxed);
    let position_ms = if sample_rate > 0 {
        current_frame.saturating_mul(1000) / sample_rate as u64
    } else {
        0
    };
    let is_paused = state.is_paused.load(Ordering::Relaxed);
    // SAFETY: the caller owns a valid pointer to a C-compatible MfPlaybackClock.
    unsafe {
        *out = MfPlaybackClock {
            position_ms,
            is_paused: is_paused as u8,
            duration_ms,
        };
    }
    CLOCK_READ_SUCCESSES.fetch_add(1, Ordering::Relaxed);
    CLOCK_LAST_POSITION_MS.store(position_ms, Ordering::Relaxed);
    CLOCK_LAST_PAUSED.store(is_paused, Ordering::Relaxed);
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_getDiagnosticLog(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let engine = engine();
    let state = engine.shared_state();
    let sample_rate = state.sample_rate.load(Ordering::Relaxed);
    let current_frame = state.current_frame.load(Ordering::Relaxed);
    let position_ms = if sample_rate == 0 {
        0
    } else {
        current_frame.saturating_mul(1_000) / u64::from(sample_rate)
    };
    let summary = format!(
        "state={:?}\nqueue_index={:?}\nposition_ms={}\ncurrent_frame={}\nsample_rate={}\nduration_ms={}\nis_paused={}\nplayback_generation={}\nrms={}\nclock_read_calls={}\nclock_read_successes={}\nclock_last_position_ms={}\nclock_last_paused={}\n\n-- buffered music-foundation logs --\n{}",
        engine.state(),
        engine.queue_index(),
        position_ms,
        current_frame,
        sample_rate,
        state.duration_ms.load(Ordering::Relaxed),
        state.is_paused.load(Ordering::Relaxed),
        state.playback_generation.load(Ordering::Relaxed),
        f32::from_bits(state.rms_level.load(Ordering::Relaxed)),
        CLOCK_READ_CALLS.load(Ordering::Relaxed),
        CLOCK_READ_SUCCESSES.load(Ordering::Relaxed),
        CLOCK_LAST_POSITION_MS.load(Ordering::Relaxed),
        CLOCK_LAST_PAUSED.load(Ordering::Relaxed),
        util::diagnostics::snapshot(),
    );
    env.new_string(summary)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Latest perceptual 0..1 output envelope for in-process visualizers. The symbol
/// name is retained as ABI, but the audio callback maps raw RMS/peak from dBFS so
/// normal music does not spend its entire visual range near zero.
#[unsafe(no_mangle)]
pub extern "C" fn mf_get_audio_rms() -> f32 {
    let state = engine().shared_state();
    f32::from_bits(state.rms_level.load(Ordering::Relaxed))
}
