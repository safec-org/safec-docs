# GUI (`std/gui/`)

SafeC로 처음부터 작성한 GUI 툴킷입니다: X11, Wayland, Win32, Cocoa, 웹 페이지(WebAssembly `<canvas>`), 베어메탈 프레임버퍼의 창과 입력, TrueType 텍스트를 갖춘 안티에일리어싱 벡터 캔버스, 그리고 레이아웃·테마·30개 이상의 위젯을 갖춘 리테인드 위젯 툴킷으로 구성됩니다. 모든 것은 CPU에서 창마다 RGBA 버퍼 하나에 렌더링되므로, UI는 어느 플랫폼에서나 바이트 단위까지 똑같이 보입니다. 그 버퍼를 화면에 올리는 일은 GPU를 거칠 수 있습니다([플랫폼과 GPU](/ko/stdlib/gui-platforms)).

이 페이지는 창, 이벤트, 애플리케이션 루프를 다룹니다. 나머지 두 부분은 각각의 페이지가 있습니다:

- [GUI 그래픽](/ko/stdlib/gui-graphics) — `GuiCanvas`(경로, 그라디언트, 스트로크, 클리핑, 블렌드 모드, 이미지)와 TrueType 텍스트(`GuiTypeface`, `GuiTextLayout`).
- [GUI 위젯](/ko/stdlib/gui-widgets) — 위젯 트리, 레이아웃, 테마, 포커스, 메뉴, 대화상자, 그리고 모든 위젯.
- [GUI 플랫폼과 GPU](/ko/stdlib/gui-platforms) — Wayland, 웹(`<canvas>`, WebGL, DOM 연동, `safeguard bundle --target wasm32`), GPU 프레젠테이션(OpenGL ES/EGL, Metal, Direct3D 11).

```c
#include <std/gui/gui.h>          // windows, events, colors, clipboard, clock
#include <std/gui/gui_app.h>      // GuiApp: several windows, timers, animation
#include <std/gui/gui_canvas.h>   // the vector canvas (and gui_path.h, gui_text.h)
#include <std/gui/gui_widget.h>   // widgets
#include <std/gui/gui_png.h>      // GuiImage: PNG load/save, scaling
#include <std/gui/gui_headless.h> // the in-memory backend, for tests
```

`std/prelude.h`는 GUI 헤더를 포함하지 않습니다 — 직접 include합니다.

### 첫 프로그램 {#a-first-program}

```c
#include <std/gui/gui_app.h>

static void on_click(&Widget w, ?&Widget label) {
    match (label) { case some(l): { l.set_text("Clicked!"); } case null: { } }
}

int main() {
    struct GuiApp app = GuiApp::new();
    ?&GuiWindow ow = app.open_window("Hello", 360, 200);
    match (ow) {
        case null: { return 1; }                 // no display
        case some(win): {
            &Widget root = Widget::vstack().set_padding(16);
            &Widget label = Widget::label("Hello from SafeC");
            root.add_child(label);
            root.add_child(Widget::button("Click me").on_click(on_click, label));
            app.set_root(win, root);             // the window owns the tree now
            app.run();                           // until the window closes
        }
    }
    app.free();
    return 0;
}
```

`examples/gui_hello`는 작은 완성 프로그램이고, `examples/gui_gallery`는 모든 위젯과 캔버스 기능 대부분을 두 개의 창에 보여 줍니다(`safeguard run -- --snapshot DIR`은 디스플레이 없이 두 창을 PNG 파일로 렌더링합니다).

---

## 창 — `gui.h` {#windows-gui-h}

