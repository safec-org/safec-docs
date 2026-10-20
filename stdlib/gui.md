# GUI (`std/gui/`)

A software-rendered GUI toolkit: a portable window + event API, a pixel rasterizer, bitmap fonts, PNG and SVG decoding, and a retained-mode widget tree on top. All of it draws into one CPU-side RGBA8888 buffer, so a UI looks the same on every backend.

```c
#include <std/gui/gui.h>         // window, events, pixel buffer
#include <std/gui/gui_draw.h>    // rectangles, lines, built-in 8x8 text
#include <std/gui/gui_font.h>    // loadable bitmap fonts
#include <std/gui/gui_png.h>     // PNG decoder
#include <std/gui/gui_svg.h>     // SVG subset renderer
#include <std/gui/gui_widget.h>  // retained-mode widgets (includes gui.h, gui_draw.h, gui_font.h)
```

`std/prelude.h` does not include the GUI headers — include them directly.

---

## Backends

Every backend implements the same `gui.h` API. Exactly one may be linked into a program, since they all define the same symbols.

| Backend | Target | Status |
|---------|--------|--------|
| `gui_cocoa.sc` | macOS — Objective-C runtime (`objc_msgSend`) + Core Graphics, layer-backed `NSView` | Built and run on real hardware |
| `gui_win32.sc` | Windows — `RegisterClassExA`/`CreateWindowExA`, `StretchDIBits` | Written against the Win32 ABI and type-checked; not yet run on Windows |
| `gui_x11.sc` | Linux/BSD — Xlib, `XPutImage` | Written against the Xlib ABI and type-checked; not yet run on an X server |
| `gui_fb.sc` | Bare metal — linear framebuffer from the bootloader | Built and run; no OS dependency |

`safeguard` picks the backend for you: the std archive for a target contains only the variant that matches it (`_cocoa` on Apple, `_x11` on Linux/BSD, `_win32` on Windows, `_fb` on bare-metal targets). See [Package Manager](/advanced/safeguard).

The OS backends call system libraries, so add them to the final link with `[build] libs`:

| Backend | Libraries |
|---------|-----------|
| Cocoa | `objc`, plus the AppKit and Core Graphics frameworks |
| Win32 | `user32`, `gdi32` |
| X11 | `X11` |

