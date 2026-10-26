// site playground — the playground page's editor, a wasm32 SafeC module on
// std/wasm/dom, loaded by theme/playground.js (only on the playground page,
// so the site-wide client stays small). Build:
//
//   cd site/playground && safeguard build --release --target wasm32
//
// The page (view.sc) server-renders the editor: a transparent <textarea>
// over a highlighted <pre>, a line-number gutter, the toolbar and the
// output panes, plus the examples as JSON (#pg-data). This module:
//   - re-highlights (with the generator's own highlight.sc) and renumbers
//     on every edit, and keeps the three layers scrolled together;
//   - Tab / Shift+Tab indent and dedent (Esc, then Tab, leaves the
//     editor), Enter keeps the indentation, Ctrl/Cmd+Enter runs;
//   - loads examples, resets to the default program, and shares the
//     program through the URL hash (#code=<base64url>), which it also
//     reads on load and on hashchange;
//   - switches the Output / Diagnostics panes.
// Running is JavaScript's job (theme/playground.js and the compiler
// integration point, theme/playground-compiler.js): Run calls pg_run.
#include <std/wasm/wasm_rt.h>
#include <std/wasm/dom.h>
#include <std/serial/json.h>
#include <std/encoding/base64.h>
#include <std/collections/string.h>
#include <std/collections/vec.h>
#include "highlight.h"

// Provided by theme/playground.js.
extern void pg_run(const char* src, int len);       // compile + run the program
extern void pg_stop();                              // stop a running program
extern void pg_insert(const char* text, int len);   // insert at the caret (keeps undo)
extern void pg_clipboard(const char* text, int len);

struct Pg {
    int src;
    int hl;
    int gutter;
    int examples_sel;
    int status;
    int html;
    int esc_armed;          // Esc was pressed: the next Tab moves focus
    unsigned long lines;    // line count the gutter shows
    struct String default_code;
    struct Value examples;  // [{t, c}]
    struct String copied_label;
};

static struct Pg gP;

// ── rendering ────────────────────────────────────────────────────────────────

static unsigned long count_lines(const &String s) {
    unsigned long n = 1UL;
    for (unsigned long i = 0UL; i < s.len; i = i + 1UL) { if (s.char_at(i) == (int)'\n') { n = n + 1UL; } }
    return n;
}

static void sync_scroll(&Pg p) {
    double top = dom_get_prop_num(p.src, "scrollTop");
    double left = dom_get_prop_num(p.src, "scrollLeft");
    int pre = dom_parent(p.hl);
    if (pre >= 0) {
        dom_set_prop_num(pre, "scrollTop", top);
        dom_set_prop_num(pre, "scrollLeft", left);
        dom_release(pre);
    }
    dom_set_prop_num(p.gutter, "scrollTop", top);
}

static void render(&Pg p) {
    struct String code = dom_get_value(p.src);
    struct String html = String::new();
    highlight_code("c", code.as_ptr(), code.len, &html);
    html.push("\n");   // a trailing newline still gets a line box
    dom_set_html(p.hl, html.as_ptr());
    unsigned long n = count_lines(&code);
    if (n != p.lines) {
        struct String g = String::new();
        for (unsigned long i = 1UL; i <= n; i = i + 1UL) { g.push_int((long long)i); g.push_char('\n'); }
        dom_set_text(p.gutter, g.as_ptr());
        g.free();
        p.lines = n;
    }
    sync_scroll(p);
    html.free(); code.free();
}

static void set_code(&Pg p, const &char code) {
    dom_set_value(p.src, code);
    dom_set_prop_num(p.src, "scrollTop", 0.0);
    dom_set_prop_num(p.src, "selectionStart", 0.0);
    dom_set_prop_num(p.src, "selectionEnd", 0.0);
    render(p);
}

static void status(&Pg p, const &char text) {
    dom_set_text(p.status, text);
    dom_class_remove(p.status, "ok");
    dom_class_remove(p.status, "bad");
}

// ── share links ──────────────────────────────────────────────────────────────

