---
title: "다음 릴리스 미리 보기: 1.0.0-beta-6 이후 병합된 모든 것"
date: 2026-10-26
author: SafeC 팀
tags: [unreleased, std, compiler, safety, breaking, tools]
summary: "다음 릴리스에 포함될, SafeC main 브랜치의 모든 변경 기록입니다. 웹 스택, 비동기, GUI 툴킷, 오디오와 MIDI, HTTPS, safeguard doc과 publish, 온라인 플레이그라운드를 움직이는 셀프 호스팅 컴파일러, 그리고 두 가지 호환성을 깨는 변경 — void*와 포인터 + 길이 쌍이 없는 std API, 컴파일러가 강제하는 Drop과 Send/Sync — 을 다루며, 마이그레이션 가이드를 포함합니다."
---

SafeC의 `main` 브랜치는 [1.0.0-beta-6](/ko/blog/releases/v1.0.0-beta-6) 이후 많이 나아갔습니다. 이 글은 다음 릴리스에 들어갈 내용의 기록입니다. 그 뒤로 병합된 풀 리퀘스트 [#56](https://github.com/safec-org/SafeC/pull/56)부터 [#79](https://github.com/safec-org/SafeC/pull/79)까지를 영역별로 묶었습니다. 그중 두 가지는 **호환성을 깨는 변경**이며, 컴파일러가 영향을 받는 호출 지점마다 오류를 보고합니다. 코드를 고치는 방법은 끝의 [마이그레이션 가이드](#migration)에 있습니다.

이 사이트가 [변경 내역](/ko/changelog)으로 보여 주는 SafeC의 [`CHANGELOG.md`](https://github.com/safec-org/SafeC/blob/main/CHANGELOG.md)는 후반부의 변경 — `safeguard doc`과 `publish`, scc, 슬라이스 API, 소유권 검사 — 을 *Unreleased* 아래에 개별 시그니처 수준까지 기록합니다. 이번 주기의 앞부분 — 웹 스택, 비동기, GUI 툴킷, 오디오, HTTPS — 은 [이번 주기의 SafeC](/ko/blog/this-release-cycle)에 더 자세히 정리되어 있습니다.

## 한눈에 보기 {#at-a-glance}

| 영역 | 변경 내용 | 풀 리퀘스트 |
|---|---|---|
| [웹 스택](#web) | `std/http` 서버, 라우터, WebSocket, SSE; URL·DEFLATE 코덱; wasm용 DOM 상호 운용 | [#57](https://github.com/safec-org/SafeC/pull/57) |
| [비동기](#async) | `goto` 상태 머신으로 구현한 Rust 스타일 future, 실행기, 비동기 TCP | [#61](https://github.com/safec-org/SafeC/pull/61) |
| [GUI](#gui) | 벡터 캔버스, 위젯, 여러 창; X11, Wayland, Win32, Cocoa, 웹; GPU 프레젠터 | [#63](https://github.com/safec-org/SafeC/pull/63), [#65](https://github.com/safec-org/SafeC/pull/65) |
| [오디오와 MIDI](#audio) | ALSA, JACK, Core Audio, WASAPI, Web Audio 위의 단일 콜백 API | [#59](https://github.com/safec-org/SafeC/pull/59) |
| [HTTPS](#https) | `std/tls`: OS의 TLS, 대체 경로로 SafeC TLS 1.3 | [#66](https://github.com/safec-org/SafeC/pull/66) |
| [safeguard doc과 publish](#safeguard-doc) | rustdoc 스타일 API 레퍼런스; CI를 포함한 라이브러리 배포 | [#69](https://github.com/safec-org/SafeC/pull/69) |
| [scc와 플레이그라운드](#scc) | SafeC로 작성되어 WebAssembly로 컴파일하는 SafeC 컴파일러 | [#70](https://github.com/safec-org/SafeC/pull/70) |
| [`void*` 대신 슬라이스](#slices) — **호환성 깨짐** | std 공개 API가 슬라이스와 타입 있는 소거를 사용 | [#76](https://github.com/safec-org/SafeC/pull/76) |
| [Drop, Send, Sync](#ownership) — **호환성 깨짐** | 컴파일러가 검사하는 소유권과 스레드 안전성 | [#77](https://github.com/safec-org/SafeC/pull/77) |
| [수정 사항](#fixes) | 코드 생성, 안전성 검사의 빈틈, Windows·macOS CI | [#64](https://github.com/safec-org/SafeC/pull/64), [#68](https://github.com/safec-org/SafeC/pull/68), [#71](https://github.com/safec-org/SafeC/pull/71)–[#75](https://github.com/safec-org/SafeC/pull/75), [#78](https://github.com/safec-org/SafeC/pull/78), [#79](https://github.com/safec-org/SafeC/pull/79) |

또한 릴리스는 이제 std를 플랫폼마다 섹션(`core`, `collections`, `gui` 등)당 아카이브 하나로 미리 빌드해 제공하며, `install.sh --std=...` / `install.ps1 -Std ...`로 고릅니다([#56](https://github.com/safec-org/SafeC/pull/56)). 이 웹사이트도 이제 [SafeC 프로그램으로 빌드](/ko/blog/new-website)됩니다([#58](https://github.com/safec-org/SafeC/pull/58)).

## 웹 스택 {#web}

[`std/http`](/ko/stdlib/http)([#57](https://github.com/safec-org/SafeC/pull/57))는 실제 서비스를 위한 스택이 되었습니다: 구조화된 헤더, chunked 전송을 지원하는 keep-alive `HttpServer`, 미들웨어와 경로 패턴을 갖춘 `Router`, 정적 파일, 쿠키, multipart 폼, server-sent events, WebSocket 업그레이드, 리다이렉트를 따라가고 gzip을 푸는 URL 클라이언트. 함께 [`std/encoding`](/ko/stdlib/encoding)의 URL(RFC 3986)과 DEFLATE / zlib / gzip 코덱이 들어왔습니다. 브라우저 쪽에서는 [`std/wasm`](/ko/stdlib/wasm)이 이벤트, 타이머, fetch, 스토리지, 히스토리 등 DOM·브라우저 API와 상호 운용하게 되었고, JavaScript 글루는 std에 포함됩니다. `examples/fullstack_demo`는 Router 서버와 SafeC→wasm 클라이언트를 합칩니다.

## 비동기: `goto` 상태 머신으로 된 future {#async}

[`std/async`](/ko/stdlib/async)([#61](https://github.com/safec-org/SafeC/pull/61))는 Rust의 `Future` / `Poll` / `Waker` / `Context` 모델을 언어 코루틴 없이 라이브러리로 가져옵니다. `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN` 매크로가 `poll` 메서드를 가진 구조체를 `goto` 기반의 재개 가능한 상태 머신으로 바꾸고, await를 넘어 살아 있는 지역 변수는 모두 구조체에 저장됩니다. 그 위에 타이머, `join` / `select` / timeout 조합기, 채널, `Mutex`, `Semaphore`, `Notify`, 취소, 베어메탈과 wasm32에서도 도는 힙 없는 `LocalExecutor`, 멀티스레드 `ThreadPool`, epoll·kqueue·WSAPoll 리액터 위의 비동기 TCP를 제공하는 `IoDriver` + `Runtime`이 있습니다.

## GUI 툴킷 {#gui}

[#63](https://github.com/safec-org/SafeC/pull/63)에서 [`std/gui`](/ko/stdlib/gui)가 툴킷이 되었습니다: TrueType 텍스트를 갖춘 안티에일리어싱 [벡터 캔버스](/ko/stdlib/gui-graphics), 레이아웃과 테마를 지원하는 리테인드 [위젯 툴킷](/ko/stdlib/gui-widgets), 여러 창 애플리케이션을 위한 `GuiApp` — 모두 X11, Win32, Cocoa 창 계층 위의 `GuiBackend` 인터페이스에 있습니다. [#65](https://github.com/safec-org/SafeC/pull/65)는 [더 많은 플랫폼](/ko/stdlib/gui-platforms)을 더했습니다: Linux에서 실행 시점에 선택되는 Wayland, DOM 상호 운용과 IME 텍스트 입력을 갖추고 `<canvas>`나 WebGL로 그리는 WebAssembly 백엔드, OpenGL ES(EGL)·Metal·Direct3D 11 GPU 프레젠터. `safeguard bundle --target wasm32`는 GUI 앱을 웹 페이지로 패키징합니다.

## 오디오와 MIDI {#audio}

[`std/audio`](/ko/stdlib/audio)([#59](https://github.com/safec-org/SafeC/pull/59))는 오디오 스트림과 MIDI 포트를 위한 콜백 기반 API 하나로, 포맷 변환을 포함합니다. 오디오는 ALSA, JACK, Core Audio, WASAPI, Web Audio, 테스트용 더미 장치에서, MIDI는 ALSA 시퀀서, JACK, Core MIDI, WinMM, Web MIDI에서 동작합니다.

## HTTPS {#https}

[`std/tls`](/ko/stdlib/tls)([#66](https://github.com/safec-org/SafeC/pull/66))는 운영체제의 TLS — OpenSSL(실행 시점에 로드), SChannel, SecureTransport — 가 있으면 그것을 쓰고, 없으면 SafeC로 작성한 TLS 1.3 클라이언트·서버로 대체하며, 시스템 신뢰 루트에 대한 X.509 경로 검증을 수행합니다. 이를 위해 [`std/crypto`](/ko/stdlib/crypto)에 X25519, P-256 / P-384 ECDH와 ECDSA, RSA(PKCS#1 v1.5와 PSS), HKDF, ChaCha20이 추가되었습니다. `http_fetch` 등은 `https://`를, `WsConn`은 `wss://`를 지원하고, `HttpServer`는 `TlsServerConfig`로 HTTPS를 서비스합니다.

## `safeguard doc`과 `safeguard publish` {#safeguard-doc}

[`safeguard doc`](/ko/advanced/safeguard#safeguard-doc)([#69](https://github.com/safec-org/SafeC/pull/69))은 rustdoc처럼 패키지의 API 레퍼런스를 만듭니다: 강조 표시된 선언이 있는 항목별 페이지, 문서 내 링크(`[Vec::push]`)와 예제를 담은 마크다운 [문서 주석](/ko/advanced/doc-comments), 헤더당 모듈 하나, 소스 페이지, 클라이언트 측 검색(wasm32로 컴파일한 SafeC), 라이트·다크 테마, 결과물의 링크 검사. `--serve`는 이를 서비스하고 `--std`는 std 자체를 문서화합니다 — 이 사이트의 [std API 레퍼런스](/api/)가 그렇게 만들어집니다. 그 밑에는 실제 프런트엔드에서 선언과 문서 주석을 JSON으로 내보내는 새 `safec --emit-doc-json`이 있습니다.

[`safeguard publish`](/ko/advanced/safeguard#safeguard-publish)는 라이브러리의 git 저장소를 배포할 수 있게 준비합니다: README / LICENSE / 메타데이터 검사, semver를 위한 `--bump`, 그리고 Linux·macOS·Windows에서 테스트하고 태그가 붙으면 미리 빌드한 정적·공유 라이브러리를 릴리스에 첨부하고 문서를 Pages에 배포하는 CI 생성(기본은 GitHub Actions, `--ci=`로 GitLab CI, Gitea, Forgejo). `--write`를 주지 않으면 드라이 런입니다. `Package.toml`에는 `repository`, `homepage`, `documentation`, `readme`, `keywords`와 `[publish]` 테이블이 생겼고, `safeguard build --crate-type staticlib|cdylib|bin`은 `[build] crate_type`을 덮어씁니다.

## scc와 온라인 플레이그라운드 {#scc}

**scc**([#70](https://github.com/safec-org/SafeC/pull/70))는 SafeC로 작성한 SafeC 컴파일러로, SafeC의 새 [`selfhost/`](https://github.com/safec-org/SafeC/tree/main/selfhost) 디렉터리에 있습니다. 자체 렉서, 전처리기, 파서, 의미 분석(safec의 안전성 검사와 진단 포함), wasm 백엔드를 거쳐 LLVM과 링커 없이 SafeC를 곧바로 WebAssembly 바이너리로 컴파일합니다. C++ `safec`로 네이티브와 WebAssembly 양쪽으로 빌드되며, 자기 자신도 컴파일합니다: `selfhost/bootstrap.sh`는 스스로 컴파일한 컴파일러가 자신을 바이트 단위로 똑같이 재현하는지 확인합니다. 차등 테스트 하네스가 Book, 레퍼런스 프로그램, 코퍼스를 두 컴파일러로 컴파일해 결과를 비교하며, 새 `selfhost` CI 작업에서 돌아갑니다 — [아래](#fixes)의 가변 인자 승격 버그도 여기서 발견되었습니다.

scc는 이 사이트의 [플레이그라운드](/ko/playground)를 움직입니다. 이제 *Run* 버튼이 시간·메모리 제한이 있는 Web Worker 안에서, 프로그램을 전부 브라우저에서 컴파일하고 실행합니다. scc는 `selfhost/SUBSET.md`에 나열된 언어 부분 집합을 받아들이며 소유권·스레드 안전성 검사는 하지 않습니다. 그 검사와 전체 std가 필요하면 로컬에서 빌드하세요.

## 호환성 깨짐: `void*`와 포인터 + 길이 쌍이 없는 std API {#slices}

[#76](https://github.com/safec-org/SafeC/pull/76)은 std의 공개 선언에서 `void*`, 원시 `T*` 버퍼, `(포인터, 길이)` 쌍을 모두 없앴습니다:

- **버퍼는 [슬라이스](/ko/reference/types#slice-types)입니다.** `[]T`는 함수가 쓰는 버퍼, `[]const T`는 읽기만 하는 버퍼입니다. 슬라이스는 길이를 갖고 있고 인덱싱은 경계 검사됩니다. 고정 크기 인자(키, 논스, 다이제스트)는 진입 시 검사되어, 너무 짧으면 그 너머를 읽거나 쓰는 대신 패닉합니다. `std::str_bytes("text")`는 문자열의 바이트를 `[]const char`로 주며, `[]const unsigned char`를 기대하는 곳 어디서나 받아들여집니다.
- **타입 소거에 타입이 있습니다.** 실행기, waker, 타이머, 위젯, VFS, 오디오·MIDI 콜백, HTTP 핸들러, DOM 리스너, 스레드 진입점처럼 std가 서로 다른 컨텍스트 타입의 콜백을 나란히 저장해야 하는 곳의 슬롯은 `?&Erased`(`std/erased.h`)이며, 일치하는 `fn(?&T)` / `?&T`를 받는 제네릭 메서드로만 채워집니다. 불투명 핸들도 타입이 있는 구조체입니다.
- **언어도 그에 맞게 자랐습니다:** [읽기 전용 슬라이스](/ko/reference/types#read-only-slices) `[]const T`, [옵셔널 슬라이스](/ko/reference/types#optional-slices) `?[]T`와 널 가능 함수 참조 `?fn R(Args)`, 슬라이스를 반환하는 함수와 슬라이스 필드, 범위 검사되는 재슬라이싱(`unsafe` 밖에서 `i > j`나 `j > len`이면 `a[i..j]`가 트랩), `char`·`signed char`·`unsigned char` 사이에서 상호 변환되는 바이트 슬라이스. 슬라이스를 포인터로 캐스트하는 것은 이제 오류이며, `s.ptr`을 쓰세요.
- std에 새로 추가: `Erased`, `S24`(패킹된 24비트 오디오 샘플로, 24비트 버퍼는 `[]S24`), `struct TimeFields`, `Vec::from_slice` / `as_slice`, `String::from_bytes` / `push_bytes` / `as_bytes`, 그리고 `StaticVec`, `StaticMap`, `RingBuffer`, `LFQueue`와 할당기의 `over([]T)` 생성자.

SafeC 테스트 스위트의 린트는 이제 std 공개 선언에 원시 포인터가 다시 나타나면 빌드를 실패시킵니다.

## 호환성 깨짐: 컴파일러가 강제하는 Drop, Send, Sync {#ownership}

[#77](https://github.com/safec-org/SafeC/pull/77)은 자원 소유권과 스레드 안전성을 컴파일 타임 검사로 만듭니다. 전체 규칙은 [소유권과 Drop](/ko/reference/ownership)과 [스레드 안전성](/ko/reference/threads)을 보세요.

- **`Drop`.** `void drop()` 메서드를 가진 타입, 또는 그런 값을 담은 타입은 모든 경로 — `return`, `break`, `continue`, `goto`, `match`, `defer`, 루프 — 에서 정확히 한 번 drop되거나 move되어야 합니다. 누수, drop·move 후 사용, 이중 drop, 살아 있는 값 덮어쓰기, 빌린 메모리에서 move해 나오기는 컴파일 오류입니다. 검사는 제네릭 인스턴스마다 이루어지고, 생성 코드에는 아무것도 삽입하지 않습니다.
- `consume` 메서드(`int close() consume;`)는 `drop()`처럼 수신자를 소멸시킵니다. `drop(x)`는 모든 타입에 대한 drop 글루이고, `std::replace`와 `std::swap`은 `std/ownership.h`에 있으며, `forget(x)`는 `unsafe` 안에서만 허용됩니다. 메서드가 수신자 안을 가리키도록 반환한 참조(`Vec::at()`, `MutexGuard::get()`)는 수신자로부터 빌린 것입니다.
- **`Send` / `Sync`**는 각 타입의 구조로부터 유도되고, `unsafe impl Send;` / `impl !Send;`로 재정의할 수 있으며, 제네릭 바운드로 쓸 수 있습니다. `spawn`은 Sync인 정적 데이터만 받고, `spawn_scoped`는 join될 때까지 지역 변수를 빌려주며, `chan_send`는 Send 값을 move합니다.
- `std/thread.h`에 새로 추가: `thread_spawn` / `SpawnedThread<T, R>`(인자를 스레드로 move하고 `join()`이 결과를 반환), `Mutex<T>` / `MutexGuard<T>`(값은 가드를 통해서만 접근하며 가드는 `drop()`에서 잠금 해제), `RwLock<T>`. `std/atomic.h`에는 `AtomicInt`와 `AtomicLong`이 있습니다.
- 컬렉션, `File`, 소켓, TLS, HTTP, 스레드, 동기화 프리미티브, GUI, 오디오, 비동기, ML 컨테이너 등 std의 소유 타입은 `Drop`을 구현합니다. `free()` / `close()` / `destroy()`는 `consume` 별칭으로 남습니다. `ThreadPool::spawn`, `Chan<T>`, `AsyncChannel<T>`, `Oneshot<T>`는 Send 값을 요구하고, GUI 창은 `!Send`이며, 끝나지 않은 `AsyncTask`를 drop하면 실행기가 해제된 메모리를 poll하게 두는 대신 패닉합니다.

std 전체에 검사를 켜자 실제 버그가 발견되어 고쳐졌습니다: TLS 오류 경로, GUI SVG 렌더러, 위젯·PNG 로더, 정적 파일·라우터 헬퍼, `Audio::open`, `std/ml`의 LLM·LoRA 경로, safec-doc의 링크 검사기에 있던 누수, 이중 해제, 해제 후 사용.

## 주요 수정 사항 {#fixes}

- **가변 인자 호출**은 C가 요구하는 대로 `bool`, `char`, `short`를 `int`로 승격합니다. 전에는 `printf("%d", !x)`가 254를 출력했습니다([#71](https://github.com/safec-org/SafeC/pull/71)).
- **안전성 검사의 빈틈을 막았습니다**([#72](https://github.com/safec-org/SafeC/pull/72)): `const &T`를 통한 필드 쓰기를 거부하고, `&stack` / `&arena` 탈출 검사가 `->`를 따라가며, `Vec::at`, `HashMap`, `StrMap`이 준 원소 참조를 `push`나 재해싱하는 삽입 너머까지 들고 있으면 보고합니다. 참조를 반환하는 호출 체인을 통한 변경(`v.at(i).field.push(x)`)은 이제 임시 복사본이 아니라 실제 필드에 닿습니다.
- **메서드 호출**: 참조로 반환된 호출 결과의 필드에 대한 메서드 호출은 참조 대상에 작용하고, 재귀 함수에서 `StrMap`을 쓸 때의 오탐이 사라졌습니다([#73](https://github.com/safec-org/SafeC/pull/73)). `atomic_cas` 인자는 가리키는 타입에 대해 검사됩니다([#74](https://github.com/safec-org/SafeC/pull/74)).
- **C 헤더 가져오기**는 128비트 float와 `_Complex` 선언을 건너뛰어 최신 glibc 헤더도 가져올 수 있고, `safeguard test`는 Windows에서 테스트의 `.exe`를 실행합니다([#75](https://github.com/safec-org/SafeC/pull/75)). 라이브러리 패키지에서 `safeguard test`는 라이브러리를 각 테스트에 링크합니다.
- **Windows와 macOS:** 제네릭 함수는 ABI 낮추기를 거쳐도 COMDAT을 유지하고, `std/sched`는 `WSAStartup`에 올바른 크기의 `WSADATA`를 넘기며, WSAPoll 리액터는 디스패치 후 감시 목록을 읽지 않습니다([#64](https://github.com/safec-org/SafeC/pull/64)). ABI 낮추기된 함수는 모듈 안의 제자리를 지키고([#79](https://github.com/safec-org/SafeC/pull/79)), std 모듈 검사는 Mach-O와 COFF에서 섹션 간에 반복되는 제네릭 인스턴스를 허용합니다([#68](https://github.com/safec-org/SafeC/pull/68)).

## 마이그레이션 {#migration}

두 호환성 깨짐 모두 분명하게 실패합니다: 영향을 받는 모든 호출은 컴파일 오류(인자 개수나 타입 불일치, 또는 선언 위치를 가리키는 노트가 붙은 소유권 오류)이고, [변경 내역](/ko/changelog)에 적힌 곳 말고는 조용히 동작이 바뀌는 것은 없습니다. 흔한 수정은 다음과 같습니다:

| 이전 | 이후 |
|---|---|
| `f.read(buf, n)`, `w.write(buf, n)` | `f.read(buf[..])`, `w.write(buf[0..n])` |
| `sha256(data, len, digest)` | `sha256(bytes, digest[..])` — `bytes`는 `buf[0..len]`이나 `std::str_bytes(s)` 같은 슬라이스 |
| `str_copy(dst, src, size)` | `str_copy(dst[..], src)` — 다 들어가면 1, 잘리면 0을 반환하고 항상 NUL로 끝냄 |
| `safe_memcpy(dst, src, bytes)` | `safe_memcpy(dst[..], src[..])` — 바이트가 아니라 **원소**를 세고, 두 길이 중 작은 만큼 복사 |
| `time_gmtime(t, fields)`(int 9개 배열) | `struct TimeFields tm = time_gmtime(t);` |
| `Vec::from_arr(p, n)` | `Vec::from_slice(a[..])` |
| `String::from_n(p, n)` / `push_n` | `String::from_bytes(s)` / `push_bytes(s)` |
| `?&T`를 반환하던 `sys_bsearch(...)` | 인덱스를 `?unsigned long`으로 반환 |
| `void*` 콜백 컨텍스트 | 타입 있는 `?&T` — std의 제네릭 메서드가 `?&Erased`로 저장 |
| 사용자 할당자의 `?&heap void alloc(size, align)` / `dealloc(?&heap void)` | 둘 다 `?&heap unsigned char` (std의 할당자와 `heap_alloc`은 바이트를 내줍니다. `alloc_t` / `dealloc_t`는 그대로) |
| `(T*)slice` | `slice.ptr` |
| 어떤 경로에서 해제되지 않는, `free()` / `close()`가 있는 값 | 만든 직후 `defer v.drop();`, 또는 모든 경로에서 해제 |
| 포인터를 공유하는 `thread_create(&tid, work, ctx)` | 데이터를 move해 넣는 `thread_spawn(fn, value)`, 공유 데이터에는 `Mutex<T>` / `spawn_scoped` |

흔한 소유권 오류와 그 해결:

```c norun
int sum_positive([]const int xs) {
    struct Vec<int> v = std::Vec::new();
    for (unsigned long i = 0UL; i < xs.len; i = i + 1UL) {
        if (xs[i] < 0) { return -1; }   // error: value of Drop type 'Vec<int>' leaks when the
        v.push(xs[i]);                  //   function returns: 'v' is never dropped;
    }                                   //   call v.drop() or add 'defer v.drop()'
    // ...
    v.drop();
    return 0;
}
```

선언 바로 뒤에 `defer v.drop();`을 추가하고 마지막 `v.drop()`을 지우면 모든 경로에서 `v`가 해제됩니다.

[플레이그라운드](/ko/playground)에서 실행할 수 있는 슬라이스 예제 — 읽기 전용 슬라이스 매개변수, 경계가 있는 `str_copy`, 원소 단위 `safe_memcpy`:

```c
#include <std/str.h>
#include <std/mem.h>
extern int printf(const char* fmt, ...);

long sum([]const int xs) {           // a read-only slice: the length comes with it
    long s = 0L;
    for (unsigned long i = 0UL; i < xs.len; i = i + 1UL) { s = s + (long)xs[i]; }
    return s;
}

int main() {
    char name[8];
    int fit = std::str_copy(name[..], "truncated!");   // bounded by the slice
    printf("fit=%d name=%s\n", fit, name);

    int src[4] = {1, 2, 3, 4};
    int dst[3];
    unsigned long n = std::safe_memcpy(dst[..], src[..]);   // elements, not bytes
    printf("copied=%lu sum=%ld\n", n, sum(dst[..]));
    return 0;
}
```

출력은 `fit=0 name=truncat`(NUL을 포함해 8바이트 버퍼에 맞게 잘림)과 `copied=3 sum=6`입니다. 암호화와 데이터를 소유하는 스레드를 쓰는 다음 예제는 전체 std가 필요하므로 로컬에서 `safeguard run`으로 빌드하세요:

```c norun
#include <std/crypto/sha256.h>
#include <std/str.h>
#include <std/thread.h>
#include <std/collections/vec.h>
extern int printf(const char* fmt, ...);

int count(struct Vec<int> v) {
    int n = (int)v.len;
    v.drop();                       // the thread owns v and must drop it
    return n;
}

int main() {
    unsigned char digest[32];
    std::sha256(std::str_bytes("abc"), digest[..]);   // was sha256(data, len, digest)

    struct Vec<int> v = std::Vec::new();
    v.push(1); v.push(2);
    struct SpawnedThread<struct Vec<int>, int> t = std::thread_spawn(count, v);
    // v.push(3);                   // error: use of 'v' after it was moved
    int len = t.join();             // join() consumes t and returns count's result

    printf("digest[0]=0x%02x len=%d\n", (unsigned)digest[0], len);
    return 0;
}
```

출력은 `digest[0]=0xba len=2`이고, `v.push(3)`의 주석을 풀면 `thread_spawn` 호출을 가리키는 노트와 함께 `use of 'v' after it was moved` 컴파일 오류가 납니다.

마이그레이션할 때 볼 곳:

- [타입: 슬라이스](/ko/reference/types#slice-types), [읽기 전용 슬라이스](/ko/reference/types#read-only-slices), [옵셔널 슬라이스](/ko/reference/types#optional-slices)
- [소유권과 Drop](/ko/reference/ownership) — 규칙, 모든 오류, [consume 메서드](/ko/reference/ownership#consume-methods), [`forget`](/ko/reference/ownership#forget)
- [스레드 안전성](/ko/reference/threads) — [Send와 Sync](/ko/reference/threads#send-and-sync), [`thread_spawn`](/ko/reference/threads#thread-spawn), [`Mutex<T>`](/ko/reference/threads#mutex)
- 사용하는 모듈의 std 페이지: [mem](/ko/stdlib/mem), [str](/ko/stdlib/str), [io](/ko/stdlib/io), [collections](/ko/stdlib/collections), [crypto](/ko/stdlib/crypto), [thread](/ko/stdlib/thread), [async](/ko/stdlib/async), [audio](/ko/stdlib/audio), [dsp](/ko/stdlib/dsp), [http](/ko/stdlib/http), 그리고 [std API 레퍼런스](/api/)
- [변경 내역](/ko/changelog)의 모듈별 *Breaking changes* 목록

## 사용해 보기 {#try}

이 모든 것이 지금 `main`에 있습니다. 다음 릴리스 전까지는 [소스에서 SafeC를 빌드](/ko/install#quick-install)하거나 [플레이그라운드](/ko/playground)에서 언어를 써 보세요. 의존하던 무언가가 컴파일러의 설명 없이 깨졌다면 [이슈를 열어](https://github.com/safec-org/SafeC/issues) 주세요.
