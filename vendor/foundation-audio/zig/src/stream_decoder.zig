const std = @import("std");
const decoder = @import("decoder.zig");
const c = decoder.c;
const Ring = @import("ring.zig").Ring;
/// Progressive demux may block waiting for a complete packet. Isolate it from
/// engine commands so pause/settings/metadata/seek handover remain responsive.
pub const Worker = struct {
    allocator: std.mem.Allocator,
    source: decoder.Decoder,
    pcm: Ring(f32),
    done: std.atomic.Value(bool) = .init(false),
    failed: std.atomic.Value(bool) = .init(false),
    thread: ?std.Thread = null,
    pub fn create(allocator: std.mem.Allocator, source: decoder.Decoder) !*Worker {
        const self = try allocator.create(Worker);
        errdefer allocator.destroy(self);
        self.* = .{ .allocator = allocator, .source = source, .pcm = try Ring(f32).init(allocator, 32768) };
        errdefer self.pcm.deinit(allocator);
        self.thread = try std.Thread.spawn(.{}, run, .{self});
        return self;
    }
    pub fn deinit(self: *Worker) void {
        // Engine invalidates the stream epoch before joining this thread.
        self.thread.?.join();
        self.source.deinit();
        self.pcm.deinit(self.allocator);
        self.allocator.destroy(self);
    }
    fn run(self: *Worker) void {
        defer self.done.store(true, .release);
        while (!self.source.input.?.cancelled()) {
            const samples = self.source.read() catch {
                self.failed.store(true, .release);
                return;
            };
            if (samples.len == 0) return;
            var offset: usize = 0;
            while (offset < samples.len and !self.source.input.?.cancelled()) {
                offset += self.pcm.push(samples[offset..]);
                if (offset < samples.len) c.fa_sleep_ms(1);
            }
        }
    }
};
