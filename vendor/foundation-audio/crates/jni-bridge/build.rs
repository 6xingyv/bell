fn main() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        // Link zlib for the Android FFmpeg build.
        println!("cargo:rustc-link-lib=z");

        // Link Android log library for __android_log_print.
        println!("cargo:rustc-link-lib=log");
    }

    // oboe-sys 0.6.1 compiles Oboe as a static C++ archive, but its build
    // script does not propagate the C++ runtime libraries to the final Rust
    // cdylib. Android's linker permits those symbols to remain undefined at
    // build time, then dlopen fails at startup on __cxa_pure_virtual. Link the
    // NDK static runtime into libjni_bridge.so so the APK remains self-contained.
    if target_os == "android" {
        println!("cargo:rustc-link-lib=static=c++_static");
        println!("cargo:rustc-link-lib=static=c++abi");
    }

    // If using pre-built FFmpeg, specify the library path
    if let Ok(ffmpeg_dir) = std::env::var("FFMPEG_DIR") {
        println!("cargo:rustc-link-search=native={}/lib", ffmpeg_dir);
    }
}
