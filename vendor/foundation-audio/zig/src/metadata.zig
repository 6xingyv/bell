const std = @import("std");
const Decoder = @import("decoder.zig").Decoder;
const c = @import("decoder.zig").c;
pub const magic: u32 = 0x3144464d;
pub fn fromJson(allocator: std.mem.Allocator, value: std.json.Value, cover: []const u8) ![]u8 {
    if (value != .object or cover.len > 16 * 1024 * 1024) return error.InvalidMetadata;
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    try number(&output, allocator, magic);
    try number(&output, allocator, 0);
    var count: u32 = 0;
    const keys = [_][]const u8{ "title", "artist", "album", "album_artist", "genre", "lyrics", "year", "track_number", "disc_number", "duration_ms", "replay_gain_db" };
    const tags = [_][]const u8{ "title", "artist", "album", "album_artist", "genre", "lyrics", "date", "track", "disc", "duration", "replaygain_track_gain" };
    for (keys, tags) |key, name| {
        const field = value.object.get(key) orelse continue;
        if (field == .null) continue;
        var buffer: [96]u8 = undefined;
        const text: []const u8 = if (std.mem.eql(u8, key, "duration_ms")) blk: {
            const ms: u64 = if (field == .integer and field.integer >= 0) @intCast(field.integer) else 0;
            break :blk try std.fmt.bufPrint(&buffer, "{d}.{d:0>3}", .{ ms / 1000, ms % 1000 });
        } else switch (field) {
            .string => |s| s,
            .integer => |n| try std.fmt.bufPrint(&buffer, "{d}", .{n}),
            .float => |n| try std.fmt.bufPrint(&buffer, "{d}", .{n}),
            else => continue,
        };
        try string(&output, allocator, name);
        try string(&output, allocator, text);
        count += 1;
    }
    std.mem.writeInt(u32, output.items[4..8], count, .little);
    try number(&output, allocator, @intCast(cover.len));
    try output.appendSlice(allocator, cover);
    return output.toOwnedSlice(allocator);
}
fn number(output: *std.ArrayList(u8), allocator: std.mem.Allocator, value: u32) !void {
    var bytes: [4]u8 = undefined;
    std.mem.writeInt(u32, &bytes, value, .little);
    try output.appendSlice(allocator, &bytes);
}
fn string(output: *std.ArrayList(u8), allocator: std.mem.Allocator, value: []const u8) !void {
    if (value.len > std.math.maxInt(u32)) return error.MetadataTooLarge;
    try number(output, allocator, @intCast(value.len));
    try output.appendSlice(allocator, value);
}
pub fn wire(allocator: std.mem.Allocator, source: *Decoder) ![]u8 {
    var tags: std.StringHashMap([]const u8) = .init(allocator);
    defer {
        var keys = tags.keyIterator();
        while (keys.next()) |key| allocator.free(key.*);
        tags.deinit();
    }
    const stream = source.format.?.streams[@intCast(source.stream_index)];
    for ([_]?*c.AVDictionary{ stream.*.metadata, source.format.?.metadata }) |dict| {
        var entry: [*c]c.AVDictionaryEntry = null;
        while (true) {
            entry = c.av_dict_get(dict, "", entry, c.AV_DICT_IGNORE_SUFFIX);
            if (entry == null) break;
            const key = try std.ascii.allocLowerString(allocator, std.mem.span(entry.*.key));
            if (std.mem.eql(u8, key, "duration")) {
                allocator.free(key);
                continue;
            }
            const result = tags.getOrPut(key) catch |err| {
                allocator.free(key);
                return err;
            };
            if (result.found_existing) allocator.free(key);
            result.value_ptr.* = std.mem.span(entry.*.value);
        }
    }
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    try number(&output, allocator, magic);
    try number(&output, allocator, @intCast(tags.count() + 1));
    var entries = tags.iterator();
    while (entries.next()) |entry| {
        try string(&output, allocator, entry.key_ptr.*);
        try string(&output, allocator, entry.value_ptr.*);
    }
    try string(&output, allocator, "duration");
    var duration: [64]u8 = undefined;
    try string(&output, allocator, try std.fmt.bufPrint(&duration, "{d}.{d:0>3}", .{ source.duration_ms / 1000, source.duration_ms % 1000 }));
    const cover = source.cover() orelse &.{};
    if (cover.len > 16 * 1024 * 1024) return error.CoverTooLarge;
    try number(&output, allocator, @intCast(cover.len));
    try output.appendSlice(allocator, cover);
    return output.toOwnedSlice(allocator);
}
