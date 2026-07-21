# io -- 입출력

`io` 모듈은 stdout/stderr로의 포맷된 출력, stdin 입력, 버퍼 포맷팅을
제공한다. `io_file` 모듈은 FILE\* 기반 파일 I/O를 제공한다. 둘 다
각각 `io.h`와 `io_file.h`를 통해 포함된다.

## io -- 콘솔 I/O {#io-console-io}

```c
#include "io.h"
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
void print(const char* s);                // 문자열 출력
void println(const char* s);              // 문자열 출력 + 개행
void print_char(int c);                   // 단일 문자 출력
void print_int(long long v);              // 부호 있는 정수 출력
void print_uint(unsigned long long v);    // 부호 없는 정수 출력
void print_float(double v);              // 부동소수점 출력
void print_hex(unsigned long long v);    // 16진수 출력 (0x...)
void print_oct(unsigned long long v);    // 8진수 출력
void print_ptr(const void* p);          // 포인터 주소 출력
void println_int(long long v);           // 정수 출력 + 개행
void println_uint(unsigned long long v); // 부호 없는 정수 출력 + 개행
void println_float(double v);           // 부동소수점 출력 + 개행
void println_char(int c);               // 문자 출력 + 개행
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
int   io_remove(const char* path);                     // 파일 삭제
int   io_rename(const char* old_path, const char* new_path); // 이름 변경/이동
void* io_tmpfile();                                     // 임시 파일 생성
```

### 플러시 {#flush}

```c
void flush_stdout();
void flush_stderr();
```

---

## io_file -- 파일 I/O {#io_file-file-io}

```c
#include "io_file.h"
```

FILE\*는 C의 `FILE` 타입을 SafeC에 노출하지 않기 위해 `void*`로
저장된다.

### 열기 / 닫기 {#open-close}

```c
void* file_open(const char* path, const char* mode);  // "r", "w", "a", "rb", "wb" 등
int   file_close(void* f);                             // 성공 시 0을 반환
```

### 읽기 {#read}

```c
unsigned long file_read(void* f, void* buf, unsigned long n);  // 읽은 바이트 수를 반환
int           file_getc(void* f);                               // 문자 또는 -1을 반환
char*         file_gets(char* buf, int n, void* f);             // buf 또는 NULL을 반환
```

### 쓰기 {#write}

```c
unsigned long file_write(void* f, const void* buf, unsigned long n); // 쓴 바이트 수를 반환
int           file_putc(int c, void* f);                              // c 또는 -1을 반환
int           file_puts(const char* s, void* f);                      // 성공 시 0 이상을 반환
```

### 탐색 / 위치 확인 {#seek-tell}

```c
int       file_seek(void* f, long long offset, int whence);  // 0=SET, 1=CUR, 2=END
long long file_tell(void* f);                                  // 현재 위치, 오류 시 -1
void      file_rewind(void* f);                                // 시작 지점으로 되감기
```

### 상태 {#status}

```c
int file_eof(void* f);    // 마지막 연산이 EOF에 도달했으면 0이 아닌 값
int file_error(void* f);  // 오류 표시자가 설정되어 있으면 0이 아닌 값
int file_flush(void* f);  // 버퍼링된 쓰기를 플러시; 성공 시 0
```

### 편의 함수 {#convenience}

```c
long long file_size(void* f);  // 파일 크기(바이트), 오류 시 -1
```

## 예제 {#example}

```c
#include "io.h"
#include "io_file.h"

int main() {
    // 콘솔 출력
    println("Writing to file...");

    // 파일 I/O
    void* f = file_open("output.txt", "w");
    if (f != 0) {
        file_puts("Hello from SafeC!\n", f);
        file_close(f);
    }

    // 다시 읽어오기
    void* g = file_open("output.txt", "r");
    if (g != 0) {
        char buf[256];
        file_gets(buf, 256, g);
        print("Read: ");
        println(buf);
        file_close(g);
    }

    return 0;
}
```
