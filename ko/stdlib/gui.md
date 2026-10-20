# GUI (`std/gui/`)

소프트웨어 렌더링 GUI 툴킷입니다: 포터블한 창 + 이벤트 API, 픽셀 래스터라이저, 비트맵 폰트, PNG와 SVG 디코딩, 그리고 그 위의 리테인드 모드 위젯 트리로 구성됩니다. 모두 CPU 쪽 RGBA8888 버퍼 하나에 그리므로, UI는 어느 백엔드에서나 똑같이 보입니다.

```c
#include <std/gui/gui.h>         // window, events, pixel buffer
#include <std/gui/gui_draw.h>    // rectangles, lines, built-in 8x8 text
#include <std/gui/gui_font.h>    // loadable bitmap fonts
#include <std/gui/gui_png.h>     // PNG decoder
#include <std/gui/gui_svg.h>     // SVG subset renderer
#include <std/gui/gui_widget.h>  // retained-mode widgets (includes gui.h, gui_draw.h, gui_font.h)
```

`std/prelude.h`는 GUI 헤더를 포함하지 않습니다 — 직접 include하세요.

---

## 백엔드 {#backends}

모든 백엔드는 같은 `gui.h` API를 구현합니다. 모두 같은 심볼을 정의하므로 프로그램에는 정확히 하나만 링크할 수 있습니다.

| 백엔드 | 대상 | 상태 |
|---------|--------|--------|
| `gui_cocoa.sc` | macOS — Objective-C 런타임(`objc_msgSend`) + Core Graphics, 레이어 기반 `NSView` | 실제 하드웨어에서 빌드 및 실행됨 |
| `gui_win32.sc` | Windows — `RegisterClassExA`/`CreateWindowExA`, `StretchDIBits` | Win32 ABI에 맞춰 작성 및 타입 검사됨; Windows에서 아직 실행해 보지 않음 |
| `gui_x11.sc` | Linux/BSD — Xlib, `XPutImage` | Xlib ABI에 맞춰 작성 및 타입 검사됨; X 서버에서 아직 실행해 보지 않음 |
| `gui_fb.sc` | 베어메탈 — 부트로더가 넘겨준 선형 프레임버퍼 | 빌드 및 실행됨; OS 의존성 없음 |

백엔드는 `safeguard`가 골라 줍니다: 타깃의 std 아카이브에는 그 타깃에 맞는 변형만 들어갑니다(Apple은 `_cocoa`, Linux/BSD는 `_x11`, Windows는 `_win32`, 베어메탈 타깃은 `_fb`). [패키지 매니저](/ko/advanced/safeguard)를 참고하세요.

OS 백엔드는 시스템 라이브러리를 호출하므로, `[build] libs`로 최종 링크에 추가하세요:

| 백엔드 | 라이브러리 |
|---------|-----------|
| Cocoa | `objc`, AppKit 및 Core Graphics 프레임워크 |
| Win32 | `user32`, `gdi32` |
| X11 | `X11` |

GPU 경로는 없습니다. 매 프레임 `gui_present()`가 픽셀 버퍼를 화면으로 복사합니다(Cocoa는 `CGImage`를 레이어의 contents로 설정, Win32는 `StretchDIBits`, X11은 `XPutImage`, 베어메탈은 픽셀 포맷을 변환하며 복사). Rust의 `minifb`/`softbuffer`와 같은 범위입니다.

---

## 창과 이벤트 — `gui.h` {#window-and-events-gui-h}

```c
struct GuiWindow {
    ?&void               platform;    // backend handle; null on failure
    &heap unsigned char  pixels;      // width*height*4 bytes, RGBA8888, top-left origin, row-major
    int                  width;
    int                  height;
    int                  shouldClose; // set when the platform close control is used
};

struct GuiEvent {
    int          kind;       // GUI_EVENT_*
    double       x, y;       // mouse position, window-local
    int          button;     // 0 = left, 1 = right, 2 = other
    int          keycode;    // GUI_KEY_*
    unsigned int codepoint;  // GUI_EVENT_CHAR
    double       scrollDx, scrollDy;
    int          width, height;      // GUI_EVENT_RESIZE
    int          defaultPrevented;   // see gui_event_prevent_default()
};

struct GuiWindow gui_create_window(const &char title, int width, int height);
int  gui_poll_event(&GuiWindow win, &GuiEvent outEvent);  // 1 = got an event, 0 = queue empty; never blocks
void gui_present(&GuiWindow win);                          // blit pixels to the screen
void gui_set_pixel(&GuiWindow win, int x, int y, unsigned int rgba); // 0xRRGGBBAA; out of bounds = no-op
void gui_destroy_window(&GuiWindow win);
```

