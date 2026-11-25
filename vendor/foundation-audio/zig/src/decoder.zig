const std = @import("std");
pub const c = @import("c.zig").api;
const native = @import("builtin").os.tag;
const os = struct {
    extern "c" fn dup(c_int) c_int;
    extern "c" fn _dup(c_int) c_int;
    extern "c" fn close(c_int) c_int;
    extern "c" fn _close(c_int) c_int;
    extern "c" fn fdopen(c_int, [*:0]const u8) ?*c.FILE;
    extern "c" fn _fdopen(c_int, [*:0]const u8) ?*c.FILE;
    extern "c" fn fseeko(*c.FILE, i64, c_int) c_int;
    extern "c" fn ftello(*c.FILE) i64;
    extern "c" fn fseeko64(*c.FILE, i64, c_int) c_int;
    extern "c" fn ftello64(*c.FILE) i64;
    extern "c" fn _fseeki64(*c.FILE, i64, c_int) c_int;
    extern "c" fn _ftelli64(*c.FILE) i64;
};
pub fn duplicate(fd: c_int) !c_int {
    const result = if (native == .windows) os._dup(fd) else os.dup(fd);
    if (result < 0) return error.InvalidDescriptor;
    return result;
}
pub fn close(fd: c_int) void {
    _ = if (native == .windows) os._close(fd) else os.close(fd);
}
fn seekFile(file: *c.FILE, offset: i64, whence: c_int) c_int {
    return if (native == .windows) os._fseeki64(file, offset, whence) else if (native == .macos or native == .ios) os.fseeko(file, offset, whence) else os.fseeko64(file, offset, whence);
}
fn tellFile(file: *c.FILE) i64 {
    return if (native == .windows) os._ftelli64(file) else if (native == .macos or native == .ios) os.ftello(file) else os.ftello64(file);
}

