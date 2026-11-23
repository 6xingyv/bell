//! Source-side positioned media stream for cross-device casting.
//!
//! Casting used to copy the container verbatim from byte zero, so a renderer
//! always restarted the track no matter where the source was playing.
//! Repositioning cannot be done by splicing container bytes: only self-delimiting
//! formats (MP3, ADTS) survive that, while MP4/FLAC/OGG hand the renderer a
//! stream with no header and absolute sample offsets that no longer resolve.
//!
//! So the source seeks its own demuxer and remuxes the remaining packets into a
//! fresh streamable Matroska container. Seeking mid-cast keeps the *same* output
//! stream alive and simply continues writing packets from the new position with
//! monotonically increasing timestamps, so the renderer never re-probes and never
//! hears a gap; the control plane tells it which track position that corresponds
//! to.

use std::ffi::CString;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

use ffmpeg_next::ffi::*;
use ffmpeg_next::format::context::Input;
use util::fd_io::FdFormatHandle;

use util::log_i;

const TAG: &str = "mf-cast";

/// No seek requested. Positions are non-negative, so -1 is free as a sentinel.
const NO_SEEK: i64 = -1;

/// Matroska accepts essentially any audio codec and is designed to be streamed,
/// so one output muxer covers every input the library can decode.
const OUTPUT_FORMAT: &str = "matroska";

const AVIO_BUFFER_BYTES: i32 = 32_768;

/// Shared handle so the app can steer a cast that is running on its own thread.
pub struct CastControl {
    pending_seek_ms: AtomicI64,
    cancelled: AtomicBool,
}

impl CastControl {
    fn new() -> Self {
        Self {
            pending_seek_ms: AtomicI64::new(NO_SEEK),
            cancelled: AtomicBool::new(false),
        }
    }

    /// Reposition the running cast. Applied by the pump thread between packets,
    /// because the FFmpeg contexts belong to that thread alone.
    pub fn request_seek(&self, position_ms: u64) {
        self.pending_seek_ms
            .store(i64::try_from(position_ms).unwrap_or(i64::MAX), Ordering::Release);
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }

    fn take_seek(&self) -> Option<i64> {
        let requested = self.pending_seek_ms.swap(NO_SEEK, Ordering::AcqRel);
        (requested != NO_SEEK).then_some(requested)
    }

    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire)
    }
}

/// Write half of the cast: an owned fd the muxer pushes bytes into.
struct SinkIo {
    fd: i32,
}

impl Drop for SinkIo {
    fn drop(&mut self) {
        if self.fd >= 0 {
            unsafe { libc::close(self.fd) };
        }
    }
}

/// # Safety
/// `opaque` is the `SinkIo` handed to `avio_alloc_context`, still alive because
/// [`CastStream`] frees the AVIO context before the sink.
unsafe extern "C" fn sink_write_packet(
    opaque: *mut std::ffi::c_void,
    buffer: *const u8,
    size: i32,
) -> i32 {
    if opaque.is_null() || buffer.is_null() || size <= 0 {
        return 0;
    }
    let sink = unsafe { &mut *(opaque as *mut SinkIo) };
    let mut written = 0usize;
    let total = size as usize;
    while written < total {
        let n = unsafe {
            libc::write(
                sink.fd,
                buffer.add(written) as *const libc::c_void,
                total - written,
            )
        };
        if n <= 0 {
            let errno = std::io::Error::last_os_error();
            if errno.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            // The renderer went away; AVERROR(EPIPE).
            return -32;
        }
        written += n as usize;
    }
    total as i32
}

/// A positioned, seekable-at-the-source media stream feeding one renderer.
pub struct CastStream {
    // Declaration order is drop order: the Input must die before the fd handle
    // that owns its AVIO context and descriptor.
    input: Input,
    _fd_handle: FdFormatHandle,
    output: *mut AVFormatContext,
    avio_ctx: *mut AVIOContext,
    sink: *mut SinkIo,
    audio_stream_index: i32,
    header_written: bool,
    control: Arc<CastControl>,
}

// SAFETY: every FFmpeg context is exclusively owned here and only touched by the
// single thread running `pump`.
unsafe impl Send for CastStream {}

