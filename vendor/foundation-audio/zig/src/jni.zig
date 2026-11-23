const std = @import("std");
const j = @import("native");
const Engine = @import("engine.zig").Engine;
const Request = @import("engine.zig").Request;
const Decoder = @import("decoder.zig").Decoder;
const c = @import("decoder.zig").c;
const allocator = std.heap.c_allocator;
const Env = [*c]j.JNIEnv;
const Cast = @import("cast.zig").Cast;
const Renderer = @import("fabric.zig").Renderer;
var casts: @import("handles.zig").Registry(Cast) = .{ .allocator = allocator };
var renderers: @import("handles.zig").Registry(Renderer) = .{ .allocator = allocator };
const Buffer = struct { bytes: []u8, reference: j.jobject };
var buffer_guard: @import("handles.zig").Lock = .{};
var buffers: std.AutoHashMapUnmanaged(usize, Buffer) = .empty;
var instance: std.atomic.Value(?*Engine) = .init(null);
var initialize: std.atomic.Value(bool) = .init(false);
fn engine() ?*Engine {
    if (instance.load(.acquire)) |value| return value;
    while (initialize.cmpxchgWeak(false, true, .acquire, .monotonic) != null) c.fa_sleep_ms(1);
    defer initialize.store(false, .release);
    if (instance.load(.acquire)) |value| return value;
    const value = Engine.create(allocator, false) catch return null;
    instance.store(value, .release);
    return value;
}
fn submit(request: *Request) j.jboolean {
    return if (engine()) |e| @intFromBool(e.submit(request)) else 0;
}
fn direct(env: Env, object: j.jobject, minimum: usize) ?[]u8 {
    const capacity = env.*.*.GetDirectBufferCapacity.?(env, object);
    if (capacity < 0 or capacity < minimum) return null;
    const ptr = env.*.*.GetDirectBufferAddress.?(env, object) orelse return null;
    return @as([*]u8, @ptrCast(ptr))[0..@intCast(capacity)];
}
fn toString(env: Env, text: []const u8) j.jstring {
    const utf16 = std.unicode.utf8ToUtf16LeAlloc(allocator, text) catch return null;
    defer allocator.free(utf16);
    return env.*.*.NewString.?(env, utf16.ptr, @intCast(utf16.len));
}
fn fromString(env: Env, value: j.jstring) ![:0]u8 {
    if (value == null) return error.NullString;
    const len = env.*.*.GetStringLength.?(env, value);
    const chars = env.*.*.GetStringChars.?(env, value, null);
    if (chars == null) return error.InvalidString;
    defer env.*.*.ReleaseStringChars.?(env, value, chars);
    return std.unicode.utf16LeToUtf8AllocZ(allocator, chars[0..@intCast(len)]);
}
fn ownedBuffer(env: Env, bytes: []u8) j.jobject {
    const result = env.*.*.NewDirectByteBuffer.?(env, bytes.ptr, @intCast(bytes.len));
    if (result == null) {
        allocator.free(bytes);
        return null;
    }
    const reference = env.*.*.NewGlobalRef.?(env, result);
    if (reference == null) {
        allocator.free(bytes);
        return null;
    }
    buffer_guard.lock();
    defer buffer_guard.unlock();
    buffers.put(allocator, @intFromPtr(bytes.ptr), .{ .bytes = bytes, .reference = reference }) catch {
        env.*.*.DeleteGlobalRef.?(env, reference);
        allocator.free(bytes);
        return null;
    };
    return result;
}
fn fromBytes(env: Env, object: j.jbyteArray, maximum: usize) ![]u8 {
    if (object == null) return error.NullBytes;
    const count = env.*.*.GetArrayLength.?(env, object);
    if (count < 0 or count > maximum) return error.TooLarge;
    const bytes = try allocator.alloc(u8, @intCast(count));
    errdefer allocator.free(bytes);
    env.*.*.GetByteArrayRegion.?(env, object, 0, count, @ptrCast(bytes.ptr));
    if (env.*.*.ExceptionCheck.?(env) != 0) return error.JavaException;
    return bytes;
}
fn toBytes(env: Env, bytes: []const u8) j.jbyteArray {
    const result = env.*.*.NewByteArray.?(env, @intCast(bytes.len));
    if (result != null) env.*.*.SetByteArrayRegion.?(env, result, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    return result;
}
fn castStreamOpen(_: Env, _: j.jobject, source: j.jint, sink: j.jint, milliseconds: j.jlong) callconv(.c) j.jlong {
    if (milliseconds < 0) return 0;
    var cast = Cast.open(allocator, source, sink, @intCast(milliseconds)) catch return 0;
    const id = casts.insert(cast) catch {
        cast.deinit();
        return 0;
    };
    const value = casts.acquire(id).?;
    defer casts.release(value);
    value.start() catch {
        const removed = casts.remove(id).?;
        casts.release(removed);
        return 0;
    };
    return @intCast(id);
}
fn castStreamAwait(_: Env, _: j.jobject, handle: j.jlong) callconv(.c) j.jboolean {
    if (handle <= 0) return 1;
    const value = casts.acquire(@intCast(handle)) orelse return 1;
    defer casts.release(value);
    return @intFromBool(value.awaitEnd());
}
fn castStreamSeek(_: Env, _: j.jobject, handle: j.jlong, ms: j.jlong) callconv(.c) j.jboolean {
    if (handle <= 0 or ms < 0) return 0;
    const value = casts.acquire(@intCast(handle)) orelse return 0;
    defer casts.release(value);
    return @intFromBool(value.seek(@intCast(ms)));
}
fn castStreamClose(_: Env, _: j.jobject, handle: j.jlong) callconv(.c) void {
    if (handle <= 0) return;
    const value = casts.remove(@intCast(handle)) orelse return;
    value.cancel();
    casts.release(value);
}
fn playMediaStreamFd(env: Env, _: j.jobject, fd: j.jint, paused: j.jboolean, json: j.jstring, artwork: j.jbyteArray) callconv(.c) j.jboolean {
    const e = engine() orelse return 0;
    const meta_json = fromString(env, json) catch return 0;
    defer allocator.free(meta_json);
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, meta_json, .{}) catch return 0;
    defer parsed.deinit();
    const cover = if (artwork != null) fromBytes(env, artwork, 16 * 1024 * 1024) catch return 0 else allocator.alloc(u8, 0) catch return 0;
    defer allocator.free(cover);
    const meta = @import("metadata.zig").fromJson(allocator, parsed.value, cover) catch return 0;
    defer allocator.free(meta);
    var request: Request = .{ .kind = .play_stream, .a = fd, .b = paused, .bytes = meta, .duration_ms = @import("fabric.zig").uint(@import("fabric.zig").field(parsed.value, "duration_ms")) orelse 0 };
    return @intFromBool(e.submit(&request));
}
fn nativeCreate(env: Env, _: j.jclass, participant: j.jstring) callconv(.c) j.jlong {
    const e = engine() orelse return 0;
    const id = fromString(env, participant) catch return 0;
    defer allocator.free(id);
    var renderer = Renderer.init(allocator, e, id) catch return 0;
    const handle = renderers.insert(renderer) catch {
        renderer.deinit();
        return 0;
    };
    return @intCast(handle);
}
fn nativeHandleControl(env: Env, _: j.jclass, handle: j.jlong, payload: j.jbyteArray) callconv(.c) j.jbyteArray {
    if (handle <= 0) return null;
    const renderer = renderers.acquire(@intCast(handle)) orelse return null;
    defer renderers.release(renderer);
    const bytes = fromBytes(env, payload, 2 * 1024 * 1024) catch return null;
    defer allocator.free(bytes);
    const reply = renderer.control(bytes) catch return null;
    defer allocator.free(reply);
    return toBytes(env, reply);
}
fn nativeAttachMediaFd(_: Env, _: j.jclass, handle: j.jlong, fd: j.jint) callconv(.c) j.jboolean {
    if (handle <= 0) return 0;
    const renderer = renderers.acquire(@intCast(handle)) orelse return 0;
    defer renderers.release(renderer);
    renderer.attach(fd) catch return 0;
    return 1;
}
fn nativeAcceptAssetFd(_: Env, _: j.jclass, handle: j.jlong, fd: j.jint) callconv(.c) j.jboolean {
    if (handle <= 0) return 0;
    const renderer = renderers.acquire(@intCast(handle)) orelse return 0;
    defer renderers.release(renderer);
    renderer.acceptAsset(fd) catch return 0;
    return 1;
}
fn nativeFeedbackJson(env: Env, _: j.jclass, handle: j.jlong) callconv(.c) j.jbyteArray {
    if (handle <= 0) return null;
    const renderer = renderers.acquire(@intCast(handle)) orelse return null;
    defer renderers.release(renderer);
    const bytes = renderer.feedback() catch return null;
    defer allocator.free(bytes);
    return toBytes(env, bytes);
}
fn nativePresentationJson(env: Env, _: j.jclass, handle: j.jlong) callconv(.c) j.jbyteArray {
    if (handle <= 0) return null;
    const renderer = renderers.acquire(@intCast(handle)) orelse return null;
    defer renderers.release(renderer);
    const bytes = renderer.presentation() catch return null;
    defer allocator.free(bytes);
    return toBytes(env, bytes);
}
fn nativeCoverBytes(env: Env, _: j.jclass, handle: j.jlong) callconv(.c) j.jbyteArray {
    if (handle <= 0) return null;
    const renderer = renderers.acquire(@intCast(handle)) orelse return null;
    defer renderers.release(renderer);
    const bytes = (renderer.cover() catch return null) orelse return null;
    defer allocator.free(bytes);
    return toBytes(env, bytes);
}
fn nativeDestroy(_: Env, _: j.jclass, handle: j.jlong) callconv(.c) void {
    if (handle <= 0) return;
    const renderer = renderers.remove(@intCast(handle)) orelse return;
    renderer.cancel();
    renderers.release(renderer);
}
fn init(_: Env, _: j.jobject, _: j.jobject) callconv(.c) void {
    _ = engine();
}
fn play(env: Env, _: j.jobject, value: j.jstring) callconv(.c) j.jboolean {
    const path = fromString(env, value) catch return 0;
    defer allocator.free(path);
    var request: Request = .{ .kind = .play_path, .path = path };
    return submit(&request);
}
fn playFd(_: Env, _: j.jobject, fd: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .play_fd, .a = fd };
    return submit(&r);
}
fn pause(_: Env, _: j.jobject) callconv(.c) void {
    if (engine()) |e| e.setPaused(true);
}
fn @"resume"(_: Env, _: j.jobject) callconv(.c) void {
    if (engine()) |e| e.setPaused(false);
}
fn stop(_: Env, _: j.jobject) callconv(.c) void {
    var r: Request = .{ .kind = .stop };
    _ = submit(&r);
}
fn addToQueue(env: Env, _: j.jobject, value: j.jstring) callconv(.c) j.jboolean {
    const path = fromString(env, value) catch return 0;
    defer allocator.free(path);
    var r: Request = .{ .kind = .add_path, .path = path };
    return submit(&r);
}
fn addToQueueFd(_: Env, _: j.jobject, fd: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .add_fd, .a = fd };
    return submit(&r);
}
fn clearQueue(_: Env, _: j.jobject) callconv(.c) void {
    var r: Request = .{ .kind = .clear };
    _ = submit(&r);
}
fn setQueueIndex(_: Env, _: j.jobject, index: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .select, .a = index };
    return submit(&r);
}
fn getQueueIndex(_: Env, _: j.jobject) callconv(.c) j.jint {
    return if (engine()) |e| e.index.load(.acquire) else -1;
}
fn moveQueueItem(_: Env, _: j.jobject, from: j.jint, to: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .move, .a = from, .b = to };
    return submit(&r);
}
fn seekTo(_: Env, _: j.jobject, ms: j.jlong) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .seek, .a = ms };
    return submit(&r);
}
fn skipToNext(_: Env, _: j.jobject) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .next };
    return submit(&r);
}
fn skipToPrevious(_: Env, _: j.jobject) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .previous };
    return submit(&r);
}
fn setCrossfadeDuration(_: Env, _: j.jobject, ms: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .crossfade, .a = ms };
    return submit(&r);
}
fn setEbuR128Enabled(_: Env, _: j.jobject, value: j.jboolean) callconv(.c) void {
    var r: Request = .{ .kind = .gain_enabled, .a = value };
    _ = submit(&r);
}
fn setQueueEbuR128Gain(_: Env, _: j.jobject, index: j.jint, value: j.jfloat) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .gain, .a = index, .value = value };
    return submit(&r);
}
fn setPlayMode(_: Env, _: j.jobject, mode: j.jint) callconv(.c) j.jboolean {
    var r: Request = .{ .kind = .mode, .a = mode };
    return submit(&r);
}
fn setSilenceRemoval(_: Env, _: j.jobject, value: j.jboolean, threshold: j.jfloat, milliseconds: j.jint) callconv(.c) void {
    var r: Request = .{ .kind = .silence, .a = value, .b = milliseconds, .value = threshold };
    _ = submit(&r);
}
fn setSoundstageCrossfade(_: Env, _: j.jobject, value: j.jboolean) callconv(.c) void {
    var r: Request = .{ .kind = .soundstage, .a = value };
    _ = submit(&r);
}
fn getPlaybackStatusDirect(env: Env, _: j.jobject, object: j.jobject) callconv(.c) j.jboolean {
    const e = engine() orelse return 0;
    if (e.state.load(.acquire) == 0) return 0;
    const bytes = direct(env, object, 32) orelse return 0;
    const status = e.status();
    std.mem.writeInt(u64, bytes[0..8], status.position_ms, .little);
    std.mem.writeInt(u64, bytes[8..16], status.duration_ms, .little);
    std.mem.writeInt(u32, bytes[16..20], status.paused, .little);
    std.mem.writeInt(u32, bytes[20..24], @bitCast(status.speed), .little);
    std.mem.writeInt(u64, bytes[24..32], status.generation, .little);
    return 1;
}
fn getSpectrumPowerDirect(env: Env, _: j.jobject, object: j.jobject) callconv(.c) j.jboolean {
    const e = engine() orelse return 0;
    const bytes = direct(env, object, 16) orelse return 0;
    const power = e.presentedSpectrum();
    std.mem.writeInt(u32, bytes[0..4], @bitCast(power.low), .little);
    std.mem.writeInt(u32, bytes[4..8], @bitCast(power.mid), .little);
    std.mem.writeInt(u32, bytes[8..12], @bitCast(power.high), .little);
    std.mem.writeInt(u32, bytes[12..16], power.sequence, .little);
    return @intFromBool(power.sequence != 0);
}
fn getPlaybackStatus(env: Env, _: j.jobject) callconv(.c) j.jobject {
    const e = engine() orelse return null;
    if (e.state.load(.acquire) == 0) return null;
    const class = env.*.*.FindClass.?(env, "com/mocharealm/foundation/audio/PlaybackStatus");
    if (class == null) return null;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const constructor = env.*.*.GetMethodID.?(env, class, "<init>", "(JJZFJ)V");
    if (constructor == null) return null;
    const s = e.status();
    const values = [_]j.jvalue{ .{ .j = @bitCast(s.position_ms) }, .{ .j = @bitCast(s.duration_ms) }, .{ .z = @intCast(s.paused) }, .{ .f = s.speed }, .{ .j = @bitCast(s.generation) } };
    return env.*.*.NewObjectA.?(env, class, constructor, &values);
}
fn extractMetadataFdDirect(env: Env, _: j.jobject, fd: j.jint) callconv(.c) j.jobject {
    var source = Decoder.openFd(allocator, fd, 48000, 2) catch return null;
    defer source.deinit();
    const bytes = @import("metadata.zig").wire(allocator, &source) catch return null;
    return ownedBuffer(env, bytes);
}
fn releaseMetadataBuffer(env: Env, _: j.jobject, object: j.jobject) callconv(.c) void {
    const bytes = direct(env, object, 0) orelse return;
    buffer_guard.lock();
    const buffer = buffers.get(@intFromPtr(bytes.ptr)) orelse {
        buffer_guard.unlock();
        return;
    };
    if (env.*.*.IsSameObject.?(env, object, buffer.reference) == 0) {
        buffer_guard.unlock();
        return;
    }
    _ = buffers.remove(@intFromPtr(bytes.ptr));
    buffer_guard.unlock();
    env.*.*.DeleteGlobalRef.?(env, buffer.reference);
    allocator.free(buffer.bytes);
}
const Wire = struct {
    bytes: []const u8,
    offset: usize = 0,
    fn int(self: *Wire) !u32 {
        if (self.offset > self.bytes.len or self.bytes.len - self.offset < 4) return error.Truncated;
        const value = std.mem.readInt(u32, self.bytes[self.offset..][0..4], .little);
        self.offset += 4;
        return value;
    }
    fn string(self: *Wire) ![]const u8 {
        const count = try self.int();
        if (count > self.bytes.len - self.offset) return error.Truncated;
        const value = self.bytes[self.offset..][0..count];
        self.offset += count;
        return value;
    }
};
fn getMetadata(env: Env, _: j.jobject) callconv(.c) j.jobject {
    var request: Request = .{ .kind = .metadata };
    if (submit(&request) == 0) return null;
    const bytes = request.bytes orelse return null;
    defer allocator.free(bytes);
    var wire: Wire = .{ .bytes = bytes };
    if ((wire.int() catch return null) != @import("metadata.zig").magic) return null;
    const count = wire.int() catch return null;
    var texts: [5][]const u8 = @splat("");
    var numbers: [3]i32 = @splat(0);
    var duration: i64 = 0;
    for (0..count) |_| {
        const key = wire.string() catch return null;
        const value = wire.string() catch return null;
        for ([_][]const u8{ "title", "artist", "album", "genre", "lyrics" }, 0..) |name, i| if (std.mem.eql(u8, key, name)) {
            texts[i] = value;
        };
        for ([_][]const u8{ "date", "track", "disc" }, 0..) |name, i| if (std.mem.eql(u8, key, name)) {
            const end = std.mem.indexOfAny(u8, value, "/- ") orelse value.len;
            numbers[i] = std.fmt.parseInt(i32, value[0..end], 10) catch 0;
        };
        if (std.mem.eql(u8, key, "duration")) {
            const seconds = std.fmt.parseFloat(f64, value) catch 0;
            if (std.math.isFinite(seconds) and seconds > 0 and seconds < 1.0e12) duration = @intFromFloat(seconds * 1000);
        }
    }
    const class = env.*.*.FindClass.?(env, "com/mocharealm/foundation/audio/TrackMetadata");
    if (class == null) return null;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const constructor = env.*.*.GetMethodID.?(env, class, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIJ)V");
    if (constructor == null) return null;
    var values: [9]j.jvalue = undefined;
    for (texts, 0..) |text, i| values[i] = .{ .l = toString(env, text) };
    defer for (values[0..5]) |value| env.*.*.DeleteLocalRef.?(env, value.l);
    for (numbers, 0..) |value, i| values[i + 5] = .{ .i = value };
    values[8] = .{ .j = duration };
    return env.*.*.NewObjectA.?(env, class, constructor, &values);
}
fn extractMetadataFd(env: Env, _: j.jobject, fd: j.jint, tags: j.jobject) callconv(.c) j.jbyteArray {
    var source = Decoder.openFd(allocator, fd, 48000, 2) catch return null;
    defer source.deinit();
    const bytes = @import("metadata.zig").wire(allocator, &source) catch return null;
    defer allocator.free(bytes);
    const class = env.*.*.GetObjectClass.?(env, tags);
    if (class == null) return null;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const put = env.*.*.GetMethodID.?(env, class, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    if (put == null) return null;
    var wire: Wire = .{ .bytes = bytes };
    _ = wire.int() catch return null;
    const count = wire.int() catch return null;
    for (0..count) |_| {
        const key = toString(env, wire.string() catch return null);
        defer env.*.*.DeleteLocalRef.?(env, key);
        const value = toString(env, wire.string() catch return null);
        defer env.*.*.DeleteLocalRef.?(env, value);
        const args = [_]j.jvalue{ .{ .l = key }, .{ .l = value } };
        const old = env.*.*.CallObjectMethodA.?(env, tags, put, &args);
        env.*.*.DeleteLocalRef.?(env, old);
        if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    }
    const cover_length = wire.int() catch return null;
    if (cover_length == 0 or cover_length > bytes.len - wire.offset) return null;
    const result = env.*.*.NewByteArray.?(env, @intCast(cover_length));
    if (result != null) env.*.*.SetByteArrayRegion.?(env, result, 0, @intCast(cover_length), @ptrCast(bytes[wire.offset..].ptr));
    return result;
}
fn getDiagnosticLog(env: Env, _: j.jobject) callconv(.c) j.jstring {
    const e = engine() orelse return toString(env, "Zig audio: device unavailable");
    var buffer: [512]u8 = undefined;
    const error_code = e.last_error.load(.acquire);
    const last_error = if (error_code == 0) "none" else @errorName(@errorFromInt(@as(u16, @intCast(error_code))));
    const text = std.fmt.bufPrint(&buffer, "backend=zig\nposition_ms={d}\nbuffered_frames={d}\nunderruns={d}\nlast_error={s}\nai=disabled", .{ e.position.load(.acquire), e.output.available(), e.underruns.load(.acquire), last_error }) catch return null;
    return toString(env, text);
}
const Clock = extern struct { position_ms: u64, is_paused: u8, duration_ms: u64 };
fn clock(out: ?*Clock) callconv(.c) bool {
    const target = out orelse return false;
    const e = instance.load(.acquire) orelse return false;
    const status = e.status();
    if (status.duration_ms == 0) return false;
    target.* = .{ .position_ms = status.position_ms, .is_paused = @intCast(status.paused), .duration_ms = status.duration_ms };
    return true;
}
fn rms() callconv(.c) f32 {
    return if (instance.load(.acquire)) |e| @bitCast(e.rms.load(.acquire)) else 0;
}
fn calculateEbuR128Album(env: Env, _: j.jobject, descriptors: j.jintArray) callconv(.c) j.jfloatArray {
    const count = env.*.*.GetArrayLength.?(env, descriptors);
    if (count <= 0 or count > 100000) return null;
    const fds = allocator.alloc(j.jint, @intCast(count)) catch return null;
    defer allocator.free(fds);
    env.*.*.GetIntArrayRegion.?(env, descriptors, 0, count, fds.ptr);
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    const gains = allocator.alloc(f32, @as(usize, @intCast(count)) + 1) catch return null;
    defer allocator.free(gains);
    var album: std.ArrayList(f64) = .empty;
    defer album.deinit(allocator);
    const Analyzer = @import("loudness.zig").Analyzer;
    for (fds, 0..) |fd, i| {
        gains[i] = std.math.nan(f32);
        var source = Decoder.openFd(allocator, fd, 48000, 2) catch continue;
        defer source.deinit();
        var analyzer = Analyzer.init(allocator);
        defer analyzer.deinit();
        var valid = true;
        while (true) {
            const pcm = source.read() catch {
                valid = false;
                break;
            };
            if (pcm.len == 0) break;
            analyzer.add(pcm) catch {
                valid = false;
                break;
            };
        }
        if (valid) {
            gains[i] = Analyzer.gain(analyzer.blocks.items);
            album.appendSlice(allocator, analyzer.blocks.items) catch return null;
        }
    }
    gains[gains.len - 1] = Analyzer.gain(album.items);
    const result = env.*.*.NewFloatArray.?(env, count + 1);
    if (result != null) env.*.*.SetFloatArrayRegion.?(env, result, 0, count + 1, gains.ptr);
    return result;
}
// AI features are deliberately disabled for this migration. These compatibility
// entry points report Off and never load a model or claim successful inference.
fn setKaraokeEnabled(_: Env, _: j.jobject, _: j.jboolean) callconv(.c) void {}
fn setKaraokeFadeMs(_: Env, _: j.jobject, _: j.jint) callconv(.c) void {}
fn getKaraokeStatusDirect(env: Env, _: j.jobject, object: j.jobject) callconv(.c) j.jboolean {
    const bytes = direct(env, object, 8) orelse return 0;
    @memset(bytes[0..8], 0);
    return 1;
}
fn decodeMusicAnalysisPcm(_: Env, _: j.jobject, _: j.jint) callconv(.c) j.jobject {
    return null;
}
comptime {
    const prefix = "Java_com_mocharealm_foundation_audio_AudioEngine_";
    for (.{ "init", "play", "playFd", "pause", "resume", "stop", "addToQueue", "addToQueueFd", "clearQueue", "setQueueIndex", "getQueueIndex", "moveQueueItem", "seekTo", "skipToNext", "skipToPrevious", "setCrossfadeDuration", "setEbuR128Enabled", "setQueueEbuR128Gain", "setPlayMode", "setSilenceRemoval", "getPlaybackStatusDirect", "getSpectrumPowerDirect", "extractMetadataFdDirect", "releaseMetadataBuffer", "getDiagnosticLog", "calculateEbuR128Album", "setKaraokeEnabled", "setKaraokeFadeMs", "getKaraokeStatusDirect", "decodeMusicAnalysisPcm" }) |name| {
        @export(&@field(@This(), name), .{ .name = prefix ++ name });
    }
    @export(&releaseMetadataBuffer, .{ .name = prefix ++ "releaseMusicAnalysisPcm" });
    @export(&calculateEbuR128Album, .{ .name = prefix ++ "calculateReplayGainAlbum" });
    @export(&setEbuR128Enabled, .{ .name = prefix ++ "setReplayGainEnabled" });
    @export(&setQueueEbuR128Gain, .{ .name = prefix ++ "setQueueReplayGain" });
    @export(&clock, .{ .name = "mf_get_playback_clock" });
    @export(&rms, .{ .name = "mf_get_audio_rms" });
    @export(&getMetadata, .{ .name = prefix ++ "getMetadata" });
    @export(&extractMetadataFd, .{ .name = prefix ++ "extractMetadataFd" });
    @export(&setSoundstageCrossfade, .{ .name = prefix ++ "setSoundstageCrossfade" });
    @export(&getPlaybackStatus, .{ .name = prefix ++ "getPlaybackStatus" });
    for (.{ "castStreamOpen", "castStreamAwait", "castStreamSeek", "castStreamClose", "playMediaStreamFd" }) |name| @export(&@field(@This(), name), .{ .name = prefix ++ name });
    for (.{ "nativeCreate", "nativeHandleControl", "nativeAttachMediaFd", "nativeAcceptAssetFd", "nativeFeedbackJson", "nativePresentationJson", "nativeCoverBytes", "nativeDestroy" }) |name| @export(&@field(@This(), name), .{ .name = "Java_com_mocharealm_foundation_audio_FabricRenderer_" ++ name });
}