/// Owns demux, decode, resample and custom AVIO lifetime. A decoder belongs to
/// exactly one worker. It never shares FFmpeg context pointers with render.
pub const Decoder = struct {
    allocator: std.mem.Allocator,
    format: ?*c.AVFormatContext = null,
    codec: ?*c.AVCodecContext = null,
    resampler: ?*c.SwrContext = null,
    frame: ?*c.AVFrame = null,
    packet: ?*c.AVPacket = null,
    io: ?*c.AVIOContext = null,
    file: ?*c.FILE = null,
    positioned: ?*@import("file_io.zig").File = null,
    input: ?*@import("stream.zig").Input = null,
    stream_header: ?@import("transport.zig").MediaHeader = null,
    stream_index: c_int = -1,
    rate: u32,
    channels: u16,
    duration_ms: u64 = 0,
    eof: bool = false,
    flushed: bool = false,
    samples: std.ArrayList(f32) = .empty,
    skip_frames: u64 = 0,
    seek_target_ms: ?u64 = null,
    damaged_packets: u8 = 0,
    packet_frames: usize = 0,

    pub fn openPath(allocator: std.mem.Allocator, path: [:0]const u8, rate: u32, channels: u16) !Decoder {
        const file: *c.FILE = @ptrCast(@alignCast(c.fa_fopen_utf8(path.ptr) orelse return error.OpenFailed));
        return openFile(allocator, file, rate, channels);
    }
    pub fn openFd(allocator: std.mem.Allocator, original: c_int, rate: u32, channels: u16) !Decoder {
        const fd = try duplicate(original);
        const io_file = allocator.create(@import("file_io.zig").File) catch |err| {
            close(fd);
            return err;
        };
        const start = if (native == .windows) 0 else @max(0, c.fa_fd_position(fd));
        io_file.* = .{ .fd = fd, .start = start, .size = @max(0, c.fa_fd_size(fd) - start) };
        var self: Decoder = .{ .allocator = allocator, .positioned = io_file, .rate = rate, .channels = channels };
        errdefer self.deinit();
        if (rate == 0 or channels == 0 or channels > 32) return error.InvalidOutputFormat;
        const buffer: [*c]u8 = @ptrCast(c.av_malloc(32768) orelse return error.OutOfMemory);
        self.io = c.avio_alloc_context(buffer, 32768, 0, io_file, @import("file_io.zig").File.read, null, @import("file_io.zig").File.seek);
        if (self.io == null) {
            c.av_free(buffer);
            return error.OutOfMemory;
        }
        self.format = c.avformat_alloc_context() orelse return error.OutOfMemory;
        self.format.?.pb = self.io;
        self.format.?.flags |= c.AVFMT_FLAG_CUSTOM_IO;
        try self.initialize(null);
        return self;
    }
    fn openFile(allocator: std.mem.Allocator, file: *c.FILE, rate: u32, channels: u16) !Decoder {
        var self: Decoder = .{ .allocator = allocator, .file = file, .rate = rate, .channels = channels };
        errdefer self.deinit();
        if (rate == 0 or channels == 0 or channels > 32) return error.InvalidOutputFormat;
        const buffer: [*c]u8 = @ptrCast(c.av_malloc(32768) orelse return error.OutOfMemory);
        self.io = c.avio_alloc_context(buffer, 32768, 0, file, readPacket, null, seekPacket);
        if (self.io == null) {
            c.av_free(buffer);
            return error.OutOfMemory;
        }
        self.format = c.avformat_alloc_context() orelse return error.OutOfMemory;
        self.format.?.pb = self.io;
        self.format.?.flags |= c.AVFMT_FLAG_CUSTOM_IO;
        try self.initialize(null);
        return self;
    }
    pub fn openStream(allocator: std.mem.Allocator, original: c_int, epoch: *const std.atomic.Value(u32), expected: u32, hint: ?[:0]const u8) !Decoder {
        const input = try allocator.create(@import("stream.zig").Input);
        input.* = .{ .fd = duplicate(original) catch |err| {
            allocator.destroy(input);
            return err;
        }, .epoch = epoch, .expected = expected };
        var self: Decoder = .{ .allocator = allocator, .input = input, .rate = 48000, .channels = 2 };
        errdefer self.deinit();
        var header: [28]u8 = undefined;
        try input.exact(&header);
        self.stream_header = @import("transport.zig").MediaHeader.decode(&header);
        if (self.stream_header.?.byte_length != 0) input.remaining = self.stream_header.?.byte_length;
        input.allocator = allocator;
        input.recording = true;
        try self.setupStream(hint != null);
        self.initialize(if (hint) |name| c.av_find_input_format(name.ptr) else null) catch |err| {
            if (hint == null or !input.rewind()) return err;
            c.av_packet_free(&self.packet);
            c.av_frame_free(&self.frame);
            c.swr_free(&self.resampler);
            c.avcodec_free_context(&self.codec);
            c.avformat_close_input(&self.format);
            if (self.io) |io| {
                c.av_free(io.buffer);
                c.avio_context_free(&self.io);
            }
            self.duration_ms = 0;
            try self.setupStream(false);
            try self.initialize(null);
        };
        input.finishProbe();
        return self;
    }
    fn setupStream(self: *Decoder, hinted: bool) !void {
        const input = self.input.?;
        const buffer: [*c]u8 = @ptrCast(c.av_malloc(32768) orelse return error.OutOfMemory);
        self.io = c.avio_alloc_context(buffer, 32768, 0, input, @import("stream.zig").Input.packet, null, null);
        if (self.io == null) {
            c.av_free(buffer);
            return error.OutOfMemory;
        }
        self.io.?.seekable = 0;
        self.format = c.avformat_alloc_context() orelse return error.OutOfMemory;
        self.format.?.pb = self.io;
        self.format.?.flags |= c.AVFMT_FLAG_CUSTOM_IO;
        self.format.?.probesize = if (hinted) 65536 else 32 * 1024 * 1024;
        self.format.?.max_analyze_duration = 1000000;
    }
    fn initialize(self: *Decoder, format_hint: [*c]const c.AVInputFormat) !void {
        if (c.avformat_open_input(&self.format, null, format_hint, null) < 0) return error.OpenFailed;
        // FLAC STREAMINFO and ALAC's configuration contain the complete decoder
        // parameters. Probing their packets again can pull megabytes over a remote
        // descriptor before the first sample is allowed to play.
        const header_audio = c.av_find_best_stream(self.format, c.AVMEDIA_TYPE_AUDIO, -1, -1, null, 0);
        const complete_lossless_header = if (header_audio >= 0) blk: {
            const parameters = self.format.?.streams[@intCast(header_audio)].*.codecpar;
            break :blk (parameters.*.codec_id == c.AV_CODEC_ID_ALAC or parameters.*.codec_id == c.AV_CODEC_ID_FLAC) and
                parameters.*.sample_rate > 0 and parameters.*.ch_layout.nb_channels > 0 and parameters.*.extradata_size > 0;
        } else false;
        if (!complete_lossless_header and c.avformat_find_stream_info(self.format, null) < 0) return error.StreamInfoFailed;
        self.stream_index = c.av_find_best_stream(self.format, c.AVMEDIA_TYPE_AUDIO, -1, -1, null, 0);
        if (self.stream_index < 0) return error.NoAudio;
        const stream = self.format.?.streams[@intCast(self.stream_index)];
        const codec = c.avcodec_find_decoder(stream.*.codecpar.*.codec_id) orelse return error.UnsupportedCodec;
        self.codec = c.avcodec_alloc_context3(codec) orelse return error.OutOfMemory;
        if (c.avcodec_parameters_to_context(self.codec, stream.*.codecpar) < 0) return error.CodecParameters;
        if (c.avcodec_open2(self.codec, codec, null) < 0) return error.CodecOpenFailed;
        if (self.format.?.duration > 0) self.duration_ms = @intCast(@divTrunc(self.format.?.duration, 1000));
        if (self.duration_ms == 0 and stream.*.duration > 0) {
            const value = c.av_rescale_q(stream.*.duration, stream.*.time_base, .{ .num = 1, .den = 1000 });
            if (value > 0) self.duration_ms = @intCast(value);
        }
        var layout: c.AVChannelLayout = std.mem.zeroes(c.AVChannelLayout);
        c.av_channel_layout_default(&layout, self.channels);
        defer c.av_channel_layout_uninit(&layout);
        if (c.swr_alloc_set_opts2(&self.resampler, &layout, c.AV_SAMPLE_FMT_FLT, @intCast(self.rate), &self.codec.?.ch_layout, self.codec.?.sample_fmt, self.codec.?.sample_rate, 0, null) < 0) return error.ResamplerFailed;
        if (c.swr_init(self.resampler) < 0) return error.ResamplerFailed;
        self.frame = c.av_frame_alloc() orelse return error.OutOfMemory;
        self.packet = c.av_packet_alloc() orelse return error.OutOfMemory;
    }
    pub fn deinit(self: *Decoder) void {
        c.av_packet_free(&self.packet);
        c.av_frame_free(&self.frame);
        c.swr_free(&self.resampler);
        c.avcodec_free_context(&self.codec);
        c.avformat_close_input(&self.format);
        if (self.io) |io| {
            c.av_free(io.buffer);
            c.avio_context_free(&self.io);
        }
        if (self.file) |file| _ = c.fclose(file);
        if (self.positioned) |file| {
            close(file.fd);
            self.allocator.destroy(file);
        }
        if (self.input) |input| {
            input.deinit();
            close(input.fd);
            self.allocator.destroy(input);
        }
        self.samples.deinit(self.allocator);
        self.* = undefined;
    }
    fn readPacket(context: ?*anyopaque, buffer: [*c]u8, size: c_int) callconv(.c) c_int {
        const file: *c.FILE = @ptrCast(@alignCast(context.?));
        const count = c.fread(buffer, 1, @intCast(size), file);
        if (count > 0) return @intCast(count);
        return if (c.ferror(file) != 0) -5 else c.AVERROR_EOF;
    }
    fn seekPacket(context: ?*anyopaque, offset: i64, flags: c_int) callconv(.c) i64 {
        const file: *c.FILE = @ptrCast(@alignCast(context.?));
        if (flags & c.AVSEEK_SIZE != 0) {
            const current = tellFile(file);
            if (current < 0 or seekFile(file, 0, c.SEEK_END) != 0) return -1;
            const length = tellFile(file);
            if (seekFile(file, current, c.SEEK_SET) != 0) return -1;
            return length;
        }
        if (seekFile(file, offset, flags & ~@as(c_int, c.AVSEEK_FORCE)) != 0) return -1;
        return tellFile(file);
    }
    pub fn seek(self: *Decoder, milliseconds: u64) !void {
        if (self.input != null) return error.NotSeekable;
        const bounded = if (self.duration_ms > 0) @min(milliseconds, self.duration_ms) else milliseconds;
        if (bounded > std.math.maxInt(i64) / 1000) return error.InvalidSeek;
        if (c.av_seek_frame(self.format, -1, @as(i64, @intCast(bounded)) * 1000, c.AVSEEK_FLAG_BACKWARD) < 0) return error.SeekFailed;
        c.avcodec_flush_buffers(self.codec);
        c.swr_close(self.resampler);
        if (c.swr_init(self.resampler) < 0) return error.ResamplerFailed;
        c.av_packet_unref(self.packet);
        self.eof = false;
        self.flushed = false;
        self.damaged_packets = 0;
        self.packet_frames = 0;
        self.skip_frames = 0;
        self.seek_target_ms = bounded;
    }
    /// Returned PCM remains valid until the next read. Empty means end of stream.
    pub fn read(self: *Decoder) ![]const f32 {
        while (true) {
            const received = c.avcodec_receive_frame(self.codec, self.frame);
            if (received == 0) {
                self.damaged_packets = 0;
                const frame = self.frame.?;
                if (self.seek_target_ms) |target| {
                    const pts = frame.best_effort_timestamp;
                    if (pts != c.AV_NOPTS_VALUE) {
                        const stream = self.format.?.streams[@intCast(self.stream_index)];
                        const milliseconds = c.av_rescale_q(pts, stream.*.time_base, .{ .num = 1, .den = 1000 });
                        self.skip_frames = if (milliseconds < @as(i64, @intCast(target))) @intCast(@divTrunc((@as(i64, @intCast(target)) - milliseconds) * self.rate, 1000)) else 0;
                        self.seek_target_ms = null;
                    }
                }
                const output = try self.convert(frame.extended_data, frame.nb_samples);
                c.av_frame_unref(frame);
                const skip = @min(output.len / self.channels, self.skip_frames);
                self.skip_frames -= skip;
                const result = output[skip * self.channels ..];
                if (result.len != 0) return result;
                continue;
            }
            if (received == c.AVERROR_EOF) return self.convert(null, 0);
            if (received == c.AVERROR_INVALIDDATA) return self.concealDamagedPacket();
            if (received != -11) return error.DecodeFailed;
            if (self.eof) {
                if (self.flushed) return &.{};
                const sent = c.avcodec_send_packet(self.codec, null);
                if (sent < 0 and sent != c.AVERROR_EOF) return error.DecodeFailed;
                self.flushed = true;
                continue;
            }
            while (true) {
                const result = c.av_read_frame(self.format, self.packet);
                if (result < 0) {
                    if (result != c.AVERROR_EOF) return error.ReadFailed;
                    self.eof = true;
                    break;
                }
                if (self.packet.?.stream_index != self.stream_index) {
                    c.av_packet_unref(self.packet);
                    continue;
                }
                const stream = self.format.?.streams[@intCast(self.stream_index)];
                const frames = c.av_rescale_q(self.packet.?.duration, stream.*.time_base, .{ .num = 1, .den = @intCast(self.rate) });
                self.packet_frames = if (frames > 0 and frames <= self.rate) @intCast(frames) else 0;
                const sent = c.avcodec_send_packet(self.codec, self.packet);
                c.av_packet_unref(self.packet);
                if (sent == c.AVERROR_INVALIDDATA) return self.concealDamagedPacket();
                if (sent < 0) return error.DecodeFailed;
                break;
            }
        }
    }
    fn concealDamagedPacket(self: *Decoder) ![]const f32 {
        // Preserve the timeline across an isolated damaged packet. Do not hide a
        // broken stream indefinitely or return an empty buffer (which means EOF).
        if (self.damaged_packets >= 8 or self.packet_frames == 0) return error.DecodeFailed;
        self.damaged_packets += 1;
        c.av_frame_unref(self.frame);
        try self.samples.resize(self.allocator, self.packet_frames * self.channels);
        @memset(self.samples.items, 0);
        return self.samples.items;
    }
    fn convert(self: *Decoder, input: [*c][*c]u8, frames: c_int) ![]const f32 {
        const capacity = c.swr_get_out_samples(self.resampler, frames);
        if (capacity < 0) return error.ResamplerFailed;
        try self.samples.resize(self.allocator, @as(usize, @intCast(@max(1, capacity))) * self.channels);
        var output = [_][*c]u8{@ptrCast(self.samples.items.ptr)};
        const converted = c.swr_convert(self.resampler, &output, capacity, @ptrCast(input), frames);
        if (converted < 0) return error.ResamplerFailed;
        return self.samples.items[0 .. @as(usize, @intCast(converted)) * self.channels];
    }
    pub fn tag(self: *const Decoder, key: [:0]const u8) ?[]const u8 {
        var value = c.av_dict_get(self.format.?.metadata, key.ptr, null, 0);
        if (value == null) value = c.av_dict_get(self.format.?.streams[@intCast(self.stream_index)].*.metadata, key.ptr, null, 0);
        return if (value != null) std.mem.span(value.*.value) else null;
    }
    pub fn cover(self: *const Decoder) ?[]const u8 {
        for (self.format.?.streams[0..self.format.?.nb_streams]) |stream| {
            if (stream.*.disposition & c.AV_DISPOSITION_ATTACHED_PIC != 0 and stream.*.attached_pic.size > 0)
                return stream.*.attached_pic.data[0..@intCast(stream.*.attached_pic.size)];
        }
        return null;
    }
};
