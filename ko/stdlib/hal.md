# 하드웨어 추상화 계층 (`std::hal`)

`std/hal/`은 흔히 쓰이는 임베디드 주변장치를, 해당 주변장치의 MMIO 베이스
주소를 담고 있는 구조체 인스턴스의 메서드로 감싼다 — 대응하는
`*_init()` 함수로 인스턴스를 만든 다음 그 위에서 메서드를 호출하면 된다.
모든 모듈이 프리스탠딩 환경에서 안전하며(libc 의존성 없음), 일반적인
`--target` 크로스 컴파일은 물론 `--freestanding`에서도 동작한다.

```c
#include <std/hal/gpio.h>

void blink(void* gpio_base, int pin) {
    struct GpioPin led = std::gpio_init(gpio_base, pin, GPIO_OUTPUT);
    led.write(1);
    led.toggle();
}
```

생성자 함수는 네임스페이스가 적용되지만 구조체 *타입* 자체는 그렇지
않다는 점에 유의한다 — `struct std::GpioPin`이 아니라 `struct GpioPin`
이다([네임스페이스](/ko/reference/namespaces#what-gets-namespaced) 참고).

## 범용 주변장치 {#generic-peripherals}

| 모듈 | 헤더 | 타입 | 설명 |
|---|---|---|---|
| GPIO | `hal/gpio.h` | `GpioPin` | `gpio_init(base, pin, direction)`; `set_direction`, `write`, `read`, `toggle`, `set_pull` |
| I2C | `hal/i2c.h` | `I2cBus` | `i2c_init(base, speed)`; `write`, `read`, `write_read`(결합 트랜잭션), `probe` |
| SPI | `hal/spi.h` | `SpiDevice` | `spi_init(base, mode)`; `transfer`(1바이트, 전이중), `cs_assert`/`cs_deassert`, `write`, `read` |
| UART | `hal/uart.h` | `Uart` | `uart_init(base, baud)`; `write_byte`, `read_byte`, `write_str`, `rx_ready`, `tx_ready`(모두 폴링 기반) |
| Timer | `hal/timer.h` | `Timer` | `timer_init(base, prescaler)`; `set_period`, `start`, `stop`, `read`, `clear_flag`, `flag_set` |
| Watchdog | `hal/watchdog.h` | `Watchdog` | `watchdog_init(base, timeout)`; `enable`(일반적으로 한 번 활성화되면 비활성화할 수 없음), `feed`, `caused_reset` |

모두 원시 MMIO 주소에 직접 작동하는 폴링 기반 드라이버다(인터럽트 기반
변형은 없음) — 주변장치가 완료를 비동기적으로 알려야 할 경우 인터럽트를
다루는 쪽은 [`std::interrupt`](/ko/stdlib/interrupt)(ISR 등록, 벡터
테이블, MMIO 비트필드 헬퍼)를 참고한다.

## ARM Cortex-M {#arm-cortex-m}

`hal/cortex_m.h` — NVIC, SysTick, SCB를 Cortex-M 표준 메모리 맵 주소에
위치한 전역 인스턴스로 제공한다. 전체 설명(HAL 사용법, DSP 확장
내장 함수, MVE)은 [베어메탈 → ARM Cortex-M](/ko/reference/baremetal#arm-cortex-m)을
참고한다.

## AArch64 {#aarch64}

`hal/aarch64.h` — 위의 Cortex-M *마이크로컨트롤러* HAL과는 별개로,
ARMv8-A 애플리케이션 코어(Cortex-A53/A57/A72/A55/A76)를 대상으로 한다.

**시스템 레지스터**(인스턴스 없이 사용하는 자유 함수):
`aa64_read_mpidr()`(프로세서 어피니티/CPU ID), `aa64_read_currentel()`
(예외 레벨 EL0–EL3), `aa64_read_daif()`/`aa64_write_daif()`(인터럽트
마스크 비트), `aa64_irq_enable()`/`aa64_irq_disable()`,
`aa64_fiq_enable()`/`aa64_fiq_disable()`, `aa64_isb()`/`aa64_dsb_sy()`/
`aa64_dmb_sy()`(배리어).

**제네릭 타이머**(`Aa64Timer`, 전역 인스턴스 `aa64_timer`):
`read_cntpct()`(시스템 카운터), `read_cntfrq()`(카운터 주파수),
`set_tval()`, `enable()`/`disable()`, `fire_pending()`.

**GIC**(GICv2 호환; `GicDist`/`GicCpu`, 전역 인스턴스 `gic_dist`/
`gic_cpu`, `gic_init(dist_base, cpu_base)`로 초기화): 분배기(distributor)
쪽의 `enable_irq`/`disable_irq`, `set_priority`, `set_target`(CPU
어피니티 마스크), `set_config`(에지 트리거 대 레벨 트리거),
`is_pending`/`clear_pending`; CPU 인터페이스 쪽의 `ack()`(대기 중인 IRQ
번호를 반환)/`eoi()`(인터럽트 종료).

## RISC-V {#risc-v}

`hal/riscv.h` — 주소는 SiFive FE310 / 표준 RISC-V MMIO 맵을 따른다.

**CSR 접근**(인라인 `asm`을 사용하는 자유 함수):
`rv_csr_read_mstatus`/`rv_csr_write_mstatus`, `rv_csr_read_mie`/
`rv_csr_write_mie`, `rv_csr_read_mip`, `rv_csr_read_mcause`,
`rv_csr_read_mepc`/`rv_csr_write_mepc`, `rv_csr_read_mtvec`/
`rv_csr_write_mtvec`, `rv_csr_read_time`/`rv_csr_read_cycle`/
`rv_csr_read_instret`, 그리고 `rv_global_irq_enable`/
`rv_global_irq_disable`.

**CLINT**(`Clint`, 전역 인스턴스 `clint`, `clint_init(base)`로 생성):
`set_msip`/`clear_msip`(하트별 소프트웨어 인터럽트), `set_mtimecmp`/
`read_mtime`, `schedule(delta)`(`delta` 틱 뒤에 타이머 인터럽트 발생).

**PLIC**(`Plic`, 전역 인스턴스 `plic`, `plic_init(base)`로 생성):
`set_priority`, `enable`/`disable`, `set_threshold`, `claim()`(우선순위가
가장 높은 대기 중 IRQ, 0이면 없음), `complete(irq)`.
