# GUI (`std/gui/`)

A GUI toolkit written in SafeC from the ground up: windows and input on X11, Wayland, Win32, Cocoa, web pages (WebAssembly `<canvas>`) and bare-metal framebuffers, an anti-aliased vector canvas with TrueType text, and a retained widget toolkit with layouts, themes and 30+ widgets. Everything is rendered on the CPU into one RGBA buffer per window, so a UI looks the same, byte for byte, on every platform; getting that buffer onto the screen can go through the GPU ([Platforms & GPU](/stdlib/gui-platforms)).

This page covers windows, events and the application loop. The other two parts have their own pages:

- [GUI Graphics](/stdlib/gui-graphics) — `GuiCanvas` (paths, gradients, strokes, clipping, blend modes, images) and TrueType text (`GuiTypeface`, `GuiTextLayout`).
- [GUI Widgets](/stdlib/gui-widgets) — the widget tree, layout, themes, focus, menus, dialogs and every widget.
- [GUI Platforms & GPU](/stdlib/gui-platforms) — Wayland, the web (`<canvas>`, WebGL, DOM interop, `safeguard bundle --target wasm32`) and GPU presentation (OpenGL ES/EGL, Metal, Direct3D 11).

```c
#include <std/gui/gui.h>          // windows, events, colors, clipboard, clock
#include <std/gui/gui_app.h>      // GuiApp: several windows, timers, animation
#include <std/gui/gui_canvas.h>   // the vector canvas (and gui_path.h, gui_text.h)
#include <std/gui/gui_widget.h>   // widgets
#include <std/gui/gui_png.h>      // GuiImage: PNG load/save, scaling
#include <std/gui/gui_headless.h> // the in-memory backend, for tests
```

`std/prelude.h` does not include the GUI headers — include them directly.

### A first program {#a-first-program}

```c
#include <std/gui/gui_app.h>

static void on_click(&Widget w, ?&Widget label) {
    match (label) { case some(l): { l.set_text("Clicked!"); } case null: { } }
}

int main() {
    struct GuiApp app = GuiApp::new();
    ?&GuiWindow ow = app.open_window("Hello", 360, 200);
    match (ow) {
        case null: { app.free(); return 1; }     // no display
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

`examples/gui_hello` is a small complete program, and `examples/gui_gallery` shows every widget and most of the canvas in two windows (`safeguard run -- --snapshot DIR` renders them to PNG files without a display).

---

## Windows — `gui.h` {#windows-gui-h}

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
    void destroy() consume;

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

A window always has a buffer `width × height` pixels large. With `hiDpi` (the default) on a high-density display, the buffer is larger than the window's logical size by `scale` (2.0 on a Retina display, 1.25–2.0 on Windows and X11 depending on the system setting; `SAFEC_GUI_SCALE` overrides it). Mouse coordinates arrive in buffer pixels, so hit-testing needs no conversion; the widget toolkit and `GuiCanvas::scale()` take care of drawing at the right size.

`create` fails cleanly — `platform` is null and the size is 0 — when there is no display (a CI runner without X, say). Check before use, or use `GuiApp::open_window`, which returns `null`.

The buffer is reallocated when the window is resized: `poll_event` updates `pixels`, `width` and `height` before returning the `GUI_EVENT_RESIZE`.

### Events {#events}

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

| Event | When |
|-------|------|
| `GUI_EVENT_CLOSE` | The close button (or Alt+F4, Cmd+W from the system). The window stays open until you destroy it. |
| `GUI_EVENT_RESIZE` | New size; the buffer is already reallocated. |
| `GUI_EVENT_MOVE` | The window moved (`width`/`height` hold the new x/y). |
| `GUI_EVENT_FOCUS_IN` / `_OUT` | The window gained / lost the keyboard focus. |
| `GUI_EVENT_MOUSE_DOWN` / `_UP` / `_MOVE` | Buttons and motion; the window captures the pointer while a button is held. |
| `GUI_EVENT_MOUSE_ENTER` / `_LEAVE` | The pointer entered / left the window. |
| `GUI_EVENT_SCROLL` | Wheel or touchpad. |
| `GUI_EVENT_KEY_DOWN` / `_UP` | Keys, with modifiers; `KEY_DOWN` carries the typed character. Repeats arrive as more `KEY_DOWN`s. |
| `GUI_EVENT_CHAR` | Text with no key behind it (an input method, compose sequences). |
| `GUI_EVENT_SCALE` | Moved to a display with another scale factor; the buffer is reallocated. |
| `GUI_EVENT_EXPOSE` | The system lost the window's contents: present again. |

Each backend translates native key codes, so programs only see `GUI_KEY_*`: arrows, Enter, Escape, Tab, Backspace, Space, Delete, Home/End, PageUp/PageDown, Insert, F1–F12, Shift/Control/Alt/Meta/Menu, and letters and digits as their uppercase ASCII codes (`'A'`, `'7'`), whatever the keyboard layout produces as text. Text input goes through the platform's input method where it has one (XIM on X11, `WM_CHAR` with surrogate pairs on Windows, `NSEvent.characters` on macOS), so `codepoint` is a full Unicode scalar.

### Clipboard, time, cursors {#clipboard-time-cursors}

```c
int    gui_clipboard_set_text(const &char utf8);    // 1 = ok
int    gui_clipboard_get_text(&String out);         // 1 = got text
double gui_time_ms();                               // monotonic milliseconds
int    gui_primary_mod();                           // GUI_MOD_META on macOS, else GUI_MOD_CTRL
double gui_display_scale();                         // the scale a hiDpi window would get
```

The clipboard is the system's: `CLIPBOARD` on X11 (served to other clients while the program runs; large `INCR` transfers aren't supported), `CF_UNICODETEXT` on Windows, `NSPasteboard` on macOS.

Cursors: `GUI_CURSOR_ARROW`, `_IBEAM`, `_HAND`, `_CROSSHAIR`, `_RESIZE_EW`, `_RESIZE_NS`, `_RESIZE_NWSE`, `_RESIZE_NESW`, `_MOVE`, `_WAIT`, `_NOT_ALLOWED`, `_HIDDEN`.

### Colors {#colors}

```c
struct GuiColor {
    unsigned char r, g, b, a;           // straight (not premultiplied) alpha

