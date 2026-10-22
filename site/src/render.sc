// safec-site — markdown -> HTML for one page (see site.h).
//
// INTERIM STRUCTURE. std/serial/markdown today covers a CommonMark subset
// (headings, paragraphs, flat lists, fences without info strings,
// blockquotes, hr, bold/italic/code/links). Until the proposed std
// markdown extensions land, this file pre-segments each page at block
// level and handles the pieces std can't yet, handing every remaining run
// of ordinary markdown to MdNode::parse/render_html:
//
//   - fenced code blocks (``` with an info string)  -> highlight.sc
//   - '::: tip|info|warning|danger|details [title]' -> custom containers
//   - GFM tables (cells rendered inline through std) -> <table>
//   - raw HTML block lines ('<a id="x"></a>')        -> passed through
//
// then post-processes the HTML for heading ids ('{#custom}' or a
// VitePress-compatible slug), header anchors, and link rewriting.
// Once std grows those features, render_page collapses to
// parse -> walk headings -> render with a link hook.
#include "site.h"
#include <std/serial/markdown.h>
#include <std/ctype.h>

// highlight.sc
void highlight_code(const &char lang, const &char code, unsigned long len, &String out);

// ── inline rendering through std ─────────────────────────────────────────────

// Renders one line/cell of inline markdown, without the <p> wrapper.
static void render_inline(const &char text, &String out) {
    &heap MdNode doc = MdNode::parse(text);
    struct String h = doc.render_html();
    doc.free();
    struct String t = h.trim();
    struct String a = t.strip_prefix("<p>");
    struct String b = a.strip_suffix("</p>");
    // Tables use <br> for in-cell line breaks; std escapes raw HTML.
    b.replace_all("&lt;br&gt;", "<br>");
    out.push_str(&b);
    h.free(); t.free(); a.free(); b.free();
}

static void render_md(const &String md, &String out) {
    if (md.len == 0UL) { return; }
    &heap MdNode doc = MdNode::parse(md.as_ptr());
    struct String h = doc.render_html();
    doc.free();
    out.push_str(&h);
    h.free();
}

// ── block segmentation ───────────────────────────────────────────────────────

// Length of the line starting at s[i] (excluding '\n').
static unsigned long line_len(const char* s, unsigned long i) {
    unsigned long j = i;
    unsafe { while (s[j] != '\0' && s[j] != '\n') { j = j + 1UL; } }
    return j - i;
}

static int line_is_blank(const char* s, unsigned long i, unsigned long n) {
    for (unsigned long k = 0UL; k < n; k = k + 1UL) {
        char c;
        unsafe { c = s[i + k]; }
        if (c != ' ' && c != '\t' && c != '\r') { return 0; }
    }
    return 1;
}

// Indent (spaces) before the first non-space byte.
static unsigned long line_indent(const char* s, unsigned long i, unsigned long n) {
    unsigned long k = 0UL;
    unsafe { while (k < n && s[i + k] == ' ') { k = k + 1UL; } }
    return k;
}

static int line_starts(const char* s, unsigned long i, unsigned long n, const char* prefix) {
    unsigned long k = 0UL;
    unsafe {
        while (prefix[k] != '\0') {
            if (k >= n || s[i + k] != prefix[k]) { return 0; }
            k = k + 1UL;
        }
    }
    return 1;
}

// A GFM delimiter row: only '|', '-', ':', spaces, with at least one '-'.
static int is_table_delim(const char* s, unsigned long i, unsigned long n) {
    int dashes = 0;
    if (n == 0UL) { return 0; }
    for (unsigned long k = 0UL; k < n; k = k + 1UL) {
        char c;
        unsafe { c = s[i + k]; }
        if (c == '-') { dashes = 1; }
        else if (c != '|' && c != ':' && c != ' ' && c != '\t' && c != '\r') { return 0; }
    }
    return dashes;
}

