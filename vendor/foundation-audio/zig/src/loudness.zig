const std = @import("std");

/// BS.1770 K-weighting for the engine's fixed 48 kHz stereo analysis format.
/// 400 ms blocks, 100 ms hop; -70 LUFS absolute and -10 LU relative gates.
pub const Analyzer = struct {
    allocator: std.mem.Allocator,
    shelf: [2]Biquad = .{ .{}, .{} },
    highpass: [2]Biquad = .{ .{}, .{} },
    energies: [19200]f64 = @splat(0),
    cursor: usize = 0,
    frames: u64 = 0,
    sum: f64 = 0,
    blocks: std.ArrayList(f64) = .empty,

    const Biquad = struct {
        z1: f64 = 0,
        z2: f64 = 0,
        fn apply(self: *Biquad, value: f64, b: [3]f64, a: [2]f64) f64 {
            const out = b[0] * value + self.z1;
            self.z1 = b[1] * value - a[0] * out + self.z2;
            self.z2 = b[2] * value - a[1] * out;
            return out;
        }
    };
    pub fn init(allocator: std.mem.Allocator) Analyzer {
        return .{ .allocator = allocator };
    }
    pub fn deinit(self: *Analyzer) void {
        self.blocks.deinit(self.allocator);
    }
    pub fn add(self: *Analyzer, stereo: []const f32) !void {
        if (stereo.len % 2 != 0) return error.PartialFrame;
        var i: usize = 0;
        while (i < stereo.len) : (i += 2) {
            var energy: f64 = 0;
            for (0..2) |ch| {
                const sample: f64 = if (std.math.isFinite(stereo[i + ch])) stereo[i + ch] else 0;
                const shelf = self.shelf[ch].apply(sample, .{ 1.53512485958697, -2.69169618940638, 1.19839281085285 }, .{ -1.69065929318241, 0.73248077421585 });
                const filtered = self.highpass[ch].apply(shelf, .{ 1, -2, 1 }, .{ -1.99004745483398, 0.99007225036621 });
                energy += filtered * filtered;
            }
            self.sum += energy - self.energies[self.cursor];
            self.energies[self.cursor] = energy;
            self.cursor = (self.cursor + 1) % self.energies.len;
            self.frames += 1;
            if (self.frames >= 19200 and (self.frames - 19200) % 4800 == 0)
                try self.blocks.append(self.allocator, @max(0, self.sum / 19200));
        }
    }
    pub fn integrated(blocks: []const f64) ?f64 {
        const absolute = std.math.pow(f64, 10, (-70.0 + 0.691) / 10.0);
        var sum: f64 = 0;
        var count: usize = 0;
        for (blocks) |value| if (value >= absolute) {
            sum += value;
            count += 1;
        };
        if (count == 0) return null;
        const relative = sum / @as(f64, @floatFromInt(count)) * 0.1;
        sum = 0;
        count = 0;
        for (blocks) |value| if (value >= absolute and value >= relative) {
            sum += value;
            count += 1;
        };
        if (count == 0) return null;
        return -0.691 + 10 * @log10(sum / @as(f64, @floatFromInt(count)));
    }
    pub fn gain(blocks: []const f64) f32 {
        return if (integrated(blocks)) |lufs| @floatCast(std.math.clamp(-23 - lufs, -60, 24)) else std.math.nan(f32);
    }
};
test "loudness ignores silent blocks and jointly gates album energies" {
    try std.testing.expectEqual(@as(?f64, null), Analyzer.integrated(&.{ 0, 0 }));
    try std.testing.expectApproxEqAbs(@as(f64, -20.691), Analyzer.integrated(&.{ 0, 0.01, 0.01 }).?, 0.00001);
    try std.testing.expectApproxEqAbs(@as(f64, -0.691), Analyzer.integrated(&.{ 0.0001, 1, 1 }).?, 0.00001);
}
