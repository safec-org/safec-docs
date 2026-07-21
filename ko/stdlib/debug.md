# 디버깅 & 프로파일링

SafeC는 성능 측정, 코드 커버리지 추적, 하드웨어 디버그 통합을 위해 `std/debug/`에 세 가지 디버그 모듈을 제공합니다.

```c
#include "debug/debug.h"  // 마스터 헤더: perf + coverage + jtag
```

SafeC 컴파일러는 `--g` 옵션과 함께 호출되면 DWARF 디버그 정보도 내보냅니다.

| 플래그 | 출력 |
|------|--------|
| `--g lines` | 함수당 DICompileUnit, DISubprogram, 구문당 DILocation |
| `--g full` | `lines`의 전체 내용 + 지역 변수당 DILocalVariable + `dbg.declare` |
| *(없음)* | 디버그 메타데이터 없음 |

---

## perf — 하드웨어 성능 카운터 {#perf-hardware-performance-counters}

```c
#include "debug/perf.h"
```

`PerfCounter`는 마이크로초 이하 단위의 타이밍을 위해 아키텍처별 사이클 카운터를 읽습니다.

### 사이클 소스 {#cycle-sources}

| 아키텍처 | 명령어 |
|---|---|
| x86-64 | `rdtsc` (EDX:EAX) |
| AArch64 | `mrs %0, cntvct_el0` |
| RISC-V | `csrr %0, cycle` |
| 기타 | 0 (하드웨어 카운터 없음) |

### 구조체 {#struct}

```c
struct PerfCounter {
    unsigned long long start_val;
    unsigned long long end_val;
    unsigned long long freq_hz;  // 초당 CPU 사이클 수

    void               start();
    void               stop();
    unsigned long long ticks() const;   // end_val - start_val
    unsigned long long ns() const;      // ticks / freq_hz * 1e9
}
```

### 생성자 {#constructor}

```c
PerfCounter perf_counter_init();
```

**호스티드**: `POSIX clock()` 기준 약 10ms 동안 스핀하며 그 구간의 CPU 사이클 수를 세어 `freq_hz`를 보정합니다.

**프리스탠딩**: `freq_hz = 0`으로 설정합니다. `ns()`는 0을 반환합니다.

### 예제 {#example}

```c
#include "debug/perf.h"
#include "io.h"

int main() {
    struct PerfCounter pc = perf_counter_init();

    pc.start();
    // 측정할 작업
    for (int i = 0; i < 1000000; i++) {}
    pc.stop();

    print("ticks: "); println_int(pc.ticks());
    print("ns:    "); println_int(pc.ns());
    return 0;
}
```

---

## coverage — 소스 레벨 코드 커버리지 {#coverage-source-level-code-coverage}

```c
#include "debug/coverage.h"
```

호출 지점을 단일 매크로로 계측합니다. 각 지점이 몇 번 도달했는지 추적합니다. 최대 1024개 지점까지 지원합니다.

### 전역 인스턴스 {#global-instance}

```c
extern struct Coverage coverage;
void coverage_init();  // 모든 지점을 0으로 초기화 (프로그램 시작 시 호출)
```

### 구조체 {#struct-1}

```c
struct CovSite {
    const char*   file;
    int           line;
    unsigned long count;
}

struct Coverage {
    CovSite       sites[COV_MAX_SITES];  // 1024
    unsigned int  used;

    int           register_site(const char* file, int line);
    void          hit(int idx);
    unsigned long get(int idx) const;
    unsigned long covered_count() const;
    unsigned long coverage_pct() const;  // 정수 0-100
    void          reset();
    void          report();              // 지점별 HIT/MISS 출력 (호스티드 전용)
}
```

### COV_SITE() 매크로 {#cov_site-macro}

계측하고 싶은 지점에 `COV_SITE();`를 배치하세요. (`static int` 가드를 통해) 한 번만 등록되며, 호출될 때마다 카운터를 증가시킵니다.

```c
int clamp(int v, int lo, int hi) {
    COV_SITE();          // 이 진입점을 계측
    if (v < lo) { COV_SITE(); return lo; }
    if (v > hi) { COV_SITE(); return hi; }
    return v;
}
```

