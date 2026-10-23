# GUI Platforms & GPU (`std/gui/`)

How [`std/gui`](/stdlib/gui) reaches the screen on each platform: the Wayland backend next to X11 on Linux, the WebAssembly backend that turns windows into `<canvas>` elements of a web page, and GPU presentation through OpenGL ES (EGL), Metal, Direct3D 11 or WebGL. None of this changes how a program draws: rendering stays on the CPU, so a frame is the same bytes everywhere — the browser frame of `examples/gui_gallery` matches its native headless snapshot pixel for pixel, except for the bars that animate with the clock.

| Platform | Backend | Presentation | GPU presenter |
|----------|---------|--------------|---------------|
| Linux, BSD (X11) | `gui_x11.sc` (Xlib) | `XPutImage` | `"gl"`: OpenGL ES 2 via EGL |
| Linux (Wayland) | `gui_wayland.sc` | `wl_shm`, double-buffered, frame callbacks | `"gl"`: OpenGL ES 2 via EGL (`wl_egl_window`) |
| Windows | `gui_win32.sc` | `StretchDIBits` | `"d3d11"`: Direct3D 11, flip-model swap chain |
| macOS | `gui_cocoa.sc` | a `CALayer`'s contents | `"metal"`: Metal, `CAMetalLayer` |
| Web (wasm32) | `gui_web_wasm.sc` + `gui_web.js` | `putImageData` | `"webgl"`: WebGL2, else WebGL1 |
| Bare metal | `gui_fb.sc` | framebuffer copy | — |
| Any (tests) | `gui_headless.sc` | counted | — |

## Choosing X11 or Wayland {#choosing-x11-or-wayland}

A Linux build contains both backends; the first window decides:

| `SAFEC_GUI_BACKEND` | Backend |
|---------------------|---------|
| unset (or anything else) | Wayland if `$WAYLAND_DISPLAY` is set, `libwayland-client.so.0` loads and the compositor answers; X11 otherwise |
| `wayland` | Wayland (window creation fails cleanly if there is none) |
| `x11` | X11 (through Xwayland on a Wayland desktop) |
| `headless` | the in-memory backend — any platform, not just Linux |

```c
#include <std/gui/gui_wayland.h>

if (!gui_use_wayland()) { /* no compositor: X11 it is */ }   // or force it in code, before the first window
```

`gui_wayland_available()` connects (once) and reports whether Wayland can be used; `gui_wayland_backend()` is the backend table (null on non-Linux targets).

Nothing is linked: `libwayland-client`, `libxkbcommon` and `libwayland-cursor` are `dlopen()`ed, so one binary runs on an X11-only system too. `safeguard bundle` lists their packages under the `.deb`'s **Recommends**, never Depends.

## Wayland {#wayland}

