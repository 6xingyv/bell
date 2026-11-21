const std = @import("std");
pub const Lock = struct {
    held: std.atomic.Value(bool) = .init(false),
    pub fn lock(self: *Lock) void {
        while (self.held.cmpxchgWeak(false, true, .acquire, .monotonic) != null) std.Thread.yield() catch {};
    }
    pub fn unlock(self: *Lock) void {
        self.held.store(false, .release);
    }
};
/// Registry IDs never expose pointers to Java. Removal prevents new borrowers;
/// existing borrowers keep the object alive until their final release.
pub fn Registry(comptime T: type) type {
    return struct {
        const Self = @This();
        const Entry = struct { value: T, refs: usize = 1 };
        allocator: std.mem.Allocator,
        guard: Lock = .{},
        next: u64 = 1,
        entries: std.AutoHashMapUnmanaged(u64, *Entry) = .empty,
        pub fn insert(self: *Self, value: T) !u64 {
            const entry = try self.allocator.create(Entry);
            errdefer self.allocator.destroy(entry);
            entry.* = .{ .value = value };
            self.guard.lock();
            defer self.guard.unlock();
            if (self.next == std.math.maxInt(i64)) return error.HandleExhausted;
            const id = self.next;
            self.next += 1;
            try self.entries.put(self.allocator, id, entry);
            return id;
        }
        pub fn acquire(self: *Self, id: u64) ?*T {
            self.guard.lock();
            defer self.guard.unlock();
            const entry = self.entries.get(id) orelse return null;
            entry.refs += 1;
            return &entry.value;
        }
        pub fn remove(self: *Self, id: u64) ?*T {
            self.guard.lock();
            defer self.guard.unlock();
            const entry = self.entries.fetchRemove(id) orelse return null;
            return &entry.value.value;
        }
        pub fn release(self: *Self, value: *T) void {
            const entry: *Entry = @fieldParentPtr("value", value);
            self.guard.lock();
            entry.refs -= 1;
            const last = entry.refs == 0;
            self.guard.unlock();
            if (last) {
                value.deinit();
                self.allocator.destroy(entry);
            }
        }
        pub fn deinit(self: *Self) void {
            std.debug.assert(self.entries.count() == 0);
            self.entries.deinit(self.allocator);
        }
    };
}
test "removed handles remain valid for outstanding borrowers" {
    const Value = struct {
        count: *usize,
        pub fn deinit(self: *@This()) void {
            self.count.* += 1;
        }
    };
    var count: usize = 0;
    var registry: Registry(Value) = .{ .allocator = std.testing.allocator };
    defer registry.deinit();
    const id = try registry.insert(.{ .count = &count });
    const borrowed = registry.acquire(id).?;
    const removed = registry.remove(id).?;
    registry.release(removed);
    try std.testing.expectEqual(@as(usize, 0), count);
    try std.testing.expect(registry.acquire(id) == null);
    registry.release(borrowed);
    try std.testing.expectEqual(@as(usize, 1), count);
}
