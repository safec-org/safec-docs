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

## Backends {#backends}

Every backend implements the same `gui.h` API. Exactly one may be linked into a program, since they all define the same symbols.

| Backend | Target | Status |
|---------|--------|--------|
| `gui_cocoa.sc` | macOS — Objective-C runtime (`objc_msgSend`) + Core Graphics, layer-backed `NSView` | Built and run on real hardware |
| `gui_win32.sc` | Windows — `RegisterClassExA`/`CreateWindowExA`, `StretchDIBits` | Run under Wine (`tests/stdlib/win32/run.sh`): drawing read back with `GetPixel`, key, text, mouse, wheel, resize and close events; not yet run on a Windows host |
| `gui_x11.sc` | Linux/BSD — Xlib, `XPutImage` | Run against an X server (`tests/stdlib/x11/run.sh`, Xvfb): drawing read back with `XGetImage`, key, text, mouse, scroll, resize and close events |
| `gui_fb.sc` | Bare metal — linear framebuffer from the bootloader; setup calls in `gui/gui_fb.h` | Built and run; no OS dependency |

`safeguard` picks the backend for you: the std archive for a target contains only the variant that matches it (`_cocoa` on Apple, `_x11` on Linux/BSD, `_win32` on Windows, `_fb` on bare-metal targets). See [Package Manager](/advanced/safeguard).

The OS backends call system libraries, so add them to the final link with `[build] libs`:

| Backend | Libraries |
|---------|-----------|
| Cocoa | `objc`, plus the AppKit and Core Graphics frameworks |
| Win32 | `user32`, `gdi32` |
| X11 | `X11` |

