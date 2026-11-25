//! fd-based AVIOContext adapter shared by decoder probing + streaming.
//!
//! Creates an FFmpeg AVIOContext that reads/seeks an already-open file
//! descriptor (Android ParcelFileDescriptor's detached fd). The handle
//! duplicates the caller's fd and owns that duplicate for its full lifetime.

use ffmpeg_next::ffi::{
    AVFormatContext, AVIOContext, AVSEEK_FORCE, AVSEEK_SIZE, av_free, av_freep, av_malloc,
    avformat_alloc_context, avformat_close_input, avformat_find_stream_info, avformat_open_input,
    avio_alloc_context, avio_context_free,
};
use ffmpeg_next::format::context::Input;

// Apple's `off_t` is already 64-bit, so the base `pread`/`lseek` are the 64-bit
// syscalls; Linux/Android/Windows expose them under the `*64` names. Alias per
// target so the fd path builds on the desktop host too (behavior is identical).
#[cfg(all(not(windows), not(target_vendor = "apple")))]
use libc::pread64 as os_pread;
#[cfg(all(not(windows), target_vendor = "apple"))]
use libc::pread as os_pread;
#[cfg(target_vendor = "apple")]
use libc::lseek as os_lseek;
#[cfg(not(target_vendor = "apple"))]
use libc::lseek64 as os_lseek;

#[repr(C)]
struct FdIOContext {
    fd: i32,
    file_size: i64,
    start_pos: i64,
    position: i64,
}

fn errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

fn errno_or_eio() -> i32 {
    match errno() {
        0 => libc::EIO,
        error => error,
    }
}

#[cfg(not(windows))]
unsafe fn read_at(fd: i32, buf: *mut std::ffi::c_void, len: usize, offset: i64) -> isize {
    // SAFETY: callers pass the valid buffer and descriptor received by the
    // FFmpeg AVIO callback.
    unsafe { os_pread(fd, buf, len, offset) }
}

#[cfg(windows)]
unsafe fn read_at(fd: i32, buf: *mut std::ffi::c_void, len: usize, offset: i64) -> isize {
    // Windows' CRT has no pread equivalent. The fd-backed API is only used by
    // Android today, but keep the native implementation buildable on Windows
    // by using the CRT's 64-bit seek/read pair.
    if unsafe { os_lseek(fd, offset, libc::SEEK_SET) } < 0 {
        return -1;
    }
    unsafe { libc::read(fd, buf, len as libc::c_uint) as isize }
}

/// Duplicates a borrowed POSIX file descriptor.
pub fn duplicate_fd(fd: i32) -> Result<i32, std::io::Error> {
    let duplicate = unsafe { libc::dup(fd) };
    if duplicate < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(duplicate)
    }
}

/// Closes an owned POSIX file descriptor.
pub fn close_fd(fd: i32) {
    if fd >= 0 {
        unsafe {
            libc::close(fd);
        }
    }
}

unsafe extern "C" fn fd_read_packet(
    opaque: *mut std::ffi::c_void,
    buf: *mut u8,
    buf_size: i32,
) -> i32 {
    if opaque.is_null() || buf.is_null() || buf_size <= 0 {
        return -libc::EINVAL;
    }
    let ctx = unsafe { &mut *(opaque as *mut FdIOContext) };
    loop {
        let Some(absolute_pos) = ctx.start_pos.checked_add(ctx.position) else {
            return -libc::EOVERFLOW;
        };
        // dup(2) descriptors share an open-file description and therefore a
        // kernel file offset. Queue replay, seek and crossfade can have two
        // decoders open on the same item, so using read/lseek here lets one
        // decoder move the other's cursor. pread keeps the queue fd immutable;
        // each AVIOContext advances only its own logical position.
        let n = unsafe {
            read_at(
                ctx.fd,
                buf as *mut libc::c_void,
                buf_size as usize,
                absolute_pos,
            )
        };
        if n < 0 {
            let err = errno_or_eio();
            if err == libc::EINTR {
                continue;
            }
            return -err;
        }
        if n == 0 {
            return ffmpeg_next::ffi::AVERROR_EOF;
        }
        ctx.position = match ctx.position.checked_add(n as i64) {
            Some(position) => position,
            None => return -libc::EOVERFLOW,
        };
        return n as i32;
    }
}

unsafe extern "C" fn fd_seek(opaque: *mut std::ffi::c_void, offset: i64, whence: i32) -> i64 {
    if opaque.is_null() {
        return -(libc::EINVAL as i64);
    }
    let ctx = unsafe { &mut *(opaque as *mut FdIOContext) };

    if whence == AVSEEK_SIZE {
        return ctx
            .file_size
            .checked_sub(ctx.start_pos)
            .filter(|&size| size >= 0)
            .unwrap_or(-(libc::ENOSYS as i64));
    }
    if ctx.file_size < 0 {
        return -(libc::ENOSYS as i64);
    }

    let safe_whence = whence & !AVSEEK_FORCE;
    let target = match safe_whence {
        libc::SEEK_SET => offset,
        libc::SEEK_CUR => {
            let Some(target) = ctx.position.checked_add(offset) else {
                return -(libc::EOVERFLOW as i64);
            };
            target
        }
        libc::SEEK_END => {
            let Some(logical_size) = ctx.file_size.checked_sub(ctx.start_pos) else {
                return -(libc::EINVAL as i64);
            };
            let Some(target) = logical_size.checked_add(offset) else {
                return -(libc::EOVERFLOW as i64);
            };
            target
        }
        _ => return -(libc::EINVAL as i64),
    };

    if target < 0 {
        return -(libc::EINVAL as i64);
    }
    ctx.position = target;
    target
}

