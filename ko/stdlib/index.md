# 표준 라이브러리 개요

SafeC 표준 라이브러리(`std/`)는 핵심 유틸리티, 컬렉션, 얼로케이터, 네트워킹, 파일시스템, DSP, 디버깅, 보안을 아우릅니다. 각 모듈은 `.h`(선언)와 `.sc`(구현) 파일 쌍으로 구성됩니다. `prelude.h`를 include하면 모든 모듈을 한 번에 가져올 수 있습니다.

```c
#include "prelude.h"

int main() {
    println("Hello from SafeC stdlib!");
    return 0;
}
```

## 모듈 분류 {#module-categories}

### 코어 {#core}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| [mem](/ko/stdlib/mem) | `mem.h` | 할당, 해제, 안전한 memcpy/memmove/memset/memcmp; 캐시라인 헬퍼, 정렬 유틸리티 |
| [io](/ko/stdlib/io) | `io.h` | 서식화된 출력(stdout/stderr), stdin 입력, 버퍼 서식화 |
| [str](/ko/stdlib/str) | `str.h` | 문자열 길이, 비교, 복사, 검색, 토큰화, 복제 |
| [math](/ko/stdlib/math) | `math.h` | 상수(PI, E 등), float/double 수학, 분류 |
| [thread](/ko/stdlib/thread) | `thread.h` | 스레드, 뮤텍스, 조건 변수, 읽기-쓰기 락 |
| [atomic](/ko/stdlib/atomic) | `atomic.h` | 락-프리 원자 연산 (C11 `<stdatomic.h>` 래퍼) |

### [직렬화](/ko/stdlib/serial) {#serialization}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| value | `serial/value.h` | 형식에 구애받지 않는 `Value` 트리 (Null/Bool/Int/Float/String/Array/Object) |
| json | `serial/json.h` | JSON 작성기 + 파서, 정확한 왕복 변환 |
| xml | `serial/xml.h` | XML 작성기 + 파서 (자체 문법 왕복) |
| html | `serial/html.h` | HTML 조각 작성기 + 파서 (`<dl>`/`<ul>` 형태) |

### [컬렉션](/ko/stdlib/collections) {#collections}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| slice | `collections/slice.h` | 경계 검사된 팻 포인터 + 제네릭 배열 함수 |
| vec | `collections/vec.h` | push/pop/sort/filter/map을 지원하는 동적 배열 |
| string | `collections/string.h` | 가변, 힙 할당 성장형 문자열 (30개 이상의 메서드) |
| stack | `collections/stack.h` | 성장형 배열 기반 LIFO 스택 |
| queue | `collections/queue.h` | FIFO 원형 버퍼 큐 |
| list | `collections/list.h` | 이중 연결 리스트 |
| map | `collections/map.h` | 해시 맵 (오픈 어드레싱, 선형 탐사) |
| bst | `collections/bst.h` | 불균형 이진 탐색 트리 |
| btree | `collections/btree.h` | B-트리 정렬된 맵 (차수-4, 256노드 풀) |
| ringbuffer | `collections/ringbuffer.h` | SPSC 락-프리 2의 거듭제곱 링 버퍼 |
| static_collections | `collections/static_vec.h` | 헤더 전용 제로-힙 vec + map 매크로 |

### [얼로케이터](/ko/stdlib/allocators) {#allocators}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| bump | `alloc/bump.h` | 선형 범프-포인터 아레나; O(1) 할당, 리셋 전용 해제 |
| slab | `alloc/slab.h` | 고정 크기 객체용 프리리스트 슬랩; O(1) 할당/해제 |
| pool | `alloc/pool.h` | 혼합된 내용을 위한 고정 블록 풀; O(1) 할당/해제 |
| tlsf | `alloc/tlsf.h` | Two-Level Segregated Fit; 최악의 경우도 O(1)인 범용 힙 |

