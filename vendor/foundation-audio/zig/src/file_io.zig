const std = @import("std");
const c = @import("c.zig").api;
/// Each AVIO has its own logical cursor; duplicated Unix fds share a kernel
/// offset, so fread/lseek would corrupt overlapping repeat/cast decoders.
pub const File = struct {
    fd: c_int,
    start: i64,
    position: i64 = 0,
    size: i64,
    pub fn read(context: ?*anyopaque, buffer: [*c]u8, count: c_int) callconv(.c) c_int {
        const self: *File = @ptrCast(@alignCast(context.?));
        const absolute = std.math.add(i64, self.start, self.position) catch return -75;
        const result = c.fa_read_at(self.fd, buffer, count, absolute);
        if (result < 0) return -5;
        if (result == 0) return c.AVERROR_EOF;
        self.position += result;
        return result;
    }
    pub fn seek(context: ?*anyopaque, offset: i64, flags: c_int) callconv(.c) i64 {
        const self: *File = @ptrCast(@alignCast(context.?));
        if (flags & c.AVSEEK_SIZE != 0) return self.size;
        const base: i64 = switch (flags & ~@as(c_int, c.AVSEEK_FORCE)) {
            c.SEEK_SET => 0,
            c.SEEK_CUR => self.position,
            c.SEEK_END => self.size,
            else => return -22,
        };
        const next = std.math.add(i64, base, offset) catch return -75;
        if (next < 0) return -22;
        self.position = next;
        return next;
    }
};
