# GUI Graphics (`std/gui/gui_canvas.h`)

An anti-aliased 2D vector canvas with the drawing model of the HTML canvas, Skia and Cairo — paths, fills, strokes, gradients, image patterns, transforms, clipping and blend modes — and TrueType text, all rendered on the CPU. It draws into a [window](/stdlib/gui#windows-gui-h)'s buffer, a `GuiImage`, or any premultiplied RGBA8888 memory, and needs nothing from the OS: it works the same on bare metal.

```c
#include <std/gui/gui_canvas.h>   // GuiCanvas, GuiPaint (includes gui_path.h, gui_text.h, gui_png.h)
```

```c
struct GuiCanvas c = GuiCanvas::for_window(&win);
c.clear(GuiColor::hex(0xFFFFFFU));

c.set_fill_color(GuiColor::hex(0x3366CCU));
c.fill_rounded_rect(20.0f, 20.0f, 160.0f, 48.0f, 10.0f);

c.set_stroke_color(GuiColor::hex(0x222222U));
c.set_line_width(3.0f);
c.set_line_cap(GUI_CAP_ROUND);
c.begin_path();
c.move_to(20.0f, 100.0f);
c.cubic_to(80.0f, 40.0f, 120.0f, 160.0f, 180.0f, 100.0f);
c.stroke();

c.set_font(GuiTypeface::fallback(), 18.0f);
c.set_fill_color(GuiColor::hex(0x000000U));
c.fill_text("Hello, canvas", 20.0f, 150.0f);

c.free();          // the canvas' scratch buffers, not the pixels
win.present();
```

Inside a [`GuiApp`](/stdlib/gui#the-application-gui-app-h), `app.on_draw(win, cb, data)` hands your callback a canvas over the window that is already set up; in a widget tree, `Widget::canvas(cb, data)` gives you one clipped and translated to the widget.

## How it renders {#how-it-renders}

- **Anti-aliasing** is exact horizontally and 16× supersampled vertically: every pixel row is cut by 16 sample lines and each edge crossing contributes its exact horizontal coverage, accumulated in a difference buffer and integrated once per row. Coverage is the true area inside the shape to within 1/16 pixel, for overlapping and self-intersecting paths, with either fill rule.
- **Curves** are flattened adaptively to within 0.05 px.
- **Compositing** uses premultiplied alpha, four channels at a time on SIMD vectors (`vec<float, 4>`); opaque solid spans are plain 32-bit stores.
- **Deterministic:** the same calls produce the same bytes on every platform and target, so rendering can be golden-tested (`tests/stdlib/fixtures/gui/canvas_scene.png`).

Rough cost on one desktop core: clearing 1280×800 takes 0.6 ms, 200 filled rounded rectangles 3 ms, 200 stroked ones 7 ms, 200 short text labels 1 ms.

## Targets {#targets}

```c
static struct GuiCanvas for_window(&GuiWindow win);
static struct GuiCanvas for_image(&GuiImage img);      // premultiplies it first if needed
static struct GuiCanvas for_pixels(&unsigned char px, int width, int height, int stride);
void retarget_window(&GuiWindow win);   // after a resize reallocated the buffer
void reset();                           // initial state: identity, black fill, no clip
void free();
struct GuiColor get_pixel(int x, int y) const;   // straight alpha
```

A canvas holds a pointer to the pixels, not a copy. Coordinates are in buffer pixels; on a HiDPI window, `c.scale((float)win.scale, (float)win.scale)` first to draw in logical units.

## State, transforms {#state-transforms}

```c
void save();                 // pushes transform, paints, line style, alpha, blend, clip, font
void restore();
void translate(float tx, float ty);
void scale(float sx, float sy);
void rotate(float radians);
void transform(struct GuiMatrix m);      // m applies first
void set_transform(struct GuiMatrix m);
void reset_transform();
struct GuiMatrix get_transform() const;
void set_global_alpha(float a);          // 0..1, multiplies everything drawn
void set_blend_mode(int mode);           // GUI_BLEND_*
void set_anti_alias(int on);             // 0: hard pixel edges
```

`GuiMatrix` (`gui_path.h`) is a 2×3 affine matrix: `identity()`, `translation()`, `scaling()`, `rotation()`, `new(a, b, c, d, e, f)`, `mul()`, `translate()`, `scale()`, `rotate()`, `invert()`, `apply_x()`/`apply_y()`, `apply_rect()`.

## Paths {#paths}

The canvas has a current path, like the HTML canvas:

```c
void begin_path();
void move_to(float x, float y);
void line_to(float x, float y);
void quad_to(float cx, float cy, float x, float y);
void cubic_to(float c1x, float c1y, float c2x, float c2y, float x, float y);
void arc(float cx, float cy, float r, float a0, float a1, int ccw);
void arc_to(float x1, float y1, float x2, float y2, float r);
void rect(float x, float y, float w, float h);
void rounded_rect(float x, float y, float w, float h, float r);
void ellipse(float cx, float cy, float rx, float ry);
void close_path();
void fill();      // with the fill paint and rule
void stroke();    // with the stroke paint and line style
void clip();      // intersect the clip with it
```

Paths can also be built separately and reused — `struct GuiPath` (`gui_path.h`) has the same commands plus `ellipse_arc`, `svg_arc_to`, `rounded_rect4` (per-corner radii), `circle`, `polygon`, `add_svg(d)` (SVG path data: every command, relative and absolute, arcs included), `append(other, matrix)`, `transform(m)`, `bounds()`:

```c
struct GuiPath heart = GuiPath::new();
heart.add_svg("M10 30 A20 20 0 0 1 50 30 A20 20 0 0 1 90 30 Q90 60 50 90 Q10 60 10 30 Z");
c.fill_path(&heart);
c.stroke_path(&heart);
heart.free();
```

Shapes without touching the current path: `fill_rect`, `stroke_rect`, `clear_rect`, `fill_rounded_rect`, `stroke_rounded_rect`, `fill_circle`, `stroke_circle`, `fill_ellipse`, `draw_line`, `fill_path`, `stroke_path`. `clear(color)` fills the whole target, ignoring transform, clip and blend mode.

### Fill rules {#fill-rules}

`set_fill_rule(GUI_FILL_NONZERO)` (the default) or `GUI_FILL_EVENODD` — the latter makes holes where subpaths overlap, whatever their direction.

## Strokes {#strokes}

```c
void set_line_width(float w);
void set_line_cap(int cap);        // GUI_CAP_BUTT, _ROUND, _SQUARE
void set_line_join(int join);      // GUI_JOIN_MITER, _ROUND, _BEVEL
void set_miter_limit(float limit); // default 10
void set_dash([]const float lengths, float offset);   // n = 0: solid
void stroke_to_path(const &GuiPath p, &GuiPath out);        // the stroke's outline, to fill or clip
```

Strokes are converted to an outline and filled with the nonzero rule, so overlapping parts of one stroke never double-blend, and translucent strokes look right. Line widths are in user space: a scaled or rotated transform scales and rotates the stroke with it. Dash patterns take up to 16 lengths (an odd count repeats once, as in SVG) and an offset you can animate.

## Paint: colors, gradients, patterns {#paint}

```c
void set_fill_color(struct GuiColor c);
void set_stroke_color(struct GuiColor c);
void set_fill_paint(const &GuiPaint p);     // copied
void set_stroke_paint(const &GuiPaint p);

struct GuiPaint {
    static struct GuiPaint solid(struct GuiColor c);
    static struct GuiPaint linear(float x0, float y0, float x1, float y1);
    static struct GuiPaint radial(float cx, float cy, float r);
    static struct GuiPaint radial2(float x0, float y0, float r0, float x1, float y1, float r1);
    static struct GuiPaint sweep(float cx, float cy, float startAngle);   // conic
    static struct GuiPaint pattern(const &GuiImage img);
    void add_stop(float offset, struct GuiColor c);    // up to 16 stops
    void set_spread(int spread);       // GUI_SPREAD_PAD, _REPEAT, _REFLECT, _NONE
    void set_transform(struct GuiMatrix m);
    void set_filter(int filter);       // GUI_FILTER_NEAREST, _BILINEAR (patterns)
    void set_opacity(float opacity);
};
```

Gradients and patterns live in user space at the time they're set (they move with the transform). Gradient colors are interpolated in premultiplied space, so a fade to transparent doesn't darken.

```c
struct GuiPaint g = GuiPaint::linear(0.0f, 0.0f, 200.0f, 0.0f);
g.add_stop(0.0f, GuiColor::hex(0x3B5BDBU));
g.add_stop(1.0f, GuiColor::hex(0x9C36B5U));
c.set_fill_paint(&g);
c.fill_rounded_rect(0.0f, 0.0f, 200.0f, 80.0f, 12.0f);
```

## Clipping {#clipping}

```c
void clip_rect(float x, float y, float w, float h);
void clip_path(const &GuiPath p);
void clip();                   // with the current path
void reset_clip();
struct GuiRect clip_bounds() const;
```

Each call intersects with the current clip; `restore()` undoes it. An axis-aligned rectangle clip is free; any other clip is an anti-aliased coverage mask, so clipped edges are smooth too.

## Compositing and blend modes {#compositing}

`set_blend_mode(mode)` takes the twelve Porter-Duff operators — `GUI_BLEND_SRC_OVER` (default), `_SRC`, `_DST`, `_DST_OVER`, `_SRC_IN`, `_DST_IN`, `_SRC_OUT`, `_DST_OUT`, `_SRC_ATOP`, `_DST_ATOP`, `_XOR`, `_CLEAR` — plus `_PLUS` and the separable blend modes `_MULTIPLY`, `_SCREEN`, `_OVERLAY`, `_DARKEN`, `_LIGHTEN`, `_COLOR_DODGE`, `_COLOR_BURN`, `_HARD_LIGHT`, `_SOFT_LIGHT`, `_DIFFERENCE`, `_EXCLUSION`, with the W3C compositing formulas.

## Images {#images}

```c
void draw_image(const &GuiImage img, float x, float y);
void draw_image_scaled(const &GuiImage img, float x, float y, float w, float h);
void draw_image_rect(const &GuiImage img, float sx, float sy, float sw, float sh,
                     float dx, float dy, float dw, float dh);
void set_image_filter(int filter);   // GUI_FILTER_BILINEAR (default) or _NEAREST
```

Images go through the full pipeline: transformed (rotated, skewed), clipped, blended, with global alpha. `GuiImage` itself ([gui_png.h](/stdlib/gui#simple-drawing)) loads and saves PNG files and resizes with area averaging. An image is drawn as straight or premultiplied according to its `premultiplied` flag; `GuiCanvas::for_image` converts it in place.

## Text {#text}

```c
void set_font(const &GuiTypeface face, float sizePx);
void set_font_size(float sizePx);
void set_text_align(int align);        // GUI_TEXT_LEFT, _CENTER, _RIGHT
void set_text_baseline(int baseline);  // GUI_BASELINE_ALPHABETIC, _TOP, _MIDDLE, _BOTTOM
float fill_text(const &char text, float x, float y);    // returns the advance width
float fill_text_n([]const char text, float x, float y);
float measure_text(const &char text) const;
void  fill_text_layout(const &GuiTextLayout layout, float x, float y);
```

Text is filled with the fill paint, so gradients and patterns work on text too. Glyphs are rasterized anti-aliased, unhinted, at quarter-pixel horizontal positions, and cached per typeface, size and subpixel offset; under a rotation or skew they are drawn from their outlines instead.

### Fonts — `GuiTypeface` (`gui_text.h`) {#fonts}

A from-scratch TrueType/OpenType reader: `cmap` formats 0/4/6/12, `glyf` simple and composite glyphs (quadratic outlines), `hmtx`, `kern` format 0, GPOS pair kerning (formats 1 and 2, extension lookups), `OS/2`, `name`, TrueType collections (`.ttc`).

```c
struct GuiTypeface face;
if (!face.load_file("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")) { ... }
// or face.load_memory(((const unsigned char*)bytes)[0..size], GUI_FONT_COPY), load_file_index(path, i) for a .ttc

&GuiTypeface any = GuiTypeface::fallback();             // always there, compiled in
?&GuiTypeface sans = GuiTypeface::system("sans");        // a system font by family
?&GuiTypeface bold = GuiTypeface::system_bold("DejaVu Sans");

float w  = face.measure("Hello", 16.0f);
float lh = face.line_height(16.0f);
int g    = face.glyph_index(0x00E9U);                    // é
float k  = face.kerning(face.glyph_index('A'), face.glyph_index('V'), 16.0f);
face.glyph_path(g, &path, 16.0f, x, y);                  // the outline, as a GuiPath
face.free();
```

- **The fallback face** ("SafeC Fallback Sans", a 23 KB subset of DejaVu Sans: ASCII, Latin-1, common punctuation and symbols, with kerning) is compiled into the library, so text renders with no font files at all — on bare metal or in a minimal container.
- **System fonts** are looked up by family name in the usual directories on Linux (`/usr/share/fonts/...`), macOS (`/System/Library/Fonts`, `/Library/Fonts`) and Windows (`C:/Windows/Fonts`), plus `$SAFEC_GUI_FONT_DIR`; results are cached. `"sans"`, `"serif"` and `"mono"` map to common families (DejaVu, Liberation, Noto, FreeFont, Segoe UI, Arial, Times New Roman, Consolas, Courier New ...); any other name is tried as a file name (`"DejaVu Sans"` finds `DejaVuSans.ttf`).
- **Missing glyphs** are taken from the fallback face by `GuiTextLayout`, so mixed text still draws.

Limits: TrueType (`glyf`) outlines only — fonts with CFF outlines (`.otf` with PostScript curves) load for metrics only and are refused unless `GUI_FONT_ALLOW_NO_OUTLINES` is given; no hinting, no color/bitmap glyphs, no variable-font axes; shaping is one glyph per codepoint with pair kerning — right for Latin, Greek, Cyrillic, CJK and symbols, not for Arabic or Indic scripts (no ligatures, no reordering, no bidi).

### Layout — `GuiTextLayout` {#text-layout}

```c
struct GuiTextLayout l = GuiTextLayout::new();
l.build(face, 14.0f, ((const unsigned char*)text)[0..(unsigned long)-1], 300.0f, GUI_TEXT_LEFT);  // wrap at 300 px
c.fill_text_layout(&l, 10.0f, 10.0f);
unsigned long at = l.hit_test(mx, my);     // the byte offset nearest a point
float cx; float top; float h;
l.caret(at, &cx, &top, &h);                // where to draw the caret
l.free();
```

`build` breaks lines at spaces, after hyphens, between CJK characters and at `\n`; expands tabs; aligns left, center or right; and records each glyph's byte range, which is what text fields use for selection and caret movement. `width`, `height`, `lineHeight` and `lines` describe the result.

## Paths, rectangles and colors as values {#values}

`GuiPoint`, `GuiRect` (`new`, `contains`, `intersect`, `unite`, `is_empty`), `GuiMatrix` and `GuiPath` are plain values in `gui_path.h`, usable without a canvas — for hit-testing, say. `GuiColor` has HSV, hex, mixing and shading helpers ([colors](/stdlib/gui#colors)).