impl CastStream {
    /// Open `input_fd` for demuxing and remux into `sink_fd`, starting at
    /// `start_position_ms`.
    ///
    /// Both descriptors are taken over by the returned stream.
    pub fn open(input_fd: i32, sink_fd: i32, start_position_ms: u64) -> Result<Self, String> {
        ffmpeg_next::init().map_err(|error| error.to_string())?;
        let control = Arc::new(CastControl::new());

        let mut fd_handle =
            FdFormatHandle::open(input_fd).map_err(|error| error.to_string())?;
        let mut input = fd_handle.take_input().map_err(|error| error.to_string())?;

        unsafe {
            let audio_stream_index =
                best_audio_stream(input.as_mut_ptr()).ok_or("no audio stream")?;
            let (output, avio_ctx, sink) = alloc_output(sink_fd)?;

            let mut stream = Self {
                input,
                _fd_handle: fd_handle,
                output,
                avio_ctx,
                sink,
                audio_stream_index,
                header_written: false,
                control,
            };
            stream.configure_output_stream()?;
            if start_position_ms > 0 {
                stream.seek_input(start_position_ms as i64);
            }
            log_i!(
                TAG,
                "cast open: stream {} codec {} from {}ms",
                audio_stream_index,
                (*(*(*(*stream.input.as_mut_ptr())
                    .streams
                    .add(audio_stream_index as usize)))
                .codecpar)
                    .codec_id as i32,
                start_position_ms
            );
            Ok(stream)
        }
    }

    #[must_use]
    pub fn control(&self) -> Arc<CastControl> {
        Arc::clone(&self.control)
    }

    /// Copy the codec parameters across and write the container header.
    unsafe fn configure_output_stream(&mut self) -> Result<(), String> {
        unsafe {
            let in_stream = *(*self.input.as_mut_ptr())
                .streams
                .add(self.audio_stream_index as usize);
            let out_stream = avformat_new_stream(self.output, std::ptr::null());
            if out_stream.is_null() {
                return Err("could not allocate the output stream".into());
            }
            if avcodec_parameters_copy((*out_stream).codecpar, (*in_stream).codecpar) < 0 {
                return Err("could not copy the codec parameters".into());
            }
            // Tags are container specific; Matroska derives its own.
            (*(*out_stream).codecpar).codec_tag = 0;
            (*out_stream).time_base = (*in_stream).time_base;

            if avformat_write_header(self.output, std::ptr::null_mut()) < 0 {
                return Err("could not write the container header".into());
            }
            self.header_written = true;
            Ok(())
        }
    }

    unsafe fn seek_input(&mut self, position_ms: i64) -> bool {
        unsafe {
            let ts = position_ms.saturating_mul(1000);
            let ok = avformat_seek_file(
                self.input.as_mut_ptr(),
                -1,
                i64::MIN,
                ts,
                i64::MAX,
                0,
            ) >= 0;
            if !ok {
                log_i!(TAG, "seek to {}ms failed", position_ms);
            }
            ok
        }
    }

    /// Remux until EOF, cancellation, or the renderer closing its end.
    ///
    /// Output timestamps are kept monotonic across seeks so the renderer sees one
    /// continuous stream instead of a discontinuity it would have to re-probe.
    pub fn pump(&mut self) -> Result<(), String> {
        unsafe {
            let packet = av_packet_alloc();
            if packet.is_null() {
                return Err("could not allocate a packet".into());
            }
            let in_stream =
                *(*self.input.as_mut_ptr()).streams.add(self.audio_stream_index as usize);
            let out_stream = *(*self.output).streams;
            let in_tb = (*in_stream).time_base;
            let out_tb = (*out_stream).time_base;

            // Output timeline cursor and the shift applied to input timestamps,
            // recomputed on every seek so the muxer never sees time go backwards.
            let mut out_cursor: i64 = 0;
            let mut shift: Option<i64> = None;
            let mut written_packets: u64 = 0;
            let mut skipped_packets: u64 = 0;

            let result = loop {
                if self.control.is_cancelled() {
                    break Ok(());
                }
                if let Some(position_ms) = self.control.take_seek() {
                    self.seek_input(position_ms);
                    // Rebase: the next packet continues where the output left off.
                    shift = None;
                }

                let read = av_read_frame(self.input.as_mut_ptr(), packet);
                if read < 0 {
                    if written_packets == 0 && !self.control.is_cancelled() {
                        break Err(format!(
                            "input ended before any packet was written (code {read})"
                        ));
                    }
                    break Ok(());
                }
                if (*packet).stream_index != self.audio_stream_index {
                    skipped_packets += 1;
                    av_packet_unref(packet);
                    continue;
                }

                av_packet_rescale_ts(packet, in_tb, out_tb);
                let pts = if (*packet).pts == AV_NOPTS_VALUE {
                    out_cursor
                } else {
                    (*packet).pts
                };
                let delta = *shift.get_or_insert(out_cursor - pts);
                (*packet).pts = pts + delta;
                (*packet).dts = (*packet).pts;
                (*packet).stream_index = 0;
                (*packet).pos = -1;
                out_cursor = (*packet).pts + (*packet).duration.max(1);

                let first_pts = (*packet).pts;
                let written = av_interleaved_write_frame(self.output, packet);
                av_packet_unref(packet);
                if written < 0 {
                    // Mid-stream EPIPE is the normal "renderer hung up" path;
                    // a sink that dies before a single packet got through means
                    // the renderer never received usable media.
                    log_i!(
                        TAG,
                        "write ended after {} packets (code {})",
                        written_packets,
                        written
                    );
                    const MIN_VIABLE_PACKETS: u64 = 3;
                    if written_packets < MIN_VIABLE_PACKETS {
                        break Err(format!(
                            "sink closed before a viable media flow was established \
                             ({written_packets} packets, code {written})"
                        ));
                    }
                    break Ok(());
                }
                written_packets += 1;
                if written_packets == 1 {
                    log_i!(TAG, "first packet out: pts={} tb={}/{}", first_pts, out_tb.num, out_tb.den);
                }
            };

            log_i!(
                TAG,
                "cast ended: {} audio packets written, {} non-audio skipped",
                written_packets,
                skipped_packets
            );
            av_packet_free(&mut { packet });
            result
        }
    }
}

