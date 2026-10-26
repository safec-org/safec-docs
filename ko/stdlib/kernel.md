# 커널 기본 요소 (`std::kernel`)

`std/kernel/`은 작은 커널이나 하이퍼바이저를 처음부터 작성하기 위한
구성 요소 — 물리 메모리 할당, 페이지 테이블과 MMU 컨텍스트, 프로세스
제어 블록, 라운드 로빈 스케줄러, IPC 메일박스, 시스템 콜 디스패치 —
를 제공합니다. 모두 프리스탠딩 환경에서 안전하며, 단일 주소 공간을 위한
펌웨어를 작성한다고 가정하는 [`std::hal`](/ko/stdlib/hal)/
[`std::interrupt`](/ko/stdlib/interrupt)보다 더 저수준으로, 여러 개의
주소 공간을 관리하는 데 쓰입니다.

```c
#include <std/kernel/frame.h>
#include <std/kernel/process.h>
#include <std/kernel/scheduler.h>

static struct FrameAllocator frames;

void kernel_setup() {
    frames.init(1024);              // 1024개의 4K 프레임 = 4 MiB 추적
    long long f = frames.alloc();   // OOM 시 -1

    struct PCB p = std::PCB::new(1, 0x1000, 0x8000, 0);   // pid, entry, sp, page_table
    struct Scheduler sched = std::Scheduler::new();
    unsafe { sched.spawn_proc(&p); }
    int next = sched.next();        // 우선순위가 가장 높은 READY 프로세스 인덱스
}
```

## 물리 프레임 얼로케이터 (`kernel/frame.h`) {#physical-frame-allocator-kernelframeh}

`FrameAllocator`는 4 KiB 프레임 단위의 비트맵 얼로케이터입니다
(`FRAME_BITMAP_SIZE` = 4096 워드 = 131072개 프레임 = 인스턴스 하나당
512 MB 추적). `init(n)`, `alloc()`(프레임 번호, OOM 시 `-1`),
`free(frame)`, `is_used(frame)`, `mark_range(start, count)`(범위를 미리
예약 — 예를 들어 커널 이미지 자체), `free_count()`. `init`은 `n`을 비트맵이
추적하는 131072개 프레임으로 제한하며, 이미 해제된 프레임을 해제하면 아무
일도 하지 않습니다. `init_at(base, n)`은 프레임 0을 물리 주소 0이 아닌
`base`에서 시작합니다(대부분의 Arm·RISC-V 보드는 RAM이 1~2 GiB에서
시작합니다). `frame_addr(f)`는 프레임 `f`의 물리 주소를 줍니다.

## 페이징 (`kernel/paging.h`) {#paging-kernelpagingh}

`PageEntry`(64비트 원시 엔트리 하나: 비트 [63:12]에 물리 프레임 번호,
비트 [11:0]에 플래그)와 `PageTable`(이를 512개 담는 테이블). 플래그:
`PAGE_PRESENT`, `PAGE_WRITABLE`, `PAGE_USER`, `PAGE_WRITE_THRU`,
`PAGE_NO_CACHE`, `PAGE_ACCESSED`, `PAGE_DIRTY`, `PAGE_HUGE`.
`PageTable` 메서드: `init()`, `map(idx, phys_addr, flags)`,
`unmap(idx)`, `is_present(idx)`, `get_phys(idx)`, `get_flags(idx)`,
`set_flags(idx, flags)`.

`PAGE_SIZE`는 4096바이트, `PAGE_TABLE_SIZE`는 테이블당 512개 엔트리입니다.

## MMU 컨텍스트 (`kernel/mmu.h`) {#mmu-context-kernelmmuh}