### [동기화](/ko/stdlib/sync) {#synchronization}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| spinlock | `sync/spinlock.h` | 바쁜 대기(busy-wait) 상호 배제 (`__sync_lock_test_and_set`) |
| lockfree | `sync/lockfree.h` | 컴파일러 배리어를 사용하는 대기-프리 SPSC 링 버퍼 |
| channel | `sync/channel.h` | 언어 내장 바운디드 블로킹 MPMC 채널(`chan_create`/`chan_send`/`chan_recv`/`chan_close`)을 감싸는 타입 지정(`chan_send_t<T>`/`chan_recv_t<T>`) 래퍼 |
| mpsc | `sync/mpsc.h` | 스핀락으로 보호되는 바운디드 MPSC 링 버퍼, 논블로킹 API |
| task | `sync/task.h` | 협조적 라운드로빈 태스크 스케줄러 |
| thread_bare | `sync/thread_bare.h` | 우선순위 기반 프리스탠딩 스레드 (OS 없음) |
| bare_spawn | `sync/bare_spawn.sc` | 프리스탠딩 타깃에서 `spawn`/`join` 언어 키워드를 위한 참조 "훅" 백엔드 |

### [IPC](/ko/stdlib/ipc) {#ipc}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| pipe | `ipc/pipe.h` | 익명 파이프 (호스티드) — 단방향 바이트 스트림, 보통 `fork()` 이후 부모/자식 간 |
| uds | `ipc/uds.h` | 유닉스 도메인 소켓 (호스티드) — 무관한 프로세스 간의 이름 지정 IPC, 논블로킹/`Reactor`와 페어링 가능 |

### [실시간 스케줄러](/ko/stdlib/sched) {#real-time-scheduler}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| reactor | `sched/reactor.h` | `Reactor` — kqueue 기반 I/O 이벤트 루프가 `TaskScheduler`를 구동 |
| io_nb | `sched/io_nb.h` | 리액터와 함께 사용하도록 설계된 논블로킹 파일/소켓 헬퍼 |

### [네트워킹](/ko/stdlib/net) {#networking}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| net-core | `net/net_core.h` | `PacketBuf`, `NetIf`, 바이트 순서 유틸리티, IP/MAC 헬퍼 |
| ethernet | `net/ethernet.h` | `EthernetHdr`, `eth_parse`, `eth_build` |
| arp | `net/arp.h` | `ArpTable` (16개 항목 FIFO), `arp_build_packet`, `arp_parse_packet` |
| ipv4 | `net/ipv4.h` | `Ipv4Hdr`, 인터넷 체크섬, `ipv4_parse`, `ipv4_build` |
| ipv6 | `net/ipv6.h` | `Ipv6Addr`/`Ipv6Hdr`, 링크 로컬/루프백 판별, `ipv6_frame` |
| udp | `net/udp.h` | `UdpHdr`, `udp_parse`, `udp_build`, `udp_frame` |
| tcp | `net/tcp.h` | `TcpConn` 10상태 상태 기계, 의사 헤더 체크섬 |
| dns | `net/dns.h` | A 레코드 쿼리 빌더 + 응답 파서 (레이블 압축) |
| dhcp | `net/dhcp.h` | `DhcpClient` DORA 핸드셰이크 |

### [HTTP & 웹](/ko/stdlib/http) {#http-web}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| http | `http/http.h` | HTTP/1.1 클라이언트 + 서버 (`http_serve`/`http_serve_threaded`), 요청/응답 타입 |
| cors | `http/cors.h` | CORS 프리플라이트 감지 + 응답 헤더 |
| jwt | `http/jwt.h` | HS256 JWT 서명/검증 (HMAC-SHA256) |
| oauth2 | `http/oauth2.h` | OAuth2 클라이언트: 인가 코드 + 리프레시 토큰 교환 (RFC 6749) |
| websocket | `http/websocket.h` | WebSocket 핸드셰이크 + 프레임 읽기/쓰기 |
| rpc | `rpc/rpc.h` | gRPC에서 영감을 받은 HTTP 기반 RPC (길이 프리픽스 프레이밍, 경로 기반 디스패치) |
| server_fn | `rpc/server_fn.h` | "한 번 작성하고 어디서든 호출" 방식의 JSON 마셜링 서버 함수 (Dioxus/Leptos 스타일) |
| reactive | `reactive/signal.h` | `Signal<T>` 세밀한 반응형 상태 (WASM 클라이언트 하이드레이션) |
| wasm | `wasm/dom.h`, `wasm/hydrate.h` | wasm32 DOM 상호 운용 + 클라이언트 하이드레이션; `wasm/wasm_rt.h`는 프리스탠딩 malloc/free 런타임(wasm32 전용 — 호스티드 stdlib 아카이브에는 절대 링크되지 않음) |
| scx | 해당 없음 (트랜스파일러) | JSX/TSX 스타일 HTML 템플릿 — `.scx` 파일은 `safec`가 보기 전에 일반 SafeC로 트랜스파일됨 |

