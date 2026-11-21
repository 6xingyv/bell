pub const queue = @import("queue.zig");
pub const dsp = @import("dsp.zig");
pub const ring = @import("ring.zig");
pub const Decoder = @import("decoder.zig").Decoder;
pub const Engine = @import("engine.zig").Engine;
pub const Request = @import("engine.zig").Request;
pub const Cast = @import("cast.zig").Cast;
pub const FabricRenderer = @import("fabric.zig").Renderer;
pub const fabric_source = @import("source.zig");
pub const protocol = @import("protocol.zig");
pub const assets = @import("assets.zig");
pub const transport = @import("transport.zig");
fn testDescriptor(file: *@import("decoder.zig").c.FILE) c_int {
    const fd = struct {
        extern "c" fn _fileno(*@import("decoder.zig").c.FILE) c_int;
        extern "c" fn fileno(*@import("decoder.zig").c.FILE) c_int;
    };
    return if (@import("builtin").os.tag == .windows) fd._fileno(file) else fd.fileno(file);
}
comptime {
    if (@import("builtin").target.abi.isAndroid()) _ = @import("jni.zig");
}
export fn fa_abi_version() u32 {
    return 1;
}
export fn fa_engine_create(headless: bool) ?*Engine {
    return Engine.create(@import("std").heap.c_allocator, headless) catch null;
}
export fn fa_engine_destroy(engine: ?*Engine) void {
    if (engine) |e| e.destroy();
}
export fn fa_engine_play(engine: *Engine, path: [*:0]const u8) bool {
    var request: Request = .{ .kind = .play_path, .path = @import("std").mem.span(path) };
    return engine.submit(&request);
}
export fn fa_engine_pause(engine: *Engine, paused: bool) void {
    engine.setPaused(paused);
}
export fn fa_engine_stop(engine: *Engine) bool {
    var request: Request = .{ .kind = .stop };
    return engine.submit(&request);
}
export fn fa_engine_seek(engine: *Engine, milliseconds: i64) bool {
    var request: Request = .{ .kind = .seek, .a = milliseconds };
    return engine.submit(&request);
}
export fn fa_engine_render(engine: *Engine, samples: [*]f32, frames: usize) void {
    engine.render(samples[0 .. frames * 2]);
}
export fn fa_engine_status(engine: *Engine, output: *@import("engine.zig").Status) void {
    output.* = engine.status();
}
/// Additive C ABI: linear output power, not microphone input or decoder lookahead.
export fn fa_engine_spectrum(engine: *Engine, output: *@import("spectrum.zig").Power) bool {
    output.* = engine.presentedSpectrum();
    return output.sequence != 0;
}
export fn fa_engine_play_fd(engine: *Engine, fd: c_int) bool {
    var r: Request = .{ .kind = .play_fd, .a = fd };
    return engine.submit(&r);
}
export fn fa_engine_add_path(engine: *Engine, path: [*:0]const u8) bool {
    var r: Request = .{ .kind = .add_path, .path = @import("std").mem.span(path) };
    return engine.submit(&r);
}
export fn fa_engine_add_fd(engine: *Engine, fd: c_int) bool {
    var r: Request = .{ .kind = .add_fd, .a = fd };
    return engine.submit(&r);
}
export fn fa_engine_clear_queue(engine: *Engine) bool {
    var r: Request = .{ .kind = .clear };
    return engine.submit(&r);
}
export fn fa_engine_select(engine: *Engine, index: i32) bool {
    var r: Request = .{ .kind = .select, .a = index };
    return engine.submit(&r);
}
export fn fa_engine_queue_index(engine: *Engine) i32 {
    return engine.index.load(.acquire);
}
export fn fa_engine_move(engine: *Engine, from: i32, to: i32) bool {
    var r: Request = .{ .kind = .move, .a = from, .b = to };
    return engine.submit(&r);
}
export fn fa_engine_next(engine: *Engine, previous: bool) bool {
    var r: Request = .{ .kind = if (previous) .previous else .next };
    return engine.submit(&r);
}
export fn fa_engine_mode(engine: *Engine, mode: u32) bool {
    var r: Request = .{ .kind = .mode, .a = mode };
    return engine.submit(&r);
}
export fn fa_engine_crossfade(engine: *Engine, ms: u32, soundstage: bool) bool {
    var duration: Request = .{ .kind = .crossfade, .a = ms };
    if (!engine.submit(&duration)) return false;
    var spatial: Request = .{ .kind = .soundstage, .a = @intFromBool(soundstage) };
    return engine.submit(&spatial);
}
export fn fa_engine_replay_gain(engine: *Engine, enabled: bool) bool {
    var r: Request = .{ .kind = .gain_enabled, .a = @intFromBool(enabled) };
    return engine.submit(&r);
}
export fn fa_engine_queue_gain(engine: *Engine, index: i32, db: f32) bool {
    var r: Request = .{ .kind = .gain, .a = index, .value = db };
    return engine.submit(&r);
}
export fn fa_engine_silence(engine: *Engine, enabled: bool, db: f32, min_ms: u32) bool {
    var r: Request = .{ .kind = .silence, .a = @intFromBool(enabled), .b = min_ms, .value = db };
    return engine.submit(&r);
}
export fn fa_engine_play_stream(engine: *Engine, fd: c_int, paused: bool) bool {
    var r: Request = .{ .kind = .play_stream, .a = fd, .b = @intFromBool(paused) };
    return engine.submit(&r);
}
export fn fa_engine_rms(engine: *Engine) f32 {
    return @bitCast(engine.rms.load(.acquire));
}
export fn fa_engine_error(engine: *Engine) [*:0]const u8 {
    const code = engine.last_error.load(.acquire);
    return if (code == 0) "" else @errorName(@errorFromInt(@as(u16, @intCast(code))));
}
test "FFmpeg resamples mono WAV to stereo and seeks" {
    const std = @import("std");
    var decoder = try Decoder.openPath(std.testing.allocator, "zig/fixtures/tone.wav", 48000, 2);
    defer decoder.deinit();
    var samples: usize = 0;
    var energy: f64 = 0;
    while (true) {
        const pcm = try decoder.read();
        if (pcm.len == 0) break;
        samples += pcm.len;
        for (pcm) |value| {
            try std.testing.expect(std.math.isFinite(value));
            energy += value * value;
        }
    }
    try std.testing.expectEqual(@as(usize, 96000), samples);
    try std.testing.expect(energy > 100);
    try decoder.seek(500);
    var remaining: usize = 0;
    while (true) {
        const pcm = try decoder.read();
        if (pcm.len == 0) break;
        remaining += pcm.len;
    }
    try std.testing.expect(remaining >= 47000 and remaining <= 49000);
}