// "#code=<base64url>" -> the program, if the hash holds one.
static int code_from_hash(&String out) {
    struct String hash = dom_location(DOM_LOC_HASH);
    int ok = 0;
    if (hash.starts_with("#code=")) {
        struct String b = hash.substr(6UL, hash.len);
        b.replace_all("-", "+");
        b.replace_all("_", "/");
        while (b.len % 4UL != 0UL) { b.push_char('='); }
        int dok = 0;
        struct Vec<unsigned char> bytes = base64_decode(b.as_ptr(), &dok);
        if (dok) {
            for (unsigned long i = 0UL; i < bytes.len; i = i + 1UL) { out.push_char((char)*bytes.at(i)); }
            ok = 1;
        }
        bytes.free();
        b.free();
    }
    hash.free();
    return ok;
}

static void share(&Pg p) {
    struct String code = dom_get_value(p.src);
    struct String enc;
    unsafe { enc = base64_encode((const &unsigned char)(const unsigned char*)code.as_ptr(), code.len); }
    struct String hash = String::from("#code=");
    for (unsigned long i = 0UL; i < enc.len; i = i + 1UL) {
        char c = (char)enc.char_at(i);
        if (c == '+') { hash.push_char('-'); }
        else if (c == '/') { hash.push_char('_'); }
        else if (c != '=') { hash.push_char(c); }
    }
    dom_history_replace(hash.as_ptr());
    struct String url = dom_location(DOM_LOC_HREF);
    unsafe { pg_clipboard(url.as_ptr(), (int)url.len); }
    status(p, p.copied_label.as_ptr());
    dom_class_add(p.status, "ok");
    url.free(); hash.free(); enc.free(); code.free();
}

// Drops a '#code=' hash (after loading an example or resetting).
static void clear_hash() {
    struct String path = dom_location(DOM_LOC_PATHNAME);
    struct String search = dom_location(DOM_LOC_SEARCH);
    path.push_str(&search);
    dom_history_replace(path.as_ptr());
    path.free(); search.free();
}

// The 'c' string of an example object.
static const &char util_c(const &Value v) {
    ?&Value c = v.object_get("c");
    match (c) { case some(x): { if (x.kind_of() == VAL_STRING) { return x.as_string(); } } case null: { } }
    return "";
}

// ── editing keys ─────────────────────────────────────────────────────────────

// Start of the line holding byte 'at'.
static unsigned long line_start(const &String s, unsigned long at) {
    while (at > 0UL && s.char_at(at - 1UL) != (int)'\n') { at = at - 1UL; }
    return at;
}

// Indents (dir > 0) or dedents (dir < 0) every line touched by [a, b).
static void shift_lines(&Pg p, unsigned long a, unsigned long b, int dir) {
    struct String s = dom_get_value(p.src);
    unsigned long first = line_start(&s, a);
    if (b > a && b > 0UL && s.char_at(b - 1UL) == (int)'\n') { b = b - 1UL; }
    struct String out = s.substr(0UL, first);
    unsigned long i = first;
    long long delta_a = 0LL;
    long long delta_total = 0LL;
    int first_line = 1;
    while (i <= s.len) {
        unsigned long e = i;
        while (e < s.len && s.char_at(e) != (int)'\n') { e = e + 1UL; }
        if (i <= b || first_line) {
            if (dir > 0) {
                out.push("    ");
                delta_total = delta_total + 4LL;
                if (first_line) { delta_a = 4LL; }
                struct String line = s.substr(i, e);
                out.push_str(&line);
                line.free();
            } else {
                unsigned long k = 0UL;
                while (k < 4UL && i + k < e && s.char_at(i + k) == (int)' ') { k = k + 1UL; }
                delta_total = delta_total - (long long)k;
                if (first_line) { delta_a = -(long long)(a - i < k ? a - i : k); }
                struct String line = s.substr(i + k, e);
                out.push_str(&line);
                line.free();
            }
        } else {
            struct String rest = s.substr(i, s.len);
            out.push_str(&rest);
            rest.free();
            break;
        }
        first_line = 0;
        if (e >= s.len) { break; }
        out.push_char('\n');
        i = e + 1UL;
    }
    dom_set_value(p.src, out.as_ptr());
    long long na = (long long)a + delta_a;
    long long nb = (long long)b + delta_total;
    if (na < (long long)first) { na = (long long)first; }
    if (nb < na) { nb = na; }
    dom_set_prop_num(p.src, "selectionStart", (double)na);
    dom_set_prop_num(p.src, "selectionEnd", (double)(a == b ? na : nb));
    render(p);
    s.free(); out.free();
}