### 머신러닝 — [ML](/ko/stdlib/ml) 참고 {#machine-learning-see-ml}

### GUI (`std/gui/`) {#gui-stdgui}

포터블한 `GuiWindow`/`GuiEvent` API 위에 만들어진 리테인 모드 위젯 툴킷으로, 일치하는
`.sc` 파일을 include하여 선택하는 네 가지 백엔드가 있습니다:
`gui_cocoa.sc`(macOS, Objective-C 런타임 상호 운용 — 실제 하드웨어에서 완전히
검증됨), `gui_win32.sc`/`gui_x11.sc`(Windows/X11 — 실제 Win32/Xlib ABI
형태에 맞춰 작성 및 타입 검사됨, 미검증: Windows/X11 호스트가 없음),
`gui_fb.sc`(베어메탈 선형 프레임버퍼 — 완전히 검증됨, OS 의존성 없음).
`gui_widget.h`는 위젯 트리(컨테이너, 버튼/레이블/체크박스/텍스트 입력/슬라이더,
커스텀 위젯 확장 API)이며, `gui_draw.h`/`gui_font.h`는 프리미티브와 비트맵
폰트를 다루고, `gui_png.h`/`gui_svg.h`는 처음부터 작성된 PNG(DEFLATE)와
SVG 디코더/렌더러입니다.

### [파일시스템](/ko/stdlib/fs) {#filesystems}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| block | `fs/block.h` | `BlockDevice` 드라이버 인터페이스 (함수 포인터 기반) |
| partition | `fs/partition.h` | MBR 파티션 테이블 파서 (4개의 기본 항목) |
| vfs | `fs/vfs.h` | 최장 접두사 마운트 라우팅을 사용하는 VFS; `VfsNode` 포워딩 |
| fat | `fs/fat.h` | FAT32 읽기 전용 드라이버; 8.3 경로 탐색, 클러스터 체인 |
| ext | `fs/ext.h` | ext2 읽기 전용 드라이버; 아이노드 탐색, 직접 블록 읽기 |
| tmpfs | `fs/tmpfs.h` | 인메모리 FS; 32개 아이노드, 64 KiB 데이터 풀; 완전한 CRUD |