test "headless playback, pause clock, seek and teardown" {
    const std = @import("std");
    const c = @import("decoder.zig").c;
    const engine = try Engine.create(std.testing.allocator, true);
    defer engine.destroy();
    try std.testing.expect(fa_engine_play(engine, "zig/fixtures/tone.wav"));
    var buffer: [960]f32 = undefined;
    var energy: f64 = 0;
    for (0..30) |_| {
        c.fa_sleep_ms(2);
        engine.render(&buffer);
        for (buffer) |value| energy += value * value;
    }
    try std.testing.expect(energy > 10);
    try std.testing.expect(engine.status().position_ms > 0);
    engine.setPaused(true);
    for (0..20) |_| engine.render(&buffer);
    const paused_position = engine.status().position_ms;
    engine.render(&buffer);
    try std.testing.expectEqual(paused_position, engine.status().position_ms);
    for (buffer) |value| try std.testing.expectEqual(@as(f32, 0), value);
    try std.testing.expect(fa_engine_seek(engine, 700));
    engine.setPaused(false);
    for (0..20) |_| {
        c.fa_sleep_ms(2);
        engine.render(&buffer);
    }
    try std.testing.expect(engine.status().position_ms >= 700);
}

test "cast remux produces decodable Matroska at requested position" {
    const std = @import("std");
    const c = @import("decoder.zig").c;
    const input = c.fopen("zig/fixtures/tone.wav", "rb") orelse return error.OpenFailed;
    defer _ = c.fclose(input);
    const output = c.tmpfile() orelse return error.OpenFailed;
    defer _ = c.fclose(output);
    const fd = struct {
        extern "c" fn _fileno(*c.FILE) c_int;
        extern "c" fn fileno(*c.FILE) c_int;
        fn get(file: *c.FILE) c_int {
            return if (@import("builtin").os.tag == .windows) _fileno(file) else fileno(file);
        }
    };
    var cast = try Cast.open(std.testing.allocator, fd.get(input), fd.get(output), 500);
    defer cast.deinit();
    try std.testing.expect(cast.seek(250));
    try cast.pump();
    c.rewind(output);
    var decoded = try Decoder.openFd(std.testing.allocator, fd.get(output), 48000, 2);
    defer decoded.deinit();
    var count: usize = 0;
    while (true) {
        const pcm = try decoded.read();
        if (pcm.len == 0) break;
        count += pcm.len;
    }
    try std.testing.expect(count >= 71000 and count <= 75000);
}