// Splits a table row into cells on unescaped '|' outside code spans;
// '\|' becomes '|'. Leading/trailing pipes are dropped.
static unsigned long split_row(const char* s, unsigned long i, unsigned long n,
                               &Vec<struct String> cells) {
    unsigned long a = i;
    unsigned long e = i + n;
    unsafe {
        while (a < e && (s[a] == ' ' || s[a] == '\t')) { a = a + 1UL; }
        while (e > a && (s[e - 1UL] == ' ' || s[e - 1UL] == '\t' || s[e - 1UL] == '\r')) { e = e - 1UL; }
        if (a < e && s[a] == '|') { a = a + 1UL; }
        if (e > a && s[e - 1UL] == '|' && !(e - 2UL >= a && s[e - 2UL] == '\\')) { e = e - 1UL; }
    }
    struct String cur = String::new();
    int in_code = 0;
    unsigned long k = a;
    while (k < e) {
        char c;
        unsafe { c = s[k]; }
        if (c == '`') { in_code = !in_code; cur.push_char(c); }
        else if (c == '\\' && k + 1UL < e) {
            char d;
            unsafe { d = s[k + 1UL]; }
            if (d == '|') { cur.push_char('|'); k = k + 1UL; }
            else { cur.push_char(c); }
        } else if (c == '|' && !in_code) {
            struct String t = cur.trim();
            cells.push(t);
            cur.clear();
        } else { cur.push_char(c); }
        k = k + 1UL;
    }
    struct String t = cur.trim();
    cells.push(t);
    cur.free();
    return cells.len;
}

static void free_cells(&Vec<struct String> cells) {
    for (unsigned long i = 0UL; i < cells.len; i = i + 1UL) { cells.at(i).free(); }
    cells.clear();
}

// Emits a table whose header line starts at 'hdr' and delimiter row at
// 'dl'; returns the offset just past the table's last row.
static unsigned long emit_table(const char* s, unsigned long hdr, unsigned long dl, &String out) {
    struct Vec<struct String> cells = Vec::new();
    struct Vec<int> align = Vec::new();   // 0 none, 1 left, 2 center, 3 right
    split_row(s, dl, line_len(s, dl), &cells);
    for (unsigned long c = 0UL; c < cells.len; c = c + 1UL) {
        &String d = cells.at(c);
        int l = d.starts_with(":");
        int r = d.ends_with(":");
        align.push(l && r ? 2 : (r ? 3 : (l ? 1 : 0)));
    }
    free_cells(&cells);

    out.push("<table>\n<thead>\n<tr>");
    split_row(s, hdr, line_len(s, hdr), &cells);
    for (unsigned long c = 0UL; c < cells.len; c = c + 1UL) {
        int al = 0;
        if (c < align.len) { al = *align.at(c); }
        out.push(al == 2 ? "<th style=\"text-align:center\">" : (al == 3 ? "<th style=\"text-align:right\">" : (al == 1 ? "<th style=\"text-align:left\">" : "<th>")));
        render_inline(cells.at(c).as_ptr(), out);
        out.push("</th>");
    }
    free_cells(&cells);
    out.push("</tr>\n</thead>\n<tbody>\n");

    unsigned long i = dl + line_len(s, dl);
    unsafe { if (s[i] == '\n') { i = i + 1UL; } }
    for (;;) {
        char c0;
        unsafe { c0 = s[i]; }
        if (c0 == '\0') { break; }
        unsigned long n = line_len(s, i);
        if (line_is_blank(s, i, n)) { break; }
        unsigned long ind = line_indent(s, i, n);
        unsafe { if (s[i + ind] != '|') { break; } }
        split_row(s, i, n, &cells);
        out.push("<tr>");
        for (unsigned long c = 0UL; c < cells.len; c = c + 1UL) {
            int al = 0;
            if (c < align.len) { al = *align.at(c); }
            out.push(al == 2 ? "<td style=\"text-align:center\">" : (al == 3 ? "<td style=\"text-align:right\">" : (al == 1 ? "<td style=\"text-align:left\">" : "<td>")));
            render_inline(cells.at(c).as_ptr(), out);
            out.push("</td>");
        }
        out.push("</tr>\n");
        free_cells(&cells);
        i = i + n;
        unsafe { if (s[i] == '\n') { i = i + 1UL; } }
    }
    out.push("</tbody>\n</table>\n");
    cells.free();
    align.free();
    return i;
}