### [DSP & 실시간](/ko/stdlib/dsp) {#dsp-real-time}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| fixed | `dsp/fixed.h` | Q8.24 고정소수점 연산 (`newtype Fixed = int`) |
| dsp | `dsp/dsp.h` | `dsp_dot`, `dsp_scale`, `dsp_add`, `dsp_clip`, `dsp_peak`, `dsp_rms`, SIMD-FMA `dsp_dot_f64` |
| complex_dsp | `dsp/complex_dsp.h` | 연산자 오버로딩을 지원하는 값 타입 `Complex`/`FComplex` (`+`,`-`,`*`,`/`,`abs`,`arg`,`conj`) |
| dft | `dsp/dft.h` | 직접 O(n²) DFT/IDFT, 임의 길이 (float + Q8.24) |
| fft | `dsp/fft.h` | 제자리 radix-2 Cooley-Tukey FFT/IFFT, O(n log n), 2의 거듭제곱 길이 (float + Q8.24) |
| convolution | `dsp/convolution.h` | 선형 컨볼루션 — 직접 O(len_x·len_h) (SIMD 가속) 및 FFT 기반 O(n log n) |
| window | `dsp/window.h` | 사각/Hann/Hamming/Blackman 분석 윈도우 |
| filter | `dsp/filter.h` | 일반 스트리밍 FIR/IIR (피드포워드/피드백) 차분 방정식 필터 (float + Q8.24) |
| biquad | `dsp/biquad.h` | 2차 IIR 섹션 + RBJ "Audio EQ Cookbook" 설계기 + 일반 쌍선형 변환 |
| dct | `dsp/dct.h` | DCT-II/DCT-III (JPEG/MPEG 스타일 이산 코사인 변환), float + Q8.24 |
| stft | `dsp/stft.h` | 단시간 푸리에 변환 + 역변환(윈도우 오버랩-애드) + 다중 해상도 STFT 손실 |
| resample | `dsp/resample.h` | 업/다운샘플링 — 최근접, 선형, 윈도우드-싱크(대역 제한); float + Q8.24 최근접/선형 |
| ztransform | `dsp/ztransform.h` | 임의 차수의 쌍선형(라플라스-투-Z) 변환 + Z-도메인 주파수 응답 평가기 |
| comb | `dsp/comb.h` | 피드포워드/피드백 콤 필터 + Karplus-Strong 현 합성 (float + Q8.24) |
| minphase | `dsp/minphase.h` | 실 켑스트럼을 통한 최소 위상 재구성 (호모모픽 처리) |
| cqt | `dsp/cqt.h` | Constant-Q 변환 (로그 간격 빈, 직접 상관 형식) |
| cwt | `dsp/cwt.h` | 연속 웨이블릿 변환 (Morlet 웨이블릿, 시간-스케일 스칼로그램) |
| imaging | `dsp/imaging.h` | 2D 컨볼루션, 2D FFT (행-열 분해), 가우시안/Sobel 커널 |
| audio_buffer | `dsp/audio_buffer.h` | 다채널 SPSC 오디오 링 버퍼 (인터리브드 `Fixed` 프레임) |
| timer_wheel | `dsp/timer_wheel.h` | 256슬롯 O(1) 타이머 휠; 일회성 + 주기적 |

### [보안 & 암호학](/ko/stdlib/crypto) {#security-cryptography}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| aes | `crypto/aes.h` | AES-128/256 ECB + CBC; 완전한 S-box + 키 확장 |
| sha256 | `crypto/sha256.h` | SHA-256/224; 스트리밍 및 원샷 API |
| rng | `crypto/rng.h` | ChaCha20 CSPRNG; `rdrand`/`/dev/urandom` 시딩 |
| secure_alloc | `crypto/secure_alloc.h` | 해제 시 제로화를 수행하는 슬랩 얼로케이터 |
| x509 | `crypto/x509.h` | X.509 DER/ASN.1 파서; SAN, 와일드카드 호스트네임, 유효성 |
| tls | `crypto/tls.h` | TLS 1.3 레코드 레이어; AES-CBC + PKCS#7 + 논스 XOR 시퀀스 |

### [디버깅 & 프로파일링](/ko/stdlib/debug) {#debugging-profiling}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| perf | `debug/perf.h` | 아키텍처별 디스패치되는 사이클 카운터 (RDTSC/cntvct_el0/CSR); ns 보정 |
| coverage | `debug/coverage.h` | 1024지점 커버리지 트래커; `COV_SITE()` 매크로; `report()` |
| jtag | `debug/jtag.h` | 아키텍처별 `debug_break`; ARM/AArch64 세미호스팅; ITM 포트 |

### [SIMD](/ko/stdlib/simd) {#simd}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| simd | `simd/simd.h` | 포터블 코어: 네이티브 `vec<T,N>` 위의 `f32x4`/`i32x8`/... 타입 별칭; 로드/저장, splat, fma, min/max, 수평 축소 |
| x86_64 / aarch64 / riscv / wasm / spirv / cortex_m / cuda / rocm | `simd/*.h` | ISA별 얇은 편의 레이어 (네이티브 우선 폭 명명, 실제 하드웨어 검증 노트) — 동일한 포터블 소스를 기반으로 하며 별도 구현 없음 |

