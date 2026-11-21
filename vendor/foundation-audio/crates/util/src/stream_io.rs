//! Byte-stream AVIOContext for progressive/remote media.
//!
//! Unlike [`crate::fd_io`], this path never takes ownership of a file
//! descriptor. Callers supply a [`MediaByteSource`] (network pipe, memory
//! buffer, custom reader). FFmpeg demuxes through AVIO callbacks so remote
//! fabric streams can be decoded without materializing a local fd.

use ffmpeg_next::ffi::{
    AVFormatContext, AVIOContext, AVSEEK_FORCE, AVSEEK_SIZE, av_find_input_format, av_free,
    av_freep, av_malloc, avformat_alloc_context, avformat_close_input, avformat_find_stream_info,
    avformat_open_input, avio_alloc_context, avio_context_free,
};
use ffmpeg_next::format::context::Input;
use std::ffi::CString;
use std::io::{self, SeekFrom};

/// Exact demux/decode parameters the source ships on the control plane so the
/// renderer can start FFmpeg without a blind stream probe (task 1, zero-probe).
///
/// The dominant startup stall on a progressive network stream is
/// `avformat_find_stream_info` reading up to `probesize` (32 MiB) before it will
/// name the streams. Knowing the container lets us pick the demuxer directly
/// (no format guessing) and bound the probe to a few KB, so first audio is ready
/// almost immediately instead of after megabytes of buffering.
#[derive(Clone, Debug, Default)]
pub struct StreamOpenParams {
    /// FFmpeg demuxer short name (`"mp3"`, `"flac"`, `"ogg"`, `"aac"`,
    /// `"matroska,webm"`, `"mov,mp4,m4a,3gp,3g2,mj2"`, …). Skips format probing.
    pub container: Option<String>,
    pub codec_id: i32,
    pub sample_rate: i32,
    pub channels: i32,
    pub channel_layout: u64,
    pub sample_format: i32,
    pub bit_rate: i64,
    pub bits_per_coded_sample: i32,
    pub block_align: i32,
    pub frame_size: i32,
    pub extradata: Vec<u8>,
    /// Upper bound on bytes FFmpeg may read while analyzing streams. `None`
    /// applies [`StreamOpenParams::DEFAULT_PROBE_BYTES`].
    pub probe_bytes: Option<u32>,
}

impl StreamOpenParams {
    /// Bounded probe used when a container hint is available: enough for the
    /// demuxer to see the first frame(s), far below the 32 MiB blind default.
    pub const DEFAULT_PROBE_BYTES: u32 = 64 * 1024;

    /// True when the container is known well enough to select a demuxer up front.
    #[must_use]
    pub fn has_container(&self) -> bool {
        self.container.as_deref().is_some_and(|c| !c.is_empty())
    }

    fn probe_bytes(&self) -> i64 {
        i64::from(self.probe_bytes.unwrap_or(Self::DEFAULT_PROBE_BYTES))
    }
}

/// Byte source for demuxing. Implemented by memory buffers, pipes, and
/// platform bridges that expose a fabric reliable stream as reads.
pub trait MediaByteSource: Send {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize>;

    /// Seek when the underlying transport allows it. Progressive fabric media
    /// streams typically return an error and report `is_seekable() == false`.
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64>;

    fn is_seekable(&self) -> bool {
        false
    }

    /// Total length when known up front (enables better probing). Unknown
    /// progressive streams return `None`.
    fn byte_len(&self) -> Option<u64> {
        None
    }

    /// Rewind to byte 0 so the container can be re-opened (used by the
    /// zero-probe → blind-probe fallback). Sources that cannot replay what was
    /// already consumed return `false`.
    fn rewind_to_start(&mut self) -> bool {
        false
    }

    /// Called once the container opened successfully: replay support is no
    /// longer needed, so any replay buffer can be released.
    fn disable_rewind(&mut self) {}
}

impl MediaByteSource for io::Cursor<Vec<u8>> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        io::Read::read(self, buf)
    }

    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        io::Seek::seek(self, pos)
    }

    fn is_seekable(&self) -> bool {
        true
    }

    fn byte_len(&self) -> Option<u64> {
        Some(self.get_ref().len() as u64)
    }
}

impl MediaByteSource for io::Cursor<&[u8]> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        io::Read::read(self, buf)
    }

    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        io::Seek::seek(self, pos)
    }

    fn is_seekable(&self) -> bool {
        true
    }

    fn byte_len(&self) -> Option<u64> {
        Some(self.get_ref().len() as u64)
    }
}

/// Progressive reader over an owned POSIX file descriptor (pipe or file).
///
/// Used by Android fabric media streams: Hub hands a pipe fd, ability reads
/// compressed container bytes without treating the fd as a seekable local file.
pub struct OwnedFdByteSource {
    fd: i32,
    remaining: Option<u64>,
    seekable: bool,
}