`gui_create_window`는 실패하면 `platform == null`이고 크기가 0인 창을 반환합니다 — 사용 전에 확인하세요.

이벤트와 키 상수는 `#define`이므로 어느 네임스페이스에서나 쓸 수 있습니다:

| 이벤트 | 키 |
|--------|------|
| `GUI_EVENT_NONE`, `GUI_EVENT_CLOSE`, `GUI_EVENT_RESIZE` | `GUI_KEY_LEFT`, `GUI_KEY_RIGHT`, `GUI_KEY_UP`, `GUI_KEY_DOWN` |
| `GUI_EVENT_MOUSE_DOWN`, `GUI_EVENT_MOUSE_UP`, `GUI_EVENT_MOUSE_MOVE` | `GUI_KEY_ENTER`, `GUI_KEY_ESCAPE`, `GUI_KEY_TAB` |
| `GUI_EVENT_KEY_DOWN`, `GUI_EVENT_KEY_UP`, `GUI_EVENT_CHAR` | `GUI_KEY_BACKSPACE`, `GUI_KEY_SPACE`, `GUI_KEY_DELETE` |
| `GUI_EVENT_SCROLL` | `GUI_KEY_UNKNOWN` |

각 백엔드가 네이티브 키 코드를 `GUI_KEY_*`로 변환하므로, 애플리케이션 코드는 플랫폼별 키 코드를 보지 않습니다.

### 예제 — 직접 그리는 루프 {#example-raw-drawing-loop}

```c
#include <std/gui/gui.h>
#include <std/gui/gui_draw.h>

int main() {
    struct GuiWindow win = std::gui_create_window("Hello", 320, 200);
    if (win.platform.is_null()) { return 1; }

    struct GuiEvent ev;
    while (!win.shouldClose) {
        while (std::gui_poll_event(&win, &ev)) {
            if (ev.kind == GUI_EVENT_KEY_DOWN && ev.keycode == GUI_KEY_ESCAPE) {
                win.shouldClose = 1;
            }
        }
        std::gui_fill_rect(&win, 0, 0, win.width, win.height, std::gui_rgb(30, 30, 40));
        std::gui_draw_rect_border(&win, 10, 10, 300, 180, std::gui_rgb(200, 200, 220), 2);
        std::gui_draw_text(&win, 24, 24, "Hello, SafeC", std::gui_rgb(255, 255, 255), 2);
        std::gui_present(&win);
    }
    std::gui_destroy_window(&win);
    return 0;
}
```

---

## 그리기 — `gui_draw.h` {#drawing-gui-draw-h}

```c
struct GuiColor { unsigned char r, g, b, a; };

struct GuiColor gui_rgba(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
struct GuiColor gui_rgb(unsigned char r, unsigned char g, unsigned char b);

void gui_fill_rect(&GuiWindow win, int x, int y, int w, int h, struct GuiColor color);
void gui_draw_rect_border(&GuiWindow win, int x, int y, int w, int h, struct GuiColor color, int thickness);
void gui_draw_line(&GuiWindow win, int x0, int y0, int x1, int y1, struct GuiColor color);

// Built-in 8x8 font, printable ASCII (0x20-0x7E); other bytes draw as a blank cell.
// Each glyph is 8*scale x 8*scale pixels. Returns the pixel width drawn.
int gui_draw_text(&GuiWindow win, int x, int y, const &char text, struct GuiColor color, int scale);
int gui_text_width(const &char text, int scale);
int gui_text_height(int scale);
```

---

## 폰트 — `gui_font.h` {#fonts-gui-font-h}

`GuiFont`는 임의 글리프 크기의 비트 패킹된 글리프 비트맵입니다. 코드로 만들고, 저장하고, 다시 로드하고, `_ex` 함수로 그립니다.

