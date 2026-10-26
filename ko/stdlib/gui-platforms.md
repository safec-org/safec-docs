# GUI 플랫폼과 GPU (`std/gui/`)

[`std/gui`](/ko/stdlib/gui)가 플랫폼마다 화면에 닿는 방법: Linux에서 X11 옆에 있는 Wayland 백엔드, 창을 웹 페이지의 `<canvas>` 요소로 만드는 WebAssembly 백엔드, 그리고 OpenGL ES(EGL), Metal, Direct3D 11, WebGL을 통한 GPU 프레젠테이션. 어느 것도 프로그램이 그리는 방식은 바꾸지 않습니다: 렌더링은 CPU에 남으므로 한 프레임은 어디서나 같은 바이트입니다 — `examples/gui_gallery`의 브라우저 프레임은 시계에 따라 움직이는 막대를 빼면 네이티브 헤드리스 스냅숏과 픽셀 단위로 같습니다.

| 플랫폼 | 백엔드 | 프레젠테이션 | GPU 프레젠터 |
|----------|---------|--------------|---------------|
| Linux, BSD (X11) | `gui_x11.sc` (Xlib) | `XPutImage` | `"gl"`: EGL을 통한 OpenGL ES 2 |
| Linux (Wayland) | `gui_wayland.sc` | `wl_shm`, 더블 버퍼, 프레임 콜백 | `"gl"`: EGL을 통한 OpenGL ES 2 (`wl_egl_window`) |
| Windows | `gui_win32.sc` | `StretchDIBits` | `"d3d11"`: Direct3D 11, 플립 모델 스왑 체인 |
| macOS | `gui_cocoa.sc` | `CALayer`의 contents | `"metal"`: Metal, `CAMetalLayer` |
| 웹 (wasm32) | `gui_web_wasm.sc` + `gui_web.js` | `putImageData` | `"webgl"`: WebGL2, 없으면 WebGL1 |
| 베어메탈 | `gui_fb.sc` | 프레임버퍼 복사 | — |
| 어디서나 (테스트) | `gui_headless.sc` | 횟수만 셈 | — |

## X11과 Wayland 중 고르기 {#choosing-x11-or-wayland}

Linux 빌드에는 두 백엔드가 모두 들어 있고, 첫 창이 결정합니다:

| `SAFEC_GUI_BACKEND` | 백엔드 |
|---------------------|---------|
| 설정 안 함(또는 그 밖의 값) | `$WAYLAND_DISPLAY`가 설정되어 있고 `libwayland-client.so.0`이 로드되고 컴포지터가 응답하면 Wayland, 아니면 X11 |
| `wayland` | Wayland (없으면 창 생성이 깔끔하게 실패) |
| `x11` | X11 (Wayland 데스크톱에서는 Xwayland를 통해) |
| `headless` | 메모리 백엔드 — Linux만이 아니라 모든 플랫폼 |

```c
#include <std/gui/gui_wayland.h>

if (!gui_use_wayland()) { /* no compositor: X11 it is */ }   // or force it in code, before the first window
```

`gui_wayland_available()`은 (한 번) 연결해 보고 Wayland를 쓸 수 있는지 알려 줍니다. `gui_wayland_backend()`는 백엔드 테이블입니다(Linux가 아닌 타깃에서는 null).

링크되는 것은 없습니다: `libwayland-client`, `libxkbcommon`, `libwayland-cursor`는 `dlopen()`되므로 하나의 바이너리가 X11만 있는 시스템에서도 실행됩니다. `safeguard bundle`은 이 패키지들을 `.deb`의 Depends가 아니라 **Recommends**에 적습니다.

## Wayland {#wayland}

