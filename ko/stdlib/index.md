# 표준 라이브러리 개요

SafeC 표준 라이브러리(`std/`)는 핵심 유틸리티, 컬렉션, 얼로케이터, 네트워킹, 파일시스템, DSP, 디버깅, 보안을 아우릅니다. 각 모듈은 `.h` 헤더와, 제네릭이 아닌 부분을 위한 `.sc` 구현으로 구성되며, 제네릭 컨테이너는 헤더 전용입니다. `#include`는 항상 `.h` 파일만 지정합니다 — `.sc` 파일은 한 번만 컴파일되어 `libsafec_std.a`에 들어가고 링크됩니다. `prelude.h`를 include하면 대부분의 모듈을 한 번에 가져올 수 있습니다. GUI, 인코딩, HTTP/RPC/wasm, 실시간 스케줄러, ML, SIMD는 각자의 헤더로 include합니다.

```c
#include <std/prelude.h>

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
| [io_traits](/ko/stdlib/io) | `io_traits.h` | `Reader` / `Writer` / `Seekable` / `Flusher` 트레이트와 이를 합성한 `Stream` / `SeekableStream`; 모든 스트림에 대한 `read_exact`, `write_all`, `copy_stream` |
| [io_buf](/ko/stdlib/io) | `io_buf.h` | 임의의 `Reader` / `Writer` 위의 버퍼링 `BufReader<R>` / `BufWriter<W>` (그 자체도 `Reader` / `Writer`) |
| [dir](/ko/stdlib/dir) | `dir.h` | 디렉터리 나열, `file_stat`(종류, 크기, 수정 시각), `dir_create_all`, `dir_walk`, `dir_copy_tree`, `dir_remove_all`, `file_copy` (POSIX + Win32) |
| [path](/ko/stdlib/dir#path) | `path.h` | 경로 문자열: join, dirname, basename, ext, stem, normalize, relative |
| [str](/ko/stdlib/str) | `str.h` | 문자열 길이, 비교, 복사, 검색, 토큰화, 복제 |
| [math](/ko/stdlib/math) | `math.h` | 상수(PI, E 등), float/double 수학, 분류 |
| [thread](/ko/stdlib/thread) | `thread.h` | 스레드, 뮤텍스, 조건 변수, 읽기-쓰기 락 |
| [atomic](/ko/stdlib/atomic) | `atomic.h` | 락-프리 원자 연산 (C11 `<stdatomic.h>` 래퍼) |

### [직렬화](/ko/stdlib/serial) {#serialization}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| value | `serial/value.h` | 형식에 구애받지 않는 `Value` 트리 (Null/Bool/Int/Float/String/Array/Object); `v.encode(f)` / `Value::decode(f, ...)`의 바탕인 `Format` 트레이트 |
| json | `serial/json.h` | JSON 작성기 + 파서, 정확한 왕복 변환 |
| xml | `serial/xml.h` | XML 작성기 + 파서 (자체 문법 왕복) |
| html | `serial/html.h` | HTML 조각 작성기 + 파서 (`<dl>`/`<ul>` 형태) |

### [컬렉션](/ko/stdlib/collections) {#collections}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| slice | `collections/slice.h` | `Slice<T>` 경계 검사 뷰 + `arr_*` 헬퍼 |
| vec | `collections/vec.h` | `Vec<T>` 동적 배열; 인트로소트, 컴파일 타임 `vec_sort_by` |
| string | `collections/string.h` | 가변, 힙 할당 성장형 문자열 (30개 이상의 메서드) |
| stack | `collections/stack.h` | `Stack<T>` LIFO |
| queue | `collections/queue.h` | `Queue<T>` FIFO 링 |
| list | `collections/list.h` | 노드 풀 위의 `List<T>` 이중 연결 리스트 |
| map | `collections/map.h` | `HashMap<K, V>` / `StrMap<V>` (오픈 어드레싱) |
| bst | `collections/bst.h` | 컴파일 타임 순서 관계 `C`를 갖는 `BST<K, V, C>` |
| btree | `collections/btree.h` | `BTree<V>` 정렬된 맵, 고정 256노드 풀 |
| ringbuffer | `collections/ringbuffer.h` | SPSC 락-프리 2의 거듭제곱 바이트 링 |
| node_pool | `collections/node_pool.h` | `NodePool<N>` 컨테이너별 노드 아레나 |
| static_vec | `collections/static_vec.h` | 호출자 저장 공간 위의 `StaticVec<T>` / `StaticMap<V>` (힙 사용 없음) |
| traits | `collections/traits.h` | 컨테이너 기능 트레이트(`Len`, `Push`, `Enqueue`, …)와 이를 합성한 `Sequence` / `Lifo` / `Fifo` / `CountedFifo` / `Map`; `push_all`, `enqueue_all`, `drain_into` |

### [얼로케이터](/ko/stdlib/allocators) {#allocators}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| allocator | `alloc/allocator.h` | `Allocate` / `Deallocate` 트레이트와 이를 합성한 `Allocator` / `ArenaAllocator`; 모든 할당자에 대한 `alloc_t` / `alloc_array_t` / `dealloc_t`와 `CountingAllocator<A>` |
| bump | `alloc/bump.h` | 선형 범프-포인터 아레나; O(1) 할당, 리셋 전용 해제 |
| slab | `alloc/slab.h` | 고정 크기 객체용 프리리스트 슬랩; O(1) 할당/해제 |
| pool | `alloc/pool.h` | 병합(coalescing)을 지원하는 가변 크기 first-fit 풀 |
| tlsf | `alloc/tlsf.h` | Two-Level Segregated Fit; 최악의 경우도 O(1)인 범용 힙 |

### [동기화](/ko/stdlib/sync) {#synchronization}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| spinlock | `sync/spinlock.h` | 바쁜 대기(busy-wait) 상호 배제 (`__sync_lock_test_and_set`) |
| lockfree | `sync/lockfree.h` | `LFQueue<T>` 락-프리 SPSC 링 버퍼 |
| channel | `sync/channel.h` | `Chan<T>`: `chan_*` 내장 함수 위의 타입 지정 바운디드 블로킹 MPMC 채널 |
| mpsc | `sync/mpsc.h` | `MpscQueue<T>` 스핀락으로 보호되는 MPSC 링, 논블로킹 |
| task | `sync/task.h` | 협조적 스케줄러; 타입 지정 `TaskScheduler::spawn`; `TaskRunner` 트레이트 + `run_rounds` |
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
| http | `http/http.h` | HTTP/1.1 클라이언트 + 서버: keep-alive `HttpServer`, 리다이렉트와 gzip을 처리하는 URL 클라이언트(`http_fetch`), 요청/응답 헬퍼, 청크 인코딩 |
| headers | `http/headers.h` | 대소문자를 구분하지 않는 헤더 맵 `HttpHeaders`, 헤더 파라미터 |
| router | `http/router.h`, `http/route.h` | `:param`/`*` 패턴, 404/405, 미들웨어(로거, CORS, gzip, 본문 제한)를 갖춘 `Router` |
| static_files | `http/static_files.h` | 정적 파일 제공: MIME 타입, ETag/Last-Modified/304, 바이트 범위 |
| cookie | `http/cookie.h` | Cookie 헤더 파싱, `Set-Cookie` 생성 |
| multipart | `http/multipart.h` | `multipart/form-data` 파싱 + 생성 |
| sse | `http/sse.h` | Server-Sent Events: 서버 스트림, 파서, 클라이언트 |
| date | `http/date.h` | HTTP 날짜 형식화/파싱 |
| cors | `http/cors.h` | CORS 프리플라이트 감지 + 응답 헤더 |
| jwt | `http/jwt.h` | HS256 JWT 서명/검증 (HMAC-SHA256) |
| oauth2 | `http/oauth2.h` | OAuth2 클라이언트: 인가 코드 + 리프레시 토큰 교환 (RFC 6749) |
| websocket | `http/websocket.h` | WebSocket 클라이언트 + 서버, 라우터에서의 업그레이드, 메시지 재조립 |
| rpc | `rpc/rpc.h` | gRPC에서 영감을 받은 HTTP 기반 RPC (길이 프리픽스 프레이밍, 경로 기반 디스패치) |
| server_fn | `rpc/server_fn.h` | "한 번 작성하고 어디서든 호출" 방식의 JSON 마셜링 서버 함수 (Dioxus/Leptos 스타일) |
| reactive | `reactive/signal.h` | `Signal<T>` 세밀한 반응형 상태 (WASM 클라이언트 하이드레이션) |
| wasm | `wasm/dom.h`, `wasm/hydrate.h` | [wasm32 DOM과 브라우저 API](/ko/stdlib/wasm)(이벤트, 타이머, fetch, 스토리지, history) + 시그널 하이드레이션, `wasm/safec_dom.js` 글루 포함; `wasm/wasm_rt.h`는 wasm32/wasm64용 프리스탠딩 런타임(`memory.grow` 위의 크기 클래스 `malloc`/`free`, `mem*`/`str*`/`strto*`/`qsort`, 128비트 compiler-rt 내장 함수, 트랩하는 `abort`) — wasm 타깃의 std 아카이브에만 포함됨([WebAssembly](/ko/reference/baremetal#wasm) 참고) |
| scx | 해당 없음 (트랜스파일러) | JSX/TSX 스타일 HTML 템플릿 — `.scx` 파일은 `safec`가 보기 전에 일반 SafeC로 트랜스파일됨 |

### 머신러닝 — [ML](/ko/stdlib/ml) 참고 {#machine-learning-see-ml}

### [GUI](/ko/stdlib/gui) {#gui}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| gui | `gui/gui.h` | RGBA8888 소프트웨어 프레임버퍼 위의 포터블 `GuiWindow`/`GuiEvent` API; Cocoa, Win32, X11, 베어메탈 프레임버퍼 백엔드 |
| draw | `gui/gui_draw.h` | 사각형, 테두리, 선, 내장 8x8 비트맵 텍스트 |
| font | `gui/gui_font.h` | 임의 글리프 크기의 `GuiFont` 비트맵 폰트; `SCXF` 저장/로드 |
| png | `gui/gui_png.h` | 처음부터 작성한 PNG 디코더 (자체 DEFLATE 인플레이터) |
| svg | `gui/gui_svg.h` | SVG 부분집합 렌더러 (도형, 3차 곡선 경로) |
| widget | `gui/gui_widget.h` | 리테인드 모드 위젯 트리: 스택, 버튼, 레이블, 체크박스, 텍스트 입력, 슬라이더, 커스텀 위젯 |
| fb | `gui/gui_fb.h` | 베어메탈 프레임버퍼 백엔드 설정: `gui_fb_configure`, `gui_fb_inject_event` |

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
| sample_filter | `dsp/sample_filter.h` | `SampleProcessor` / `BlockProcessor` / `Resettable` 트레이트와 이를 합성한 `SampleFilter`(15개 필터 모두); `FilterChain`, 제네릭 임펄스/스텝 응답 |
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
| hash | `crypto/hash.h` | `Hasher` / `BlockHash` 트레이트; 모든 해시에 대한 `hash_bytes` |
| hmac | `crypto/hmac.h` | 임의의 `BlockHash` 위의 `Hmac<H>`; 원샷 `hmac_sha256` |
| rng | `crypto/rng.h` | ChaCha20 CSPRNG; OS(`BCryptGenRandom`, `getentropy`, `getrandom`), `rdrand` 또는 `/dev/urandom`으로 시딩 |
| secure_alloc | `crypto/secure_alloc.h` | 해제 시 제로화를 수행하는 슬랩 얼로케이터 |
| x509 | `crypto/x509.h` | X.509 DER/ASN.1 파서; SAN, 와일드카드 호스트네임, 유효성 |
| tls | `crypto/tls.h` | TLS 1.3 레코드 레이어; AES-256-GCM 레코드 보호(RFC 8446 §5.2), 논스 XOR 시퀀스 |

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
| gpio | `hal/gpio.h` | `GpioPin<C>`: 방향, read/write/toggle, 풀업/풀다운(`GpioGeneric`, `Stm32F4Gpio`) |
| i2c | `hal/i2c.h` | `I2cBus<C>`: 폴링 마스터 — write/read/write_read/probe(`I2cGeneric`, `Stm32F4I2c`) |
| spi | `hal/spi.h` | `SpiDevice<C>`: 폴링 마스터 — transfer/write/read, 칩 셀렉트(`SpiGeneric`, `Stm32F4Spi`) |
| uart | `hal/uart.h` | `Uart<C>`: 폴링 시리얼 — 바이트/문자열 I/O, 준비 플래그(`UartGeneric`, `Pl011`, `Ns16550`, `Stm32F4Usart`) |
| timer | `hal/timer.h` | `Timer<C>`: 주기/시작/정지/읽기/플래그(`TimerGeneric`, `Stm32F4Tim`) |
| watchdog | `hal/watchdog.h` | `Watchdog<C>`: 활성화/피드/리셋 원인 확인(`WatchdogGeneric`, `Stm32F4Iwdg`) |
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
| mmu | `kernel/mmu.h` | `MmuContext<F>` — 실제 페이지 테이블(x86-64, x86, Sv39, Sv32, AArch64, ARMv7-A): map/unmap/walk/TLB/activate |
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

### [인코딩](/ko/stdlib/encoding) {#encoding}

| 모듈 | 헤더 | 설명 |
|--------|--------|-------------|
| ascii | `encoding/ascii.h` | ASCII 검증, 분류, 대소문자 변환 |
| utf8 | `encoding/utf8.h` | UTF-8 인코드/디코드/검증, 코드포인트 개수 |
| url | `encoding/url.h` | `Url` 파싱/직렬화/상대 참조 해석, `QueryParams`, 퍼센트·폼 인코딩 |
| base64 | `encoding/base64.h` | 패딩을 포함한 RFC 4648 Base64 |
| deflate | `encoding/deflate.h` | DEFLATE 압축/해제, zlib + gzip 컨테이너, CRC-32, Adler-32 |

### [유틸리티](/ko/stdlib/utilities) {#utilities}

| 헤더 | 설명 |
|--------|-------------|
| `bit.h` | 비트 조작 (C23 `<stdbit.h>` + popcount/clz/ctz/bswap 내장 함수) |
| `cmp.h` | 컴파일 타임 순서 관계: `Order` 트레이트, `Less` / `Greater` / `FnOrder` ([순서 관계](/ko/stdlib/collections#orderings) 참고) |
| `traits.h` | 여러 모듈이 공유하는 기능 트레이트: `Resettable`(아레나 할당자, DSP 필터) |
| `convert.h` | 문자열 ↔ 숫자 파싱 (C11/C17), 실패 시 `*ok` 성공 플래그 |
| `dma.h` | 캐시 일관성 DMA 버퍼 디스크립터 (64바이트 정렬) |
| `fmt.h` | 호출자가 제공한 버퍼로 서식화하는 안전한 `snprintf` 기반 서식화 |
| `heap.h` | 통합 힙: TLSF 기반 정적 버퍼(프리스탠딩) 또는 malloc/free/realloc(호스티드) |
| `log.h` | 구성 가능한 로깅, `LOG_LEVEL`이 0이면 오버헤드 없음 |
| `panic.h` | 옵트인 패닉 핸들러 — 기본값으로 무한 루프(프리스탠딩) 또는 `abort()`(호스티드) |
| `result.h` | `Result<T, E>`: 값 또는 오류를 인라인으로 저장 (`Result::new_ok` / `Result::new_err`) |
| `sys.h` | 프로세스 제어 상수 (`EXIT_SUCCESS`/`EXIT_FAILURE`), PRNG 상수 |
| `complex.h` | 복소수 (C99 `<complex.h>`), `[real, imag]` float/double 쌍 |

### [C 호환 헤더](/ko/stdlib/utilities#c-type-and-limit-headers) {#c-compatibility-headers}

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

컨테이너와 그 밖의 값을 담는 API는 요소 타입마다 단형화되는 제네릭 구조체와 함수입니다. 이들은 `?&T` 참조를 내주고(`match`, `.default(x)`, `std::expect_ref`로 좁힙니다), 값이 없을 수 있으면 `?T`를 반환하며, 타입이 지정된 `fn` 콜백을 받습니다 — 표준 라이브러리에는 `void*` 요소나 콜백 매개변수가 없습니다. `T`는 변수에 한 번만 쓰고, 생성자는 그 대상 타입에서 이를 추론합니다:

```c
#include <std/collections/vec.h>

