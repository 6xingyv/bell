const std = @import("std");

pub fn build(b: *std.Build) void {
    if (!std.mem.eql(u8, @import("builtin").zig_version_string, std.mem.trim(u8, @embedFile(".zig-version"), "\r\n ")))
        @panic("Use the compiler pinned in .zig-version");
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const android = target.result.abi.isAndroid();
    const tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("zig/src/tests.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    b.step("test", "Run portable audio behavior tests").dependOn(&b.addRunArtifact(tests).step);
    const ffmpeg = b.option([]const u8, "ffmpeg", "FFmpeg prefix containing include and lib") orelse b.graph.environ_map.get("FFMPEG_DIR") orelse (if (b.graph.host.result.os.tag == .windows) "ffmpeg-windows-shared" else "/usr");
    const root = b.addModule("foundation_audio", .{
        .root_source_file = b.path("zig/src/root.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = b.option(bool, "strip", "Strip debug information") orelse android,
    });
    const native = b.addTranslateC(.{ .root_source_file = b.path("zig/include/native.h"), .target = target, .optimize = optimize, .link_libc = !android });
    native.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ ffmpeg, "include" }) });
    native.addIncludePath(b.path("zig/include"));
    root.addImport("native", native.createModule());
    root.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ ffmpeg, "include" }) });
    root.addLibraryPath(.{ .cwd_relative = b.pathJoin(&.{ ffmpeg, "lib" }) });
    root.addIncludePath(b.path("zig/vendor"));
    root.addIncludePath(b.path("zig/include"));
    for ([_][]const u8{ "avformat", "avcodec", "swresample", "avutil" }) |name| {
        if (android) {
            // Explicit shared object: never silently fall back to a .a archive.
            root.addObjectFile(.{ .cwd_relative = b.pathJoin(&.{ ffmpeg, "lib", b.fmt("lib{s}.so", .{name}) }) });
        } else if (target.result.os.tag == .windows) {
            // Import archive contains DLL thunks, not FFmpeg implementation.
            root.addObjectFile(.{ .cwd_relative = b.pathJoin(&.{ ffmpeg, "lib", b.fmt("lib{s}.dll.a", .{name}) }) });
        } else {
            root.linkSystemLibrary(name, .{ .preferred_link_mode = .dynamic, .search_strategy = .no_fallback });
        }
    }
    root.addCSourceFile(.{ .file = b.path("zig/device.c"), .flags = &.{"-std=c11"} });
    if (target.result.os.tag == .windows) {
        for ([_][]const u8{ "ole32", "winmm", "uuid" }) |name| root.linkSystemLibrary(name, .{});
    } else if (!android) {
        root.linkSystemLibrary("m", .{});
        root.linkSystemLibrary("dl", .{});
        if (!android) root.linkSystemLibrary("pthread", .{});
    }
    const lib = b.addLibrary(.{ .name = if (android) "jni_bridge" else "foundation_audio", .linkage = .dynamic, .root_module = root });
    if (android) {
        lib.link_z_max_page_size = 16384;
        lib.link_z_common_page_size = 16384;
        lib.version_script = b.path("zig/exports.map");
        // NDK nullability is a Clang diagnostic annotation, not part of ABI.
        // Strip it for Zig's C translator, which cannot translate array forms.
        root.addCMacro("_Nonnull", "");
        root.addCMacro("_Nullable", "");
        root.addCMacro("_Null_unspecified", "");
        native.defineCMacro("_Nonnull", "");
        native.defineCMacro("_Nullable", "");
        native.defineCMacro("_Null_unspecified", "");
        const ndk = b.option([]const u8, "ndk", "Android NDK directory") orelse @panic("Android builds require -Dndk=<path>");
        const triple = switch (target.result.cpu.arch) {
            .aarch64 => "aarch64-linux-android",
            .arm => "arm-linux-androideabi",
            .x86_64 => "x86_64-linux-android",
            else => @panic("Unsupported Android ABI"),
        };
        const host = switch (b.graph.host.result.os.tag) {
            .windows => "windows-x86_64",
            .macos => "darwin-x86_64",
            else => "linux-x86_64",
        };
        const sysroot = b.pathJoin(&.{ ndk, "toolchains/llvm/prebuilt", host, "sysroot" });
        const include = b.pathJoin(&.{ sysroot, "usr/include" });
        const system_include = b.pathJoin(&.{ include, triple });
        const library = b.pathJoin(&.{ sysroot, "usr/lib", triple, "26" });
        native.addSystemIncludePath(.{ .cwd_relative = include });
        native.addSystemIncludePath(.{ .cwd_relative = system_include });
        const config = b.addWriteFiles().add("android-libc.conf", b.fmt("include_dir={s}\nsys_include_dir={s}\ncrt_dir={s}\nmsvc_lib_dir=\nkernel32_lib_dir=\ncc_dir={s}\ndarwin_sdk_dir=\n", .{ include, system_include, library, library }));
        lib.setLibCFile(config);
        root.addLibraryPath(.{ .cwd_relative = library });
        // Pass NDK import libraries as files; Zig 0.17's Windows-host library
        // resolver otherwise emits invalid ELF -l:C:\... names.
        for ([_][]const u8{ "m", "dl", "log", "android", "z" }) |name|
            root.addObjectFile(.{ .cwd_relative = b.pathJoin(&.{ library, b.fmt("lib{s}.so", .{name}) }) });
    }
    b.installArtifact(lib);
    lib.installHeader(b.path("zig/include/foundation_audio.h"), "foundation_audio.h");
    const integration = b.addTest(.{ .root_module = root });
    b.step("integration", "Decode and render integration tests (requires FFmpeg)").dependOn(&b.addRunArtifact(integration).step);
}