// '::: kind [title]' — kind is one of tip/info/warning/danger/details.
static void emit_container_open(const char* s, unsigned long i, unsigned long n, &String out,
                                &Vec<int> open_blocks) {
    unsigned long k = i + 3UL;
    unsafe { while (k < i + n && s[k] == ' ') { k = k + 1UL; } }
    unsigned long ks = k;
    unsafe { while (k < i + n && s[k] != ' ' && s[k] != '\r') { k = k + 1UL; } }
    struct String kind;
    unsafe { kind = String::from_n(s + ks, k - ks); }
    unsafe { while (k < i + n && s[k] == ' ') { k = k + 1UL; } }
    struct String title;
    unsafe { title = String::from_n(s + k, i + n - k); }
    struct String t = title.trim();
    int details = kind.eq_cstr("details");
    if (details) {
        out.push("<details class=\"custom-block details\"><summary>");
        if (t.len > 0UL) { render_inline(t.as_ptr(), out); } else { out.push("Details"); }
        out.push("</summary>\n");
    } else {
        out.push("<div class=\"custom-block ");
        util_push_esc(out, kind.as_ptr());
        out.push("\"><p class=\"custom-block-title\">");
        if (t.len > 0UL) { render_inline(t.as_ptr(), out); }
        else {
            // VitePress's default titles: the kind, upper-cased.
            struct String up = kind.to_upper();
            out.push_str(&up);
            up.free();
        }
        out.push("</p>\n");
    }
    open_blocks.push(details);
    kind.free(); title.free(); t.free();
}

static void emit_code(const char* s, unsigned long fence_at, unsigned long fence_len,
                      unsigned long body, unsigned long body_end, &String out) {
    // Info string: the first word after the backticks.
    unsigned long k = fence_at;
    unsafe { while (s[k] == ' ') { k = k + 1UL; } while (s[k] == '`') { k = k + 1UL; } }
    unsigned long ls = k;
    unsafe { while (k < fence_at + fence_len && s[k] != ' ' && s[k] != '{' && s[k] != '\r') { k = k + 1UL; } }
    struct String lang;
    unsafe { lang = String::from_n(s + ls, k - ls); }
    if (lang.len == 0UL) { lang.push("text"); }
    out.push("<div class=\"language-");
    util_push_esc(out, lang.as_ptr());
    out.push(" code-block\"><button class=\"copy\" type=\"button\" aria-label=\"Copy code\"></button><span class=\"lang\">");
    util_push_esc(out, lang.as_ptr());
    out.push("</span><pre><code>");
    unsafe { highlight_code(lang.as_ptr(), s + body, body_end - body, out); }
    out.push("</code></pre></div>\n");
    lang.free();
}

// '#'..'######' then a space: the level, else 0.
static int heading_level(const char* s, unsigned long i, unsigned long n) {
    int lvl = 0;
    unsafe { while ((unsigned long)lvl < n && s[i + (unsigned long)lvl] == '#') { lvl = lvl + 1; } }
    if (lvl == 0 || lvl > 6 || (unsigned long)lvl >= n) { return 0; }
    char c;
    unsafe { c = s[i + (unsigned long)lvl]; }
    return c == ' ' || c == '\t' ? lvl : 0;
}

