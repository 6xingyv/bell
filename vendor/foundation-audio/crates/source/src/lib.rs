//! FFmpeg-based audio source. Exposes a single `AudioSource` type that
//! can be opened from a filesystem path, an already-open fd (Android
//! ParcelFileDescriptor), or a progressive [`MediaByteSource`] stream
//! (fabric remote media). Owns a unified metadata extraction pipeline and
//! pushes decoded f32 interleaved samples into a ringbuf.

use ffmpeg_next::decoder::Audio;
use ffmpeg_next::format::context::Input;
use ffmpeg_next::software::resampling::Context as ResamplerContext;
use ringbuf::HeapProd;
use ringbuf::traits::Producer;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use types::{SilenceSettings, TrackMetadata};
use util::fd_io::FdFormatHandle;
use util::stream_io::{MediaByteSource, StreamFormatHandle};
use util::{log_e, log_i, log_w};

const TAG: &str = "mf-source";

/// Safety-net timeout for the ring-buffer-full park in [`AudioSource::decode_and_push`].
/// The renderer normally unparks the decoder the moment it frees space; this
/// bound only guards against a missed wake, so it can be generous.
const RING_FULL_PARK_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(100);

mod cast;
mod metadata;
mod silence;
pub use cast::{CastControl, CastStream};
pub use metadata::{probe_fd, probe_from_input, probe_path};
pub use silence::{SilenceSkipper, SilenceTimeline};
pub use util::stream_io::{MediaByteSource as ByteSource, StreamOpenParams};

pub struct AudioSource {
    format_ctx: Option<Input>,
    decoder_ctx: Audio,
    resampler: Option<ResamplerContext>,
    target_sample_rate: u32,
    target_channels: u16,
    _fd_handle: Option<FdFormatHandle>,
    _stream_handle: Option<StreamFormatHandle>,
    /// Decode-side silence removal, when enabled by settings.
    silence: Option<SilenceSkipper>,
    /// Audio decoded past the target during [`skip_forward_ms`](Self::skip_forward_ms);
    /// emitted first by the next [`decode_and_push`](Self::decode_and_push) so the
    /// chunk that crosses the start position is not thrown away.
    pending_lead: Vec<f32>,
}

// SAFETY: all FFmpeg contexts are exclusively owned by AudioSource and are
// moved to one decoder thread; they are never accessed concurrently.
unsafe impl Send for AudioSource {}

impl AudioSource {
    pub fn open_path<P: AsRef<Path>>(path: P) -> Result<(Self, TrackMetadata), ffmpeg_next::Error> {
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;

            // Minimal Android FFmpeg builds often omit the `file` protocol.
            // Open the filesystem path with the OS and feed its descriptor to
            // our custom AVIOContext instead, which also gives path and
            // content-URI playback identical seek/replay semantics.
            let file = std::fs::File::open(path).map_err(|_| ffmpeg_next::Error::InvalidData)?;
            return Self::open_fd(file.as_raw_fd());
        }

