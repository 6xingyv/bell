fn main() {
    // Link Android log library for __android_log_print
    println!("cargo:rustc-link-lib=log");
}
