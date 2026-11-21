plugins {
    base
}

// FFmpeg is a separate, infrequent dependency build. Ordinary audio changes
// compile natively with Zig and consume the installed FFmpeg archives.
if (providers.gradleProperty("rebuildFfmpeg").isPresent) {
    apply(from = "ffmpeg-build.gradle.kts")
}
apply(from = "zig-build.gradle.kts")