        #[cfg(not(unix))]
        {
            ffmpeg_next::init()?;
            let format_ctx = ffmpeg_next::format::input(&path)?;
            let (decoder_ctx, metadata) = build_decoder_and_meta(&format_ctx)?;

            Ok((
                Self {
                    format_ctx: Some(format_ctx),
                    decoder_ctx,
                    resampler: None,
                    target_sample_rate: metadata.sample_rate,
                    target_channels: metadata.channels,
                    _fd_handle: None,
                    _stream_handle: None,
                    silence: None,
                    pending_lead: Vec::new(),
                },
                metadata,
            ))
        }
    }

    pub fn open_fd(fd: i32) -> Result<(Self, TrackMetadata), ffmpeg_next::Error> {
        let mut handle = FdFormatHandle::open(fd)?;
        let format_ctx = handle.take_input()?;
        let (decoder_ctx, metadata) = build_decoder_and_meta(&format_ctx)?;

        Ok((
            Self {
                format_ctx: Some(format_ctx),
                decoder_ctx,
                resampler: None,
                target_sample_rate: metadata.sample_rate,
                target_channels: metadata.channels,
                _fd_handle: Some(handle),
                _stream_handle: None,
                silence: None,
                pending_lead: Vec::new(),
            },
            metadata,
        ))
    }

    /// Open a progressive or in-memory media byte stream for demux/decode.
    ///
    /// This is the fabric remote path: the ability layer feeds compressed
    /// container bytes from a reliable stream; foundation.audio never sees an
    /// fd and never materializes the full object unless the source is seekable.
    pub fn open_stream(
        source: Box<dyn MediaByteSource>,
    ) -> Result<(Self, TrackMetadata), ffmpeg_next::Error> {
        let mut handle = StreamFormatHandle::open(source)?;
        let format_ctx = handle.take_input()?;
        let (decoder_ctx, metadata) = build_decoder_and_meta(&format_ctx)?;

        Ok((
            Self {
                format_ctx: Some(format_ctx),
                decoder_ctx,
                resampler: None,
                target_sample_rate: metadata.sample_rate,
                target_channels: metadata.channels,
                _fd_handle: None,
                _stream_handle: Some(handle),
                silence: None,
                pending_lead: Vec::new(),
            },
            metadata,
        ))
    }

    /// Zero-probe variant of [`open_stream`](Self::open_stream): the caller
    /// supplies the exact container/codec parameters the source announced, so
    /// FFmpeg selects the demuxer directly and bounds stream analysis instead of
    /// blind-probing up to 32 MiB (task 1). Falls back to probing when the
    /// params carry no container hint.
    pub fn open_stream_with_params(
        source: Box<dyn MediaByteSource>,
        params: &StreamOpenParams,
    ) -> Result<(Self, TrackMetadata), ffmpeg_next::Error> {
        // Progressive streams cannot be re-read: buffer what the bounded
        // zero-probe consumes so a failed open can fall back to a blind probe
        // instead of failing the whole attach.
        let source: Box<dyn MediaByteSource> =
            if params.has_container() && !source.is_seekable() {
                Box::new(util::stream_io::RestartableByteSource::new(source))
            } else {
                source
            };
        let mut handle = StreamFormatHandle::open_with_params(source, Some(params))?;
        let format_ctx = handle.take_input()?;
        let (decoder_ctx, metadata) = build_decoder_and_meta(&format_ctx)?;

        Ok((
            Self {
                format_ctx: Some(format_ctx),
                decoder_ctx,
                resampler: None,
                target_sample_rate: metadata.sample_rate,
                target_channels: metadata.channels,
                _fd_handle: None,
                _stream_handle: Some(handle),
                silence: None,
                pending_lead: Vec::new(),
            },
            metadata,
        ))
    }

    /// Extract the current source's exact demux/codec parameters so a source
    /// engine can announce them for zero-probe renderer startup (task 1). The
    /// `container` is the demuxer's first short name (what `av_find_input_format`
    /// expects), e.g. `"mov"` out of `"mov,mp4,m4a,…"`.
    pub fn stream_parameters(&self) -> StreamOpenParams {
        let Some(format_ctx) = self.format_ctx.as_ref() else {
            return StreamOpenParams::default();
        };
        // SAFETY: format_ctx owns a valid AVFormatContext; iformat/codecpar are
        // populated by the demuxer and read-only here.
        unsafe {
            let iformat = (*format_ctx.as_ptr()).iformat;
            let container = if iformat.is_null() || (*iformat).name.is_null() {
                None
            } else {
                std::ffi::CStr::from_ptr((*iformat).name)
                    .to_str()
                    .ok()
                    .and_then(|name| name.split(',').next())
                    .filter(|first| !first.is_empty())
                    .map(str::to_owned)
            };

            let Some(stream) = format_ctx
                .streams()
                .best(ffmpeg_next::media::Type::Audio)
            else {
                return StreamOpenParams {
                    container,
                    ..Default::default()
                };
            };

            let par = &*stream.parameters().as_ptr();
            let extradata = if par.extradata.is_null() || par.extradata_size <= 0 {
                Vec::new()
            } else {
                std::slice::from_raw_parts(par.extradata, par.extradata_size as usize).to_vec()
            };
            StreamOpenParams {
                container,
                codec_id: par.codec_id as i32,
                sample_rate: par.sample_rate,
                channels: par.ch_layout.nb_channels,
                channel_layout: par.ch_layout.u.mask,
                sample_format: par.format,
                bit_rate: par.bit_rate,
                bits_per_coded_sample: par.bits_per_coded_sample,
                block_align: par.block_align,
                frame_size: par.frame_size,
                extradata,
                probe_bytes: None,
            }
        }
    }

    /// Enable decode-side silence removal: leading/trailing silence is trimmed
    /// and mid-track silence longer than the configured minimum is collapsed, so
    /// only audible audio reaches the ring buffer (the rendered frame count then
    /// *is* the trimmed timeline). `timeline` receives checkpoints so a seek in
    /// the trimmed timeline can be mapped back to the container position.
    ///
    /// Must be called after [`set_targets`](Self::set_targets).
    pub fn set_silence_removal(
        &mut self,
        settings: SilenceSettings,
        timeline: Arc<SilenceTimeline>,
    ) {
        if !settings.enabled {
            self.silence = None;
            return;
        }
        let min_frames = settings.min_silence_frames(self.target_sample_rate);
        // Checkpoint about twice per second of trimmed output.
        let interval = (u64::from(self.target_sample_rate) / 2).max(1);
        self.silence = Some(
            SilenceSkipper::new(
                settings.threshold_linear(),
                min_frames,
                self.target_channels as usize,
            )
            .with_timeline(timeline, interval),
        );
    }

    /// Set the resampler's output targets. Must be called before `decode_and_push`.
    pub fn set_targets(&mut self, sample_rate: u32, channels: u16) {
        self.target_sample_rate = sample_rate;
        self.target_channels = channels;
        self.resampler = None;
    }

    pub fn seek_to_ms(&mut self, position_ms: u64) -> Result<(), ffmpeg_next::Error> {
        let format_ctx = self.format_ctx.as_mut().unwrap();
        let ts_us = position_ms as i64 * 1000;
        unsafe {
            let ret = ffmpeg_next::ffi::avformat_seek_file(
                format_ctx.as_mut_ptr(),
                -1,
                i64::MIN,
                ts_us,
                i64::MAX,
                0,
            );
            if ret < 0 {
                return Err(ffmpeg_next::Error::from(ret));
            }
        }
        self.decoder_ctx.flush();
        Ok(())
    }

    /// Decode and discard audio until `position_ms`, returning the position
    /// actually reached.
    ///
    /// Progressive streams (fabric media fds) have no seek callback, so
    /// [`seek_to_ms`](Self::seek_to_ms) fails on them: the container arrives from
    /// byte zero and the only way to start mid-track is to throw the head away.
    /// Call after [`set_targets`](Self::set_targets) -- the frame count is
    /// measured in output frames.
    pub fn skip_forward_ms(&mut self, position_ms: u64) -> Result<u64, ffmpeg_next::Error> {
        if position_ms == 0 {
            return Ok(0);
        }
        let channels = u64::from(self.target_channels.max(1));
        let sample_rate = u64::from(self.target_sample_rate.max(1));
        let target_frames = sample_rate.saturating_mul(position_ms) / 1000;
        let mut dropped_frames = 0u64;
        // Audio past the target inside the chunk that crosses it is kept and
        // emitted first by the next decode_and_push, not thrown away.
        let mut leftover: Vec<f32> = Vec::new();
        self.decode_interleaved(Arc::new(AtomicBool::new(false)), |data| {
            let frames = data.len() as u64 / channels;
            if dropped_frames + frames < target_frames {
                dropped_frames += frames;
                return true;
            }
            let keep_from = ((target_frames - dropped_frames) * channels) as usize;
            leftover.extend_from_slice(&data[keep_from.min(data.len())..]);
            dropped_frames = target_frames;
            false
        })?;
        self.pending_lead = leftover;
        Ok(dropped_frames.saturating_mul(1000) / sample_rate)
    }

    /// Drain the source into `producer`. Returns when EOF, or when
    /// `cancel` is flipped to true.
    pub fn decode_and_push(
        &mut self,
        producer: &mut HeapProd<f32>,
        cancel: Arc<AtomicBool>,
    ) -> Result<(), ffmpeg_next::Error> {
        let push_cancel = cancel.clone();
        // Taken out of `self` so the `&mut self` decode borrow does not conflict.
        let mut skipper = self.silence.take();

        let mut push = |data: &[f32]| -> bool {
            let mut remaining = data;
            while !remaining.is_empty() {
                if push_cancel.load(Ordering::Relaxed) {
                    return false;
                }
                let pushed = producer.push_slice(remaining);
                remaining = &remaining[pushed..];
                if !remaining.is_empty() {
                    // Ring buffer full: block until the renderer consumes and
                    // unparks this decoder thread (task 5). The timeout is only a
                    // safety net against a missed wake — steady state is fully
                    // event-driven, so no fixed 5 ms polling and no needless CPU
                    // wakeups (which drained battery on mobile). `park_timeout`
                    // consumes any unpark token set before we parked, so a wake
                    // that races ahead of the park is never lost.
                    std::thread::park_timeout(RING_FULL_PARK_TIMEOUT);
                }
            }
            true
        };

        let mut consume = |data: &[f32]| match skipper.as_mut() {
            Some(skipper) => skipper.push(data, &mut push),
            None => push(data),
        };

        // Audio retained past the skip_forward_ms target goes out first.
        let pending = std::mem::take(&mut self.pending_lead);
        if !pending.is_empty() && !consume(&pending) {
            self.silence = skipper;
            return Ok(());
        }

        // With silence removal on, only audible audio reaches the ring buffer.
        let result = self.decode_interleaved(cancel, &mut consume);

        if let Some(skipper) = skipper.as_mut() {
            // Trailing silence is dropped; records the final trimmed length.
            skipper.finish();
        }
        self.silence = skipper;
        result
    }

    /// Decode the complete source into packed, interleaved f32 chunks.
    ///
    /// The callback runs synchronously on the decoder thread and must consume
    /// the slice before returning. Returning `false` stops decoding cleanly.
    /// Playback uses this through [decode_and_push], while offline analysis can
    /// feed the exact same resampled PCM directly into a loudness meter without
    /// an intermediate ring buffer or copy.
    pub fn decode_interleaved<F>(
        &mut self,
        cancel: Arc<AtomicBool>,
        mut consume: F,
    ) -> Result<(), ffmpeg_next::Error>
    where
        F: FnMut(&[f32]) -> bool,
    {
        log_i!(
            TAG,
            "decode_interleaved start: target_sr={}, target_ch={}",
            self.target_sample_rate,
            self.target_channels
        );

        let format_ctx = self.format_ctx.as_mut().unwrap();
        let stream_index = format_ctx
            .streams()
            .best(ffmpeg_next::media::Type::Audio)
            .ok_or(ffmpeg_next::Error::StreamNotFound)?
            .index();

        let mut packets = format_ctx.packets();
        // Reused across every packet and the EOF drain: `receive_frame` unrefs
        // the frame each call, so one frame avoids an AVFrame allocation per
        // packet on the decode thread (allocator churn matters on mobile).
        let mut decoded = ffmpeg_next::frame::Audio::empty();

        while let Some((stream, packet)) = packets.next() {
            if cancel.load(Ordering::Relaxed) {
                break;
            }
            if stream.index() != stream_index {
                continue;
            }

            if self.decoder_ctx.send_packet(&packet).is_err() {
                continue;
            }

            while self.decoder_ctx.receive_frame(&mut decoded).is_ok() {
                if cancel.load(Ordering::Relaxed) {
                    return Ok(());
                }
                if !consume_frame(
                    &mut self.resampler,
                    self.target_sample_rate,
                    self.target_channels,
                    &decoded,
                    &mut consume,
                )? {
                    return Ok(());
                }
            }
        }

        if cancel.load(Ordering::Relaxed) {
            return Ok(());
        }

        log_i!(TAG, "decode_interleaved: flushing EOF frames");
        let _ = self.decoder_ctx.send_eof();
        while self.decoder_ctx.receive_frame(&mut decoded).is_ok() {
            if cancel.load(Ordering::Relaxed) {
                return Ok(());
            }
            if !consume_frame(
                &mut self.resampler,
                self.target_sample_rate,
                self.target_channels,
                &decoded,
                &mut consume,
            )? {
                return Ok(());
            }
        }

        // Drain the resampler's internal delay: with rate conversion swr holds
        // back the tail of the track, which would otherwise be dropped.
        if let Some(resampler) = self.resampler.as_mut() {
            loop {
                let mut flushed = ffmpeg_next::frame::Audio::empty();
                if resampler.flush(&mut flushed).is_err() {
                    break;
                }
                match emit_packed(&flushed, &mut consume)? {
                    EmitOutcome::Empty => break,
                    EmitOutcome::Stopped => return Ok(()),
                    EmitOutcome::Consumed => {}
                }
            }
        }
        log_i!(TAG, "decode_interleaved end");
        Ok(())
    }
}