// Emits <hN>inline text {#id}</hN>; process_headings() turns the raw
// '{#id}' into the id attribute.
static void emit_heading(const char* s, unsigned long i, unsigned long n, &String out) {
    int lvl = heading_level(s, i, n);
    struct String line;
    unsafe { line = String::from_n(s + i + (unsigned long)lvl, n - (unsigned long)lvl); }
    struct String text = line.trim();
    struct String id = String::new();
    if (text.ends_with("}")) {
        long long open = text.last_index_of("{#");
        if (open >= 0LL) {
            id = text.substr((unsigned long)open, text.len);
            struct String rest = text.substr(0UL, (unsigned long)open);
            text.free();
            text = rest.trim();
            rest.free();
        }
    }
    out.push("<h"); out.push_int((long long)lvl); out.push(">");
    render_inline(text.as_ptr(), out);
    if (id.len > 0UL) { out.push(" "); out.push_str(&id); }
    out.push("</h"); out.push_int((long long)lvl); out.push(">\n");
    line.free(); text.free(); id.free();
}

static void render_blocks(const &String md, &String out) {
    const char* s = md.as_ptr();
    struct String pending = String::new();   // ordinary markdown run for std
    struct Vec<int> containers = Vec::new(); // 1 = details, 0 = div
    unsigned long i = 0UL;
    for (;;) {
        char c0;
        unsafe { c0 = s[i]; }
        if (c0 == '\0') { break; }
        unsigned long n = line_len(s, i);
        unsigned long next = i + n;
        unsafe { if (s[next] == '\n') { next = next + 1UL; } }
        unsigned long ind = line_indent(s, i, n);
        unsigned long j = i + ind;

        // Fenced code (up to 3 spaces of indent at block level).
        if (ind <= 3UL && line_starts(s, j, n - ind, "```")) {
            render_md(&pending, out); pending.clear();
            unsigned long body = next;
            unsigned long k = body;
            unsigned long end = body;
            unsigned long after = body;
            for (;;) {
                char d;
                unsafe { d = s[k]; }
                if (d == '\0') { end = k; after = k; break; }
                unsigned long m = line_len(s, k);
                unsigned long mi = line_indent(s, k, m);
                if (line_starts(s, k + mi, m - mi, "```") && line_is_blank(s, k + mi + 3UL, m - mi - 3UL)) {
                    end = k;
                    after = k + m;
                    unsafe { if (s[after] == '\n') { after = after + 1UL; } }
                    break;
                }
                k = k + m;
                unsafe { if (s[k] == '\n') { k = k + 1UL; } }
            }
            emit_code(s, i, n, body, end, out);
            i = after;
            continue;
        }
        // ATX headings: the '{#custom-id}' suffix is split off here, before
        // std's inline parser can read '__x__' in an id as emphasis.
        if (ind <= 3UL && heading_level(s, j, n - ind) > 0) {
            render_md(&pending, out); pending.clear();
            emit_heading(s, j, n - ind, out);
            i = next;
            continue;
        }
        // Custom containers.
        if (ind == 0UL && line_starts(s, i, n, ":::")) {
            render_md(&pending, out); pending.clear();
            if (line_is_blank(s, i + 3UL, n - 3UL)) {
                ?int top = containers.pop();
                match (top) {
                    case some(d): { out.push(d ? "</details>\n" : "</div>\n"); }
                    case none: { }
                }
            } else {
                emit_container_open(s, i, n, out, &containers);
            }
            i = next;
            continue;
        }
        // GFM table: a '|' row followed by a delimiter row.
        unsafe {
            if (s[j] == '|' && s[next] != '\0') {
                unsigned long n2 = line_len(s, next);
                if (is_table_delim(s, next, n2)) {
                    render_md(&pending, out); pending.clear();
                    i = emit_table(s, i, next, out);
                    continue;
                }
            }
        }
        // Raw HTML block line (only at the start of a block).
        int at_block_start = pending.len == 0UL || pending.ends_with("\n\n");
        unsafe {
            if (ind == 0UL && s[i] == '<' && at_block_start &&
                (char_is_alpha((int)s[i + 1UL]) || s[i + 1UL] == '/' || s[i + 1UL] == '!')) {
                render_md(&pending, out); pending.clear();
                out.push_n(s + i, n);
                out.push_char('\n');
                i = next;
                continue;
            }
        }
        unsafe { pending.push_n(s + i, next - i); }
        unsafe { if (s[next - 1UL] != '\n') { pending.push_char('\n'); } }
        i = next;
    }
    render_md(&pending, out);
    // Close anything left open (malformed source) so the page stays valid.
    for (;;) {
        ?int top = containers.pop();
        match (top) {
            case some(d): { out.push(d ? "</details>\n" : "</div>\n"); }
            case none: { break; }
        }
    }
    pending.free();
    containers.free();
}

