// safec-site client — the docs site's in-browser behavior, compiled to
// wasm32 and loaded by theme/site.js:
//
//   cd site/client && safeguard build --release --target wasm32
//   cp build/site-client ../theme/site.wasm
//
// Every page is complete server-rendered HTML that works without this
// module (nav menus open on hover/focus via CSS, links are plain links);
// the client only adds behavior.
//
// Implemented now, on today's std/wasm/dom (selector lookup, text,
// attributes, click listeners):
//   - theme toggle (html.dark), persisted via the loader
//   - mobile sidebar toggle (body.menu-open)
//
// Waiting on std/wasm's richer DOM interop (claude/web: classList,
// querySelectorAll iteration, addEventListener with event data, keyboard
// events, timers/rAF, fetch, localStorage, location/history, clipboard),
// tracked as TODO(web) below:
//   - search: fetch assets/search-<locale>.json once, tokenize + rank in
//     SafeC, render results, '/' and Ctrl/Cmd+K to open, arrows + Enter
//   - copy buttons on every code block (clipboard, "Copied" feedback)
//   - nav dropdowns on click / touch (.open) and Escape to close
//   - outline scroll-spy (IntersectionObserver-free: scroll + rAF)
//   - language switch: remember the choice, redirect on first visit
//   - 404: swap in the visitor's locale strings
#include <std/wasm/wasm_rt.h>
#include <std/wasm/dom.h>

// Provided by theme/site.js (until std/wasm grows localStorage access).
extern void site_store_theme(int dark);

struct ClientState {
    int dark;
    int menu_open;
    int html;   // element handles
    int body;
};

static struct ClientState gState;

static void apply_theme(?&ClientState st) {
    match (st) {
        case some(s): {
            dom_set_attr(s.html, "class", s.dark ? "dark" : "");
            unsafe { site_store_theme(s.dark); }
        }
        case null: { }
    }
}

static void on_theme(?&ClientState st) {
    match (st) {
        case some(s): { s.dark = !s.dark; apply_theme(st); }
        case null: { }
    }
}

static void on_menu(?&ClientState st) {
    match (st) {
        case some(s): {
            s.menu_open = !s.menu_open;
            // TODO(web): classList.toggle — today's API can only replace the
            // whole attribute, so the body's layout class is rewritten too.
            dom_set_attr(s.body, "data-menu", s.menu_open ? "open" : "closed");
        }
        case null: { }
    }
}

// Called by theme/site.js once the module is instantiated. 'dark' is the
// theme the inline boot script already applied before first paint.
__attribute__((export_name)) int site_init(int dark) {
    gState.dark = dark;
    gState.menu_open = 0;
    gState.html = dom_get_element("html");
    gState.body = dom_get_element("body");
    unsafe {
        dom_on_click("[data-action=theme]", on_theme, (?&ClientState)&gState);
        dom_on_click(".menu-toggle", on_menu, (?&ClientState)&gState);
    }
    // TODO(web): search, copy buttons, nav dropdowns, scroll-spy, locale memory.
    return 0;
}