### [하드웨어 추상화 레이어](/ko/stdlib/hal) {#hardware-abstraction-layer}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| gpio | `hal/gpio.h` | `GpioPin`: 방향, read/write/toggle, 풀업/풀다운 |
| i2c | `hal/i2c.h` | `I2cBus`: 폴링 마스터 — write/read/write_read/probe |
| spi | `hal/spi.h` | `SpiDevice`: 폴링 마스터 — transfer/write/read, 칩 셀렉트 |
| uart | `hal/uart.h` | `Uart`: 폴링 시리얼 — 바이트/문자열 I/O, 준비 플래그 |
| timer | `hal/timer.h` | `Timer`: 주기/시작/정지/읽기/플래그 |
| watchdog | `hal/watchdog.h` | `Watchdog`: 활성화/피드/리셋 원인 확인 |
| cortex_m | `hal/cortex_m.h` | NVIC, SysTick, SCB (ARM Cortex-M) |
| aarch64 | `hal/aarch64.h` | 시스템 레지스터, Generic Timer, GICv2 (ARMv8-A) |
| riscv | `hal/riscv.h` | CSR 접근, CLINT, PLIC |

### [인터럽트 & MMIO](/ko/stdlib/interrupt) {#interrupts-mmio}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| mmio | `interrupt/mmio.h` | `MmioReg` + 자유 함수 레지스터 read/write/필드 접근 |
| bitfield | `interrupt/bitfield.h` | 순수 비트 조작 함수 (`bf_extract32`, `bf_insert32`, ...) |
| isr | `interrupt/isr.h` | 소프트웨어 ISR 디스패치 테이블 (256슬롯) |
| vector_table | `interrupt/vector_table.h` | 하드웨어 벡터 테이블 — Cortex-M `VTOR`/RISC-V `mtvec`/AArch64 `VBAR_EL1` |
| clock | `interrupt/clock.h` | PLL/클록 소스 구성 |

### [커널 프리미티브](/ko/stdlib/kernel) {#kernel-primitives}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| frame | `kernel/frame.h` | 비트맵 물리 프레임 얼로케이터 (4 KiB 프레임) |
| paging | `kernel/paging.h` | `PageEntry`/`PageTable` — 원시 페이지 테이블 조작 |
| mmu | `kernel/mmu.h` | `MmuContext` — 2단계 가상 메모리, map/unmap/walk/TLB/activate |
| process | `kernel/process.h` | `PCB` — 프로세스 제어 블록 |
| scheduler | `kernel/scheduler.h` | PCB 기반 우선순위 라운드로빈 스케줄러 |
| ipc | `kernel/ipc.h` | `Mailbox` — 고정 용량 메시지 큐 |
| syscall | `kernel/syscall.h` | 시스템 콜 등록/디스패치 테이블 |

### [테스트 & 벤치마킹](/ko/stdlib/testing) {#testing-benchmarking}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| test | `test/test.h` | `TestSuite` + `ASSERT_*` 매크로 |
| bench | `test/bench.h` | `BenchSuite` — 벽시계 기준 반복 벤치마크 |
| fuzz | `test/fuzz.h` | `FuzzTarget` — 경량 인프로세스 뮤테이션 퍼저 |

### 유틸리티 {#utilities}

| 헤더 | 설명 |
|--------|-------------|
| `bit.h` | 비트 조작 (C23 `<stdbit.h>` + popcount/clz/ctz/bswap 내장 함수) |
| `convert.h` | 문자열 ↔ 숫자 파싱 (C11/C17), 실패 시 `*ok` 성공 플래그 |
| `dma.h` | 캐시 일관성 DMA 버퍼 디스크립터 (64바이트 정렬) |
| `fmt.h` | 호출자가 제공한 버퍼로 서식화하는 안전한 `snprintf` 기반 서식화 |
| `heap.h` | 통합 힙: TLSF 기반 정적 버퍼(프리스탠딩) 또는 malloc/free/realloc(호스티드) |
| `log.h` | 구성 가능한 로깅, `LOG_LEVEL`이 0이면 오버헤드 없음 |
| `panic.h` | 옵트인 패닉 핸들러 — 기본값으로 무한 루프(프리스탠딩) 또는 `abort()`(호스티드) |
| `result.h` | 명시적 `Result` 오류 전파 타입 (힙 할당, `?T` 옵셔널을 반영) |
| `sys.h` | 프로세스 제어 상수 (`EXIT_SUCCESS`/`EXIT_FAILURE`), PRNG 상수 |
| `complex.h` | 복소수 (C99 `<complex.h>`), `[real, imag]` float/double 쌍 |

