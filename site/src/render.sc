// safec-site — markdown -> HTML for one page (see site.h), on
// std/serial/markdown: GFM + raw HTML + '::: containers' + heading ids,
// rendered through a hook that adds what a docs page needs on top of
// std's default HTML:
//   - headings: tabindex + a '#' header anchor (VitePress's shape);
//   - links: internal routes get the base prefix and '.html', external
//     links open in a new tab;
//   - code blocks: build-time syntax highlighting (highlight.sc), a
//     language label, a copy button and, for whole SafeC programs, a
//     "Run in Playground" link carrying the program in the URL hash;
//   - layout containers for the landing and section pages (below).
// The outline (headings) and the search sections come from the same tree.
//
// Layout containers (any other kind — tip, warning, details, ... — keeps
// std's custom-block rendering):
//   ::: hero              <section class="hero">
//   ::: section [mod]     a full-width band; 'alt' gives it the alternate background
//   ::: split / ::: col [title]   two columns (text | code); col titles are small labels
//   ::: compare           like split, for "C vs SafeC" pairs
//   ::: cards [N]         a grid of ::: card [icon] boxes (N columns, default 3);
//                         a card's '### [Title](link)' makes the whole card a link
//   ::: tabs / ::: tab Title      tabs (all panels shown without JavaScript)
//   ::: actions           the links inside become buttons (the first one primary)
//   ::: lead              larger intro text
// Headings inside layout containers get no '#' anchor.
#include "site.h"
#include <std/serial/markdown.h>
#include <std/serial/html.h>
#include <std/encoding/base64.h>

// ── links ────────────────────────────────────────────────────────────────────

static int has_file_extension(const &Site site, const &String path) {
    long long dot = path.rfind_char('.');
    long long slash = path.rfind_char('/');
    if (dot < 0LL || dot < slash) { return 0; }
    struct String ext = path.substr((unsigned long)dot + 1UL, path.len);
    int hit = ext.eq_cstr("html");
    ?&Value list = site.cfg.object_get("file_extensions");
    match (list) {
        case some(l): {
            for (unsigned long i = 0UL; i < l.array_len(); i = i + 1UL) {
                &Value e = l.array_at(i);
                if (e.kind_of() == VAL_STRING && ext.eq_cstr(e.as_string())) { hit = 1; }
            }
        }
        case null: { }
    }
    ext.free();
    return hit;
}

void link_rewrite(const &Site site, const &char href, &String out) {
    struct String h = String::from(href);
    if (!h.starts_with("/") || h.starts_with("//")) {
        out.push_str(&h);   // external, fragment-only, relative, mailto:, ...
        h.free();
        return;
    }
    struct String path = h.clone();
    struct String frag = String::new();
    long long hash = h.find_char('#');
    if (hash >= 0LL) {
        path.truncate((unsigned long)hash);
        frag = h.substr((unsigned long)hash, h.len);
    }
    out.push(site.base.as_ptr());
    path.remove_range(0UL, 1UL);   // base already ends in '/'
    out.push_str(&path);
    if (path.len > 0UL && !path.ends_with("/") && !has_file_extension(site, &path)) {
        out.push(".html");
    }
    out.push_str(&frag);
    h.free(); path.free(); frag.free();
}

void playground_hash(const &char code, unsigned long len, &String out) {
    struct String b;
    unsafe { b = base64_encode(((const unsigned char*)code)[0..len]); }
    out.push("code=");
    for (unsigned long i = 0UL; i < b.len; i = i + 1UL) {
        char c = (char)b.char_at(i);
        if (c == '+') { out.push_char('-'); }
        else if (c == '/') { out.push_char('_'); }
        else if (c != '=') { out.push_char(c); }
    }
    b.free();
}

// ── the render hook ──────────────────────────────────────────────────────────

struct RenderCtx {
    const struct Site* site;
    const struct Locale* loc;
    struct Value* examples;    // the locale's playground examples
    int is_book;               // collect runnable code blocks as examples
    int depth;                 // layout-container nesting
    int actions;               // inside ::: actions: links rendered so far + 1 (0 = not inside)
    int tabs_seen;             // tab groups on this page (unique ids)
    struct String h1;          // the page's first <h1> text
    struct String heading;     // the latest heading's text
};

