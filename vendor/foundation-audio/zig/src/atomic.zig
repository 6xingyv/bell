const std = @import("std");
const c = @import("c.zig").api;
/// C ABI bridge for ARMv7's doubleword atomic instructions. Stronger ordering
/// on 32-bit targets is intentional; the audio callback never takes a mutex.
pub const U64 = struct {
    raw: u64 align(8),
    pub fn init(value: u64) U64 {
        return .{ .raw = value };
    }
    pub fn load(self: *const U64, comptime order: std.builtin.AtomicOrder) u64 {
        return if (@sizeOf(usize) == 4) c.fa_atomic_load64(&self.raw) else @atomicLoad(u64, &self.raw, order);
    }
    pub fn store(self: *U64, value: u64, comptime order: std.builtin.AtomicOrder) void {
        if (@sizeOf(usize) == 4) c.fa_atomic_store64(&self.raw, value) else @atomicStore(u64, &self.raw, value, order);
    }
    pub fn swap(self: *U64, value: u64, comptime order: std.builtin.AtomicOrder) u64 {
        return if (@sizeOf(usize) == 4) c.fa_atomic_swap64(&self.raw, value) else @atomicRmw(u64, &self.raw, .Xchg, value, order);
    }
    pub fn fetchAdd(self: *U64, value: u64, comptime order: std.builtin.AtomicOrder) u64 {
        return if (@sizeOf(usize) == 4) c.fa_atomic_add64(&self.raw, value) else @atomicRmw(u64, &self.raw, .Add, value, order);
    }
};
