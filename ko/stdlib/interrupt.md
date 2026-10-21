# 인터럽트 및 MMIO (`std::interrupt`)

`std/interrupt/`는 인터럽트 처리 중 소프트웨어가 관리하는 부분 —
벡터 테이블, ISR 등록/디스패치, MMIO 레지스터 접근, 비트필드 조작,
클럭 설정 — 을 다루며, [`std::hal`](/ko/stdlib/hal)의 주변장치 드라이버를
보완합니다. 모두 프리스탠딩 환경에서 안전합니다.

```c
#include <std/interrupt/mmio.h>
#include <std/interrupt/bitfield.h>

void configure_uart(&volatile unsigned int uart_base) {
    struct MmioReg ctrl = std::MmioReg::at(uart_base);
    unsigned int baud_field = ctrl.read_field(0, 15);
    ctrl.write_field(0, 15, baud_field | 1u);

    unsigned int combined = std::bf_insert32(0, 4, 7, 0xA);
}
```

## MMIO (`interrupt/mmio.h`) {#mmio-interruptmmioh}

레지스터 주소는 `&volatile` 참조(32비트 레지스터는 `&volatile unsigned int`)이며,
고정 주소로부터 `unsafe` 안에서 한 번 만듭니다 — 패턴은
[HAL](/ko/stdlib/hal)을 참고하십시오.

동일한 연산을 두 가지 방식으로 동등하게 제공합니다: 한 레지스터에 대해
여러 접근을 묶을 때 쓰는 `MmioReg` 구조체(`MmioReg::at(addr)`로 생성한 뒤
메서드 호출)와, 일회성 접근을 위한 자유 함수.

| `MmioReg` 메서드 | 대응하는 자유 함수 |
|---|---|
| `read32()` / `write32(val)` | `mmio_read32(addr)` / `mmio_write32(addr, val)` |
| — | `mmio_read16`/`mmio_write16`, `mmio_read8`/`mmio_write8` |
| `set_bits(mask)` / `clear_bits(mask)` | `mmio_set_bits32(addr, mask)` / `mmio_clear_bits32(addr, mask)` |
| `read_field(lo, hi)` / `write_field(lo, hi, val)` | `mmio_read_field32(addr, lo, hi)` / `mmio_write_field32(addr, lo, hi, val)` |

8비트와 16비트 접근 함수는 8비트와 16비트 버스 접근을 하므로 인접한 레지스터를 건드리지 않습니다. 필드 함수는 전체 폭인 `(0, 31)`도 받습니다.

## 비트필드 헬퍼 (`interrupt/bitfield.h`) {#bitfield-helpers-interruptbitfieldh}

레지스터 비트 조작을 위한 순수 함수들 — `bf_extract32(val, lo, hi)`,
`bf_insert32(val, lo, hi, field)`, `bf_set32`/`bf_clear32`/`bf_toggle32`
(비트 `[hi:lo]` 대상), `bf_test32(val, n)`(단일 비트 검사),
`bf_mask32(lo, hi)`(레지스터 접근 없이 마스크 생성), `bf_popcount32(val)`.

## ISR 벡터 테이블 (`interrupt/isr.h`) {#isr-vector-table-interruptisrh}

아래의 하드웨어 벡터 테이블과는 별개인 소프트웨어 디스패치 테이블 —
`IsrTable`은 최대 `ISR_MAX`(256)개의 `fn void()` 핸들러를 담습니다:

```c
static void uart_rx_isr() { /* ... */ }

struct IsrTable table = std::IsrTable::new();
table.register_(3, uart_rx_isr);   // trailing underscore: 'register' is a C keyword
// re-registering an IRQ replaces its handler
...
if (table.dispatch(3)) { /* 핸들러가 실행됨 */ }
```

그 외에 `irq_disable()`/`irq_enable()`이 있으며, 베어메탈 대상에서 CPU의
IRQ를 막고 풉니다: `cpsid i`/`cpsie i`(ARM), `msr daifset/daifclr, #2`
(AArch64), `mstatus.MIE` 비트 지우기/설정(RISC-V), `cli`/`sti`(x86).
특권 모드가 필요하며, 호스트 프로세스에서는 컴파일러 배리어 역할만 합니다.

## 하드웨어 벡터 테이블 (`interrupt/vector_table.h`) {#hardware-vector-table-interruptvector_tableh}

`VectorTable`(전역 인스턴스 `vtable`)은 CPU가 실제로 인식하는 벡터
테이블입니다 — 이를 채우려면 `install(index, handler)`/`remove(index)`/
`get(index)`를, 특정 슬롯의 핸들러(또는 `set_default()`로 지정한 기본
핸들러)를 호출하려면 `dispatch(index)`를, CPU가 이 테이블을 가리키게
하려면 `vtable_activate()`를 사용합니다:

| 대상 | `vtable_activate()`가 하는 일 |
|---|---|
| Cortex-M | `SCB->VTOR`에 기록 (v7-M, v7E-M, v8-M, v8.1-M; VTOR가 선택 사항인 v6-M은 제외) |
| RISC-V | `mtvec`에 기록 (벡터 모드) |
| AArch64 | `VBAR_EL1`에 기록 |

`vtable_init()`은 사용 전에 모든 슬롯을 `vtable_default_handler`(프로젝트별로
재정의하도록 만들어진, 약한 심벌(weak)의 무한 루프 폴트 핸들러)로
설정합니다. `set_default(handler)`는 이전 기본 핸들러를 가진 슬롯도 모두
새 핸들러로 바꾸며, 빈 인덱스나 범위를 벗어난 인덱스의 `dispatch()`는 현재
기본 핸들러를 호출합니다.

`VTABLE_MAX_VECTORS`는 256개입니다(Cortex-M: 코어 예외 16개 + 외부 IRQ 최대 240개).

## 클럭 설정 (`interrupt/clock.h`) {#clock-configuration-interruptclockh}

`ClockConfig`(`ClockConfig::new(base)`로 생성) — `set_source()`(`CLK_SRC_HSI`/
`CLK_SRC_HSE`/`CLK_SRC_PLL`), `configure_pll(mul, div)`, `set_ahb_div`/
`set_apb1_div`/`set_apb2_div`(버스 프리스케일러), `get_freq()`.