static const &Site ctx_site(&RenderCtx c) {
    unsafe { return (const &Site)c.site; }
}

static const &Locale ctx_loc(&RenderCtx c) {
    unsafe { return (const &Locale)c.loc; }
}

static const &char label(&RenderCtx c, const &char key, const &char fallback) {
    ?&Value lv = ctx_loc(c).data.object_get("labels");
    match (lv) { case some(l): { return util_vstr(l, key, fallback); } case null: { } }
    return fallback;
}

static int render_hook(?&RenderCtx ctx, const &MdNode n, int entering, &String out);

static void render_child(&RenderCtx c, const &MdNode child, &String out) {
    struct String h;
    unsafe { h = md_render_html_with(child, render_hook, (?&RenderCtx)c); }
    out.push_str(&h);
    h.free();
}

// The plain text of a container's title ("" when it has none).
static struct String container_title(const &MdNode n) {
    struct String t = String::new();
    if (n.children.length() > 0UL) {
        &heap MdNode first;
        unsafe { first = *n.children.at(0UL); }
        if (first.kind == MD_CONTAINER_TITLE) { first.plain_text(&t); }
    }
    return t;
}

static int is_layout_kind(const &String k) {
    return k.eq_cstr("hero") || k.eq_cstr("section") || k.eq_cstr("split") || k.eq_cstr("col") ||
           k.eq_cstr("compare") || k.eq_cstr("cards") || k.eq_cstr("card") || k.eq_cstr("tabs") ||
           k.eq_cstr("tab") || k.eq_cstr("actions") || k.eq_cstr("lead");
}

// Small stroke icons (24x24, currentColor) for ::: card [icon].
static const char* icon_path(const &String name) {
    if (name.eq_cstr("shield"))   { return "<path d=\"M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6l-8-3Z\"/><path d=\"m9 12 2 2 4-4\"/>"; }
    if (name.eq_cstr("plug"))     { return "<path d=\"M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0V8ZM12 17v4\"/>"; }
    if (name.eq_cstr("clock"))    { return "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/>"; }
    if (name.eq_cstr("chip"))     { return "<rect x=\"6\" y=\"6\" width=\"12\" height=\"12\" rx=\"2\"/><path d=\"M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4\"/>"; }
    if (name.eq_cstr("flow"))     { return "<path d=\"M4 6h10a4 4 0 0 1 0 8H8a4 4 0 0 0 0 8h12\"/><path d=\"m17 19 3 3-3 3\" transform=\"translate(0 -3)\"/>"; }
    if (name.eq_cstr("layers"))   { return "<path d=\"m12 3 9 5-9 5-9-5 9-5Z\"/><path d=\"m3 13 9 5 9-5\"/>"; }
    if (name.eq_cstr("globe"))    { return "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3Z\"/>"; }
    if (name.eq_cstr("window"))   { return "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M3 9h18M7 6.5h.01M10 6.5h.01\"/>"; }
    if (name.eq_cstr("wave"))     { return "<path d=\"M2 12h2l2-6 3 12 3-14 3 16 3-10 2 2h2\"/>"; }
    if (name.eq_cstr("book"))     { return "<path d=\"M4 4h6a3 3 0 0 1 3 3v13a2 2 0 0 0-2-2H4V4ZM20 4h-6a3 3 0 0 0-3 3\"/><path d=\"M13 20a2 2 0 0 1 2-2h5V4\"/>"; }
    if (name.eq_cstr("compass"))  { return "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"m15.5 8.5-2 5-5 2 2-5 5-2Z\"/>"; }
    if (name.eq_cstr("code"))     { return "<path d=\"m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14\"/>"; }
    if (name.eq_cstr("list"))     { return "<path d=\"M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01\"/>"; }
    if (name.eq_cstr("package"))  { return "<path d=\"m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z\"/><path d=\"m4 7.5 8 4.5 8-4.5M12 12v9\"/>"; }
    if (name.eq_cstr("terminal")) { return "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"m7 9 3 3-3 3M13 15h4\"/>"; }
    if (name.eq_cstr("play"))     { return "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"m10 8 6 4-6 4V8Z\"/>"; }
    if (name.eq_cstr("gauge"))    { return "<path d=\"M4 18a9 9 0 1 1 16 0\"/><path d=\"m12 14 4-5\"/>"; }
    if (name.eq_cstr("edit"))     { return "<path d=\"M4 20h4L19 9l-4-4L4 16v4ZM13 7l4 4\"/>"; }
    if (name.eq_cstr("bug"))      { return "<rect x=\"7\" y=\"8\" width=\"10\" height=\"12\" rx=\"5\"/><path d=\"M12 8V5M9 5l1 2M15 5l-1 2M3 13h4M17 13h4M4 19l3-2M20 19l-3-2M4 7l3 2M20 7l-3 2\"/>"; }
    if (name.eq_cstr("git"))      { return "<circle cx=\"6\" cy=\"6\" r=\"2\"/><circle cx=\"6\" cy=\"18\" r=\"2\"/><circle cx=\"18\" cy=\"9\" r=\"2\"/><path d=\"M6 8v8M18 11c0 4-6 3-11 6\"/>"; }
    if (name.eq_cstr("chat"))     { return "<path d=\"M4 5h16v11H9l-5 4V5Z\"/>"; }
    if (name.eq_cstr("rss"))      { return "<path d=\"M5 5a14 14 0 0 1 14 14M5 11a8 8 0 0 1 8 8\"/><circle cx=\"6\" cy=\"18\" r=\"1\"/>"; }
    if (name.eq_cstr("heart"))    { return "<path d=\"M12 20s-8-4.6-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.4 12 20 12 20Z\"/>"; }
    if (name.eq_cstr("bolt"))     { return "<path d=\"M13 2 4 14h7l-1 8 9-12h-7l1-8Z\"/>"; }
    return "<circle cx=\"12\" cy=\"12\" r=\"4\"/>";
}

