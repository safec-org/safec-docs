#pragma once
// SafeC Standard Library — Gemma 4 HTTP streaming server.
//
// std/http/http.h's HttpHandler contract is strictly request-in,
// complete-response-out (`fn struct HttpResponse(struct HttpRequest*)`)
// -- there is no incremental-write/chunked-transfer hook anywhere in
// that module (its own header comment says so explicitly: "chunked
// transfer-encoding is not supported"). Genuine token-by-token push
// therefore can't be built on std::http_serve_reactor's handler
// abstraction at all -- this module instead talks directly to the same
// portable, public, non-reactor socket primitives std/http/http.sc
// itself is built on (std/sched/io_nb.h's tcp_listen_nb/tcp_accept_nb/
// fd_set_blocking, "flipped back to blocking mode" exactly the way
// http_serve() does), and hand-writes real HTTP/1.1 chunked
// transfer-encoding response bytes as each token is generated. This is
// still a real HTTP/1.1 server any browser/curl/fetch() can talk to --
// it just doesn't route through http.h's request/response value type,
// since that type has nowhere to express "write this now, more to
// follow."
//
// Deliberately minimal HTTP: reads only the request line (method +
// path?query), no header parsing, no request body support -- the prompt
// is passed as a URL query parameter (?prompt=...), percent-decoded.
// Good enough for a benchmark demo hit from curl or a browser's
// EventSource-less fetch() reader; not a general-purpose HTTP request
// parser.
//
// Needs (build-time, same "backend .sc your platform" convention as
// std/http/http.h itself): std/sched/io_nb_bsd.sc (macOS/iOS/BSD) or
// io_nb_linux.sc / io_nb_win32.sc, included/passed by the caller.
#include "gemma4_generate.h"
#include "gemma4_tokenizer.h"

namespace std {

// Binds 'port' and serves GET requests of the form "GET
// /generate?prompt=<url-encoded text> HTTP/1.1" in a blocking accept
// loop (one connection at a time -- this is a benchmark demo server, not
// a production concurrent one; see header comment), streaming the
// model's greedy-decoded continuation back as HTTP/1.1 chunked
// transfer-encoding text/plain, one chunk per generated token, until
// eosId or 'maxNewTokens' is reached. Does not return under normal
// operation (mirrors std::http_serve's own contract); returns -1
// immediately if the listening socket can't be created/bound.
int gemma4_http_serve_stream_bf16(unsigned short port, &Gemma4Bf16Model model, &Gemma4Tokenizer tok,
                                    unsigned long maxNewTokens, float temperature);

} // namespace std
