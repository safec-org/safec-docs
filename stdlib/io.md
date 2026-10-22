# io -- Input/Output

The `io` module provides formatted output to stdout/stderr, stdin input, and buffer formatting. The `io_file` module provides FILE\*-based file I/O. Both are included via `io.h` and `io_file.h` respectively. `io_traits.h` has the [stream traits](#io-traits) and the helpers written once over every byte stream, and `io_buf.h` the [buffered streams](#buffered-io).

## io -- Console I/O {#io-console-io}

```c
#include <std/io.h>
```

### Constants {#constants}

```c
#define SC_SEEK_SET  0
#define SC_SEEK_CUR  1
#define SC_SEEK_END  2
#define SC_EOF       (-1)
```

### Formatted Output (stdout) {#formatted-output-stdout}

```c
void print(const &char s);                // print string
void println(const &char s);              // print string + newline
void print_char(int c);                   // print single character
void print_int(long long v);              // print signed integer
void print_uint(unsigned long long v);    // print unsigned integer
void print_float(double v);              // print floating-point
void print_hex(unsigned long long v);    // print hex (0x...)
void print_oct(unsigned long long v);    // print octal
generic<T> void print_ptr(const ?&T p);  // print the address a reference points at
void println_int(long long v);           // print int + newline
void println_uint(unsigned long long v); // print uint + newline
void println_float(double v);           // print float + newline
void println_char(int c);               // print char + newline
```

### Formatted Output (stderr) {#formatted-output-stderr}

```c
void eprint(const &char s);
void eprintln(const &char s);
void eprint_int(long long v);
void eprint_float(double v);
```

### Buffer Formatting {#buffer-formatting}

All return the number of characters written (excluding NUL), or -1 on error.

```c
int io_fmt_int(&char buf, int n, long long v);
int io_fmt_uint(&char buf, int n, unsigned long long v);
int io_fmt_float(&char buf, int n, double v);
int io_fmt_float_prec(&char buf, int n, double v, int prec);
int io_fmt_hex(&char buf, int n, unsigned long long v);
int io_fmt_str(&char buf, int n, const &char s);
```

### Input (stdin) {#input-stdin}

```c
int  io_getchar();                        // read single char; returns SC_EOF on end
int  io_ungetc(int c);                    // push char back onto stdin
int  io_read_line(&char buf, int n);      // read line (up to n-1 chars + NUL)
int  io_read_token(&char buf, int n);     // read whitespace-delimited token
int  io_scan_int(&long long out);           // parse decimal int from stdin
int  io_scan_uint(&unsigned long long out); // parse unsigned int from stdin
int  io_scan_float(&double out);            // parse float from stdin
```

`io_read_line` returns the number of characters read (excluding NUL), or -1 on EOF/error.

`io_scan_*` functions return 1 on success, 0 on error.

### File System Operations {#file-system-operations}

```c
int   io_remove(const &char path);                     // delete a file
int   io_rename(const &char old_path, const &char new_path); // rename/move
?&File io_tmpfile();                                    // create temporary file; null on failure
```

### Flush {#flush}

```c
void flush_stdout();
void flush_stderr();
```

---

## io_file -- File I/O {#io_file-file-io}

```c
#include <std/io_file.h>
```

Handles are `&File` references to an opaque type — the C `FILE` underneath is never exposed. The [static method](/reference/functions#static-methods) `File::open` returns `?&File` (null on failure), so it must be narrowed (`match`, `std::expect_ref`) before use; every operation is then a method on the non-null `&File` (`f.read(buf, n)`, `f.close()`, ...). From outside `namespace std`, call it as `std::File::open(...)`. Byte buffers are `&unsigned char` / `const &unsigned char`.

`File` is a [`SeekableStream`](#io-traits) and a `Flusher`, so the generic stream helpers and [`BufReader` / `BufWriter`](#buffered-io) work on it.

```c
struct File {
    static ?&File open(const &char path, const &char mode);  // "r", "w", "a", "rb", "wb", etc.
    int       close();                                       // 0 on success

    long long read(&unsigned char buf, unsigned long n);       // bytes read, 0 at EOF, -1 on error
    int       getc();                                          // char, or -1 at EOF/error
    ?&char    gets(&char buf, int n);                          // buf, or null at EOF/error

    long long write(const &unsigned char buf, unsigned long n); // bytes written, -1 on error
    int       putc(int c);                                      // c, or -1 on error
    int       puts(const &char s);                              // non-negative on success (no newline)

    int       seek(long long offset, int whence);  // 0=SET, 1=CUR, 2=END; 0 on success
    long long tell();                              // current position, -1 on error
    void      rewind();                            // back to the start

    int       eof();                               // non-zero if the last op hit EOF
    int       error();                             // non-zero if the error indicator is set
    int       flush();                             // flush buffered writes; 0 on success

    long long size();                              // file size in bytes, -1 on error

    // Whole files (static).
    static int read_to_string(const &char path, &String out);           // appends the contents; 1 on success
    static int write_bytes(const &char path, const &char data, unsigned long n); // create/truncate + write; 1 on success
};
```

### Open / Close {#open-close}

`File::open(path, mode)` takes the C `fopen` modes and returns null on failure; `f.close()` returns 0 on success. A handle must not be used after `close()`.

### Read {#read}

`f.read(buf, n)` reads at most `n` bytes and returns the count: fewer than `n` near the end, 0 at end of file, -1 when an error stopped it before any byte was read. To read exactly `n` bytes, use [`read_exact`](#io-traits). `f.getc()` returns one character or -1; `f.gets(buf, n)` reads up to `n - 1` characters and NUL-terminates.

### Write {#write}

`f.write(buf, n)` returns the count written (fewer than `n` on an error part-way), or -1 when an error stopped it before any byte; [`write_all`](#io-traits) loops until everything is written. `f.putc(c)` and `f.puts(s)` write one character and a string.

### Seek / Tell {#seek-tell}

`f.seek(offset, whence)` takes `SC_SEEK_SET` / `SC_SEEK_CUR` / `SC_SEEK_END` (0 / 1 / 2) and returns 0 on success; `f.tell()` returns the position or -1; `f.rewind()` goes back to the start.

### Status {#status}

`f.eof()` and `f.error()` report the end-of-file and error indicators; `f.flush()` pushes buffered writes to the OS and returns 0 on success.

### Convenience {#convenience}

`f.size()` seeks to the end, reads the position and seeks back: the size in bytes, or -1 on error. [`stream_size`](#io-traits) does the same for any `Seekable`.

### Whole files {#whole-files}

`File::read_to_string(path, &out)` appends a whole file to a `String` (binary-safe; `out` stays NUL-terminated) and returns 1, or 0 if the file can't be opened or a read fails. `File::write_bytes(path, data, n)` creates or truncates the file and writes `n` bytes, returning 1 only when every byte was written and the file closed cleanly. For listing, creating and copying directories, see [dir](/stdlib/dir).

```c
struct String text = String::new();
if (std::File::read_to_string("notes.md", &text)) {
    text.push("\n- one more line\n");
    std::File::write_bytes("notes.md", text.as_ptr(), text.len);
}
text.free();
```

## io_traits -- Readers and writers {#io-traits}

```c
#include <std/io_traits.h>
```

std's blocking byte streams share one `read` / `write` shape. `io_traits.h` names it as small [traits](/reference/generics#composing-traits), one capability each, composed into the roles generic code asks for, and writes the loops every caller needs around them once: read until the buffer is full, write until everything is out, copy one stream into another. Each call is resolved at compile time.

```c
trait Reader {
    long long read(&unsigned char buf, unsigned long n);         // count, 0 at end, -1 on error
}
trait Writer {
    long long write(const &unsigned char buf, unsigned long n);  // count (may be short), -1 on error
}
trait Seekable {
    int       seek(long long offset, int whence);                // 0 = start, 1 = current, 2 = end; 0 on success
    long long tell();                                            // -1 on error
}
trait Flusher {
    int flush();                                                 // 0 on success
}
trait Stream         : Reader + Writer {}
trait SeekableStream : Stream + Seekable {}
```

| Trait | Composed of | Satisfied by |
|---|---|---|
| `Reader` | `read(buf, n)` | `File`, `Pipe`, `TcpStream`, `BufReader<R>` |
| `Writer` | `write(buf, n)` | `File`, `Pipe`, `TcpStream`, `BufWriter<W>` |
| `Seekable` | `seek(offset, whence)`, `tell()` | `File` |
| `Flusher` | `flush()` | `File`, `BufWriter<W>` |
| `Stream` | `Reader + Writer` | `File`, [`Pipe`](/stdlib/ipc#pipe-anonymous-pipes) (reads the read end, writes the write end), [`TcpStream`](/stdlib/http#tcp-streams) (`std/http/http.h`, a connected blocking TCP socket) |
| `SeekableStream` | `Stream + Seekable` | `File` |

| Function / struct | Bound | Does |
|---|---|---|
| `read_exact(&r, buf, n)` | `Reader` | reads exactly `n` bytes, looping over short reads: 1 once all arrived, 0 if the stream ended or failed first (`buf` then holds what did arrive) |
| `write_all(&w, buf, n)` | `Writer` | writes all `n` bytes, looping over short writes: 1 on success, 0 if the stream failed (or accepted nothing) first |
| `copy_stream(&r, &w)` | `Reader`, `Writer` | copies `r` into `w` until `r` ends: the byte count, or -1 if a read or write failed |
| `stream_size(&s)` | `Seekable` | the stream's length (its end offset), leaving the position where it was; -1 on error |
| [`BufReader<R>`](#buffered-io) | `Reader` | a buffer in front of a reader, with `read_until`; a `Reader` itself |
| [`BufWriter<W>`](#buffered-io) | `Writer` | a buffer in front of a writer; a `Writer` and `Flusher` itself |

```c
generic<R: Reader>             int       read_exact(&R r, &unsigned char buf, unsigned long n);
generic<W: Writer>             int       write_all(&W w, const &unsigned char buf, unsigned long n);
generic<R: Reader, W: Writer>  long long copy_stream(&R r, &W w);
generic<S: Seekable>           long long stream_size(&S s);
```

The example writes `byte_sum` once against `Reader` and runs it over a pipe and a file:

```c
#include <std/io.h>
#include <std/io_traits.h>
#include <std/ipc/pipe.h>

// Sums every byte a stream yields until its end; -1 on a read error.
generic<R: Reader>
long long byte_sum(&R r) {
    unsigned char chunk[256];
    long long sum = 0LL;
    while (1) {
        long long k = r.read(&chunk[0], 256UL);
        if (k == 0LL) { return sum; }
        if (k < 0LL) { return -1LL; }
        for (long long i = 0LL; i < k; i = i + 1LL) { sum = sum + (long long)chunk[i]; }
    }
    return sum;
}

int main() {
    unsigned char msg[3] = { 1, 2, 3 };

    // Through a pipe: write all, close the write end, read to the end.
    struct Pipe p;
    if (p.init() != 0) { return 1; }
    if (!std::write_all(&p, &msg[0], 3UL)) { return 1; }
    p.close_write();
    long long a = byte_sum(&p);                 // R = Pipe
    p.close_read();

    // Through a file: the same function, another type.
    match (std::File::open("/tmp/sum.bin", "w+b")) {
        case null: { return 1; }
        case some(f): {
            std::write_all(f, &msg[0], 3UL);
            f.seek(0LL, 0);
            long long b = byte_sum(f);          // R = File
            long long n = std::stream_size(f);  // Seekable
            f.close();
            std::println_int(a + b);            // 12
            return (a == 6LL && b == 6LL && n == 3LL) ? 0 : 1;
        }
    }
    return 1;
}
```

Traits are structural: your own type is a `Reader` as soon as it has `read` with that signature (and a `Writer` with `write`, and so on), and then the helpers, the buffered streams and any `generic<R: Reader>` code take it. Two std types deliberately don't conform: `WsConn` is message-based (`recv` yields whole frames), and the freestanding `TcpConn` (`std/net`) is a non-blocking segment buffer whose `read` returning 0 means "nothing yet", not end of stream, which the loops above would misread.

Copying one file into another is one call:

```c
#include <std/io_file.h>
#include <std/io_traits.h>

// Copies one file into another; 0 on success.
int copy_file(const &char from, const &char to) {
    match (std::File::open(from, "rb")) {
        case null: return 1;
        case some(in): {
            match (std::File::open(to, "wb")) {
                case null: { in.close(); return 1; }
                case some(out): {
                    long long n = std::copy_stream(in, out);
                    in.close();
                    out.close();
                    return n < 0LL ? 1 : 0;
                }
            }
        }
    }
    return 1;
}
```

## io_buf -- Buffered streams {#buffered-io}

```c
#include <std/io_buf.h>
```

`BufReader<R>` and `BufWriter<W>` are [bounded generic structs](/reference/generics#bounded-generic-structs) that borrow a stream and put an `IO_BUF_SIZE`-byte (4096) buffer in front of it, so many small reads or writes become one call on the stream. Each is a `Reader` / `Writer` itself, so `read_exact`, `write_all`, `copy_stream` and any `generic<R: Reader>` code take it like the stream it wraps. HTTP and WebSocket read their headers and bodies through a `BufReader<TcpStream>` and write WebSocket frames through a `BufWriter<TcpStream>` (see [HTTP](/stdlib/http)).

```c
generic<R: Reader>
struct BufReader {
    &R inner;                                              // the stream read from (borrowed)

    static struct BufReader<R> over(&R inner);
    long long     read(&unsigned char out, unsigned long n); // from the buffer first; 0 at end, -1 on error
    int           read_until(&String out, const &char term);
    unsigned long buffered() const;                          // bytes a read can return without touching the stream
};

generic<W: Writer>
struct BufWriter {
    &W inner;                                              // the stream written to (borrowed)

    static struct BufWriter<W> over(&W inner);
    long long write(const &unsigned char data, unsigned long n); // takes all n bytes: n, or -1 if the stream failed
    int       flush();                                           // writes out the buffer: 0, or -1 (buffer dropped either way)
};
```

`read_until(&out, term)` appends bytes to `out` up to and including the first occurrence of `term` (a non-empty string: `"\n"` reads a line, `"\r\n\r\n"` an HTTP header block) and returns 1, or returns 0 when the stream ended or failed first (`out` then holds what did arrive). Bytes after `term` stay buffered for the next read. A read of at least `IO_BUF_SIZE` bytes with nothing buffered, and a write that doesn't fit, go straight to the stream.

A `BufWriter` holds what was written until `flush()` (or until its buffer fills): call `flush()` before the stream is used directly or closed.

```c
#include <std/io.h>
#include <std/io_buf.h>
#include <std/collections/string.h>

int main() {
    // Write three lines through a BufWriter: one write to the file at flush().
    match (std::File::open("/tmp/lines.txt", "w")) {
        case null: { return 1; }
        case some(f): {
            struct BufWriter<File> out = std::BufWriter::over(f);
            const &char text = "alpha\nbeta\ngamma\n";
            unsafe { std::write_all(&out, (const &unsigned char)(const unsigned char*)text, 17UL); }
            if (out.flush() != 0) { return 1; }  // before the file is closed
            f.close();
        }
    }

    // Read them back one line at a time.
    int lines = 0;
    match (std::File::open("/tmp/lines.txt", "r")) {
        case null: { return 1; }
        case some(f): {
            struct BufReader<File> in = std::BufReader::over(f);
            while (1) {
                struct String line = std::String::new();
                if (!in.read_until(&line, "\n")) { line.free(); break; }
                std::print(line.as_ptr());       // "alpha\n", "beta\n", "gamma\n"
                line.free();
                lines = lines + 1;
            }
            f.close();
        }
    }
    return lines == 3 ? 0 : 1;
}
```

## Example {#example}

```c
#include <std/io.h>
#include <std/io_file.h>

int main() {
    // Console output
    std::println("Writing to file...");

    // File I/O
    match (std::File::open("output.txt", "w")) {
        case some(f): {
            f.puts("Hello from SafeC!\n");
            f.close();
        }
        case null: std::println("cannot open output.txt");
    }

    // Read it back
    match (std::File::open("output.txt", "r")) {
        case some(g): {
            char buf[256];
            unsafe { g.gets(buf, 256); }
            std::print("Read: ");
            unsafe { std::println(buf); }
            g.close();
        }
        case null: { }
    }

    return 0;
}
```