fn build_decoder_and_meta(
    format_ctx: &Input,
) -> Result<(Audio, TrackMetadata), ffmpeg_next::Error> {
    let stream = format_ctx
        .streams()
        .best(ffmpeg_next::media::Type::Audio)
        .ok_or(ffmpeg_next::Error::StreamNotFound)?;

    let context = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters())?;
    let decoder_ctx = context.decoder().audio()?;

    let metadata = metadata::build_from_input(format_ctx, &decoder_ctx);
    Ok((decoder_ctx, metadata))
}

fn consume_frame<F>(
    resampler_slot: &mut Option<ResamplerContext>,
    target_sr: u32,
    target_ch: u16,
    decoded: &ffmpeg_next::frame::Audio,
    consume: &mut F,
) -> Result<bool, ffmpeg_next::Error>
where
    F: FnMut(&[f32]) -> bool,
{
    if resampler_slot.is_none() {
        let mut layout = decoded.channel_layout();
        let channels = decoded.channels() as i32;
        let rate = if decoded.rate() > 0 {
            decoded.rate()
        } else {
            target_sr
        };
        if layout.is_empty() {
            layout = ffmpeg_next::channel_layout::ChannelLayout::default(if channels > 0 {
                channels
            } else {
                2
            });
        }
        let target_layout = ffmpeg_next::channel_layout::ChannelLayout::default(target_ch as i32);
        match ResamplerContext::get(
            decoded.format(),
            layout,
            rate,
            ffmpeg_next::format::Sample::F32(ffmpeg_next::format::sample::Type::Packed),
            target_layout,
            target_sr,
        ) {
            Ok(r) => *resampler_slot = Some(r),
            Err(e) => {
                log_e!(TAG, "resampler init failed: {:?}", e);
                return Ok(true);
            }
        }
    }

    let Some(resampler) = resampler_slot.as_mut() else {
        return Ok(true);
    };

    let mut resampled = ffmpeg_next::frame::Audio::empty();
    if resampler.run(decoded, &mut resampled).is_err() {
        log_w!(TAG, "resampler.run failed");
        return Ok(true);
    }

    Ok(!matches!(
        emit_packed(&resampled, consume)?,
        EmitOutcome::Stopped
    ))
}

