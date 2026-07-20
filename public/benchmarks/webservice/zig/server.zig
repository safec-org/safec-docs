const std = @import("std");
const c = @cImport({
    @cInclude("sys/socket.h");
    @cInclude("netinet/in.h");
    @cInclude("unistd.h");
    @cInclude("pthread.h");
    @cInclude("string.h");
    @cInclude("stdio.h");
});

const BODY = "{\"message\":\"Hello, World!\"}";

fn handleConn(arg: ?*anyopaque) callconv(.c) ?*anyopaque {
    const fd: c_int = @intCast(@intFromPtr(arg));
    var buf: [4096]u8 = undefined;
    const n = c.read(fd, &buf, buf.len - 1);
    if (n > 0) {
        var resp: [512]u8 = undefined;
        const len = c.snprintf(&resp, resp.len,
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
            @as(c_int, BODY.len), BODY.ptr);
        _ = c.write(fd, &resp, @intCast(len));
    }
    _ = c.close(fd);
    return null;
}

pub fn main() void {
    const listen_fd = c.socket(c.AF_INET, c.SOCK_STREAM, 0);
    var opt: c_int = 1;
    _ = c.setsockopt(listen_fd, c.SOL_SOCKET, c.SO_REUSEADDR, &opt, @sizeOf(c_int));

    var addr: c.struct_sockaddr_in = undefined;
    _ = c.memset(&addr, 0, @sizeOf(c.struct_sockaddr_in));
    addr.sin_family = @intCast(c.AF_INET);
    addr.sin_addr.s_addr = c.INADDR_ANY;
    addr.sin_port = c.htons(8085);

    _ = c.bind(listen_fd, @ptrCast(&addr), @sizeOf(c.struct_sockaddr_in));
    _ = c.listen(listen_fd, 1024);
    std.debug.print("Zig server listening on 8085\n", .{});

    while (true) {
        const conn_fd = c.accept(listen_fd, null, null);
        if (conn_fd < 0) continue;
        var tid: c.pthread_t = undefined;
        _ = c.pthread_create(&tid, null, handleConn, @ptrFromInt(@as(usize, @intCast(conn_fd))));
        _ = c.pthread_detach(tid);
    }
}
