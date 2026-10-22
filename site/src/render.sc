// safec-site — markdown -> HTML for one page (see site.h), on
// std/serial/markdown: GFM + raw HTML + '::: containers' + heading ids,
// rendered through a hook that adds what a docs page needs on top of
// std's default HTML:
//   - headings: tabindex + a '#' header anchor (VitePress's shape);
//   - links: internal routes get the base prefix and '.html', external
//     links open in a new tab;
//   - code blocks: build-time syntax highlighting (highlight.sc), a
//     language label and a copy button.
// The outline (headings) and the search sections come from the same tree.
#include "site.h"
#include <std/serial/markdown.h>
#include <std/serial/html.h>

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

// ── the render hook ──────────────────────────────────────────────────────────

struct RenderCtx {
    const struct Site* site;
};

static const struct Site* ctx_site(?&RenderCtx ctx) {
    match (ctx) { case some(c): { unsafe { return c.site; } } case null: { } }
    unsafe { return (const struct Site*)0; }
}

static int render_hook(?&RenderCtx ctx, const &MdNode n, int entering, &String out) {
    const struct Site* site = ctx_site(ctx);
    if (n.kind == MD_HEADING) {
        if (entering) {
            out.push("<h"); out.push_int((long long)n.level);
            out.push(" id=\""); util_push_esc(out, n.id.as_ptr()); out.push("\" tabindex=\"-1\">");
            return 2;
        }
        out.push(" <a class=\"header-anchor\" href=\"#");
        util_push_esc(out, n.id.as_ptr());
        out.push("\" aria-hidden=\"true\">#</a></h");
        out.push_int((long long)n.level);
        out.push(">\n");
        return 1;
    }
    if (n.kind == MD_LINK && entering) {
        struct String href = String::new();
        unsafe { link_rewrite((const &Site)site, n.text.as_ptr(), &href); }
        out.push("<a href=\"");
        util_push_esc(out, href.as_ptr());
        out.push("\"");
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
        unsafe { link_rewrite((const &Site)site, n.text.as_ptr(), &src); }
        struct String alt = String::new();
        n.plain_text(&alt);
        out.push("<img src=\""); util_push_esc(out, src.as_ptr());
        out.push("\" alt=\""); util_push_esc(out, alt.as_ptr()); out.push("\">");
        src.free(); alt.free();
        return 1;
    }
    if (n.kind == MD_CODE_BLOCK && entering) {
        struct String lang = String::new();
        long long sp = n.info.find_char(' ');
        if (sp >= 0LL) { lang = n.info.substr(0UL, (unsigned long)sp); } else { lang.push_str(&n.info); }
        if (lang.len == 0UL) { lang.push("text"); }
        out.push("<div class=\"language-");
        util_push_esc(out, lang.as_ptr());
        out.push(" code-block\"><button class=\"copy\" type=\"button\" aria-label=\"Copy code\"></button><span class=\"lang\">");
        util_push_esc(out, lang.as_ptr());
        out.push("</span><pre><code>");
        highlight_code(lang.as_ptr(), n.text.as_ptr(), n.text.len, out);
        out.push("</code></pre></div>\n");
        lang.free();
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

static void collect_sections(const &MdNode doc, &Page page) {
    struct Section cur;
    cur.id = String::new();
    cur.title = String::new();
    cur.text = String::new();
    for (unsigned long i = 0UL; i < doc.children.length(); i = i + 1UL) {
        &heap MdNode b;
        unsafe { b = *doc.children.at(i); }
        if (b.kind == MD_HEADING) {
            push_section(page, &cur);
            cur.id.push_str(&b.id);
            b.plain_text(&cur.title);
            continue;
        }
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
    push_section(page, &cur);
    cur.id.free(); cur.title.free(); cur.text.free();
}

void render_page(const &Site site, &Page page) {
    struct MdOptions o = MdOptions::gfm();
    o.autolinks = 0;          // VitePress (markdown-it, linkify off) leaves bare URLs as text
    o.html = 1;
    o.containers = 1;
    o.heading_ids = 1;
    &heap MdNode doc = MdNode::parse_opts(page.body_md.as_ptr(), &o);
    struct RenderCtx ctx;
    unsafe { ctx.site = (const struct Site*)site; }
    page.html.free();
    unsafe { page.html = md_render_html_with(doc, render_hook, (?&RenderCtx)&ctx); }
    page.title.clear();
    collect_headings(doc, page);
    collect_sections(doc, page);
    doc.free();
    // Title: frontmatter 'title', else the first <h1>, else the hero name.
    struct String ft = String::from(util_vstr(&page.front, "title", ""));
    if (ft.len > 0UL) { page.title.clear(); page.title.push_str(&ft); }
    ft.free();
    if (page.title.len == 0UL) {
        ?&Value hero = page.front.object_get("hero");
        match (hero) { case some(h): { page.title.push(util_vstr(h, "name", "")); } case null: { } }
    }
}
