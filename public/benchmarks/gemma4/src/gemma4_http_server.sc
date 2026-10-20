// SafeC Standard Library — Gemma 4 HTTP streaming server implementation
// (see gemma4_http_server.h).
#pragma once
#include "gemma4_http_server.h"
#include "gemma4_generate.h"
#include "gemma4_tokenizer.h"
#include <std/sched/io_nb.h>
#include <std/fmt.h>
#include <std/str.h>

namespace std {

extern int   printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);

// Same platform split as std/http/http.sc's sock_read_/sock_write_ (see
// that file's own comment for why: Winsock sockets need recv/send, POSIX
// fds work with plain read/write) -- duplicated here rather than reused
// since those are 'static' (file-private) in http.sc and this module
// deliberately never links http.sc at all (see gemma4_http_server.h's
// header comment on why it bypasses http_serve_reactor entirely).
#ifdef _WIN32
extern int recv(unsigned long long fd, void* buf, int count, int flags);
extern int send(unsigned long long fd, const void* buf, int count, int flags);
extern int closesocket(unsigned long long fd);
static long __g4h_sock_read(int fd, void* buf, unsigned long count) {
    unsafe { return (long)recv((unsigned long long)fd, buf, (int)count, 0); }
}
static long __g4h_sock_write(int fd, const void* buf, unsigned long count) {
    unsafe { return (long)send((unsigned long long)fd, buf, (int)count, 0); }
}
static int __g4h_sock_close(int fd) {
    unsafe { return closesocket((unsigned long long)fd); }
}
#else
extern long read(int fd, void* buf, unsigned long count);
extern long write(int fd, const void* buf, unsigned long count);
extern int close(int fd);
static long __g4h_sock_read(int fd, void* buf, unsigned long count) {
    unsafe { return read(fd, buf, count); }
}
static long __g4h_sock_write(int fd, const void* buf, unsigned long count) {
    unsafe { return write(fd, buf, count); }
}
static int __g4h_sock_close(int fd) {
    unsafe { return close(fd); }
}
#endif

static void __g4h_write_all(int fd, const char* data, unsigned long len) {
    unsigned long sent = 0UL;
    while (sent < len) {
        long n;
        unsafe { n = __g4h_sock_write(fd, (const void*)(data + sent), len - sent); }
        if (n <= 0L) { return; } // connection gone -- nothing more we can do
        sent = sent + (unsigned long)n;
    }
}

// Writes one HTTP/1.1 chunked-transfer-encoding chunk: "<hex-len>\r\n<data>\r\n".
static void __g4h_write_chunk(int fd, const char* data, unsigned long len) {
    char hexBuf[20];
    int hexLen;
    unsafe { hexLen = fmt_hex(hexBuf, 20UL, (unsigned long long)len); }
    struct String frame = string_new();
    unsafe { frame.push_n(hexBuf, (unsigned long)hexLen); }
    frame.push("\r\n");
    if (len > 0UL) { unsafe { frame.push_n(data, len); } }
    frame.push("\r\n");
    __g4h_write_all(fd, frame.as_ptr(), frame.length());
    frame.free();
}

static int __g4h_hex_digit(char c) {
    if (c >= '0' && c <= '9') { return (int)(c - '0'); }
    if (c >= 'a' && c <= 'f') { return (int)(c - 'a') + 10; }
    if (c >= 'A' && c <= 'F') { return (int)(c - 'A') + 10; }
    return -1;
}

// Decodes a URL-encoded query-parameter value: '+' -> space, "%XX" -> the
// raw byte, everything else copied through as-is.
static struct String __g4h_percent_decode(const char* raw, unsigned long len) {
    struct String out = string_new();
    unsigned long i = 0UL;
    unsafe {
        while (i < len) {
            char c = raw[i];
            if (c == '+') {
                out.push_char(' ');
                i = i + 1UL;
            } else if (c == '%' && i + 2UL < len) {
                int hi = __g4h_hex_digit(raw[i + 1UL]);
                int lo = __g4h_hex_digit(raw[i + 2UL]);
                if (hi >= 0 && lo >= 0) {
                    out.push_char((char)((hi << 4) | lo));
                    i = i + 3UL;
                } else {
                    out.push_char(c);
                    i = i + 1UL;
                }
            } else {
                out.push_char(c);
                i = i + 1UL;
            }
        }
    }
    return out;
}

// Parses "GET /generate?prompt=<value>[&...] HTTP/1.1" and returns the
// decoded prompt value, or an empty String if the request line doesn't
// have one (caller checks .length()==0).
static struct String __g4h_extract_prompt(const char* reqLine, unsigned long len) {
    struct String empty = string_new();
    unsigned long sp1 = 0UL;
    unsafe {
        while (sp1 < len && reqLine[sp1] != ' ') { sp1 = sp1 + 1UL; }
        if (sp1 >= len) { return empty; }
    }
    unsigned long pathStart = sp1 + 1UL;
    unsigned long sp2 = pathStart;
    unsafe {
        while (sp2 < len && reqLine[sp2] != ' ') { sp2 = sp2 + 1UL; }
        if (sp2 >= len) { return empty; }
    }
    // path+query is reqLine[pathStart, sp2)
    unsigned long qMark = pathStart;
    unsafe {
        while (qMark < sp2 && reqLine[qMark] != '?') { qMark = qMark + 1UL; }
        if (qMark >= sp2) { return empty; } // no query string at all
    }
    unsigned long queryStart = qMark + 1UL;
    // find "prompt=" within [queryStart, sp2)
    const char* needle = "prompt=";
    unsigned long needleLen = 7UL;
    unsigned long p = queryStart;
    unsafe {
        while (p + needleLen <= sp2) {
            int isMatch = 1;
            unsigned long k = 0UL;
            while (k < needleLen) {
                if (reqLine[p + k] != needle[k]) { isMatch = 0; }
                k = k + 1UL;
            }
            if (isMatch) {
                unsigned long valStart = p + needleLen;
                unsigned long valEnd = valStart;
                while (valEnd < sp2 && reqLine[valEnd] != '&') { valEnd = valEnd + 1UL; }
                return __g4h_percent_decode(reqLine + valStart, valEnd - valStart);
            }
            p = p + 1UL;
        }
    }
    return empty;
}

