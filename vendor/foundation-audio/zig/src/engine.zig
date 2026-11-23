const std = @import("std");
const decoder = @import("decoder.zig");
const c = decoder.c;
const dsp = @import("dsp.zig");
const queue = @import("queue.zig");
const Ring = @import("ring.zig").Ring;
const Frame = struct { left: f32, right: f32, position: u64, duration: u64, generation: u64, index: i32 };
pub const Rate = 48000;
pub const State = enum(u32) { stopped, playing, paused, buffering };
pub const Status = extern struct { position_ms: u64, duration_ms: u64, paused: u32, speed: f32, generation: u64 };
pub const Request = struct {
    kind: enum { play_path, play_fd, play_stream, rebase, add_path, add_fd, clear, select, move, seek, next, previous, stop, mode, crossfade, soundstage, gain_enabled, gain, silence, metadata, shutdown },
    path: [:0]const u8 = "",
    a: i64 = 0,
    b: i64 = 0,
    value: f32 = 0,
    success: bool = false,
    bytes: ?[]u8 = null,
    epoch: u32 = 0,
    expected_generation: u64 = 0,
    duration_ms: u64 = 0,
    start_ms: u64 = 0,
    stream_gain_db: ?f32 = null,
    done: std.atomic.Value(bool) = .init(false),
};

