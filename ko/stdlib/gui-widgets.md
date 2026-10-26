# GUI 위젯 (`std/gui/gui_widget.h`)

리테인드 위젯 툴킷입니다: 위젯 트리를 한 번 만들면, 툴킷이 레이아웃하고, [벡터 캔버스](/ko/stdlib/gui-graphics)로 라이트 또는 다크 테마로 그리고, 입력으로 구동합니다 — 키보드 포커스와 Tab 탐색, 호버·눌림·비활성 상태, 시스템 클립보드를 쓰는 텍스트 편집, 단축키가 있는 메뉴, 컨텍스트 메뉴, 툴팁, 모달 대화상자.

```c
#include <std/gui/gui_widget.h>
#include <std/gui/gui_app.h>       // to run it
```

```c
struct Form { &Widget name; &Widget greeting; };

static void greet(&Widget button, ?&Form of) {
    match (of) {
        case some(f): {
            struct String s = String::from("Hello, ");
            s.push_str(&f.name.text);
            unsafe { f.greeting.set_text((const char*)s.data); }
            s.free();
        }
        case null: { }
    }
}

&Widget root = Widget::vstack().set_padding(16);
struct Form form;
form.name = Widget::textinput("Your name");
form.greeting = Widget::label("");
root.add_child(form.name);
root.add_child(Widget::button("Greet").on_click(greet, &form));
root.add_child(form.greeting);
app.set_root(win, root);        // GuiApp: layout, painting and events are automatic
```

`examples/gui_gallery`는 모든 위젯을 보여 주고, `tests/stdlib/tests/test_gui_widgets.sc`는 합성 입력으로 위젯을 구동합니다.

---

## 트리 실행하기 {#running-a-tree}

