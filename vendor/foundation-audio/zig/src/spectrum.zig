const std = @import("std");

/// Read-only power of the 30–300, 300–3500 and >3500 Hz output bands.
/// The audio callback owns all filter/window state. Readers only load atomic snapshots.
pub const Power = extern struct { low: f32 = 0, mid: f32 = 0, high: f32 = 0, sequence: u32 = 0 };
pub const OutputSpectrum = struct {
    const Slot = struct {
        sequence: std.atomic.Value(u32) = .init(0),
        at: std.atomic.Value(u32) = .init(0),
        values: [3]std.atomic.Value(u32) = .{ .init(0), .init(0), .init(0) },
    };
    const rate: f32 = 48000;
    const dc = @exp(-2.0 * std.math.pi * 30.0 / rate);
    const bass = 1.0 - @exp(-2.0 * std.math.pi * 300.0 / rate);
    const treble = 1.0 - @exp(-2.0 * std.math.pi * 3500.0 / rate);
    history: [128]Slot = @splat(.{}),
    reset_version: std.atomic.Value(u32) = .init(0),
    clock_ms: u32 = 0,
    previous: [2]f32 = .{ 0, 0 },
    dc_state: [2]f32 = .{ 0, 0 },
    bass_state: [2]f32 = .{ 0, 0 },
    treble_state: [2]f32 = .{ 0, 0 },
    sum: [3]f64 = .{ 0, 0, 0 },
    frames: u32 = 0,
    version: std.atomic.Value(u32) = .init(0),
    power: [3]std.atomic.Value(u32) = .{ .init(0), .init(0), .init(0) },

    pub fn reset(self: *OutputSpectrum) void {
        self.previous = .{ 0, 0 };
        self.dc_state = .{ 0, 0 };
        self.bass_state = .{ 0, 0 };
        self.treble_state = .{ 0, 0 };
        self.sum = .{ 0, 0, 0 };
        self.frames = 0;
        self.reset_version.store(self.version.load(.seq_cst) +% 2, .seq_cst);
        self.publish(.{ 0, 0, 0 });
    }
    pub fn consume(self: *OutputSpectrum, left: f32, right: f32) void {
        // Separate channel energies preserve opposite-phase stereo. No PCM is modified.
        for ([_]f32{ left, right }, 0..) |sample, channel| {
            const input = if (std.math.isFinite(sample)) sample else 0;
            const high = dc * (self.dc_state[channel] + input - self.previous[channel]);
            self.previous[channel] = input;
            self.dc_state[channel] = high;
            self.bass_state[channel] += bass * (high - self.bass_state[channel]);
            self.treble_state[channel] += treble * (high - self.treble_state[channel]);
            const bands = [_]f32{ self.bass_state[channel], self.treble_state[channel] - self.bass_state[channel], high - self.treble_state[channel] };
            for (bands, 0..) |value, band| self.sum[band] += @as(f64, value) * value;
        }
        self.frames += 1;
        if (self.frames == 960) {
            var result: [3]f32 = undefined;
            for (0..3) |band| result[band] = @floatCast(self.sum[band] / 1920.0);
            self.publish(result);
            self.sum = .{ 0, 0, 0 };
            self.frames = 0;
        }
    }
    fn publish(self: *OutputSpectrum, values: [3]f32) void {
        _ = self.version.fetchAdd(1, .seq_cst);
        for (values, 0..) |value, band| self.power[band].store(@bitCast(value), .seq_cst);
        _ = self.version.fetchAdd(1, .seq_cst);
        const sequence = self.version.load(.seq_cst);
        const slot = &self.history[(sequence / 2) % 128];
        slot.sequence.store(sequence | 1, .seq_cst);
        slot.at.store(self.clock_ms, .seq_cst);
        for (values, 0..) |value, band| slot.values[band].store(@bitCast(value), .seq_cst);
        slot.sequence.store(sequence, .seq_cst);
    }
    /// Select history by audible time; never wait for the realtime writer.
    pub fn readAt(self: *const OutputSpectrum, target_ms: u32) Power {
        const newest = self.version.load(.seq_cst) & ~@as(u32, 1);
        const cutoff = self.reset_version.load(.seq_cst);
        for (0..128) |age| {
            const sequence = newest -% (@as(u32, @intCast(age)) * 2);
            if (sequence == 0 or @as(i32, @bitCast(sequence -% cutoff)) < 0) break;
            const slot = &self.history[(sequence / 2) % 128];
            if (slot.sequence.load(.seq_cst) != sequence) continue;
            const at = slot.at.load(.seq_cst);
            const result: Power = .{ .low = @bitCast(slot.values[0].load(.seq_cst)), .mid = @bitCast(slot.values[1].load(.seq_cst)), .high = @bitCast(slot.values[2].load(.seq_cst)), .sequence = sequence };
            if (slot.sequence.load(.seq_cst) != sequence) continue;
            if (@as(i32, @bitCast(target_ms -% at)) >= 0) return result;
        }
        return .{};
    }
    pub fn read(self: *const OutputSpectrum) Power {
        // Bounded retries: a UI/analysis reader never waits on the realtime callback.
        for (0..3) |_| {
            const before = self.version.load(.seq_cst);
            if (before & 1 != 0) continue;
            const result: Power = .{ .low = @bitCast(self.power[0].load(.seq_cst)), .mid = @bitCast(self.power[1].load(.seq_cst)), .high = @bitCast(self.power[2].load(.seq_cst)), .sequence = before };
            if (before == self.version.load(.seq_cst)) return result;
        }
        return .{};
    }
};