### 예제 {#example-1}

```c
#include "debug/coverage.h"
#include "io.h"

int clamp(int v, int lo, int hi) {
    COV_SITE();
    if (v < lo) { COV_SITE(); return lo; }
    if (v > hi) { COV_SITE(); return hi; }
    return v;
}

int main() {
    coverage_init();

    clamp(5,  0, 10);   // 중간 분기
    clamp(-1, 0, 10);   // lo 분기
    clamp(15, 0, 10);   // hi 분기

    coverage.report();
    print("coverage: ");
    println_int(coverage.coverage_pct());
    return 0;
}
```

출력 예시:
```
[cov] /path/clamp.sc:3 HIT (3)
[cov] /path/clamp.sc:4 HIT (1)
[cov] /path/clamp.sc:5 HIT (1)
coverage: 100
```

---

## jtag — 하드웨어 디버그 헬퍼 {#jtag-hardware-debug-helpers}

```c
#include "debug/jtag.h"
```

아키텍처 조건부 브레이크포인트, 세미호스팅, Cortex-M ITM 스티뮬러스 포트 출력을 제공합니다. UART가 아직 준비되지 않은 초기 부트업 단계에서 유용합니다.

### 브레이크포인트 {#breakpoints}

```c
void debug_break();
```

네이티브 브레이크포인트 명령어를 내보냅니다.

| 아키텍처 | 명령어 |
|---|---|
| AArch64 | `brk #0` |
| ARM / Thumb | `bkpt #0` |
| x86-64 | `int3` |
| RISC-V | `ebreak` |

### 세미호스팅 (ARM / AArch64) {#semihosting-arm-aarch64}

세미호스팅을 사용하면 Cortex-M 또는 Cortex-A 타깃이 UART 없이 디버그 연결(J-Link, OpenOCD)을 통해 호스트 디버거로 출력을 보낼 수 있습니다.

```c
void debug_semihost_puts(const char* s);  // SYS_WRITE0 — NUL로 종료된 문자열
void debug_semihost_putc(char c);         // SYS_WRITEC — 단일 문자
```

x86-64와 RISC-V에서는 **아무 동작도 하지 않습니다(no-op)**.

### ITM 스티뮬러스 포트 (Cortex-M 전용) {#itm-stimulus-ports-cortex-m-only}

Instrumentation Trace Macrocell — SWO 핀을 통해 데이터를 전송하여 J-Link 등의 프로브로 캡처합니다.

```c
void itm_enable_port(int port);           // ITM TER (0xE0000E00)에 비트 설정
void itm_putc(int port, char c);          // 스티뮬러스 레지스터에 바이트 쓰기
void itm_put32(int port, unsigned int v); // 32비트 워드 쓰기
```

`itm_putc`와 `itm_put32`는 쓰기 전에 스티뮬러스 레지스터가 준비될 때까지(비트 0 == 1) 폴링합니다. ITM이 비활성화되어 있거나 포트가 활성화되지 않은 경우 아무 동작도 하지 않습니다.

### DBG_ASSERT {#dbg_assert}

```c
DBG_ASSERT(cond);
// cond가 false이면 debug_break()를 호출합니다.
// NDEBUG가 정의된 경우 완전히 컴파일에서 제외됩니다.
```

### 예제 {#example-2}

```c
#include "debug/jtag.h"

void uart_init(struct Uart* u) {
    DBG_ASSERT(u != 0);

    // UART가 준비되기 전 세미호스트를 통한 초기 부트업 출력
    debug_semihost_puts("uart_init called\n");

    // UART가 준비된 후 가벼운 트레이싱을 위해 ITM으로 전환
    itm_enable_port(0);
    itm_putc(0, 'A');
}
```

::: info
ITM에는 Cortex-M3/M4/M7 이상과 SWO 캡처를 지원하는 디버그 프로브(J-Link, ULINK, ST-LINK v3)가 필요합니다. 프로브의 SWO 클록을 타깃 CPU 속도에 맞게 설정하세요.
:::