    static struct GuiColor rgba(r, g, b, a);
    static struct GuiColor rgb(r, g, b);
    static struct GuiColor hex(unsigned int rrggbb);
    static struct GuiColor hexa(unsigned int rrggbbaa);
    static struct GuiColor rgbaf(float r, float g, float b, float a);   // 0..1
    static struct GuiColor hsv(float h, float s, float v, float a);     // h in degrees
    struct GuiColor with_alpha(float f) const;   // alpha multiplied by f (0..1)
    struct GuiColor mix(struct GuiColor other, float t) const;
    struct GuiColor shade(float amount) const;   // > 0 lighter, < 0 darker
    unsigned int packed() const;                 // 0xRRGGBBAA
    void to_hsv(&float h, &float s, &float v) const;
};
```

---

## The application — `gui_app.h` {#the-application-gui-app-h}

`GuiApp` runs any number of windows from one event loop: it pumps the platform's events, routes each to the window it belongs to (its widget tree and/or your callback), fires timers and per-frame animation callbacks, repaints the windows that changed and presents them, and closes a window when its close button is clicked.

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
    void free() consume;        // closes every window
};
```

- **Rendering.** A window with a widget root is laid out and painted by its `GuiUi`. A window with an `on_draw` callback gets a [`GuiCanvas`](/stdlib/gui-graphics) over its buffer — after the widgets, if it has both. A window with neither shows whatever you wrote into `win.pixels`. Windows are repainted only when something changed: an input event a widget or your `on_event` handled (return 1), a resize, a timer, an animation frame, or `app.redraw(win)`.
- **Closing.** `GUI_EVENT_CLOSE` closes the window after your `on_event` callback saw it — unless the callback called `ev.prevent_default()` (to ask "save changes?" first, say). `run()` returns when the last window is gone or `quit()` was called.
- **Animation.** `on_frame` callbacks run once per frame (`set_frame_interval`, 60 Hz by default) with the time in milliseconds, for as long as they return 1; the window is repainted after each.
- **Timers** run on the loop thread, never concurrently with event handling, as close to their due time as the loop allows. Ids are never 0.
- **Waiting.** `run()` sleeps in the platform's event wait (`poll` on the X connection, `MsgWaitForMultipleObjects`, `nextEventMatchingMask`) until the next event, timer or frame is due, so an idle app uses no CPU.

### Driving the loop yourself {#driving-the-loop-yourself}

Call `app.pump(0)` once per iteration of your own loop (a game, an embedding host, a WebAssembly page driven by `requestAnimationFrame`): it does one iteration without waiting and returns 0 once the app should stop. `app.pump(ms)` waits up to `ms` milliseconds for the next event, timer or frame.

