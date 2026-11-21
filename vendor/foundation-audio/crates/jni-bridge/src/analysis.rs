use ebur128::{EbuR128, Mode};
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JIntArray};
use jni::sys::{jfloatArray, jint, jobject};
use source::AudioSource;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;

const ANALYSIS_SAMPLE_RATE: u32 = 48_000;
const ANALYSIS_CHANNELS: u16 = 2;
/// EBU R128 programme loudness target.
const EBU_R128_TARGET_LUFS: f64 = -23.0;
const MUSIC_ANALYSIS_SAMPLE_RATE: u32 = 16_000;
const MUSIC_ANALYSIS_CHANNELS: u16 = 1;
const MUSIC_ANALYSIS_WINDOW_SAMPLES: usize = 15_600;
const MUSIC_ANALYSIS_WINDOW_MS: u64 = 975;
const MUSIC_ANALYSIS_SEGMENT_COUNT: usize = 6;
const MUSIC_ANALYSIS_WINDOWS_PER_SEGMENT: usize = 3;
const MUSIC_ANALYSIS_WINDOW_COUNT: usize =
    MUSIC_ANALYSIS_SEGMENT_COUNT * MUSIC_ANALYSIS_WINDOWS_PER_SEGMENT;

/// Analyze one album in a single JNI call.
///
/// The returned array contains one EBU R128 gain per input fd, followed by the
/// Album Gain calculated from all successfully decoded tracks using the joint
/// EBU R128 gate. Failed/silent tracks are represented by NaN and are excluded
/// from the album measurement. The JNI name is retained for source and binary
/// compatibility with the previous ReplayGain API.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_calculateReplayGainAlbum(
    env: JNIEnv,
    _class: JClass,
    fds: JIntArray,
) -> jfloatArray {
    calculate_ebu_r128_album(env, fds)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_calculateEbuR128Album(
    env: JNIEnv,
    _class: JClass,
    fds: JIntArray,
) -> jfloatArray {
    calculate_ebu_r128_album(env, fds)
}

fn calculate_ebu_r128_album(env: JNIEnv, fds: JIntArray) -> jfloatArray {
    let Ok(length) = env.get_array_length(&fds) else {
        return std::ptr::null_mut();
    };
    if length <= 0 {
        return std::ptr::null_mut();
    }

    let mut raw_fds = vec![0i32; length as usize];
    if env.get_int_array_region(&fds, 0, &mut raw_fds).is_err() {
        return std::ptr::null_mut();
    }

    let analyzers = raw_fds.into_iter().map(analyze_fd).collect::<Vec<_>>();
    let mut gains = analyzers
        .iter()
        .map(|analyzer| {
            analyzer
                .as_ref()
                .and_then(|analyzer| analyzer.loudness_global().ok())
                .and_then(gain_from_loudness)
                .unwrap_or(f32::NAN)
        })
        .collect::<Vec<_>>();

    let album_gain = EbuR128::loudness_global_multiple(analyzers.iter().filter_map(Option::as_ref))
        .ok()
        .and_then(gain_from_loudness)
        .unwrap_or(f32::NAN);
    gains.push(album_gain);

    let Ok(result) = env.new_float_array(gains.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&result, 0, &gains).is_err() {
        return std::ptr::null_mut();
    }
    result.into_raw()
}

fn analyze_fd(fd: i32) -> Option<EbuR128> {
    let (mut source, _) = AudioSource::open_fd(fd).ok()?;
    // A fixed stereo analysis format makes states compatible for the joint
    // album gate and lets FFmpeg apply its channel-layout-aware downmix first.
    source.set_targets(ANALYSIS_SAMPLE_RATE, ANALYSIS_CHANNELS);
    let mut analyzer =
        EbuR128::new(u32::from(ANALYSIS_CHANNELS), ANALYSIS_SAMPLE_RATE, Mode::I).ok()?;
    let mut failed = false;
    source
        .decode_interleaved(Arc::new(AtomicBool::new(false)), |samples| {
            if analyzer.add_frames_f32(samples).is_err() {
                failed = true;
                false
            } else {
                true
            }
        })
        .ok()?;
    (!failed).then_some(analyzer)
}

fn gain_from_loudness(loudness_lufs: f64) -> Option<f32> {
    loudness_lufs
        .is_finite()
        .then(|| (EBU_R128_TARGET_LUFS - loudness_lufs).clamp(-60.0, 24.0) as f32)
}

/// Decode eighteen YAMNet windows from six evenly distributed segments.
///
/// The returned direct buffer is native-endian packed f32 PCM at 16 kHz mono.
/// Kotlin passes each window straight to LiteRT without another PCM copy and
/// releases the native allocation in a `finally` block.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_decodeMusicAnalysisPcm(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jobject {
    let Some(samples) = decode_music_analysis_windows(fd as i32) else {
        return std::ptr::null_mut();
    };
    let boxed = samples.into_boxed_slice();
    let byte_length = boxed.len() * size_of::<f32>();
    let address = Box::into_raw(boxed) as *mut f32;
    match unsafe { env.new_direct_byte_buffer(address.cast::<u8>(), byte_length) } {
        Ok(buffer) => buffer.into_raw(),
        Err(_) => {
            unsafe {
                let slice =
                    std::ptr::slice_from_raw_parts_mut(address, byte_length / size_of::<f32>());
                drop(Box::from_raw(slice));
            }
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mocharealm_foundation_audio_AudioEngine_releaseMusicAnalysisPcm(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
) {
    let Ok(address) = env.get_direct_buffer_address(&buffer) else {
        return;
    };
    let Ok(byte_length) = env.get_direct_buffer_capacity(&buffer) else {
        return;
    };
    if address.is_null() || byte_length == 0 || byte_length % size_of::<f32>() != 0 {
        return;
    }
    unsafe {
        let slice = std::ptr::slice_from_raw_parts_mut(
            address.cast::<f32>(),
            byte_length / size_of::<f32>(),
        );
        drop(Box::from_raw(slice));
    }
}

fn decode_music_analysis_windows(fd: i32) -> Option<Vec<f32>> {
    let (mut source, metadata) = AudioSource::open_fd(fd).ok()?;
    source.set_targets(MUSIC_ANALYSIS_SAMPLE_RATE, MUSIC_ANALYSIS_CHANNELS);
    let starts = music_analysis_segment_starts(metadata.duration_ms);
    let mut output =
        Vec::with_capacity(MUSIC_ANALYSIS_WINDOW_COUNT * MUSIC_ANALYSIS_WINDOW_SAMPLES);

    for start_ms in starts {
        source.seek_to_ms(start_ms).ok()?;
        let segment_start = output.len();
        let segment_samples = MUSIC_ANALYSIS_WINDOW_SAMPLES * MUSIC_ANALYSIS_WINDOWS_PER_SEGMENT;
        source
            .decode_interleaved(Arc::new(AtomicBool::new(false)), |samples| {
                let written = output.len() - segment_start;
                let remaining = segment_samples.saturating_sub(written);
                output.extend_from_slice(&samples[..samples.len().min(remaining)]);
                output.len() - segment_start < segment_samples
            })
            .ok()?;
        output.resize(segment_start + segment_samples, 0.0);
    }
    Some(output)
}

fn music_analysis_segment_starts(duration_ms: u64) -> [u64; MUSIC_ANALYSIS_SEGMENT_COUNT] {
    let segment_ms = MUSIC_ANALYSIS_WINDOW_MS * MUSIC_ANALYSIS_WINDOWS_PER_SEGMENT as u64;
    let latest_start = duration_ms.saturating_sub(segment_ms);
    std::array::from_fn(|index| {
        latest_start.saturating_mul(index as u64 + 1) / (MUSIC_ANALYSIS_SEGMENT_COUNT as u64 + 1)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gain_targets_minus_twenty_three_lufs() {
        assert_eq!(gain_from_loudness(-23.0), Some(0.0));
        assert_eq!(gain_from_loudness(-18.0), Some(-5.0));
        assert_eq!(gain_from_loudness(-28.0), Some(5.0));
        assert_eq!(gain_from_loudness(f64::NEG_INFINITY), None);
    }

    #[test]
    fn analysis_windows_are_evenly_distributed_and_in_bounds() {
        assert_eq!(music_analysis_segment_starts(500), [0; 6]);
        assert_eq!(
            music_analysis_segment_starts(9_925),
            [1_000, 2_000, 3_000, 4_000, 5_000, 6_000]
        );
    }
}
