# GUI Widgets (`std/gui/gui_widget.h`)

A retained widget toolkit: build a tree of widgets once, and the toolkit lays it out, paints it with the [vector canvas](/stdlib/gui-graphics) in a light or dark theme, and drives it from input — keyboard focus and Tab navigation, hover, pressed and disabled states, text editing with the system clipboard, menus with shortcuts, context menus, tooltips and modal dialogs.

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

`examples/gui_gallery` shows every widget; `tests/stdlib/tests/test_gui_widgets.sc` drives them with synthetic input.

---

## Running a tree {#running-a-tree}

With [`GuiApp`](/stdlib/gui#the-application-gui-app-h), `app.set_root(win, root)` is all: the window owns the tree, repaints when something changed, and frees it when it closes. Each window has its own focus, popups and theme.

Without it, drive the tree by hand once per frame:

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

The older entry points still work on a root widget — `root.layout(x, y, w, h)`, `root.dispatch_event(&ev)`, `root.render(&win)`, `root.free()` — and create a `GuiUi` for it on first use.

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

## Building the tree {#building-the-tree}

Every constructor is a static method returning a heap-allocated `&Widget`. Setters return the widget, so calls chain. A widget is freed with its tree.

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

## Layout {#layout}

Each widget has a natural size — its content plus padding — that `set_pref_size(w, h)` overrides per dimension (0 keeps the natural one) and `set_min_size`/`set_max_size` clamp. All sizes are logical units; the UI multiplies them by the window's scale factor, so a HiDPI window gets the same layout with twice the pixels. Widget rectangles (`x`, `y`, `w`, `h`) are in window pixels after layout.

| Container | Lays out |
|-----------|----------|
| `Widget::vstack()`, `Widget::hstack()` | A column / row with `set_spacing` between children. Extra space goes to children with `set_stretch(weight)`, in proportion; when space is short, stretching children shrink first (down to their minimum). On the cross axis, `set_alignment(WIDGET_ALIGN_START / _CENTER / _END / _STRETCH)`. |
| `Widget::grid(columns)` | Cells in reading order, or placed with `set_cell(row, col)` and `set_span(rows, cols)`. Columns and rows size to their largest cell; `set_column_stretch(col, w)` / `set_row_stretch(row, w)` share extra space. `set_align2(h, v)` aligns a child in its cell. |
| `Widget::stack()` | Children on top of each other, each aligned on both axes with `set_align2`. |
| `Widget::scroll()` | One child at its natural size, scrolled with the wheel, the scroll bars or the keyboard (and into view as focus moves). |
| `Widget::splitter(WIDGET_HORIZONTAL / _VERTICAL)` | Two children with a draggable divider; `value()` is the first one's share (0..1). |
| `Widget::tabs()` | Pages added with `add_tab(title, page)`; click a tab or Ctrl+Tab / Ctrl+Shift+Tab. |
| `Widget::group(title)` | A titled frame around a column. |
| `Widget::panel()` | A plain frame (background and border) around a column. |
| `Widget::statusbar()` | A row along the bottom of a window. |
| `Widget::spacer()` | Empty space: give it a stretch to push its siblings apart. |

Every widget also has margins (`set_margin(m)`, `set_margin4(l, t, r, b)`) and padding (`set_padding(p)`, `set_padding4(l, t, r, b)`); `set_visible(0)` takes it out of the layout.

## Widgets {#widgets}

| Constructor | Is | Value API |
|-------------|----|-----------|
| `label(text)` | Text; `set_wrap(1)` wraps it, `set_text_align`, `set_bold`, `set_font_size` | `set_text`, `text` |
| `button(text)` | A push button | `on_click` |
| `checkbox(text, checked)` | A check box | `set_checked`, `is_checked`, `on_change` |
| `toggle(text, on)` | A switch | same as a check box |
| `radio_group()` | One choice of several (`add_item`) | `set_selected`, `selected_index`, `on_change` |
| `slider(min, max, value)` | Horizontal, or vertical with `set_orientation(WIDGET_VERTICAL)`; `set_step` | `set_value`, `value`, `set_range`, `on_change` |
| `progress(fraction)` | A progress bar; `set_indeterminate(1)` animates a busy bar | `set_value` (0..1) |
| `spinner(min, max, value, step)` | A number field with up/down buttons; `set_decimals` | `value`, `on_change` |
| `textinput(placeholder)` | One line of text; `set_password(1)`, `set_read_only(1)` | `set_text`, `text`, `select(a, b)`, `select_all`, `selection_start/end`, `on_change`, `on_activate` (Enter) |
| `textarea()` | Multi-line text, scrolling; `set_wrap(1)` | same |
| `combo()` | A drop-down list (`add_item`) | `set_selected`, `selected_index`, `on_change` |
| `list()` | A scrolling list (`add_item`) | `set_selected`, `selected_index`, `on_change`, `on_activate` (double click, Enter) |
| `tree()` | A tree: `add_node(parent, text)` returns the node's index (`-1` = top level); `set_expanded` | `selected_index` (a node), `node_text`, `on_change`, `on_activate` |
| `table()` | Columns (`add_column(title, width)`, width 0 = share the rest; resizable by dragging the header), rows (`add_row`, `set_cell_text`) | `selected_index` (a row), `cell_text`, `row_count`, `clear_rows` |
| `image(img)` | A `GuiImage` (not owned), `set_fit(WIDGET_FIT_CONTAIN / _COVER / _STRETCH / _NONE)` | `set_image` |
| `canvas(draw, data)` | Your drawing: `draw(widget, canvas, data)` gets a canvas translated to the widget, clipped and scaled to logical units | `invalidate()` to redraw |
| `color_picker(color)` | Saturation/value square, hue bar, swatch and hex code | `set_color`, `get_color`, `on_change` |
| `separator()` | A line (vertical in a row) | |
| `menubar()`, `menu(title)`, `menu_item(text)`, `menu_separator()` | See [menus](#menus) | |
| `dialog(title)` | See [dialogs](#dialogs) | |
| `custom(draw, layout, event, data)` | The original custom-widget hook, drawing with the window's integer helpers | |

Other common setters: `set_enabled(0)` (greys it out, skipped by focus), `set_tooltip(text)`, `set_placeholder(text)`, `set_name(name)`, `set_focusable(on)`, `set_tag(n)` (yours), and the style overrides `set_bg`, `set_fg`, `set_border`, `set_font` (a bitmap `GuiFont`), `set_font_scale`.

### Callbacks {#callbacks}

```c
generic<T> &Widget on_click(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_change(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_activate(fn void(&Widget, ?&T) cb, ?&T data);
generic<T> &Widget on_draw(fn void(&Widget, &GuiCanvas, ?&T) cb, ?&T data);
```

`T` is inferred from the callback, which is type-checked against the data at compile time; the widget stores the pair type-erased, because one tree holds widgets with unrelated context types. `on_change` fires for every value change the user makes — text, check state, slider and spinner values, selection, color, splitter position, the current tab — and not for values set from code.

## Keyboard and focus {#keyboard-and-focus}

| Key | Does |
|-----|------|
| Tab / Shift+Tab | Next / previous focusable widget in tree order (skips hidden and disabled ones; stays inside an open dialog) |
| Space, Enter | Click a focused button; toggle a check box or switch |
| Arrows, Home, End, PageUp/Down | Move sliders, spinners, radio groups, list/tree/table selections; Left/Right collapse and expand tree nodes |
| Ctrl+Tab | Next tab page |
| Escape | Close the open popup, else the dialog (as Cancel), else clear the focus |
| A menu item's shortcut | Fire it from anywhere in the window (a context menu's while its widget has the focus) |

A click focuses the focusable widget under it. A key goes to the focused widget first, then bubbles up through its parents; `ev.prevent_default()` from a `custom` widget's event hook or `GuiApp.on_event` stops the default actions.

**Text fields** move by character, by word (Ctrl+Left/Right; Alt on macOS), to the line ends (Home/End), and by line and page in a text area; extend the selection with Shift; select with the mouse (double click: a word, triple click: everything); delete by word with Ctrl+Backspace/Delete; and cut, copy, paste and select all with the primary modifier (Cmd on macOS, Ctrl elsewhere) through the system clipboard. Password fields show bullets and don't copy out. Input is UTF-8 throughout, including text from input methods.

## Menus {#menus}

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

Menus open on click, follow the pointer across the bar while one is open, work by press-drag-release, and from the keyboard (arrows, Enter, Escape, Right/Left for submenus). Shortcuts are written `"Ctrl+S"`, `"Ctrl+Shift+Z"`, `"Alt+F4"`, `"F5"`, `"Delete"`; `Ctrl` (or `Cmd`, `Primary`) means Cmd on macOS and Ctrl elsewhere, and the shortcut text is shown in the menu.

## Dialogs {#dialogs}

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

A modal dialog is centered over the window (and can be dragged by its title bar), dims and blocks everything under it, keeps Tab navigation inside, focuses its first control, and closes with Escape (`GUI_RESULT_CANCEL`); Enter presses the focused button.

## Tooltips {#tooltips}

`set_tooltip(text)` on any widget: shown after the pointer rests on it for 0.6 s, placed below the pointer and kept inside the window, hidden on a click, a key or when the pointer moves away.

## Themes {#themes}

```c
struct GuiTheme t = GuiTheme::light();     // or dark(), or system()
t.accent = GuiColor::hex(0xE8590CU);
t.fontSize = 15.0f;
t.radius = 6.0f;
ui.set_theme(t);
```

`GuiTheme` holds the palette (`window`, `panel`, `text`, `textDim`, `textDisabled`, `accent`, `accentText`, `button`, `buttonHover`, `buttonPressed`, `input`, `border`, `borderStrong`, `selection`, `selectionText`, `hover`, `header`, `altRow`, `scrollbar`, `scrollbarHover`, `tooltip`, `tooltipText`, `shadow`, `overlay`), metrics (`fontSize`, `smallFontSize`, `radius`, `borderWidth`, `padding`, `spacing`, `controlHeight`, `rowHeight`, `scrollbarSize`) and fonts (`font`, `boldFont`: the system's sans-serif, or `GuiTypeface::fallback()` if none is installed). `GuiTheme::system()` picks light or dark from `$SAFEC_GUI_THEME` (`dark` / `light`) or a `GTK_THEME` ending in `:dark`, else light. `GuiApp::set_root` applies `system()`.

For pixel-exact output that doesn't depend on installed fonts (tests, golden images), set `t.font = GuiTypeface::fallback()`.

## Custom drawing {#custom-drawing}

The simplest way to draw your own content is a canvas widget:

```c
static void draw_chart(&Widget w, &GuiCanvas c, ?&Data od) {
    // (0, 0) is the widget's top-left; units are logical
    c.set_fill_color(GuiColor::hex(0x4DABF7U));
    c.fill_rect(0.0f, 0.0f, 40.0f, 80.0f);
}
root.add_child(Widget::canvas(draw_chart, &data).set_pref_size(0, 120));
```

`Widget::custom(draw, layout, event, data)` — the original hook, with `WidgetDrawFn` drawing into the window with the integer helpers, `WidgetLayoutFn` placing children and `WidgetCustomEventFn` getting raw events first — keeps working for existing code.

## Testing UIs {#testing-uis}

With the [headless backend](/stdlib/gui#the-headless-backend), a test builds a tree, injects input and checks the results — layout, focus, values, even pixels:

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
ui.free();                 // the UI and its widget tree
win.destroy();
```