### C 호환 헤더 {#c-compatibility-headers}

| 헤더 | 설명 |
|--------|-------------|
| `assert.h` | 런타임 어서션 (`runtime_assert`, `assert_true`); NDEBUG 지원 |
| `ctype.h` | 문자 분류 (`char_is_alpha`, `char_is_digit`, ...) 및 변환 |
| `errno.h` | 스레드 로컬 `errno` 값과 오류 설명 (C11) |
| `fenv.h` | 부동소수점 예외 플래그와 반올림 모드 (C99) |
| `locale.h` | 로케일 카테고리 상수 (C11) |
| `signal.h` | 시그널 핸들러 설치 및 디스패치 (C11) |
| `time.h` | 달력/벽시계 시간 (C11), `sys.h`의 고해상도 클록을 보완 |
| `stdckdint.h` | 검사된 정수 연산 (C23 스타일 `ckd_add`/`ckd_sub`/`ckd_mul`) |
| `stdint.h` | 고정 폭 정수 타입 |
| `stddef.h` | `size_t`, `NULL`, `offsetof` |
| `stdbool.h` | 불린 상수 |
| `limits.h` | 정수 한계값 |
| `float.h` | 부동소수점 한계값 |
| `inttypes.h` | 고정 폭 타입을 위한 서식 매크로 |

## 제네릭 패턴 {#generic-pattern}

표준 라이브러리 자체의 컬렉션들은 SafeC의 제네릭 구조체 지원([제네릭](/ko/reference/generics#generic-structs-and-methods) 참고 — 이제 구조체와 유니온도 제네릭이 될 수 있습니다)보다 먼저 만들어졌고, 아직 그쪽으로 마이그레이션되지 않았습니다. 여전히 내부 자료구조에는 `void*` 구조체를 사용하며, 타입 안전한 접근을 위한 `generic<T>` 래퍼 함수를 함께 씁니다. `T`는 호출 지점에서 `T*` 인자로부터 단형화(monomorphization)를 통해 추론됩니다. 이 방식은 요소 타입 `T`마다 하나씩이 아니라, 컬렉션 *타입*당 하나의 컴파일된 구조체만 유지합니다 — 이는 제네릭 구조체의 존재 여부와 무관한 실질적인 트레이드오프이며, 단순히 그 부재에 대한 임시방편이 아닙니다(전체 근거는 [컬렉션](/ko/stdlib/collections) 참고).

```c
#include "collections/vec.h"

int main() {
    struct Vec v = vec_new(sizeof(int));

    // 타입 소거된 API
    int x = 42;
    vec_push(&v, &x);

    // 제네릭 타입 래퍼 — T는 int* 인자로부터 추론됨
    vec_push_t(&v, 100);
    int* p = vec_at(&v, 0);  // int*를 반환

    vec_free(&v);
    return 0;
}
```

이 `void*`-plus-typed-wrapper 형태는 명시적 런타임 다형성(`void* data`와 `fn` 필드를 가진 구조체) 뒤에 있는 것과 동일한 일반적인 기법입니다 — [다형성 & OOP](/ko/reference/polymorphism#manual-dispatch-explicit-vtables) 참고.

## 표준 라이브러리 포함하기 {#including-the-standard-library}

**개별 모듈:**
```c
#include "mem.h"
#include "io.h"
#include "collections/vec.h"
```

**모든 모듈을 한 번에:**
```c
#include "prelude.h"
```

`safeguard` 패키지 매니저로 빌드할 때는 표준 라이브러리가 자동으로 `build/deps/libsafec_std.a`로 컴파일되어 링크됩니다.

수동으로 빌드할 때는 `-I`로 std 디렉터리를 전달하세요.
```bash
./build/safec myfile.sc -I /path/to/SafeC/std --emit-llvm -o myfile.ll
clang myfile.ll -o myfile
```