`MmuContext<F: MmuFormat>`는 CPU가 직접 탐색하는 페이지 테이블을 만듭니다.
`F`는 컴파일 타임에 고르는 페이지 테이블 [형식 타입](#mmu-format)이며
(`BST<K, V, C: Order>`의 순서 관계와 같은 방식), 아키텍처마다 하나씩 있습니다:

| 형식 | 테이블 |
|---|---|
| `MmuX86_64` | x86-64 4단계 페이징, 48비트 VA, 8바이트 엔트리. 실행 불가 페이지는 NX를 설정하며 `EFER.NXE`가 필요합니다 |
| `MmuX86_32` | PAE 없는 x86 32비트 페이징: 2단계, 4바이트 엔트리(NX 없음) |
| `MmuSv39` | RISC-V Sv39(RV64): 3단계, 8바이트 PTE. 리프는 A와 D를 미리 설정합니다 |
| `MmuSv32` | RISC-V Sv32(RV32): 2단계, 4바이트 PTE |
| `MmuAArch64` | AArch64 stage 1, 4 KiB 그래뉼, 48비트 VA: 4단계. 리프는 일반 메모리에 MAIR 인덱스 0, 디바이스 메모리에 1을 씁니다(`MAIR_EL1`을 Attr0 = 0xFF, Attr1 = 0x04로 설정). `TCR_EL1`은 4 KiB 그래뉼과 T0SZ = 16이어야 합니다 |
| `MmuArmV7` | ARMv7-A short descriptor: 16 KiB 정렬된 16 KiB L1 테이블과 1 KiB L2 테이블. 일반 메모리는 write-back(TEX=001 C B), 디바이스 메모리는 TEX=000 B |

`MmuNative`는 컴파일 대상 CPU의 형식입니다(MPU만 있고 MMU가 없는 Cortex-M과
wasm에는 없습니다). 모든 형식은 어느 대상에서든 만들고 탐색할 수 있지만(예:
호스트의 테스트나 이미지 빌더), `activate()`와 TLB 함수는 형식이 실행 중인
베어메탈 CPU의 것일 때만 동작하고 그 외에는 0을 반환합니다.

```c
#include <std/kernel/mmu.h>

static struct FrameAllocator g_frames;

void vm_setup(unsigned long root) {           // 0으로 채운 4 KiB 정렬 테이블
    g_frames.init_at(0x80400000UL, 256UL);
    struct MmuContext<MmuNative> m = std::MmuContext::new(root, &g_frames, 0UL);
    m.map(0x80000000UL, 0x80000000UL, MMU_WRITE | MMU_EXEC);   // 항등 매핑: 커널 이미지
    m.map(0x10000000UL, 0x10000000UL, MMU_WRITE | MMU_DEVICE); // UART
    m.activate();                             // satp / CR3 / TTBR0
}
```

- `MmuContext::new(root, frames, phys_offset)`([정적 메서드](/ko/reference/functions#static-methods)이며 `F`는 선언된 타입에서 추론됩니다): `root`는 0으로 채운 루트 테이블의
  물리 주소(4 KiB 정렬, `MmuArmV7`은 16 KiB), `frames`는 새 중간 테이블마다
  프레임 하나를 공급하는 살아 있는 `&static` `FrameAllocator`이며, 테이블은
  물리 주소 + `phys_offset`에서 읽고 씁니다(항등 매핑 커널은 0, higher-half
  커널은 direct map의 기준 주소).
- `map(virt, phys, flags)`는 4 KiB 페이지 하나를 매핑합니다. `flags`는
  `MMU_WRITE`, `MMU_EXEC`, `MMU_USER`, `MMU_DEVICE`입니다(모든 매핑은 읽을 수
  있으며, RISC-V는 캐시 가능성을 PMA에 맡깁니다). 테이블 프레임을 할당할 수
  없거나 더 큰 페이지가 이미 `virt`를 덮고 있으면 0을 반환합니다.
- `unmap(virt)`, `walk(virt, &phys_out)`(더 큰 블록/슈퍼페이지 리프도
  따라갑니다), `entry(virt, &entry_out)`(리프 엔트리 원본).
- `tlb_flush_all()`, `tlb_flush_page(virt)`, `activate()`: x86은 `CR3` 재적재 /
  `invlpg`, RISC-V는 `satp` 기록(S·U 모드 변환을 켭니다) / `sfence.vma`,
  AArch64는 `TTBR0_EL1` 기록 / `TLBI`, ARMv7은 `TTBR0` 기록 / `TLBIALL`입니다.
  페이징 켜기(`CR0.PG`, `SCTLR.M`)와 MAIR/TCR 설정은 부트 코드의 몫입니다.

`tests/stdlib/baremetal/qemu/run.sh`는 모든 형식을 QEMU에서 변환을 켠 채로
실행합니다 — S 모드의 Sv39와 Sv32, MMU를 켠 AArch64와 ARMv7, x86 32비트
페이징과 x86-64 롱 모드 — 그리고 별칭 매핑을 통해 페이지를 읽고 씁니다.

### 페이지 테이블 형식: `MmuFormat` {#mmu-format}

`MmuContext`는 [제한된 제네릭 구조체](/ko/reference/generics#bounded-generic-structs)입니다.
형식 매개변수는 `MmuFormat` 트레이트를 만족해야 하므로, 페이지 테이블 형식이
아닌 타입은 `map()` 깊숙한 곳이 아니라 `MmuContext<...>`를 쓴 지점에서 오류가
납니다(`type 'X' does not satisfy constraint 'MmuFormat' ... (no method
'levels')`).

| 트레이트 | 요구 사항 | 만족하는 타입 |
|---|---|---|
| `MmuFormat` | `levels()`; `shift(l)`(단계 `l`의 인덱스가 시작하는 VA 비트, 루트가 `l` = 0); `entries(l)`; `esize()`(엔트리당 4 또는 8바이트); `table_desc(phys)`와 `leaf_desc(phys, flags)`(다음 단계 테이블을 가리키는 엔트리 / `MMU_*` 플래그를 가진 4 KiB 페이지 엔트리); `valid(e)`, `is_leaf(e, l)`, `addr(e, l)`; `load_root(root)`, `flush_all()`, `flush_page(virt)`(실행했으면 1, 실행 중인 CPU의 형식이 아니면 0). 모두 `const` | `MmuX86_64`, `MmuX86_32`, `MmuSv39`, `MmuSv32`, `MmuAArch64`, `MmuArmV7`(따라서 `MmuNative`도) |

`map`, `unmap`, `walk`, `entry`의 모든 단계는 이 메서드들에 대한 직접적이고
인라인 가능한 호출입니다. 새 아키텍처의 형식은 이 메서드들을 가지기만 하면
준수하며, `mmu.h`의 다른 부분은 바뀌지 않습니다. 페이지 테이블을 다루는 코드도
같은 제약으로 한 번만 작성합니다. 아래 예제는 호스트 버퍼에 RISC-V Sv39 테이블을
만들고(물리 주소가 곧 호스트 주소이므로 `phys_offset`은 0) 이를 탐색합니다:

```c
#include <std/kernel/mmu.h>

// 모든 페이지 테이블 형식에 대해 한 번만 작성합니다.
generic<F: MmuFormat>
int map_range(&MmuContext<F> m, unsigned long virt, unsigned long phys,
              unsigned long pages, unsigned int flags) {
    unsigned long i = 0UL;
    while (i < pages) {
        if (!m.map(virt + i * 4096UL, phys + i * 4096UL, flags)) return 0;
        i = i + 1UL;
    }
    return 1;
}

static unsigned char g_mem[17 * 4096];       // 프레임 16개 + 정렬 여유분
static struct FrameAllocator g_frames;

int main() {
    // 호스트에서 g_mem 안에 RISC-V Sv39 테이블을 만듭니다(물리 주소 == 호스트 주소).
    unsigned long base = 0UL;
    unsafe { base = ((unsigned long)&g_mem[0] + 4095UL) & ~4095UL; }
    g_frames.init_at(base, 16UL);
    g_frames.mark_range(0UL, 1UL);           // 프레임 0은 루트 테이블

    struct MmuContext<MmuSv39> m = std::MmuContext::new(base, &g_frames, 0UL);
    if (!map_range(&m, 0x40000000UL, 0x80000000UL, 4UL, MMU_WRITE)) return 1;

    unsigned long phys = 0UL;
    if (!m.walk(0x40002010UL, &phys) || phys != 0x80002010UL) return 2;
    return m.activate();                     // 0: Sv39는 이 CPU의 MMU가 아닙니다
}
```

## 프로세스 제어 블록 (`kernel/process.h`) {#process-control-block-kernelprocessh}

`PCB` — `pid`, `state`(`PROC_READY`/`PROC_RUNNING`/`PROC_BLOCKED`/
`PROC_ZOMBIE`), `priority`, 저장된 `stack_ptr`/`pc`, `page_table`(물리
루트 주소), `parent_pid`, `exit_code`. `PCB::new(pid, entry, sp,
page_table)`; 메서드 `set_state`, `set_priority`, `save_context(sp,
pc)`, `exit(code)`(`PROC_ZOMBIE`로 전이).

## 스케줄러 (`kernel/scheduler.h`) {#scheduler-kernelschedulerh}

`Scheduler` — 최대 `SCHED_MAX_PROCS`(256)개의 PCB에 대한 우선순위
기반 라운드 로빈. `Scheduler::new()`; `spawn_proc(&PCB)`(인덱스 반환,
가득 찼으면 `-1`), `next()`(우선순위가 가장 높은 READY 프로세스 선택),
`yield()`(현재 프로세스를 READY로 표시하고 다음 프로세스 선택),
`block_current()`, `unblock(idx)`, `remove(idx)`(좀비 회수),
`ready_count()`. `next()`는 READY 프로세스 중 우선순위가 가장 높은 것을
고르고, 우선순위가 같으면 현재 프로세스 다음부터 돌아가며 고릅니다.
`remove`는 `current`가 같은 프로세스를 가리키도록 유지합니다(제거된 것이
현재 프로세스였으면 `-1`).

## IPC 메일박스 (`kernel/ipc.h`) {#ipc-mailbox-kernelipch}

`Mailbox`(`Mailbox::new(owner_pid)`로 생성) — 고정 용량
(`MAILBOX_CAPACITY` = 64개 메시지, `MSG_MAX_SIZE` = 256바이트 페이로드)
메시지 큐. `send(sender_pid, type, payload)`(`payload`는
`[]const unsigned char`이며 최대 256바이트를 보관. 신호 전용 메시지는 빈 슬라이스를 전달), `recv(&Message out)`,
`peek(&Message out)`(비파괴적), `has_msg()`,
`length()`, `clear()`.

## 시스템 콜 테이블 (`kernel/syscall.h`) {#syscall-table-kernelsyscallh}

`SyscallTable`(`SyscallTable::new()`로 생성) — 타입이
`fn long long(long long, long long, long long)`인 핸들러를 최대
`SYSCALL_MAX`(256)개까지 등록. `register_(num, handler)`/
`unregister_(num)`(끝의 밑줄: `register`는 C 키워드이므로),
`dispatch(num, arg0, arg1, arg2)`(핸들러의 결과를 반환하며, 등록되지
않았으면 `-1`), `is_registered(num)`.