### Example — two windows and a timer {#example-two-windows-and-a-timer}

```c
#include <std/gui/gui_app.h>

struct State { int seconds; &Widget label; };

static void tick(&GuiApp app, unsigned int id, ?&State os) {
    match (os) {
        case some(s): {
            s.seconds = s.seconds + 1;
            struct String t = std::String::new();
            t.push_int((long long)s.seconds);
            t.push(" s");
            s.label.set_text(t.as_ptr());
            t.free();
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
    if (oa.is_null() || ob.is_null()) { app.free(); return 1; }
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

## Backends {#backends}

Everything platform-specific sits behind one table of function pointers, `struct GuiBackend` (`gui_backend.h`). The portable layer — windows, the clipboard, the clock, `GuiApp` — calls only through it, and the canvas, text and widget code only ever see a window's pixel buffer.

| Backend | Target | Tested |
|---------|--------|--------|
| `gui_x11.sc` | Linux, BSD — Xlib, `XPutImage`, XIM text input, `CLIPBOARD`, EWMH | Against Xvfb (`tests/stdlib/x11/run.sh`): pixels read back with `XGetImage`; keys, text, mouse, scroll, resize, close; two windows under one `GuiApp`; UTF-8 titles; the clipboard both ways with a second X client |
| `gui_wayland.sc` | Linux — Wayland: xdg-shell, `wl_shm`, xkbcommon, fractional scale (all `dlopen()`ed); chosen at run time when there is a compositor | Under sway and Weston, headless (`tests/stdlib/wayland/run.sh`): frames read back with grim, virtual pointer and keyboard input, the clipboard with wl-copy/wl-paste, scale 1 and 1.5 |
| `gui_web_wasm.sc` | WebAssembly — `<canvas>` elements of a page, with `std/gui/gui_web.js` | Under Node with a fake DOM (`tests/stdlib/wasm_gui/run.sh`) and in headless Chromium (`browser.sh`): the gallery's frame matches the native one |
| `gui_win32.sc` | Windows — wide-character Win32, `StretchDIBits`, per-monitor DPI | Under Wine (`tests/stdlib/win32/run.sh`): pixels read back with `GetPixel`; posted input; two windows; the clipboard |
| `gui_cocoa.sc` | macOS — AppKit through the Objective-C runtime, a layer-backed `NSView` | Compiled for arm64 and x86_64 macOS |
| `gui_fb.sc` | Bare metal — a linear framebuffer; input injected from your drivers | Compiled for every bare-metal target |
| `gui_headless.sc` | Any target — in memory, with a virtual clock | `tests/stdlib/tests/test_gui_widgets.sc`, `test_gui_app.sc` |

`safeguard` links the backend for the target by filename suffix (`_x11` on Linux/BSD, `_win32` on Windows, `_cocoa` on Apple, `_wasm` on WebAssembly, `_fb` on bare metal) and adds its system libraries to the link — nothing to list in `Package.toml`. `SAFEC_GUI_BACKEND=headless` runs any GUI program on the [headless backend](#the-headless-backend); on Linux, `SAFEC_GUI_BACKEND=x11|wayland` picks between the two ([details](/stdlib/gui-platforms#choosing-x11-or-wayland)).

| Backend | Libraries |
|---------|-----------|
| Cocoa | `-framework Cocoa -lobjc` |
| Win32 | `-luser32 -lgdi32` |
| X11 | `-lX11` (and `libx11-6` in a `.deb`'s Depends) |
| Wayland, EGL | none: `dlopen()`ed (`-ldl`); `libwayland-client0`, `libxkbcommon0`, `libwayland-cursor0`, `libegl1`, `libgles2` in a `.deb`'s Recommends |
| Metal, Direct3D 11 | none: loaded at run time |
| WebAssembly | none: imports `std/gui/gui_web.js` provides |

Building without `safeguard`, pass them to the link yourself.

### The headless backend {#the-headless-backend}

`gui_use_headless()` (from `gui_headless.h`, before the first window) swaps the platform backend for one that keeps windows in memory, with a virtual clock that only moves when the loop waits or you advance it. It drives tests: inject input and read the pixels back.

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

### Writing a backend {#writing-a-backend}

`gui_backend.h` documents the contract in full. In short, a backend is one `.sc` file that fills in a `GuiBackend`:

| Entry | Does |
|-------|------|
| `init()` | Connect to the display server; 0 if there is none. |
| `window_create(st, opts)` | Create the native window; set `st.key` (native id), `st.scale`, `st.pixelW/H`, `st.x/y`. |
| `window_destroy`, `window_set_title/size/position/visible`, `window_focus`, `window_set_cursor`, `window_update_limits` | Apply a change. |
| `window_present(st, px, bufW, bufH, x, y, w, h)` | Show a rectangle of the premultiplied RGBA buffer, converting to the native pixel order. |
| `pump(timeoutMs)` | Read native events, translate them, `gui_backend_post()` them; wait up to `timeoutMs` if none was pending. |
| `wake()` | Make a blocked `pump()` return (any thread). |
| `now_ns`, `clipboard_set/get`, `display_scale`, `native_handle` | Clock, clipboard, scale, handles for GPU presenters. |

A new **platform default** is a suffix rule in safeguard's `stdVariantMatches` plus a `gui_<...>_<suffix>.sc` defining `gui_platform_backend()` (as `gui_web_wasm.sc` is for `_wasm`). An **alternative** next to an existing default defines its own table and setup call, as `gui_wayland.sc` does beside X11; a library it needs is best `dlopen()`ed and named with `// safeguard: deb-recommends`.

