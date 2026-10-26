# io -- 입출력

`io` 모듈은 stdout/stderr로의 포맷된 출력, stdin 입력, 버퍼 포맷팅을
제공합니다. `io_file` 모듈은 FILE\* 기반 파일 I/O를 제공합니다. 둘 다
각각 `io.h`와 `io_file.h`를 통해 포함됩니다. `io_traits.h`에는
[스트림 트레이트](#io-traits)와 모든 바이트 스트림에 대해 한 번만 작성된
헬퍼가, `io_buf.h`에는 [버퍼링 스트림](#buffered-io)이 있습니다.

## io -- 콘솔 I/O {#io-console-io}

```c
#include <std/io.h>
```

### 상수 {#constants}

```c
#define SC_SEEK_SET  0
#define SC_SEEK_CUR  1
#define SC_SEEK_END  2
#define SC_EOF       (-1)
```

### 포맷된 출력 (stdout) {#formatted-output-stdout}

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

### 포맷된 출력 (stderr) {#formatted-output-stderr}

```c
void eprint(const &char s);
void eprintln(const &char s);
void eprint_int(long long v);
void eprint_float(double v);
```

### 버퍼 포맷팅 {#buffer-formatting}

모두 (NUL을 제외하고) 기록된 문자 수를 반환하거나, 오류 시 -1을 반환합니다.

```c
int io_fmt_int([]char buf, long long v);
int io_fmt_uint([]char buf, unsigned long long v);
int io_fmt_float([]char buf, double v);
int io_fmt_float_prec([]char buf, double v, int prec);
int io_fmt_hex([]char buf, unsigned long long v);
int io_fmt_str([]char buf, const &char s);
```

### 입력 (stdin) {#input-stdin}

```c
int  io_getchar();                        // 문자 하나를 읽음; 끝에 도달하면 SC_EOF 반환
int  io_ungetc(int c);                    // 문자를 stdin으로 되돌려 놓음
int io_read_line([]char buf);      // 한 줄을 읽음 (최대 n-1자 + NUL)
int io_read_token([]char buf);     // 공백으로 구분된 토큰을 읽음
int  io_scan_int(&long long out);           // stdin에서 10진 정수를 파싱
int  io_scan_uint(&unsigned long long out); // stdin에서 부호 없는 정수를 파싱
int  io_scan_float(&double out);            // stdin에서 부동소수점을 파싱
```

`io_read_line`은 (NUL을 제외하고) 읽은 문자 수를 반환하거나, EOF/오류
시 -1을 반환합니다.

`io_scan_*` 함수들은 성공 시 1, 오류 시 0을 반환합니다.

### 파일 시스템 연산 {#file-system-operations}

```c
int   io_remove(const &char path);                     // delete a file
int   io_rename(const &char old_path, const &char new_path); // rename/move
?&File io_tmpfile();                                    // create temporary file; null on failure
```

### 플러시 {#flush}

```c
void flush_stdout();
void flush_stderr();
```

---

## io_file -- 파일 I/O {#io_file-file-io}

```c
#include <std/io_file.h>
```

핸들은 불투명 타입에 대한 `&File` 참조입니다 — 그 아래의 C `FILE`은 결코
노출되지 않습니다. [정적 메서드](/ko/reference/functions#static-methods)
`File::open`은 `?&File`(실패 시 null)을 반환하므로 사용 전에 좁혀야
하며(`match`, `std::expect_ref`), 그 뒤의 모든 연산은 non-null `&File`의
메서드입니다(`f.read(buf)`, `f.close()`, ...). `namespace std`
바깥에서는 `std::File::open(...)`으로 호출합니다. 바이트 버퍼는
`&unsigned char` / `const &unsigned char`입니다.

`File`은 [`SeekableStream`](#io-traits)이자 `Flusher`이므로, 제네릭 스트림
헬퍼와 [`BufReader` / `BufWriter`](#buffered-io)가 그대로 동작합니다.

```c
struct File {
    static ?&File open(const &char path, const &char mode);  // "r", "w", "a", "rb", "wb", etc.
    int       close();                                       // 0 on success

    long long read([]unsigned char buf);       // bytes read, 0 at EOF, -1 on error
    int       getc();                                          // char, or -1 at EOF/error
    ?&char gets([]char buf);                          // buf, or null at EOF/error

    long long write([]const unsigned char buf); // bytes written, -1 on error
    int       putc(int c);                                      // c, or -1 on error
    int       puts(const &char s);                              // non-negative on success (no newline)

    int       seek(long long offset, int whence);  // 0=SET, 1=CUR, 2=END; 0 on success
    long long tell();                              // current position, -1 on error
    void      rewind();                            // back to the start

    int       eof();                               // non-zero if the last op hit EOF
    int       error();                             // non-zero if the error indicator is set
    int       flush();                             // flush buffered writes; 0 on success

    long long size();                              // file size in bytes, -1 on error

    // 파일 전체 (정적 메서드).
    static int read_to_string(const &char path, &String out);           // 내용을 덧붙임, 성공 시 1
    static int write_bytes(const &char path, []const unsigned char data); // 생성/잘라내기 후 쓰기, 성공 시 1
};
```

### 열기 / 닫기 {#open-close}

`File::open(path, mode)`는 C `fopen`의 모드를 받으며 실패하면 null을
반환합니다. `f.close()`는 성공 시 0을 반환합니다. `close()` 이후에는
핸들을 사용하면 안 됩니다.

### 읽기 {#read}

`f.read(buf)`는 최대 `buf.len`바이트를 읽고 그 수를 반환합니다: 끝에 가까우면
`n`보다 적고, 파일 끝에서는 0, 한 바이트도 읽기 전에 오류가 나면 -1입니다.
정확히 `n`바이트를 읽으려면 [`read_exact`](#io-traits)를 사용합니다.
`f.getc()`는 문자 하나 또는 -1을 반환하고, `f.gets(buf)`는 최대
`buf.len - 1`개의 문자를 읽어 NUL로 끝맺습니다.

### 쓰기 {#write}

`f.write(((const unsigned char*)buf)[0..n])`은 쓴 바이트 수(도중에 오류가 나면 `n`보다 적음)를,
한 바이트도 쓰기 전에 오류가 나면 -1을 반환합니다.
[`write_all`](#io-traits)은 전부 쓸 때까지 반복합니다. `f.putc(c)`와
`f.puts(s)`는 문자 하나와 문자열을 씁니다.

### 탐색 / 위치 확인 {#seek-tell}

`f.seek(offset, whence)`는 `SC_SEEK_SET` / `SC_SEEK_CUR` / `SC_SEEK_END`
(0 / 1 / 2)를 받아 성공 시 0을 반환합니다. `f.tell()`은 위치 또는 -1을
반환하고, `f.rewind()`는 처음으로 돌아갑니다.

### 상태 {#status}

`f.eof()`와 `f.error()`는 파일 끝 표시와 오류 표시를 알려 줍니다.
`f.flush()`는 버퍼에 쌓인 쓰기를 OS로 내보내며 성공 시 0을 반환합니다.

### 편의 함수 {#convenience}

`f.size()`는 끝으로 이동해 위치를 읽고 원래 위치로 돌아옵니다: 크기(바이트)
또는 오류 시 -1입니다. [`stream_size`](#io-traits)는 모든 `Seekable`에
대해 같은 일을 합니다.

### 파일 전체 {#whole-files}

`File::read_to_string(path, &out)`은 파일 전체를 `String`에 덧붙이고(바이너리 안전, `out`은 계속 NUL로 끝남) 1을 반환하며, 파일을 열 수 없거나 읽기에 실패하면 0을 반환합니다. `File::write_bytes(path, data, n)`은 파일을 생성하거나 잘라낸 뒤 `n` 바이트를 쓰고, 모든 바이트를 쓰고 파일이 깨끗이 닫혔을 때만 1을 반환합니다. 디렉터리 나열, 생성, 복사는 [dir](/ko/stdlib/dir)을 참고하십시오.

```c
struct String text = String::new();
if (std::File::read_to_string("notes.md", &text)) {
    text.push("\n- one more line\n");
    std::File::write_bytes("notes.md", text.as_bytes());
}
text.free();
```

## io_traits -- 리더와 라이터 {#io-traits}

```c
#include <std/io_traits.h>
```

std의 블로킹 바이트 스트림은 모두 같은 `read` / `write` 형태를 공유합니다.
`io_traits.h`는 이를 기능 하나당 하나씩 작은
[트레이트](/ko/reference/generics#composing-traits)로 이름 붙이고 제네릭
코드가 요구하는 역할로 합성하며, 호출하는 쪽마다 필요한 반복문 — 버퍼가 찰
때까지 읽기, 전부 나갈 때까지 쓰기, 한 스트림을 다른 스트림으로 복사하기 —
을 한 번만 작성해 둡니다. 각 호출은 컴파일 타임에 결정됩니다.

```c
trait Reader {
    long long read([]unsigned char buf);         // count, 0 at end, -1 on error
}
trait Writer {
    long long write([]const unsigned char buf);  // count (may be short), -1 on error
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

| 트레이트 | 구성 | 만족하는 타입 |
|---|---|---|
| `Reader` | `read(buf)` (`[]unsigned char`) | `File`, `Pipe`, `TcpStream`, `TlsStream`, `BufReader<R>` |
| `Writer` | `write(buf)` (`[]const unsigned char`) | `File`, `Pipe`, `TcpStream`, `TlsStream`, `BufWriter<W>` |
| `Seekable` | `seek(offset, whence)`, `tell()` | `File` |
| `Flusher` | `flush()` | `File`, `BufWriter<W>` |
| `Stream` | `Reader + Writer` | `File`, [`Pipe`](/ko/stdlib/ipc#pipe-anonymous-pipes)(읽기 쪽 끝에서 읽고 쓰기 쪽 끝에 씀), [`TcpStream`](/ko/stdlib/http#tcp-streams)(`std/http/http.h`, 연결된 블로킹 TCP 소켓) |
| `SeekableStream` | `Stream + Seekable` | `File` |

| 함수 / 구조체 | 바운드 | 하는 일 |
|---|---|---|
| `read_exact(&r, ((unsigned char*)buf)[0..n])` | `Reader` | 짧은 읽기를 반복해 정확히 `n`바이트를 읽음: 모두 도착하면 1, 스트림이 먼저 끝나거나 실패하면 0(`buf`에는 도착한 만큼 들어 있음) |
| `write_all(&w, ((const unsigned char*)buf)[0..n])` | `Writer` | 짧은 쓰기를 반복해 `n`바이트를 모두 씀: 성공하면 1, 스트림이 먼저 실패하면(또는 아무것도 받지 않으면) 0 |
| `copy_stream(&r, &w)` | `Reader`, `Writer` | `r`이 끝날 때까지 `w`로 복사: 복사한 바이트 수, 읽기나 쓰기가 실패하면 -1 |
| `stream_size(&s)` | `Seekable` | 위치는 그대로 둔 채 스트림의 길이(끝 오프셋)를 반환, 오류 시 -1 |
| [`BufReader<R>`](#buffered-io) | `Reader` | 리더 앞에 두는 버퍼, `read_until` 제공. 그 자신도 `Reader` |
| [`BufWriter<W>`](#buffered-io) | `Writer` | 라이터 앞에 두는 버퍼. 그 자신도 `Writer`이자 `Flusher` |

```c
generic<R: Reader> int read_exact(&R r, []unsigned char buf);
generic<W: Writer> int write_all(&W w, []const unsigned char buf);
generic<R: Reader, W: Writer>  long long copy_stream(&R r, &W w);
generic<S: Seekable>           long long stream_size(&S s);
```

아래 예제는 `byte_sum`을 `Reader`에 대해 한 번만 작성하고 파이프와 파일
위에서 실행합니다:

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
        long long k = r.read(chunk[0..256UL]);
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
    if (!std::write_all(&p, msg[0..3])) { return 1; }
    p.close_write();
    long long a = byte_sum(&p);                 // R = Pipe
    p.close_read();

    // Through a file: the same function, another type.
    match (std::File::open("/tmp/sum.bin", "w+b")) {
        case null: { return 1; }
        case some(f): {
            std::write_all(f, msg[0..3]);
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

트레이트는 구조적입니다: 여러분의 타입은 그 시그니처의 `read`를 갖추는
순간 `Reader`가 되고(`write`가 있으면 `Writer`, 나머지도 마찬가지), 그때부터
헬퍼, 버퍼링 스트림, 모든 `generic<R: Reader>` 코드가 이를 받아들입니다.
std 타입 중 두 가지는 의도적으로 따르지 않습니다: `WsConn`은 메시지
기반이며(`recv`는 프레임 전체를 내놓음), 프리스탠딩 `TcpConn`(`std/net`)은
논블로킹 세그먼트 버퍼여서 `read`가 0을 반환하면 스트림 끝이 아니라 "아직
없음"을 뜻하므로 위의 반복문이 이를 잘못 해석하게 됩니다.

한 파일을 다른 파일로 복사하는 일은 호출 한 번이면 됩니다:

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

## io_buf -- 버퍼링 스트림 {#buffered-io}

```c
#include <std/io_buf.h>
```

`BufReader<R>`와 `BufWriter<W>`는 스트림을 빌려 그 앞에
`IO_BUF_SIZE`바이트(4096) 버퍼를 두는
[바운드가 있는 제네릭 구조체](/ko/reference/generics#bounded-generic-structs)
입니다. 덕분에 여러 번의 작은 읽기나 쓰기가 스트림에 대한 호출 한 번이
됩니다. 각각 그 자신도 `Reader` / `Writer`이므로 `read_exact`, `write_all`,
`copy_stream`과 모든 `generic<R: Reader>` 코드가 감싼 스트림처럼 받아들입니다.
HTTP와 WebSocket은 헤더와 본문을 `BufReader<TcpStream>`으로 읽고 WebSocket
프레임을 `BufWriter<TcpStream>`으로 씁니다([HTTP](/ko/stdlib/http) 참고).

```c
generic<R: Reader>
struct BufReader {
    &R inner;                                              // the stream read from (borrowed)

    static struct BufReader<R> over(&R inner);
    long long read([]unsigned char out);     // from the buffer first; 0 at end, -1 on error
    int           read_until(&String out, const &char term);
    unsigned long buffered() const;                          // bytes a read can return without touching the stream
};

generic<W: Writer>
struct BufWriter {
    &W inner;                                              // the stream written to (borrowed)

    static struct BufWriter<W> over(&W inner);
    long long write([]const unsigned char data); // takes all of data: data.len, or -1 if the stream failed
    int       flush();                                           // writes out the buffer: 0, or -1 (buffer dropped either way)
};
```

`read_until(&out, term)`은 `term`(비어 있지 않은 문자열: `"\n"`은 한 줄,
`"\r\n\r\n"`은 HTTP 헤더 블록)이 처음 나타나는 곳까지(그것을 포함해)
바이트를 `out`에 덧붙이고 1을 반환하며, 그 전에 스트림이 끝나거나 실패하면
0을 반환합니다(`out`에는 도착한 만큼 들어 있음). `term` 뒤의 바이트는 다음
읽기를 위해 버퍼에 남습니다. 버퍼가 비어 있을 때 `IO_BUF_SIZE`바이트 이상을
읽는 요청과, 버퍼에 들어가지 않는 쓰기는 스트림으로 바로 갑니다.

`BufWriter`는 `flush()`를 호출할 때까지(또는 버퍼가 찰 때까지) 쓴 내용을
붙잡아 둡니다: 스트림을 직접 쓰거나 닫기 전에 `flush()`를 호출합니다.

```c
#include <std/io.h>
#include <std/io_buf.h>
#include <std/str.h>
#include <std/collections/string.h>

int main() {
    // Write three lines through a BufWriter: one write to the file at flush().
    match (std::File::open("/tmp/lines.txt", "w")) {
        case null: { return 1; }
        case some(f): {
            struct BufWriter<File> out = std::BufWriter::over(f);
            const &char text = "alpha\nbeta\ngamma\n";
            std::write_all(&out, str_bytes(text));
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

## 예제 {#example}

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
            g.gets(buf[..]);
            std::print("Read: ");
            unsafe { std::println(buf); }
            g.close();
        }
        case null: { }
    }

    return 0;
}
```