test "Fabric preserves presentation and rejects stale barriers and invalid control" {
    const std = @import("std");
    const a = std.testing.allocator;
    const e = try Engine.create(a, true);
    defer e.destroy();
    var renderer = try FabricRenderer.init(a, e, "receiver");
    defer renderer.deinit();
    const announce = try renderer.control(
        \\{"type":"track_announce","generation":7,"meta":{"title":"测试 🎵","artist":"artist","duration_ms":1000},"media":{"start_position_ms":0}}
    );
    defer a.free(announce);
    try std.testing.expect(std.mem.indexOf(u8, announce, "\"ok\":true") != null);
    const meta = try renderer.presentation();
    defer a.free(meta);
    try std.testing.expect(std.mem.indexOf(u8, meta, "测试 🎵") != null);
    const stale = try renderer.control(
        \\{"type":"prepare_synced","generation":6,"activate_at_group_ns":1}
    );
    defer a.free(stale);
    try std.testing.expect(std.mem.indexOf(u8, stale, "stale generation") != null);
    const bad = try renderer.control("not json");
    defer a.free(bad);
    try std.testing.expect(std.mem.indexOf(u8, bad, "\"ok\":false") != null);
}

test "framed progressive stream decodes, rebases and preserves caller descriptor" {
    const std = @import("std");
    const c = @import("decoder.zig").c;
    const file = c.tmpfile() orelse return error.OpenFailed;
    defer _ = c.fclose(file);
    const source = c.fopen("zig/fixtures/tone.wav", "rb") orelse return error.OpenFailed;
    defer _ = c.fclose(source);
    const header: transport.MediaHeader = .{ .generation = 7, .flags = 1, .byte_length = 0, .start_position_ms = 1234 };
    var epoch: std.atomic.Value(u32) = .init(0);
    const written = try fabric_source.pushMedia(testDescriptor(source), &.{testDescriptor(file)}, header, &epoch, 0);
    try std.testing.expect(written > 88000);
    c.rewind(file);
    const fd = struct {
        extern "c" fn _fileno(*c.FILE) c_int;
        extern "c" fn fileno(*c.FILE) c_int;
        fn get(f: *c.FILE) c_int {
            return if (@import("builtin").os.tag == .windows) _fileno(f) else fileno(f);
        }
    }.get(file);
    const e = try Engine.create(std.testing.allocator, true);
    defer e.destroy();
    // A stale source-container hint is possible after Matroska remux. Opening
    // must rewind its bounded probe cache and retry automatic detection.
    var request: Request = .{ .kind = .play_stream, .a = fd, .b = 1, .path = "flac", .expected_generation = 7, .duration_ms = 10000 };
    try std.testing.expect(e.submit(&request));
    try std.testing.expectEqual(@as(u64, 1234), e.status().position_ms);
    var metadata: Request = .{ .kind = .metadata };
    try std.testing.expect(e.submit(&metadata));
    std.testing.allocator.free(metadata.bytes.?);
    var seek: Request = .{ .kind = .rebase, .a = 5000 };
    try std.testing.expect(e.submit(&seek));
    e.setPaused(false);
    var pcm: [960]f32 = undefined;
    for (0..80) |_| {
        c.fa_sleep_ms(1);
        e.render(&pcm);
    }
    try std.testing.expect(e.status().position_ms >= 5000);
    var stop: Request = .{ .kind = .stop };
    try std.testing.expect(e.submit(&stop));
    try std.testing.expectEqual(@as(c_int, 0), c.fseek(file, 0, c.SEEK_SET));
}

test "blocked progressive open is cancelled by stop" {
    const std = @import("std");
    const c = @import("decoder.zig").c;
    const pipes = struct {
        extern "c" fn _pipe(*[2]c_int, c_uint, c_int) c_int;
        extern "c" fn pipe(*[2]c_int) c_int;
    };
    var fds: [2]c_int = undefined;
    const result = if (@import("builtin").os.tag == .windows) pipes._pipe(&fds, 4096, 0x8000) else pipes.pipe(&fds);
    if (result != 0) return error.PipeFailed;
    defer @import("decoder.zig").close(fds[0]);
    defer @import("decoder.zig").close(fds[1]);
    const e = try Engine.create(std.testing.allocator, true);
    defer e.destroy();
    var request: Request = .{ .kind = .play_stream, .a = fds[0] };
    const t = try std.Thread.spawn(.{}, struct {
        fn run(engine: *Engine, r: *Request) void {
            _ = engine.submit(r);
        }
    }.run, .{ e, &request });
    while (!e.api_busy.load(.acquire)) c.fa_sleep_ms(1);
    c.fa_sleep_ms(20);
    var stop: Request = .{ .kind = .stop };
    try std.testing.expect(e.submit(&stop));
    t.join();
    try std.testing.expect(!request.success);
}

