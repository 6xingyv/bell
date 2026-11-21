const std = @import("std");
const transport = @import("transport.zig");
pub const Hash = [32]u8;
pub const Blob = struct { mime: []u8, bytes: []u8 };
pub const Store = struct {
    allocator: std.mem.Allocator,
    cache: std.AutoArrayHashMapUnmanaged(Hash, Blob) = .empty,
    chunks: std.AutoHashMapUnmanaged(Hash, Assembly) = .empty,
    bytes: usize = 0,
    const Assembly = struct {
        mime: []u8,
        parts: []?[]u8,
        received: usize = 0,
        bytes: usize = 0,
        fn deinit(self: *Assembly, a: std.mem.Allocator) void {
            for (self.parts) |part| if (part) |data| a.free(data);
            a.free(self.parts);
            a.free(self.mime);
        }
    };
    pub fn deinit(self: *Store) void {
        for (self.cache.values()) |blob| {
            self.allocator.free(blob.mime);
            self.allocator.free(blob.bytes);
        }
        self.cache.deinit(self.allocator);
        var values = self.chunks.valueIterator();
        while (values.next()) |value| value.deinit(self.allocator);
        self.chunks.deinit(self.allocator);
    }
    pub fn get(self: *const Store, hash: Hash) ?Blob {
        return self.cache.get(hash);
    }
    pub fn put(self: *Store, hash: Hash, mime: []const u8, bytes: []const u8) !void {
        if (bytes.len > transport.max_asset_bytes or mime.len > 4096) return error.AssetTooLarge;
        if (!transport.verify(hash, bytes)) return error.HashMismatch;
        if (self.cache.contains(hash)) return;
        const owned_mime = try self.allocator.dupe(u8, mime);
        errdefer self.allocator.free(owned_mime);
        const owned_bytes = try self.allocator.dupe(u8, bytes);
        errdefer self.allocator.free(owned_bytes);
        while (self.cache.count() >= 16 or self.bytes + bytes.len > 64 * 1024 * 1024) {
            const first = self.cache.values()[0];
            self.bytes -= first.bytes.len;
            self.allocator.free(first.mime);
            self.allocator.free(first.bytes);
            self.cache.orderedRemoveAt(0);
        }
        try self.cache.put(self.allocator, hash, .{ .mime = owned_mime, .bytes = owned_bytes });
        self.bytes += bytes.len;
    }
    pub fn offer(self: *Store, hash: Hash, mime: []const u8, encoded: []const u8) !void {
        const bytes = try self.decode(encoded);
        defer self.allocator.free(bytes);
        try self.put(hash, mime, bytes);
    }
    fn decode(self: *Store, encoded: []const u8) ![]u8 {
        const codec = std.base64.standard.Decoder;
        const count = try codec.calcSizeForSlice(encoded);
        if (count > transport.max_asset_bytes) return error.AssetTooLarge;
        const bytes = try self.allocator.alloc(u8, count);
        errdefer self.allocator.free(bytes);
        try codec.decode(bytes, encoded);
        return bytes;
    }
    pub fn chunk(self: *Store, hash: Hash, mime: []const u8, index: usize, total: usize, encoded: []const u8) !void {
        if (total == 0 or total > 4096 or index >= total or mime.len > 4096) return error.InvalidChunk;
        if (!self.chunks.contains(hash)) {
            if (self.chunks.count() >= 4) return error.TooManyAssemblies;
            const parts = try self.allocator.alloc(?[]u8, total);
            errdefer self.allocator.free(parts);
            @memset(parts, null);
            const owned_mime = try self.allocator.dupe(u8, mime);
            errdefer self.allocator.free(owned_mime);
            try self.chunks.put(self.allocator, hash, .{ .mime = owned_mime, .parts = parts });
        }
        const assembly = self.chunks.getPtr(hash).?;
        if (assembly.parts.len != total or !std.mem.eql(u8, assembly.mime, mime)) return error.ConflictingChunk;
        const decoded = try self.decode(encoded);
        var owned = true;
        defer if (owned) self.allocator.free(decoded);
        if (assembly.parts[index]) |old| {
            if (!std.mem.eql(u8, old, decoded)) return error.ConflictingChunk;
            return;
        }
        if (decoded.len + assembly.bytes > transport.max_asset_bytes) return error.AssetTooLarge;
        assembly.parts[index] = decoded;
        owned = false;
        assembly.bytes += decoded.len;
        assembly.received += 1;
        if (assembly.received == total) {
            var complete = self.chunks.fetchRemove(hash).?.value;
            defer complete.deinit(self.allocator);
            const bytes = try self.allocator.alloc(u8, complete.bytes);
            defer self.allocator.free(bytes);
            var offset: usize = 0;
            for (complete.parts) |part| {
                @memcpy(bytes[offset..][0..part.?.len], part.?);
                offset += part.?.len;
            }
            try self.put(hash, mime, bytes);
        }
    }
};
test "assets assemble out of order, reject conflict and verify SHA256" {
    var store: Store = .{ .allocator = std.testing.allocator };
    defer store.deinit();
    var hash: Hash = undefined;
    std.crypto.hash.sha2.Sha256.hash("abcdef", &hash, .{});
    try store.chunk(hash, "text/plain", 1, 2, "ZGVm");
    try store.chunk(hash, "text/plain", 1, 2, "ZGVm");
    try std.testing.expectError(error.ConflictingChunk, store.chunk(hash, "text/plain", 1, 2, "YWJj"));
    try store.chunk(hash, "text/plain", 0, 2, "YWJj");
    try std.testing.expectEqualStrings("abcdef", store.get(hash).?.bytes);
    try std.testing.expectError(error.HashMismatch, store.put(hash, "text/plain", "wrong"));
}