int main() {
    struct Vec<int> v = std::Vec::new();
    v.push(42);
    v.push(100);
    match (v.get(0UL)) {
        case some(x): *x = *x + 1;      // 43, written in place
        case null:    { }
    }
    int last = v.pop().default(-1);     // 100
    v.free();
    return last == 100 ? 0 : 1;
}
```

컨테이너가 *서로 다른* 컨텍스트 타입의 콜백을 동시에 담아야 하는 경우(타이머 휠, 위젯 트리, 여러 드라이버를 마운트하는 VFS), 슬롯은 그 쌍을 타입 소거된 형태로 저장하지만, 그것을 채우는 유일한 방법은 짝이 맞는 `fn(?&T)`와 `?&T`를 받는 제네릭 메서드 — `TimerWheel::add`, `Widget::on_click`, `Vfs::mount`, `TaskScheduler::spawn` — 이므로 모든 호출은 여전히 컴파일 타임에 검사됩니다. `std/`에 남아 있는 `void*` 선언은 C ABI 경계입니다: libc/OS extern, `mem.h`의 원시 바이트 할당자, MMIO 기본 주소, GPU/WASM 외부 인터페이스.

## 표준 라이브러리 포함하기 {#including-the-standard-library}

**개별 모듈:**
```c
#include <std/mem.h>
#include <std/io.h>
#include <std/collections/vec.h>
```

**대부분의 모듈을 한 번에:**
```c
#include <std/prelude.h>
```

`safeguard` 패키지 매니저로 빌드할 때는 프로그램이 포함하는 std 모듈(그리고 `new`, 채널처럼 코드가 include 없이 호출하는 모듈)이 `build/deps/libsafec_std.a`로 컴파일되어, 그 모듈에 필요한 시스템 라이브러리와 함께 링크됩니다. 릴리스 설치본에서는 미리 빌드된 std 아카이브에서 가져옵니다([표준 라이브러리 모듈](/ko/advanced/safeguard#standard-library-linking) 참고).

수동으로 빌드할 때는 SafeC 체크아웃을 include 경로에 두고(헤더 이름은 `<std/...>`), std 전체의 아카이브(`[build] std = "all"`인 프로젝트를 `safeguard build`하면 만들어지며, 모든 `std/**/*.sc`로 직접 빌드해도 됩니다)를 사용하는 모듈의 시스템 라이브러리(`safeguard std-modules std/x.h`가 나열합니다)와 함께 링크합니다:
```bash
safec myfile.sc -I /path/to/SafeC -o myfile.ll
clang myfile.ll /path/to/libsafec_std.a -lm -lpthread -o myfile
```
