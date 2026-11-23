const std = @import("std");
const decoder = @import("decoder.zig");
const c = decoder.c;
/// Owned descriptor and cancel token kept at a stable address for AVIO.
pub const Input = struct {
    fd: c_int,
    epoch: *const std.atomic.Value(u32),
    expected: u32,
    remaining: ?u64 = null,
    allocator: ?std.mem.Allocator = null,
    cache: std.ArrayList(u8) = .empty,
    replay_offset: usize = 0,
    recording: bool = false,
    rewindable: bool = true,
    pub fn cancelled(self: *const Input) bool {
        return self.epoch.load(.acquire) != self.expected;
    }
    pub fn read(self: *Input, bytes: []u8) !usize {
        if (self.cancelled()) return error.Cancelled;
        if (self.replay_offset < self.cache.items.len) {
            const n = @min(bytes.len, self.cache.items.len - self.replay_offset);
            @memcpy(bytes[0..n], self.cache.items[self.replay_offset..][0..n]);
            self.replay_offset += n;
            if (!self.recording and self.replay_offset == self.cache.items.len) self.clearCache();
            return n;
        }
        const count = @min(bytes.len, self.remaining orelse std.math.maxInt(u64), std.math.maxInt(c_int));
        if (count == 0) return 0;
        const result = c.fa_read_fd(self.fd, bytes.ptr, @intCast(count), &self.epoch.raw, self.expected);
        if (result < 0) return if (self.cancelled()) error.Cancelled else error.ReadFailed;
        if (self.remaining) |*left| left.* -= @intCast(result);
        if (self.recording) {
            if (self.cache.items.len + @as(usize, @intCast(result)) <= 32 * 1024 * 1024) {
                try self.cache.appendSlice(self.allocator.?, bytes[0..@intCast(result)]);
                self.replay_offset = self.cache.items.len;
            } else self.rewindable = false;
        }
        return @intCast(result);
    }
    fn clearCache(self: *Input) void {
        if (self.allocator) |a| self.cache.deinit(a);
        self.cache = .empty;
        self.replay_offset = 0;
    }
    pub fn deinit(self: *Input) void {
        self.clearCache();
    }
    pub fn rewind(self: *Input) bool {
        if (!self.rewindable) return false;
        self.replay_offset = 0;
        return true;
    }
    pub fn finishProbe(self: *Input) void {
        self.recording = false;
        if (self.replay_offset == self.cache.items.len) self.clearCache();
    }
    pub fn exact(self: *Input, bytes: []u8) !void {
        var offset: usize = 0;
        while (offset < bytes.len) {
            const count = try self.read(bytes[offset..]);
            if (count == 0) return error.Truncated;
            offset += count;
        }
    }
    pub fn packet(context: ?*anyopaque, bytes: [*c]u8, size: c_int) callconv(.c) c_int {
        const self: *Input = @ptrCast(@alignCast(context.?));
        const count = self.read(bytes[0..@intCast(size)]) catch return c.AVERROR_EXIT;
        return if (count == 0) c.AVERROR_EOF else @intCast(count);
    }
};
