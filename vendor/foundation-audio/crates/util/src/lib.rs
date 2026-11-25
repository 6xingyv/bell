//! Cross-crate utilities: logging macros + fd-based AVIO adapter.

pub mod diagnostics {
    use std::collections::VecDeque;
    use std::sync::{Mutex, OnceLock};
    use std::time::{SystemTime, UNIX_EPOCH};

    const MAX_LINES: usize = 1_024;
    const MAX_MESSAGE_CHARS: usize = 2_048;

    static LINES: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();

    pub fn record(level: &str, tag: &str, message: &str) {
        let timestamp_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_millis())
            .unwrap_or_default();
        let message = if message.chars().count() > MAX_MESSAGE_CHARS {
            let mut truncated = message.chars().take(MAX_MESSAGE_CHARS).collect::<String>();
            truncated.push_str("…[truncated]");
            truncated
        } else {
            message.to_owned()
        };
        let line = format!("{timestamp_ms} [{level}] [{tag}] {message}");
        let mut lines = LINES
            .get_or_init(|| Mutex::new(VecDeque::with_capacity(MAX_LINES)))
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if lines.len() == MAX_LINES {
            lines.pop_front();
        }
        lines.push_back(line);
    }

    pub fn snapshot() -> String {
        LINES
            .get_or_init(|| Mutex::new(VecDeque::with_capacity(MAX_LINES)))
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .iter()
            .cloned()
            .collect::<Vec<_>>()
            .join("\n")
    }
}

#[cfg(target_os = "android")]
#[macro_export]
macro_rules! log_d {
    ($tag:expr, $($arg:tt)*) => {{
        $crate::__android::write(3, $tag, &format!($($arg)*));
    }};
}

#[cfg(target_os = "android")]
#[macro_export]
macro_rules! log_i {
    ($tag:expr, $($arg:tt)*) => {{
        $crate::__android::write(4, $tag, &format!($($arg)*));
    }};
}

#[cfg(target_os = "android")]
#[macro_export]
macro_rules! log_w {
    ($tag:expr, $($arg:tt)*) => {{
        $crate::__android::write(5, $tag, &format!($($arg)*));
    }};
}

#[cfg(target_os = "android")]
#[macro_export]
macro_rules! log_e {
    ($tag:expr, $($arg:tt)*) => {{
        $crate::__android::write(6, $tag, &format!($($arg)*));
    }};
}

#[cfg(not(target_os = "android"))]
#[macro_export]
macro_rules! log_d { ($tag:expr, $($arg:tt)*) => { eprintln!("[{}][D] {}", $tag, format!($($arg)*)); }; }
#[cfg(not(target_os = "android"))]
#[macro_export]
macro_rules! log_i { ($tag:expr, $($arg:tt)*) => { eprintln!("[{}][I] {}", $tag, format!($($arg)*)); }; }
#[cfg(not(target_os = "android"))]
#[macro_export]
macro_rules! log_w { ($tag:expr, $($arg:tt)*) => { eprintln!("[{}][W] {}", $tag, format!($($arg)*)); }; }
#[cfg(not(target_os = "android"))]
#[macro_export]
macro_rules! log_e { ($tag:expr, $($arg:tt)*) => { eprintln!("[{}][E] {}", $tag, format!($($arg)*)); }; }

#[cfg(target_os = "android")]
pub mod __android {
    use std::os::raw::{c_char, c_int};
    unsafe extern "C" {
        fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    }

    pub fn write(priority: c_int, tag: &str, message: &str) {
        let level = match priority {
            3 => "D",
            4 => "I",
            5 => "W",
            6 => "E",
            _ => "?",
        };
        crate::diagnostics::record(level, tag, message);
        let message = std::ffi::CString::new(message).unwrap_or_default();
        let tag = std::ffi::CString::new(tag).unwrap_or_default();
        // SAFETY: both pointers are valid NUL-terminated strings for the
        // duration of the call. The message is passed as data, not a format.
        unsafe {
            __android_log_print(priority, tag.as_ptr(), c"%s".as_ptr(), message.as_ptr());
        }
    }
}

pub mod fd_io;
pub mod stream_io;
