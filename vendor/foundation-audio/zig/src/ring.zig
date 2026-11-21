const std = @import("std");

/// Single producer/single consumer. Storage is allocated once, never in render.
pub fn Ring(comptime T: type) type {
    return struct {
        const Self = @This();
        data: []T,
        read: std.atomic.Value(usize) = .init(0),
        write: std.atomic.Value(usize) = .init(0),
        pub fn init(allocator: std.mem.Allocator, capacity: usize) !Self {
            if (capacity < 2 or !std.math.isPowerOfTwo(capacity)) return error.InvalidCapacity;
            return .{ .data = try allocator.alloc(T, capacity) };
        }
        pub fn deinit(self: *Self, allocator: std.mem.Allocator) void {
            allocator.free(self.data);
        }
        pub fn available(self: *const Self) usize {
            const read = self.read.load(.acquire);
            return @min(self.data.len, self.write.load(.acquire) -% read);
        }
        pub fn push(self: *Self, input: []const T) usize {
            const write = self.write.load(.monotonic);
            const count = @min(input.len, self.data.len - (write -% self.read.load(.acquire)));
            for (input[0..count], 0..) |value, i| self.data[(write +% i) & (self.data.len - 1)] = value;
            self.write.store(write +% count, .release);
            return count;
        }
        pub fn pop(self: *Self, output: []T) usize {
            const read = self.read.load(.monotonic);
            const count = @min(output.len, self.write.load(.acquire) -% read);
            for (output[0..count], 0..) |*value, i| value.* = self.data[(read +% i) & (self.data.len - 1)];
            self.read.store(read +% count, .release);
            return count;
        }
        /// Only when both producer and consumer are stopped.
        pub fn reset(self: *Self) void {
            self.read.store(0, .monotonic);
            self.write.store(0, .monotonic);
        }
    };
}

test "ring wraps and never overwrites unread PCM" {
    var ring = try Ring(f32).init(std.testing.allocator, 4);
    defer ring.deinit(std.testing.allocator);
    try std.testing.expectEqual(@as(usize, 4), ring.push(&.{ 1, 2, 3, 4, 5 }));
    var out: [3]f32 = undefined;
    try std.testing.expectEqual(@as(usize, 3), ring.pop(&out));
    try std.testing.expectEqualSlices(f32, &.{ 1, 2, 3 }, &out);
    try std.testing.expectEqual(@as(usize, 2), ring.push(&.{ 5, 6 }));
    try std.testing.expectEqual(@as(usize, 3), ring.pop(&out));
    try std.testing.expectEqualSlices(f32, &.{ 4, 5, 6 }, &out);
}
