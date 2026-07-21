extern int printf(const char* fmt, ...);
// io_nb.h's backend is picked by whichever one the caller includes first
// (see http.sc's own comment on this) — not auto-selected by std/ itself.
#ifdef __APPLE__
#include <std/sched/io_nb_bsd.sc>
#elif defined(_WIN32)
#include <std/sched/io_nb_win32.sc>
#else
#include <std/sched/io_nb_linux.sc>
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
    printf("SafeC server listening on 8081\n");
    std::http_serve_threaded(8081, handle, 8);
    return 0;
}
