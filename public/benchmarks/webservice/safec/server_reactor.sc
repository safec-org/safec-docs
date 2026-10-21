extern int printf(const char* fmt, ...);
// io_nb.h's backend is picked by whichever one the caller includes first
// (see http.sc's own comment on this) — not auto-selected by std/ itself.
// http_serve_reactor also needs a matching reactor backend, same rule.
#ifdef __APPLE__
#elif defined(_WIN32)
#else
#endif
#include <std/http/http.h>
#include <std/sys.h>

struct HttpResponse handle(struct HttpRequest* req) {
    struct HttpResponse resp;
    resp.status = 200;
    resp.headers = std::string_from("Content-Type: application/json\r\n");
    resp.body = std::string_from("{\"message\":\"Hello, World!\"}");
    return resp;
}

int main() {
    unsigned short port = (unsigned short)8082;
    match (std::sys_getenv("PORT")) {
        case some(e): { port = (unsigned short)std::sys_atoi(e); }
        case null: { }
    }
    printf("SafeC server (reactor) listening on %d\n", (int)port);
    std::http_serve_reactor(port, handle, 1);
    return 0;
}