**GPU presentation** is a `GuiPresenter` attached per window — see [Platforms & GPU](/stdlib/gui-platforms#gpu-presentation) for the ones that ship (OpenGL ES/EGL, Metal, Direct3D 11, WebGL) and how to write one.

---

## Bare metal — `gui_fb.h` {#bare-metal-gui-fb-h}

There is no windowing system or input stack on bare metal. Tell the backend where the framebuffer is before creating the window, and feed it input from your own drivers.

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

The values usually come from the bootloader, for example a Multiboot2 framebuffer tag or a VESA VBE mode. Without `gui_fb_configure`, window creation fails. Injected events go into a 32-entry ring (`GUI_FB_QUEUE_CAP`) that `poll_event` drains; events are dropped when it is full. `present()` converts the RGBA buffer to the framebuffer's pixel format as it copies. The canvas, text (with the built-in fallback font) and widgets all work unchanged.

See [Bare-Metal](/reference/baremetal) for targets and linking.

---

## Simple drawing, bitmap fonts, PNG and SVG {#simple-drawing}

The original integer helpers are still there for quick pixel work (they write the buffer directly, without blending or anti-aliasing — use the [canvas](/stdlib/gui-graphics) for anything else):

```c
win.fill_rect(x, y, w, h, color);
win.draw_rect_border(x, y, w, h, color, thickness);
win.draw_line(x0, y0, x1, y1, color);
int n = win.draw_text(x, y, "text", color, scale);   // built-in 8x8 font, ASCII
int ok = win.draw_svg(ox, oy, svgText);              // an SVG subset, see below
int gui_text_width(const &char text, int scale);     // gui_draw.h
int gui_text_height(int scale);
```

**Bitmap fonts — `gui_font.h`.** A `GuiFont` is a bit-packed glyph bitmap of any glyph size (`GuiFont::new`, `GuiFont::default()` for the built-in 8×8 font, `get_bit`/`set_bit`, `save`/`load` in the `SCXF` format, `draw_text`, `text_width`). A widget given one with `set_font()` draws its text with it. For real text, use [TrueType fonts](/stdlib/gui-graphics#text).

**Images — `gui_png.h`.**

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
    struct GuiImage scaled(int width, int height) const;  // area average shrinking, bilinear enlarging
    unsigned int get_pixel(int x, int y) const; void set_pixel(int x, int y, unsigned int rgba);
    void draw(&GuiWindow win, int x, int y) const;              // a straight copy
    void free() consume;
};
```

The PNG decoder (with its own DEFLATE inflater) reads 8-bit grayscale, RGB and RGBA, non-interlaced; palette images, 16-bit depth and Adam7 fail cleanly with 0. Draw images with blending, scaling and filtering through the canvas (`draw_image*`, image patterns).

**SVG — `draw_svg`.** Parses a `<svg>` document and draws `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>` and `<path>` (`M`/`L`/`H`/`V`/`C`/`Z`) with `fill`/`stroke` colors; no `viewBox`, transforms, gradients or text. For vector graphics with anti-aliasing, `GuiPath::add_svg()` parses full SVG path data (every command, arcs included) into a canvas path.