| 기능 | 방법 |
|---------|-----|
| 창 | `xdg_toplevel`, 개수 제한 없음; 제목, 앱 ID(`$SAFEC_GUI_APP_ID`, 없으면 프로그램 이름), 최소/최대 크기, 닫기 요청. 컴포지터가 정하는 크기(타일링, 최대화)는 `GUI_EVENT_RESIZE`로 옵니다. |
| 프레젠테이션 | `wl_shm` 버퍼 두 개(컴포지터가 둘 다 잡고 있을 때만 세 번째나 네 번째), 손상 영역만 복사, 프레임 콜백으로 조절: 앞 프레임이 아직 가는 중일 때의 present는 다음 프레임에 합쳐집니다. |
| HiDPI | `wp_fractional_scale_v1` + `wp_viewporter`(1.25, 1.5 등; 버퍼는 창의 장치 픽셀 크기와 정확히 같음), 없으면 `wl_surface.preferred_buffer_scale`, 없으면 창이 있는 출력의 배율; 바뀌면 `GUI_EVENT_SCALE`. `SAFEC_GUI_SCALE`이 우선합니다. |
| 장식 | 컴포지터가 제공하면 `zxdg_decoration_manager_v1`을 통한 서버 측 장식(sway, KDE, wlroots 컴포지터). 그 밖에서는(GNOME의 Mutter, 예전 Weston) 창에 **장식이 없습니다**: std/gui는 클라이언트 측 제목 표시줄을 그리지 않으므로, 그런 창에는 직접 닫기/이동 컨트롤을 두십시오. `decorated = 0`은 장식 없음을 요청합니다. |
| 포인터 | 진입/이탈, 이동, 버튼, 휠: 고해상도 `value120`, 불연속 단계, 또는 부드러운(터치패드) 스크롤. |
| 키보드 | libxkbcommon을 통한 컴포지터의 xkb 키맵: 레이아웃과 무관한 `GUI_KEY_*`, Shift/Ctrl/Alt/Super, 데드 키와 조합 시퀀스, 컴포지터의 속도와 지연을 따르는 클라이언트 측 키 반복. libxkbcommon이 없으면 키는 US 레이아웃으로 매핑됩니다. 장치가 생기거나 사라지는 시트도 따라갑니다. |
| 커서 | `wp_cursor_shape_v1`, 없으면 libwayland-cursor를 통한 XCursor 테마(`$XCURSOR_THEME`, `$XCURSOR_SIZE`). |
| 클립보드 | `wl_data_device`, 양방향 UTF-8 텍스트(읽기는 최대 1초 기다림). 모든 Wayland 클라이언트처럼, 프로그램은 자기 입력 이벤트 직후에만 클립보드를 *가져올* 수 있습니다 — 보통은 Ctrl+C 그 자체. |

Wayland는 클라이언트에게 창 위치, 자기 창을 올리거나 포커스 주기, 항상 위 유지를 맡기지 않습니다: `set_position()`은 무시되고(`pos_x()`/`pos_y()`는 설정하지 않는 한 `GUI_POS_DEFAULT`), `focus()`는 아무것도 하지 않으며, `topmost`는 무시됩니다.

**테스트:** sway와 Weston 헤드리스에서(`tests/stdlib/wayland/run.sh`): `grim`으로 읽은 프레임(전체·부분 present, 동시에 여러 개), 테스트가 자기 연결로 만든 가상 포인터와 키보드의 입력(이동, 버튼, 휠, Shift와 Ctrl을 곁들인 키, 키 반복), `wl-copy`/`wl-paste`와의 클립보드, 컴포지터와 클라이언트의 크기 변경, 숨기기/보이기, 닫기, 하나의 `GuiApp` 아래 두 창, 분수 배율 1.5, EGL 프레젠터.

## 웹 {#the-web}

`wasm32`/`wasm64`에서 std/gui의 백엔드는 브라우저 페이지입니다. 호스트 쪽은 std와 함께 배포되는 ES 모듈 **`std/gui/gui_web.js`** 입니다:

```js
import { loadSafeCGui, fetchFiles } from "./gui_web.js";

const files = await fetchFiles({ "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf": "fonts/DejaVuSans.ttf" });
const app = await loadSafeCGui(fetch("app.wasm"), { files, entry: "start" });
```

브라우저는 블록할 수 없으므로 웹 프로그램은 `GuiApp::run()`을 호출하지 않습니다. 진입점 — 아무 `export_name` 함수이며 글루가 한 번 호출합니다 — 이 창을 열고 앱을 `gui_web_run()`에 넘기면 즉시 반환합니다. 그 뒤 페이지가 `requestAnimationFrame`마다 `app.pump(0)`을 한 번씩, 0을 반환할 때까지 실행합니다. 나머지 — 위젯, 캔버스, 타이머, 애니메이션 프레임 — 는 데스크톱 코드 그대로입니다:

