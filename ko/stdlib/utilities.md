# 유틸리티와 C 호환

`std/` 최상위의 작은 단일 헤더 모듈들입니다: 숫자 파싱과 서식화, 문자 분류, 오류와 시그널, 시간, 로깅, 패닉, `Result`, 검사된 산술, 전역 힙, DMA 버퍼, 그리고 C11/C23 호환 헤더.

```c
#include <std/convert.h>
#include <std/time.h>
// ... one header per module, or:
#include <std/prelude.h>
```

비트 조작(`bit.h`)과 복소수(`complex.h`)는 [math](/ko/stdlib/math)에 문서화되어 있습니다. 고정 폭 정수와 한계값 헤더(`stdint.h`, `stddef.h`, `stdbool.h`, `limits.h`, `float.h`, `inttypes.h`)는 타입과 상수만 정의합니다. 아래의 [C 타입과 한계값 헤더](#c-type-and-limit-headers)를 참고하십시오.

::: tip 상수는 함수입니다
`errno.h`, `fenv.h`, `locale.h`, `signal.h`는 네임스페이스를 통해 접근할 수 있도록 상수를 함수로 제공합니다: `SIG_INT`가 아니라 `std::SIG_INT()`라고 씁니다. `log.h`, `sys.h`, `stdckdint.h`, `heap.h`, `dma.h`의 상수는 일반 `#define`입니다.
:::

`?&int ok` 매개변수는 성공 플래그가 필요 없으면 `null`을 넘겨도 됩니다.

---

## convert — 문자열 ↔ 숫자 {#convert-string-number}

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

## ctype — 문자 분류 {#ctype-character-classes}

```c
#include <std/ctype.h>
```

C의 `<ctype.h>`처럼 로케일을 따릅니다. `c`는 `0..255` 또는 `-1`(EOF)이어야 합니다. 각 판별 함수는 참이면 0이 아닌 값을 반환합니다. 로케일과 무관한 ASCII 검사는 [인코딩](/ko/stdlib/encoding#ascii-ascii-h)을 참고하십시오.

| 함수 | 참인 경우 |
|----------|----------|
| `char_is_alpha(c)` | 문자 |
| `char_is_digit(c)` | `0`–`9` |
| `char_is_alnum(c)` | 문자 또는 숫자 |
| `char_is_xdigit(c)` | `0`–`9`, `a`–`f`, `A`–`F` |
| `char_is_space(c)` | 공백, 탭, 줄바꿈, CR, FF, VT |
| `char_is_blank(c)` | 공백 또는 탭 |
| `char_is_print(c)` | 출력 가능, 공백 포함 |
| `char_is_graph(c)` | 출력 가능, 공백 제외 |
| `char_is_punct(c)` | 출력 가능하고 영숫자도 공백도 아님 |
| `char_is_ctrl(c)` | 제어 문자 |
| `char_is_upper(c)` / `char_is_lower(c)` | 대문자 / 소문자 |

`char_to_lower(c)`와 `char_to_upper(c)`는 변환된 문자를, 해당 없으면 `c`를 그대로 반환합니다.

---

## errno — 오류 코드 {#errno-error-codes}

```c
#include <std/errno.h>
```

```c
int         errno_get();                 // current thread-local errno
void        errno_set(int code);
const &char errno_str(int code);         // static description (strerror)
void        errno_print(const &char prefix); // "prefix: <description>" to stderr
```

상수(호출해서 사용): `ERRNO_OK()`, `ERRNO_EPERM()`, `ERRNO_ENOENT()`, `ERRNO_EIO()`, `ERRNO_EBADF()`, `ERRNO_ENOMEM()`, `ERRNO_EACCES()`, `ERRNO_EBUSY()`, `ERRNO_EEXIST()`, `ERRNO_EINVAL()`, `ERRNO_ERANGE()`, `ERRNO_EAGAIN()`, `ERRNO_EWOULDBLOCK()`, `ERRNO_ETIMEDOUT()`.

대부분의 코드는 Linux, macOS, BSD에서 값이 같습니다. `EAGAIN`/`EWOULDBLOCK`/`ETIMEDOUT`은 다르며(Linux 11/11/110, macOS/BSD 35/35/60), Windows 소켓은 `errno`로 보고하지 않습니다. 논블로킹 소켓 호출이 블록될 뻔했는지 확인하려면 `errno_get()`과 비교하지 말고 [`sched/io_nb.h`](/ko/stdlib/sched)의 `std::sock_would_block()`을 씁니다.

---

## fenv — 부동소수점 환경 {#fenv-floating-point-environment}

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

| 반올림 모드 | 예외 플래그 |
|----------------|-----------------|
| `FE_TONEAREST()`, `FE_DOWNWARD()`, `FE_UPWARD()`, `FE_TOWARDZERO()` | `FE_INVALID()`, `FE_DIVBYZERO()`, `FE_OVERFLOW()`, `FE_UNDERFLOW()`, `FE_INEXACT()`, `FE_ALL_EXCEPT()` |

값은 대상의 `<fenv.h>` 값이며 아키텍처마다 다릅니다(x86, ARM/AArch64, RISC-V가 각자 다른 값을 씁니다). 숫자 리터럴이 아니라 항상 이 함수들을 전달합니다.

---

## fmt — 버퍼로 서식화 {#fmt-formatting-into-buffers}

```c
#include <std/fmt.h>
```

각 함수는 `buf[0 .. cap-1]`에 쓰고, 항상 NUL로 종료하며, 쓴 문자 수(NUL 제외)나 오류 시 `-1`을 반환합니다.

```c
int fmt_int([]char buf, long long v);
int fmt_uint([]char buf, unsigned long long v);
int fmt_float([]char buf, double v, int decimals);
int fmt_hex([]char buf, unsigned long long v);        // lowercase
int fmt_hex_upper([]char buf, unsigned long long v);
int fmt_bool([]char buf, int v);                       // "true" / "false"

int fmt_str([]char dst, const &char src);    // copy; returns chars copied
int fmt_append([]char dst, const &char src); // returns new length, -1 if cap exceeded
```

```c
#include <std/fmt.h>
#include <std/io.h>

int main() {
    char buf[32];
    std::fmt_str(buf[..], "x = ");
    char num[16];
    std::fmt_int(num[..], 42LL);
    std::fmt_append(buf[..], num);
    std::println(buf);          // x = 42
    return 0;
}
```

---

## heap — 전역 힙 {#heap-global-heap}

```c
#include <std/heap.h>
```

두 환경에서 같은 힙 API입니다. 호스티드 빌드는 `malloc`/`free`/`realloc`을 감쌉니다. 프리스탠딩 빌드는 `HEAP_SIZE`바이트 정적 버퍼(기본 1 MiB, `-DHEAP_SIZE=<bytes>`로 변경)를 [TLSF](/ko/stdlib/allocators)로 관리하므로 모든 연산이 O(1)입니다.

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

카테고리: `LC_ALL()`, `LC_COLLATE()`, `LC_CTYPE()`, `LC_MONETARY()`, `LC_NUMERIC()`, `LC_TIME()`.

---

## log — 로깅 {#log-logging}

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

`log_set_backend`는 모든 메시지를 사용자 함수(UART, 링 버퍼, 파일)로 보냅니다.

`LOG_E`/`LOG_W`/`LOG_I`/`LOG_D(tag, msg)` 매크로는 `__FILE__`/`__LINE__`을 채워 주며 `LOG_LEVEL` 아래에서는 아무것도 남기지 않으므로 `LOG_LEVEL=0`은 비용이 없습니다. 함수형 매크로라서 [호환 전처리기 모드](/ko/reference/preprocessor)에서만 존재하며, 안전 모드에서는 `log_write`를 직접 호출합니다.

---

## panic — 패닉과 `expect_ref` {#panic-panics-and-expect-ref}

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

기본 핸들러는 프리스탠딩 타깃에서 무한 루프를, 호스티드 타깃에서 `abort()`를 호출합니다. `expect_ref`는 "여기서는 null일 수 없다"를 검사와 함께 말하는 방법입니다. 캐스트 `(&T)r`은 검사를 건너뛰므로 거부됩니다. 회복 가능한 부재에는 `match`를 씁니다.

`PANIC(msg)`와 `PANIC_ASSERT(cond)`는 [호환 전처리기 모드](/ko/reference/preprocessor)에서 쓸 수 있습니다.

---

## result — `Result<T, E>` {#result-result-t-e}

```c
#include <std/result.h>
```

값 또는 오류를 인라인으로 저장합니다(힙 할당 없음).

```c
generic<T, E>
struct Result {
    T   value;   // valid when is_ok == 1
    E   error;   // valid when is_ok == 0
    int is_ok;

    static struct Result<T, E> new_ok(T val);    // Result::new_ok(v)
    static struct Result<T, E> new_err(E err);   // Result::new_err(e)

    int ok() const;
    int err() const;
    ?&T get_ok();              // null for an error
    ?&E get_err();             // null for a success
    T   unwrap_or(T fallback) const;
};
```

인자가 주지 못하는 타입 매개변수는 변수의 타입이 정합니다:

```c
#include <std/result.h>

int main() {
    struct Result<int, long long> r = std::Result::new_ok(42);
    int v = r.get_ok().default(0);                // 42

    struct Result<int, long long> e = std::Result::new_err(7LL);
    long long code = e.get_err().default(0LL);    // 7
    int fallback = e.unwrap_or(-1);               // -1

    return (v == 42 && code == 7LL && fallback == -1) ? 0 : 1;
}
```

---

## signal — POSIX 시그널 {#signal-posix-signals}

```c
#include <std/signal.h>
```

```c
int   signal_handle(int sig, fn void(int) handler);   // 0, or -1 for an invalid or uncatchable signal
void  signal_default(int sig);
void  signal_ignore(int sig);
int   signal_raise(int sig);                   // to this process
int   signal_kill(int pid, int sig);           // to another process
void  signal_pause();                          // block until a signal arrives
```

시그널 번호(POSIX 값): `SIG_HUP()`, `SIG_INT()`, `SIG_QUIT()`, `SIG_ILL()`, `SIG_ABRT()`, `SIG_FPE()`, `SIG_KILL()`, `SIG_SEGV()`, `SIG_PIPE()`, `SIG_ALRM()`, `SIG_TERM()`, `SIG_USR1()`, `SIG_USR2()`, `SIG_CHLD()`, `SIG_CONT()`, `SIG_STOP()`, `SIG_TSTP()`, `SIG_WINCH()`. `SIG_KILL`과 `SIG_STOP`은 잡을 수 없습니다. `SIG_USR1`, `SIG_USR2`, `SIG_CHLD`, `SIG_CONT`, `SIG_STOP`, `SIG_TSTP`는 Linux와 macOS/BSD에서 번호가 다르며, 함수가 대상에 맞는 번호를 반환합니다.

`signal_handle`은 성공하면 0을, 잡을 수 없는 시그널이면 -1을 반환합니다. 이전 핸들러는 돌려주지 않습니다(SafeC 함수가 아니라 C 라이브러리의 `SIG_DFL`/`SIG_IGN` 표식일 수 있기 때문입니다). 처리 방식을 되돌리려면 `signal_default` / `signal_ignore`를 쓰세요.

---

## stdckdint — 검사된 산술 {#stdckdint-checked-arithmetic}

```c
#include <std/stdckdint.h>
```

C23의 `ckd_add`/`ckd_sub`/`ckd_mul`을 타입마다 함수 하나로 제공합니다. 각 함수는 감싼 결과를 `*result`에 저장하고, 실제 결과가 맞지 않으면 `CKD_OVERFLOW`(1)를, 아니면 `CKD_OK`(0)를 반환합니다.

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

언어에도 오버플로 연산자가 내장되어 있습니다. [오버플로 연산자](/ko/reference/overflow)를 참고하십시오.

---

## sys — 프로세스, 환경, 정렬, 시계 {#sys-process-environment-sorting-clocks}

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

`end`는 선택적 출력 매개변수입니다: 파싱이 멈춘 위치를 알고 싶으면 `const &char` 변수의 주소를, 아니면 `null`을 넘깁니다. 성공 플래그가 있는 파싱에는 [`convert.h`](#convert-string-number)가 낫습니다.

```c
#include <std/sys.h>

static int cmp_int(const &int a, const &int b) { return *a - *b; }

int main() {
    int xs[5] = { 5, 3, 9, 1, 7 };
    std::sys_qsort(xs[..], cmp_int);       // 1 3 5 7 9
    int key = 7;
    match (std::sys_bsearch(&key, xs[..], cmp_int)) {
        case some(i): { return xs[i] == 7 ? 0 : 1; }   // i is the index found
        case none:    { return 1; }
    }
}
```

---

## time — 달력과 시계 {#time-calendar-and-clocks}

```c
#include <std/time.h>
```

분해된 시간은 C의 `struct tm` 필드를 이름으로 가진 `struct TimeFields`입니다: `sec`(초), `min`(분), `hour`(시), `mday`(일), `mon`(월, 0–11), `year`(1900년 이후 연도), `wday`(요일, 0 = 일요일), `yday`(연중 일), `isdst`(서머타임 플래그).

```c
long long time_now();                               // Unix seconds

struct TimeFields { int sec; int min; int hour; int mday; int mon; int year; int wday; int yday; int isdst; };

struct TimeFields time_gmtime(long long t);         // UTC
struct TimeFields time_localtime(long long t);
long long time_mktime(const &TimeFields tm);        // local broken-down -> Unix seconds, -1 on error

// strftime / strptime formats. time_format returns bytes written (0 on failure); time_parse returns 1 on success.
unsigned long time_format([]char buf, const &char fmt, const &TimeFields tm);
int           time_parse(const &char s, const &char fmt, &TimeFields tm);

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
    struct TimeFields tm = std::time_gmtime(std::time_now());
    char buf[32];
    std::time_format(buf[..], "%Y-%m-%d %H:%M:%S", &tm);
    std::println(buf);

    long long t0 = std::time_mono_ns();
    std::time_sleep_ns(1000000LL);                  // 1 ms
    double ms = std::time_ns_to_ms(std::time_elapsed_ns(t0, std::time_mono_ns()));
    return ms >= 1.0 ? 0 : 1;
}
```

---

## dma — DMA 버퍼와 채널 {#dma-dma-buffers-and-channels}

```c
#include <std/dma.h>
```

캐시 일관성 버퍼 소유권과 드라이버에 독립적인 DMA 채널입니다. 프리스탠딩에서 안전하며 어떤 얼로케이터나 정적 저장소와도 동작합니다.

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

    // Wraps an existing allocation; phys 0 = identity-mapped.
    static struct DmaBuffer wrap([]unsigned char virt, unsigned long phys);
};

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

    // T is inferred from the callbacks: DmaChannel::new(1U, start, poll, ctx)
    static struct DmaChannel<T> new(unsigned int id,
                                    fn int(?&T, unsigned long, unsigned long, unsigned long) start_fn,
                                    fn int(?&T) poll_fn, ?&T ctx);
};

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

`virt`는 버퍼의 첫 바이트를 가리키며, 버퍼는 어느 리전에든(정적 DMA 풀, 힙, 아레나) 있을 수 있습니다.

---

## assert — 런타임 어서션 {#assert-runtime-assertions}

```c
#include <std/assert.h>
```

```c
// On failure: prints "Assertion failed: <msg> at <file>:<line>" to stderr, then abort().
void runtime_assert_at(int cond, const &char msg, const &char file, int line);
// The same without a location: a function can't see its call site.
void runtime_assert(int cond, const &char msg);
void assert_true(int cond);                     // no message
```

[호환 전처리기 모드](/ko/reference/preprocessor)에서 `runtime_assert`와 `assert_true`는 호출자의 `__FILE__`과 `__LINE__`을 `runtime_assert_at`에 넘기는 매크로이며, include 전에 `NDEBUG`를 정의하면 아무 동작도 하지 않게 됩니다. 안전 모드에서 위치를 남기려면 `runtime_assert_at(cond, msg, __FILE__, __LINE__)`을 호출합니다. `static_assert`는 언어 키워드이며 항상 컴파일 타임에 검사됩니다.

---

## C 타입과 한계값 헤더 {#c-type-and-limit-headers}

이 헤더들은 C11/C23 대응 헤더와 같은 타입과 상수만 정의합니다.

| 헤더 | 제공 |
|--------|----------|
| `stdint.h` | `int8_t`…`int64_t`, `uint8_t`…`uint64_t`, `intptr_t`/`uintptr_t`, `intmax_t`/`uintmax_t`; `INT8_MIN`/`INT8_MAX` … `INT64_MIN`/`INT64_MAX`, `UINT8_MAX` … `UINT64_MAX`, `INTPTR_MIN`/`INTPTR_MAX`/`UINTPTR_MAX`, `PTRDIFF_MIN`/`PTRDIFF_MAX`, `SIZE_MAX`, `INTMAX_MIN`/`INTMAX_MAX`/`UINTMAX_MAX` |
| `stddef.h` | `size_t`, `ssize_t`, `ptrdiff_t`, `max_align_t`, `NULL`, `offsetof` — 포인터 크기 타입은 32비트 타깃(예: wasm32)에서 32비트, 그 외에는 64비트 |
| `stdbool.h` | `bool`, `true`, `false` |
| `limits.h` | `CHAR_BIT`, `SCHAR_MIN`/`SCHAR_MAX`, `UCHAR_MAX`, `CHAR_MIN`/`CHAR_MAX`, `SHRT_MIN`/`SHRT_MAX`, `USHRT_MAX`, `INT_MIN`/`INT_MAX`, `UINT_MAX`, `LONG_MIN`/`LONG_MAX`, `ULONG_MAX`, `LLONG_MIN`/`LLONG_MAX`, `ULLONG_MAX`, `MB_LEN_MAX` |
| `float.h` | `FLT_`/`DBL_`/`LDBL_` × `MANT_DIG`, `DIG`, `MIN_EXP`, `MAX_EXP`, `EPSILON`, `MIN`, `MAX` (그리고 `FLT_`/`DBL_` `MIN_10_EXP`, `MAX_10_EXP`, `TRUE_MIN`); `FLT_RADIX`, `FLT_ROUNDS`, `FLT_EVAL_METHOD`, `DECIMAL_DIG` |
| `inttypes.h` | `printf`/`scanf` 서식 문자열: `PRId8`…`PRId64`, `PRIu*`, `PRIo*`, `PRIx*`, `PRIX*`, `MAX`/`PTR` 변형, 그리고 대응하는 `SCN*` 매크로 |