static void run(&Pg p) {
    struct String code = dom_get_value(p.src);
    unsafe { pg_run(code.as_ptr(), (int)code.len); }
    code.free();
}

static void on_keydown(?&Pg pp, &DomEvent ev) {
    match (pp) {
        case some(p): {
            struct String key = ev.key();
            if (key.eq_cstr("Escape")) { p.esc_armed = 1; key.free(); return; }
            int armed = p.esc_armed;
            p.esc_armed = 0;
            if (key.eq_cstr("Enter") && (ev.ctrl_key() || ev.meta_key())) {
                ev.prevent_default();
                run(p);
            } else if (key.eq_cstr("Tab") && !armed && !ev.ctrl_key() && !ev.meta_key() && !ev.alt_key()) {
                ev.prevent_default();
                unsigned long a = (unsigned long)dom_get_prop_num(p.src, "selectionStart");
                unsigned long b = (unsigned long)dom_get_prop_num(p.src, "selectionEnd");
                struct String s = dom_get_value(p.src);
                int multi = 0;
                for (unsigned long i = a; i < b && i < s.len; i = i + 1UL) { if (s.char_at(i) == (int)'\n') { multi = 1; } }
                s.free();
                if (ev.shift_key() || multi) { shift_lines(p, a, b, ev.shift_key() ? -1 : 1); }
                else { unsafe { pg_insert("    ", 4); } }
            } else if (key.eq_cstr("Enter") && !ev.shift_key() && !ev.alt_key()) {
                // Keep the current line's indentation (one level more after '{').
                ev.prevent_default();
                unsigned long a = (unsigned long)dom_get_prop_num(p.src, "selectionStart");
                struct String s = dom_get_value(p.src);
                unsigned long ls = line_start(&s, a);
                struct String ins = String::from("\n");
                unsigned long k = ls;
                while (k < a && (s.char_at(k) == (int)' ' || s.char_at(k) == (int)'\t')) { ins.push_char((char)s.char_at(k)); k = k + 1UL; }
                if (a > 0UL && s.char_at(a - 1UL) == (int)'{') { ins.push("    "); }
                unsafe { pg_insert(ins.as_ptr(), (int)ins.len); }
                ins.free(); s.free();
            }
            key.free();
        }
        case null: { }
    }
}

static void on_input(?&Pg pp, &DomEvent ev) {
    match (pp) { case some(p): { render(p); } case null: { } }
}

static void on_scroll(?&Pg pp, &DomEvent ev) {
    match (pp) { case some(p): { sync_scroll(p); } case null: { } }
}

static void on_example(?&Pg pp, &DomEvent ev) {
    match (pp) {
        case some(p): {
            struct String v = dom_get_value(p.examples_sel);
            if (v.eq_cstr("default")) { set_code(p, p.default_code.as_ptr()); }
            else if (v.len > 0UL) {
                int ok = 0;
                long long i = v.parse_int(&ok);
                if (ok && i >= 0LL && (unsigned long)i < p.examples.array_len()) {
                    set_code(p, util_c(p.examples.array_at((unsigned long)i)));
                }
            }
            clear_hash();
            v.free();
        }
        case null: { }
    }
}

static void on_hash(?&Pg pp, &DomEvent ev) {
    match (pp) {
        case some(p): {
            struct String code = String::new();
            if (code_from_hash(&code)) { set_code(p, code.as_ptr()); }
            code.free();
        }
        case null: { }
    }
}