impl OwnedFdByteSource {
    /// Takes ownership of `fd` (will be closed on drop).
    pub fn new(fd: i32) -> Self {
        Self {
            fd,
            remaining: None,
            seekable: false,
        }
    }

    /// Progressive object with known remaining length (after a framing header).
    pub fn with_byte_length(fd: i32, byte_length: u64) -> Self {
        Self {
            fd,
            remaining: (byte_length > 0).then_some(byte_length),
            seekable: false,
        }
    }

    pub fn fd(&self) -> i32 {
        self.fd
    }
}

impl Drop for OwnedFdByteSource {
    fn drop(&mut self) {
        if self.fd >= 0 {
            unsafe {
                libc::close(self.fd);
            }
            self.fd = -1;
        }
    }
}

// SAFETY: exclusive ownership of the fd; moved to one decoder thread.
unsafe impl Send for OwnedFdByteSource {}

impl MediaByteSource for OwnedFdByteSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        if let Some(0) = self.remaining {
            return Ok(0);
        }
        let max = match self.remaining {
            Some(left) => usize::try_from(left).unwrap_or(buf.len()).min(buf.len()),
            None => buf.len(),
        };
        loop {
            // Windows CRT `read` takes `c_uint`; Unix takes `size_t`.
            #[cfg(windows)]
            let count = max as u32;
            #[cfg(not(windows))]
            let count = max as libc::size_t;
            let n = unsafe {
                libc::read(self.fd, buf.as_mut_ptr() as *mut libc::c_void, count)
            };
            if n < 0 {
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(err);
            }
            let n = n as usize;
            if let Some(left) = self.remaining.as_mut() {
                *left = left.saturating_sub(n as u64);
            }
            return Ok(n);
        }
    }

    fn seek(&mut self, _pos: SeekFrom) -> io::Result<u64> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "progressive fabric fd stream is not seekable",
        ))
    }

    fn is_seekable(&self) -> bool {
        self.seekable
    }

    fn byte_len(&self) -> Option<u64> {
        self.remaining
    }
}

/// Wraps a non-seekable [`MediaByteSource`] and records what is read so the
/// stream can be replayed from byte 0 once — the zero-probe open path uses this
/// to fall back to a blind probe when the announced parameters turn out not to
/// open the container (e.g. a header larger than the bounded probe).
///
/// Recording stops (and the buffer is dropped) either when the container opens
/// successfully ([`MediaByteSource::disable_rewind`]) or when more than
/// `REPLAY_CAP_BYTES` have been consumed, after which rewinding reports `false`.
pub struct RestartableByteSource {
    source: Box<dyn MediaByteSource>,
    recorded: Vec<u8>,
    /// `Some(offset)` while replaying previously recorded bytes.
    replay_pos: Option<usize>,
    recording: bool,
}

impl RestartableByteSource {
    /// Upper bound on the replay buffer; beyond this the fallback is abandoned
    /// rather than holding arbitrary amounts of the stream in memory.
    pub const REPLAY_CAP_BYTES: usize = 16 * 1024 * 1024;

    pub fn new(source: Box<dyn MediaByteSource>) -> Self {
        Self {
            source,
            recorded: Vec::new(),
            replay_pos: None,
            recording: true,
        }
    }
}

impl MediaByteSource for RestartableByteSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if let Some(pos) = self.replay_pos {
            if pos < self.recorded.len() {
                let n = (self.recorded.len() - pos).min(buf.len());
                buf[..n].copy_from_slice(&self.recorded[pos..pos + n]);
                self.replay_pos = Some(pos + n);
                return Ok(n);
            }
            self.replay_pos = None;
            if !self.recording {
                self.recorded = Vec::new();
            }
        }
        let n = self.source.read(buf)?;
        if self.recording {
            if self.recorded.len() + n <= Self::REPLAY_CAP_BYTES {
                self.recorded.extend_from_slice(&buf[..n]);
            } else {
                self.recording = false;
                self.recorded = Vec::new();
            }
        }
        Ok(n)
    }

    fn seek(&mut self, _pos: SeekFrom) -> io::Result<u64> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "restartable stream only rewinds to the start",
        ))
    }

    fn byte_len(&self) -> Option<u64> {
        self.source
            .byte_len()
            .map(|remaining| remaining + self.recorded.len() as u64)
    }

    fn rewind_to_start(&mut self) -> bool {
        if !self.recording {
            return false;
        }
        self.replay_pos = Some(0);
        true
    }

    fn disable_rewind(&mut self) {
        self.recording = false;
        if self.replay_pos.is_none() {
            self.recorded = Vec::new();
        }
    }
}

struct StreamIoContext {
    source: Box<dyn MediaByteSource>,
    position: u64,
    file_size: i64,
    seekable: bool,
}