```c
#include <std/gui/gui_app.h>
#include <std/gui/gui_web.h>

static struct GuiApp gApp;          // outlives start()

__attribute__((export_name("start")))
int start() {
    gApp = GuiApp::new();
    ?&GuiWindow w = gApp.open_window("Hello", 480, 320);
    match (w) {
        case some(win): { gApp.set_root(win, Widget::button("Hi")); }
        case null: { return 1; }
    }
    gui_web_run(&gApp);
    return 0;
}
```

| | |
|---|---|
| 창 | `windows: "canvas"`(기본값): 창마다 `<canvas>` 하나 — 페이지의 다음 빈 `canvas[data-safec-gui]`, 넘겨준 것(`canvas` 옵션), 또는 새로 만든 것. CSS 크기는 논리 크기이고 백킹 스토어는 그 × `devicePixelRatio`입니다. `fit`(또는 `data-safec-fit` 속성)이면 캔버스는 페이지의 CSS가 준 크기를 유지하고 `ResizeObserver`로 그 변화를 따릅니다(`GUI_EVENT_RESIZE`). `windows: "frames"`: 창마다 `container` 안에 배치된 `<div class="safec-gui-window">` 하나로, 제목 표시줄(끌면 `GUI_EVENT_MOVE`, `set_position()`이 동작; ×는 `GUI_EVENT_CLOSE`), 크기 제한을 지키는 크기 조절 손잡이가 있고, 클릭하면 위로 올라옵니다. |
| 키보드와 텍스트 | 키보드 포커스는 실제 DOM 포커스이며, 창마다 숨겨진 `<textarea>`가 받습니다(캔버스를 클릭하면 여기에 포커스). 키는 입력된 문자와 함께 `GUI_KEY_*`(`KeyboardEvent.code` 기준)가 되고, 키 없이 오는 텍스트 — **IME 조합**, 가상(모바일) 키보드, 문맥 메뉴 붙여넣기 — 는 `GUI_EVENT_CHAR`로 옵니다. |
| 포인터 | 포인터 이벤트(버튼을 누르고 있는 동안 캡처), 휠(픽셀·줄·페이지 단위를 줄로). |
| 클립보드 | `gui_clipboard_set_text()`는 `navigator.clipboard`와 Ctrl/Cmd+C가 일으키는 copy 이벤트에 씁니다. `gui_clipboard_get_text()`는 마지막 paste 이벤트의 텍스트(Ctrl/Cmd+V의 keydown은 그 paste 이벤트가 올 때까지 보류됩니다)나 마지막 복사를 돌려줍니다. |
| 배율 | `devicePixelRatio`, 바뀌면(확대, 다른 디스플레이) 따라감: `GUI_EVENT_SCALE`. |
| 커서 | CSS `cursor`. |
| 접근성 | 캔버스는 창 제목을 `aria-label`로 가진 `role="img"`이고, `gui_web_announce(text)`는 `aria-live` 영역으로 읽어 줍니다. |
| 파일 | `fopen`/`fread`는 `files` 옵션을 읽습니다(`fetchFiles()`가 URL로 채움): std/gui가 찾는 경로의 폰트, 데이터 파일. 폰트가 없으면 TrueType 스택의 내장 대체 서체가 쓰입니다. |
| DOM | 모듈을 [`std/wasm`](/ko/stdlib/wasm)의 `loadSafeC`로 로드하면(`loadSafeC` 옵션으로 넘김) `gui_web_window_element(win)`이 창의 요소를 `dom.h` 핸들로 돌려주고 `gui_web_mount(win, parent)`가 그것을 DOM 요소 안으로 옮깁니다 — 한 페이지에 일반 DOM UI와 std/gui 창을 섞을 수 있습니다. |