```c
struct GuiWindowOptions {
    const &char title;
    int x, y;                 // GUI_POS_DEFAULT: let the system place it
    int width, height;        // client area, logical units
    int minWidth, minHeight;  // 0 = no limit
    int maxWidth, maxHeight;
    int resizable;            // default 1
    int decorated;            // title bar and border; default 1
    int visible;              // default 1
    int hiDpi;                // a buffer at the display's scale; default 1
    int topmost;              // default 0

    static struct GuiWindowOptions new(const &char title, int width, int height);
};

struct GuiWindow {
    ?&struct GuiWindowState platform; // backend state (gui_backend.h); null if creation failed
    &heap unsigned char pixels; // width*height*4 bytes: premultiplied RGBA8888, R first
    int width, height;        // the buffer, in pixels
    int shouldClose;          // set by GUI_EVENT_CLOSE
    double scale;             // pixels per logical unit
    unsigned int id;          // unique, never 0

    static struct GuiWindow create(const &char title, int width, int height);
    static struct GuiWindow create_with(const &GuiWindowOptions opts);
    static struct GuiWindow offscreen(int width, int height);  // a buffer, no window

    int  poll_event(&GuiEvent out);       // 1 = got one; never blocks
    int  wait_event(&GuiEvent out, int timeoutMs);
    void present();                       // show the buffer
    void present_rect(int x, int y, int w, int h);
    void destroy();

    void set_title(const &char title);    // UTF-8
    void set_size(int width, int height); // logical
    void set_position(int x, int y);
    int  pos_x() const; int pos_y() const;
    void set_min_size(int w, int h); void set_max_size(int w, int h);
    void show(); void hide(); int is_visible() const;
    void focus(); int is_focused() const;
    void set_cursor(int cursor);          // GUI_CURSOR_*
    int  logical_width() const; int logical_height() const;
    void request_close();                 // posts a GUI_EVENT_CLOSE
    unsigned long long native_handle() const;  // X11 Window / HWND / NSWindow*

    void set_pixel(int x, int y, unsigned int rgba);   // raw store, 0xRRGGBBAA
    unsigned int get_pixel(int x, int y) const;
    void clear(struct GuiColor c);
    // and the integer drawing helpers below (fill_rect, draw_text, draw_svg, ...)
};
```

창은 항상 `width × height` 픽셀 크기의 버퍼를 가집니다. `hiDpi`(기본값)이고 고밀도 디스플레이라면, 버퍼는 창의 논리 크기보다 `scale`배 큽니다(Retina 디스플레이에서는 2.0, Windows와 X11에서는 시스템 설정에 따라 1.25–2.0; `SAFEC_GUI_SCALE`로 덮어쓸 수 있습니다). 마우스 좌표는 버퍼 픽셀 단위로 들어오므로 히트 테스트에 변환이 필요 없고, 올바른 크기로 그리는 일은 위젯 툴킷과 `GuiCanvas::scale()`이 맡습니다.

`create`는 디스플레이가 없으면(X가 없는 CI 러너 등) 깔끔하게 실패합니다 — `platform`은 null이고 크기는 0입니다. 사용하기 전에 확인하거나, `null`을 반환하는 `GuiApp::open_window`를 사용하십시오.

창 크기가 바뀌면 버퍼가 다시 할당됩니다: `poll_event`는 `GUI_EVENT_RESIZE`를 반환하기 전에 `pixels`, `width`, `height`를 갱신합니다.

### 이벤트 {#events}

```c
struct GuiEvent {
    int kind;                 // GUI_EVENT_*
    double x, y;              // pointer position, buffer pixels
    int button;               // GUI_BUTTON_LEFT / RIGHT / MIDDLE
    int clicks;               // 1, 2 (double click), 3 ... on MOUSE_DOWN
    int keycode;              // GUI_KEY_*: named keys, 'A'..'Z', '0'..'9'
    int modifiers;            // GUI_MOD_SHIFT | CTRL | ALT | META
    unsigned int codepoint;   // the typed character (KEY_DOWN, CHAR)
    double scrollDx, scrollDy;// in lines; +y = away from the user
    int width, height;        // RESIZE / SCALE: the new buffer size; MOVE: x, y
    unsigned int windowId;
    double timeMs;            // gui_time_ms() when it happened
    int defaultPrevented;

    void prevent_default();   // see the widget toolkit's default actions
    int  has_mod(int mod) const;
    int  primary_mod() const; // Cmd on macOS, Ctrl elsewhere
};
```