There is no GPU path. Each frame, `win.present()` copies the pixel buffer to the screen (a `CGImage` set as the layer's contents on Cocoa, `StretchDIBits` on Win32, `XPutImage` on X11, a format-converting copy on bare metal). This is the same scope as Rust's `minifb`/`softbuffer`.

---

## Window and Events — `gui.h` {#window-and-events-gui-h}

```c
struct GuiWindow {
    ?&void               platform;    // backend handle; null on failure
    &heap unsigned char  pixels;      // width*height*4 bytes, RGBA8888, top-left origin, row-major
    int                  width;
    int                  height;
    int                  shouldClose; // set when the platform close control is used

    static struct GuiWindow create(const &char title, int width, int height);
    int  poll_event(&GuiEvent outEvent);   // 1 = got an event, 0 = queue empty; never blocks
    void present();                        // blit pixels to the screen
    void set_pixel(int x, int y, unsigned int rgba); // 0xRRGGBBAA; out of bounds = no-op
    void destroy();

    // drawing, see gui_draw.h and gui_svg.h
    void fill_rect(int x, int y, int w, int h, struct GuiColor color);
    void draw_rect_border(int x, int y, int w, int h, struct GuiColor color, int thickness);
    void draw_line(int x0, int y0, int x1, int y1, struct GuiColor color);
    int  draw_text(int x, int y, const &char text, struct GuiColor color, int scale);
    int  draw_svg(int ox, int oy, const &char svgText);
};

struct GuiEvent {
    int          kind;       // GUI_EVENT_*
    double       x, y;       // mouse position, window-local
    int          button;     // 0 = left, 1 = right, 2 = other
    int          keycode;    // GUI_KEY_*
    unsigned int codepoint;  // the typed character (GUI_EVENT_KEY_DOWN / GUI_EVENT_CHAR)
    double       scrollDx, scrollDy;
    int          width, height;      // GUI_EVENT_RESIZE
    int          defaultPrevented;   // see prevent_default()

    void prevent_default();  // skip the widget tree's default key action
};

struct GuiColor {
    unsigned char r, g, b, a;

    static struct GuiColor rgba(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
    static struct GuiColor rgb(unsigned char r, unsigned char g, unsigned char b);  // a = 255
};
```

`GuiWindow::create` returns a window with `platform == null` and zero size on failure — check it before use.

The event and key constants are `#define`s, so they work from any namespace:

| Events | Keys |
|--------|------|
| `GUI_EVENT_NONE`, `GUI_EVENT_CLOSE`, `GUI_EVENT_RESIZE` | `GUI_KEY_LEFT`, `GUI_KEY_RIGHT`, `GUI_KEY_UP`, `GUI_KEY_DOWN` |
| `GUI_EVENT_MOUSE_DOWN`, `GUI_EVENT_MOUSE_UP`, `GUI_EVENT_MOUSE_MOVE` | `GUI_KEY_ENTER`, `GUI_KEY_ESCAPE`, `GUI_KEY_TAB` |
| `GUI_EVENT_KEY_DOWN`, `GUI_EVENT_KEY_UP`, `GUI_EVENT_CHAR` | `GUI_KEY_BACKSPACE`, `GUI_KEY_SPACE`, `GUI_KEY_DELETE` |
| `GUI_EVENT_SCROLL` | `GUI_KEY_UNKNOWN` |

Each backend translates native key codes to `GUI_KEY_*`, so application code never sees a platform key code. A key press that types a character carries it in `codepoint` (printable ASCII); when the size changes, `poll_event` resizes `win.pixels`, `win.width` and `win.height` to the new client area before returning `GUI_EVENT_RESIZE`, so redraw everything after one.

### Example — raw drawing loop {#example-raw-drawing-loop}

```c
#include <std/gui/gui.h>
#include <std/gui/gui_draw.h>

int main() {
    struct GuiWindow win = std::GuiWindow::create("Hello", 320, 200);
    if (win.platform.is_null()) { return 1; }

    struct GuiEvent ev;
    while (!win.shouldClose) {
        while (win.poll_event(&ev)) {
            if (ev.kind == GUI_EVENT_KEY_DOWN && ev.keycode == GUI_KEY_ESCAPE) {
                win.shouldClose = 1;
            }
        }
        win.fill_rect(0, 0, win.width, win.height, std::GuiColor::rgb(30, 30, 40));
        win.draw_rect_border(10, 10, 300, 180, std::GuiColor::rgb(200, 200, 220), 2);
        win.draw_text(24, 24, "Hello, SafeC", std::GuiColor::rgb(255, 255, 255), 2);
        win.present();
    }
    win.destroy();
    return 0;
}
```

---

## Drawing — `gui_draw.h` {#drawing-gui-draw-h}

The drawing primitives are `GuiWindow` methods, declared in `gui.h` next to
`GuiColor` and defined in `gui_draw.sc`. They all write through
`set_pixel`, so they work on every backend; out-of-bounds pixels are
clipped.

```c
win.fill_rect(x, y, w, h, color);
win.draw_rect_border(x, y, w, h, color, thickness);
win.draw_line(x0, y0, x1, y1, color);

// Built-in 8x8 font, printable ASCII (0x20-0x7E); other bytes draw as a blank cell.
// Each glyph is 8*scale x 8*scale pixels. Returns the pixel width drawn.
int n = win.draw_text(x, y, "text", GuiColor::rgb(255, 255, 255), scale);
```

`gui_draw.h` adds the built-in font's metrics, for sizing without drawing:

```c
int gui_text_width(const &char text, int scale);
int gui_text_height(int scale);
```

---

## Fonts — `gui_font.h` {#fonts-gui-font-h}

A `GuiFont` is a bit-packed glyph bitmap of any glyph size. Build one in code, save it, load it back, and draw with its own `draw_text`.

```c
struct GuiFont {
    int glyphW, glyphH;
    int first;                  // first codepoint covered
    int count;                  // codepoints first .. first+count-1
    &heap unsigned char bitmap;

    static struct GuiFont new(int glyphW, int glyphH, int first, int count); // blank
    static struct GuiFont default();          // heap copy of the built-in 8x8 font

    int  get_bit(int cp, int px, int py) const;   // (0,0) = glyph top-left
    void set_bit(int cp, int px, int py, int value);

    int  save(const &char path) const;  // 1 = ok
    int  load(const &char path);        // 1 = ok; overwrites without freeing, untouched on failure
    void free();

    int  draw_text(&GuiWindow win, int x, int y, const &char text,
                   struct GuiColor color, int scale) const;
    int  text_width(const &char text, int scale) const;
    int  text_height(int scale) const;
};
```

Out-of-range codepoints or pixels read as 0 and are ignored on write. Call
`load` on a fresh `struct GuiFont f;` (or after `free()`), since it does not
free the font's previous bitmap.

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

## Images — `gui_png.h` {#images-gui-png-h}

A from-scratch PNG decoder, including its own DEFLATE (RFC 1951) inflater.

```c
struct GuiImage {
    &heap unsigned char pixels; // width*height*4, RGBA8888
    int width;
    int height;

    int  load_png(const &unsigned char data, unsigned long size); // in place; 1 = ok, 0 = failed (image zeroed)
    void draw(&GuiWindow win, int x, int y) const;                 // straight copy, no blending
    void free();
};
```

```c
struct GuiImage img;
if (img.load_png(data, size)) {
    img.draw(&win, 10, 10);
    img.free();
}
```

Supported: 8-bit-per-channel, non-interlaced, color types 0 (grayscale), 2 (RGB) and 6 (RGBA), all five scanline filters. Not supported: palette images (type 3), 16-bit depth, Adam7 interlacing, ancillary chunks such as `tRNS`/`gAMA`. Unsupported files fail with `0` rather than decoding wrongly. Chunk CRCs are not checked.

`load_png` takes bytes already in memory; read the file yourself first (for example with `std/io_file.h`).

---

## SVG — `gui_svg.h` {#svg-gui-svg-h}

The renderer is a `GuiWindow` method, declared in `gui.h` and defined in
`gui_svg.sc`; there is no retained SVG object.

```c
// Parses a complete <svg>...</svg> document and draws it with its top-left at (ox, oy).
// Returns 1 if the <svg> root parsed, 0 on malformed or unsupported input.
int GuiWindow::draw_svg(int ox, int oy, const &char svgText);
```

Supported elements: `<svg>`, `<g>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, and `<path>` with `M`/`L`/`H`/`V`/`C`/`Z` (cubic curves are flattened). `fill`/`stroke` accept `#rrggbb`, `none`, and the names black, white, red, green, blue, yellow, cyan, magenta, gray, transparent.

Not supported: `viewBox`, `transform`, gradients, clip paths, `<text>`, `<use>`, CSS. Parsing and drawing happen in one pass, so shapes before a parse error are already drawn.

---

## Widgets — `gui_widget.h` {#widgets-gui-widget-h}

A retained-mode widget tree. Build it once, then lay it out, dispatch events, and render every frame:

```c
root.layout(0, 0, win.width, win.height);
while (win.poll_event(&ev)) { root.dispatch_event(&ev); }
root.render(&win);
win.present();
```

### Built-in widgets {#built-in-widgets}

| Constructor | Kind | Notes |
|-------------|------|-------|
| `Widget::vstack()` | `WIDGET_VSTACK` | Lays children out top to bottom |
| `Widget::hstack()` | `WIDGET_HSTACK` | Lays children out left to right |
| `Widget::button(text)` | `WIDGET_BUTTON` | Fires `onClick` on press + release inside it |
| `Widget::label(text)` | `WIDGET_LABEL` | Static text |
| `Widget::checkbox(text, checked)` | `WIDGET_CHECKBOX` | Toggles `checked`, fires `onChange` |
| `Widget::textinput(placeholder)` | `WIDGET_TEXTINPUT` | Single-line edit; `text` and `caret`; fires `onChange` |
| `Widget::slider(min, max, value)` | `WIDGET_SLIDER` | Drag to set `sliderValue`; fires `onChange` |
| `Widget::spacer()` | `WIDGET_SPACER` | Empty space; give it a stretch to push siblings apart |
| `Widget::custom(draw, layout, eventFn, userData)` | `WIDGET_CUSTOM` | Your own drawing, layout and event handling |

Every constructor is a [static method](/reference/functions#static-methods) returning a non-null, heap-allocated `&Widget` with default styling. `parent.add_child(child)` builds the tree; `root.free()` frees the whole subtree.

### Customization {#customization}

Every setter returns the same widget, so calls chain
(`Widget::button("Go").set_padding(8).set_bg(60, 120, 200, 255)`):

```c
struct Widget {
    // ...
    void    add_child(&Widget child);

    &Widget set_bg(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
    &Widget set_fg(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
    &Widget set_border(unsigned char r, unsigned char g, unsigned char b, unsigned char a, int width);
    &Widget set_padding(int padding);
    &Widget set_spacing(int spacing);         // gap between children of a stack
    &Widget set_pref_size(int prefW, int prefH); // 0 = size to content
    &Widget set_stretch(int stretch);         // 0 = natural size, >0 = flex weight
    &Widget set_alignment(int alignment);     // cross axis: WIDGET_ALIGN_START, _CENTER, _END, _STRETCH
    &Widget set_font_scale(int scale);
    &Widget set_font(const ?&GuiFont font);   // null = built-in font
    &Widget set_visible(int visible);
    &Widget set_enabled(int enabled);
    &Widget set_text(const &char text);

    generic<T> &Widget on_click(fn void(&Widget, ?&T) cb, ?&T userData);
    generic<T> &Widget on_change(fn void(&Widget, ?&T) cb, ?&T userData);

    void layout(int x, int y, int w, int h);
    void render(&GuiWindow win);
    int  dispatch_event(&GuiEvent ev);
    void free();                              // the whole subtree
};
```

`on_click`/`on_change` are [methods with their own type parameter](/reference/generics#methods-with-their-own-type-parameters): `T` is inferred from the callback, which is checked against its context type at compile time. The widget stores the pair type-erased, because one tree holds buttons, sliders and custom widgets with unrelated context types (see [Generic Pattern](/stdlib/#generic-pattern)).

### Layout {#layout}

`root.layout(x, y, w, h)` positions the whole tree inside the box. A stack first gives each child with `stretch == 0` its preferred or natural size, then splits the remaining space among stretched children by weight. `padding` applies inside a container, `spacing` between its children, and `alignment` places each child on the cross axis.

### Events and keyboard defaults {#events-and-keyboard-defaults}

`root.dispatch_event(&ev)` sends the event to the deepest widget under the cursor first, updates `hover`/`pressed`/`focused`, fires `onClick`/`onChange`, and calls a custom widget's event hook. It returns 1 if a widget used the event.

For `GUI_EVENT_KEY_DOWN`, a default action then runs, like a browser's default action for a key:

| Key | Default action |
|-----|----------------|
| Tab | Focus the next focusable widget (wraps) |
| Enter | Activate the focused widget (click a button, toggle a checkbox) |
| Escape | Clear focus |
| Left / Right | Move a focused slider by 5% of its range |

These run even when a widget already handled the event. To stop one, call `ev.prevent_default()` from a custom widget's event hook.

### Custom widgets {#custom-widgets}

```c
typedef fn void(&GuiWindow, &Widget) WidgetDrawFn;               // draw inside widget.x/y/w/h
typedef fn void(&Widget, int, int, int, int) WidgetLayoutFn;      // set widget.x/y/w/h, lay out children
typedef fn int(&Widget, &GuiEvent) WidgetCustomEventFn;           // 1 = handled, stop dispatch

struct Widget {
    // ...
    generic<T>
    static &Widget custom(WidgetDrawFn draw, WidgetLayoutFn layout,
                          WidgetCustomEventFn eventFn, ?&T userData);
};
```

`draw` and `layout` are required. `eventFn` may be null: the widget then gets the same hover, press and click handling as a button. Set it only when you need raw events, such as drag deltas on a canvas:

```c
static int canvas_event(&Widget w, &GuiEvent ev) {
    if (ev.kind == GUI_EVENT_KEY_DOWN && ev.keycode == GUI_KEY_TAB) {
        ev.prevent_default();   // keep focus here: no default Tab action
        return 1;
    }
    return 0;
}

struct Canvas c;
&Widget canvas;
unsafe { canvas = std::Widget::custom(canvas_draw, canvas_layout, canvas_event, (?&Canvas)&c); }
```

### Example — counter {#example-counter}

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
            c.label.set_text(c.clicks == 1 ? "Clicked once" : "Clicked again");
        }
        case null: { }
    }
}

