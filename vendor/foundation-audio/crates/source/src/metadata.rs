//! Unified metadata extraction from an ffmpeg `Input`. Called from both
//! path- and fd-based open paths, and from bare probes that don't need a
//! full decoder handle.

use ffmpeg_next::decoder::Audio;
use ffmpeg_next::format::context::Input;
use std::path::Path;
use types::TrackMetadata;
use util::fd_io::FdFormatHandle;

pub fn build_from_input(format_ctx: &Input, decoder_ctx: &Audio) -> TrackMetadata {
    // Playback never exposes cover_art through getMetadata. Avoid copying a
    // potentially multi-megabyte attached picture on the synchronous track-open
    // path; artwork scanning remains handled by probe_from_input below.
    let mut metadata = build_container_metadata(format_ctx, false);
    metadata.sample_rate = decoder_ctx.rate();
    metadata.channels = decoder_ctx.channels() as u16;
    metadata.bit_rate = decoder_ctx.bit_rate().min(u32::MAX as usize) as u32;
    metadata
}

/// Read tags, stream parameters and attached artwork without opening a decoder.
/// Metadata probing must keep working for codecs that the playback build does not
/// support (or has not loaded yet).
fn build_probe_metadata(format_ctx: &Input) -> Result<TrackMetadata, ffmpeg_next::Error> {
    let stream = format_ctx
        .streams()
        .best(ffmpeg_next::media::Type::Audio)
        .ok_or(ffmpeg_next::Error::StreamNotFound)?;
    let parameters = stream.parameters();
    let mut metadata = build_container_metadata(format_ctx, true);
    // SAFETY: parameters owns a valid AVCodecParameters for the lifetime of this
    // scope. These scalar fields are populated by the demuxer and need no decoder.
    unsafe {
        let parameters = &*parameters.as_ptr();
        metadata.sample_rate = parameters.sample_rate.max(0) as u32;
        metadata.channels = parameters.ch_layout.nb_channels.max(0) as u16;
        metadata.bit_rate = parameters.bit_rate.clamp(0, u32::MAX as i64) as u32;
    }
    Ok(metadata)
}

fn build_container_metadata(format_ctx: &Input, include_cover: bool) -> TrackMetadata {
    let mut metadata = TrackMetadata {
        duration_ms: if format_ctx.duration() > 0 {
            (format_ctx.duration() as u64) / 1000
        } else {
            0
        },
        ..Default::default()
    };

    let dict = format_ctx.metadata();
    metadata.title = dict.get("title").map(|v| v.to_string());
    metadata.artist = dict.get("artist").map(|v| v.to_string());
    metadata.album = dict.get("album").map(|v| v.to_string());
    metadata.album_artist = dict
        .get("album_artist")
        .or_else(|| dict.get("ALBUMARTIST"))
        .map(|v| v.to_string());
    metadata.genre = dict.get("genre").map(|v| v.to_string());
    metadata.year = dict.get("date").or_else(|| dict.get("year")).and_then(|v| {
        v.split(|c: char| !c.is_ascii_digit())
            .find(|s| s.len() == 4)
            .and_then(|s| s.parse::<i32>().ok())
    });
    metadata.track_number = dict
        .get("track")
        .and_then(|v| v.split('/').next().and_then(|s| s.parse::<i32>().ok()));
    metadata.disc_number = dict
        .get("disc")
        .and_then(|v| v.split('/').next().and_then(|s| s.parse::<i32>().ok()));
    metadata.lyrics = dict
        .get("LYRICS")
        .or_else(|| dict.get("lyrics"))
        .or_else(|| dict.get("lyrics-eng"))
        .map(|v| v.to_string());
    // Prefer native EBU R128 tags. Their Q7.8 value is already expressed
    // relative to the EBU R128 -23 LUFS target. Legacy ReplayGain tags use a
    // -18 LUFS reference, so convert them by subtracting 5 dB.
    metadata.replay_gain_db = dict
        .get("R128_TRACK_GAIN")
        .or_else(|| dict.get("r128_track_gain"))
        .and_then(parse_r128_gain_db)
        .or_else(|| {
            dict.get("R128_ALBUM_GAIN")
                .or_else(|| dict.get("r128_album_gain"))
                .and_then(parse_r128_gain_db)
        })
        .or_else(|| {
            dict.get("REPLAYGAIN_TRACK_GAIN")
                .or_else(|| dict.get("replaygain_track_gain"))
                .and_then(parse_replay_gain_db)
        })
        .or_else(|| {
            dict.get("REPLAYGAIN_ALBUM_GAIN")
                .or_else(|| dict.get("replaygain_album_gain"))
                .and_then(parse_replay_gain_db)
        });

    if include_cover {
        for s in format_ctx.streams() {
            if !s
                .disposition()
                .contains(ffmpeg_next::format::stream::Disposition::ATTACHED_PIC)
            {
                continue;
            }
            // SAFETY: AVStream and its attached packet are owned by format_ctx and
            // remain alive for this copy. FFmpeg guarantees `size` bytes at `data`.
            unsafe {
                let av_stream = s.as_ptr();
                let packet = &(*av_stream).attached_pic;
                if !packet.data.is_null() && packet.size > 0 {
                    let slice = std::slice::from_raw_parts(packet.data, packet.size as usize);
                    metadata.cover_art = Some(slice.to_vec());
                    break;
                }
            }
        }
    }

    metadata
}

fn parse_replay_gain_db(value: &str) -> Option<f32> {
    let gain = value
        .trim()
        .split_ascii_whitespace()
        .next()?
        .parse::<f32>()
        .ok()?;
    gain.is_finite().then_some(gain - 5.0)
}

fn parse_r128_gain_db(value: &str) -> Option<f32> {
    let gain_q7_8 = value.trim().parse::<i32>().ok()?;
    // R128_* tags use signed Q7.8 dB relative to the EBU R128 -23 LUFS
    // reference, which is also the target used by the analyzer.
    Some((gain_q7_8 as f32 / 256.0).clamp(-60.0, 24.0))
}

pub fn probe_from_input(format_ctx: &Input) -> Result<TrackMetadata, ffmpeg_next::Error> {
    build_probe_metadata(format_ctx)
}

pub fn probe_path<P: AsRef<Path>>(path: P) -> Result<TrackMetadata, ffmpeg_next::Error> {
    #[cfg(unix)]
    {
        use std::os::fd::AsRawFd;

        let file = std::fs::File::open(path).map_err(|_| ffmpeg_next::Error::InvalidData)?;
        return probe_fd(file.as_raw_fd());
    }

    #[cfg(not(unix))]
    {
        ffmpeg_next::init()?;
        let format_ctx = ffmpeg_next::format::input(&path)?;
        probe_from_input(&format_ctx)
    }
}

pub fn probe_fd(fd: i32) -> Result<TrackMetadata, ffmpeg_next::Error> {
    let handle = FdFormatHandle::open(fd)?;
    probe_from_input(handle.input().ok_or(ffmpeg_next::Error::Bug)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_replay_gain_values() {
        assert_eq!(parse_replay_gain_db("-7.25 dB"), Some(-12.25));
        assert_eq!(parse_replay_gain_db("3"), Some(-2.0));
        assert_eq!(parse_replay_gain_db("NaN dB"), None);
        assert_eq!(parse_replay_gain_db(""), None);
    }

    #[test]
    fn parses_r128_q7_8_values() {
        assert_eq!(parse_r128_gain_db("-512"), Some(-2.0));
        assert_eq!(parse_r128_gain_db("256"), Some(1.0));
        assert_eq!(parse_r128_gain_db("invalid"), None);
    }
}
