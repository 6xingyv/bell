const std = @import("std");
pub const MediaHeader = struct {
    generation: u64,
    flags: u32,
    byte_length: u64,
    start_position_ms: u64,
    pub fn decode(bytes: *const [28]u8) MediaHeader {
        return .{ .generation = std.mem.readInt(u64, bytes[0..8], .big), .flags = std.mem.readInt(u32, bytes[8..12], .big), .byte_length = std.mem.readInt(u64, bytes[12..20], .big), .start_position_ms = std.mem.readInt(u64, bytes[20..28], .big) };
    }
    pub fn encode(self: MediaHeader) [28]u8 {
        var bytes: [28]u8 = undefined;
        std.mem.writeInt(u64, bytes[0..8], self.generation, .big);
        std.mem.writeInt(u32, bytes[8..12], self.flags, .big);
        std.mem.writeInt(u64, bytes[12..20], self.byte_length, .big);
        std.mem.writeInt(u64, bytes[20..28], self.start_position_ms, .big);
        return bytes;
    }
};
pub const max_asset_bytes = 16 * 1024 * 1024;
pub const AssetHeader = struct {
    hash: [32]u8,
    mime_length: u16,
    byte_length: u64,
    pub fn decode(bytes: *const [42]u8) !AssetHeader {
        const result: AssetHeader = .{ .hash = bytes[0..32].*, .mime_length = std.mem.readInt(u16, bytes[32..34], .big), .byte_length = std.mem.readInt(u64, bytes[34..42], .big) };
        if (result.byte_length > max_asset_bytes or result.mime_length > 4096) return error.AssetTooLarge;
        return result;
    }
};
pub fn verify(hash: [32]u8, bytes: []const u8) bool {
    var computed: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(bytes, &computed, .{});
    return std.mem.eql(u8, &hash, &computed);
}
test "media framing is network order and preserves handover position" {
    const header: MediaHeader = .{ .generation = 0x0102030405060708, .flags = 1, .byte_length = 999, .start_position_ms = 123456 };
    const encoded = header.encode();
    try std.testing.expectEqual(@as(u8, 1), encoded[0]);
    try std.testing.expectEqualDeep(header, MediaHeader.decode(&encoded));
}
test "asset framing rejects oversized allocations and verifies contents" {
    var bytes: [42]u8 = @splat(0);
    std.mem.writeInt(u64, bytes[34..42], max_asset_bytes + 1, .big);
    try std.testing.expectError(error.AssetTooLarge, AssetHeader.decode(&bytes));
    var hash: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash("asset", &hash, .{});
    try std.testing.expect(verify(hash, "asset"));
    try std.testing.expect(!verify(hash, "other"));
}