int main() {
    struct GuiWindow win = std::GuiWindow::create("Counter", 320, 160);
    if (win.platform.is_null()) { return 1; }

    &Widget root   = std::Widget::vstack().set_padding(12).set_spacing(8);
    &Widget label  = std::Widget::label("Not clicked");
    &Widget button = std::Widget::button("Click me").set_bg(60, 120, 200, 255);

    struct Counter counter;
    counter.clicks = 0;
    counter.label = label;
    unsafe { button.on_click(on_click, (?&Counter)&counter); }

    root.add_child(label);
    root.add_child(button);

    struct GuiEvent ev;
    while (!win.shouldClose) {
        root.layout(0, 0, win.width, win.height);
        while (win.poll_event(&ev)) { root.dispatch_event(&ev); }
        root.render(&win);
        win.present();
    }

    root.free();
    win.destroy();
    return 0;
}
```

---

## Bare Metal — `gui_fb.sc` {#bare-metal-gui-fb-sc}

There is no windowing system or input stack on bare metal. Tell the backend where the framebuffer is before creating the window, and feed it input from your own drivers.

```c
#include <std/gui/gui_fb.h>   // these declarations, on top of gui.h

struct GuiFbConfig {
    &volatile unsigned char base;  // linear framebuffer memory
    unsigned long pitch;  // bytes per scanline (may include padding)
    int width;
    int height;
    int bpp;              // 32 (xRGB/xBGR) or 16 (RGB565)
    int redShift; int greenShift; int blueShift; // channel bit positions, 32 bpp only
};

void gui_fb_configure(struct GuiFbConfig cfg);                 // call once, before GuiWindow::create()
void gui_fb_inject_event(&GuiWindow win, struct GuiEvent ev);  // call from your keyboard/mouse/touch ISR
```

The values usually come from the bootloader, for example a Multiboot2 framebuffer tag (`framebuffer_addr`, `framebuffer_pitch`, `framebuffer_bpp`) or a VESA VBE mode. Without `gui_fb_configure`, `GuiWindow::create` fails (`platform` is null). Injected events go into a 32-entry queue (`GUI_FB_QUEUE_CAP`) that `win.poll_event` drains; events are dropped when it is full. `win.present()` converts the RGBA buffer to the framebuffer's pixel format as it copies.

See [Bare-Metal](/reference/baremetal) for targets and linking.
