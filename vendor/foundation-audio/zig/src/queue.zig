const std = @import("std");
pub const Mode = enum(u8) { once, list_loop, single_loop, shuffle };
pub const Item = union(enum) { path: [:0]const u8, fd: c_int };
pub const Entry = struct { item: Item, gain_db: ?f32 = null, gain_override: bool = false };

/// Queue owns path strings. Descriptor ownership belongs to the engine.
pub const Queue = struct {
    allocator: std.mem.Allocator,
    entries: std.ArrayList(Entry) = .empty,
    selected: ?usize = null,
    order: std.ArrayList(usize) = .empty,
    cursor: usize = 0,
    random: std.Random.DefaultPrng = .init(0xc01dcafe),

    pub fn init(allocator: std.mem.Allocator) Queue {
        return .{ .allocator = allocator };
    }
    pub fn deinit(self: *Queue) void {
        for (self.entries.items) |entry| switch (entry.item) {
            .path => |path| self.allocator.free(path),
            .fd => {},
        };
        self.entries.deinit(self.allocator);
        self.order.deinit(self.allocator);
        self.* = undefined;
    }
    pub fn addPath(self: *Queue, path: []const u8) !void {
        const owned = try self.allocator.dupeSentinel(u8, path, 0);
        errdefer self.allocator.free(owned);
        try self.add(.{ .path = owned });
    }
    pub fn add(self: *Queue, item: Item) !void {
        try self.entries.append(self.allocator, .{ .item = item });
        self.order.clearRetainingCapacity();
    }
    pub fn select(self: *Queue, index: usize) bool {
        if (index >= self.entries.items.len) return false;
        self.selected = index;
        self.order.clearRetainingCapacity();
        return true;
    }
    pub fn move(self: *Queue, from: usize, to: usize) bool {
        if (from >= self.entries.items.len or to >= self.entries.items.len) return false;
        if (from == to) return true;
        const entry = self.entries.orderedRemove(from);
        self.entries.insertAssumeCapacity(to, entry);
        if (self.selected) |current| {
            self.selected = if (current == from) to else if (from < current and current <= to) current - 1 else if (to <= current and current < from) current + 1 else current;
        }
        self.order.clearRetainingCapacity();
        return true;
    }
    pub fn setGain(self: *Queue, index: usize, gain: f32) bool {
        if (index >= self.entries.items.len) return false;
        self.entries.items[index].gain_override = true;
        self.entries.items[index].gain_db = if (std.math.isFinite(gain)) std.math.clamp(gain, -60, 24) else null;
        return true;
    }
    fn shuffle(self: *Queue) !void {
        self.order.clearRetainingCapacity();
        if (self.selected) |index| try self.order.append(self.allocator, index);
        for (0..self.entries.items.len) |index| {
            if (self.selected == null or index != self.selected.?) try self.order.append(self.allocator, index);
        }
        self.random.random().shuffle(usize, self.order.items[if (self.selected != null) @as(usize, 1) else 0..]);
        self.cursor = 0;
    }
    pub fn peek(self: *Queue, mode: Mode) !?usize {
        const n = self.entries.items.len;
        if (n == 0) return null;
        if (mode == .shuffle) {
            if (self.order.items.len != n) try self.shuffle();
            if (self.selected == null) return self.order.items[0];
            if (n == 1) return self.selected;
            if (self.cursor + 1 == n) try self.shuffle();
            return self.order.items[self.cursor + 1];
        }
        const current = self.selected orelse return 0;
        return switch (mode) {
            .once => if (current + 1 < n) current + 1 else null,
            .list_loop => (current + 1) % n,
            .single_loop => current,
            .shuffle => unreachable,
        };
    }
    pub fn next(self: *Queue, mode: Mode) !?usize {
        const index = (try self.peek(mode)) orelse return null;
        self.commit(index, mode);
        return index;
    }
    pub fn previous(self: *Queue, mode: Mode) !?usize {
        const n = self.entries.items.len;
        if (n == 0) return null;
        const current = self.selected orelse {
            self.commit(0, mode);
            return 0;
        };
        const index: usize = switch (mode) {
            .once => if (current > 0) current - 1 else return null,
            .single_loop => current,
            .list_loop => (current + n - 1) % n,
            .shuffle => blk: {
                if (self.order.items.len != n) try self.shuffle();
                break :blk self.order.items[(self.cursor + n - 1) % n];
            },
        };
        self.commit(index, mode);
        return index;
    }
    pub fn commit(self: *Queue, index: usize, mode: Mode) void {
        self.selected = index;
        if (mode == .shuffle) {
            for (self.order.items, 0..) |item, cursor| {
                if (item == index) {
                    self.cursor = cursor;
                    break;
                }
            }
        } else self.order.clearRetainingCapacity();
    }
};

test "queue boundaries, move identity and shuffle peek stability" {
    var q = Queue.init(std.testing.allocator);
    defer q.deinit();
    for (0..4) |_| try q.addPath("track");
    try std.testing.expect(q.select(0));
    try std.testing.expectEqual(@as(?usize, null), try q.previous(.once));
    try std.testing.expectEqual(@as(?usize, 3), try q.previous(.list_loop));
    try std.testing.expect(q.setGain(3, -6));
    try std.testing.expect(q.move(3, 1));
    try std.testing.expectEqual(@as(?usize, 1), q.selected);
    try std.testing.expectEqual(@as(?f32, -6), q.entries.items[1].gain_db);
    for (0..20) |_| {
        const old = q.selected;
        const peek = try q.peek(.shuffle);
        try std.testing.expectEqual(old, q.selected);
        try std.testing.expectEqual(peek, try q.peek(.shuffle));
        try std.testing.expectEqual(peek, try q.next(.shuffle));
        try std.testing.expect(old != q.selected);
    }
}