// ── headings: ids, anchors, outline ──────────────────────────────────────────

// VitePress (@mdit-vue/shared) slugify, ASCII-exact; non-ASCII bytes are
// kept as-is (VitePress additionally NFKD-normalizes them — no page links
// to such a heading today, and the build's link check would catch one).
static void slugify(const &char text, &String out) {
    unsigned long start = out.len;
    int dash = 0;
    unsigned long i = 0UL;
    unsafe {
        while (text[i] != '\0') {
            unsigned char c = (unsigned char)text[i];
            int special = c <= 0x20 || (c < 0x80 && !char_is_alnum((int)c));
            if (special) { dash = 1; }
            else {
                if (dash && out.len > start) { out.push_char('-'); }
                dash = 0;
                if (c >= 'A' && c <= 'Z') { out.push_char((char)(c + 32)); } else { out.push_char((char)c); }
            }
            i = i + 1UL;
        }
    }
    // Leading digit: '_' prefix (a valid CSS identifier).
    if (out.len > start) {
        int c = out.char_at(start);
        if (c >= (int)'0' && c <= (int)'9') { out.insert(start, "_"); }
    }
}

// Strips tags and decodes the handful of entities std emits.
static void plain_text(const char* s, unsigned long n, &String out) {
    int in_tag = 0;
    for (unsigned long k = 0UL; k < n; k = k + 1UL) {
        char c;
        unsafe { c = s[k]; }
        if (c == '<') { in_tag = 1; }
        else if (c == '>' && in_tag) { in_tag = 0; }
        else if (!in_tag) { out.push_char(c); }
    }
    out.replace_all("&lt;", "<");
    out.replace_all("&gt;", ">");
    out.replace_all("&quot;", "\"");
    out.replace_all("&#39;", "'");
    out.replace_all("&amp;", "&");
}

static int heading_id_taken(const &Vec<struct Heading> hs, const &String id) {
    for (unsigned long k = 0UL; k < hs.len; k = k + 1UL) {
        if (hs.at(k).id.eq(id)) { return 1; }
    }
    return 0;
}

