# GUI 그래픽 (`std/gui/gui_canvas.h`)

HTML canvas, Skia, Cairo와 같은 그리기 모델 — 경로, 채우기, 스트로크, 그라디언트, 이미지 패턴, 변환, 클리핑, 블렌드 모드 — 과 TrueType 텍스트를 갖춘 안티에일리어싱 2D 벡터 캔버스로, 모두 CPU에서 렌더링합니다. [창](/ko/stdlib/gui#windows-gui-h)의 버퍼, `GuiImage`, 또는 임의의 프리멀티플라이드 RGBA8888 메모리에 그리며, OS에 아무것도 요구하지 않으므로 베어메탈에서도 똑같이 동작합니다.

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

[`GuiApp`](/ko/stdlib/gui#the-application-gui-app-h) 안에서는 `app.on_draw(win, cb, data)`가 이미 설정된 창 캔버스를 콜백에 넘겨 주고, 위젯 트리 안에서는 `Widget::canvas(cb, data)`가 위젯에 맞게 클리핑·이동된 캔버스를 줍니다.

## 렌더링 방식 {#how-it-renders}

- **안티에일리어싱**은 가로로는 정확하고 세로로는 16배 슈퍼샘플링합니다: 픽셀 행마다 샘플 선 16개로 자르고, 각 에지 교차는 정확한 가로 커버리지를 차분 버퍼에 누적한 뒤 행마다 한 번 적분합니다. 커버리지는 겹치거나 자기 교차하는 경로에서도, 어느 채우기 규칙이든 세로 1/16픽셀 이내로 도형 내부의 실제 면적입니다.
- **곡선**은 0.05 px 이내로 적응적으로 평탄화됩니다.
- **합성**은 프리멀티플라이드 알파를 사용하며 SIMD 벡터(`vec<float, 4>`)로 네 채널을 한 번에 처리합니다. 불투명한 단색 구간은 단순한 32비트 저장입니다.
- **결정적:** 같은 호출은 모든 플랫폼과 타깃에서 같은 바이트를 만들므로 렌더링을 골든 테스트할 수 있습니다(`tests/stdlib/fixtures/gui/canvas_scene.png`).

데스크톱 코어 하나 기준 대략적인 비용: 1280×800 지우기 0.6 ms, 둥근 사각형 200개 채우기 3 ms, 스트로크 200개 7 ms, 짧은 텍스트 레이블 200개 1 ms.

## 대상 {#targets}

```c
static struct GuiCanvas for_window(&GuiWindow win);
static struct GuiCanvas for_image(&GuiImage img);      // premultiplies it first if needed
static struct GuiCanvas for_pixels(&unsigned char px, int width, int height, int stride);
void retarget_window(&GuiWindow win);   // after a resize reallocated the buffer
void reset();                           // initial state: identity, black fill, no clip
void free();
struct GuiColor get_pixel(int x, int y) const;   // straight alpha
```

캔버스는 픽셀의 복사본이 아니라 포인터를 가집니다. 좌표는 버퍼 픽셀 단위이며, HiDPI 창에서 논리 단위로 그리려면 먼저 `c.scale((float)win.scale, (float)win.scale)`을 호출하십시오.

## 상태와 변환 {#state-transforms}

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

`GuiMatrix`(`gui_path.h`)는 2×3 아핀 행렬입니다: `identity()`, `translation()`, `scaling()`, `rotation()`, `new(a, b, c, d, e, f)`, `mul()`, `translate()`, `scale()`, `rotate()`, `invert()`, `apply_x()`/`apply_y()`, `apply_rect()`.

## 경로 {#paths}

HTML canvas처럼 캔버스에는 현재 경로가 있습니다:

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

경로는 따로 만들어 재사용할 수도 있습니다 — `struct GuiPath`(`gui_path.h`)는 같은 명령에 더해 `ellipse_arc`, `svg_arc_to`, `rounded_rect4`(모서리별 반지름), `circle`, `polygon`, `add_svg(d)`(SVG 경로 데이터: 상대·절대, 호를 포함한 모든 명령), `append(other, matrix)`, `transform(m)`, `bounds()`를 가집니다:

```c
struct GuiPath heart = GuiPath::new();
heart.add_svg("M10 30 A20 20 0 0 1 50 30 A20 20 0 0 1 90 30 Q90 60 50 90 Q10 60 10 30 Z");
c.fill_path(&heart);
c.stroke_path(&heart);
heart.free();
```

현재 경로를 건드리지 않는 도형: `fill_rect`, `stroke_rect`, `clear_rect`, `fill_rounded_rect`, `stroke_rounded_rect`, `fill_circle`, `stroke_circle`, `fill_ellipse`, `draw_line`, `fill_path`, `stroke_path`. `clear(color)`는 변환, 클립, 블렌드 모드를 무시하고 대상 전체를 채웁니다.

### 채우기 규칙 {#fill-rules}

`set_fill_rule(GUI_FILL_NONZERO)`(기본값) 또는 `GUI_FILL_EVENODD` — 후자는 방향과 관계없이 하위 경로가 겹치는 곳에 구멍을 만듭니다.

## 스트로크 {#strokes}

```c
void set_line_width(float w);
void set_line_cap(int cap);        // GUI_CAP_BUTT, _ROUND, _SQUARE
void set_line_join(int join);      // GUI_JOIN_MITER, _ROUND, _BEVEL
void set_miter_limit(float limit); // default 10
void set_dash([]const float lengths, float offset);   // n = 0: solid
void stroke_to_path(const &GuiPath p, &GuiPath out);        // the stroke's outline, to fill or clip
```

스트로크는 외곽선으로 변환된 뒤 nonzero 규칙으로 채워지므로, 한 스트로크의 겹친 부분이 두 번 블렌딩되지 않고 반투명 스트로크도 올바르게 보입니다. 선 두께는 사용자 공간 단위입니다: 변환이 확대·회전되면 스트로크도 함께 확대·회전됩니다. 대시 패턴은 최대 16개 길이(홀수 개는 SVG처럼 한 번 더 반복)와 애니메이션할 수 있는 오프셋을 받습니다.

## 페인트: 색, 그라디언트, 패턴 {#paint}

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

그라디언트와 패턴은 설정 시점의 사용자 공간에 놓입니다(변환과 함께 움직입니다). 그라디언트 색은 프리멀티플라이드 공간에서 보간되므로 투명으로 사라지는 그라디언트가 어두워지지 않습니다.

```c
struct GuiPaint g = GuiPaint::linear(0.0f, 0.0f, 200.0f, 0.0f);
g.add_stop(0.0f, GuiColor::hex(0x3B5BDBU));
g.add_stop(1.0f, GuiColor::hex(0x9C36B5U));
c.set_fill_paint(&g);
c.fill_rounded_rect(0.0f, 0.0f, 200.0f, 80.0f, 12.0f);
```

## 클리핑 {#clipping}

```c
void clip_rect(float x, float y, float w, float h);
void clip_path(const &GuiPath p);
void clip();                   // with the current path
void reset_clip();
struct GuiRect clip_bounds() const;
```

각 호출은 현재 클립과 교차하며, `restore()`가 되돌립니다. 축에 정렬된 사각형 클립은 비용이 없고, 그 밖의 클립은 안티에일리어싱된 커버리지 마스크이므로 클리핑된 가장자리도 부드럽습니다.

## 합성과 블렌드 모드 {#compositing}

`set_blend_mode(mode)`는 Porter-Duff 연산자 12개 — `GUI_BLEND_SRC_OVER`(기본값), `_SRC`, `_DST`, `_DST_OVER`, `_SRC_IN`, `_DST_IN`, `_SRC_OUT`, `_DST_OUT`, `_SRC_ATOP`, `_DST_ATOP`, `_XOR`, `_CLEAR` — 와 `_PLUS`, 그리고 분리 가능한 블렌드 모드 `_MULTIPLY`, `_SCREEN`, `_OVERLAY`, `_DARKEN`, `_LIGHTEN`, `_COLOR_DODGE`, `_COLOR_BURN`, `_HARD_LIGHT`, `_SOFT_LIGHT`, `_DIFFERENCE`, `_EXCLUSION`을 W3C 합성 공식대로 받습니다.

## 이미지 {#images}

```c
void draw_image(const &GuiImage img, float x, float y);
void draw_image_scaled(const &GuiImage img, float x, float y, float w, float h);
void draw_image_rect(const &GuiImage img, float sx, float sy, float sw, float sh,
                     float dx, float dy, float dw, float dh);
void set_image_filter(int filter);   // GUI_FILTER_BILINEAR (default) or _NEAREST
```

이미지는 전체 파이프라인을 거칩니다: 변환(회전, 기울이기), 클리핑, 블렌딩, 전역 알파. `GuiImage` 자체([gui_png.h](/ko/stdlib/gui#simple-drawing))는 PNG 파일을 읽고 쓰며 면적 평균으로 크기를 바꿉니다. 이미지는 `premultiplied` 플래그에 따라 스트레이트 또는 프리멀티플라이드로 그려지며, `GuiCanvas::for_image`는 이미지를 제자리에서 변환합니다.

## 텍스트 {#text}

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

텍스트는 채우기 페인트로 채워지므로 그라디언트와 패턴도 텍스트에 적용됩니다. 글리프는 힌팅 없이 안티에일리어싱되어 1/4픽셀 가로 위치로 래스터화되고, 서체·크기·서브픽셀 오프셋별로 캐시됩니다. 회전이나 기울이기가 있으면 외곽선에서 직접 그립니다.

### 폰트 — `GuiTypeface` (`gui_text.h`) {#fonts}

처음부터 작성한 TrueType/OpenType 리더입니다: `cmap` 형식 0/4/6/12, `glyf` 단순·복합 글리프(2차 곡선 외곽선), `hmtx`, `kern` 형식 0, GPOS 쌍 커닝(형식 1과 2, 확장 룩업), `OS/2`, `name`, TrueType 컬렉션(`.ttc`).

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

- **폴백 서체**("SafeC Fallback Sans", DejaVu Sans의 23 KB 서브셋: ASCII, Latin-1, 흔한 문장 부호와 기호, 커닝 포함)는 라이브러리에 컴파일되어 있으므로, 폰트 파일이 전혀 없어도 — 베어메탈이나 최소 컨테이너에서도 — 텍스트가 렌더링됩니다.
- **시스템 폰트**는 Linux(`/usr/share/fonts/...`), macOS(`/System/Library/Fonts`, `/Library/Fonts`), Windows(`C:/Windows/Fonts`)의 일반적인 디렉터리와 `$SAFEC_GUI_FONT_DIR`에서 패밀리 이름으로 찾으며, 결과는 캐시됩니다. `"sans"`, `"serif"`, `"mono"`는 흔한 패밀리(DejaVu, Liberation, Noto, FreeFont, Segoe UI, Arial, Times New Roman, Consolas, Courier New …)에 대응하고, 그 밖의 이름은 파일 이름으로 시도합니다(`"DejaVu Sans"`는 `DejaVuSans.ttf`를 찾습니다).
- **없는 글리프**는 `GuiTextLayout`이 폴백 서체에서 가져오므로, 섞인 텍스트도 그려집니다.

한계: TrueType(`glyf`) 외곽선만 지원합니다 — CFF 외곽선 폰트(PostScript 곡선의 `.otf`)는 메트릭만 읽히며, `GUI_FONT_ALLOW_NO_OUTLINES`를 주지 않으면 거부됩니다. 힌팅, 컬러·비트맵 글리프, 가변 폰트 축은 없습니다. 셰이핑은 코드포인트당 글리프 하나에 쌍 커닝입니다 — 라틴, 그리스, 키릴, CJK(한글 포함), 기호에는 맞지만 아랍이나 인도계 문자에는 맞지 않습니다(합자, 재배열, 양방향 텍스트 없음).

### 레이아웃 — `GuiTextLayout` {#text-layout}

```c
struct GuiTextLayout l = GuiTextLayout::new();
l.build(face, 14.0f, ((const unsigned char*)text)[0..(unsigned long)-1], 300.0f, GUI_TEXT_LEFT);  // wrap at 300 px
c.fill_text_layout(&l, 10.0f, 10.0f);
unsigned long at = l.hit_test(mx, my);     // the byte offset nearest a point
float cx; float top; float h;
l.caret(at, &cx, &top, &h);                // where to draw the caret
l.free();
```

`build`는 공백, 하이픈 뒤, CJK 문자 사이, `\n`에서 줄을 나누고, 탭을 펼치고, 왼쪽·가운데·오른쪽으로 정렬하며, 각 글리프의 바이트 범위를 기록합니다. 텍스트 필드는 이것으로 선택과 캐럿 이동을 처리합니다. 결과는 `width`, `height`, `lineHeight`, `lines`가 설명합니다.

## 값으로서의 경로, 사각형, 색 {#values}

`GuiPoint`, `GuiRect`(`new`, `contains`, `intersect`, `unite`, `is_empty`), `GuiMatrix`, `GuiPath`는 `gui_path.h`의 일반 값으로, 캔버스 없이도 — 예를 들어 히트 테스트에 — 쓸 수 있습니다. `GuiColor`에는 HSV, 16진수, 섞기, 명암 헬퍼가 있습니다([색](/ko/stdlib/gui#colors)).
