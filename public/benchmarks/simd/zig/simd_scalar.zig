const std = @import("std");
const N = 20000000;
var a: [N]f64 = undefined;
pub fn main() void {
    var i: usize = 0;
    while (i < N) : (i += 1) a[i] = @as(f64, @floatFromInt(i % 1000)) * 0.001;
    var sum: f64 = 0.0;
    i = 0;
    while (i < N) : (i += 1) sum += a[i] * a[i];
    std.debug.print("{d:.6}\n", .{sum});
}
