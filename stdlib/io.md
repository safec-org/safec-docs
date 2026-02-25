# io -- Input/Output

The `io` module provides formatted output to stdout/stderr, stdin input, and buffer formatting. The `io_file` module provides FILE\*-based file I/O. Both are included via `io.h` and `io_file.h` respectively.

## io -- Console I/O

```c
#include "io.h"
```

### Constants

```c
#define SC_SEEK_SET  0
#define SC_SEEK_CUR  1
#define SC_SEEK_END  2
#define SC_EOF       (-1)
```

### Formatted Output (stdout)

```c
void print(const char* s);                // print string
void println(const char* s);              // print string + newline
void print_char(int c);                   // print single character
void print_int(long long v);              // print signed integer
void print_uint(unsigned long long v);    // print unsigned integer
void print_float(double v);              // print floating-point
void print_hex(unsigned long long v);    // print hex (0x...)
void print_oct(unsigned long long v);    // print octal
void print_ptr(const void* p);          // print pointer address
void println_int(long long v);           // print int + newline
void println_uint(unsigned long long v); // print uint + newline
void println_float(double v);           // print float + newline
void println_char(int c);               // print char + newline
```

### Formatted Output (stderr)

```c
void eprint(const char* s);
void eprintln(const char* s);
void eprint_int(long long v);
void eprint_float(double v);
```

### Buffer Formatting

All return the number of characters written (excluding NUL), or -1 on error.

```c
int io_fmt_int(char* buf, int n, long long v);
int io_fmt_uint(char* buf, int n, unsigned long long v);
int io_fmt_float(char* buf, int n, double v);
int io_fmt_float_prec(char* buf, int n, double v, int prec);
int io_fmt_hex(char* buf, int n, unsigned long long v);
int io_fmt_str(char* buf, int n, const char* s);
```

### Input (stdin)

```c
int  io_getchar();                        // read single char; returns SC_EOF on end
int  io_ungetc(int c);                    // push char back onto stdin
int  io_read_line(char* buf, int n);      // read line (up to n-1 chars + NUL)
int  io_read_token(char* buf, int n);     // read whitespace-delimited token
int  io_scan_int(long long* out);         // parse decimal int from stdin
int  io_scan_uint(unsigned long long* out); // parse unsigned int from stdin
int  io_scan_float(double* out);          // parse float from stdin
```

`io_read_line` returns the number of characters read (excluding NUL), or -1 on EOF/error.

`io_scan_*` functions return 1 on success, 0 on error.

### File System Operations

```c
int   io_remove(const char* path);                     // delete a file
int   io_rename(const char* old_path, const char* new_path); // rename/move
void* io_tmpfile();                                     // create temporary file
```

### Flush

```c
void flush_stdout();
void flush_stderr();
```

---

## io_file -- File I/O

```c
#include "io_file.h"
```

FILE\* is stored as `void*` to avoid exposing the C `FILE` type to SafeC.

### Open / Close

```c
void* file_open(const char* path, const char* mode);  // "r", "w", "a", "rb", "wb", etc.
int   file_close(void* f);                             // returns 0 on success
```

### Read

```c
unsigned long file_read(void* f, void* buf, unsigned long n);  // returns bytes read
int           file_getc(void* f);                               // returns char or -1
char*         file_gets(char* buf, int n, void* f);             // returns buf or NULL
```

### Write

```c
unsigned long file_write(void* f, const void* buf, unsigned long n); // returns bytes written
int           file_putc(int c, void* f);                              // returns c or -1
int           file_puts(const char* s, void* f);                      // returns non-negative on success
```

### Seek / Tell

```c
int       file_seek(void* f, long long offset, int whence);  // 0=SET, 1=CUR, 2=END
long long file_tell(void* f);                                  // current position, -1 on error
void      file_rewind(void* f);                                // rewind to start
```

### Status

```c
int file_eof(void* f);    // non-zero if last op hit EOF
int file_error(void* f);  // non-zero if error indicator set
int file_flush(void* f);  // flush buffered writes; 0 on success
```

### Convenience

```c
long long file_size(void* f);  // file size in bytes, -1 on error
```

## Example

```c
#include "io.h"
#include "io_file.h"

int main() {
    // Console output
    println("Writing to file...");

    // File I/O
    void* f = file_open("output.txt", "w");
    if (f != 0) {
        file_puts("Hello from SafeC!\n", f);
        file_close(f);
    }

    // Read it back
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
