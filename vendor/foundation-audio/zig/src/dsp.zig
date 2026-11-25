const std = @import("std");
pub fn gain(db: f32) f32 {
    return if (std.math.isFinite(db)) std.math.pow(f32, 10, std.math.clamp(db, -60, 24) / 20) else 1;
}
pub fn crossfade(a: f32, b: f32, progress: f32) f32 {
    const t = std.math.clamp(progress, 0, 1);
    return a * @sqrt(1 - t) + b * @sqrt(t);
}
pub fn soundstage(frame: *[2]f32, alpha: f32, side_protect: f32, depth: f32) void {
    const mid = (frame[0] + frame[1]) * 0.5;
    const side = (frame[0] - frame[1]) * 0.5;
    const strength = std.math.clamp(mid * mid / (mid * mid + side * side + 1.0e-8), 0, 1) * std.math.clamp(@abs(mid) * 4, 0, 1);
    const a = std.math.clamp(alpha, 0, 1);
    const d = std.math.clamp(depth, 0, 1);
    const m = mid * (1 - std.math.clamp(a * strength * 0.92, 0, 0.92)) * (1 - 0.75 * d) * (1 - 0.35 * d);
    const s = side * (1 - std.math.clamp(a * strength * 0.25 * (1 - std.math.clamp(side_protect, 0, 1)), 0, 0.35)) * (1 - 0.75 * d) * (1 + 0.85 * d);
    frame.* = .{ m + s, m - s };
}
pub const Ramp = struct {
    value: f32 = 1,
    target: f32 = 1,
    remaining: u64 = 0,
    pub fn set(self: *Ramp, target: f32, frames: u64) void {
        self.target = target;
        self.remaining = frames;
        if (frames == 0) self.value = target;
    }
    pub fn next(self: *Ramp) f32 {
        if (self.remaining > 0) {
            self.value += (self.target - self.value) / @as(f32, @floatFromInt(self.remaining));
            self.remaining -= 1;
        }
        return self.value;
    }
};
pub const Silence = struct {
    allocator: std.mem.Allocator,
    threshold: f32,
    min_frames: usize,
    channels: usize,
    started: bool = false,
    collapsing: bool = false,
    pending: std.ArrayList(f32) = .empty,
    output: std.ArrayList(f32) = .empty,
    positions: std.ArrayList(u64) = .empty,
    input_frames: u64 = 0,
    output_frames: u64 = 0,
    pub fn init(allocator: std.mem.Allocator, threshold: f32, min_frames: usize, channels: usize) !Silence {
        if (channels == 0 or channels > 32) return error.InvalidChannels;
        return .{ .allocator = allocator, .threshold = @max(0, threshold), .min_frames = @max(1, min_frames), .channels = channels };
    }
    pub fn deinit(self: *Silence) void {
        self.pending.deinit(self.allocator);
        self.output.deinit(self.allocator);
        self.positions.deinit(self.allocator);
    }
    /// Runs on the decode worker, not the audio callback. Trailing silence is
    /// intentionally never flushed; short interior gaps keep their PCM samples.
    pub fn process(self: *Silence, input: []const f32) ![]const f32 {
        if (input.len % self.channels != 0) return error.PartialFrame;
        self.output.clearRetainingCapacity();
        self.positions.clearRetainingCapacity();
        var offset: usize = 0;
        while (offset < input.len) : (offset += self.channels) {
            const frame = input[offset..][0..self.channels];
            self.input_frames += 1;
            var silent = true;
            for (frame) |sample| {
                if (@abs(sample) >= self.threshold) {
                    silent = false;
                    break;
                }
            }
            if (silent) {
                if (!self.started or self.collapsing) continue;
                try self.pending.appendSlice(self.allocator, frame);
                if (self.pending.items.len / self.channels >= self.min_frames) {
                    self.pending.clearRetainingCapacity();
                    self.collapsing = true;
                }
            } else {
                self.started = true;
                self.collapsing = false;
                try self.output.appendSlice(self.allocator, self.pending.items);
                const pending_frames = self.pending.items.len / self.channels;
                for (0..pending_frames) |i| try self.positions.append(self.allocator, self.input_frames - pending_frames + i);
                self.pending.clearRetainingCapacity();
                try self.output.appendSlice(self.allocator, frame);
                try self.positions.append(self.allocator, self.input_frames);
            }
        }
        self.output_frames += self.output.items.len / self.channels;
        return self.output.items;
    }
};
test "silence filter preserves short gaps across decoder blocks" {
    var filter = try Silence.init(std.testing.allocator, 0.01, 4, 1);
    defer filter.deinit();
    try std.testing.expectEqualSlices(f32, &.{ 1, 1 }, try filter.process(&.{ 0, 0, 1, 1, 0 }));
    try std.testing.expectEqualSlices(u64, &.{ 3, 4 }, filter.positions.items);
    try std.testing.expectEqualSlices(f32, &.{ 0, 0, 1, 1 }, try filter.process(&.{ 0, 1, 1, 0, 0 }));
    try std.testing.expectEqualSlices(u64, &.{ 5, 6, 7, 8 }, filter.positions.items);
    try std.testing.expectEqualSlices(f32, &.{1}, try filter.process(&.{ 0, 0, 0, 1, 0 }));
}
test "equal power and fade endpoints" {
    try std.testing.expectApproxEqAbs(@as(f32, 1), crossfade(1, 0, 0), 0.00001);
    try std.testing.expectApproxEqAbs(@as(f32, 1), crossfade(0, 1, 1), 0.00001);
    var ramp: Ramp = .{};
    ramp.set(0, 4);
    for (0..4) |_| _ = ramp.next();
    try std.testing.expectEqual(@as(f32, 0), ramp.value);
}