| 이벤트 | 발생 시점 |
|-------|------|
| `GUI_EVENT_CLOSE` | 닫기 버튼(또는 시스템의 Alt+F4, Cmd+W). 창을 파괴할 때까지 창은 열려 있습니다. |
| `GUI_EVENT_RESIZE` | 새 크기; 버퍼는 이미 다시 할당되어 있습니다. |
| `GUI_EVENT_MOVE` | 창이 이동함(`width`/`height`에 새 x/y). |
| `GUI_EVENT_FOCUS_IN` / `_OUT` | 창이 키보드 포커스를 얻음 / 잃음. |
| `GUI_EVENT_MOUSE_DOWN` / `_UP` / `_MOVE` | 버튼과 이동; 버튼을 누르고 있는 동안 창이 포인터를 캡처합니다. |
| `GUI_EVENT_MOUSE_ENTER` / `_LEAVE` | 포인터가 창에 들어옴 / 나감. |
| `GUI_EVENT_SCROLL` | 휠 또는 터치패드. |
| `GUI_EVENT_KEY_DOWN` / `_UP` | 수정자 키와 함께 오는 키; `KEY_DOWN`은 입력된 문자를 담습니다. 반복 입력은 추가 `KEY_DOWN`으로 옵니다. |
| `GUI_EVENT_CHAR` | 키 이벤트 없이 들어온 텍스트(입력기, 조합 입력). |
| `GUI_EVENT_SCALE` | 배율이 다른 디스플레이로 이동함; 버퍼는 다시 할당됩니다. |
| `GUI_EVENT_EXPOSE` | 시스템이 창 내용을 잃음: 다시 present하십시오. |

각 백엔드가 네이티브 키 코드를 변환하므로 프로그램은 `GUI_KEY_*`만 봅니다: 화살표, Enter, Escape, Tab, Backspace, Space, Delete, Home/End, PageUp/PageDown, Insert, F1–F12, Shift/Control/Alt/Meta/Menu, 그리고 글자와 숫자는 키보드 배열이 어떤 텍스트를 만들든 대문자 ASCII 코드(`'A'`, `'7'`)로 옵니다. 텍스트 입력은 플랫폼에 입력기가 있으면 그것을 거치므로(X11의 XIM, Windows의 서로게이트 쌍을 포함한 `WM_CHAR`, macOS의 `NSEvent.characters`) `codepoint`는 완전한 유니코드 스칼라 값입니다. 한글처럼 조합되는 입력도 완성된 문자로 들어옵니다.

### 클립보드, 시간, 커서 {#clipboard-time-cursors}

```c
int    gui_clipboard_set_text(const &char utf8);    // 1 = ok
int    gui_clipboard_get_text(&String out);         // 1 = got text
double gui_time_ms();                               // monotonic milliseconds
int    gui_primary_mod();                           // GUI_MOD_META on macOS, else GUI_MOD_CTRL
double gui_display_scale();                         // the scale a hiDpi window would get
```

클립보드는 시스템의 것입니다: X11에서는 `CLIPBOARD`(프로그램이 실행되는 동안 다른 클라이언트에 제공되며, 큰 `INCR` 전송은 지원하지 않습니다), Windows에서는 `CF_UNICODETEXT`, macOS에서는 `NSPasteboard`.

커서: `GUI_CURSOR_ARROW`, `_IBEAM`, `_HAND`, `_CROSSHAIR`, `_RESIZE_EW`, `_RESIZE_NS`, `_RESIZE_NWSE`, `_RESIZE_NESW`, `_MOVE`, `_WAIT`, `_NOT_ALLOWED`, `_HIDDEN`.

### 색 {#colors}