/// Owns FFmpeg allocations for a stream-backed `Input`.
///
/// Drop order: `Input` first, then AVIO, then the byte source.
pub struct StreamFormatHandle {
    input: Option<Input>,
    avio_ctx: *mut AVIOContext,
    io_ctx: *mut StreamIoContext,
}

// SAFETY: exclusive ownership; moved to a single decoder thread.
unsafe impl Send for StreamFormatHandle {}

impl StreamFormatHandle {
    /// Open with FFmpeg's default blind probe (up to 32 MiB). Use when the
    /// container is unknown.
    pub fn open(source: Box<dyn MediaByteSource>) -> Result<Self, ffmpeg_next::Error> {
        Self::open_with_params(source, None)
    }

    /// Zero-probe open (task 1): when `params` carry a container hint, select the
    /// demuxer directly and bound stream analysis to a few KB instead of the
    /// 32 MiB blind default, so first audio is ready almost immediately. Falls
    /// back to the blind probe when no container is known.
    pub fn open_with_params(
        source: Box<dyn MediaByteSource>,
        params: Option<&StreamOpenParams>,
    ) -> Result<Self, ffmpeg_next::Error> {
        let zero_probe = params.is_some_and(StreamOpenParams::has_container);
        match Self::try_open(source, params) {
            Ok(handle) => Ok(handle),
            // The announced parameters did not open the container (bounded
            // probe too small, stale demuxer name, …). When the stream can be
            // replayed from byte 0, retry once with the blind probe so the
            // track still plays instead of failing the attach.
            Err((error, Some(mut source))) if zero_probe => {
                if !source.rewind_to_start() {
                    return Err(error);
                }
                crate::log_w!(
                    "stream-io",
                    "zero-probe open failed ({error:?}); falling back to blind probe"
                );
                Self::try_open(source, None).map_err(|(error, _)| error)
            }
            Err((error, _)) => Err(error),
        }
    }

    fn try_open(
        source: Box<dyn MediaByteSource>,
        params: Option<&StreamOpenParams>,
    ) -> Result<Self, (ffmpeg_next::Error, Option<Box<dyn MediaByteSource>>)> {
        ffmpeg_next::init().map_err(|error| (error, None))?;
        let seekable = source.is_seekable();
        let file_size = source
            .byte_len()
            .map(|len| i64::try_from(len).unwrap_or(-1))
            .unwrap_or(-1);

        // Resolve the demuxer up front from the container hint. Kept alive (the
        // CString) until after avformat_open_input has consumed the name.
        let container = params
            .and_then(|p| p.container.clone())
            .filter(|c| !c.is_empty());
        let container_cstr = container.as_deref().and_then(|c| CString::new(c).ok());

        unsafe {
            let input_format = container_cstr
                .as_ref()
                .map(|c| av_find_input_format(c.as_ptr()))
                .unwrap_or(std::ptr::null_mut());

            let io_box = Box::new(StreamIoContext {
                source,
                position: 0,
                file_size,
                seekable,
            });
            let io_ctx = Box::into_raw(io_box);

            let avio_buffer_size = 32_768i32;
            let avio_buffer = av_malloc(avio_buffer_size as usize) as *mut u8;
            if avio_buffer.is_null() {
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::Bug, Some(io_box.source)));
            }

            let seek_cb = if seekable {
                Some(stream_seek as _)
            } else {
                None
            };
            let avio_ctx = avio_alloc_context(
                avio_buffer,
                avio_buffer_size,
                0,
                io_ctx as *mut std::ffi::c_void,
                Some(stream_read_packet),
                None,
                seek_cb,
            );
            if avio_ctx.is_null() {
                av_free(avio_buffer as *mut std::ffi::c_void);
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::Bug, Some(io_box.source)));
            }
            (*avio_ctx).seekable = if seekable { 1 } else { 0 };

            let mut fmt: *mut AVFormatContext = avformat_alloc_context();
            if fmt.is_null() {
                free_avio(avio_ctx);
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::Bug, Some(io_box.source)));
            }
            (*fmt).pb = avio_ctx;
            // AVFMT_FLAG_CUSTOM_IO
            (*fmt).flags |= 0x0080;
            // Bound the probe when we know the container; otherwise fall back to
            // FFmpeg's 32 MiB blind default so unknown streams still open.
            (*fmt).probesize = match params {
                Some(p) if p.has_container() => p.probe_bytes(),
                _ => 32 * 1024 * 1024,
            };

            if avformat_open_input(
                &mut fmt,
                std::ptr::null(),
                input_format,
                std::ptr::null_mut(),
            ) < 0
            {
                free_avio(avio_ctx);
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::InvalidData, Some(io_box.source)));
            }

            if avformat_find_stream_info(fmt, std::ptr::null_mut()) < 0 {
                avformat_close_input(&mut fmt);
                free_avio(avio_ctx);
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::InvalidData, Some(io_box.source)));
            }

            // A demuxer can "open" foreign bytes yet expose no decodable audio
            // stream (e.g. a wrong container hint leaves codec parameters
            // empty). Treat that as an open failure so the zero-probe fallback
            // can retry with a blind probe.
            let has_audio = (0..(*fmt).nb_streams).any(|i| {
                let stream = *(*fmt).streams.add(i as usize);
                if stream.is_null() || (*stream).codecpar.is_null() {
                    return false;
                }
                let par = &*(*stream).codecpar;
                par.codec_type == ffmpeg_next::ffi::AVMediaType::AVMEDIA_TYPE_AUDIO
                    && par.codec_id != ffmpeg_next::ffi::AVCodecID::AV_CODEC_ID_NONE
                    && par.sample_rate > 0
            });
            if !has_audio {
                avformat_close_input(&mut fmt);
                free_avio(avio_ctx);
                let io_box = Box::from_raw(io_ctx);
                return Err((ffmpeg_next::Error::StreamNotFound, Some(io_box.source)));
            }

            // The container is open; the replay buffer (if any) is not needed.
            (*io_ctx).source.disable_rewind();

            Ok(Self {
                input: Some(Input::wrap(fmt)),
                avio_ctx,
                io_ctx,
            })
        }
    }

    pub fn take_input(&mut self) -> Result<Input, ffmpeg_next::Error> {
        self.input.take().ok_or(ffmpeg_next::Error::Bug)
    }
}