| Feature | How |
|---------|-----|
| Windows | `xdg_toplevel`s, any number; title, app id (`$SAFEC_GUI_APP_ID`, else the program's name), min/max size, the close request. Compositor-driven sizes (tiling, maximizing) arrive as `GUI_EVENT_RESIZE`. |
| Presenting | `wl_shm` buffers, two (a third or fourth only while the compositor holds both), damage-only copies, throttled by frame callbacks: a present while the last frame is still on its way joins the next one. |
| HiDPI | `wp_fractional_scale_v1` + `wp_viewporter` (1.25, 1.5, … with a buffer exactly the window's size in device pixels), else `wl_surface.preferred_buffer_scale`, else the scale of the outputs the window is on; `GUI_EVENT_SCALE` when it changes. `SAFEC_GUI_SCALE` overrides it. |
| Decorations | Server-side through `zxdg_decoration_manager_v1` where the compositor offers it (sway, KDE, wlroots compositors). Elsewhere (GNOME's Mutter, older Weston) windows are **undecorated**: std/gui draws no client-side title bar, so give such windows their own close/move controls. `decorated = 0` asks for none. |
| Pointer | enter/leave, motion, buttons, the wheel: high-resolution `value120`, discrete steps, or smooth (touchpad) scrolling. |
| Keyboard | the compositor's xkb keymap through libxkbcommon: layout-independent `GUI_KEY_*`, Shift/Ctrl/Alt/Super, dead keys and compose sequences, client-side key repeat at the compositor's rate and delay. Without libxkbcommon, keys map as a US layout. Seats that gain or lose devices are followed. |
| Cursors | `wp_cursor_shape_v1`, else the XCursor theme (`$XCURSOR_THEME`, `$XCURSOR_SIZE`) through libwayland-cursor. |
| Clipboard | `wl_data_device`, UTF-8 text both ways (reads wait up to a second). Like every Wayland client, a program can only *take* the clipboard right after an input event of its own — normally the Ctrl+C itself. |

Wayland gives a client no say over window positions, raising or focusing its own windows, or staying on top: `set_position()` is ignored (and `pos_x()`/`pos_y()` stay `GUI_POS_DEFAULT` unless set), `focus()` does nothing, and `topmost` is ignored.

**Tested** under sway and Weston, both headless (`tests/stdlib/wayland/run.sh`): frames read back with `grim` (full and partial presents, several in flight), input from a virtual pointer and keyboard the test creates on its own connection (motion, buttons, wheel, keys with Shift and Ctrl, key repeat), the clipboard against `wl-copy`/`wl-paste`, compositor and client resizes, hide/show, close, two windows under one `GuiApp`, fractional scale 1.5, and the EGL presenter.

## The web {#the-web}

On `wasm32`/`wasm64`, std/gui's backend is the browser page. The host side is **`std/gui/gui_web.js`**, an ES module shipped with std:

```js
import { loadSafeCGui, fetchFiles } from "./gui_web.js";

const files = await fetchFiles({ "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf": "fonts/DejaVuSans.ttf" });
const app = await loadSafeCGui(fetch("app.wasm"), { files, entry: "start" });
```

A browser can't block, so a web program doesn't call `GuiApp::run()`. Its entry point — any `export_name` function; the glue calls it once — opens the windows and hands the app to `gui_web_run()`, which returns at once; the page then runs one `app.pump(0)` per `requestAnimationFrame` until it returns 0. Everything else — widgets, the canvas, timers, animation frames — is the desktop code unchanged:

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
| Windows | `windows: "canvas"` (default): each window is a `<canvas>` — the next free `canvas[data-safec-gui]` of the page, one you pass (`canvas` option), or a new one. Its CSS size is the logical size, its backing store that × `devicePixelRatio`. With `fit` (or a `data-safec-fit` attribute) the canvas keeps the size the page's CSS gives it and follows it with a `ResizeObserver` (`GUI_EVENT_RESIZE`). `windows: "frames"`: each window is a positioned `<div class="safec-gui-window">` in a `container`, with a title bar (drag it: `GUI_EVENT_MOVE`, `set_position()` works; × : `GUI_EVENT_CLOSE`), a resize grip that honors the size limits, raised on a click. |
| Keyboard and text | Keyboard focus is real DOM focus, on a hidden `<textarea>` per window that a click on the canvas focuses: keys become `GUI_KEY_*` (by `KeyboardEvent.code`) with the typed character; text that comes without a key — **IME composition**, virtual (mobile) keyboards, context-menu pastes — arrives as `GUI_EVENT_CHAR`. |
| Pointer | pointer events (captured while a button is down), the wheel (pixel, line and page deltas to lines). |
| Clipboard | `gui_clipboard_set_text()` writes `navigator.clipboard` and the copy event a Ctrl/Cmd+C fires; `gui_clipboard_get_text()` returns the last paste event's text (a Ctrl/Cmd+V keydown is held back until its paste event arrives) or the last copy. |
| Scale | `devicePixelRatio`, followed when it changes (zoom, another display): `GUI_EVENT_SCALE`. |
| Cursors | CSS `cursor`. |
| Accessibility | canvases are `role="img"` with the window title as `aria-label`; `gui_web_announce(text)` speaks through an `aria-live` region. |
| Files | `fopen`/`fread` read the `files` option (`fetchFiles()` fills it from URLs): fonts at the paths std/gui looks in, data files. Without fonts, the TrueType stack's embedded fallback face is used. |
| DOM | Load the module through [`std/wasm`](/stdlib/wasm)'s `loadSafeC` (pass it as the `loadSafeC` option) and `gui_web_window_element(win)` returns the window's element as a `dom.h` handle, `gui_web_mount(win, parent)` moves it into a DOM element — a page mixes ordinary DOM UI and std/gui windows. |

`gui_web.js` options: `canvas`, `fit`, `title` (the first window's title becomes `document.title`), `windows`, `container`, `present` (`"webgl"` / `"2d"`), `preserveDrawingBuffer`, `files`, `entry`, `loadSafeC`, `domGlue`, `imports`, `globals`. The returned `gui` object has `start()`, `stop()`, `frame()`, `canvas(id)`, `element(id)`, `input(id)` and `presenter(id)`. The glue also supplies the libc a freestanding module may still import — a small `printf`/`snprintf`, `getenv` (nothing set), the `files` reads, `Math`-backed libm.

### A page from safeguard {#safeguard-bundle-wasm32}

`safeguard bundle --target wasm32` builds the program and writes **`build/<name>.web/`**: the module, the glue of every std module it uses (`gui_web.js`, `safec_dom.js`, `audio_web.js`), the `[bundle]` resources, and an `index.html` that loads them. Serve the folder over HTTP (`python3 -m http.server -d build/<name>.web`): browsers don't instantiate WebAssembly from `file://`. No `[bundle]` table is needed; with one, these keys apply:

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

`examples/gui_web` is a complete web program (`safeguard bundle --target wasm32` in it); `examples/gui_gallery` runs in a page too (its `start` export).

**Tested** under Node against a fake DOM (`tests/stdlib/wasm_gui/run.sh`: three windows, widgets clicked and typed into, IME and virtual-keyboard text, the clipboard, resizes, scale changes, frames dragged and resized, WebGL2/WebGL1 with partial uploads and context loss, the DOM interop) and in headless Chromium through Playwright (`browser.sh`: the gallery, built for wasm32 with the native build's fonts, matches its native snapshot through both putImageData and WebGL (SwiftShader); real mouse, keyboard and `insertText` input; frames dragged; the generated `safeguard bundle` page).

## GPU presentation {#gpu-presentation}

```c
#include <std/gui/gui_gpu.h>

struct GuiWindowOptions o = GuiWindowOptions::new("Video", 1280, 720);
o.present = GUI_PRESENT_GPU;          // GUI_PRESENT_DEFAULT / GPU / CPU
struct GuiWindow win = GuiWindow::create_with(&o);
const char* how = gui_window_presenter_name(&win);   // "gl", "metal", "d3d11", "webgl" or "cpu"
```

| Call | Does |
|------|------|
| `GuiWindowOptions.present` | `GUI_PRESENT_DEFAULT`: the software path unless `SAFEC_GUI_PRESENT=gpu` (on the web: the glue's `present: "webgl"`); `GUI_PRESENT_GPU`: the GPU, falling back to software; `GUI_PRESENT_CPU`: software, whatever the environment says. |
| `gui_window_use_gpu(&win)` / `gui_window_use_cpu(&win)` | Switch a live window. `use_gpu` returns 0 if it couldn't (and the window keeps the software path). |
| `gui_window_uses_gpu(&win)`, `gui_window_presenter_name(&win)` | Which path a window is on. |
| `gui_gpu_presenter()` | The platform's `GuiPresenter`, or null (bare metal, headless). |

A presenter uploads the frame into a texture — only the damaged rectangle (or, without a row-length setting, the damaged rows) — and draws it as one quad, or blits it into the swap chain:

| Presenter | Details |
|-----------|---------|
| `"gl"` (X11, Wayland) | `libEGL`, `libGLESv2` and `libwayland-egl` `dlopen()`ed; an OpenGL ES 2 context per window, the EGL config matching the X11 visual; vsync on X11 (swap interval 1; on Wayland the compositor paces frames and nothing tears); the damaged rectangle passed on with `EGL_KHR/EXT_swap_buffers_with_damage`. |
| `"metal"` (macOS) | `Metal.framework` loaded at run time; a `CAMetalLayer` on the content view, the frame's damaged rows copied into a shared `MTLBuffer` and drawn by a full-drawable triangle that reads its pixel from it, presented with the layer's vsync. |
| `"d3d11"` (Windows) | `d3d11.dll` loaded at run time (a hardware device, else WARP); a flip-model swap chain (`FLIP_DISCARD`, else `FLIP_SEQUENTIAL`; `R8G8B8A8`, `SCALING_NONE`), `UpdateSubresource` of the damaged box, `CopyResource`, `Present1` with the dirty rect and vsync. |
| `"webgl"` (web) | WebGL2, else WebGL1; the premultiplied frame uploaded straight from linear memory (`UNPACK_ROW_LENGTH` for the damaged rectangle on WebGL2) — no straight-alpha conversion, unlike `putImageData`. |

Any failure — no driver or library, a context or swap chain that can't be made, a device or WebGL context lost later — sends the window back to the software path for good, without the program noticing beyond `gui_window_uses_gpu()` turning 0. Worth it for large windows redrawn often (animation, video, games); for a mostly static UI the software path is just as good.

**Tested:** `"gl"` for real on Xvfb with Mesa llvmpipe (`tests/stdlib/x11/run.sh`: frames read back, partial updates, resizes, two GL windows, switching to software and back) and on sway (`wayland/run.sh`, scale 1 and 1.5); `"d3d11"` under Wine, whose wined3d translates to OpenGL (`tests/stdlib/win32/run.sh`: frames, a partial update, a resize, back to GDI); `"webgl"` in headless Chromium with SwiftShader and under Node; `"metal"` compiled for arm64 and x86_64 macOS only.

### Writing a presenter {#writing-a-presenter}

A `GuiPresenter` (`gui_backend.h`) is three functions: `attach(st, handles)` gets the window's native handles (`GuiNativeHandle`: display, window, view, visual) and keeps its state in `st.presenterData`; `present(st, pixels, bufW, bufH, x, y, w, h)` gets exactly what the backend's `window_present` would — premultiplied RGBA, the damaged rectangle — and must cope with the size changing between frames; `detach(st)` frees it. A presenter that fails for good while presenting frees its data, sets `st.presenter = null` and hands the frame to the backend's `window_present`. Attach one with `gui_window_set_presenter()`.
