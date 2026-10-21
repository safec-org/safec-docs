# Utilities & C Compatibility

Small single-header modules at the top of `std/`: number parsing and formatting, character classes, errors and signals, time, logging, panics, `Result`, checked arithmetic, the global heap, DMA buffers, and the C11/C23 compatibility headers.

```c
#include <std/convert.h>
#include <std/time.h>
// ... one header per module, or:
#include <std/prelude.h>
```

Bit manipulation (`bit.h`) and complex numbers (`complex.h`) are documented under [math](/stdlib/math). Fixed-width integer and limit headers (`stdint.h`, `stddef.h`, `stdbool.h`, `limits.h`, `float.h`, `inttypes.h`) only define types and constants; see [C Type and Limit Headers](#c-type-and-limit-headers) below.

::: tip Constants are functions
`errno.h`, `fenv.h`, `locale.h` and `signal.h` expose their constants as functions so they can be reached through the namespace: write `std::SIG_INT()`, not `SIG_INT`. Constants in `log.h`, `sys.h`, `stdckdint.h`, `heap.h` and `dma.h` are plain `#define`s.
:::

`?&int ok` parameters may be `null` when you don't need the success flag.

---

## convert — String ↔ Number {#convert-string-number}

```c
#include <std/convert.h>
```

```c
// String -> number. *ok = 1 on success; on failure *ok = 0 and the result is 0.
long long          str_to_int(const &char s, ?&int ok);
unsigned long long str_to_uint(const &char s, ?&int ok);
unsigned long long str_to_hex(const &char s, ?&int ok);   // "0x" prefix optional
double             str_to_float(const &char s, ?&int ok);

int str_is_int(const &char s);     // 1 if s is a valid decimal integer
int str_is_float(const &char s);   // 1 if s is a valid floating-point number

// Number -> heap string (null on OOM). Free the result with std::dealloc().
?&heap char int_to_str(long long v);
?&heap char uint_to_str(unsigned long long v);
?&heap char float_to_str(double v, int decimals);

// Numeric conversions
int       ll_to_int(long long v, &int out);   // 1 and *out = v if it fits in int, else 0
long long float_clamp_to_ll(double v, long long min, long long max); // clamp, then round
```

```c
#include <std/convert.h>

int main() {
    int ok = 0;
    long long n = std::str_to_int("1234", &ok);    // 1234, ok = 1
    std::str_to_int("12x", &ok);                    // ok = 0
    unsigned long long h = std::str_to_hex("0xff", null);  // 255
    return (n == 1234LL && h == 255ULL) ? 0 : 1;
}
```

---

## ctype — Character Classes {#ctype-character-classes}

```c
#include <std/ctype.h>
```

Locale-aware, like C's `<ctype.h>`. `c` must be in `0..255` or `-1` (EOF). Each predicate returns non-zero for true. For locale-independent ASCII checks, see [Encoding](/stdlib/encoding#ascii-ascii-h).

| Function | True for |
|----------|----------|
| `char_is_alpha(c)` | letter |
| `char_is_digit(c)` | `0`–`9` |
| `char_is_alnum(c)` | letter or digit |
| `char_is_xdigit(c)` | `0`–`9`, `a`–`f`, `A`–`F` |
| `char_is_space(c)` | space, tab, newline, CR, FF, VT |
| `char_is_blank(c)` | space or tab |
| `char_is_print(c)` | printable, including space |
| `char_is_graph(c)` | printable, excluding space |
| `char_is_punct(c)` | printable, not alphanumeric, not space |
| `char_is_ctrl(c)` | control character |
| `char_is_upper(c)` / `char_is_lower(c)` | upper / lower case letter |

`char_to_lower(c)` and `char_to_upper(c)` return the converted character, or `c` unchanged.

---

## errno — Error Codes {#errno-error-codes}

```c
#include <std/errno.h>
```

```c
int         errno_get();                 // current thread-local errno
void        errno_set(int code);
const &char errno_str(int code);         // static description (strerror)
void        errno_print(const &char prefix); // "prefix: <description>" to stderr
```

Constants (call them): `ERRNO_OK()`, `ERRNO_EPERM()`, `ERRNO_ENOENT()`, `ERRNO_EIO()`, `ERRNO_EBADF()`, `ERRNO_ENOMEM()`, `ERRNO_EACCES()`, `ERRNO_EBUSY()`, `ERRNO_EEXIST()`, `ERRNO_EINVAL()`, `ERRNO_ERANGE()`, `ERRNO_EAGAIN()`, `ERRNO_EWOULDBLOCK()`, `ERRNO_ETIMEDOUT()`.

Most codes have the same value on Linux, macOS and BSD. `EAGAIN`/`EWOULDBLOCK`/`ETIMEDOUT` differ (Linux 11/11/110, macOS/BSD 35/35/60), and Windows sockets don't report through `errno` at all. To ask whether a non-blocking socket call would have blocked, use `std::sock_would_block()` from [`sched/io_nb.h`](/stdlib/sched) instead of comparing `errno_get()`.

---

## fenv — Floating-Point Environment {#fenv-floating-point-environment}

```c
#include <std/fenv.h>
```

```c
int fenv_clear(int excepts);     // clear flags (FE_ALL_EXCEPT() clears all)
int fenv_test(int excepts);      // which of these flags are raised
int fenv_raise(int excepts);
int fenv_get_round();            // FE_* rounding mode
int fenv_set_round(int mode);    // 0 on success

// Whole environment, saved into / restored from a 32-byte buffer.
int fenv_save(&unsigned char env);
int fenv_restore(const &unsigned char env);
int fenv_save_clear(&unsigned char env);  // save, then clear all flags
```

| Rounding modes | Exception flags |
|----------------|-----------------|
| `FE_TONEAREST()`, `FE_DOWNWARD()`, `FE_UPWARD()`, `FE_TOWARDZERO()` | `FE_INVALID()`, `FE_DIVBYZERO()`, `FE_OVERFLOW()`, `FE_UNDERFLOW()`, `FE_INEXACT()`, `FE_ALL_EXCEPT()` |

The values are the target's own `<fenv.h>` values, which differ per architecture (x86, ARM/AArch64 and RISC-V each have their own); always pass the functions, never literal numbers.

---

## fmt — Formatting into Buffers {#fmt-formatting-into-buffers}

```c
#include <std/fmt.h>
```

Each function writes into `buf[0 .. cap-1]`, always NUL-terminates, and returns the characters written (not counting NUL), or `-1` on error.

```c
int fmt_int(&char buf, unsigned long cap, long long v);
int fmt_uint(&char buf, unsigned long cap, unsigned long long v);
int fmt_float(&char buf, unsigned long cap, double v, int decimals);
int fmt_hex(&char buf, unsigned long cap, unsigned long long v);        // lowercase
int fmt_hex_upper(&char buf, unsigned long cap, unsigned long long v);
int fmt_bool(&char buf, unsigned long cap, int v);                       // "true" / "false"

int fmt_str(&char dst, unsigned long cap, const &char src);    // copy; returns chars copied
int fmt_append(&char dst, unsigned long cap, const &char src); // returns new length, -1 if cap exceeded
```

```c
#include <std/fmt.h>
#include <std/io.h>

int main() {
    char buf[32];
    std::fmt_str(buf, 32UL, "x = ");
    char num[16];
    std::fmt_int(num, 16UL, 42LL);
    std::fmt_append(buf, 32UL, num);
    std::println(buf);          // x = 42
    return 0;
}
```

---

## heap — Global Heap {#heap-global-heap}

```c
#include <std/heap.h>
```

One heap API for both environments. Hosted builds wrap `malloc`/`free`/`realloc`. Freestanding builds manage a static `HEAP_SIZE`-byte buffer (default 1 MiB, override with `-DHEAP_SIZE=<bytes>`) with [TLSF](/stdlib/allocators), so every operation is O(1).

```c
void          heap_init();                                   // call once first (no-op when hosted)
&heap void    heap_alloc(unsigned long size);                // null on failure
void          heap_free(&heap void ptr);
&heap void    heap_realloc(&heap void ptr, unsigned long new_size); // null on failure; ptr stays valid
unsigned long heap_capacity();                               // bytes; 0 when hosted (unbounded)
```

---

## locale {#locale}

```c
#include <std/locale.h>
```

```c
// name: "" = system default, "C", "en_US.UTF-8", ... Returns the new locale name, or null.
const ?&char locale_set(int category, const &char name);
const ?&char locale_get(int category);
```

Categories: `LC_ALL()`, `LC_COLLATE()`, `LC_CTYPE()`, `LC_MONETARY()`, `LC_NUMERIC()`, `LC_TIME()`.

---

## log — Logging {#log-logging}

```c
#include <std/log.h>
```

```c
#define LOG_LEVEL_NONE   0
#define LOG_LEVEL_ERROR  1
#define LOG_LEVEL_WARN   2
#define LOG_LEVEL_INFO   3
#define LOG_LEVEL_DEBUG  4
// LOG_LEVEL defaults to LOG_LEVEL_INFO; set it with -DLOG_LEVEL=<n>.

typedef fn void(int level, const &char tag, const &char msg,
                const &char file, int line) LogBackend;

void log_set_backend(LogBackend backend);
void log_write(int level, const &char tag, const &char msg, const &char file, int line);
```

`log_set_backend` routes every message to your function (a UART, a ring buffer, a file).

The `LOG_E`/`LOG_W`/`LOG_I`/`LOG_D(tag, msg)` macros fill in `__FILE__`/`__LINE__` and compile to nothing below `LOG_LEVEL`, so `LOG_LEVEL=0` costs nothing. They are function-like macros, so they exist only in [compat preprocessor mode](/reference/preprocessor); in safe mode call `log_write` directly.

---

## panic — Panics and `expect_ref` {#panic-panics-and-expect-ref}

```c
#include <std/panic.h>
```

```c
typedef fn void(const &char msg, const &char file, int line) PanicHandler;

void panic_set_handler(PanicHandler handler);   // null restores the default
void panic_at(const &char msg, const &char file, int line);  // never returns

// r as &T, or panic with msg if it is null.
generic<T> &T expect_ref(?&T r, const &char msg);
```

The default handler loops forever on freestanding targets and calls `abort()` on hosted ones. `expect_ref` is the checked way to say "this can't be null here"; a cast `(&T)r` is rejected because it would skip the check. For absence you can recover from, use `match`.

`PANIC(msg)` and `PANIC_ASSERT(cond)` are available in [compat preprocessor mode](/reference/preprocessor).

---

## result — `Result<T, E>` {#result-result-t-e}

```c
#include <std/result.h>
```

A value or an error, stored inline (no heap allocation).

```c
generic<T, E>
struct Result {
    T   value;   // valid when is_ok == 1
    E   error;   // valid when is_ok == 0
    int is_ok;

    int ok() const;
    int err() const;
    ?&T get_ok();              // null for an error
    ?&E get_err();             // null for a success
    T   unwrap_or(T fallback) const;
};

generic<T, E> struct Result<T, E> result_ok(T val);
generic<T, E> struct Result<T, E> result_err(E err);
```

The variable's type supplies the type parameter the argument doesn't:

```c
#include <std/result.h>

int main() {
    struct Result<int, long long> r = std::result_ok(42);
    int v = r.get_ok().default(0);                // 42

    struct Result<int, long long> e = std::result_err(7LL);
    long long code = e.get_err().default(0LL);    // 7
    int fallback = e.unwrap_or(-1);               // -1

    return (v == 42 && code == 7LL && fallback == -1) ? 0 : 1;
}
```

---

## signal — POSIX Signals {#signal-posix-signals}

```c
#include <std/signal.h>
```

```c
?&void signal_handle(int sig, fn void(int) handler);   // previous handler (opaque); SIG_ERR on error
void  signal_default(int sig);
void  signal_ignore(int sig);
int   signal_raise(int sig);                   // to this process
int   signal_kill(int pid, int sig);           // to another process
void  signal_pause();                          // block until a signal arrives
```

Signal numbers (POSIX values): `SIG_HUP()`, `SIG_INT()`, `SIG_QUIT()`, `SIG_ILL()`, `SIG_ABRT()`, `SIG_FPE()`, `SIG_KILL()`, `SIG_SEGV()`, `SIG_PIPE()`, `SIG_ALRM()`, `SIG_TERM()`, `SIG_USR1()`, `SIG_USR2()`, `SIG_CHLD()`, `SIG_CONT()`, `SIG_STOP()`, `SIG_TSTP()`, `SIG_WINCH()`. `SIG_KILL` and `SIG_STOP` can't be caught. `SIG_USR1`, `SIG_USR2`, `SIG_CHLD`, `SIG_CONT`, `SIG_STOP` and `SIG_TSTP` have different numbers on Linux and on macOS/BSD; the functions return the right one for the target.

`signal_handle` returns the previous handler as an opaque `?&void`, since it may be one of the C library's `SIG_DFL`/`SIG_IGN` markers rather than a SafeC function.

---

## stdckdint — Checked Arithmetic {#stdckdint-checked-arithmetic}

```c
#include <std/stdckdint.h>
```

C23's `ckd_add`/`ckd_sub`/`ckd_mul`, one function per type. Each stores the wrapped result in `*result` and returns `CKD_OVERFLOW` (1) if the true result didn't fit, `CKD_OK` (0) otherwise.

```c
int ckd_add_i32(&int result, int a, int b);
int ckd_sub_i32(&int result, int a, int b);
int ckd_mul_i32(&int result, int a, int b);

int ckd_add_i64(&long long result, long long a, long long b);
int ckd_sub_i64(&long long result, long long a, long long b);
int ckd_mul_i64(&long long result, long long a, long long b);

int ckd_add_u32(&unsigned int result, unsigned int a, unsigned int b);
int ckd_sub_u32(&unsigned int result, unsigned int a, unsigned int b);
int ckd_mul_u32(&unsigned int result, unsigned int a, unsigned int b);

int ckd_add_u64(&unsigned long long result, unsigned long long a, unsigned long long b);
int ckd_sub_u64(&unsigned long long result, unsigned long long a, unsigned long long b);
int ckd_mul_u64(&unsigned long long result, unsigned long long a, unsigned long long b);
```

The language also has overflow operators built in; see [Overflow Operators](/reference/overflow).

---

## sys — Process, Environment, Sorting, Clocks {#sys-process-environment-sorting-clocks}

```c
#include <std/sys.h>
```

```c
#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1
#define RAND_MAX     2147483647

void        sys_exit(int code);
void        sys_abort();
int         sys_system(const &char cmd);
int         sys_atexit(fn void() func);
const ?&char sys_getenv(const &char name);      // null if unset

int         sys_rand();                         // 0 .. RAND_MAX
void        sys_srand(unsigned int seed);

int         sys_abs(int x);
long long   sys_llabs(long long x);

int         sys_atoi(const &char s);
long long   sys_atoll(const &char s);
double      sys_atof(const &char s);

// base 0 auto-detects a 0x / 0 prefix. If end is non-null it receives the first unparsed character.
long long          sys_strtoll(const &char s, ?&&const char end, int base);
unsigned long long sys_strtoull(const &char s, ?&&const char end, int base);
double             sys_strtod(const &char s, ?&&const char end);
float              sys_strtof(const &char s, ?&&const char end);

// Typed sort/search over n elements starting at first; cmp returns <0 / 0 / >0.
generic<T> void sys_qsort(&T first, unsigned long n, fn int(const &T, const &T) cmp);
generic<T> ?&T  sys_bsearch(const &T key, const &T first, unsigned long n,
                            fn int(const &T, const &T) cmp);   // match, or null

long long   sys_cpu_time_ns();
long long   sys_time_now_ns();
long long   sys_monotonic_ns();
```

`end` is an optional out-parameter: pass the address of a `const &char` to learn where parsing stopped, or `null`. For parsing with a success flag, prefer [`convert.h`](#convert-string-number).

```c
#include <std/sys.h>

static int cmp_int(const &int a, const &int b) { return *a - *b; }

int main() {
    int xs[5] = { 5, 3, 9, 1, 7 };
    std::sys_qsort(&xs[0], 5UL, cmp_int);       // 1 3 5 7 9
    int key = 7;
    match (std::sys_bsearch(&key, &xs[0], 5UL, cmp_int)) {
        case some(p): { return *p == 7 ? 0 : 1; }
        case null:    { return 1; }
    }
}
```

---

## time — Calendar and Clocks {#time-calendar-and-clocks}

```c
#include <std/time.h>
```

Broken-down times are 9-element `int` arrays, in C's `struct tm` order: `[0]` sec, `[1]` min, `[2]` hour, `[3]` day of month, `[4]` month (0–11), `[5]` years since 1900, `[6]` weekday (0 = Sunday), `[7]` day of year, `[8]` DST flag.

```c
long long time_now();                               // Unix seconds

void      time_gmtime(long long t, &int out);       // UTC; out has 9 elements
void      time_localtime(long long t, &int out);
long long time_mktime(&int tm_fields);              // local broken-down -> Unix seconds, -1 on error

// strftime / strptime formats. time_format returns bytes written (0 on failure); time_parse returns 1 on success.
unsigned long time_format(&char buf, unsigned long cap, const &char fmt, &int tm_fields);
int           time_parse(const &char s, const &char fmt, &int tm_fields);

double    time_diff(long long end, long long start);  // seconds

long long time_wall_ns();                           // CLOCK_REALTIME
long long time_mono_ns();                           // CLOCK_MONOTONIC — use for elapsed time
long long time_cpu_ns();                            // process CPU time
long long time_elapsed_ns(long long start, long long end);
double    time_ns_to_ms(long long ns);
void      time_sleep_ns(long long ns);

int time_days_in_month(int month, int year);        // month 0-11, full year (e.g. 2024)
int time_is_leap(int year);
```

```c
#include <std/time.h>
#include <std/io.h>

int main() {
    int tm[9];
    std::time_gmtime(std::time_now(), &tm[0]);
    char buf[32];
    std::time_format(&buf[0], 32UL, "%Y-%m-%d %H:%M:%S", &tm[0]);
    std::println(buf);

    long long t0 = std::time_mono_ns();
    std::time_sleep_ns(1000000LL);                  // 1 ms
    double ms = std::time_ns_to_ms(std::time_elapsed_ns(t0, std::time_mono_ns()));
    return ms >= 1.0 ? 0 : 1;
}
```

---

## dma — DMA Buffers and Channels {#dma-dma-buffers-and-channels}

```c
#include <std/dma.h>
```

Cache-coherent buffer ownership and a driver-agnostic DMA channel. Freestanding-safe; works with any allocator or static storage.

```c
#define DMA_ALIGN  64   // typical burst alignment (one cache line)

struct DmaBuffer {
    &unsigned char virt;  // CPU view of the buffer
    unsigned long phys;   // device address (0 = identity-mapped)
    unsigned long size;
    int           owner;  // 0 = CPU, 1 = device

    void          to_device();        // flush CPU cache, hand to device
    void          to_cpu();           // invalidate cache after the transfer, hand back
    int           cpu_accessible() const;
    unsigned long phys_at(unsigned long offset) const;
};

struct DmaBuffer dma_buf_wrap(&unsigned char virt, unsigned long phys, unsigned long size);

// One driver owns a channel, so its callback context is typed.
generic<T> struct DmaChannel {
    unsigned int id;
    int          busy;
    fn int(?&T, unsigned long, unsigned long, unsigned long) start_fn; // (ctx, src, dst, len) -> 0 on success
    fn int(?&T)  poll_fn;                                              // (ctx) -> 1 when complete
    int          has_poll;   // 0: wait() returns at once (synchronous engines)
    ?&T          ctx;

    int start(unsigned long src_phys, unsigned long dst_phys, unsigned long len); // -1 if busy
    int wait(unsigned int max_polls);   // 0 when idle, -1 on timeout
    int is_busy() const;
};

generic<T>
struct DmaChannel<T> dma_channel_new(unsigned int id,
                                     fn int(?&T, unsigned long, unsigned long, unsigned long) start_fn,
                                     fn int(?&T) poll_fn, ?&T ctx);

// Scatter-gather list, up to DMA_SG_MAX (16) entries.
struct DmaSgEntry { unsigned long phys; unsigned long len; };
struct DmaSgList {
    struct DmaSgEntry entries[DMA_SG_MAX];
    int               count;
    void          add(unsigned long phys, unsigned long len);
    void          clear();
    unsigned long total_len() const;
};
```

`virt` refers to the first byte of the buffer, which can live in any region (a static DMA pool, the heap, an arena).

---

## assert — Runtime Assertions {#assert-runtime-assertions}

```c
#include <std/assert.h>
```

```c
// On failure: prints "Assertion failed: <msg> at <file>:<line>" to stderr, then abort().
void runtime_assert(int cond, const &char msg);
void assert_true(int cond);                     // fixed message
```

`static_assert` is a language keyword and is always checked at compile time. Defining `NDEBUG` before the include turns `runtime_assert` and `assert_true` into no-ops in [compat preprocessor mode](/reference/preprocessor).

---

## C Type and Limit Headers {#c-type-and-limit-headers}

These headers only define types and constants, matching their C11/C23 counterparts.

| Header | Provides |
|--------|----------|
| `stdint.h` | `int8_t`…`int64_t`, `uint8_t`…`uint64_t`, `intptr_t`/`uintptr_t`, `intmax_t`/`uintmax_t`; `INT8_MIN`/`INT8_MAX` … `INT64_MIN`/`INT64_MAX`, `UINT8_MAX` … `UINT64_MAX`, `INTPTR_MIN`/`INTPTR_MAX`/`UINTPTR_MAX`, `PTRDIFF_MIN`/`PTRDIFF_MAX`, `SIZE_MAX`, `INTMAX_MIN`/`INTMAX_MAX`/`UINTMAX_MAX` |
| `stddef.h` | `size_t`, `ssize_t`, `ptrdiff_t`, `max_align_t`, `NULL`, `offsetof` — pointer-sized types are 32-bit on 32-bit targets (e.g. wasm32) and 64-bit elsewhere |
| `stdbool.h` | `bool`, `true`, `false` |
| `limits.h` | `CHAR_BIT`, `SCHAR_MIN`/`SCHAR_MAX`, `UCHAR_MAX`, `CHAR_MIN`/`CHAR_MAX`, `SHRT_MIN`/`SHRT_MAX`, `USHRT_MAX`, `INT_MIN`/`INT_MAX`, `UINT_MAX`, `LONG_MIN`/`LONG_MAX`, `ULONG_MAX`, `LLONG_MIN`/`LLONG_MAX`, `ULLONG_MAX`, `MB_LEN_MAX` |
| `float.h` | `FLT_`/`DBL_`/`LDBL_` × `MANT_DIG`, `DIG`, `MIN_EXP`, `MAX_EXP`, `EPSILON`, `MIN`, `MAX` (plus `FLT_`/`DBL_` `MIN_10_EXP`, `MAX_10_EXP`, `TRUE_MIN`); `FLT_RADIX`, `FLT_ROUNDS`, `FLT_EVAL_METHOD`, `DECIMAL_DIG` |
| `inttypes.h` | `printf`/`scanf` format strings: `PRId8`…`PRId64`, `PRIu*`, `PRIo*`, `PRIx*`, `PRIX*`, plus `MAX`/`PTR` variants and the matching `SCN*` macros |