[`GuiApp`](/ko/stdlib/gui#the-application-gui-app-h)을 쓰면 `app.set_root(win, root)`가 전부입니다: 창이 트리를 소유하고, 무언가 바뀌면 다시 그리고, 창이 닫히면 트리를 해제합니다. 창마다 자신의 포커스, 팝업, 테마를 가집니다.

`GuiApp` 없이는 프레임마다 직접 트리를 구동합니다:

```c
&GuiUi ui = GuiUi::new(root);
ui.attach(&win);
while (!win.shouldClose) {
    struct GuiEvent ev;
    if (win.wait_event(&ev, 16)) {
        ui.dispatch(&ev);
        while (win.poll_event(&ev)) { ui.dispatch(&ev); }
    }
    ui.tick(gui_time_ms());               // tooltips, caret blink, busy bars
    if (ui.needs_redraw()) { ui.render(); win.present(); }
}
ui.free();                                // and the tree
```

예전 진입점도 루트 위젯에서 그대로 동작합니다 — `root.layout(x, y, w, h)`, `root.dispatch_event(&ev)`, `root.render(&win)`, `root.free()` — 처음 사용할 때 `GuiUi`를 만듭니다.

```c
struct GuiUi {
    &Widget root;
    struct GuiTheme theme;
    ?&Widget focus;
    // ...
    static &GuiUi new(&Widget root);
    void attach(&GuiWindow win);
    void set_theme(struct GuiTheme t);
    int  dispatch(&GuiEvent ev);         // 1 if a widget used it
    void render();                       // layout if needed, paint
    void tick(double nowMs);
    int  animating() const;              // something moves: keep rendering
    int  needs_redraw() const;
    void invalidate(); void request_layout();
    void set_focus(?&Widget w);
    void popup(&Widget menu, float x, float y);   // any menu as a popup
    void close_popups();
    void show_modal(&Widget dialog);
    void close_modal(&Widget dialog);
    ?&Widget modal() const;
    void free() consume;
};
```

## 트리 만들기 {#building-the-tree}

모든 생성자는 힙에 할당된 `&Widget`을 반환하는 정적 메서드입니다. 세터는 위젯을 반환하므로 호출을 이어 쓸 수 있습니다. 위젯은 트리와 함께 해제됩니다.

```c
parent.add_child(child);
parent.insert_child(index, child);
parent.remove_child(child);          // detached, not freed
parent.clear_children();             // removed and freed
unsigned long n = parent.child_count();
?&Widget c = parent.child_at(i);
?&Widget w = root.find("name");      // by set_name(), depth first
w.invalidate();                      // repaint (and relayout)
```

## 레이아웃 {#layout}

각 위젯은 자연 크기 — 내용과 패딩 — 를 가지며, `set_pref_size(w, h)`가 차원별로 덮어쓰고(0은 자연 크기 유지) `set_min_size`/`set_max_size`가 제한합니다. 모든 크기는 논리 단위이며, UI가 창의 배율을 곱하므로 HiDPI 창은 같은 레이아웃을 두 배의 픽셀로 얻습니다. 레이아웃 후의 위젯 사각형(`x`, `y`, `w`, `h`)은 창 픽셀 단위입니다.

| 컨테이너 | 배치 방식 |
|-----------|----------|
| `Widget::vstack()`, `Widget::hstack()` | 자식 사이에 `set_spacing` 간격을 둔 열 / 행. 남는 공간은 `set_stretch(weight)`가 있는 자식에게 비율대로 나눠 주고, 공간이 모자라면 늘어나는 자식부터 (최소 크기까지) 줄입니다. 교차 축에서는 `set_alignment(WIDGET_ALIGN_START / _CENTER / _END / _STRETCH)`. |
| `Widget::grid(columns)` | 읽는 순서대로 셀을 채우거나, `set_cell(row, col)`과 `set_span(rows, cols)`로 배치. 열과 행은 가장 큰 셀에 맞춰지고, `set_column_stretch(col, w)` / `set_row_stretch(row, w)`가 남는 공간을 나눕니다. `set_align2(h, v)`는 셀 안에서 자식을 정렬합니다. |
| `Widget::stack()` | 자식들을 겹쳐 놓고, 각각을 `set_align2`로 두 축에서 정렬. |
| `Widget::scroll()` | 자식 하나를 자연 크기로 두고 휠, 스크롤 막대, 키보드로 스크롤(포커스가 움직이면 보이도록 스크롤). |
| `Widget::splitter(WIDGET_HORIZONTAL / _VERTICAL)` | 드래그할 수 있는 구분선이 있는 두 자식; `value()`는 첫 번째 자식의 비율(0..1). |
| `Widget::tabs()` | `add_tab(title, page)`로 추가한 페이지; 탭을 클릭하거나 Ctrl+Tab / Ctrl+Shift+Tab. |
| `Widget::group(title)` | 열을 둘러싼 제목 있는 테두리. |
| `Widget::panel()` | 열을 둘러싼 단순한 테두리(배경과 외곽선). |
| `Widget::statusbar()` | 창 아래쪽을 따라가는 행. |
| `Widget::spacer()` | 빈 공간: stretch를 주면 형제들을 밀어냅니다. |

모든 위젯에는 마진(`set_margin(m)`, `set_margin4(l, t, r, b)`)과 패딩(`set_padding(p)`, `set_padding4(l, t, r, b)`)이 있으며, `set_visible(0)`은 레이아웃에서 뺍니다.

## 위젯 {#widgets}

| 생성자 | 설명 | 값 API |
|-------------|----|-----------|
| `label(text)` | 텍스트; `set_wrap(1)`로 줄바꿈, `set_text_align`, `set_bold`, `set_font_size` | `set_text`, `text` |
| `button(text)` | 누름 버튼 | `on_click` |
| `checkbox(text, checked)` | 체크 상자 | `set_checked`, `is_checked`, `on_change` |
| `toggle(text, on)` | 스위치 | 체크 상자와 같음 |
| `radio_group()` | 여러 항목(`add_item`) 중 하나 선택 | `set_selected`, `selected_index`, `on_change` |
| `slider(min, max, value)` | 가로, 또는 `set_orientation(WIDGET_VERTICAL)`로 세로; `set_step` | `set_value`, `value`, `set_range`, `on_change` |
| `progress(fraction)` | 진행 막대; `set_indeterminate(1)`은 작업 중 막대를 애니메이션 | `set_value`(0..1) |
| `spinner(min, max, value, step)` | 위/아래 버튼이 있는 숫자 필드; `set_decimals` | `value`, `on_change` |
| `textinput(placeholder)` | 한 줄 텍스트; `set_password(1)`, `set_read_only(1)` | `set_text`, `text`, `select(a, b)`, `select_all`, `selection_start/end`, `on_change`, `on_activate`(Enter) |
| `textarea()` | 스크롤되는 여러 줄 텍스트; `set_wrap(1)` | 같음 |
| `combo()` | 드롭다운 목록(`add_item`) | `set_selected`, `selected_index`, `on_change` |
| `list()` | 스크롤되는 목록(`add_item`) | `set_selected`, `selected_index`, `on_change`, `on_activate`(더블 클릭, Enter) |
| `tree()` | 트리: `add_node(parent, text)`는 노드 인덱스를 반환(`-1` = 최상위); `set_expanded` | `selected_index`(노드), `node_text`, `on_change`, `on_activate` |
| `table()` | 열(`add_column(title, width)`, 너비 0 = 나머지를 나눠 가짐; 헤더를 드래그해 크기 조절), 행(`add_row`, `set_cell_text`) | `selected_index`(행), `cell_text`, `row_count`, `clear_rows` |
| `image(img)` | `GuiImage`(소유하지 않음), `set_fit(WIDGET_FIT_CONTAIN / _COVER / _STRETCH / _NONE)` | `set_image` |
| `canvas(draw, data)` | 직접 그리기: `draw(widget, canvas, data)`는 위젯으로 이동·클리핑되고 논리 단위로 확대된 캔버스를 받습니다 | 다시 그리려면 `invalidate()` |
| `color_picker(color)` | 채도/명도 사각형, 색상 막대, 견본과 16진수 코드 | `set_color`, `get_color`, `on_change` |
| `separator()` | 선(행 안에서는 세로) | |
| `menubar()`, `menu(title)`, `menu_item(text)`, `menu_separator()` | [메뉴](#menus) 참고 | |
| `dialog(title)` | [대화상자](#dialogs) 참고 | |
| `custom(draw, layout, event, data)` | 창의 정수 헬퍼로 그리는 기존 사용자 정의 위젯 훅 | |

그 밖의 흔한 세터: `set_enabled(0)`(회색으로 표시되고 포커스에서 제외), `set_tooltip(text)`, `set_placeholder(text)`, `set_name(name)`, `set_focusable(on)`, `set_tag(n)`(사용자용), 그리고 스타일 덮어쓰기 `set_bg`, `set_fg`, `set_border`, `set_font`(비트맵 `GuiFont`), `set_font_scale`.

### 콜백 {#callbacks}

```c
generic<T> &Widget on_click(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_change(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_activate(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_draw(fn void(&Widget, &GuiCanvas, ?&T) cb, ?&T data);
```

`T`는 콜백에서 추론되며, 콜백은 컴파일 시점에 데이터와 타입 검사됩니다. 한 트리가 서로 무관한 컨텍스트 타입의 위젯을 담으므로 위젯은 이 쌍을 타입 소거해 저장합니다. `on_change`는 사용자가 만든 모든 값 변경 — 텍스트, 체크 상태, 슬라이더와 스피너 값, 선택, 색, 스플리터 위치, 현재 탭 — 에 호출되며, 코드에서 설정한 값에는 호출되지 않습니다.

## 키보드와 포커스 {#keyboard-and-focus}

| 키 | 동작 |
|-----|------|
| Tab / Shift+Tab | 트리 순서상 다음 / 이전 포커스 가능 위젯(숨겨지거나 비활성인 위젯은 건너뛰고, 열린 대화상자 안에 머무름) |
| Space, Enter | 포커스된 버튼 클릭; 체크 상자나 스위치 전환 |
| 화살표, Home, End, PageUp/Down | 슬라이더, 스피너, 라디오 그룹, 목록/트리/표 선택 이동; Left/Right는 트리 노드를 접고 펼침 |
| Ctrl+Tab | 다음 탭 페이지 |
| Escape | 열린 팝업, 없으면 대화상자(취소로), 없으면 포커스 해제 |
| 메뉴 항목의 단축키 | 창 어디서나 실행(컨텍스트 메뉴의 단축키는 그 위젯에 포커스가 있을 때) |

클릭하면 그 아래의 포커스 가능 위젯에 포커스가 갑니다. 키는 포커스된 위젯에 먼저 가고 부모로 버블링됩니다. `custom` 위젯의 이벤트 훅이나 `GuiApp.on_event`에서 `ev.prevent_default()`를 호출하면 기본 동작이 멈춥니다.

**텍스트 필드**는 문자 단위, 단어 단위(Ctrl+Left/Right; macOS에서는 Alt), 줄 끝(Home/End), 텍스트 영역에서는 줄과 페이지 단위로 이동하고; Shift로 선택을 넓히고; 마우스로 선택하며(더블 클릭: 단어, 트리플 클릭: 전체); Ctrl+Backspace/Delete로 단어 단위 삭제를 하고; 기본 수정자 키(macOS는 Cmd, 그 밖에는 Ctrl)로 시스템 클립보드를 통해 잘라내기, 복사, 붙여넣기, 모두 선택을 합니다. 암호 필드는 글머리 기호로 표시되고 복사되지 않습니다. 입력기에서 온 텍스트를 포함해 입력은 처음부터 끝까지 UTF-8입니다.

## 메뉴 {#menus}

```c
&Widget bar = Widget::menubar();
&Widget file = Widget::menu("File");
file.add_child(Widget::menu_item("Open...").set_shortcut("Ctrl+O").on_click(open_file, &app));
file.add_child(Widget::menu_separator());
&Widget recent = Widget::menu("Open recent");          // a menu inside a menu: a submenu
recent.add_child(Widget::menu_item("notes.txt"));
file.add_child(recent);
&Widget wrap = Widget::menu_item("Word wrap").set_checked(1).on_change(toggle_wrap, &app);   // checkable
file.add_child(wrap);
bar.add_child(file);
root.add_child(bar);                                    // usually the first child

&Widget ctx = Widget::menu("");
ctx.add_child(Widget::menu_item("Delete").set_shortcut("Delete"));
list.set_context_menu(ctx);                             // right click shows it
ui.popup(ctx, x, y);                                    // or show any menu yourself
```

메뉴는 클릭으로 열리고, 하나가 열려 있는 동안에는 포인터를 따라 메뉴 막대를 이동하며, 누른 채 끌어서 놓기로도, 키보드(화살표, Enter, Escape, 하위 메뉴는 Right/Left)로도 동작합니다. 단축키는 `"Ctrl+S"`, `"Ctrl+Shift+Z"`, `"Alt+F4"`, `"F5"`, `"Delete"`처럼 씁니다. `Ctrl`(또는 `Cmd`, `Primary`)은 macOS에서 Cmd, 그 밖에서는 Ctrl을 뜻하며, 단축키 텍스트는 메뉴에 표시됩니다.

## 대화상자 {#dialogs}

```c
// A message box: a text and a row of buttons; frees itself.
gui_message_box(ui, "Unsaved changes", "Save the document before closing?",
                GUI_MB_YES | GUI_MB_NO | GUI_MB_CANCEL, on_answer, &app);
static void on_answer(&Widget box, int result, ?&App app) {  // GUI_RESULT_YES / _NO / _CANCEL / _OK
    ...
}

// Your own: a dialog is a column; show it modally.
&Widget dlg = Widget::dialog("Settings");
dlg.add_child(Widget::checkbox("Autosave", 1));
dlg.add_child(Widget::button("Close").on_click(close_it, &state));   // close_it: dlg.close_dialog(GUI_RESULT_OK)
ui.show_modal(dlg);
// ... later, after it closed: dlg.free() (the UI doesn't own a dialog)
```

모달 대화상자는 창 가운데에 놓이고(제목 표시줄을 끌어 옮길 수 있음), 아래의 모든 것을 어둡게 하고 막으며, Tab 탐색을 안에 가두고, 첫 컨트롤에 포커스를 주고, Escape로 닫힙니다(`GUI_RESULT_CANCEL`). Enter는 포커스된 버튼을 누릅니다.

## 툴팁 {#tooltips}

모든 위젯에 `set_tooltip(text)`: 포인터가 0.6초 머무르면 포인터 아래에 창 안쪽으로 표시되고, 클릭, 키 입력, 포인터 이동 시 숨겨집니다.

## 테마 {#themes}

```c
struct GuiTheme t = GuiTheme::light();     // or dark(), or system()
t.accent = GuiColor::hex(0xE8590CU);
t.fontSize = 15.0f;
t.radius = 6.0f;
ui.set_theme(t);
```

`GuiTheme`은 팔레트(`window`, `panel`, `text`, `textDim`, `textDisabled`, `accent`, `accentText`, `button`, `buttonHover`, `buttonPressed`, `input`, `border`, `borderStrong`, `selection`, `selectionText`, `hover`, `header`, `altRow`, `scrollbar`, `scrollbarHover`, `tooltip`, `tooltipText`, `shadow`, `overlay`), 치수(`fontSize`, `smallFontSize`, `radius`, `borderWidth`, `padding`, `spacing`, `controlHeight`, `rowHeight`, `scrollbarSize`), 폰트(`font`, `boldFont`: 시스템의 산세리프, 설치된 것이 없으면 `GuiTypeface::fallback()`)를 가집니다. `GuiTheme::system()`은 `$SAFEC_GUI_THEME`(`dark` / `light`)이나 `:dark`로 끝나는 `GTK_THEME`에서 라이트·다크를 고르며, 없으면 라이트입니다. `GuiApp::set_root`는 `system()`을 적용합니다.

설치된 폰트에 의존하지 않는 픽셀 단위로 정확한 출력(테스트, 골든 이미지)이 필요하면 `t.font = GuiTypeface::fallback()`으로 설정하십시오.

## 직접 그리기 {#custom-drawing}

자신의 내용을 그리는 가장 간단한 방법은 캔버스 위젯입니다:

```c
static void draw_chart(&Widget w, &GuiCanvas c, ?&Data od) {
    // (0, 0) is the widget's top-left; units are logical
    c.set_fill_color(GuiColor::hex(0x4DABF7U));
    c.fill_rect(0.0f, 0.0f, 40.0f, 80.0f);
}
root.add_child(Widget::canvas(draw_chart, &data).set_pref_size(0, 120));
```

`Widget::custom(draw, layout, event, data)` — 정수 헬퍼로 창에 그리는 `WidgetDrawFn`, 자식을 배치하는 `WidgetLayoutFn`, 원시 이벤트를 먼저 받는 `WidgetCustomEventFn`으로 된 기존 훅 — 은 기존 코드를 위해 계속 동작합니다.

## UI 테스트 {#testing-uis}

[헤드리스 백엔드](/ko/stdlib/gui#the-headless-backend)로 테스트는 트리를 만들고, 입력을 주입하고, 결과 — 레이아웃, 포커스, 값, 픽셀까지 — 를 확인할 수 있습니다:

```c
gui_use_headless();
struct GuiWindow win = GuiWindow::create("test", 300, 200);
&Widget root = Widget::vstack();
&Widget name = Widget::textinput("");
root.add_child(name);
&GuiUi ui = GuiUi::new(root);
ui.attach(&win);
ui.render();
gui_headless_click(&win, (double)(name.x + 5), (double)(name.y + 5));
gui_headless_type(&win, "hello");
struct GuiEvent ev;
while (win.poll_event(&ev)) { ui.dispatch(&ev); }
ASSERT_TRUE(name.text.eq_cstr("hello"));
ui.free();                 // UI와 그 위젯 트리
win.destroy();
```