impl Drop for CastStream {
    fn drop(&mut self) {
        unsafe {
            if self.header_written && !self.output.is_null() {
                av_write_trailer(self.output);
            }
            if !self.output.is_null() {
                avformat_free_context(self.output);
                self.output = std::ptr::null_mut();
            }
            if !self.avio_ctx.is_null() {
                av_free((*self.avio_ctx).buffer as *mut std::ffi::c_void);
                avio_context_free(&mut self.avio_ctx);
            }
            if !self.sink.is_null() {
                drop(Box::from_raw(self.sink));
                self.sink = std::ptr::null_mut();
            }
        }
    }
}

unsafe fn best_audio_stream(input: *mut AVFormatContext) -> Option<i32> {
    unsafe {
        let index = av_find_best_stream(
            input,
            AVMediaType::AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            std::ptr::null_mut(),
            0,
        );
        (index >= 0).then_some(index)
    }
}

unsafe fn alloc_output(
    sink_fd: i32,
) -> Result<(*mut AVFormatContext, *mut AVIOContext, *mut SinkIo), String> {
    unsafe {
        let format_name = CString::new(OUTPUT_FORMAT).map_err(|_| "bad format name".to_string())?;
        let mut output: *mut AVFormatContext = std::ptr::null_mut();
        if avformat_alloc_output_context2(
            &mut output,
            std::ptr::null(),
            format_name.as_ptr(),
            std::ptr::null(),
        ) < 0
            || output.is_null()
        {
            return Err(format!("{OUTPUT_FORMAT} muxer is unavailable"));
        }

        let sink = Box::into_raw(Box::new(SinkIo { fd: sink_fd }));
        let buffer = av_malloc(AVIO_BUFFER_BYTES as usize) as *mut u8;
        if buffer.is_null() {
            avformat_free_context(output);
            drop(Box::from_raw(sink));
            return Err("could not allocate the AVIO buffer".into());
        }
        let avio_ctx = avio_alloc_context(
            buffer,
            AVIO_BUFFER_BYTES,
            1,
            sink as *mut std::ffi::c_void,
            None,
            Some(sink_write_packet),
            None,
        );
        if avio_ctx.is_null() {
            av_free(buffer as *mut std::ffi::c_void);
            avformat_free_context(output);
            drop(Box::from_raw(sink));
            return Err("could not allocate the AVIO context".into());
        }
        // A pipe cannot seek, so the muxer must stay in streaming mode.
        (*avio_ctx).seekable = 0;
        (*output).pb = avio_ctx;
        (*output).flags |= AVFMT_FLAG_CUSTOM_IO;
        // Live pipe: hand each packet to the renderer as it is written instead of
        // letting the muxer accumulate a cluster, and never hold packets back
        // waiting to interleave -- there is only one stream to interleave.
        (*output).flags |= AVFMT_FLAG_FLUSH_PACKETS;
        (*output).max_interleave_delta = 0;
        Ok((output, avio_ctx, sink))
    }
}
