const std = @import("std");
const engine_module = @import("engine.zig");
const Engine = engine_module.Engine;
const Request = engine_module.Request;
const c = @import("c.zig").api;
const assets = @import("assets.zig");
const Value = std.json.Value;
pub fn field(value: Value, key: []const u8) Value {
    return if (value == .object) value.object.get(key) orelse .null else .null;
}
pub fn text(value: Value) ?[]const u8 {
    return if (value == .string) value.string else null;
}
pub fn uint(value: Value) ?u64 {
    return switch (value) {
        .integer => |n| if (n >= 0) @intCast(n) else null,
        .number_string => |s| std.fmt.parseInt(u64, s, 10) catch null,
        else => null,
    };
}
fn hashValue(value: Value) !assets.Hash {
    if (value != .array or value.array.items.len != 32) return error.InvalidHash;
    var result: assets.Hash = undefined;
    for (value.array.items, 0..) |part, i| {
        const n = uint(part) orelse return error.InvalidHash;
        result[i] = std.math.cast(u8, n) orelse return error.InvalidHash;
    }
    return result;
}
pub const Feedback = struct {
    generation: u64,
    state: []const u8,
    position_ms: u64,
    duration_ms: u64,
    buffered_ms: u32,
    sample_rate: u32 = 48000,
    underruns: u32,
};
pub const Renderer = struct {
    allocator: std.mem.Allocator,
    engine: *Engine,
    participant: []u8,
    guard: @import("handles.zig").Lock = .{},
    epoch: std.atomic.Value(u32) = .init(0),
    origin_ns: u64,
    clock: @import("protocol.zig").Clock = .{},
    generation: u64 = 0,
    pending_ns: u64 = 0,
    announcement: ?std.json.Parsed(Value) = null,
    store: assets.Store,
    cover_hash: ?assets.Hash = null,
    lyrics_hash: ?assets.Hash = null,
    pub fn init(allocator: std.mem.Allocator, engine: *Engine, participant: []const u8) !Renderer {
        return .{ .allocator = allocator, .engine = engine, .participant = try allocator.dupe(u8, participant), .origin_ns = c.fa_monotonic_ns(), .store = .{ .allocator = allocator } };
    }
    pub fn cancel(self: *Renderer) void {
        self.epoch.store(1, .release);
        self.engine.resume_at.store(0, .release);
        _ = self.engine.stream_epoch.fetchAdd(1, .acq_rel);
    }
    pub fn deinit(self: *Renderer) void {
        if (self.announcement) |*a| a.deinit();
        self.store.deinit();
        self.allocator.free(self.participant);
    }
    pub fn setClock(self: *Renderer, clock: @import("protocol.zig").Clock) void {
        self.guard.lock();
        defer self.guard.unlock();
        self.clock = clock;
    }
    fn feedbackValue(self: *Renderer) Feedback {
        const snapshot = self.engine.status();
        const states = [_][]const u8{ "stopped", "playing", "paused", "buffering" };
        return .{ .generation = self.generation, .state = states[@min(self.engine.state.load(.acquire), 3)], .position_ms = snapshot.position_ms, .duration_ms = snapshot.duration_ms, .buffered_ms = @intCast(self.engine.output.available() * 1000 / 48000), .underruns = @intCast(@min(self.engine.underruns.load(.acquire), std.math.maxInt(u32))) };
    }
    pub fn feedback(self: *Renderer) ![]u8 {
        self.guard.lock();
        defer self.guard.unlock();
        return std.json.Stringify.valueAlloc(self.allocator, self.feedbackValue(), .{});
    }
    fn status(self: *Renderer, ok: bool, err: ?[]const u8) ![]u8 {
        return std.json.Stringify.valueAlloc(self.allocator, .{ .ok = ok, .@"error" = err, .status = self.feedbackValue() }, .{});
    }
    fn meta(self: *Renderer) Value {
        return if (self.announcement) |a| field(a.value, "meta") else .null;
    }
    pub fn presentation(self: *Renderer) ![]u8 {
        self.guard.lock();
        defer self.guard.unlock();
        const m = self.meta();
        const lyrics = if (self.lyrics_hash) |hash| if (self.store.get(hash)) |blob| if (std.unicode.utf8ValidateSlice(blob.bytes)) blob.bytes else text(field(m, "lyrics")) else text(field(m, "lyrics")) else text(field(m, "lyrics"));
        return std.json.Stringify.valueAlloc(self.allocator, .{
            .generation = self.generation,
            .title = field(m, "title"),
            .artist = field(m, "artist"),
            .album = field(m, "album"),
            .album_artist = field(m, "album_artist"),
            .genre = field(m, "genre"),
            .year = field(m, "year"),
            .track_number = field(m, "track_number"),
            .disc_number = field(m, "disc_number"),
            .duration_ms = uint(field(m, "duration_ms")) orelse 0,
            .lyrics = lyrics,
        }, .{});
    }
    pub fn cover(self: *Renderer) !?[]u8 {
        self.guard.lock();
        defer self.guard.unlock();
        const hash = self.cover_hash orelse return null;
        const blob = self.store.get(hash) orelse return null;
        return try self.allocator.dupe(u8, blob.bytes);
    }
    pub fn control(self: *Renderer, bytes: []const u8) ![]u8 {
        self.guard.lock();
        defer self.guard.unlock();
        if (self.epoch.load(.acquire) != 0) return error.Closed;
        return self.handle(bytes) catch |err| self.status(false, @errorName(err));
    }
    fn handle(self: *Renderer, bytes: []const u8) ![]u8 {
        if (bytes.len > 2 * 1024 * 1024) return error.ControlTooLarge;
        var parsed = try std.json.parseFromSlice(Value, self.allocator, bytes, .{ .allocate = .alloc_always });
        defer parsed.deinit();
        const value = parsed.value;
        const kind = text(field(value, "type")) orelse return error.MissingType;
        const generation = uint(field(value, "generation")) orelse 0;
        if (std.mem.eql(u8, kind, "track_announce")) {
            if (field(value, "meta") != .object or field(value, "media") != .object) return error.InvalidAnnounce;
            const cover_hash = if (field(value, "cover_hash") != .null) try hashValue(field(value, "cover_hash")) else null;
            const lyrics_hash = if (field(value, "lyrics_hash") != .null) try hashValue(field(value, "lyrics_hash")) else null;
            const copy = try std.json.parseFromSlice(Value, self.allocator, bytes, .{ .allocate = .alloc_always });
            if (self.announcement) |*a| a.deinit();
            self.announcement = copy;
            self.generation = generation;
            self.pending_ns = 0;
            self.engine.resume_at.store(0, .release);
            self.cover_hash = cover_hash;
            self.lyrics_hash = lyrics_hash;
            const Reply = struct { type: []const u8 = "asset_request", hash: assets.Hash };
            var replies: [2]Reply = undefined;
            var count: usize = 0;
            for ([_]?assets.Hash{ cover_hash, lyrics_hash }) |optional| if (optional) |hash| {
                if (self.store.get(hash) == null) {
                    replies[count] = .{ .hash = hash };
                    count += 1;
                }
            };
            if (count > 0) return std.json.Stringify.valueAlloc(self.allocator, replies[0..count], .{});
        } else if (std.mem.eql(u8, kind, "asset_offer") or std.mem.eql(u8, kind, "asset_chunk")) {
            const hash = try hashValue(field(value, "hash"));
            const mime = text(field(value, "mime")) orelse return error.InvalidMime;
            if (std.mem.eql(u8, kind, "asset_offer")) {
                const encoded = text(field(value, "bytes")) orelse text(field(value, "bytes_b64")) orelse return error.InvalidBytes;
                try self.store.offer(hash, mime, encoded);
            } else {
                const encoded = text(field(value, "data")) orelse text(field(value, "data_b64")) orelse return error.InvalidBytes;
                const index = std.math.cast(usize, uint(field(value, "index")) orelse return error.InvalidIndex) orelse return error.InvalidIndex;
                const total = std.math.cast(usize, uint(field(value, "total")) orelse return error.InvalidTotal) orelse return error.InvalidTotal;
                try self.store.chunk(hash, mime, index, total, encoded);
            }
        } else if (std.mem.eql(u8, kind, "prepare_synced")) {
            if (generation != self.generation) return std.json.Stringify.valueAlloc(self.allocator, .{.{ .type = "reject", .generation = generation, .participant_id = self.participant, .reason = "stale generation" }}, .{});
            const local = uint(field(value, "start_at_local_ns")) orelse blk: {
                const group_value = field(value, "activate_at_group_ns");
                const group: i128 = switch (group_value) {
                    .integer => |n| n,
                    .number_string => |s| std.fmt.parseInt(i128, s, 10) catch return error.ClockMappingFailed,
                    else => return error.ClockMappingFailed,
                };
                break :blk try self.clock.toLocal(group);
            };
            const now = c.fa_monotonic_ns();
            self.pending_ns = @min(self.origin_ns +| local, now +| 10_000_000_000);
            if (self.engine.paused.load(.acquire)) self.engine.scheduleResume(self.pending_ns);
            return std.json.Stringify.valueAlloc(self.allocator, .{.{ .type = "ready", .generation = generation, .participant_id = self.participant, .uncertainty_ns = self.clock.uncertainty_ns, .buffered_ms = self.feedbackValue().buffered_ms }}, .{});
        } else if (std.mem.eql(u8, kind, "commit")) {
            if (generation != self.generation) return error.StaleGeneration;
            if (self.pending_ns != 0) self.engine.scheduleResume(self.pending_ns);
        } else if (std.mem.eql(u8, kind, "play") or std.mem.eql(u8, kind, "pause") or std.mem.eql(u8, kind, "stop") or std.mem.eql(u8, kind, "abort") or std.mem.eql(u8, kind, "seek")) {
            if (generation != 0 and generation != self.generation) return error.StaleGeneration;
            if (std.mem.eql(u8, kind, "play")) self.engine.setPaused(false) else if (std.mem.eql(u8, kind, "pause")) self.engine.setPaused(true) else if (std.mem.eql(u8, kind, "seek")) {
                const ms = std.math.cast(i64, uint(field(value, "position_ms")) orelse return error.InvalidPosition) orelse return error.InvalidPosition;
                var request: Request = .{ .kind = .seek, .a = ms };
                if (!self.engine.submit(&request)) {
                    var rebase: Request = .{ .kind = .rebase, .a = ms };
                    if (!self.engine.submit(&rebase)) return error.SeekFailed;
                }
            } else {
                self.pending_ns = 0;
                var request: Request = .{ .kind = .stop };
                if (!self.engine.submit(&request)) return error.StopFailed;
            }
        } else if (!std.mem.eql(u8, kind, "ping") and !std.mem.eql(u8, kind, "status") and !std.mem.eql(u8, kind, "asset_stream_announce") and !std.mem.eql(u8, kind, "ready") and !std.mem.eql(u8, kind, "reject") and !std.mem.eql(u8, kind, "asset_request")) return error.UnknownControl;
        return self.status(true, null);
    }
    pub fn attach(self: *Renderer, fd: c_int) !void {
        self.guard.lock();
        const snapshot = self.attachment() catch |err| {
            self.guard.unlock();
            return err;
        };
        self.guard.unlock();
        defer self.allocator.free(snapshot.hint);
        defer self.allocator.free(snapshot.metadata);
        var request: Request = .{ .kind = .play_stream, .a = fd, .b = 1, .path = snapshot.hint, .bytes = snapshot.metadata, .duration_ms = snapshot.duration, .expected_generation = snapshot.generation, .start_ms = snapshot.position, .stream_gain_db = snapshot.gain };
        if (!self.engine.submit(&request)) return error.StreamOpenFailed;
        self.guard.lock();
        defer self.guard.unlock();
        if (self.epoch.load(.acquire) != 0 or self.generation != snapshot.generation) return error.StaleGeneration;
        if (self.pending_ns != 0) self.engine.scheduleResume(self.pending_ns) else if (snapshot.was_playing and request.start_ms > 0) self.engine.setPaused(false);
    }
    const Attachment = struct { hint: [:0]u8, metadata: []u8, generation: u64, position: u64, duration: u64, was_playing: bool, gain: ?f32 };
    fn attachment(self: *Renderer) !Attachment {
        if (self.epoch.load(.acquire) != 0) return error.Closed;
        const a = self.announcement orelse return error.NoAnnounce;
        const media = field(a.value, "media");
        const hint = try self.allocator.dupeSentinel(u8, text(field(media, "container_hint")) orelse text(field(field(media, "decode"), "container")) orelse "", 0);
        errdefer self.allocator.free(hint);
        const cover_bytes = if (self.cover_hash) |hash| if (self.store.get(hash)) |blob| blob.bytes else &.{} else &.{};
        const gain = field(self.meta(), "replay_gain_db");
        return .{ .hint = hint, .metadata = try @import("metadata.zig").fromJson(self.allocator, self.meta(), cover_bytes), .generation = self.generation, .position = uint(field(media, "start_position_ms")) orelse 0, .duration = uint(field(self.meta(), "duration_ms")) orelse 0, .was_playing = self.engine.state.load(.acquire) == @backingInt(engine_module.State.playing), .gain = switch (gain) {
            .float => |n| @floatCast(n),
            .integer => |n| @floatFromInt(n),
            else => null,
        } };
    }
    pub fn acceptAsset(self: *Renderer, fd: c_int) !void {
        const decoder = @import("decoder.zig");
        const owned = try decoder.duplicate(fd);
        defer decoder.close(owned);
        var input: @import("stream.zig").Input = .{ .fd = owned, .epoch = &self.epoch, .expected = 0 };
        var header_bytes: [42]u8 = undefined;
        try input.exact(&header_bytes);
        const header = try @import("transport.zig").AssetHeader.decode(&header_bytes);
        const mime = try self.allocator.alloc(u8, header.mime_length);
        defer self.allocator.free(mime);
        try input.exact(mime);
        if (!std.unicode.utf8ValidateSlice(mime)) return error.InvalidMime;
        const bytes = try self.allocator.alloc(u8, @intCast(header.byte_length));
        defer self.allocator.free(bytes);
        try input.exact(bytes);
        self.guard.lock();
        defer self.guard.unlock();
        try self.store.put(header.hash, mime, bytes);
    }
};