fn sine(hz: f32, opposite: bool) Power {
    var spectrum: OutputSpectrum = .{};
    for (0..9600) |frame| {
        const sample = @sin(2.0 * std.math.pi * hz * @as(f32, @floatFromInt(frame)) / 48000.0) * 0.25;
        spectrum.consume(sample, if (opposite) -sample else sample);
    }
    return spectrum.read();
}
test "output frequency bands discriminate bass vocals and treble" {
    const low = sine(80, false);
    const mid = sine(1000, false);
    const high = sine(8000, false);
    try std.testing.expect(low.low > low.mid * 3 and low.low > low.high * 3);
    try std.testing.expect(mid.mid > mid.low * 2 and mid.mid > mid.high * 2);
    try std.testing.expect(high.high > high.low * 3 and high.high > high.mid * 2);
}
test "opposite phase stereo does not cancel spectrum" {
    try std.testing.expectApproxEqAbs(sine(80, false).low, sine(80, true).low, 0.00001);
}
test "silence reset and invalid samples do not retain stale energy" {
    var spectrum: OutputSpectrum = .{};
    for (0..1920) |_| spectrum.consume(0.2, -0.2);
    spectrum.reset();
    try std.testing.expectEqual(@as(f32, 0), spectrum.read().low);
    for (0..9600) |_| spectrum.consume(0, std.math.nan(f32));
    const quiet = spectrum.read();
    try std.testing.expect(quiet.low == 0 and quiet.mid == 0 and quiet.high == 0);
}
test "presentation history selects past windows and discards pre-seek samples" {
    var spectrum: OutputSpectrum = .{};
    spectrum.clock_ms = 100;
    spectrum.publish(.{ 0.1, 0.2, 0.3 });
    spectrum.clock_ms = 120;
    spectrum.publish(.{ 0.4, 0.5, 0.6 });
    try std.testing.expectEqual(@as(f32, 0.1), spectrum.readAt(110).low);
    try std.testing.expectEqual(@as(f32, 0.4), spectrum.readAt(125).low);
    spectrum.reset();
    try std.testing.expectEqual(@as(u32, 0), spectrum.readAt(110).sequence);
}
