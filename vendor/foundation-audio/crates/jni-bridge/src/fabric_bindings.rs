//! JNI surface for the fabric renderer adapter.
//!
//! Pairs with the Kotlin `FabricRenderer` class in the audio SDK: clef binds
//! the hub through the fabric-sdk AAR (`FabricClient.registerAbility`) and
//! forwards what it receives here — control payloads from `onInvoke` and the
//! `media` / `asset` stream descriptors from `onStreamOpen`. All protocol state
//! (announce, assets, barrier, start position) then lives in Rust; Kotlin never
//! parses control JSON.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};

use fabric_audio_adapter::{IdentityGroupClock, LocalAudioRenderAdapter};
use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

use super::state::{ENGINE_TAG, engine, jboolean as to_jboolean};

type Renderer = Arc<LocalAudioRenderAdapter<IdentityGroupClock>>;

static RENDERERS: LazyLock<Mutex<HashMap<jlong, Renderer>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_RENDERER_HANDLE: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

fn renderer(handle: jlong) -> Option<Renderer> {
    RENDERERS.lock().unwrap().get(&handle).cloned()
}

/// Create a renderer adapter bound to the process-wide playback engine.
/// `participant_id` is this device's fabric participant id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    participant_id: JString,
) -> jlong {
    let participant: String = env
        .get_string(&participant_id)
        .map(Into::into)
        .unwrap_or_default();
    let adapter = Arc::new(LocalAudioRenderAdapter::new(
        Arc::clone(engine()),
        IdentityGroupClock::new(),
        participant,
    ));
    let handle = NEXT_RENDERER_HANDLE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    RENDERERS.lock().unwrap().insert(handle, adapter);
    handle
}

/// Handle one control payload (from `AbilityHandler.onInvoke`) and return the
/// reply bytes to complete the invocation with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeHandleControl(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    payload: JByteArray,
) -> jbyteArray {
    let Some(adapter) = renderer(handle) else {
        return std::ptr::null_mut();
    };
    let Ok(bytes) = env.convert_byte_array(&payload) else {
        return std::ptr::null_mut();
    };
    let reply = adapter.handle_control_json(&bytes);
    env.byte_array_from_slice(&reply)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Attach the framed media stream arriving on `fd` (the hub's read end from
/// `onStreamOpen` for the `media` stream). Blocks until the stream header is
/// read and decode starts — call from a background thread. The fd is duplicated;
/// the caller keeps ownership of the original.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeAttachMediaFd(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fd: jint,
) -> jboolean {
    let Some(adapter) = renderer(handle) else {
        return to_jboolean(false);
    };
    let Some(file) = file_from_fd(fd) else {
        return to_jboolean(false);
    };
    match adapter.attach_media_stream(file) {
        Ok(()) => to_jboolean(true),
        Err(error) => {
            util::log_i!(ENGINE_TAG, "fabric attach media failed: {}", error);
            to_jboolean(false)
        }
    }
}

/// Read one framed cover/lyrics asset from `fd` (the `asset` stream) and
/// install it. Blocks until the object is fully read. The fd is duplicated.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeAcceptAssetFd(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fd: jint,
) -> jboolean {
    let Some(adapter) = renderer(handle) else {
        return to_jboolean(false);
    };
    let Some(file) = file_from_fd(fd) else {
        return to_jboolean(false);
    };
    match adapter.protocol.accept_asset_stream(file) {
        Ok(_) => to_jboolean(true),
        Err(error) => {
            util::log_i!(ENGINE_TAG, "fabric accept asset failed: {}", error);
            to_jboolean(false)
        }
    }
}

/// Presentation metadata for the current announce (title/artist/album/…,
/// including merged lyrics), as JSON for the app UI. Cover art is excluded —
/// fetch it via `nativeCoverBytes` to keep this payload small.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativePresentationJson(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(adapter) = renderer(handle) else {
        return std::ptr::null_mut();
    };
    let meta = adapter.presentation_metadata();
    let json = serde_json::json!({
        "generation": adapter.protocol.generation(),
        "title": meta.title,
        "artist": meta.artist,
        "album": meta.album,
        "album_artist": meta.album_artist,
        "genre": meta.genre,
        "year": meta.year,
        "track_number": meta.track_number,
        "disc_number": meta.disc_number,
        "duration_ms": meta.duration_ms,
        "lyrics": meta.lyrics,
    });
    let bytes = serde_json::to_vec(&json).unwrap_or_default();
    env.byte_array_from_slice(&bytes)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Cover art bytes for the current announce, or null when none is installed yet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeCoverBytes(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(adapter) = renderer(handle) else {
        return std::ptr::null_mut();
    };
    let Some(cover) = adapter.protocol.current_cover() else {
        return std::ptr::null_mut();
    };
    env.byte_array_from_slice(&cover)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Latest playback feedback as a JSON `AudioFeedbackDatagram`, for the app to
/// push on the `feedback` port.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeFeedbackJson(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(adapter) = renderer(handle) else {
        return std::ptr::null_mut();
    };
    let bytes = serde_json::to_vec(&adapter.feedback_datagram()).unwrap_or_default();
    env.byte_array_from_slice(&bytes)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_FabricRenderer_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    RENDERERS.lock().unwrap().remove(&handle);
}

#[cfg(unix)]
fn file_from_fd(fd: jint) -> Option<std::fs::File> {
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return None;
    }
    let duped = util::fd_io::duplicate_fd(fd).ok()?;
    // SAFETY: `duped` is a fresh descriptor we own exclusively.
    Some(unsafe { std::fs::File::from_raw_fd(duped) })
}

#[cfg(not(unix))]
fn file_from_fd(_fd: jint) -> Option<std::fs::File> {
    None
}