static void select_pane(const &String pane) {
    struct Vec<int> els = Vec::new();
    dom_query_all(".pg-tab, .pg-pane", &els);
    for (unsigned long i = 0UL; i < els.len; i = i + 1UL) {
        int e = *els.at(i);
        struct String d = dom_get_attr(e, "data-pane");
        int on = d.eq(pane);
        if (on) { dom_class_add(e, "active"); } else { dom_class_remove(e, "active"); }
        if (dom_class_contains(e, "pg-tab")) { dom_set_attr(e, "aria-selected", on ? "true" : "false"); }
        d.free();
        dom_release(e);
    }
    els.free();
}

static void on_click(?&Pg pp, &DomEvent ev) {
    match (pp) {
        case some(p): {
            int t = ev.target();
            if (t < 0) { return; }
            // the button itself or an element inside it (the Run icon)
            int b = t;
            struct String id = dom_get_attr(b, "id");
            if (id.len == 0UL) {
                int par = dom_parent(t);
                if (par >= 0) { id.free(); id = dom_get_attr(par, "id"); b = par; }
            }
            if (id.eq_cstr("pg-run")) { run(p); }
            else if (id.eq_cstr("pg-stop")) { unsafe { pg_stop(); } }
            else if (id.eq_cstr("pg-share")) { share(p); }
            else if (id.eq_cstr("pg-reset")) {
                set_code(p, p.default_code.as_ptr());
                clear_hash();
                dom_set_value(p.examples_sel, "");
            }
            else if (dom_class_contains(b, "pg-tab")) {
                struct String pane = dom_get_attr(b, "data-pane");
                select_pane(&pane);
                pane.free();
            }
            id.free();
            // t and b are not released: the glue hands out one handle per
            // element, so the target may be the editor's own textarea.
        }
        case null: { }
    }
}

// ── entry point ──────────────────────────────────────────────────────────────

__attribute__((export_name("pg_init"))) int pg_init() {
    gP.src = dom_get_by_id("pg-src");
    gP.hl = dom_get_by_id("pg-hl");
    gP.gutter = dom_get_by_id("pg-gutter");
    gP.examples_sel = dom_get_by_id("pg-example");
    gP.status = dom_get_by_id("pg-status");
    gP.html = dom_query("html");
    gP.esc_armed = 0;
    gP.lines = 0UL;
    if (gP.src < 0 || gP.hl < 0) { return 1; }
    int app = dom_get_by_id("playground");
    gP.copied_label = dom_get_attr(app, "data-label-copied");
    dom_release(app);
    // The examples and the default program.
    gP.default_code = String::new();
    gP.examples = Value::array();
    int data = dom_get_by_id("pg-data");
    if (data >= 0) {
        struct String json = dom_get_text(data);
        int ok = 0;
        struct Value v = json_parse(json.as_ptr(), &ok);
        if (ok) {
            ?&Value d = v.object_get("default");
            match (d) { case some(x): { gP.default_code.push(x.as_string()); } case null: { } }
            ?&Value ex = v.object_get("examples");
            match (ex) { case some(x): { gP.examples.free(); gP.examples = x.clone(); } case null: { } }
        }
        v.free();
        json.free();
        dom_release(data);
    }
    struct String shared = String::new();
    if (code_from_hash(&shared)) { dom_set_value(gP.src, shared.as_ptr()); }
    shared.free();
    unsafe {
        dom_on(gP.src, "keydown", on_keydown, (?&Pg)&gP);
        dom_on(gP.src, "input", on_input, (?&Pg)&gP);
        dom_on(gP.src, "scroll", on_scroll, (?&Pg)&gP);
        dom_on(gP.examples_sel, "change", on_example, (?&Pg)&gP);
        dom_on(DOM_WINDOW, "hashchange", on_hash, (?&Pg)&gP);
        dom_on(DOM_DOCUMENT, "click", on_click, (?&Pg)&gP);
    }
    render(&gP);
    return 0;
}
