const std = @import("std");
const p = @import("protocol.zig");
const assets = @import("assets.zig");
const transport = @import("transport.zig");
const wire = @import("fabric.zig");
const c = @import("c.zig").api;
/// Inject the platform's Fabric SDK. JSON crosses this boundary, so the audio
/// library has no Rust/Cargo dependency on fabric-client or its executor.
pub const Control = struct {
    context: *anyopaque,
    send: *const fn (*anyopaque, []const u8, []const u8) anyerror!void,
    /// Return an owned JSON Ready/Reject, respecting the monotonic deadline.
    receive: *const fn (*anyopaque, std.mem.Allocator, u64) anyerror![]u8,
};
pub const Source = struct {
    allocator: std.mem.Allocator,
    control: Control,
    peers: []const []const u8,
    clock: p.Clock = .{},
    start_delay_ns: u64 = 400_000_000,
    min_buffer_ms: u32 = 80,
    max_uncertainty_ns: u64 = 5_000_000,
    single_device_fast_start: bool = true,
    store: *assets.Store,
    pub const Offer = struct {
        generation: u64,
        meta: p.TrackMeta,
        media: p.Media = .{},
        cover: ?[]const u8 = null,
        cover_mime: []const u8 = "image/jpeg",
        cover_url: ?[]const u8 = null,
        lyrics: ?[]const u8 = null,
    };
    fn send(self: *Source, peer: []const u8, value: anytype) !void {
        const bytes = try std.json.Stringify.valueAlloc(self.allocator, value, .{});
        defer self.allocator.free(bytes);
        try self.control.send(self.control.context, peer, bytes);
    }
    fn broadcast(self: *Source, value: anytype) !void {
        for (self.peers) |peer| try self.send(peer, value);
    }
    pub fn announce(self: *Source, offer: Offer, delivery: p.Delivery) !void {
        var meta = offer.meta;
        var cover_hash: ?assets.Hash = null;
        var lyrics_hash: ?assets.Hash = null;
        if (offer.cover) |bytes| if (offer.cover_url == null) {
            var hash: assets.Hash = undefined;
            std.crypto.hash.sha2.Sha256.hash(bytes, &hash, .{});
            try self.store.put(hash, offer.cover_mime, bytes);
            cover_hash = hash;
        };
        if (offer.lyrics orelse meta.lyrics) |lyrics| {
            const chars = try std.unicode.utf8CountCodepoints(lyrics);
            if (chars <= 8192) meta.lyrics = lyrics else {
                var hash: assets.Hash = undefined;
                std.crypto.hash.sha2.Sha256.hash(lyrics, &hash, .{});
                try self.store.put(hash, "text/plain; charset=utf-8", lyrics);
                lyrics_hash = hash;
                meta.lyrics = null;
            }
        }
        var media = offer.media;
        media.delivery = delivery;
        try self.broadcast(.{ .type = "track_announce", .generation = offer.generation, .meta = meta, .media = media, .cover_hash = cover_hash, .lyrics_hash = lyrics_hash, .cover_url = offer.cover_url });
    }
    pub fn fulfillAsset(self: *Source, peer: []const u8, hash: assets.Hash) !void {
        const blob = self.store.get(hash) orelse return error.UnknownAsset;
        const chunk_size = 24 * 1024;
        const total = if (blob.bytes.len <= 48 * 1024) 1 else (blob.bytes.len + chunk_size - 1) / chunk_size;
        for (0..total) |index| {
            const bytes = if (total == 1) blob.bytes else blob.bytes[index * chunk_size .. @min((index + 1) * chunk_size, blob.bytes.len)];
            const encoded = try self.allocator.alloc(u8, std.base64.standard.Encoder.calcSize(bytes.len));
            defer self.allocator.free(encoded);
            _ = std.base64.standard.Encoder.encode(encoded, bytes);
            if (total == 1) try self.send(peer, .{ .type = "asset_offer", .hash = hash, .mime = blob.mime, .bytes = encoded }) else try self.send(peer, .{ .type = "asset_chunk", .hash = hash, .mime = blob.mime, .index = index, .total = total, .data = encoded });
        }
    }
    pub fn synchronize(self: *Source, generation: u64, local_now_ns: u64, deadline_ns: u64) !i128 {
        if (self.clock.uncertainty_ns > self.max_uncertainty_ns) return error.UncertainClock;
        const activation = try self.clock.toGroup(local_now_ns +| self.start_delay_ns);
        try self.broadcast(.{ .type = "prepare_synced", .generation = generation, .activate_at_group_ns = activation, .start_at_local_ns = @as(?u64, null) });
        if (!(self.single_device_fast_start and self.peers.len <= 1)) {
            const ready = try self.allocator.alloc(bool, self.peers.len);
            defer self.allocator.free(ready);
            @memset(ready, false);
            var count: usize = 0;
            while (count < ready.len) {
                const bytes = self.control.receive(self.control.context, self.allocator, deadline_ns) catch |err| {
                    try self.abort(generation, @errorName(err));
                    return err;
                };
                defer self.allocator.free(bytes);
                var parsed = try std.json.parseFromSlice(std.json.Value, self.allocator, bytes, .{});
                defer parsed.deinit();
                const value = parsed.value;
                if ((wire.uint(wire.field(value, "generation")) orelse 0) != generation) continue;
                const peer = wire.text(wire.field(value, "participant_id")) orelse continue;
                const index = for (self.peers, 0..) |candidate, i| {
                    if (std.mem.eql(u8, candidate, peer)) break i;
                } else continue;
                const kind = wire.text(wire.field(value, "type")) orelse continue;
                if (std.mem.eql(u8, kind, "reject")) {
                    try self.abort(generation, "renderer rejected");
                    return error.Rejected;
                }
                if (!std.mem.eql(u8, kind, "ready") or ready[index]) continue;
                if ((wire.uint(wire.field(value, "uncertainty_ns")) orelse std.math.maxInt(u64)) > self.max_uncertainty_ns) {
                    try self.abort(generation, "clock uncertainty");
                    return error.UncertainClock;
                }
                if ((wire.uint(wire.field(value, "buffered_ms")) orelse 0) < self.min_buffer_ms) {
                    try self.abort(generation, "insufficient buffer");
                    return error.InsufficientBuffer;
                }
                ready[index] = true;
                count += 1;
            }
        }
        try self.broadcast(.{ .type = "commit", .generation = generation, .activate_at_group_ns = activation });
        return activation;
    }
    fn abort(self: *Source, generation: u64, reason: []const u8) !void {
        try self.broadcast(.{ .type = "abort", .generation = generation, .reason = reason });
    }
    pub fn command(self: *Source, kind: enum { play, pause, stop }, generation: u64) !void {
        try self.broadcast(.{ .type = @tagName(kind), .generation = generation });
    }
    pub fn seek(self: *Source, generation: u64, position_ms: u64) !void {
        try self.broadcast(.{ .type = "seek", .generation = generation, .position_ms = position_ms });
    }
    pub fn serveAsset(self: *Source, peer: []const u8, hash: assets.Hash, fd: c_int, epoch: *const std.atomic.Value(u32), expected: u32) !void {
        const blob = self.store.get(hash) orelse return error.UnknownAsset;
        try self.send(peer, .{ .type = "asset_stream_announce", .hash = hash, .mime = blob.mime, .byte_length = blob.bytes.len });
        var header: [42]u8 = undefined;
        @memcpy(header[0..32], &hash);
        std.mem.writeInt(u16, header[32..34], @intCast(blob.mime.len), .big);
        std.mem.writeInt(u64, header[34..42], blob.bytes.len, .big);
        try write(fd, &header, epoch, expected);
        try write(fd, blob.mime, epoch, expected);
        try write(fd, blob.bytes, epoch, expected);
    }
};
fn write(fd: c_int, bytes: []const u8, epoch: *const std.atomic.Value(u32), expected: u32) !void {
    var offset: usize = 0;
    while (offset < bytes.len) {
        const n = c.fa_write_fd(fd, bytes[offset..].ptr, @intCast(@min(bytes.len - offset, std.math.maxInt(c_int))), &epoch.raw, expected);
        if (n <= 0) return error.WriteFailed;
        offset += @intCast(n);
    }
}
/// Borrowed descriptors; bounded compressed-byte fanout, never PCM or whole-file buffering.
pub fn pushMedia(fd: c_int, sinks: []const c_int, header: transport.MediaHeader, epoch: *const std.atomic.Value(u32), expected: u32) !u64 {
    if (sinks.len == 0) return error.NoReceivers;
    const encoded = header.encode();
    for (sinks) |sink| try write(sink, &encoded, epoch, expected);
    var input: @import("stream.zig").Input = .{ .fd = fd, .epoch = epoch, .expected = expected, .remaining = if (header.byte_length == 0) null else header.byte_length };
    var buffer: [65536]u8 = undefined;
    var count: u64 = 0;
    while (true) {
        const n = try input.read(&buffer);
        if (n == 0) break;
        for (sinks) |sink| try write(sink, buffer[0..n], epoch, expected);
        count += n;
    }
    if (header.byte_length != 0 and count != header.byte_length) return error.Truncated;
    return count;
}