```c
struct GuiFont {
    int glyphW, glyphH;
    int first;                  // first codepoint covered
    int count;                  // codepoints first .. first+count-1
    &heap unsigned char bitmap;
};

struct GuiFont gui_font_new(int glyphW, int glyphH, int first, int count); // blank
struct GuiFont gui_font_default();                                         // copy of the built-in 8x8 font

int  gui_font_get_bit(const &GuiFont font, int cp, int px, int py);        // (0,0) = glyph top-left
void gui_font_set_bit(&GuiFont font, int cp, int px, int py, int value);

int  gui_font_save(const &GuiFont font, const &char path);  // 1 = ok
int  gui_font_load(&GuiFont outFont, const &char path);     // 1 = ok
void gui_font_free(&GuiFont font);

int gui_draw_text_ex(&GuiWindow win, int x, int y, const &char text,
                     struct GuiColor color, int scale, const &GuiFont font);
int gui_text_width_ex(const &char text, int scale, const &GuiFont font);
int gui_text_height_ex(int scale, const &GuiFont font);
```

파일 포맷은 `SCXF` 버전 1, 리틀 엔디언입니다:

| 오프셋 | 크기 | 필드 |
|--------|------|-------|
| 0 | 4 | 매직 `"SCXF"` |
| 4 | 1 | 버전 (`1`) |
| 5 | 1 | 글리프 너비 (1–255) |
| 6 | 1 | 글리프 높이 (1–255) |
| 7 | 1 | 예약 (`0`) |
| 8 | 4 | 첫 코드포인트 (u32) |
| 12 | 4 | 글리프 개수 (u32) |
| 16 | … | `count * ceil(w*h/8)` 바이트, 행 우선, MSB 먼저, 패딩 없음 |

TTF/OTF는 지원하지 않습니다: 폰트는 이미 `GuiFont`로 래스터화되어 있어야 합니다.

---

## 이미지 — `gui_png.h` {#images-gui-png-h}

자체 DEFLATE(RFC 1951) 인플레이터를 포함해 처음부터 작성한 PNG 디코더입니다.

```c
struct GuiImage {
    &heap unsigned char pixels; // width*height*4, RGBA8888
    int width;
    int height;
};

int  gui_load_png(const &unsigned char data, unsigned long size, &GuiImage out); // 1 = ok, 0 = failed (out zeroed)
void gui_draw_image(&GuiWindow win, int x, int y, const &GuiImage img);
void gui_image_free(&GuiImage img);
```

지원: 채널당 8비트, 비인터레이스, 컬러 타입 0(그레이스케일), 2(RGB), 6(RGBA), 다섯 가지 스캔라인 필터 전부. 미지원: 팔레트 이미지(타입 3), 16비트 깊이, Adam7 인터레이스, `tRNS`/`gAMA` 같은 보조 청크. 지원하지 않는 파일은 잘못 디코딩하지 않고 `0`으로 실패합니다. 청크 CRC는 검사하지 않습니다.

`gui_load_png`는 이미 메모리에 있는 바이트를 받습니다; 파일은 먼저 직접 읽으세요(예: `std/io_file.h`).

---

## SVG — `gui_svg.h` {#svg-gui-svg-h}

```c
// Parses a complete <svg>...</svg> document and draws it with its top-left at (ox, oy).
// Returns 1 if the <svg> root parsed, 0 on malformed or unsupported input.
int gui_draw_svg(&GuiWindow win, int ox, int oy, const &char svgText);
```

지원 요소: `<svg>`, `<g>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, 그리고 `M`/`L`/`H`/`V`/`C`/`Z` 명령을 쓰는 `<path>`(3차 곡선은 선분으로 평탄화). `fill`/`stroke`는 `#rrggbb`, `none`, 그리고 black, white, red, green, blue, yellow, cyan, magenta, gray, transparent 이름을 받습니다.

미지원: `viewBox`, `transform`, 그라디언트, 클립 경로, `<text>`, `<use>`, CSS. 파싱과 그리기가 한 번에 진행되므로, 파싱 오류 이전의 도형은 이미 그려진 상태입니다.

---

## 위젯 — `gui_widget.h` {#widgets-gui-widget-h}

리테인드 모드 위젯 트리입니다. 한 번 만들고, 매 프레임 레이아웃, 이벤트 디스패치, 렌더링을 합니다:

```c
std::gui_layout(root, 0, 0, win.width, win.height);
while (std::gui_poll_event(&win, &ev)) { std::gui_dispatch_event(root, &ev); }
std::gui_render(&win, root);
std::gui_present(&win);
```

### 내장 위젯 {#built-in-widgets}

