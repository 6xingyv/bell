const std = @import("std");
pub const ability = "com.mocharealm.foundation.audio.render";
pub const TrackMeta = struct {
    title: ?[]const u8 = null,
    artist: ?[]const u8 = null,
    album: ?[]const u8 = null,
    album_artist: ?[]const u8 = null,
    genre: ?[]const u8 = null,
    year: ?i32 = null,
    track_number: ?i32 = null,
    disc_number: ?i32 = null,
    lyrics: ?[]const u8 = null,
    duration_ms: u64 = 0,
    sample_rate: u32 = 0,
    channels: u16 = 0,
    bit_rate: u32 = 0,
    replay_gain_db: ?f32 = null,
};
pub const AudioParameters = struct {
    container: ?[]const u8 = null,
    codec_id: u32 = 0,
    codec_name: ?[]const u8 = null,
    sample_rate: u32 = 0,
    channels: u16 = 0,
    channel_layout: u64 = 0,
    sample_format: i32 = 0,
    bit_rate: i64 = 0,
    bits_per_coded_sample: u32 = 0,
    block_align: u32 = 0,
    frame_size: u32 = 0,
    extradata: []const u8 = &.{},
    time_base_num: u32 = 0,
    time_base_den: u32 = 0,
};
pub const Delivery = enum { per_peer_unicast, relay_tree, multicast };
pub const Media = struct {
    container_hint: ?[]const u8 = null,
    codec_hint: ?[]const u8 = null,
    byte_length: ?u64 = null,
    content_hash: ?[32]u8 = null,
    delivery: Delivery = .per_peer_unicast,
    decode: ?AudioParameters = null,
    start_position_ms: u64 = 0,
};
pub const PlanInput = struct {
    renderer_count: usize,
    media_bytes: u64 = 0,
    cover_bytes: u64 = 0,
    lyrics_bytes: u64 = 0,
    cover_cache_hits: usize = 0,
    lyrics_cache_hits: usize = 0,
    allow_relay: bool = false,
    allow_multicast: bool = false,
    preferred: Delivery = .per_peer_unicast,
};
pub const Plan = struct { policy: Delivery, media_stream_count: usize, source_bytes: u64, medium_bytes: u64 };
pub fn plan(input: PlanInput) Plan {
    const n = @max(1, input.renderer_count);
    const policy: Delivery = switch (input.preferred) {
        .multicast => if (input.allow_multicast) .multicast else if (input.allow_relay) .relay_tree else .per_peer_unicast,
        .relay_tree => if (input.allow_multicast and n >= 3) .multicast else if (input.allow_relay) .relay_tree else .per_peer_unicast,
        .per_peer_unicast => if (n >= 3 and input.allow_multicast) .multicast else if (n >= 3 and input.allow_relay) .relay_tree else .per_peer_unicast,
    };
    const assets = input.cover_bytes *| (n -| input.cover_cache_hits) +| input.lyrics_bytes *| (n -| input.lyrics_cache_hits);
    return .{ .policy = policy, .media_stream_count = if (policy == .per_peer_unicast) n else 1, .source_bytes = input.media_bytes *| (if (policy == .per_peer_unicast) n else 1) +| assets, .medium_bytes = input.media_bytes *| (switch (policy) {
        .per_peer_unicast => n,
        .relay_tree => n - 1,
        .multicast => 1,
    }) +| assets };
}
/// Mapping is supplied by Fabric SessionClock. This library does not perform
/// clock estimation or create another networking stack.
pub const Clock = struct {
    offset_ns: i128 = 0,
    uncertainty_ns: u64 = 0,
    valid: bool = true,
    pub fn toLocal(self: Clock, group: i128) !u64 {
        if (!self.valid) return error.StaleClock;
        const local = std.math.sub(i128, group, self.offset_ns) catch return error.InvalidClock;
        return std.math.cast(u64, local) orelse error.InvalidClock;
    }
    pub fn toGroup(self: Clock, local: u64) !i128 {
        if (!self.valid) return error.StaleClock;
        return std.math.add(i128, local, self.offset_ns) catch error.InvalidClock;
    }
};
test "delivery respects capabilities, cache hits and saturates estimates" {
    const p = plan(.{ .renderer_count = 3, .media_bytes = 1000, .cover_bytes = 100, .cover_cache_hits = 2, .allow_multicast = true });
    try std.testing.expectEqual(Delivery.multicast, p.policy);
    try std.testing.expectEqual(@as(u64, 1100), p.source_bytes);
    const fallback = plan(.{ .renderer_count = 3, .media_bytes = std.math.maxInt(u64), .preferred = .multicast });
    try std.testing.expectEqual(Delivery.per_peer_unicast, fallback.policy);
    try std.testing.expectEqual(std.math.maxInt(u64), fallback.source_bytes);
}
test "group clock round trips and rejects stale or negative instants" {
    const clock: Clock = .{ .offset_ns = -100 };
    try std.testing.expectEqual(@as(u64, 42), try clock.toLocal(try clock.toGroup(42)));
    try std.testing.expectError(error.InvalidClock, (Clock{}).toLocal(-1));
    try std.testing.expectError(error.StaleClock, (Clock{ .valid = false }).toLocal(0));
}