There is no GPU path. Each frame, `gui_present()` copies the pixel buffer to the screen (a `CGImage` set as the layer's contents on Cocoa, `StretchDIBits` on Win32, `XPutImage` on X11, a format-converting copy on bare metal). This is the same scope as Rust's `minifb`/`softbuffer`.

---

## Window and Events — `gui.h`

```c
struct GuiWindow {
    void*                platform;    // backend handle; null on failure
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

struct GuiWindow gui_create_window(const char* title, int width, int height);
int  gui_poll_event(&GuiWindow win, &GuiEvent outEvent);  // 1 = got an event, 0 = queue empty; never blocks
void gui_present(&GuiWindow win);                          // blit pixels to the screen
void gui_set_pixel(&GuiWindow win, int x, int y, unsigned int rgba); // 0xRRGGBBAA; out of bounds = no-op
void gui_destroy_window(&GuiWindow win);
```

`gui_create_window` returns a window with `platform == null` and zero size on failure — check it before use.

The event and key constants are `#define`s, so they work from any namespace:

| Events | Keys |
|--------|------|
| `GUI_EVENT_NONE`, `GUI_EVENT_CLOSE`, `GUI_EVENT_RESIZE` | `GUI_KEY_LEFT`, `GUI_KEY_RIGHT`, `GUI_KEY_UP`, `GUI_KEY_DOWN` |
| `GUI_EVENT_MOUSE_DOWN`, `GUI_EVENT_MOUSE_UP`, `GUI_EVENT_MOUSE_MOVE` | `GUI_KEY_ENTER`, `GUI_KEY_ESCAPE`, `GUI_KEY_TAB` |
| `GUI_EVENT_KEY_DOWN`, `GUI_EVENT_KEY_UP`, `GUI_EVENT_CHAR` | `GUI_KEY_BACKSPACE`, `GUI_KEY_SPACE`, `GUI_KEY_DELETE` |
| `GUI_EVENT_SCROLL` | `GUI_KEY_UNKNOWN` |

Each backend translates native key codes to `GUI_KEY_*`, so application code never sees a platform key code.

### Example — raw drawing loop

```c
#include <std/gui/gui.h>
#include <std/gui/gui_draw.h>

int main() {
    struct GuiWindow win = std::gui_create_window("Hello", 320, 200);
    int ok = 0;
    unsafe { ok = win.platform != (void*)0; }
    if (!ok) { return 1; }

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

## Drawing — `gui_draw.h`

```c
struct GuiColor { unsigned char r, g, b, a; };

struct GuiColor gui_rgba(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
struct GuiColor gui_rgb(unsigned char r, unsigned char g, unsigned char b);

void gui_fill_rect(&GuiWindow win, int x, int y, int w, int h, struct GuiColor color);
void gui_draw_rect_border(&GuiWindow win, int x, int y, int w, int h, struct GuiColor color, int thickness);
void gui_draw_line(&GuiWindow win, int x0, int y0, int x1, int y1, struct GuiColor color);

// Built-in 8x8 font, printable ASCII (0x20-0x7E); other bytes draw as a blank cell.
// Each glyph is 8*scale x 8*scale pixels. Returns the pixel width drawn.
int gui_draw_text(&GuiWindow win, int x, int y, const char* text, struct GuiColor color, int scale);
int gui_text_width(const char* text, int scale);
int gui_text_height(int scale);
```

---

## Fonts — `gui_font.h`

A `GuiFont` is a bit-packed glyph bitmap of any glyph size. Build one in code, save it, load it back, and draw with the `_ex` functions.

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

int  gui_font_save(const &GuiFont font, const char* path);  // 1 = ok
int  gui_font_load(&GuiFont outFont, const char* path);     // 1 = ok
void gui_font_free(&GuiFont font);

int gui_draw_text_ex(&GuiWindow win, int x, int y, const char* text,
                     struct GuiColor color, int scale, const &GuiFont font);
int gui_text_width_ex(const char* text, int scale, const &GuiFont font);
int gui_text_height_ex(int scale, const &GuiFont font);
```

The file format is `SCXF` version 1, little-endian:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | magic `"SCXF"` |
| 4 | 1 | version (`1`) |
| 5 | 1 | glyph width (1–255) |
| 6 | 1 | glyph height (1–255) |
| 7 | 1 | reserved (`0`) |
| 8 | 4 | first codepoint (u32) |
| 12 | 4 | glyph count (u32) |
| 16 | … | `count * ceil(w*h/8)` bytes, row-major, MSB first, no padding |

TTF/OTF is not supported: a font must already be rasterized into a `GuiFont`.

---

## Images — `gui_png.h`

A from-scratch PNG decoder, including its own DEFLATE (RFC 1951) inflater.

```c
struct GuiImage {
    &heap unsigned char pixels; // width*height*4, RGBA8888
    int width;
    int height;
};

int  gui_load_png(const unsigned char* data, unsigned long size, &GuiImage out); // 1 = ok, 0 = failed (out zeroed)
void gui_draw_image(&GuiWindow win, int x, int y, const &GuiImage img);
void gui_image_free(&GuiImage img);
```

Supported: 8-bit-per-channel, non-interlaced, color types 0 (grayscale), 2 (RGB) and 6 (RGBA), all five scanline filters. Not supported: palette images (type 3), 16-bit depth, Adam7 interlacing, ancillary chunks such as `tRNS`/`gAMA`. Unsupported files fail with `0` rather than decoding wrongly. Chunk CRCs are not checked.

`gui_load_png` takes bytes already in memory; read the file yourself first (for example with `std/io_file.h`).

---

## SVG — `gui_svg.h`

```c
// Parses a complete <svg>...</svg> document and draws it with its top-left at (ox, oy).
// Returns 1 if the <svg> root parsed, 0 on malformed or unsupported input.
int gui_draw_svg(&GuiWindow win, int ox, int oy, const char* svgText);
```

Supported elements: `<svg>`, `<g>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, and `<path>` with `M`/`L`/`H`/`V`/`C`/`Z` (cubic curves are flattened). `fill`/`stroke` accept `#rrggbb`, `none`, and the names black, white, red, green, blue, yellow, cyan, magenta, gray, transparent.

Not supported: `viewBox`, `transform`, gradients, clip paths, `<text>`, `<use>`, CSS. Parsing and drawing happen in one pass, so shapes before a parse error are already drawn.

---

## Widgets — `gui_widget.h`

A retained-mode widget tree. Build it once, then lay it out, dispatch events, and render every frame:

```c
std::gui_layout(root, 0, 0, win.width, win.height);
while (std::gui_poll_event(&win, &ev)) { std::gui_dispatch_event(root, &ev); }
std::gui_render(&win, root);
std::gui_present(&win);
```

### Built-in widgets

| Constructor | Kind | Notes |
|-------------|------|-------|
| `widget_vstack()` | `WIDGET_VSTACK` | Lays children out top to bottom |
| `widget_hstack()` | `WIDGET_HSTACK` | Lays children out left to right |
| `widget_button(text)` | `WIDGET_BUTTON` | Fires `onClick` on press + release inside it |
| `widget_label(text)` | `WIDGET_LABEL` | Static text |
| `widget_checkbox(text, checked)` | `WIDGET_CHECKBOX` | Toggles `checked`, fires `onChange` |
| `widget_textinput(placeholder)` | `WIDGET_TEXTINPUT` | Single-line edit; `text` and `caret`; fires `onChange` |
| `widget_slider(min, max, value)` | `WIDGET_SLIDER` | Drag to set `sliderValue`; fires `onChange` |
| `widget_spacer()` | `WIDGET_SPACER` | Empty space; give it a stretch to push siblings apart |
| `widget_custom(draw, layout, eventFn, userData)` | `WIDGET_CUSTOM` | Your own drawing, layout and event handling |

Every constructor returns a non-null, heap-allocated `&Widget` with default styling. `widget_add_child(parent, child)` builds the tree; `widget_free(root)` frees the whole subtree.

### Customization

Every setter returns the same widget, so calls chain:

```c
&Widget widget_set_bg(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a);
&Widget widget_set_fg(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a);
&Widget widget_set_border(&Widget w, unsigned char r, unsigned char g, unsigned char b, unsigned char a, int width);
&Widget widget_set_padding(&Widget w, int padding);
&Widget widget_set_spacing(&Widget w, int spacing);     // gap between children of a stack
&Widget widget_set_pref_size(&Widget w, int prefW, int prefH); // 0 = size to content
&Widget widget_set_stretch(&Widget w, int stretch);     // 0 = natural size, >0 = flex weight
&Widget widget_set_alignment(&Widget w, int alignment); // WIDGET_ALIGN_START/CENTER/END/STRETCH (cross axis)
&Widget widget_set_font_scale(&Widget w, int scale);
&Widget widget_set_font(&Widget w, const ?&GuiFont font); // null = built-in font
&Widget widget_set_visible(&Widget w, int visible);
&Widget widget_set_enabled(&Widget w, int enabled);
&Widget widget_set_text(&Widget w, const char* text);

generic<T> &Widget widget_on_click(&Widget w, fn void(&Widget, ?&T) cb, ?&T userData);
generic<T> &Widget widget_on_change(&Widget w, fn void(&Widget, ?&T) cb, ?&T userData);
```

`widget_on_click`/`widget_on_change` check the callback against its context type at compile time. The widget stores the pair type-erased, because one tree holds buttons, sliders and custom widgets with unrelated context types (see [Generic Pattern](/stdlib/#generic-pattern)).

### Layout

`gui_layout(root, x, y, w, h)` positions the whole tree inside the box. A stack first gives each child with `stretch == 0` its preferred or natural size, then splits the remaining space among stretched children by weight. `padding` applies inside a container, `spacing` between its children, and `alignment` places each child on the cross axis.

### Events and keyboard defaults

`gui_dispatch_event(root, &ev)` sends the event to the deepest widget under the cursor first, updates `hover`/`pressed`/`focused`, fires `onClick`/`onChange`, and calls a custom widget's event hook. It returns 1 if a widget used the event.

For `GUI_EVENT_KEY_DOWN`, a default action then runs, like a browser's default action for a key:

| Key | Default action |
|-----|----------------|
| Tab | Focus the next focusable widget (wraps) |
| Enter | Activate the focused widget (click a button, toggle a checkbox) |
| Escape | Clear focus |
| Left / Right | Move a focused slider by 5% of its range |

These run even when a widget already handled the event. To stop one, call `gui_event_prevent_default(&ev)` from a custom widget's event hook.

### Custom widgets

```c
typedef fn void(struct GuiWindow*, struct Widget*) WidgetDrawFn;          // draw inside widget->x/y/w/h
typedef fn void(struct Widget*, int, int, int, int) WidgetLayoutFn;       // set widget->x/y/w/h, lay out children
typedef fn int(struct Widget*, struct GuiEvent*) WidgetCustomEventFn;     // 1 = handled, stop dispatch

generic<T>
&Widget widget_custom(WidgetDrawFn draw, WidgetLayoutFn layout,
                      WidgetCustomEventFn eventFn, ?&T userData);
```

`draw` and `layout` are required. `eventFn` may be null: the widget then gets the same hover, press and click handling as a button. Set it only when you need raw events, such as drag deltas on a canvas.

### Example — counter

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
    int ok = 0;
    unsafe { ok = win.platform != (void*)0; }
    if (!ok) { return 1; }

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

## Bare Metal — `gui_fb.sc`

There is no windowing system or input stack on bare metal. Tell the backend where the framebuffer is before creating the window, and feed it input from your own drivers.

```c
struct GuiFbConfig {
    void* base;           // linear framebuffer address
    unsigned long pitch;  // bytes per scanline (may include padding)
    int width;
    int height;
    int bpp;              // 32 (xRGB/xBGR) or 16 (RGB565)
    int redShift; int greenShift; int blueShift; // channel bit positions, 32 bpp only
};

void gui_fb_configure(struct GuiFbConfig cfg);                 // call once, before gui_create_window()
void gui_fb_inject_event(&GuiWindow win, struct GuiEvent ev);  // call from your keyboard/mouse/touch ISR
```

The values usually come from the bootloader, for example a Multiboot2 framebuffer tag (`framebuffer_addr`, `framebuffer_pitch`, `framebuffer_bpp`) or a VESA VBE mode. Without `gui_fb_configure`, `gui_create_window` fails (`platform` is null). Injected events go into a 32-entry queue (`GUI_FB_QUEUE_CAP`) that `gui_poll_event` drains; events are dropped when it is full. `gui_present` converts the RGBA buffer to the framebuffer's pixel format as it copies.

See [Bare-Metal](/reference/baremetal) for targets and linking.