// Rewrites every <hN>…</hN> in 'html' to carry an id and a header anchor,
// recording (level, id, text) in page.headings.
static void process_headings(&Page page, &String html) {
    struct String out = String::with_cap(html.len + html.len / 8UL);
    const char* s = html.as_ptr();
    unsigned long i = 0UL;
    for (;;) {
        char c;
        unsafe { c = s[i]; }
        if (c == '\0') { break; }
        int lvl = 0;
        unsafe {
            if (c == '<' && s[i + 1UL] == 'h' && s[i + 2UL] >= '1' && s[i + 2UL] <= '6' && s[i + 3UL] == '>') {
                lvl = (int)(s[i + 2UL] - '0');
            }
        }
        if (lvl == 0) { out.push_char(c); i = i + 1UL; continue; }
        unsigned long body = i + 4UL;
        unsigned long close = body;
        unsafe {
            while (s[close] != '\0' && !(s[close] == '<' && s[close + 1UL] == '/' && s[close + 2UL] == 'h')) {
                close = close + 1UL;
            }
        }
        struct String inner;
        unsafe { inner = String::from_n(s + body, close - body); }
        struct String id = String::new();
        // Trailing '{#custom-id}'.
        struct String trimmed = inner.trim_right();
        if (trimmed.ends_with("}")) {
            long long open = trimmed.last_index_of("{#");
            if (open >= 0LL) {
                id = trimmed.substr((unsigned long)open + 2UL, trimmed.len - 1UL);
                struct String rest = trimmed.substr(0UL, (unsigned long)open);
                inner.free();
                inner = rest.trim_right();
                rest.free();
            }
        }
        trimmed.free();
        struct String text = String::new();
        plain_text(inner.as_ptr(), inner.len, &text);
        if (id.len == 0UL) {
            slugify(text.as_ptr(), &id);
            if (heading_id_taken(&page.headings, &id)) {
                struct String base = id.clone();
                for (long long dup = 1LL; heading_id_taken(&page.headings, &id); dup = dup + 1LL) {
                    id.clear();
                    id.push_str(&base);
                    id.push_char('-');
                    id.push_int(dup);
                }
                base.free();
            }
        }
        out.push("<h"); out.push_int((long long)lvl); out.push(" id=\"");
        util_push_esc(&out, id.as_ptr());
        out.push("\" tabindex=\"-1\">");
        out.push_str(&inner);
        out.push(" <a class=\"header-anchor\" href=\"#");
        util_push_esc(&out, id.as_ptr());
        out.push("\" aria-hidden=\"true\">#</a></h"); out.push_int((long long)lvl); out.push(">");
        if (lvl == 1 && page.title.len == 0UL) { page.title.push_str(&text); }
        struct Heading h;
        h.level = lvl;
        h.id = id;
        h.text = text;
        page.headings.push(h);
        inner.free();
        i = close + 5UL;   // past "</hN>"
    }
    html.free();
    *html = out;
}

// ── links ────────────────────────────────────────────────────────────────────

static int has_file_extension(const &Site site, const &String path) {
    long long dot = path.rfind_char('.');
    long long slash = path.rfind_char('/');
    if (dot < 0LL || dot < slash) { return 0; }
    struct String ext = path.substr((unsigned long)dot + 1UL, path.len);
    struct String list = String::from(" ");
    list.push(util_vstr(&site.cfg, "file_extensions", ""));
    list.push(" ");
    struct String needle = String::from(" ");
    needle.push_str(&ext);
    needle.push(" ");
    int hit = list.contains(needle.as_ptr()) || ext.eq_cstr("html");
    ext.free(); list.free(); needle.free();
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

// Rewrites every href="/…" in 'html'.
static void rewrite_links(const &Site site, &String html) {
    struct String out = String::with_cap(html.len + 256UL);
    const char* s = html.as_ptr();
    unsigned long i = 0UL;
    for (;;) {
        char c;
        unsafe { c = s[i]; }
        if (c == '\0') { break; }
        if (c == 'h' && line_starts(s, i, 6UL, "href=\"")) {
            out.push("href=\"");
            unsigned long a = i + 6UL;
            unsigned long e = a;
            unsafe { while (s[e] != '\0' && s[e] != '"') { e = e + 1UL; } }
            struct String target;
            unsafe { target = String::from_n(s + a, e - a); }
            // std's renderer escaped '&' in URLs; undo for rewriting, redo after.
            target.replace_all("&amp;", "&");
            struct String rewritten = String::new();
            link_rewrite(site, target.as_ptr(), &rewritten);
            util_push_esc(&out, rewritten.as_ptr());
            target.free(); rewritten.free();
            i = e;
            continue;
        }
        out.push_char(c);
        i = i + 1UL;
    }
    html.free();
    *html = out;
}

void render_page(const &Site site, &Page page) {
    struct String html = String::new();
    render_blocks(&page.body_md, &html);
    process_headings(page, &html);
    rewrite_links(site, &html);
    page.html.free();
    page.html = html;
    // Title: frontmatter 'title', else the first <h1>, else the hero name.
    const &char ft = util_vstr(&page.front, "title", "");
    unsafe {
        if (ft[0] != '\0') { page.title.clear(); page.title.push(ft); }
    }
}