| 생성자 | 종류 | 비고 |
|-------------|------|-------|
| `widget_vstack()` | `WIDGET_VSTACK` | 자식을 위에서 아래로 배치 |
| `widget_hstack()` | `WIDGET_HSTACK` | 자식을 왼쪽에서 오른쪽으로 배치 |
| `widget_button(text)` | `WIDGET_BUTTON` | 안에서 눌렀다 떼면 `onClick` 호출 |
| `widget_label(text)` | `WIDGET_LABEL` | 정적 텍스트 |
| `widget_checkbox(text, checked)` | `WIDGET_CHECKBOX` | `checked`를 토글하고 `onChange` 호출 |
| `widget_textinput(placeholder)` | `WIDGET_TEXTINPUT` | 한 줄 편집; `text`와 `caret`; `onChange` 호출 |
| `widget_slider(min, max, value)` | `WIDGET_SLIDER` | 드래그로 `sliderValue` 설정; `onChange` 호출 |
| `widget_spacer()` | `WIDGET_SPACER` | 빈 공간; stretch를 주면 형제를 밀어냄 |
| `widget_custom(draw, layout, eventFn, userData)` | `WIDGET_CUSTOM` | 직접 구현하는 그리기, 레이아웃, 이벤트 처리 |

모든 생성자는 기본 스타일이 적용된, null이 아닌 힙 할당 `&Widget`을 반환합니다. `widget_add_child(parent, child)`로 트리를 만들고, `widget_free(root)`로 서브트리 전체를 해제합니다.

### 커스터마이즈 {#customization}

모든 setter는 같은 위젯을 반환하므로 호출을 이어 쓸 수 있습니다:

```c
&Widget widget_set_bg(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a);
&Widget widget_set_fg(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a);
&Widget widget_set_border(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a, int width);
&Widget widget_set_padding(&Widget w, int padding);
&Widget widget_set_spacing(&Widget w, int spacing);     // gap between children of a stack
&Widget widget_set_pref_size(&Widget w, int prefW, int prefH); // 0 = size to content
&Widget widget_set_stretch(&Widget w, int stretch);     // 0 = natural size, >0 = flex weight
&Widget widget_set_alignment(&Widget w, int alignment); // cross axis: WIDGET_ALIGN_START, _CENTER, _END, _STRETCH
&Widget widget_set_font_scale(&Widget w, int scale);
&Widget widget_set_font(&Widget w, const ?&GuiFont font); // null = built-in font
&Widget widget_set_visible(&Widget w, int visible);
&Widget widget_set_enabled(&Widget w, int enabled);
&Widget widget_set_text(&Widget w, const &char text);

generic<T> &Widget widget_on_click(&Widget w, fn void(&Widget, ?&T) cb, ?&T userData);
generic<T> &Widget widget_on_change(&Widget w, fn void(&Widget, ?&T) cb, ?&T userData);
```