```c
struct GuiColor {
    unsigned char r, g, b, a;           // straight (not premultiplied) alpha

    static struct GuiColor rgba(r, g, b, a);
    static struct GuiColor rgb(r, g, b);
    static struct GuiColor hex(unsigned int rrggbb);
    static struct GuiColor hexa(unsigned int rrggbbaa);
    static struct GuiColor rgbaf(float r, float g, float b, float a);   // 0..1
    static struct GuiColor hsv(float h, float s, float v, float a);     // h in degrees
    struct GuiColor with_alpha(unsigned char a) const;
    struct GuiColor mix(struct GuiColor other, float t) const;
    struct GuiColor shade(float amount) const;   // > 0 lighter, < 0 darker
    unsigned int packed() const;                 // 0xRRGGBBAA
    void to_hsv(&float h, &float s, &float v) const;
};
```

---

## 애플리케이션 — `gui_app.h` {#the-application-gui-app-h}

`GuiApp`은 하나의 이벤트 루프로 여러 개의 창을 실행합니다: 플랫폼 이벤트를 펌프하고, 각 이벤트를 해당 창(그 창의 위젯 트리 및/또는 콜백)으로 보내고, 타이머와 프레임별 애니메이션 콜백을 실행하고, 바뀐 창을 다시 그려 present하며, 닫기 버튼이 눌리면 창을 닫습니다.

```c
struct GuiApp {
    static struct GuiApp new();

    ?&GuiWindow open_window(const &char title, int width, int height);
    ?&GuiWindow open(const &GuiWindowOptions opts);
    void close_window(&GuiWindow win);          // destroys it (and its widget tree) now
    unsigned long window_count() const;
    ?&GuiWindow window_at(unsigned long i) const;
    ?&GuiWindow find_window(unsigned int id) const;

    &GuiUi set_root(&GuiWindow win, &Widget root);   // show a widget tree in the window
    ?&GuiUi ui_of(const &GuiWindow win) const;
    void redraw(&GuiWindow win);                     // repaint on the next iteration

    generic<T> void on_event(&GuiWindow win, fn int(&GuiApp, &GuiWindow, &GuiEvent, ?&T) cb, ?&T data);
    generic<T> void on_draw(&GuiWindow win, fn void(&GuiApp, &GuiWindow, &GuiCanvas, ?&T) cb, ?&T data);
    generic<T> void on_frame(&GuiWindow win, fn int(&GuiApp, &GuiWindow, double, ?&T) cb, ?&T data);

    generic<T> unsigned int set_timeout(double ms, fn void(&GuiApp, unsigned int, ?&T) cb, ?&T data);
    generic<T> unsigned int set_interval(double ms, fn void(&GuiApp, unsigned int, ?&T) cb, ?&T data);
    void cancel_timer(unsigned int id);

    int  pump(int timeoutMs);   // one iteration; 0 once the app should stop
    int  run();                 // pump() until then; returns quit()'s code
    void quit(int exitCode);
    double now_ms() const;
    void set_frame_interval(double ms);   // default 1000/60
    void free();                // closes every window
};
```

- **렌더링.** 위젯 루트가 있는 창은 그 `GuiUi`가 레이아웃하고 그립니다. `on_draw` 콜백이 있는 창은 버퍼 위의 [`GuiCanvas`](/ko/stdlib/gui-graphics)를 받습니다 — 둘 다 있으면 위젯 다음에. 둘 다 없는 창은 `win.pixels`에 직접 쓴 내용을 보여 줍니다. 창은 무언가 바뀌었을 때만 다시 그려집니다: 위젯이나 `on_event`가 처리한(1을 반환한) 입력 이벤트, 크기 변경, 타이머, 애니메이션 프레임, 또는 `app.redraw(win)`.
- **닫기.** `GUI_EVENT_CLOSE`는 `on_event` 콜백이 본 다음 창을 닫습니다 — 콜백이 `ev.prevent_default()`를 호출하지 않았다면(먼저 "변경 사항을 저장할까요?"를 묻는 경우 등). `run()`은 마지막 창이 사라지거나 `quit()`이 호출되면 반환합니다.
- **애니메이션.** `on_frame` 콜백은 1을 반환하는 동안 프레임마다(`set_frame_interval`, 기본 60 Hz) 밀리초 단위 시간과 함께 호출되고, 매번 창이 다시 그려집니다.
- **타이머**는 루프 스레드에서 실행되며 이벤트 처리와 동시에 실행되지 않고, 루프가 허락하는 한 예정 시각에 가깝게 실행됩니다. id는 0이 아닙니다.
- **대기.** `run()`은 다음 이벤트, 타이머, 프레임이 올 때까지 플랫폼의 이벤트 대기(X 연결의 `poll`, `MsgWaitForMultipleObjects`, `nextEventMatchingMask`)에서 잠들므로, 유휴 상태의 앱은 CPU를 쓰지 않습니다.