fn free_avio(avio_ctx: *mut AVIOContext) {
    if avio_ctx.is_null() {
        return;
    }
    unsafe {
        av_freep(&mut (*avio_ctx).buffer as *mut _ as *mut std::ffi::c_void);
        let mut context = avio_ctx;
        avio_context_free(&mut context);
    }
}

impl Drop for StreamFormatHandle {
    fn drop(&mut self) {
        self.input.take();
        unsafe {
            free_avio(self.avio_ctx);
            if !self.io_ctx.is_null() {
                drop(Box::from_raw(self.io_ctx));
            }
        }
    }
}

unsafe extern "C" fn stream_read_packet(
    opaque: *mut std::ffi::c_void,
    buf: *mut u8,
    buf_size: i32,
) -> i32 {
    if opaque.is_null() || buf.is_null() || buf_size <= 0 {
        return -libc::EINVAL;
    }
    let ctx = unsafe { &mut *(opaque as *mut StreamIoContext) };
    let slice = unsafe { std::slice::from_raw_parts_mut(buf, buf_size as usize) };
    match ctx.source.read(slice) {
        Ok(0) => ffmpeg_next::ffi::AVERROR_EOF,
        Ok(n) => {
            ctx.position = ctx.position.saturating_add(n as u64);
            n as i32
        }
        Err(error) if error.kind() == io::ErrorKind::Interrupted => {
            // Retry once from FFmpeg's perspective by reporting EINTR.
            -libc::EINTR
        }
        Err(error) => {
            let code = error.raw_os_error().unwrap_or(libc::EIO);
            -code
        }
    }
}

unsafe extern "C" fn stream_seek(
    opaque: *mut std::ffi::c_void,
    offset: i64,
    whence: i32,
) -> i64 {
    if opaque.is_null() {
        return -(libc::EINVAL as i64);
    }
    let ctx = unsafe { &mut *(opaque as *mut StreamIoContext) };
    if !ctx.seekable {
        return -(libc::ENOSYS as i64);
    }

    if whence == AVSEEK_SIZE {
        return if ctx.file_size >= 0 {
            ctx.file_size
        } else {
            -(libc::ENOSYS as i64)
        };
    }

    let safe_whence = whence & !AVSEEK_FORCE;
    let target = match safe_whence {
        libc::SEEK_SET => offset,
        libc::SEEK_CUR => {
            let Ok(pos) = i64::try_from(ctx.position) else {
                return -(libc::EOVERFLOW as i64);
            };
            match pos.checked_add(offset) {
                Some(v) => v,
                None => return -(libc::EOVERFLOW as i64),
            }
        }
        libc::SEEK_END => {
            if ctx.file_size < 0 {
                return -(libc::ENOSYS as i64);
            }
            match ctx.file_size.checked_add(offset) {
                Some(v) => v,
                None => return -(libc::EOVERFLOW as i64),
            }
        }
        _ => return -(libc::EINVAL as i64),
    };
    if target < 0 {
        return -(libc::EINVAL as i64);
    }
    let Ok(abs) = u64::try_from(target) else {
        return -(libc::EINVAL as i64);
    };
    match ctx.source.seek(SeekFrom::Start(abs)) {
        Ok(new_pos) => {
            ctx.position = new_pos;
            target
        }
        Err(_) => -(libc::EIO as i64),
    }
}
