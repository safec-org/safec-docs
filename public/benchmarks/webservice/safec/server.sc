extern int printf(const char* fmt, ...);
#include <std/sched/io_nb_bsd.sc>
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