`gui_web.js` 옵션: `canvas`, `fit`, `title`(첫 창의 제목이 `document.title`이 됨), `windows`, `container`, `present`(`"webgl"` / `"2d"`), `preserveDrawingBuffer`, `files`, `entry`, `loadSafeC`, `domGlue`, `imports`, `globals`. 반환되는 `gui` 객체에는 `start()`, `stop()`, `frame()`, `canvas(id)`, `element(id)`, `input(id)`, `presenter(id)`가 있습니다. 글루는 독립 실행 모듈이 여전히 임포트할 수 있는 libc도 제공합니다 — 작은 `printf`/`snprintf`, `getenv`(아무것도 설정되지 않음), `files` 읽기, `Math` 기반 libm.

### safeguard가 만드는 페이지 {#safeguard-bundle-wasm32}

`safeguard bundle --target wasm32`는 프로그램을 빌드하고 **`build/<name>.web/`** 을 씁니다: 모듈, 사용하는 모든 std 모듈의 글루(`gui_web.js`, `safec_dom.js`, `audio_web.js`), `[bundle]` 리소스, 그리고 이것들을 로드하는 `index.html`. 폴더를 HTTP로 제공하십시오(`python3 -m http.server -d build/<name>.web`): 브라우저는 `file://`에서 WebAssembly를 인스턴스화하지 않습니다. `[bundle]` 테이블은 없어도 되며, 있으면 다음 키가 적용됩니다:

```toml
[bundle]
name        = "GuiWeb"
identifier  = "org.safec.gui-web"
web_entry   = "start"                 # the export the page calls (default)
web_present = "webgl"                 # or "2d"
web_windows = "frames"                # or "canvas" (default)
web_page    = "web/index.html"        # your page instead of the generated one
web_files   = ["fonts/Inter.ttf=/usr/share/fonts/truetype/inter/Inter.ttf"]
                                      # files the module can fopen() ("src" or "src=path")
```

`examples/gui_web`은 완전한 웹 프로그램입니다(그 안에서 `safeguard bundle --target wasm32`). `examples/gui_gallery`도 페이지에서 실행됩니다(`start` 익스포트).

**테스트:** 가짜 DOM의 Node에서(`tests/stdlib/wasm_gui/run.sh`: 세 창, 클릭하고 입력한 위젯, IME와 가상 키보드 텍스트, 클립보드, 크기 변경, 배율 변경, 끌고 크기를 바꾼 프레임, 부분 업로드와 컨텍스트 손실이 있는 WebGL2/WebGL1, DOM 연동)와 Playwright를 통한 헤드리스 Chromium에서(`browser.sh`: 네이티브 빌드의 폰트로 wasm32용으로 빌드한 갤러리가 putImageData와 WebGL(SwiftShader) 모두에서 네이티브 스냅숏과 일치; 실제 마우스·키보드·`insertText` 입력; 끈 프레임; `safeguard bundle`이 생성한 페이지).

## GPU 프레젠테이션 {#gpu-presentation}

```c
#include <std/gui/gui_gpu.h>

struct GuiWindowOptions o = GuiWindowOptions::new("Video", 1280, 720);
o.present = GUI_PRESENT_GPU;          // GUI_PRESENT_DEFAULT / GPU / CPU
struct GuiWindow win = GuiWindow::create_with(&o);
const char* how = gui_window_presenter_name(&win);   // "gl", "metal", "d3d11", "webgl" or "cpu"
```

| 호출 | 하는 일 |
|------|------|
| `GuiWindowOptions.present` | `GUI_PRESENT_DEFAULT`: `SAFEC_GUI_PRESENT=gpu`가 아니면(웹에서는 글루의 `present: "webgl"`) 소프트웨어 경로; `GUI_PRESENT_GPU`: GPU, 실패하면 소프트웨어; `GUI_PRESENT_CPU`: 환경과 상관없이 소프트웨어. |
| `gui_window_use_gpu(&win)` / `gui_window_use_cpu(&win)` | 살아 있는 창을 전환합니다. `use_gpu`는 전환하지 못하면 0을 반환합니다(창은 소프트웨어 경로를 유지). |
| `gui_window_uses_gpu(&win)`, `gui_window_presenter_name(&win)` | 창이 어느 경로에 있는지. |
| `gui_gpu_presenter()` | 플랫폼의 `GuiPresenter`, 또는 null(베어메탈, 헤드리스). |

