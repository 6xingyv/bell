//! getMetadata / getPlaybackStatus / extractMetadataFd JNI bindings.

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JObject};
use jni::sys::{jboolean, jbyteArray, jint, jobject};
use types::PlayerState;
use util::fd_io::FdFormatHandle;

use crate::engine;
use crate::metadata_wire::{
    attached_cover, collect_track_tags, direct_buffer, put_i32, put_wire_tag,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_getMetadata<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobject {
    let Some(m) = engine().current_metadata() else {
        return std::ptr::null_mut();
    };

    let class = "com/mocharealm/foundation/audio/TrackMetadata";
    let sig = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIJ)V";

    let title = env
        .new_string(m.title.as_deref().unwrap_or(""))
        .unwrap_or_default();
    let artist = env
        .new_string(m.artist.as_deref().unwrap_or(""))
        .unwrap_or_default();
    let album = env
        .new_string(m.album.as_deref().unwrap_or(""))
        .unwrap_or_default();
    let genre = env
        .new_string(m.genre.as_deref().unwrap_or(""))
        .unwrap_or_default();
    let lyrics = env
        .new_string(m.lyrics.as_deref().unwrap_or(""))
        .unwrap_or_default();

    match env.new_object(
        class,
        sig,
        &[
            (&title).into(),
            (&artist).into(),
            (&album).into(),
            (&genre).into(),
            (&lyrics).into(),
            jni::objects::JValue::Int(m.year.unwrap_or(0)),
            jni::objects::JValue::Int(m.track_number.unwrap_or(0)),
            jni::objects::JValue::Int(m.disc_number.unwrap_or(0)),
            jni::objects::JValue::Long(m.duration_ms as i64),
        ],
    ) {
        Ok(o) => o.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_getPlaybackStatus<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobject {
    if engine().state() == PlayerState::Stopped {
        return std::ptr::null_mut();
    }
    let state = engine().shared_state();
    let sr = state.sample_rate.load(std::sync::atomic::Ordering::Relaxed);
    let frame = state
        .current_frame
        .load(std::sync::atomic::Ordering::Relaxed);
    let is_paused = state.is_paused.load(std::sync::atomic::Ordering::Relaxed) != 0;
    let duration_ms = state.duration_ms.load(std::sync::atomic::Ordering::Relaxed);
    let playback_generation = state
        .playback_generation
        .load(std::sync::atomic::Ordering::Acquire);
    let pos_ms = if sr > 0 { frame * 1000 / sr as u64 } else { 0 };

    match env.new_object(
        "com/mocharealm/foundation/audio/PlaybackStatus",
        "(JJZFJ)V",
        &[
            jni::objects::JValue::Long(pos_ms as i64),
            jni::objects::JValue::Long(duration_ms as i64),
            jni::objects::JValue::Bool(is_paused as u8),
            jni::objects::JValue::Float(1.0f32),
            jni::objects::JValue::Long(playback_generation as i64),
        ],
    ) {
        Ok(o) => o.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Writes the playback snapshot into a caller-owned 32-byte direct buffer:
/// current_ms:u64, duration_ms:u64, paused:u32, speed:f32,
/// playback_generation:u64 (little endian).
/// This avoids constructing a Java object from JNI on every controller poll.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_getPlaybackStatusDirect(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
) -> jboolean {
    if engine().state() == PlayerState::Stopped {
        return 0;
    }
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else {
        return 0;
    };
    let Ok(address) = env.get_direct_buffer_address(&buffer) else {
        return 0;
    };
    if capacity < 32 {
        return 0;
    }
    let state = engine().shared_state();
    let sample_rate = state.sample_rate.load(std::sync::atomic::Ordering::Relaxed);
    let frame = state
        .current_frame
        .load(std::sync::atomic::Ordering::Relaxed);
    let duration_ms = state.duration_ms.load(std::sync::atomic::Ordering::Relaxed);
    let current_ms = if sample_rate > 0 {
        frame.saturating_mul(1000) / sample_rate as u64
    } else {
        0
    };
    let paused = state.is_paused.load(std::sync::atomic::Ordering::Relaxed) as u32;
    let playback_generation = state
        .playback_generation
        .load(std::sync::atomic::Ordering::Acquire);
    // SAFETY: capacity was checked above and the direct buffer remains pinned for
    // the duration of this JNI call.
    let output = unsafe { std::slice::from_raw_parts_mut(address, 32) };
    output[0..8].copy_from_slice(&current_ms.to_le_bytes());
    output[8..16].copy_from_slice(&duration_ms.to_le_bytes());
    output[16..20].copy_from_slice(&paused.to_le_bytes());
    output[20..24].copy_from_slice(&1.0f32.to_le_bytes());
    output[24..32].copy_from_slice(&playback_generation.to_le_bytes());
    1
}

/// Extracts all tags + cover art from an fd without starting playback.
/// Fills `tags_map` (a Java HashMap<String,String>) and returns cover bytes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_extractMetadataFd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    tags_map: JObject<'local>,
) -> jbyteArray {
    let fd = fd as i32;
    let handle = match FdFormatHandle::open(fd) {
        Ok(h) => h,
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(format_ctx) = handle.input() else {
        return std::ptr::null_mut();
    };

    for (key, value) in collect_track_tags(format_ctx) {
        put_tag(&mut env, &tags_map, &key, &value);
    }

    // The tags were already copied into the Java map above. Building a complete
    // TrackMetadata here would duplicate every string (including embedded lyrics)
    // and copy the artwork into a Rust Vec before copying it again to Java. Copy
    // the attached packet straight into the result array instead.
    let cover = attached_cover(format_ctx).and_then(|bytes| {
        // SAFETY: the packet belongs to format_ctx and remains valid until handle
        // is dropped below. byte_array_from_slice completes the Java-side copy now.
        env.byte_array_from_slice(bytes)
            .ok()
            .map(|array| array.into_raw())
    });
    drop(handle);

    cover.unwrap_or(std::ptr::null_mut())
}

fn put_tag(env: &mut JNIEnv, map: &JObject, k: &str, v: &str) {
    let Ok(jk) = env.new_string(k) else {
        return;
    };
    let Ok(jv) = env.new_string(v) else {
        return;
    };
    if let Ok(ret) = env.call_method(
        map,
        "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        &[(&jk).into(), (&jv).into()],
    ) {
        if let Ok(obj) = ret.l() {
            if !obj.is_null() {
                let _ = env.delete_local_ref(obj);
            }
        }
    }
    let _ = env.delete_local_ref(jk);
    let _ = env.delete_local_ref(jv);
}

/// One native-owned direct buffer containing all tags and the attached picture.
/// Wire format: `MFD1`, tag_count:i32, repeated UTF-8 key/value strings, then
/// cover_length:i32 and raw cover bytes. All integers are little endian.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_extractMetadataFdDirect(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jobject {
    let handle = match FdFormatHandle::open(fd as i32) {
        Ok(handle) => handle,
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(format_ctx) = handle.input() else {
        return std::ptr::null_mut();
    };

    let cover = attached_cover(format_ctx);
    // Reserve the artwork up front so appending a multi-megabyte cover does not
    // repeatedly move the already encoded metadata wire.
    let mut output = Vec::with_capacity(16 * 1024 + cover.map_or(0, |bytes| bytes.len()));
    output.extend_from_slice(b"MFD1");
    let count_offset = output.len();
    put_i32(&mut output, 0);
    let mut tag_count = 0i32;

    for (key, value) in collect_track_tags(format_ctx) {
        put_wire_tag(&mut output, &key, &value);
        tag_count += 1;
    }
    output[count_offset..count_offset + 4].copy_from_slice(&tag_count.to_le_bytes());

    match cover {
        Some(bytes) => {
            put_i32(&mut output, i32::try_from(bytes.len()).unwrap_or(i32::MAX));
            output.extend_from_slice(&bytes[..bytes.len().min(i32::MAX as usize)]);
        }
        None => put_i32(&mut output, 0),
    }
    drop(handle);
    direct_buffer(&mut env, output)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_releaseMetadataBuffer(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
) {
    let Ok(address) = env.get_direct_buffer_address(&buffer) else {
        return;
    };
    let Ok(length) = env.get_direct_buffer_capacity(&buffer) else {
        return;
    };
    if address.is_null() || length == 0 {
        return;
    }
    // SAFETY: buffers returned by extractMetadataFdDirect are created from this
    // exact boxed slice and released exactly once by the Kotlin finally block.
    unsafe {
        let slice = std::ptr::slice_from_raw_parts_mut(address, length);
        drop(Box::from_raw(slice));
    }
}