static void push_icon(&String out, const &String name) {
    out.push("<span class=\"card-icon\" aria-hidden=\"true\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">");
    unsafe { out.push(icon_path(name)); }
    out.push("</svg></span>");
}

// "macOS & Linux" -> "mac linux": which OSes a tab is for (the client
// opens the visitor's tab first).
static void tab_os(const &String title, &String out) {
    struct String t = title.to_lower();
    if (t.contains("mac")) { out.push("mac "); }
    if (t.contains("linux")) { out.push("linux "); }
    if (t.contains("windows")) { out.push("windows "); }
    t.free();
}

static void render_children_from(&RenderCtx c, const &MdNode n, unsigned long from, &String out) {
    for (unsigned long i = from; i < n.children.length(); i = i + 1UL) {
        &heap MdNode ch;
        unsafe { ch = *n.children.at(i); }
        render_child(c, ch, out);
    }
}

// Renders a layout container (and its children) — the hook's MD_CONTAINER case.
static void render_layout(&RenderCtx c, const &MdNode n, &String out) {
    const &String k = &n.info;
    struct String title = container_title(n);
    unsigned long first = 0UL;
    if (n.children.length() > 0UL) {
        &heap MdNode f;
        unsafe { f = *n.children.at(0UL); }
        if (f.kind == MD_CONTAINER_TITLE) { first = 1UL; }
    }
    c.depth = c.depth + 1;
    if (k.eq_cstr("hero")) {
        out.push("<section class=\"hero\"><div class=\"l-inner\">\n");
        render_children_from(c, n, first, out);
        out.push("</div></section>\n");
    } else if (k.eq_cstr("section")) {
        out.push("<section class=\"l-section");
        if (title.len > 0UL) { out.push(" "); util_push_esc(out, title.as_ptr()); }
        out.push("\"><div class=\"l-inner\">\n");
        render_children_from(c, n, first, out);
        out.push("</div></section>\n");
    } else if (k.eq_cstr("split") || k.eq_cstr("compare")) {
        out.push("<div class=\""); util_push_esc(out, k.as_ptr()); out.push("\">\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    } else if (k.eq_cstr("col")) {
        out.push("<div class=\"col\">");
        if (title.len > 0UL) { out.push("<p class=\"col-title\">"); util_push_esc(out, title.as_ptr()); out.push("</p>"); }
        out.push("\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    } else if (k.eq_cstr("cards")) {
        out.push("<div class=\"cards cols-");
        if (title.len > 0UL) { util_push_esc(out, title.as_ptr()); } else { out.push("3"); }
        out.push("\">\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    } else if (k.eq_cstr("card")) {
        out.push("<div class=\"card\">");
        if (title.len > 0UL) { push_icon(out, &title); }
        out.push("\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    } else if (k.eq_cstr("tabs")) {
        c.tabs_seen = c.tabs_seen + 1;
        out.push("<div class=\"tabs\" data-tabs=\"");
        out.push_int((long long)c.tabs_seen);
        out.push("\"><div class=\"tab-list\" role=\"tablist\">");
        int idx = 0;
        for (unsigned long i = first; i < n.children.length(); i = i + 1UL) {
            &heap MdNode ch;
            unsafe { ch = *n.children.at(i); }
            if (ch.kind != MD_CONTAINER || !ch.info.eq_cstr("tab")) { continue; }
            struct String t = container_title(ch);
            struct String os = String::new();
            tab_os(&t, &os);
            out.push("<button type=\"button\" role=\"tab\" class=\"tab-btn");
            if (idx == 0) { out.push(" active"); }
            out.push("\" aria-selected=\""); out.push(idx == 0 ? "true" : "false");
            out.push("\" data-tab=\""); out.push_int((long long)idx);
            out.push("\" data-os=\""); util_push_esc(out, os.as_ptr());
            out.push("\">");
            util_push_esc(out, t.as_ptr());
            out.push("</button>");
            t.free(); os.free();
            idx = idx + 1;
        }
        out.push("</div>\n");
        idx = 0;
        for (unsigned long i = first; i < n.children.length(); i = i + 1UL) {
            &heap MdNode ch;
            unsafe { ch = *n.children.at(i); }
            if (ch.kind == MD_CONTAINER && ch.info.eq_cstr("tab")) {
                struct String t = container_title(ch);
                out.push("<div class=\"tab-panel");
                if (idx == 0) { out.push(" active"); }
                out.push("\" role=\"tabpanel\" data-tab=\""); out.push_int((long long)idx); out.push("\">");
                out.push("<p class=\"tab-title\">"); util_push_esc(out, t.as_ptr()); out.push("</p>\n");
                unsigned long f2 = 0UL;
                if (ch.children.length() > 0UL) {
                    &heap MdNode tf;
                    unsafe { tf = *ch.children.at(0UL); }
                    if (tf.kind == MD_CONTAINER_TITLE) { f2 = 1UL; }
                }
                render_children_from(c, ch, f2, out);
                out.push("</div>\n");
                t.free();
                idx = idx + 1;
            } else {
                render_child(c, ch, out);
            }
        }
        out.push("</div>\n");
    } else if (k.eq_cstr("actions")) {
        out.push("<div class=\"actions\">");
        int saved = c.actions;
        c.actions = 1;
        render_children_from(c, n, first, out);
        c.actions = saved;
        out.push("</div>\n");
    } else if (k.eq_cstr("lead")) {
        out.push("<div class=\"lead\">\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    } else {
        // a stray ::: tab outside ::: tabs
        out.push("<div class=\""); util_push_esc(out, k.as_ptr()); out.push("\">\n");
        render_children_from(c, n, first, out);
        out.push("</div>\n");
    }
    c.depth = c.depth - 1;
    title.free();
}

// A whole SafeC program (something the playground can run)?
static int runnable(const &String lang, const &String info, const &String code) {
    if (info.contains("norun")) { return 0; }
    if (!(lang.eq_cstr("c") || lang.eq_cstr("sc") || lang.eq_cstr("safec"))) { return 0; }
    return code.contains("int main(") || info.contains(" run");
}

static void render_code(&RenderCtx c, const &MdNode n, &String out) {
    struct String lang = String::new();
    long long sp = n.info.find_char(' ');
    if (sp >= 0LL) { lang = n.info.substr(0UL, (unsigned long)sp); } else { lang.push_str(&n.info); }
    if (lang.len == 0UL) { lang.push("text"); }
    out.push("<div class=\"language-");
    util_push_esc(out, lang.as_ptr());
    out.push(" code-block\"><button class=\"copy\" type=\"button\" aria-label=\"");
    util_push_esc(out, label(c, "copy", "Copy code"));
    out.push("\"></button><span class=\"lang\">");
    util_push_esc(out, lang.as_ptr());
    out.push("</span><pre><code>");
    highlight_code(lang.as_ptr(), n.text.as_ptr(), n.text.len, out);
    out.push("</code></pre>");
    if (runnable(&lang, &n.info, &n.text)) {
        struct String route = ctx_loc(c).prefix.clone();
        route.push("playground");
        struct String href = String::new();
        link_rewrite(ctx_site(c), route.as_ptr(), &href);
        href.push_char('#');
        playground_hash(n.text.as_ptr(), n.text.len, &href);
        out.push("<a class=\"run-link\" href=\"");
        util_push_esc(out, href.as_ptr());
        out.push("\">");
        out.push("<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M8 5v14l11-7L8 5Z\"/></svg>");
        util_push_esc(out, label(c, "runInPlayground", "Run in Playground"));
        out.push("</a>");
        route.free(); href.free();
        if (c.is_book) {
            struct Value ex = Value::object();
            struct String t = c.h1.clone();
            if (c.heading.len > 0UL && !c.heading.eq(&c.h1)) {
                if (t.len > 0UL) { t.push(" · "); }
                t.push_str(&c.heading);
            }
            ex.object_set("t", Value::from_str(t.as_ptr()));
            ex.object_set("c", Value::from_str(n.text.as_ptr()));
            unsafe { c.examples->array_push(ex); }
            t.free();
        }
    }
    out.push("</div>\n");
    lang.free();
}

static int hook_impl(&RenderCtx c, const &MdNode n, int entering, &String out);

static int render_hook(?&RenderCtx ctx, const &MdNode n, int entering, &String out) {
    match (ctx) { case some(c): { return hook_impl(c, n, entering, out); } case null: { } }
    return 0;
}

static int hook_impl(&RenderCtx c, const &MdNode n, int entering, &String out) {
    const &Site site = ctx_site(c);
    if (n.kind == MD_HEADING) {
        if (entering) {
            c.heading.clear();
            n.plain_text(&c.heading);
            if (n.level == 1 && c.h1.len == 0UL) { c.h1.push_str(&c.heading); }
            out.push("<h"); out.push_int((long long)n.level);
            out.push(" id=\""); util_push_esc(out, n.id.as_ptr()); out.push("\" tabindex=\"-1\">");
            return 2;
        }
        if (c.depth == 0) {
            out.push(" <a class=\"header-anchor\" href=\"#");
            util_push_esc(out, n.id.as_ptr());
            out.push("\" aria-hidden=\"true\">#</a>");
        }
        out.push("</h");
        out.push_int((long long)n.level);
        out.push(">\n");
        return 1;
    }
    if (n.kind == MD_CONTAINER && entering && is_layout_kind(&n.info)) {
        render_layout(c, n, out);
        return 1;
    }
    if (n.kind == MD_LINK && entering) {
        struct String href = String::new();
        link_rewrite(site, n.text.as_ptr(), &href);
        out.push("<a href=\"");
        util_push_esc(out, href.as_ptr());
        out.push("\"");
        if (c.actions > 0) {
            out.push(c.actions == 1 ? " class=\"button brand\"" : " class=\"button alt\"");
            c.actions = c.actions + 1;
        }
        if (n.title.len > 0UL) { out.push(" title=\""); util_push_esc(out, n.title.as_ptr()); out.push("\""); }
        if (n.text.starts_with("http://") || n.text.starts_with("https://")) {
            out.push(" target=\"_blank\" rel=\"noreferrer\"");
        }
        out.push(">");
        href.free();
        return 2;
    }
    if (n.kind == MD_IMAGE && entering && n.text.starts_with("/")) {
        struct String src = String::new();
        link_rewrite(site, n.text.as_ptr(), &src);
        struct String alt = String::new();
        n.plain_text(&alt);
        out.push("<img src=\""); util_push_esc(out, src.as_ptr());
        out.push("\" alt=\""); util_push_esc(out, alt.as_ptr()); out.push("\" loading=\"lazy\">");
        src.free(); alt.free();
        return 1;
    }
    if (n.kind == MD_CODE_BLOCK && entering) {
        render_code(c, n, out);
        return 1;
    }
    return 0;
}

// ── outline and search sections ──────────────────────────────────────────────

static void collect_headings(const &MdNode n, &Page page) {
    if (n.kind == MD_HEADING) {
        struct Heading h;
        h.level = n.level;
        h.id = n.id.clone();
        h.text = String::new();
        n.plain_text(&h.text);
        if (h.level == 1 && page.title.len == 0UL) { page.title.push_str(&h.text); }
        page.headings.push(h);
        return;
    }
    for (unsigned long i = 0UL; i < n.children.length(); i = i + 1UL) {
        &heap MdNode c;
        unsafe { c = *n.children.at(i); }
        collect_headings(c, page);
    }
}

static void push_section(&Page page, &Section cur) {
    struct String t = cur.text.trim();
    if (t.len > 0UL || cur.title.len > 0UL) {
        struct Section s;
        s.id = cur.id.clone();
        s.title = cur.title.clone();
        s.text = t;
        page.sections.push(s);
    } else {
        t.free();
    }
    cur.id.clear(); cur.title.clear(); cur.text.clear();
}

// One search section per heading, in document order, through layout
// containers too (a landing page's sections are searchable).
static void walk_sections(const &MdNode parent, &Page page, &Section cur) {
    for (unsigned long i = 0UL; i < parent.children.length(); i = i + 1UL) {
        &heap MdNode b;
        unsafe { b = *parent.children.at(i); }
        if (b.kind == MD_HEADING) {
            push_section(page, cur);
            cur.id.push_str(&b.id);
            b.plain_text(&cur.title);
            continue;
        }
        if (b.kind == MD_CONTAINER && is_layout_kind(&b.info)) {
            walk_sections(b, page, cur);
            continue;
        }
        if (b.kind == MD_CONTAINER_TITLE) { continue; }
        if (cur.text.len > 0UL) { cur.text.push_char(' '); }
        if (b.kind == MD_HTML_BLOCK) {
            struct String tags = String::new();
            html_strip_tags(&tags, b.text.as_ptr());
            html_unescape(&cur.text, tags.as_ptr());
            tags.free();
        } else {
            b.plain_text(&cur.text);
        }
    }
}

static void collect_sections(const &MdNode doc, &Page page) {
    struct Section cur;
    cur.id = String::new();
    cur.title = String::new();
    cur.text = String::new();
    walk_sections(doc, page, &cur);
    push_section(page, &cur);
    cur.id.free(); cur.title.free(); cur.text.free();
}

void render_page(const &Site site, &Page page, &Value examples) {
    struct MdOptions o = MdOptions::gfm();
    o.autolinks = 0;          // VitePress (markdown-it, linkify off) leaves bare URLs as text
    o.html = 1;
    o.containers = 1;
    o.heading_ids = 1;
    &heap MdNode doc = MdNode::parse_opts(page.body_md.as_ptr(), &o);
    &Locale loc = site.locales.at(page.locale);
    struct RenderCtx ctx;
    unsafe {
        ctx.site = (const struct Site*)site;
        ctx.loc = (const struct Locale*)loc;
        ctx.examples = (struct Value*)examples;
    }
    struct String book = loc.prefix.clone();
    book.push("book/");
    ctx.is_book = page.route.starts_with(book.as_ptr()) && !page.generated;
    book.free();
    ctx.depth = 0;
    ctx.actions = 0;
    ctx.tabs_seen = 0;
    ctx.h1 = String::new();
    ctx.heading = String::new();
    page.html.free();
    unsafe { page.html = md_render_html_with(doc, render_hook, (?&RenderCtx)&ctx); }
    ctx.h1.free(); ctx.heading.free();
    page.title.clear();
    collect_headings(doc, page);
    collect_sections(doc, page);
    doc.free();
    // Title: frontmatter 'title', else the first <h1>.
    struct String ft = String::from(util_vstr(&page.front, "title", ""));
    if (ft.len > 0UL) { page.title.clear(); page.title.push_str(&ft); }
    ft.free();
}
