Exit code: 0
Wall time: 1.1 seconds
Output:
use engine_core::TrackMetadata;
use ffmpeg_next::format::context::Input;
use ffmpeg_next::decoder::Audio;
use ffmpeg_next::software::resampling::Context as ResamplerContext;
use ringbuf::traits::Producer;
use ringbuf::HeapProd;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

const ANDROID_LOG_ERROR: i32 = 6;
const ANDROID_LOG_WARN: i32 = 5;
const ANDROID_LOG_INFO: i32 = 4;
const ANDROID_LOG_DEBUG: i32 = 3;

unsafe extern "C" {
    fn __android_log_print(prio: i32, tag: *const std::ffi::c_char, fmt: *const std::ffi::c_char, ...) -> i32;
}

fn log_print(prio: i32, msg: &str) {
    unsafe {
        let tag = std::ffi::CString::new("FFmpegDecoder").unwrap();
        let fmt = std::ffi::CString::new("%s").unwrap();
        let c_msg = std::ffi::CString::new(msg).unwrap();
        __android_log_print(prio, tag.as_ptr(), fmt.as_ptr(), c_msg.as_ptr());
    }
}

macro_rules! log_debug {
    ($($arg:tt)*) => { log_print(ANDROID_LOG_DEBUG, &format!($($arg)*)) };
}
macro_rules! log_info {
    ($($arg:tt)*) => { log_print(ANDROID_LOG_INFO, &format!($($arg)*)) };
}
macro_rules! log_warn {
    ($($arg:tt)*) => { log_print(ANDROID_LOG_WARN, &format!($($arg)*)) };
}
macro_rules! log_error {
    ($($arg:tt)*) => { log_print(ANDROID_LOG_ERROR, &format!($($arg)*)) };
}

pub struct FFmpegDecoder {
    pub format_ctx: Option<Input>,
    pub decoder_ctx: Audio,
    pub resampler: Option<ResamplerContext>,
    pub target_sample_rate: u32,
    pub target_channels: u16,
    pub avio_ctx: *mut ffmpeg_next::ffi::AVIOContext,
    pub io_ctx: *mut FdIOContext,
}

unsafe impl Send for FFmpegDecoder {}
mod decoder;
mod fd_io;
mod metadata;

use fd_io::FdIOContext;

pub struct FFmpegDecoder {
    pub format_ctx: Option<Input>,
    pub decoder_ctx: Audio,
    pub resampler: Option<ResamplerContext>,
    pub target_sample_rate: u32,
    pub target_channels: u16,
    pub avio_ctx: *mut ffmpeg_next::ffi::AVIOContext,
    pub io_ctx: *mut FdIOContext,
}

unsafe impl Send for FFmpegDecoder {}