struct __Gemma4StreamCtx {
    int fd;
    &Gemma4Tokenizer tok;
};

static void __g4h_stream_token(void* ctxRaw, int tokenId) {
    struct __Gemma4StreamCtx* ctx;
    unsafe { ctx = (struct __Gemma4StreamCtx*)ctxRaw; }
    int ids[1];
    struct String piece;
    unsafe {
        ids[0] = tokenId;
        piece = gemma4_tok_decode(ctx->tok, ids, 1UL);
    }
    unsafe { __g4h_write_chunk(ctx->fd, piece.as_ptr(), piece.length()); }
    piece.free();
}

int gemma4_http_serve_stream_bf16(unsigned short port, &Gemma4Bf16Model model, &Gemma4Tokenizer tok,
                                    unsigned long maxNewTokens, float temperature) {
    int listenFd;
    unsafe { listenFd = tcp_listen_nb(port); }
    if (listenFd < 0) { return -1; }
    unsafe { fd_set_blocking(listenFd); }
    printf("gemma4 streaming server (bf16) listening on port %u -- GET /generate?prompt=<text>\n", (unsigned int)port);

    while (1) {
        int clientFd;
        unsafe { clientFd = tcp_accept_nb(listenFd); }
        if (clientFd < 0) { continue; }
        unsafe { fd_set_blocking(clientFd); }

        char buf[4096];
        long n;
        unsafe { n = __g4h_sock_read(clientFd, (void*)buf, 4095UL); }
        if (n <= 0L) {
            unsafe { __g4h_sock_close(clientFd); }
            continue;
        }
        unsafe { buf[n] = (char)0; }

        unsigned long lineLen = 0UL;
        unsafe {
            while (lineLen < (unsigned long)n && buf[lineLen] != '\r') { lineLen = lineLen + 1UL; }
        }

        struct String prompt;
        unsafe { prompt = __g4h_extract_prompt(buf, lineLen); }

        if (prompt.length() == 0UL) {
            const char* body = "Missing ?prompt= query parameter\n";
            struct String resp = string_new();
            resp.push("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\nContent-Length: ");
            unsafe { resp.push_uint((unsigned long long)str_len(body)); }
            resp.push("\r\n\r\n");
            unsafe { resp.push(body); }
            __g4h_write_all(clientFd, resp.as_ptr(), resp.length());
            resp.free();
            prompt.free();
            unsafe { __g4h_sock_close(clientFd); }
            continue;
        }

        printf("request: prompt=%s\n", prompt.as_ptr());

        struct Vec<int> encoded = gemma4_tok_encode(tok, prompt.as_ptr());
        prompt.free();
        unsigned long promptLen = encoded.length();
        int* promptIds;
        unsafe { promptIds = (int*)malloc(sizeof(int) * (promptLen + 1UL)); }
        unsafe {
            promptIds[0] = tok.bosId;
            unsigned long i = 0UL;
            while (i < promptLen) {
                promptIds[i + 1UL] = *encoded.at(i);
                i = i + 1UL;
            }
        }
        encoded.free();

        struct String headers = string_new();
        headers.push("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        __g4h_write_all(clientFd, headers.as_ptr(), headers.length());
        headers.free();

        struct __Gemma4StreamCtx ctx;
        ctx.fd = clientFd;
        ctx.tok = tok;
        struct Gemma4GenConfig cfg;
        cfg.maxNewTokens = maxNewTokens;
        cfg.temperature = temperature;
        unsafe { cfg.eosId = tok.eosId; }
        // Speculative (prompt-lookup) decoding is a strict drop-in here --
        // same signature, same callback contract, proven bit-identical
        // output to gemma4_bf16_generate (see tests/test_gemma4_bf16_
        // speculative.sc's exhaustive eosId sweep and tests/test_gemma4_
        // bf16_speculative_real.sc's real-checkpoint comparison), and it
        // degrades to the exact same one-token-at-a-time behavior
        // whenever a caller requests temperature>0 sampling (drafting
        // only ever activates for temperature<=0 -- see gemma4_generate.h).
        // A real ~1.8x latency win specifically for repetitive output
        // (RAG-style completions, code with repeated structure, etc.),
        // which streaming HTTP responses are exactly the use case for.
        unsafe { gemma4_bf16_generate_speculative(model, promptIds, promptLen + 1UL, cfg, (void*)&ctx, __g4h_stream_token); }
        unsafe { free((void*)promptIds); }

        __g4h_write_chunk(clientFd, "", 0UL);
        unsafe { __g4h_sock_close(clientFd); }
    }
}

} // namespace std
