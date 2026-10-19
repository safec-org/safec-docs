# io -- 입출력

`io` 모듈은 stdout/stderr로의 포맷된 출력, stdin 입력, 버퍼 포맷팅을
제공한다. `io_file` 모듈은 FILE\* 기반 파일 I/O를 제공한다. 둘 다
각각 `io.h`와 `io_file.h`를 통해 포함된다.

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
void print(const char* s);                // print string
void println(const char* s);              // print string + newline
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
void eprint(const char* s);
void eprintln(const char* s);
void eprint_int(long long v);
void eprint_float(double v);
```

### 버퍼 포맷팅 {#buffer-formatting}

모두 (NUL을 제외하고) 기록된 문자 수를 반환하거나, 오류 시 -1을 반환한다.

```c
int io_fmt_int(char* buf, int n, long long v);
int io_fmt_uint(char* buf, int n, unsigned long long v);
int io_fmt_float(char* buf, int n, double v);
int io_fmt_float_prec(char* buf, int n, double v, int prec);
int io_fmt_hex(char* buf, int n, unsigned long long v);
int io_fmt_str(char* buf, int n, const char* s);
```

### 입력 (stdin) {#input-stdin}

```c
int  io_getchar();                        // 문자 하나를 읽음; 끝에 도달하면 SC_EOF 반환
int  io_ungetc(int c);                    // 문자를 stdin으로 되돌려 놓음
int  io_read_line(char* buf, int n);      // 한 줄을 읽음 (최대 n-1자 + NUL)
int  io_read_token(char* buf, int n);     // 공백으로 구분된 토큰을 읽음
int  io_scan_int(long long* out);         // stdin에서 10진 정수를 파싱
int  io_scan_uint(unsigned long long* out); // stdin에서 부호 없는 정수를 파싱
int  io_scan_float(double* out);          // stdin에서 부동소수점을 파싱
```

`io_read_line`은 (NUL을 제외하고) 읽은 문자 수를 반환하거나, EOF/오류
시 -1을 반환한다.

`io_scan_*` 함수들은 성공 시 1, 오류 시 0을 반환한다.

### 파일 시스템 연산 {#file-system-operations}

```c
int   io_remove(const char* path);                     // delete a file
int   io_rename(const char* old_path, const char* new_path); // rename/move
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

핸들은 불투명 타입에 대한 `&File` 참조다 — 그 아래의 C `FILE`은 결코
노출되지 않는다. `file_open`은 `?&File`(실패 시 null)을 반환하므로 사용
전에 좁혀야 하며(`match`, `std::expect_ref`), 그 밖의 모든 함수는
non-null `&File`을 받는다. 바이트 버퍼는 `&unsigned char` /
`const &unsigned char`다.

### 열기 / 닫기 {#open-close}

```c
?&File file_open(const char* path, const char* mode); // "r", "w", "a", "rb", "wb", etc.
int   file_close(&File f);                             // returns 0 on success
```

### 읽기 {#read}

```c
unsigned long file_read(&File f, &unsigned char buf, unsigned long n);  // returns bytes read
int           file_getc(&File f);                               // returns char or -1
char*         file_gets(char* buf, int n, &File f);             // returns buf or NULL
```

### 쓰기 {#write}

```c
unsigned long file_write(&File f, const &unsigned char buf, unsigned long n); // returns bytes written
int           file_putc(int c, &File f);                              // returns c or -1
int           file_puts(const char* s, &File f);                      // returns non-negative on success
```

### 탐색 / 위치 확인 {#seek-tell}

```c
int       file_seek(&File f, long long offset, int whence);  // 0=SET, 1=CUR, 2=END
long long file_tell(&File f);                                  // current position, -1 on error
void      file_rewind(&File f);                                // rewind to start
```

### 상태 {#status}

```c
int file_eof(&File f);    // non-zero if last op hit EOF
int file_error(&File f);  // non-zero if error indicator set
int file_flush(&File f);  // flush buffered writes; 0 on success
```

### 편의 함수 {#convenience}

```c
long long file_size(&File f);  // file size in bytes, -1 on error
```

## 예제 {#example}

```c
#include <std/io.h>
#include <std/io_file.h>

int main() {
    // Console output
    std::println("Writing to file...");

    // File I/O
    match (std::file_open("output.txt", "w")) {
        case some(f): {
            std::file_puts("Hello from SafeC!\n", f);
            std::file_close(f);
        }
        case null: std::println("cannot open output.txt");
    }

    // Read it back
    match (std::file_open("output.txt", "r")) {
        case some(g): {
            char buf[256];
            unsafe { std::file_gets(buf, 256, g); }
            std::print("Read: ");
            unsafe { std::println(buf); }
            std::file_close(g);
        }
        case null: { }
    }

    return 0;
}
```
