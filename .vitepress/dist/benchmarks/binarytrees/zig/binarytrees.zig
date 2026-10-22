const std = @import("std");

const Node = struct {
    left: ?*Node,
    right: ?*Node,
};

fn makeTree(allocator: std.mem.Allocator, depth: i32) !*Node {
    const n = try allocator.create(Node);
    if (depth > 0) {
        n.left = try makeTree(allocator, depth - 1);
        n.right = try makeTree(allocator, depth - 1);
    } else {
        n.left = null;
        n.right = null;
    }
    return n;
}

fn checksum(n: *Node) i32 {
    if (n.left == null) return 1;
    return 1 + checksum(n.left.?) + checksum(n.right.?);
}

fn freeTree(allocator: std.mem.Allocator, n: *Node) void {
    if (n.left != null) {
        freeTree(allocator, n.left.?);
        freeTree(allocator, n.right.?);
    }
    allocator.destroy(n);
}

pub fn main() !void {
    
    const allocator = std.heap.c_allocator;

    const min_depth: i32 = 4;
    const max_depth: i32 = 18;

    const stretch_depth = max_depth + 1;
    const stretch_tree = try makeTree(allocator, stretch_depth);
    std.debug.print("stretch tree of depth {d} check: {d}\n", .{ stretch_depth, checksum(stretch_tree) });
    freeTree(allocator, stretch_tree);

    const long_lived_tree = try makeTree(allocator, max_depth);

    var depth: i32 = min_depth;
    while (depth <= max_depth) : (depth += 2) {
        const iterations = @as(i32, 1) << @as(u5, @intCast(max_depth - depth + min_depth));
        var check: i32 = 0;
        var i: i32 = 0;
        while (i < iterations) : (i += 1) {
            const t = try makeTree(allocator, depth);
            check += checksum(t);
            freeTree(allocator, t);
        }
        std.debug.print("{d} trees of depth {d} check: {d}\n", .{ iterations, depth, check });
    }

    std.debug.print("long lived tree of depth {d} check: {d}\n", .{ max_depth, checksum(long_lived_tree) });
    freeTree(allocator, long_lived_tree);
}