enum EmitOutcome {
    /// The frame carried no samples.
    Empty,
    /// Samples were handed to the consumer, which wants more.
    Consumed,
    /// The consumer asked to stop.
    Stopped,
}

/// Hand one packed-f32 resampled frame to `consume`.
fn emit_packed<F>(
    resampled: &ffmpeg_next::frame::Audio,
    consume: &mut F,
) -> Result<EmitOutcome, ffmpeg_next::Error>
where
    F: FnMut(&[f32]) -> bool,
{
    let nb_samples = resampled.samples();
    let channels = resampled.channels();
    let total = nb_samples * channels as usize;
    if total == 0 {
        return Ok(EmitOutcome::Empty);
    }

    if !resampled.is_packed() || resampled.planes() == 0 {
        return Err(ffmpeg_next::Error::InvalidData);
    }
    let samples = bytemuck::try_cast_slice::<u8, f32>(resampled.data(0))
        .map_err(|_| ffmpeg_next::Error::InvalidData)?;
    let data = samples
        .get(..total)
        .ok_or(ffmpeg_next::Error::InvalidData)?;
    Ok(if consume(data) {
        EmitOutcome::Consumed
    } else {
        EmitOutcome::Stopped
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn make_pcm_wav(sample_rate: u32, channels: u16, samples: &[i16]) -> Vec<u8> {
        let bits = 16u16;
        let block_align = channels * bits / 8;
        let byte_rate = sample_rate * u32::from(block_align);
        let data_len = (samples.len() * 2) as u32;
        let mut v = Vec::new();
        v.extend_from_slice(b"RIFF");
        v.extend_from_slice(&(36 + data_len).to_le_bytes());
        v.extend_from_slice(b"WAVE");
        v.extend_from_slice(b"fmt ");
        v.extend_from_slice(&16u32.to_le_bytes());
        v.extend_from_slice(&1u16.to_le_bytes()); // PCM
        v.extend_from_slice(&channels.to_le_bytes());
        v.extend_from_slice(&sample_rate.to_le_bytes());
        v.extend_from_slice(&byte_rate.to_le_bytes());
        v.extend_from_slice(&block_align.to_le_bytes());
        v.extend_from_slice(&bits.to_le_bytes());
        v.extend_from_slice(b"data");
        v.extend_from_slice(&data_len.to_le_bytes());
        for s in samples {
            v.extend_from_slice(&s.to_le_bytes());
        }
        v
    }

    fn decode_runs_ok(mut src: AudioSource) {
        src.set_targets(44_100, 2);
        // The pipeline must run to EOF without a hard decode error on the
        // zero-probe-opened source.
        src.decode_interleaved(Arc::new(AtomicBool::new(false)), |_| true)
            .unwrap();
    }

    #[test]
    fn stream_parameters_describe_the_container_and_codec() {
        let wav = make_pcm_wav(8000, 1, &vec![0i16; 800]);
        let (src, _meta) = AudioSource::open_stream(Box::new(Cursor::new(wav))).unwrap();
        let params = src.stream_parameters();
        assert_eq!(params.container.as_deref(), Some("wav"));
        assert_eq!(params.sample_rate, 8000);
        assert_eq!(params.channels, 1);
        assert!(params.codec_id != 0, "codec id should be resolved");
    }

    /// A progressive stream: no seeking, no known length — like a fabric pipe.
    struct PipeLike(Cursor<Vec<u8>>);

    impl MediaByteSource for PipeLike {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            std::io::Read::read(&mut self.0, buf)
        }
        fn seek(&mut self, _pos: std::io::SeekFrom) -> std::io::Result<u64> {
            Err(std::io::Error::new(
                std::io::ErrorKind::Unsupported,
                "progressive",
            ))
        }
    }

    #[test]
    fn wrong_zero_probe_hint_falls_back_to_blind_probe_on_a_pipe() {
        let wav = make_pcm_wav(8000, 1, &vec![0i16; 800]);
        let params = StreamOpenParams {
            // A demuxer that will not open WAV bytes.
            container: Some("flac".into()),
            ..Default::default()
        };
        let (src, meta) =
            AudioSource::open_stream_with_params(Box::new(PipeLike(Cursor::new(wav))), &params)
                .expect("blind-probe fallback must open the stream");
        assert_eq!(meta.sample_rate, 8000);
        decode_runs_ok(src);
    }

    #[test]
    fn zero_probe_open_matches_full_probe_and_decodes() {
        let samples: Vec<i16> = (0..4000)
            .map(|i| ((i as f32 * 0.05).sin() * 8000.0) as i16)
            .collect();
        let wav = make_pcm_wav(8000, 1, &samples);

        // Announce params from a normal probe of the same content.
        let (probe_src, probe_meta) =
            AudioSource::open_stream(Box::new(Cursor::new(wav.clone()))).unwrap();
        let params = probe_src.stream_parameters();
        drop(probe_src);

        // Zero-probe open with the announced params must yield an equivalent
        // source: same technical metadata and the same demux/codec parameters,
        // proving the demuxer was selected and configured without a blind probe.
        let (zero_src, zero_meta) =
            AudioSource::open_stream_with_params(Box::new(Cursor::new(wav.clone())), &params)
                .unwrap();
        assert_eq!(zero_meta.sample_rate, probe_meta.sample_rate);
        assert_eq!(zero_meta.channels, probe_meta.channels);
        assert_eq!(zero_meta.duration_ms, probe_meta.duration_ms);

        let zero_params = zero_src.stream_parameters();
        assert_eq!(zero_params.container, params.container);
        assert_eq!(zero_params.codec_id, params.codec_id);
        assert_eq!(zero_params.sample_rate, params.sample_rate);
        assert_eq!(zero_params.channels, params.channels);

        // And the full decode pipeline runs to EOF on the zero-probe source.
        decode_runs_ok(zero_src);
    }
}
