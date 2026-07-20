const std = @import("std");

const nthreads = 8;

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

const WorkSlice = struct { start: i32, end: i32, depth: i32, result: i32 };

fn worker(allocator: std.mem.Allocator, s: *WorkSlice) void {
    var check: i32 = 0;
    var i: i32 = s.start;
    while (i < s.end) : (i += 1) {
        const t = makeTree(allocator, s.depth) catch unreachable;
        check += checksum(t);
        freeTree(allocator, t);
    }
    s.result = check;
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
        var per_thread = @divTrunc(iterations, nthreads);
        if (per_thread < 1) per_thread = 1;

        var threads: [nthreads]?std.Thread = [_]?std.Thread{null} ** nthreads;
        var slices: [nthreads]WorkSlice = undefined;
        var n_spawned: usize = 0;
        var start: i32 = 0;
        var t: usize = 0;
        while (t < nthreads and start < iterations) : (t += 1) {
            var end = start + per_thread;
            if (t == nthreads - 1 or end > iterations) end = iterations;
            slices[t] = WorkSlice{ .start = start, .end = end, .depth = depth, .result = 0 };
            threads[t] = try std.Thread.spawn(.{}, worker, .{ allocator, &slices[t] });
            n_spawned += 1;
            start = end;
        }
        var check: i32 = 0;
        var j: usize = 0;
        while (j < n_spawned) : (j += 1) {
            threads[j].?.join();
            check += slices[j].result;
        }
        std.debug.print("{d} trees of depth {d} check: {d}\n", .{ iterations, depth, check });
    }

    std.debug.print("long lived tree of depth {d} check: {d}\n", .{ max_depth, checksum(long_lived_tree) });
    freeTree(allocator, long_lived_tree);
}
