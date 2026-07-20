#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <thread>
#include <sys/socket.h>
#include <netinet/in.h>

static const char *BODY = "{\"message\":\"Hello, World!\"}";

void handle_conn(int fd) {
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n > 0) {
        char resp[512];
        int body_len = (int)strlen(BODY);
        int len = snprintf(resp, sizeof(resp),
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
            body_len, BODY);
        write(fd, resp, len);
    }
    close(fd);
}

int main() {
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(8083);
    bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr));
    listen(listen_fd, 1024);
    printf("C++ server listening on 8083\n");
    for (;;) {
        int conn_fd = accept(listen_fd, nullptr, nullptr);
        if (conn_fd < 0) continue;
        std::thread(handle_conn, conn_fd).detach();
    }
    return 0;
}
