#[repr(C)]
pub(super) struct FdIOContext {
    fd: i32,
    file_size: i64,
    start_pos: i64, // FD 的逻辑起点（Scoped Storage 可能非 0）
}

fn get_errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

unsafe extern "C" fn fd_read_packet(opaque: *mut std::ffi::c_void, buf: *mut u8, buf_size: i32) -> i32 {
    let ctx = unsafe { &*(opaque as *const FdIOContext) };
    loop {
        let bytes_read = unsafe { libc::read(ctx.fd, buf as *mut libc::c_void, buf_size as usize) };
        if bytes_read < 0 {
            let err = get_errno();
            if err == libc::EINTR {
                continue;
            }
            return -err;
        }
        if bytes_read == 0 {
            return ffmpeg_next::ffi::AVERROR_EOF;
        }
        return bytes_read as i32;
    }
}

unsafe extern "C" fn fd_seek(opaque: *mut std::ffi::c_void, offset: i64, whence: i32) -> i64 {
    let ctx = &*(opaque as *const FdIOContext);

    if whence == ffmpeg_next::ffi::AVSEEK_SIZE {
        return if ctx.file_size >= 0 { ctx.file_size - ctx.start_pos } else { -1 };
    }

    if ctx.file_size < 0 { return -1; }

    let safe_whence = whence & !ffmpeg_next::ffi::AVSEEK_FORCE;

    let target_pos = match safe_whence {
        libc::SEEK_SET => offset,
        libc::SEEK_CUR => {
            let cur_phys = libc::lseek64(ctx.fd, 0, libc::SEEK_CUR);
            if cur_phys < 0 { return -1; }
            cur_phys + offset
        },
        libc::SEEK_END => ctx.file_size + offset,
        _ => return -1,
    };

    let final_pos = ctx.start_pos + target_pos;

    let result = libc::lseek64(ctx.fd, final_pos, libc::SEEK_SET);
    if result < 0 {
        return -get_errno() as i64;
    }

    result - ctx.start_pos
}

/// 从 fd 打开 format context，返回 (Input, avio_ctx, io_ctx) 以便外部正确释放
pub(super) fn open_format_ctx_from_fd(fd: i32) -> Result<(Input, *mut ffmpeg_next::ffi::AVIOContext, *mut FdIOContext), ffmpeg_next::Error> {
    unsafe {
        ffmpeg_next::init()?;

        let current_pos = libc::lseek64(fd, 0, libc::SEEK_CUR).max(0);
        let file_size = libc::lseek64(fd, 0, libc::SEEK_END);

        if file_size >= 0 {
            libc::lseek64(fd, current_pos, libc::SEEK_SET);
        }

        let io_ctx = libc::malloc(std::mem::size_of::<FdIOContext>()) as *mut FdIOContext;
        if io_ctx.is_null() {
            return Err(ffmpeg_next::Error::Bug);
        }
        (*io_ctx).fd = fd;
        (*io_ctx).file_size = file_size;
        (*io_ctx).start_pos = current_pos;

        let avio_buffer_size = 32768;
        let avio_buffer = ffmpeg_next::ffi::av_malloc(avio_buffer_size as usize) as *mut u8;
        if avio_buffer.is_null() {
            libc::free(io_ctx as *mut libc::c_void);
            return Err(ffmpeg_next::Error::Bug);
        }

        let avio_ctx = ffmpeg_next::ffi::avio_alloc_context(
            avio_buffer, avio_buffer_size, 0, io_ctx as *mut std::ffi::c_void,
            Some(fd_read_packet), None, Some(fd_seek)
        );

        if avio_ctx.is_null() {
            ffmpeg_next::ffi::av_free(avio_buffer as *mut std::ffi::c_void);
            libc::free(io_ctx as *mut libc::c_void);
            return Err(ffmpeg_next::Error::Bug);
        }

        let mut av_fmt_ctx = ffmpeg_next::ffi::avformat_alloc_context();
        (*av_fmt_ctx).pb = avio_ctx;
        (*av_fmt_ctx).flags |= 0x0080; // AVFMT_FLAG_CUSTOM_IO
        (*av_fmt_ctx).probesize = 32 * 1024 * 1024;

        if ffmpeg_next::ffi::avformat_open_input(&mut av_fmt_ctx, std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut()) < 0 {
            ffmpeg_next::ffi::av_freep(&mut (*avio_ctx).buffer as *mut _ as *mut std::ffi::c_void);
            let mut ctx = avio_ctx;
            ffmpeg_next::ffi::avio_context_free(&mut ctx);
            libc::free(io_ctx as *mut libc::c_void);
            return Err(ffmpeg_next::Error::InvalidData);
        }

        if ffmpeg_next::ffi::avformat_find_stream_info(av_fmt_ctx, std::ptr::null_mut()) < 0 {
            ffmpeg_next::ffi::avformat_close_input(&mut av_fmt_ctx);
            ffmpeg_next::ffi::av_freep(&mut (*avio_ctx).buffer as *mut _ as *mut std::ffi::c_void);
            let mut ctx = avio_ctx;
            ffmpeg_next::ffi::avio_context_free(&mut ctx);
            libc::free(io_ctx as *mut libc::c_void);
            return Err(ffmpeg_next::Error::InvalidData);
        }

        Ok((Input::wrap(av_fmt_ctx), avio_ctx, io_ctx))
    }
}

/// 安全释放 fd IO 相关资源：先 drop format_ctx，再释放 avio_ctx，最后释放 io_ctx
pub(super) unsafe fn cleanup_fd_io(avio_ctx: *mut ffmpeg_next::ffi::AVIOContext, io_ctx: *mut FdIOContext) {
    if !avio_ctx.is_null() {
        ffmpeg_next::ffi::av_freep(&mut (*avio_ctx).buffer as *mut _ as *mut std::ffi::c_void);
        let mut ctx = avio_ctx;
        ffmpeg_next::ffi::avio_context_free(&mut ctx);
    }
    if !io_ctx.is_null() {
        libc::free(io_ctx as *mut libc::c_void);
    }
}