### 루프를 직접 돌리기 {#driving-the-loop-yourself}

자신의 루프(게임, 임베딩 호스트, `requestAnimationFrame`으로 구동되는 WebAssembly 페이지)의 매 반복마다 `app.pump(0)`을 한 번 호출하십시오: 기다리지 않고 한 번 반복하며, 앱이 멈춰야 하면 0을 반환합니다. `app.pump(ms)`는 다음 이벤트, 타이머, 프레임을 최대 `ms` 밀리초 기다립니다.

### 예제 — 두 개의 창과 타이머 {#example-two-windows-and-a-timer}

```c
#include <std/gui/gui_app.h>

struct State { int seconds; &Widget label; };

static void tick(&GuiApp app, unsigned int id, ?&State os) {
    match (os) {
        case some(s): {
            s.seconds = s.seconds + 1;
            char buf[32];
            unsafe { snprintf(&buf[0], 32UL, "%d s", s.seconds); }
            s.label.set_text(&buf[0]);
        }
        case null: { }
    }
}

static void draw(&GuiApp app, &GuiWindow win, &GuiCanvas c, ?&State os) {
    c.clear(GuiColor::hex(0x202830U));
    c.set_fill_color(GuiColor::hex(0x4DABF7U));
    c.fill_circle((float)win.width / 2.0f, (float)win.height / 2.0f, 40.0f * (float)win.scale);
}

int main() {
    struct GuiApp app = GuiApp::new();
    ?&GuiWindow oa = app.open_window("Widgets", 300, 120);
    ?&GuiWindow ob = app.open_window("Canvas", 300, 200);
    if (oa.is_null() || ob.is_null()) { return 1; }
    &GuiWindow a; &GuiWindow b;
    unsafe { a = (struct GuiWindow*)oa; b = (struct GuiWindow*)ob; }

    struct State s;
    s.seconds = 0;
    s.label = Widget::label("0 s");
    &Widget root = Widget::vstack().set_padding(16);
    root.add_child(s.label);
    app.set_root(a, root);
    app.on_draw(b, draw, &s);
    app.set_interval(1000.0, tick, &s);
    int code = app.run();
    app.free();
    return code;
}
```

---

## 백엔드 {#backends}

플랫폼에 따라 달라지는 모든 것은 함수 포인터 테이블 하나, `struct GuiBackend`(`gui_backend.h`) 뒤에 있습니다. 포터블 계층 — 창, 클립보드, 시계, `GuiApp` — 은 이 테이블만 통해 호출하고, 캔버스·텍스트·위젯 코드는 창의 픽셀 버퍼만 봅니다.

