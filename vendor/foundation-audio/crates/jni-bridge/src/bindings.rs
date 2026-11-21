use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong};
use types::PlayMode;

use super::state::{ANDROID_CONTEXT, ENGINE_TAG, engine, jboolean as to_jboolean};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_init(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    std::panic::set_hook(Box::new(|info| {
        util::log_e!(ENGINE_TAG, "rust panic: {}", info);
    }));

    if let Ok(vm) = env.get_java_vm() {
        if let Ok(global_ctx) = env.new_global_ref(context) {
            let context_ptr = global_ctx.as_obj().as_raw() as *mut std::ffi::c_void;
            if ANDROID_CONTEXT.set(global_ctx).is_ok() {
                // SAFETY: the VM is process-scoped and ANDROID_CONTEXT retains
                // the Java context for the remainder of the process.
                unsafe {
                    ndk_context::initialize_android_context(
                        vm.get_java_vm_pointer() as *mut std::ffi::c_void,
                        context_ptr,
                    );
                }
            }
        }
    }
    let _ = engine();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_play(
    mut env: JNIEnv,
    _class: JClass,
    path_jstr: JString,
) -> jboolean {
    let Ok(path) = env.get_string(&path_jstr) else {
        return to_jboolean(false);
    };
    let path: String = path.into();
    to_jboolean(engine().play_path(path))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_playFd(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jboolean {
    to_jboolean(engine().play_fd(fd as i32))
}

/// Play a progressive fabric media stream from a pipe/socket fd.
///
/// Expects the fabric media framing header (20 bytes) then compressed container
/// bytes. Does **not** use seekable file-fd demux. Optional `meta_json` overlays
/// presentation metadata (title/artist/album/lyrics/…); `cover` is optional
/// artwork bytes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_playMediaStreamFd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    start_paused: jboolean,
    meta_json: JString<'local>,
    cover: jni::objects::JByteArray<'local>,
) -> jboolean {
    use types::TrackMetadata;
    use util::fd_io::duplicate_fd;
    use util::stream_io::OwnedFdByteSource;

    if fd < 0 {
        return to_jboolean(false);
    }
    let Ok(owned) = duplicate_fd(fd as i32) else {
        return to_jboolean(false);
    };

    // Strip MediaStreamHeader
    // (generation:u64, flags:u32, byte_length:u64, start_position_ms:u64).
    const HEADER_LEN: usize = 28;
    let mut header = [0u8; HEADER_LEN];
    let mut filled = 0usize;
    while filled < HEADER_LEN {
        #[cfg(windows)]
        let count = (HEADER_LEN - filled) as u32;
        #[cfg(not(windows))]
        let count = (HEADER_LEN - filled) as libc::size_t;
        let n = unsafe {
            libc::read(
                owned,
                header[filled..].as_mut_ptr() as *mut libc::c_void,
                count,
            )
        };
        if n <= 0 {
            unsafe {
                libc::close(owned);
            }
            return to_jboolean(false);
        }
        filled += n as usize;
    }
    let byte_length = u64::from_be_bytes(header[12..20].try_into().unwrap_or([0; 8]));
    let stream_position_ms = u64::from_be_bytes(header[20..28].try_into().unwrap_or([0; 8]));
    let source: Box<dyn util::stream_io::MediaByteSource> = if byte_length > 0 {
        Box::new(OwnedFdByteSource::with_byte_length(owned, byte_length))
    } else {
        Box::new(OwnedFdByteSource::new(owned))
    };

    let mut overlay = TrackMetadata::default();
    if let Ok(js) = env.get_string(&meta_json) {
        let text: String = js.into();
        if !text.is_empty()
            && let Ok(value) = serde_json::from_str::<serde_json::Value>(&text)
        {
            overlay.title = value
                .get("title")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.artist = value
                .get("artist")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.album = value
                .get("album")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.album_artist = value
                .get("album_artist")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.genre = value
                .get("genre")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.lyrics = value
                .get("lyrics")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(str::to_owned);
            overlay.year = value.get("year").and_then(|v| v.as_i64()).map(|v| v as i32);
            overlay.track_number = value
                .get("track_number")
                .and_then(|v| v.as_i64())
                .map(|v| v as i32);
            overlay.disc_number = value
                .get("disc_number")
                .and_then(|v| v.as_i64())
                .map(|v| v as i32);
            overlay.duration_ms = value
                .get("duration_ms")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            overlay.sample_rate = value
                .get("sample_rate")
                .and_then(|v| v.as_u64())
                .unwrap_or(0) as u32;
            overlay.channels = value
                .get("channels")
                .and_then(|v| v.as_u64())
                .unwrap_or(0) as u16;
            overlay.bit_rate = value
                .get("bit_rate")
                .and_then(|v| v.as_u64())
                .unwrap_or(0) as u32;
            overlay.replay_gain_db = value
                .get("replay_gain_db")
                .and_then(|v| v.as_f64())
                .map(|v| v as f32);
        }
    }
    if let Ok(bytes) = env.convert_byte_array(cover) {
        if !bytes.is_empty() {
            overlay.cover_art = Some(bytes);
        }
    }

    let has_overlay = overlay.title.is_some()
        || overlay.artist.is_some()
        || overlay.album.is_some()
        || overlay.lyrics.is_some()
        || overlay.cover_art.is_some()
        || overlay.duration_ms > 0;
    // The source remuxes from its own playhead, so the stream content already
    // begins at `stream_position_ms` — nothing to skip, only the reported
    // timeline needs rebasing to that position.
    let ok = engine().play_media_stream(source, has_overlay.then_some(overlay), start_paused != 0, 0);
    if ok && stream_position_ms > 0 {
        let _ = engine().rebase_stream_position(stream_position_ms);
    }
    to_jboolean(ok)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_pause(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().pause();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_resume(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().resume();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_stop(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().stop();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_addToQueue(
    mut env: JNIEnv,
    _class: JClass,
    path_jstr: JString,
) -> jboolean {
    let Ok(path) = env.get_string(&path_jstr) else {
        return to_jboolean(false);
    };
    to_jboolean(engine().add_to_queue_path(path.into()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_addToQueueFd(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jboolean {
    to_jboolean(engine().add_to_queue_fd(fd as i32))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_clearQueue(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().clear_queue();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setQueueIndex(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jboolean {
    to_jboolean(index >= 0 && engine().set_queue_index(index as usize))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_getQueueIndex(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    engine().queue_index().unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_moveQueueItem(
    _env: JNIEnv,
    _class: JClass,
    from: jint,
    to: jint,
) -> jboolean {
    to_jboolean(from >= 0 && to >= 0 && engine().move_queue_item(from as usize, to as usize))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_seekTo(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) -> jboolean {
    to_jboolean(position_ms >= 0 && engine().seek(position_ms as u64))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_skipToNext(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    to_jboolean(engine().skip_next())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_skipToPrevious(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let state = engine().shared_state();
    let sr = state.sample_rate.load(std::sync::atomic::Ordering::Relaxed);
    let frame = state
        .current_frame
        .load(std::sync::atomic::Ordering::Relaxed);
    let pos_ms = if sr > 0 { frame * 1000 / sr as u64 } else { 0 };
    if pos_ms > 3000 {
        to_jboolean(engine().seek(0))
    } else {
        to_jboolean(engine().skip_previous())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setCrossfadeDuration(
    _env: JNIEnv,
    _class: JClass,
    duration_ms: jint,
) -> jboolean {
    to_jboolean(duration_ms >= 0 && engine().set_crossfade_duration(duration_ms as u32))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setEbuR128Enabled(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    engine().set_replay_gain_enabled(enabled != 0);
}

/// Configure decode-side silence removal. Takes effect on the next track/seek.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setSilenceRemoval(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
    threshold_dbfs: jfloat,
    min_silence_ms: jint,
) {
    engine().set_silence_removal(
        enabled != 0,
        threshold_dbfs,
        min_silence_ms.max(0) as u32,
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setReplayGainEnabled(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    engine().set_replay_gain_enabled(enabled != 0);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setQueueEbuR128Gain(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
    gain_db: jfloat,
) -> jboolean {
    let gain = gain_db.is_finite().then_some(gain_db);
    to_jboolean(index >= 0 && engine().set_queue_replay_gain(index as usize, gain))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setQueueReplayGain(
    env: JNIEnv,
    class: JClass,
    index: jint,
    gain_db: jfloat,
) -> jboolean {
    Java_com_mocharealm_foundation_audio_AudioEngine_setQueueEbuR128Gain(env, class, index, gain_db)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_setPlayMode(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) -> jboolean {
    match PlayMode::try_from(mode as i32) {
        Ok(mode) => {
            engine().set_play_mode(mode);
            to_jboolean(true)
        }
        Err(()) => to_jboolean(false),
    }
}

// ---------------------------------------------------------------------------
// Source-side cast streams
//
// Casting used to be a verbatim byte copy of the file, which always restarted
// the track on the renderer. These entry points hand the file to a remuxer that
// starts at the source's own playhead and can be repositioned while it runs.
// ---------------------------------------------------------------------------

/// Live casts keyed by the handle returned to Java.
static CAST_STREAMS: std::sync::LazyLock<
    std::sync::Mutex<std::collections::HashMap<i64, std::sync::Arc<CastHandle>>>,
> = std::sync::LazyLock::new(|| std::sync::Mutex::new(std::collections::HashMap::new()));

static NEXT_CAST_HANDLE: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

struct CastHandle {
    control: std::sync::Arc<source::CastControl>,
    /// Taken by whichever of await/close gets there first; joining twice is a
    /// no-op so both entry points stay safe to call.
    thread: std::sync::Mutex<Option<std::thread::JoinHandle<()>>>,
    /// Set by the pump thread when the cast failed before establishing a viable media flow,
    /// so `castStreamAwait` can report the failure to the caller.
    failed: std::sync::atomic::AtomicBool,
}

impl CastHandle {
    fn join(&self) {
        let thread = self
            .thread
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        if let Some(thread) = thread {
            let _ = thread.join();
        }
    }
}

fn cast_handle(handle: jlong) -> Option<std::sync::Arc<CastHandle>> {
    CAST_STREAMS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(&handle)
        .cloned()
}

/// Open a cast stream from `source_fd` into `sink_fd`, positioned at
/// `start_position_ms`. Returns a handle, or 0 on failure. Both descriptors are
/// duplicated; the caller keeps ownership of the originals.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_castStreamOpen(
    _env: JNIEnv,
    _class: JClass,
    source_fd: jint,
    sink_fd: jint,
    start_position_ms: jlong,
) -> jlong {
    use util::fd_io::duplicate_fd;

    if source_fd < 0 || sink_fd < 0 {
        return 0;
    }
    let Ok(sink) = duplicate_fd(sink_fd) else {
        return 0;
    };
    let mut stream =
        match source::CastStream::open(source_fd, sink, start_position_ms.max(0) as u64) {
            Ok(stream) => stream,
            Err(error) => {
                util::log_i!(ENGINE_TAG, "castStreamOpen failed: {}", error);
                return 0;
            }
        };
    let control = stream.control();
    let handle = NEXT_CAST_HANDLE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let cast = std::sync::Arc::new(CastHandle {
        control,
        thread: std::sync::Mutex::new(None),
        failed: std::sync::atomic::AtomicBool::new(false),
    });
    let pump_cast = std::sync::Arc::clone(&cast);
    let thread = std::thread::Builder::new()
        .name("fabric-cast-remux".into())
        .spawn(move || {
            if let Err(error) = stream.pump() {
                util::log_i!(ENGINE_TAG, "cast stream failed: {}", error);
                pump_cast
                    .failed
                    .store(true, std::sync::atomic::Ordering::Release);
            }
        })
        .ok();
    let Some(thread) = thread else {
        return 0;
    };
    *cast
        .thread
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(thread);
    CAST_STREAMS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .insert(handle, cast);
    handle
}

/// Block until the cast finishes: end of track, cancellation, or the renderer
/// closing its end. Mirrors the blocking copy this replaced, so the caller can
/// keep the descriptors alive for exactly as long as they are needed.
///
/// Returns `false` when the cast failed before establishing a viable media flow
/// (dead sink or unreadable input), `true` for a clean end or an already-closed handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_castStreamAwait(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(cast) = cast_handle(handle) else {
        return to_jboolean(true);
    };
    cast.join();
    to_jboolean(!cast.failed.load(std::sync::atomic::Ordering::Acquire))
}

/// Reposition a running cast. The renderer keeps decoding the same stream.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_castStreamSeek(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_ms: jlong,
) -> jboolean {
    match cast_handle(handle) {
        Some(cast) => {
            cast.control.request_seek(position_ms.max(0) as u64);
            to_jboolean(true)
        }
        None => to_jboolean(false),
    }
}

/// Stop a cast and release its descriptors.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_castStreamClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let cast = CAST_STREAMS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .remove(&handle);
    let Some(cast) = cast else {
        return;
    };
    cast.control.cancel();
    // The pump only blocks on writes the renderer is draining, so this joins
    // promptly once cancellation is observed.
    cast.join();
}