/// Owns the ffmpeg-side allocations for a fd-backed Input.
/// Drop order matters: close Input first, then free avio_ctx, then free io_ctx.
pub struct FdFormatHandle {
    input: Option<Input>,
    avio_ctx: *mut AVIOContext,
    io_ctx: *mut FdIOContext,
}

impl FdFormatHandle {
    /// Open an FFmpeg Input on a borrowed `fd`.
    pub fn open(fd: i32) -> Result<Self, ffmpeg_next::Error> {
        ffmpeg_next::init()?;
        let fd = duplicate_fd(fd).map_err(|_| ffmpeg_next::Error::Bug)?;
        unsafe {
            let cur = os_lseek(fd, 0, libc::SEEK_CUR).max(0);
            let mut stat: libc::stat = std::mem::zeroed();
            let size = if libc::fstat(fd, &mut stat) == 0 {
                stat.st_size
            } else {
                -1
            };

            let io_ctx = libc::malloc(std::mem::size_of::<FdIOContext>()) as *mut FdIOContext;
            if io_ctx.is_null() {
                close_fd(fd);
                return Err(ffmpeg_next::Error::Bug);
            }
            (*io_ctx).fd = fd;
            (*io_ctx).file_size = size;
            (*io_ctx).start_pos = cur;
            (*io_ctx).position = 0;

            let avio_buffer_size = 32768i32;
            let avio_buffer = av_malloc(avio_buffer_size as usize) as *mut u8;
            if avio_buffer.is_null() {
                libc::free(io_ctx as *mut libc::c_void);
                close_fd(fd);
                return Err(ffmpeg_next::Error::Bug);
            }

            let avio_ctx = avio_alloc_context(
                avio_buffer,
                avio_buffer_size,
                0,
                io_ctx as *mut std::ffi::c_void,
                Some(fd_read_packet),
                None,
                Some(fd_seek),
            );
            if avio_ctx.is_null() {
                av_free(avio_buffer as *mut std::ffi::c_void);
                libc::free(io_ctx as *mut libc::c_void);
                close_fd(fd);
                return Err(ffmpeg_next::Error::Bug);
            }

            let mut fmt: *mut AVFormatContext = avformat_alloc_context();
            if fmt.is_null() {
                free_avio(avio_ctx);
                libc::free(io_ctx as *mut libc::c_void);
                close_fd(fd);
                return Err(ffmpeg_next::Error::Bug);
            }
            (*fmt).pb = avio_ctx;
            (*fmt).flags |= 0x0080;
            (*fmt).probesize = 32 * 1024 * 1024;

            if avformat_open_input(
                &mut fmt,
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            ) < 0
            {
                free_avio(avio_ctx);
                libc::free(io_ctx as *mut libc::c_void);
                close_fd(fd);
                return Err(ffmpeg_next::Error::InvalidData);
            }

            if avformat_find_stream_info(fmt, std::ptr::null_mut()) < 0 {
                avformat_close_input(&mut fmt);
                free_avio(avio_ctx);
                libc::free(io_ctx as *mut libc::c_void);
                close_fd(fd);
                return Err(ffmpeg_next::Error::InvalidData);
            }

            Ok(Self {
                input: Some(Input::wrap(fmt)),
                avio_ctx,
                io_ctx,
            })
        }
    }

    pub fn input(&self) -> Option<&Input> {
        self.input.as_ref()
    }
    pub fn take_input(&mut self) -> Result<Input, ffmpeg_next::Error> {
        self.input.take().ok_or(ffmpeg_next::Error::Bug)
    }
}

fn free_avio(avio_ctx: *mut AVIOContext) {
    if avio_ctx.is_null() {
        return;
    }
    // SAFETY: avio_ctx came from avio_alloc_context and is still exclusively
    // owned here. FFmpeg accepts and nulls both allocation pointers.
    unsafe {
        av_freep(&mut (*avio_ctx).buffer as *mut _ as *mut std::ffi::c_void);
        let mut context = avio_ctx;
        avio_context_free(&mut context);
    }
}

impl Drop for FdFormatHandle {
    fn drop(&mut self) {
        self.input.take();
        unsafe {
            free_avio(self.avio_ctx);
            if !self.io_ctx.is_null() {
                close_fd((*self.io_ctx).fd);
                libc::free(self.io_ctx as *mut libc::c_void);
            }
        }
    }
}