/// The worker owns the queue, decoders and DSP. Render owns only its ramp and
/// consumes fixed-size PCM records. Neither locks nor allocations occur there.
pub const Engine = struct {
    allocator: std.mem.Allocator,
    output: Ring(Frame),
    device: ?*anyopaque = null,
    thread: ?std.Thread = null,
    request: std.atomic.Value(?*Request) = .init(null),
    api_busy: std.atomic.Value(bool) = .init(false),
    stream_epoch: std.atomic.Value(u32) = .init(0),
    resume_at: @import("atomic.zig").U64 = .init(0),
    alive: bool = true,
    paused: std.atomic.Value(bool) = .init(false),
    finished: std.atomic.Value(bool) = .init(true),
    state: std.atomic.Value(u32) = .init(@backingInt(State.stopped)),
    position: @import("atomic.zig").U64 = .init(0),
    duration: @import("atomic.zig").U64 = .init(0),
    generation: @import("atomic.zig").U64 = .init(0),
    index: std.atomic.Value(i32) = .init(-1),
    underruns: @import("atomic.zig").U64 = .init(0),
    rms: std.atomic.Value(u32) = .init(0),
    spectrum: @import("spectrum.zig").OutputSpectrum = .{},
    spectrum_generation: u64 = 0,
    spectrum_active: bool = false,
    last_error: std.atomic.Value(u32) = .init(0),
    queue: queue.Queue,
    mode: queue.Mode = .once,
    current: ?decoder.Decoder = null,
    stream_worker: ?*@import("stream_decoder.zig").Worker = null,
    stream_duration: u64 = 0,
    stream_gain: f32 = 1,
    stream_buffer: [1024]f32 = undefined,
    follow: ?decoder.Decoder = null,
    follow_index: ?usize = null,
    pcm: []const f32 = &.{},
    pcm_offset: usize = 0,
    follow_pcm: []const f32 = &.{},
    follow_offset: usize = 0,
    decoded_frames: u64 = 0,
    follow_frames: u64 = 0,
    sequence: u64 = 0,
    crossfade_ms: u32 = 5000,
    soundstage: bool = false,
    gain_enabled: bool = false,
    current_gain: f32 = 1,
    follow_gain: f32 = 1,
    silence_enabled: bool = false,
    silence_threshold: f32 = 0.0031622777,
    silence_ms: u32 = 500,
    silence: ?dsp.Silence = null,
    silence_base: u64 = 0,
    ramp: dsp.Ramp = .{},
    render_paused: bool = false,
    metadata: []u8 = &.{},
    previous_metadata: []u8 = &.{},

    pub fn create(allocator: std.mem.Allocator, headless: bool) !*Engine {
        const self = try allocator.create(Engine);
        errdefer allocator.destroy(self);
        self.* = .{ .allocator = allocator, .output = try Ring(Frame).init(allocator, 16384), .queue = queue.Queue.init(allocator) };
        self.queue.random = .init(c.fa_monotonic_ns());
        errdefer self.output.deinit(allocator);
        if (!headless) self.device = c.fa_device_open(renderCallback, self, Rate) orelse return error.AudioDeviceUnavailable;
        errdefer c.fa_device_close(self.device);
        self.thread = try std.Thread.spawn(.{}, worker, .{self});
        return self;
    }
    pub fn destroy(self: *Engine) void {
        var request: Request = .{ .kind = .shutdown };
        _ = self.submit(&request);
        self.thread.?.join();
        c.fa_device_close(self.device);
        self.releaseQueue();
        self.queue.deinit();
        self.output.deinit(self.allocator);
        self.allocator.free(self.metadata);
        self.allocator.free(self.previous_metadata);
        const allocator = self.allocator;
        allocator.destroy(self);
    }
    pub fn submit(self: *Engine, request: *Request) bool {
        request.epoch = switch (request.kind) {
            .play_path, .play_fd, .play_stream, .stop, .shutdown, .select, .next, .previous => self.stream_epoch.fetchAdd(1, .acq_rel) +% 1,
            else => self.stream_epoch.load(.acquire),
        };
        while (self.api_busy.cmpxchgWeak(false, true, .acquire, .monotonic) != null) c.fa_sleep_ms(1);
        defer self.api_busy.store(false, .release);
        self.request.store(request, .release);
        while (!request.done.load(.acquire)) c.fa_sleep_ms(1);
        return request.success;
    }
    pub fn setPaused(self: *Engine, paused: bool) void {
        self.resume_at.store(0, .release);
        self.paused.store(paused, .release);
    }
    pub fn scheduleResume(self: *Engine, deadline: u64) void {
        self.resume_at.store(deadline, .release);
    }
    pub fn status(self: *const Engine) Status {
        return .{ .position_ms = self.position.load(.acquire), .duration_ms = self.duration.load(.acquire), .paused = @intFromBool(self.paused.load(.acquire)), .speed = 1, .generation = self.generation.load(.acquire) };
    }
    pub fn presentedSpectrum(self: *const Engine) @import("spectrum.zig").Power {
        if (self.state.load(.acquire) == 0 or self.paused.load(.acquire)) return .{};
        const delay_ns = c.fa_device_output_delay_ns(self.device);
        if (delay_ns < 0) return self.spectrum.read();
        // Approximate one display frame of readback/render lead. A backend timestamp
        // anchors this to the device queue rather than the decoder's media position.
        const lag_ms: u64 = @intCast(@max(0, @divTrunc(delay_ns, 1000000) - 16));
        const now = c.fa_monotonic_ns() / 1000000;
        return self.spectrum.readAt(@truncate(now -| lag_ms));
    }
    fn releaseQueue(self: *Engine) void {
        for (self.queue.entries.items) |entry| switch (entry.item) {
            .fd => |fd| decoder.close(fd),
            .path => {},
        };
    }
    fn clearQueue(self: *Engine) void {
        self.releaseQueue();
        self.queue.deinit();
        self.queue = queue.Queue.init(self.allocator);
    }
    fn closeDecoders(self: *Engine) void {
        if (self.stream_worker) |stream| stream.deinit();
        self.stream_worker = null;
        if (self.current) |*current| current.deinit();
        if (self.follow) |*follow| follow.deinit();
        if (self.silence) |*silence| silence.deinit();
        self.current = null;
        self.follow = null;
        self.silence = null;
        self.pcm = &.{};
        self.follow_pcm = &.{};
        self.follow_index = null;
        self.pcm_offset = 0;
        self.follow_offset = 0;
    }
    fn stop(self: *Engine) void {
        self.resume_at.store(0, .release);
        c.fa_device_stop(self.device);
        self.closeDecoders();
        self.output.reset();
        self.finished.store(true, .release);
        self.state.store(@backingInt(State.stopped), .release);
        self.position.store(0, .release);
        self.duration.store(0, .release);
    }
    fn open(self: *Engine, entry: queue.Entry) !decoder.Decoder {
        return switch (entry.item) {
            .path => |path| decoder.Decoder.openPath(self.allocator, path, Rate, 2),
            .fd => |fd| decoder.Decoder.openFd(self.allocator, fd, Rate, 2),
        };
    }
    fn entryGain(self: *Engine, entry: queue.Entry, source: *decoder.Decoder) f32 {
        if (!self.gain_enabled) return 1;
        const db = if (entry.gain_override) entry.gain_db else if (source.tag("replaygain_track_gain")) |value| std.fmt.parseFloat(f32, std.mem.trim(u8, std.mem.trimEnd(u8, value, "dB"), " ")) catch null else null;
        return dsp.gain(db orelse 0);
    }
    fn start(self: *Engine, index: usize) !void {
        if (index >= self.queue.entries.items.len) return error.InvalidIndex;
        // Open before disturbing an existing track; a broken queue item must
        // not destroy the current playback session.
        var next = try self.open(self.queue.entries.items[index]);
        const metadata = @import("metadata.zig").wire(self.allocator, &next) catch |err| {
            next.deinit();
            return err;
        };
        self.install(next, metadata, false, 0) catch |err| return err;
        self.queue.commit(index, self.mode);
        self.index.store(@intCast(index), .release);
        self.current_gain = self.entryGain(self.queue.entries.items[index], &self.current.?);
    }
    fn install(self: *Engine, next: decoder.Decoder, metadata: []u8, paused: bool, position_ms: u64) !void {
        self.stop();
        self.allocator.free(self.previous_metadata);
        self.previous_metadata = &.{};
        self.allocator.free(self.metadata);
        self.metadata = metadata;
        self.current_gain = 1;
        self.current = next;
        self.decoded_frames = position_ms * Rate / 1000;
        self.sequence +%= 1;
        self.index.store(-1, .release);
        self.position.store(position_ms, .release);
        self.generation.store(self.sequence, .release);
        self.duration.store(next.duration_ms, .release);
        self.paused.store(paused, .release);
        self.render_paused = paused;
        self.ramp = .{ .value = if (paused) 0 else 1, .target = if (paused) 0 else 1 };
        try self.resetSilence();
        self.finished.store(false, .release);
        self.state.store(@backingInt(if (paused) State.paused else State.buffering), .release);
        if (self.device) |device| if (c.fa_device_start(device) != 0) {
            self.stop();
            return error.AudioDeviceUnavailable;
        };
    }
    fn handle(self: *Engine, r: *Request) !void {
        switch (r.kind) {
            .play_stream => {
                if (r.start_ms > 1_000_000_000_000 or r.duration_ms > 1_000_000_000_000) return error.InvalidTimeline;
                // Release an old blocked network decoder before opening the
                // replacement. submit() already invalidated its epoch.
                self.stop();
                var next = try decoder.Decoder.openStream(self.allocator, @intCast(r.a), &self.stream_epoch, r.epoch, if (r.path.len == 0) null else r.path);
                var owned = true;
                errdefer if (owned) next.deinit();
                const header = next.stream_header.?;
                if (header.start_position_ms > 1_000_000_000_000) return error.InvalidTimeline;
                if (r.expected_generation != 0 and header.generation != 0 and header.generation != r.expected_generation) return error.StaleGeneration;
                if (r.duration_ms != 0) next.duration_ms = r.duration_ms;
                const position_ms = if (header.start_position_ms != 0) header.start_position_ms else r.start_ms;
                if (header.start_position_ms == 0 and r.start_ms > 0) next.skip_frames = r.start_ms * Rate / 1000;
                const meta = if (r.bytes) |bytes| try self.allocator.dupe(u8, bytes) else try @import("metadata.zig").wire(self.allocator, &next);
                var own_meta = true;
                errdefer if (own_meta) self.allocator.free(meta);
                self.clearQueue();
                self.stream_worker = try @import("stream_decoder.zig").Worker.create(self.allocator, next);
                owned = false;
                self.allocator.free(self.metadata);
                self.metadata = meta;
                own_meta = false;
                self.stream_duration = next.duration_ms;
                self.stream_gain = dsp.gain(r.stream_gain_db orelse 0);
                self.decoded_frames = position_ms * Rate / 1000;
                self.sequence +%= 1;
                r.start_ms = position_ms;
                try self.resetSilence();
                self.position.store(position_ms, .release);
                self.duration.store(next.duration_ms, .release);
                self.generation.store(self.sequence, .release);
                self.index.store(-1, .release);
                self.paused.store(r.b != 0, .release);
                self.render_paused = r.b != 0;
                self.ramp = .{ .value = if (r.b != 0) 0 else 1, .target = if (r.b != 0) 0 else 1 };
                self.finished.store(false, .release);
                self.state.store(@backingInt(if (r.b != 0) State.paused else State.buffering), .release);
                if (self.device) |device| if (c.fa_device_start(device) != 0) {
                    _ = self.stream_epoch.fetchAdd(1, .acq_rel);
                    self.stop();
                    return error.AudioDeviceUnavailable;
                };
            },
            .play_path, .play_fd => {
                var replacement = queue.Queue.init(self.allocator);
                var owns_replacement = true;
                errdefer if (owns_replacement) replacement.deinit();
                if (r.kind == .play_path) try replacement.addPath(r.path) else {
                    const fd = try decoder.duplicate(@intCast(r.a));
                    errdefer decoder.close(fd);
                    try replacement.add(.{ .fd = fd });
                }
                self.stop();
                self.clearQueue();
                self.queue.deinit();
                self.queue = replacement;
                owns_replacement = false;
                try self.start(0);
            },
            .add_path => try self.queue.addPath(r.path),
            .add_fd => {
                const fd = try decoder.duplicate(@intCast(r.a));
                errdefer decoder.close(fd);
                try self.queue.add(.{ .fd = fd });
            },
            .clear => {
                c.fa_device_stop(self.device);
                defer {
                    if (self.device) |device| _ = c.fa_device_start(device);
                }
                self.discardFollow();
                self.clearQueue();
                self.index.store(-1, .release);
                const read = self.output.read.load(.acquire);
                for (0..self.output.available()) |i| self.output.data[(read +% i) & (self.output.data.len - 1)].index = -1;
            },
            .select => {
                if (r.a < 0) return error.InvalidIndex;
                try self.start(@intCast(r.a));
            },
            .move => {
                if (r.a < 0 or r.b < 0 or !self.queue.move(@intCast(r.a), @intCast(r.b))) return error.InvalidIndex;
                self.discardFollow();
                c.fa_device_stop(self.device);
                defer {
                    if (self.device) |device| _ = c.fa_device_start(device);
                }
                const read = self.output.read.load(.acquire);
                for (0..self.output.available()) |i| {
                    const frame = &self.output.data[(read +% i) & (self.output.data.len - 1)];
                    frame.index = movedIndex(frame.index, @intCast(r.a), @intCast(r.b));
                }
                self.index.store(movedIndex(self.index.load(.acquire), @intCast(r.a), @intCast(r.b)), .release);
            },
            .next, .previous => {
                const old = self.queue.selected;
                const next = if (r.kind == .next) try self.queue.next(self.mode) else try self.queue.previous(self.mode);
                self.queue.selected = old;
                try self.start(next orelse return error.EndOfQueue);
            },
            .seek => {
                if (r.a < 0 or self.current == null) return error.InvalidSeek;
                c.fa_device_stop(self.device);
                defer {
                    if (self.device) |device| _ = c.fa_device_start(device);
                }
                const ms: u64 = if (self.current.?.duration_ms > 0) @min(@as(u64, @intCast(r.a)), self.current.?.duration_ms) else @intCast(r.a);
                try self.current.?.seek(ms);
                self.output.reset();
                self.pcm = &.{};
                self.pcm_offset = 0;
                if (self.follow) |*follow| follow.deinit();
                self.follow = null;
                self.follow_index = null;
                self.decoded_frames = ms * Rate / 1000;
                self.position.store(ms, .release);
                try self.resetSilence();
                self.finished.store(false, .release);
            },
            .rebase => {
                if (r.a < 0 or self.stream_worker == null) return error.NotStream;
                c.fa_device_stop(self.device);
                defer {
                    if (self.device) |device| _ = c.fa_device_start(device);
                }
                self.output.reset();
                self.pcm = &.{};
                self.pcm_offset = 0;
                var discard: [1024]f32 = undefined;
                var remaining = self.stream_worker.?.pcm.available();
                while (remaining > 0) {
                    const n = self.stream_worker.?.pcm.pop(discard[0..@min(remaining, discard.len)]);
                    if (n == 0) break;
                    remaining -= n;
                }
                self.decoded_frames = @as(u64, @intCast(r.a)) * Rate / 1000;
                self.position.store(@intCast(r.a), .release);
                try self.resetSilence();
            },
            .stop => self.stop(),
            .mode => {
                if (r.a < 0 or r.a > 3) return error.InvalidMode;
                self.mode = @fromBackingInt(@intCast(r.a));
                self.discardFollow();
            },
            .crossfade => {
                if (r.a < 0 or r.a > 60000) return error.InvalidDuration;
                self.crossfade_ms = @intCast(r.a);
            },
            .soundstage => self.soundstage = r.a != 0,
            .gain_enabled => {
                self.gain_enabled = r.a != 0;
                self.refreshGain();
            },
            .gain => {
                if (r.a < 0 or !self.queue.setGain(@intCast(r.a), r.value)) return error.InvalidIndex;
                self.refreshGain();
            },
            .silence => {
                if (!std.math.isFinite(r.value) or r.b < 0 or r.b > 60000) return error.InvalidSilence;
                self.silence_enabled = r.a != 0;
                self.silence_threshold = std.math.pow(f32, 10, std.math.clamp(r.value, -120, 0) / 20);
                self.silence_ms = @intCast(r.b);
            },
            .metadata => r.bytes = try self.allocator.dupe(u8, if (self.generation.load(.acquire) != self.sequence and self.previous_metadata.len != 0) self.previous_metadata else self.metadata),
            .shutdown => {
                self.stop();
                self.alive = false;
            },
        }
    }
    fn movedIndex(index: i32, from: i32, to: i32) i32 {
        if (index == from) return to;
        if (from < to and index > from and index <= to) return index - 1;
        if (from > to and index >= to and index < from) return index + 1;
        return index;
    }
    fn resetSilence(self: *Engine) !void {
        if (self.silence) |*filter| filter.deinit();
        self.silence = null;
        self.silence_base = self.decoded_frames;
        if (self.silence_enabled) self.silence = try dsp.Silence.init(self.allocator, self.silence_threshold, @as(usize, self.silence_ms) * Rate / 1000, 2);
    }
    fn discardFollow(self: *Engine) void {
        if (self.follow) |*follow| follow.deinit();
        self.follow = null;
        self.follow_index = null;
        self.follow_pcm = &.{};
        self.follow_offset = 0;
    }
    fn refreshGain(self: *Engine) void {
        if (self.queue.selected) |index| if (self.current) |*current| {
            self.current_gain = self.entryGain(self.queue.entries.items[index], current);
        };
        if (self.follow_index) |index| if (self.follow) |*follow| {
            self.follow_gain = self.entryGain(self.queue.entries.items[index], follow);
        };
    }
    fn worker(self: *Engine) void {
        while (self.alive) {
            if (self.request.swap(null, .acquire)) |r| {
                self.handle(r) catch |err| {
                    self.last_error.store(@intFromError(err), .release);
                    r.done.store(true, .release);
                    continue;
                };
                r.success = true;
                r.done.store(true, .release);
            }
            if (!self.alive) break;
            if ((self.current == null and self.stream_worker == null) or self.finished.load(.acquire) or self.output.available() >= self.output.data.len - 512) {
                c.fa_sleep_ms(1);
                continue;
            }
            if (self.stream_worker != null) {
                self.pumpStream() catch |err| {
                    self.last_error.store(@intFromError(err), .release);
                    _ = self.stream_epoch.fetchAdd(1, .acq_rel);
                    self.closeDecoders();
                    self.finished.store(true, .release);
                };
                continue;
            }
            self.pump() catch |err| {
                self.last_error.store(@intFromError(err), .release);
                self.finished.store(true, .release);
                self.closeDecoders();
            };
        }
    }
    fn pumpStream(self: *Engine) !void {
        const stream = self.stream_worker.?;
        if (self.pcm_offset >= self.pcm.len) {
            const count = stream.pcm.pop(&self.stream_buffer);
            if (count == 0) {
                if (stream.done.load(.acquire) and stream.pcm.available() == 0) {
                    if (stream.failed.load(.acquire)) self.last_error.store(@intFromError(error.StreamDecodeFailed), .release);
                    self.finished.store(true, .release);
                    stream.deinit();
                    self.stream_worker = null;
                } else c.fa_sleep_ms(1);
                return;
            }
            self.pcm = if (self.silence) |*filter| try filter.process(self.stream_buffer[0..count]) else self.stream_buffer[0..count];
            self.pcm_offset = 0;
        }
        const count = @min(512, (self.pcm.len - self.pcm_offset) / 2);
        var records: [512]Frame = undefined;
        const gain = if (self.gain_enabled) self.stream_gain else 1;
        for (0..count) |i| {
            self.decoded_frames = if (self.silence) |*filter| self.silence_base + filter.positions.items[self.pcm_offset / 2] else self.decoded_frames + 1;
            records[i] = .{ .left = self.pcm[self.pcm_offset] * gain, .right = self.pcm[self.pcm_offset + 1] * gain, .position = self.decoded_frames * 1000 / Rate, .duration = self.stream_duration, .generation = self.sequence, .index = -1 };
            self.pcm_offset += 2;
        }
        _ = self.output.push(records[0..count]);
    }
    fn pump(self: *Engine) !void {
        var records: [512]Frame = undefined;
        var count: usize = 0;
        var decode_blocks: usize = 0;
        while (count < records.len and self.current != null) {
            if (self.pcm_offset >= self.pcm.len) {
                if (decode_blocks == 4) break;
                decode_blocks += 1;
                self.pcm = try self.current.?.read();
                self.pcm_offset = 0;
                if (self.pcm.len == 0) {
                    // Keep at most the audible track and its successor alive.
                    // This also bounds presentation metadata retained for the
                    // buffered tail of very short tracks.
                    if (self.generation.load(.acquire) != self.sequence) break;
                    if (self.follow != null) {
                        self.promote();
                        continue;
                    }
                    const next = (if (self.current.?.input == null) try self.queue.peek(self.mode) else null) orelse {
                        self.finished.store(true, .release);
                        break;
                    };
                    self.follow = try self.open(self.queue.entries.items[next]);
                    self.follow_index = next;
                    self.follow_frames = 0;
                    self.follow_offset = 0;
                    self.follow_pcm = &.{};
                    self.follow_gain = self.entryGain(self.queue.entries.items[next], &self.follow.?);
                    self.promote();
                    continue;
                }
                if (self.silence) |*silence| {
                    self.pcm = try silence.process(self.pcm);
                    if (self.pcm.len == 0) continue;
                }
            }
            var left = self.pcm[self.pcm_offset] * self.current_gain;
            var right = self.pcm[self.pcm_offset + 1] * self.current_gain;
            const end_frame = self.current.?.duration_ms * Rate / 1000;
            const fade_frames = @min(@as(u64, self.crossfade_ms) * Rate / 1000, end_frame / 2);
            if (!self.silence_enabled and fade_frames > 0 and end_frame > 0 and self.decoded_frames >= end_frame - fade_frames) {
                if (self.follow == null and self.follow_index == null) {
                    if (try self.queue.peek(self.mode)) |next| {
                        self.follow_index = next;
                        self.follow = self.open(self.queue.entries.items[next]) catch null;
                        if (self.follow != null) {
                            self.follow_index = next;
                            self.follow_frames = 0;
                            self.follow_offset = 0;
                            self.follow_pcm = &.{};
                            self.follow_gain = self.entryGain(self.queue.entries.items[next], &self.follow.?);
                        }
                    }
                }
                if (self.follow) |*follow| {
                    if (self.follow_offset >= self.follow_pcm.len) {
                        self.follow_pcm = try follow.read();
                        self.follow_offset = 0;
                    }
                    if (self.follow_offset + 1 < self.follow_pcm.len) {
                        const t = @as(f32, @floatFromInt(self.follow_frames)) / @as(f32, @floatFromInt(fade_frames));
                        var lead = [2]f32{ left, right };
                        var incoming = [2]f32{ self.follow_pcm[self.follow_offset] * self.follow_gain, self.follow_pcm[self.follow_offset + 1] * self.follow_gain };
                        if (self.soundstage) {
                            dsp.soundstage(&lead, t, 0.55, t);
                            dsp.soundstage(&incoming, 0, 0.55, 1 - t);
                        }
                        left = dsp.crossfade(lead[0], incoming[0], t);
                        right = dsp.crossfade(lead[1], incoming[1], t);
                        self.follow_offset += 2;
                        self.follow_frames += 1;
                    }
                }
            }
            self.decoded_frames = if (self.silence) |*silence| self.silence_base + silence.positions.items[self.pcm_offset / 2] else self.decoded_frames + 1;
            self.pcm_offset += 2;
            records[count] = .{ .left = left, .right = right, .position = self.decoded_frames * 1000 / Rate, .duration = self.current.?.duration_ms, .generation = self.sequence, .index = if (self.queue.selected) |index| @intCast(index) else -1 };
            count += 1;
        }
        _ = self.output.push(records[0..count]);
    }
    fn promote(self: *Engine) void {
        self.current.?.deinit();
        self.current = self.follow;
        self.follow = null;
        self.queue.commit(self.follow_index.?, self.mode);
        self.follow_index = null;
        self.current_gain = self.follow_gain;
        const meta = @import("metadata.zig").wire(self.allocator, &self.current.?) catch null;
        if (meta) |bytes| {
            self.allocator.free(self.previous_metadata);
            self.previous_metadata = self.metadata;
            self.metadata = bytes;
        }
        self.pcm = self.follow_pcm;
        self.pcm_offset = self.follow_offset;
        self.decoded_frames = self.follow_frames;
        self.sequence +%= 1;
        self.resetSilence() catch {};
        self.follow_pcm = &.{};
        self.follow_offset = 0;
    }
    fn renderCallback(context: ?*anyopaque, samples: [*c]f32, frames: u32) callconv(.c) void {
        const self: *Engine = @ptrCast(@alignCast(context.?));
        self.render(samples[0 .. @as(usize, frames) * 2]);
    }
    pub fn render(self: *Engine, samples: []f32) void {
        self.spectrum.clock_ms = @truncate(c.fa_monotonic_ns() / 1000000);
        @memset(samples, 0);
        const deadline = self.resume_at.load(.acquire);
        if (deadline != 0 and c.fa_monotonic_ns() >= deadline) {
            self.resume_at.store(0, .release);
            self.paused.store(false, .release);
        }
        const paused = self.paused.load(.acquire);
        if (paused != self.render_paused) {
            self.ramp.set(if (paused) 0 else 1, Rate / 10);
            self.render_paused = paused;
        }
        const spectrum_active = !paused and self.output.available() > 0;
        if (spectrum_active != self.spectrum_active) {
            self.spectrum.reset();
            self.spectrum_active = spectrum_active;
        }
        var energy: f64 = 0;
        var missing = false;
        var i: usize = 0;
        while (i + 1 < samples.len) : (i += 2) {
            defer if (spectrum_active) self.spectrum.consume(samples[i], samples[i + 1]);
            const volume = self.ramp.next();
            if (paused and volume == 0) continue;
            var frame: [1]Frame = undefined;
            if (self.output.pop(&frame) == 0) {
                missing = true;
                continue;
            }
            if (frame[0].generation != self.spectrum_generation) {
                self.spectrum.reset();
                self.spectrum_generation = frame[0].generation;
            }
            samples[i] = frame[0].left * volume;
            samples[i + 1] = frame[0].right * volume;
            energy += samples[i] * samples[i] + samples[i + 1] * samples[i + 1];
            self.position.store(frame[0].position, .release);
            self.duration.store(frame[0].duration, .release);
            self.generation.store(frame[0].generation, .release);
            self.index.store(frame[0].index, .release);
        }
        self.rms.store(@bitCast(@as(f32, @floatCast(@sqrt(energy / @as(f64, @floatFromInt(@max(samples.len, 1))))))), .release);
        if (missing and !self.finished.load(.acquire)) _ = self.underruns.fetchAdd(1, .monotonic);
        const state: State = if (paused) .paused else if (self.finished.load(.acquire) and self.output.available() == 0) .stopped else if (missing) .buffering else .playing;
        self.state.store(@backingInt(state), .release);
    }
};
