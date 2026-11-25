use jni::JNIEnv;
use jni::sys::jobject;
use std::collections::HashMap;

pub(crate) fn put_i32(output: &mut Vec<u8>, value: i32) {
    output.extend_from_slice(&value.to_le_bytes());
}

pub(crate) fn put_string(output: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    put_i32(output, i32::try_from(bytes.len()).unwrap_or(i32::MAX));
    output.extend_from_slice(&bytes[..bytes.len().min(i32::MAX as usize)]);
}

pub(crate) fn put_wire_tag(output: &mut Vec<u8>, key: &str, value: &str) {
    put_string(output, key);
    put_string(output, value);
}

/// Merge only metadata that describes the audio track. FFmpeg exposes embedded
/// artwork as an ATTACHED_PIC stream whose metadata commonly contains
/// `title=Cover`; merging every stream used to overwrite the real song title and
/// could also collapse unrelated tracks onto the same album/cover cache key.
/// The best audio stream supplies a fallback, while container tags win when both
/// levels contain the same case-insensitive key.
pub(crate) fn collect_track_tags(
    format_ctx: &ffmpeg_next::format::context::Input,
) -> Vec<(String, String)> {
    let mut tags = Vec::<(String, String)>::new();
    let mut indices = HashMap::<String, usize>::new();

    let mut upsert = |key: &str, value: &str| {
        if key.eq_ignore_ascii_case("duration") {
            return;
        }
        let normalized = key.to_ascii_lowercase();
        if let Some(&index) = indices.get(&normalized) {
            tags[index] = (key.to_owned(), value.to_owned());
        } else {
            indices.insert(normalized, tags.len());
            tags.push((key.to_owned(), value.to_owned()));
        }
    };

    if let Some(audio_stream) = format_ctx.streams().best(ffmpeg_next::media::Type::Audio) {
        for (key, value) in audio_stream.metadata().iter() {
            upsert(key, value);
        }
    }
    for (key, value) in format_ctx.metadata().iter() {
        upsert(key, value);
    }
    drop(upsert);

    let duration_sec = format_ctx.duration() as f64 / ffmpeg_next::ffi::AV_TIME_BASE as f64;
    if duration_sec > 0.0 {
        tags.push(("duration".to_owned(), format!("{duration_sec:.3}")));
    }
    tags
}

pub(crate) fn attached_cover(format_ctx: &ffmpeg_next::format::context::Input) -> Option<&[u8]> {
    format_ctx.streams().find_map(|stream| {
        if !stream
            .disposition()
            .contains(ffmpeg_next::format::stream::Disposition::ATTACHED_PIC)
        {
            return None;
        }
        // SAFETY: AVStream and its attached packet stay owned by format_ctx for
        // the returned slice's lifetime. Callers consume/copy it before dropping
        // the FdFormatHandle.
        unsafe {
            let packet = &(*stream.as_ptr()).attached_pic;
            (!packet.data.is_null() && packet.size > 0)
                .then(|| std::slice::from_raw_parts(packet.data, packet.size as usize))
        }
    })
}

pub(crate) fn direct_buffer(env: &mut JNIEnv, bytes: Vec<u8>) -> jobject {
    if bytes.is_empty() {
        return std::ptr::null_mut();
    }
    let boxed = bytes.into_boxed_slice();
    let length = boxed.len();
    let address = Box::into_raw(boxed) as *mut u8;
    match unsafe { env.new_direct_byte_buffer(address, length) } {
        Ok(buffer) => buffer.into_raw(),
        Err(_) => {
            // SAFETY: JNI did not take ownership, so reconstruct and drop it.
            unsafe {
                let slice = std::ptr::slice_from_raw_parts_mut(address, length);
                drop(Box::from_raw(slice));
            }
            std::ptr::null_mut()
        }
    }
}
