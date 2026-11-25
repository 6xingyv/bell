use super::fd_io::{cleanup_fd_io, open_format_ctx_from_fd};
use super::*;

impl FFmpegDecoder {
pub fn probe_metadata<P: AsRef<Path>>(path: P) -> Result<TrackMetadata, ffmpeg_next::Error> {
        ffmpeg_next::init()?;
        let format_ctx = ffmpeg_next::format::input(&path)?;
        let stream = format_ctx.streams().best(ffmpeg_next::media::Type::Audio)
            .ok_or(ffmpeg_next::Error::StreamNotFound)?;
        
        let context = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters())?;
        let decoder_ctx = context.decoder().audio()?;
        
        let mut metadata = TrackMetadata {
            duration_ms: format_ctx.duration() as u64 / 1000,
            sample_rate: decoder_ctx.rate(),
            channels: decoder_ctx.channels() as u16,
            ..Default::default()
        };

        let dict = format_ctx.metadata();
        metadata.title = dict.get("title").map(|v| v.to_string());
        metadata.artist = dict.get("artist").map(|v| v.to_string());
        metadata.album = dict.get("album").map(|v| v.to_string());
        metadata.genre = dict.get("genre").map(|v| v.to_string());
        metadata.year = dict.get("date").and_then(|v| v.parse::<i32>().ok());
        metadata.lyrics = dict.get("LYRICS").map(|v| v.to_string());

        // Extract cover art from attached pictures
        for s in format_ctx.streams() {
            unsafe {
                let av_stream = s.as_ptr();
                let disposition = (*av_stream).disposition;
                if (disposition & ffmpeg_next::ffi::AV_DISPOSITION_ATTACHED_PIC) != 0 {
                    let packet = &(*av_stream).attached_pic;
                    if !packet.data.is_null() && packet.size > 0 {
                        let slice = std::slice::from_raw_parts(packet.data, packet.size as usize);
                        metadata.cover_art = Some(slice.to_vec());
                        break;
                    }
                }
            }
        }

        Ok(metadata)
    }

    pub fn probe_metadata_fd(fd: i32) -> Result<TrackMetadata, ffmpeg_next::Error> {
        let (format_ctx, avio_ctx, io_ctx) = open_format_ctx_from_fd(fd)?;
        let stream = format_ctx.streams().best(ffmpeg_next::media::Type::Audio)
            .ok_or(ffmpeg_next::Error::StreamNotFound)?;
        
        let context = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters())?;
        let decoder_ctx = context.decoder().audio()?;
        
        let mut metadata = TrackMetadata {
            duration_ms: format_ctx.duration() as u64 / 1000,
            sample_rate: decoder_ctx.rate(),
            channels: decoder_ctx.channels() as u16,
            ..Default::default()
        };

        let dict = format_ctx.metadata();
        metadata.title = dict.get("title").map(|v| v.to_string());
        metadata.artist = dict.get("artist").map(|v| v.to_string());
        metadata.album = dict.get("album").map(|v| v.to_string());
        metadata.genre = dict.get("genre").map(|v| v.to_string());
        metadata.year = dict.get("date").and_then(|v| v.parse::<i32>().ok());
        metadata.lyrics = dict.get("LYRICS").map(|v| v.to_string());

        // Extract cover art from attached pictures
        for s in format_ctx.streams() {
            unsafe {
                let av_stream = s.as_ptr();
                let disposition = (*av_stream).disposition;
                if (disposition & ffmpeg_next::ffi::AV_DISPOSITION_ATTACHED_PIC) != 0 {
                    let packet = &(*av_stream).attached_pic;
                    if !packet.data.is_null() && packet.size > 0 {
                        let slice = std::slice::from_raw_parts(packet.data, packet.size as usize);
                        metadata.cover_art = Some(slice.to_vec());
                        break;
                    }
                }
            }
        }

        // 先 drop format_ctx（执行 avformat_close_input），再释放 IO 资源
        drop(format_ctx);
        unsafe { cleanup_fd_io(avio_ctx, io_ctx); }

        Ok(metadata)
    }
}

