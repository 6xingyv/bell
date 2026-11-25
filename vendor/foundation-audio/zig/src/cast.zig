const std = @import("std");
const decoder = @import("decoder.zig");
const c = decoder.c;
const U64 = @import("atomic.zig").U64;
pub const Cast = struct {
    allocator: std.mem.Allocator,
    source: decoder.Decoder,
    sink: c_int,
    start_ms: u64,
    epoch: std.atomic.Value(u32) = .init(0),
    seek_ms: U64 = .init(std.math.maxInt(u64)),
    done: std.atomic.Value(bool) = .init(false),
    success: bool = false,
    thread: ?std.Thread = null,
    pub fn open(allocator: std.mem.Allocator, source_fd: c_int, sink_fd: c_int, start_ms: u64) !Cast {
        var source = try decoder.Decoder.openFd(allocator, source_fd, 48000, 2);
        errdefer source.deinit();
        const sink = try decoder.duplicate(sink_fd);
        return .{ .allocator = allocator, .source = source, .sink = sink, .start_ms = start_ms };
    }
    pub fn start(self: *Cast) !void {
        self.thread = try std.Thread.spawn(.{}, run, .{self});
    }
    pub fn cancel(self: *Cast) void {
        self.epoch.store(1, .release);
    }
    pub fn seek(self: *Cast, ms: u64) bool {
        if (ms > std.math.maxInt(i64) / 1000 or self.done.load(.acquire)) return false;
        self.seek_ms.store(ms, .release);
        return true;
    }
    pub fn awaitEnd(self: *Cast) bool {
        while (!self.done.load(.acquire)) c.fa_sleep_ms(1);
        return self.success;
    }
    pub fn deinit(self: *Cast) void {
        self.cancel();
        if (self.thread) |thread| thread.join();
        self.source.deinit();
        decoder.close(self.sink);
    }
    fn run(self: *Cast) void {
        self.pump() catch {
            self.success = self.epoch.load(.acquire) != 0;
            self.done.store(true, .release);
            return;
        };
        self.success = true;
        self.done.store(true, .release);
    }
    fn writePacket(context: ?*anyopaque, bytes: [*c]const u8, size: c_int) callconv(.c) c_int {
        const self: *Cast = @ptrCast(@alignCast(context.?));
        var offset: usize = 0;
        while (offset < size) {
            const n = c.fa_write_fd(self.sink, bytes + offset, @intCast(@as(usize, @intCast(size)) - offset), &self.epoch.raw, 0);
            if (n <= 0) return -32;
            offset += @intCast(n);
        }
        return size;
    }
    fn seekInput(self: *Cast, ms: u64) !void {
        if (ms > std.math.maxInt(i64) / 1000) return error.InvalidSeek;
        if (c.avformat_seek_file(self.source.format, -1, std.math.minInt(i64), @as(i64, @intCast(ms)) * 1000, std.math.maxInt(i64), 0) < 0) return error.SeekFailed;
    }
    pub fn pump(self: *Cast) !void {
        var output: ?*c.AVFormatContext = null;
        if (c.avformat_alloc_output_context2(&output, null, "matroska", null) < 0 or output == null) return error.MuxerUnavailable;
        defer c.avformat_free_context(output);
        const bytes: [*c]u8 = @ptrCast(c.av_malloc(32768) orelse return error.OutOfMemory);
        var io = c.avio_alloc_context(bytes, 32768, 1, self, null, writePacket, null);
        if (io == null) {
            c.av_free(bytes);
            return error.OutOfMemory;
        }
        defer {
            c.av_free(io.*.buffer);
            c.avio_context_free(&io);
        }
        io.*.seekable = 0;
        output.?.pb = io;
        output.?.flags |= c.AVFMT_FLAG_CUSTOM_IO | c.AVFMT_FLAG_FLUSH_PACKETS;
        output.?.max_interleave_delta = 0;
        const input_stream = self.source.format.?.streams[@intCast(self.source.stream_index)];
        const stream = c.avformat_new_stream(output, null);
        if (stream == null) return error.OutOfMemory;
        if (c.avcodec_parameters_copy(stream.*.codecpar, input_stream.*.codecpar) < 0) return error.CodecParameters;
        stream.*.codecpar.*.codec_tag = 0;
        stream.*.time_base = input_stream.*.time_base;
        if (self.start_ms > 0) try self.seekInput(self.start_ms);
        if (c.avformat_write_header(output, null) < 0) return error.HeaderFailed;
        var packet: *c.AVPacket = c.av_packet_alloc() orelse return error.OutOfMemory;
        defer c.av_packet_free(@ptrCast(&packet));
        var cursor: i64 = 0;
        var shift: ?i64 = null;
        while (self.epoch.load(.acquire) == 0) {
            const target = self.seek_ms.swap(std.math.maxInt(u64), .acq_rel);
            if (target != std.math.maxInt(u64)) {
                try self.seekInput(target);
                shift = null;
            }
            const read = c.av_read_frame(self.source.format, packet);
            if (read == c.AVERROR_EOF) break;
            if (read < 0) return error.ReadFailed;
            defer c.av_packet_unref(packet);
            if (packet.stream_index != self.source.stream_index) continue;
            c.av_packet_rescale_ts(packet, input_stream.*.time_base, stream.*.time_base);
            const pts = if (packet.pts == c.AV_NOPTS_VALUE) cursor else packet.pts;
            if (shift == null) shift = cursor -| pts;
            packet.pts = @max(cursor, pts +| shift.?);
            packet.dts = packet.pts;
            packet.stream_index = 0;
            packet.pos = -1;
            cursor = packet.pts +| @max(packet.duration, 1);
            if (c.av_interleaved_write_frame(output, packet) < 0) return error.WriteFailed;
        }
        if (self.epoch.load(.acquire) == 0 and c.av_write_trailer(output) < 0) return error.TrailerFailed;
        c.avio_flush(io);
    }
};