`widget_on_click`/`widget_on_change`는 콜백을 컨텍스트 타입에 맞춰 컴파일 타임에 검사합니다. 위젯은 이 쌍을 타입 소거해 저장하는데, 하나의 트리가 서로 무관한 컨텍스트 타입을 가진 버튼, 슬라이더, 커스텀 위젯을 함께 담기 때문입니다([제네릭 패턴](/ko/stdlib/#generic-pattern) 참고).

### 레이아웃 {#layout}

`gui_layout(root, x, y, w, h)`는 트리 전체를 박스 안에 배치합니다. 스택은 먼저 `stretch == 0`인 각 자식에게 선호 크기나 자연 크기를 주고, 남은 공간을 stretch된 자식들에게 가중치대로 나눕니다. `padding`은 컨테이너 안쪽에, `spacing`은 자식 사이에 적용되며, `alignment`는 각 자식을 교차 축에 배치합니다.

### 이벤트와 키보드 기본 동작 {#events-and-keyboard-defaults}

`gui_dispatch_event(root, &ev)`는 이벤트를 커서 아래 가장 깊은 위젯부터 전달하고, `hover`/`pressed`/`focused`를 갱신하고, `onClick`/`onChange`를 호출하고, 커스텀 위젯의 이벤트 훅을 부릅니다. 어떤 위젯이 이벤트를 사용했으면 1을 반환합니다.

`GUI_EVENT_KEY_DOWN`이면 그다음 브라우저의 키 기본 동작처럼 기본 동작이 실행됩니다:

| 키 | 기본 동작 |
|-----|----------------|
| Tab | 다음 포커스 가능한 위젯으로 포커스 이동 (순환) |
| Enter | 포커스된 위젯 활성화 (버튼 클릭, 체크박스 토글) |
| Escape | 포커스 해제 |
| Left / Right | 포커스된 슬라이더를 범위의 5%만큼 이동 |

이 동작은 위젯이 이미 이벤트를 처리했어도 실행됩니다. 막으려면 커스텀 위젯의 이벤트 훅에서 `gui_event_prevent_default(&ev)`를 호출하세요.

### 커스텀 위젯 {#custom-widgets}

```c
typedef fn void(&GuiWindow, &Widget) WidgetDrawFn;               // draw inside widget.x/y/w/h
typedef fn void(&Widget, int, int, int, int) WidgetLayoutFn;      // set widget.x/y/w/h, lay out children
typedef fn int(&Widget, &GuiEvent) WidgetCustomEventFn;           // 1 = handled, stop dispatch

generic<T>
&Widget widget_custom(WidgetDrawFn draw, WidgetLayoutFn layout,
                      WidgetCustomEventFn eventFn, ?&T userData);
```

`draw`와 `layout`은 필수입니다. `eventFn`은 null이어도 됩니다: 그러면 위젯은 버튼과 같은 hover, press, click 처리를 받습니다. 캔버스의 드래그 변화량처럼 원시 이벤트가 필요할 때만 설정하세요.

### 예제 — 카운터 {#example-counter}

```c
#include <std/gui/gui_widget.h>

struct Counter {
    int clicks;
    &Widget label;
};

static void on_click(&Widget w, ?&Counter ctx) {
    match (ctx) {
        case some(c): {
            c.clicks = c.clicks + 1;
            std::widget_set_text(c.label, c.clicks == 1 ? "Clicked once" : "Clicked again");
        }
        case null: { }
    }
}

int main() {
    struct GuiWindow win = std::gui_create_window("Counter", 320, 160);
    if (win.platform.is_null()) { return 1; }

    &Widget root  = std::widget_set_spacing(std::widget_set_padding(std::widget_vstack(), 12), 8);
    &Widget label = std::widget_label("Not clicked");
    &Widget button = std::widget_set_bg(std::widget_button("Click me"), 60, 120, 200, 255);

    struct Counter counter;
    counter.clicks = 0;
    counter.label = label;
    unsafe { std::widget_on_click(button, on_click, (?&Counter)&counter); }

    std::widget_add_child(root, label);
    std::widget_add_child(root, button);

    struct GuiEvent ev;
    while (!win.shouldClose) {
        std::gui_layout(root, 0, 0, win.width, win.height);
        while (std::gui_poll_event(&win, &ev)) { std::gui_dispatch_event(root, &ev); }
        std::gui_render(&win, root);
        std::gui_present(&win);
    }

    std::widget_free(root);
    std::gui_destroy_window(&win);
    return 0;
}
```

---

## 베어메탈 — `gui_fb.sc` {#bare-metal-gui-fb-sc}

베어메탈에는 창 시스템도 입력 스택도 없습니다. 창을 만들기 전에 프레임버퍼 위치를 백엔드에 알려 주고, 입력은 직접 작성한 드라이버에서 넣어 주세요.

```c
struct GuiFbConfig {
    &volatile unsigned char base;  // linear framebuffer memory
    unsigned long pitch;  // bytes per scanline (may include padding)
    int width;
    int height;
    int bpp;              // 32 (xRGB/xBGR) or 16 (RGB565)
    int redShift; int greenShift; int blueShift; // channel bit positions, 32 bpp only
};

void gui_fb_configure(struct GuiFbConfig cfg);                 // call once, before gui_create_window()
void gui_fb_inject_event(&GuiWindow win, struct GuiEvent ev);  // call from your keyboard/mouse/touch ISR
```

값은 보통 부트로더에서 옵니다. 예를 들어 Multiboot2 프레임버퍼 태그(`framebuffer_addr`, `framebuffer_pitch`, `framebuffer_bpp`)나 VESA VBE 모드입니다. `gui_fb_configure` 없이는 `gui_create_window`가 실패합니다(`platform`이 null). 주입된 이벤트는 `gui_poll_event`가 비우는 32칸 큐(`GUI_FB_QUEUE_CAP`)에 들어가며, 큐가 가득 차면 버려집니다. `gui_present`는 복사하면서 RGBA 버퍼를 프레임버퍼의 픽셀 포맷으로 변환합니다.

타깃과 링크는 [베어메탈](/ko/reference/baremetal)을 참고하세요.