test "48k stereo loudness agrees with FFmpeg ebur128 reference" {
    const std = @import("std");
    var source = try Decoder.openPath(std.testing.allocator, "zig/fixtures/tone.wav", 48000, 2);
    defer source.deinit();
    var analyzer = @import("loudness.zig").Analyzer.init(std.testing.allocator);
    defer analyzer.deinit();
    while (true) {
        const pcm = try source.read();
        if (pcm.len == 0) break;
        try analyzer.add(pcm);
    }
    // FFmpeg 8.1 aformat=flt:48000:stereo,ebur128 reports -15.7 LUFS.
    try std.testing.expectApproxEqAbs(@as(f64, -15.7), @import("loudness.zig").Analyzer.integrated(analyzer.blocks.items).?, 0.1);
}

test "source announces assets and waits for distinct ready peers before commit" {
    const std = @import("std");
    const a = std.testing.allocator;
    const Mock = struct {
        messages: std.ArrayList([]u8) = .empty,
        received: usize = 0,
        fn send(ctx: *anyopaque, _: []const u8, bytes: []const u8) !void {
            const self: *@This() = @ptrCast(@alignCast(ctx));
            const copy = try std.testing.allocator.dupe(u8, bytes);
            errdefer std.testing.allocator.free(copy);
            try self.messages.append(std.testing.allocator, copy);
        }
        fn receive(ctx: *anyopaque, allocator: std.mem.Allocator, _: u64) ![]u8 {
            const self: *@This() = @ptrCast(@alignCast(ctx));
            const peer: []const u8 = if (self.received < 2) "one" else "two";
            self.received += 1;
            if (self.received > 3) return error.NoReplies;
            return std.json.Stringify.valueAlloc(allocator, .{ .type = "ready", .generation = @as(u64, 7), .participant_id = peer, .buffered_ms = @as(u32, 100), .uncertainty_ns = @as(u64, 0) }, .{});
        }
    };
    var mock: Mock = .{};
    defer {
        for (mock.messages.items) |message| a.free(message);
        mock.messages.deinit(a);
    }
    var store: assets.Store = .{ .allocator = a };
    defer store.deinit();
    var source: fabric_source.Source = .{ .allocator = a, .control = .{ .context = &mock, .send = Mock.send, .receive = Mock.receive }, .peers = &.{ "one", "two" }, .store = &store };
    try source.announce(.{ .generation = 7, .meta = .{ .title = "test" }, .cover = "cover", .lyrics = "verse" }, .per_peer_unicast);
    var hash: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash("cover", &hash, .{});
    try source.fulfillAsset("one", hash);
    const c = @import("decoder.zig").c;
    const asset_file = c.tmpfile() orelse return error.OpenFailed;
    defer _ = c.fclose(asset_file);
    var epoch: std.atomic.Value(u32) = .init(0);
    try source.serveAsset("one", hash, testDescriptor(asset_file), &epoch, 0);
    c.rewind(asset_file);
    const e = try Engine.create(a, true); defer e.destroy();
    var renderer = try FabricRenderer.init(a, e, "one"); defer renderer.deinit();
    const reply = try renderer.control(mock.messages.items[0]); defer a.free(reply);
    try renderer.acceptAsset(testDescriptor(asset_file));
    const cover = (try renderer.cover()).?; defer a.free(cover);
    try std.testing.expectEqualStrings("cover", cover);
    try source.command(.pause, 7);
    try source.seek(7, 500);
    try std.testing.expectEqual(@as(i128, 400000010), try source.synchronize(7, 10, 900000000));
    try std.testing.expectEqual(@as(usize, 3), mock.received);
    try std.testing.expect(std.mem.indexOf(u8, mock.messages.items[mock.messages.items.len - 1], "commit") != null);
}

test "seek remains available after decoder reaches EOF ahead of playback" {
    const std = @import("std");
    const c = @import("decoder.zig").c;
    const e = try Engine.create(std.testing.allocator, true);
    defer e.destroy();
    try std.testing.expect(fa_engine_play(e, "zig/fixtures/tone.wav"));
    var pcm: [960]f32 = undefined;
    for (0..85) |_| {
        c.fa_sleep_ms(2);
        e.render(&pcm);
    }
    try std.testing.expect(e.finished.load(.acquire));
    try std.testing.expect(fa_engine_seek(e, 100));
    for (0..5) |_| {
        c.fa_sleep_ms(2);
        e.render(&pcm);
    }
    try std.testing.expect(e.status().position_ms >= 100 and e.status().position_ms < 200);
}
