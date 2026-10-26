---
title: "이번 주기의 SafeC: 비동기, 오디오, 웹 스택, GUI 툴킷, HTTPS"
date: 2026-10-25
author: SafeC 팀
tags: [release, std, async, audio, web, gui]
summary: 1.0.0-beta-6 이후 SafeC 표준 라이브러리에 Rust 스타일 비동기, 실시간 오디오와 MIDI, HTTPS를 포함한 완전한 웹 스택, Wayland와 브라우저에서도 도는 안티에일리어싱 GUI 툴킷, 섹션 단위로 미리 빌드된 std가 추가되었습니다. 문서는 이제 SafeC로 빌드되고, Windows CI가 다시 통과합니다.
---

[1.0.0-beta-6](/ko/blog/releases/v1.0.0-beta-6) 이후 몇 주는 표준 라이브러리에 쓰였습니다. 이제 HTTP를 서비스하고, 창을 열고, 오디오를 재생하고, future를 await하는 프로그램을 `std`만으로 작성할 수 있습니다. 아래 내용은 모두 `main`에 병합되었고(풀 리퀘스트 [#56](https://github.com/safec-org/SafeC/pull/56)–[#67](https://github.com/safec-org/SafeC/pull/67)) 다음 릴리스에 포함됩니다. [변경 내역](/ko/changelog)의 *Unreleased*에서 확인할 수 있습니다.

## std/async: 언어 코루틴 없는 future {#async}

[`std/async`](/ko/stdlib/async)([#61](https://github.com/safec-org/SafeC/pull/61))는 Rust의 `Future` / `Poll` / `Waker` / `Context` 모델을 라이브러리로 SafeC에 가져옵니다. future는 `poll` 메서드를 가진 구조체이고, `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN` 매크로가 이를 `goto` 기반의 재개 가능한 상태 머신으로 바꿉니다. await를 넘어 살아 있는 지역 변수는 모두 구조체에 저장됩니다.

그 위에 타이머, `join` / `select` / timeout 조합기, 채널, `Mutex`, `Semaphore`, `Notify`, 취소, 베어메탈과 wasm32에서도 도는 결정론적이고 힙을 쓰지 않는 단일 스레드 `LocalExecutor`, 멀티스레드 `ThreadPool`, 호스트 `block_on`, epoll·kqueue·WSAPoll 리액터 위의 비동기 TCP를 제공하는 `IoDriver` + `Runtime`이 있습니다. 컴파일러는 이를 위해 `goto` 규칙(중복 레이블, 선언을 건너뛰는 점프, `return` 뒤의 레이블)을 익혔고, `safeguard`에는 매크로 기반 패키지를 위한 `[build] compat_preprocessor`가 생겼습니다. `examples/async_demo`를 보세요.

## std/audio: 실시간 오디오와 MIDI {#audio}

[`std/audio`](/ko/stdlib/audio)([#59](https://github.com/safec-org/SafeC/pull/59))는 오디오 스트림과 MIDI 포트를 위한 콜백 기반 API 하나로, 포맷 변환을 포함합니다. 오디오 백엔드는 ALSA, JACK, Core Audio, WASAPI, Web Audio, 그리고 테스트용 더미 장치. MIDI는 메시지 타입·파서·락프리 큐로 된 MIDI 코어 위에 ALSA 시퀀서, JACK, Core MIDI, WinMM, Web MIDI가 있습니다. 런타임 테스트는 더미 백엔드, ALSA의 null·file PCM, JACK 루프백, MIDI를 다루며 Windows는 Wine에서 확인합니다. `examples/audio_demo`는 장치를 나열하고, 사인파를 재생하고, MIDI를 모니터링합니다. 그 과정에서 `safeguard`는 `_macos`, `_wasm` 플랫폼별 std 소스를 지원하게 되었습니다.

## 웹 스택 {#web}

[#57](https://github.com/safec-org/SafeC/pull/57)은 [`std/http`](/ko/stdlib/http)를 실제 서비스를 위한 스택으로 다시 만들었습니다: 구조화된 헤더와 요청/응답 헬퍼, chunked 전송을 지원하는 keep-alive `HttpServer`, 미들웨어와 경로 패턴을 갖춘 `Router`, 정적 파일, 쿠키, multipart 폼, server-sent events, WebSocket 업그레이드, 그리고 리다이렉트를 따라가고 gzip을 푸는 URL 클라이언트. 함께 `std/encoding/url`(RFC 3986 파싱과 해석, 쿼리 매개변수, 폼 인코딩)과 `std/encoding/deflate`(DEFLATE, zlib, gzip, CRC-32, Adler-32)가 들어왔습니다.

브라우저 쪽에서는 [`std/wasm`](/ko/stdlib/wasm)이 이벤트, 타이머, fetch, 스토리지, 히스토리 등 DOM과 브라우저 API 전체와 상호 운용하게 되었고, JavaScript 글루가 std에 포함되어 Node에서 테스트됩니다. `examples/fullstack_demo`는 둘을 합칩니다: Router 서버와 SafeC→wasm 클라이언트.

## std/gui: GUI 툴킷 {#gui}

[#63](https://github.com/safec-org/SafeC/pull/63)은 [`std/gui`](/ko/stdlib/gui)를 툴킷으로 만들었습니다: TrueType 폰트 로딩·커닝·텍스트 레이아웃을 갖춘 안티에일리어싱 벡터 캔버스([GUI 그래픽](/ko/stdlib/gui-graphics)), 레이아웃과 테마를 지원하는 리테인드 [위젯 툴킷](/ko/stdlib/gui-widgets), 여러 창 애플리케이션을 위한 `GuiApp` — 모두 이식 가능한 창 계층 아래의 `GuiBackend` 인터페이스 위에 있습니다. 캔버스 테스트는 골든 이미지와 비교하고, 위젯 테스트는 헤드리스 백엔드에서, 두 창 `GuiApp` 테스트는 X11(Xvfb)과 Win32(Wine)에서 돌아갑니다. `examples/gui_hello`와 모든 위젯을 보여 주는 `examples/gui_gallery`를 실행해 보세요.

이어서 [#65](https://github.com/safec-org/SafeC/pull/65)가 툴킷을 더 많은 플랫폼으로 넓혔습니다: Linux에서 실행 시점에 선택되는 Wayland 백엔드, 페이지 안의 창·IME 텍스트 입력·DOM 상호 운용과 함께 `<canvas>`나 WebGL로 그리는 WebAssembly 백엔드, 그리고 OpenGL ES(EGL), Metal, Direct3D 11 GPU 프레젠터. `safeguard bundle --target wasm32`는 GUI 앱을 실행 가능한 웹 페이지로 패키징합니다.

## 섹션 단위로 미리 빌드된 std {#sections}

이제 릴리스는 플랫폼마다 표준 라이브러리를 *섹션* — `core`, `collections`, `gui`, `ml` 같은 std의 최상위 디렉터리 — 당 아카이브 하나로 미리 빌드해 제공합니다([#56](https://github.com/safec-org/SafeC/pull/56)). `safeguard`는 프로젝트가 쓰는 섹션을 컴파일하는 대신 링크하고, `install.sh --std=...` / `install.ps1 -Std ...`로 설치할 섹션을 고릅니다. std는 이제 디버그 빌드에서도 항상 최적화되어 컴파일되며, 릴리스 바이너리는 macOS 11과 glibc 2.25(Debian 10, Ubuntu 18.04, RHEL 8 이후)를 대상으로 합니다.

## SafeC로 빌드하는 문서 {#docs}

지금 읽고 있는 문서 사이트는 이제 VitePress 대신 SafeC 프로그램이 생성합니다. 이를 위해 정적 사이트 생성기에 필요한 std 기능이 추가되었습니다([#58](https://github.com/safec-org/SafeC/pull/58)): 파일 전체를 다루는 `File::read_to_string` / `write_bytes`와 함께 [`std/dir`와 `std/path`](/ko/stdlib/dir), 컨테이너·헤딩 id·렌더 훅을 지원하는 CommonMark + GFM [마크다운](/ko/stdlib/serial) 파서, YAML 플로 컬렉션과 블록 스칼라, `html_unescape` / `html_strip_tags`, `memory.grow` 위에서 크기 클래스를 재사용하는 할당기를 갖춘 wasm 런타임, [scx](/ko/advanced/scx) 템플릿 수정. 컴파일러는 이제 C의 `\a \b \f \v \e` 이스케이프를 인식합니다. [새 웹사이트](/ko/blog/new-website)는 이 모든 것 위에 만들어졌습니다.

## Windows 수정 {#windows}

[#64](https://github.com/safec-org/SafeC/pull/64)로 Windows CI가 다시 통과합니다: 컴파일러는 ABI 패스가 제네릭 함수를 다시 만들 때도 그 COMDAT을 유지하고, `std/sched`는 `WSAStartup`에 실제 408바이트 `WSADATA`를 넘기며, WSAPoll 리액터는 디스패치 후 감시 목록을 인덱싱하지 않습니다. [#63](https://github.com/safec-org/SafeC/pull/63)의 작은 컴파일러 수정도 있습니다: `f` 접미사가 붙은 부동소수점 리터럴은 `float`입니다.

## HTTPS {#https}

이 글을 쓰는 동안 마지막 조각이 병합되었습니다: [#66](https://github.com/safec-org/SafeC/pull/66)은 `std/tls`를 추가합니다. 운영체제의 TLS가 있으면 그것을 — OpenSSL(실행 시점에 로드), SChannel, SecureTransport — 쓰고, 없으면 SafeC로 작성한 TLS 1.3 엔진(클라이언트와 서버)을 쓰며, X.509 파싱, 인증서 경로 검증, 시스템 신뢰 루트를 갖췄습니다. `std/crypto`에는 이를 위한 X25519, P-256 / P-384 ECDH·ECDSA, RSA PKCS#1 v1.5·PSS, HKDF, ChaCha20이 추가되었습니다. 이로써 `std/http`는 `http_fetch` 계열과 SSE 클라이언트에서 `https://`를, `WsConn`은 `wss://`를 지원하고, `HttpServer`는 `TlsServerConfig`로 HTTPS를 서비스합니다.

## 써 보기 {#try}

위 내용은 모두 `main`에 있습니다. 다음 릴리스 전까지는 [소스에서 빌드](/ko/install#quick-install)하거나, [1.0.0-beta-6](/ko/blog/releases/v1.0.0-beta-6)을 설치하고 [변경 내역](/ko/changelog)을 따라가세요.