| 백엔드 | 대상 | 테스트 |
|---------|--------|--------|
| `gui_x11.sc` | Linux, BSD — Xlib, `XPutImage`, XIM 텍스트 입력, `CLIPBOARD`, EWMH | Xvfb에서(`tests/stdlib/x11/run.sh`): `XGetImage`로 읽은 픽셀; 키·텍스트·마우스·스크롤·크기 변경·닫기; 하나의 `GuiApp` 아래 두 개의 창; UTF-8 제목; 두 번째 X 클라이언트와 양방향 클립보드 |
| `gui_wayland.sc` | Linux — Wayland: xdg-shell, `wl_shm`, xkbcommon, 분수 배율(모두 `dlopen()`); 컴포지터가 있으면 실행 시점에 선택됨 | sway와 Weston 헤드리스에서(`tests/stdlib/wayland/run.sh`): grim으로 읽은 프레임, 가상 포인터·키보드 입력, wl-copy/wl-paste와의 클립보드, 배율 1과 1.5 |
| `gui_web_wasm.sc` | WebAssembly — 페이지의 `<canvas>` 요소, `std/gui/gui_web.js`와 함께 | 가짜 DOM의 Node에서(`tests/stdlib/wasm_gui/run.sh`)와 헤드리스 Chromium에서(`browser.sh`): 갤러리의 프레임이 네이티브 프레임과 일치 |
| `gui_win32.sc` | Windows — 와이드 문자 Win32, `StretchDIBits`, 모니터별 DPI | Wine에서(`tests/stdlib/win32/run.sh`): `GetPixel`로 읽은 픽셀; 게시된 입력; 두 개의 창; 클립보드 |
| `gui_cocoa.sc` | macOS — Objective-C 런타임을 통한 AppKit, 레이어 기반 `NSView` | arm64와 x86_64 macOS용으로 컴파일됨 |
| `gui_fb.sc` | 베어메탈 — 선형 프레임버퍼; 입력은 직접 작성한 드라이버가 주입 | 모든 베어메탈 타깃용으로 컴파일됨 |
| `gui_headless.sc` | 모든 타깃 — 메모리 안에서, 가상 시계와 함께 | `tests/stdlib/tests/test_gui_widgets.sc`, `test_gui_app.sc` |