프레젠터는 프레임을 텍스처에 업로드하고 — 손상된 사각형만(행 길이 설정이 없으면 손상된 행만) — 사각형 하나로 그리거나 스왑 체인으로 블릿합니다:

| 프레젠터 | 세부 |
|-----------|---------|
| `"gl"` (X11, Wayland) | `libEGL`, `libGLESv2`, `libwayland-egl`을 `dlopen()`; 창마다 OpenGL ES 2 컨텍스트, X11 비주얼에 맞는 EGL 설정; X11에서 vsync(스왑 간격 1; Wayland에서는 컴포지터가 프레임을 조절하고 티어링이 없음); 손상된 사각형은 `EGL_KHR/EXT_swap_buffers_with_damage`로 전달. |
| `"metal"` (macOS) | 실행 시점에 로드한 `Metal.framework`; 콘텐츠 뷰의 `CAMetalLayer`, 프레임의 손상된 행을 공유 `MTLBuffer`에 복사하고 거기서 픽셀을 읽는 전면 삼각형으로 그린 뒤 레이어의 vsync로 표시. |
| `"d3d11"` (Windows) | 실행 시점에 로드한 `d3d11.dll`(하드웨어 장치, 없으면 WARP); 플립 모델 스왑 체인(`FLIP_DISCARD`, 없으면 `FLIP_SEQUENTIAL`; `R8G8B8A8`, `SCALING_NONE`), 손상된 박스의 `UpdateSubresource`, `CopyResource`, 더티 사각형과 vsync를 곁들인 `Present1`. |
| `"webgl"` (웹) | WebGL2, 없으면 WebGL1; 프리멀티플라이된 프레임을 선형 메모리에서 바로 업로드(WebGL2에서는 손상된 사각형에 `UNPACK_ROW_LENGTH`) — `putImageData`와 달리 직선 알파 변환이 필요 없음. |

어떤 실패든 — 드라이버나 라이브러리가 없음, 컨텍스트나 스왑 체인을 만들 수 없음, 나중에 잃은 장치나 WebGL 컨텍스트 — 그 창을 영구히 소프트웨어 경로로 돌려보내며, 프로그램이 알아챌 것은 `gui_window_uses_gpu()`가 0이 되는 것뿐입니다. 자주 다시 그리는 큰 창(애니메이션, 동영상, 게임)에 쓸 만하고, 대부분 정적인 UI에는 소프트웨어 경로로도 충분합니다.

**테스트:** `"gl"`은 Mesa llvmpipe의 Xvfb에서 실제로(`tests/stdlib/x11/run.sh`: 읽은 프레임, 부분 업데이트, 크기 변경, GL 창 두 개, 소프트웨어로 전환했다가 되돌리기)와 sway에서(`wayland/run.sh`, 배율 1과 1.5); `"d3d11"`은 wined3d가 OpenGL로 변환하는 Wine에서(`tests/stdlib/win32/run.sh`: 프레임, 부분 업데이트, 크기 변경, GDI로 되돌아가기); `"webgl"`은 SwiftShader를 쓰는 헤드리스 Chromium과 Node에서; `"metal"`은 arm64와 x86_64 macOS용 컴파일만.

### 프레젠터 작성하기 {#writing-a-presenter}

`GuiPresenter`(`gui_backend.h`)는 함수 세 개입니다: `attach(st, handles)`는 창의 네이티브 핸들(`GuiNativeHandle`: display, window, view, visual)을 받아 상태를 `st.presenterData`에 두고; `present(st, pixels, bufW, bufH, x, y, w, h)`는 백엔드의 `window_present`가 받을 것과 똑같은 것 — 프리멀티플라이된 RGBA, 손상된 사각형 — 을 받으며 프레임 사이에 크기가 바뀌는 것을 처리해야 하고; `detach(st)`는 정리합니다. present 중에 영구히 실패한 프레젠터는 자기 데이터를 해제하고 `st.presenter = null`로 둔 뒤 그 프레임을 백엔드의 `window_present`에 넘깁니다. `gui_window_set_presenter()`로 붙입니다.
