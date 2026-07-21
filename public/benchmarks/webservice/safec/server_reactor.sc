extern int printf(const char* fmt, ...);
// io_nb.h's backend is picked by whichever one the caller includes first
// (see http.sc's own comment on this) — not auto-selected by std/ itself.
// http_serve_reactor also needs a matching reactor backend, same rule.
#ifdef __APPLE__
#include <std/sched/io_nb_bsd.sc>
#include <std/sched/reactor_kqueue.sc>
#elif defined(_WIN32)
#include <std/sched/io_nb_win32.sc>
#include <std/sched/reactor_win32.sc>
#else
#include <std/sched/io_nb_linux.sc>
#include <std/sched/reactor_epoll.sc>
#endif
#include <std/http/http.h>
#include <std/http/http.sc>

struct HttpResponse handle(struct HttpRequest* req) {
    struct HttpResponse resp;
    resp.status = 200;
    resp.headers = std::string_from("Content-Type: application/json\r\n");
    resp.body = std::string_from("{\"message\":\"Hello, World!\"}");
    return resp;
}

int main() {
    printf("SafeC server (reactor) listening on 8082\n");
    std::http_serve_reactor(8082, handle, 1);
    return 0;
}