`safeguard`는 파일 이름 접미사로 타깃용 백엔드를 링크하고(Linux/BSD는 `_x11`, Windows는 `_win32`, Apple은 `_cocoa`, WebAssembly는 `_wasm`, 베어메탈은 `_fb`) 그 시스템 라이브러리를 링크에 추가합니다 — `Package.toml`에 지정할 것은 없습니다. `SAFEC_GUI_BACKEND=headless`는 어떤 GUI 프로그램이든 [헤드리스 백엔드](#the-headless-backend)에서 실행하고, Linux에서 `SAFEC_GUI_BACKEND=x11|wayland`는 둘 중 하나를 고릅니다([자세히](/ko/stdlib/gui-platforms#choosing-x11-or-wayland)).

| 백엔드 | 라이브러리 |
|---------|-----------|
| Cocoa | `-framework Cocoa -lobjc` |
| Win32 | `-luser32 -lgdi32` |
| X11 | `-lX11`(`.deb`의 Depends에는 `libx11-6`) |
| Wayland, EGL | 없음: `dlopen()`(`-ldl`); `.deb`의 Recommends에 `libwayland-client0`, `libxkbcommon0`, `libwayland-cursor0`, `libegl1`, `libgles2` |
| Metal, Direct3D 11 | 없음: 실행 시점에 로드 |
| WebAssembly | 없음: `std/gui/gui_web.js`가 제공하는 임포트 |

`safeguard` 없이 빌드할 때는 링크에 직접 전달하십시오.

### 헤드리스 백엔드 {#the-headless-backend}

`gui_use_headless()`(`gui_headless.h`, 첫 창을 만들기 전에 호출)는 플랫폼 백엔드를 창을 메모리에 두는 백엔드로 바꿉니다. 이 백엔드의 가상 시계는 루프가 기다리거나 직접 진행시킬 때만 흐릅니다. 테스트에 사용합니다: 입력을 주입하고 픽셀을 읽어 봅니다.

```c
gui_use_headless();
struct GuiWindow win = GuiWindow::create("test", 200, 100);
gui_headless_click(&win, 50.0, 20.0);          // MOUSE_DOWN + MOUSE_UP
gui_headless_key(&win, GUI_KEY_TAB, GUI_MOD_SHIFT, 0U);
gui_headless_type(&win, "héllo");             // one KEY_DOWN per character
gui_headless_scroll(&win, 10.0, 10.0, 0.0, -3.0);
gui_headless_resize(&win, 300, 120);
gui_headless_close(&win);
gui_headless_advance(600.0);                   // the clock, in ms
gui_headless_set_scale(2.0);                   // windows made from now on are 2x
int cur = gui_headless_cursor(&win);           // GUI_CURSOR_* last asked for
unsigned long n = gui_headless_present_count(&win);
```

### 백엔드 작성하기 {#writing-a-backend}

`gui_backend.h`에 계약 전체가 문서화되어 있습니다. 요약하면, 백엔드는 `GuiBackend`를 채우는 `.sc` 파일 하나입니다:

| 항목 | 하는 일 |
|-------|------|
| `init()` | 디스플레이 서버에 연결; 없으면 0. |
| `window_create(st, opts)` | 네이티브 창 생성; `st.key`(네이티브 id), `st.scale`, `st.pixelW/H`, `st.x/y` 설정. |
| `window_destroy`, `window_set_title/size/position/visible`, `window_focus`, `window_set_cursor`, `window_update_limits` | 변경 적용. |
| `window_present(st, px, bufW, bufH, x, y, w, h)` | 프리멀티플라이드 RGBA 버퍼의 사각형을 네이티브 픽셀 순서로 변환해 표시. |
| `pump(timeoutMs)` | 네이티브 이벤트를 읽어 변환하고 `gui_backend_post()`로 게시; 대기 중인 이벤트가 없었으면 최대 `timeoutMs` 대기. |
| `wake()` | 블록된 `pump()`가 반환하게 함(어느 스레드에서나). |
| `now_ns`, `clipboard_set/get`, `display_scale`, `native_handle` | 시계, 클립보드, 배율, GPU 프레젠터용 핸들. |

새 **플랫폼 기본값**은 safeguard의 `stdVariantMatches`에 접미사 규칙을 추가하고 `gui_platform_backend()`를 정의하는 `gui_<...>_<suffix>.sc`를 두는 것입니다(`_wasm`의 `gui_web_wasm.sc`처럼). 기존 기본값 옆의 **대안**은 X11 옆의 `gui_wayland.sc`처럼 자신의 테이블과 설정 함수를 정의합니다. 필요한 라이브러리는 `dlopen()`하고 `// safeguard: deb-recommends`로 이름을 적는 것이 좋습니다.

**GPU 프레젠테이션**은 창마다 붙이는 `GuiPresenter`입니다 — 포함된 프레젠터(OpenGL ES/EGL, Metal, Direct3D 11, WebGL)와 직접 작성하는 법은 [플랫폼과 GPU](/ko/stdlib/gui-platforms#gpu-presentation)를 보십시오.

---

## 베어메탈 — `gui_fb.h` {#bare-metal-gui-fb-h}

베어메탈에는 윈도 시스템도 입력 스택도 없습니다. 창을 만들기 전에 프레임버퍼가 어디 있는지 백엔드에 알려 주고, 입력은 직접 작성한 드라이버에서 넣어 줍니다.

```c
#include <std/gui/gui_fb.h>

struct GuiFbConfig {
    &volatile unsigned char base;  // linear framebuffer memory
    unsigned long pitch;           // bytes per scanline (may include padding)
    int width;
    int height;
    int bpp;                       // 32 (xRGB/xBGR) or 16 (RGB565)
    int redShift; int greenShift; int blueShift; // channel bit positions, 32 bpp only
};

void gui_fb_configure(struct GuiFbConfig cfg);                 // once, before GuiWindow::create()
void gui_fb_inject_event(&GuiWindow win, struct GuiEvent ev);  // from your keyboard/mouse/touch ISR
void gui_fb_set_clock(fn long long() nowNs);                   // a monotonic clock, for timers and animation
```

값은 보통 부트로더에서 옵니다. 예를 들어 Multiboot2 프레임버퍼 태그나 VESA VBE 모드입니다. `gui_fb_configure` 없이는 창 생성이 실패합니다. 주입된 이벤트는 `poll_event`가 비우는 32개짜리 링(`GUI_FB_QUEUE_CAP`)에 들어가며, 가득 차면 버려집니다. `present()`는 복사하면서 RGBA 버퍼를 프레임버퍼의 픽셀 형식으로 변환합니다. 캔버스, 텍스트(내장 폴백 폰트 사용), 위젯은 모두 그대로 동작합니다.

타깃과 링크는 [베어메탈](/ko/reference/baremetal)을 참고하십시오.

---

## 간단한 그리기, 비트맵 폰트, PNG, SVG {#simple-drawing}

빠른 픽셀 작업을 위한 기존 정수 헬퍼도 그대로 있습니다(블렌딩이나 안티에일리어싱 없이 버퍼에 직접 씁니다 — 그 밖의 용도에는 [캔버스](/ko/stdlib/gui-graphics)를 사용하십시오):

```c
win.fill_rect(x, y, w, h, color);
win.draw_rect_border(x, y, w, h, color, thickness);
win.draw_line(x0, y0, x1, y1, color);
int n = win.draw_text(x, y, "text", color, scale);   // built-in 8x8 font, ASCII
int ok = win.draw_svg(ox, oy, svgText);              // an SVG subset, see below
int gui_text_width(const &char text, int scale);     // gui_draw.h
int gui_text_height(int scale);
```

**비트맵 폰트 — `gui_font.h`.** `GuiFont`는 임의 크기 글리프의 비트 패킹된 비트맵입니다(`GuiFont::new`, 내장 8×8 폰트인 `GuiFont::default()`, `get_bit`/`set_bit`, `SCXF` 형식의 `save`/`load`, `draw_text`, `text_width`). `set_font()`로 받은 위젯은 이 폰트로 텍스트를 그립니다. 제대로 된 텍스트에는 [TrueType 폰트](/ko/stdlib/gui-graphics#text)를 사용하십시오.

**이미지 — `gui_png.h`.**

```c
struct GuiImage {
    &heap unsigned char pixels;   // width*height*4, RGBA8888
    int width, height;
    int premultiplied;            // 1 for canvas-ready pixels

    static struct GuiImage new(int width, int height);           // transparent
    static struct GuiImage from_window(const &GuiWindow win);    // a copy of the buffer
    int load_png([]const unsigned char data);
    int  load_png_file(const &char path);
    int  save_png(const &char path) const;                       // RGBA, stored deflate blocks
    void premultiply(); void unpremultiply();
    struct GuiImage copy() const;
    struct GuiImage scaled(int width, int height, int smooth) const;  // area average / bilinear
    unsigned int get_pixel(int x, int y) const; void set_pixel(int x, int y, unsigned int rgba);
    void draw(&GuiWindow win, int x, int y) const;              // a straight copy
    void free();
};
```

PNG 디코더(자체 DEFLATE 인플레이터 포함)는 8비트 그레이스케일, RGB, RGBA, 비인터레이스 이미지를 읽습니다. 팔레트 이미지, 16비트 깊이, Adam7은 0을 반환하며 깔끔하게 실패합니다. 블렌딩, 확대·축소, 필터링을 거쳐 이미지를 그리려면 캔버스(`draw_image*`, 이미지 패턴)를 사용하십시오.

**SVG — `draw_svg`.** `<svg>` 문서를 파싱해 `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, `<path>`(`M`/`L`/`H`/`V`/`C`/`Z`)를 `fill`/`stroke` 색으로 그립니다. `viewBox`, 변환, 그라디언트, 텍스트는 지원하지 않습니다. 안티에일리어싱된 벡터 그래픽에는 `GuiPath::add_svg()`가 SVG 경로 데이터 전체(호를 포함한 모든 명령)를 캔버스 경로로 파싱합니다.
