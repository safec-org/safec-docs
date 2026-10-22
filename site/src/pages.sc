// safec-site — page discovery and frontmatter (see site.h).
#include "site.h"
#include <std/serial/yaml.h>
#include <std/io.h>

void route_to_paths(const &char route, &String md_rel, &String html_rel, &String url) {
    struct String r = String::from(route);
    // Drop a "#fragment" and a trailing ".html" if a link carried them.
    long long hash = r.find_char('#');
    if (hash >= 0LL) { r.truncate((unsigned long)hash); }
    if (r.ends_with(".html")) { r.truncate(r.len - 5UL); }
    if (r.ends_with("/")) {
        // "/book/" -> book/index.md, book/index.html, URL "/book/"
        md_rel.push(r.as_ptr());
        md_rel.push("index.md");
        html_rel.push(r.as_ptr());
        html_rel.push("index.html");
        url.push(r.as_ptr());
    } else {
        md_rel.push(r.as_ptr());
        md_rel.push(".md");
        html_rel.push(r.as_ptr());
        html_rel.push(".html");
        url.push(r.as_ptr());
        url.push(".html");
    }
    // md_rel / html_rel are relative to source / output: no leading '/'.
    if (md_rel.starts_with("/")) { md_rel.remove_range(0UL, 1UL); }
    if (html_rel.starts_with("/")) { html_rel.remove_range(0UL, 1UL); }
    r.free();
}

void page_split_frontmatter(&Page page, const &String text) {
    page.front = Value::object();
    if (!text.starts_with("---\n") && !text.starts_with("---\r\n")) {
        page.body_md = text.clone();
        return;
    }
    // Find the closing "\n---" line.
    struct String rest = text.substr(4UL, text.len);
    long long end = rest.index_of("\n---");
    if (end < 0LL) {
        rest.free();
        page.body_md = text.clone();
        return;
    }
    struct String yaml = rest.substr(0UL, (unsigned long)end + 1UL);
    unsigned long body_at = (unsigned long)end + 4UL;
    // Skip the rest of the closing '---' line.
    while (body_at < rest.len && rest.char_at(body_at) != (int)'\n') { body_at = body_at + 1UL; }
    page.body_md = rest.substr(body_at, rest.len);
    int ok = 0;
    struct Value fm = yaml_parse(yaml.as_ptr(), &ok);
    if (ok && fm.kind_of() == VAL_OBJECT) {
        page.front.free();
        page.front = fm;
    } else {
        fm.free();
        eprint("safec-site: ignoring malformed frontmatter in ");
        eprintln(page.src.as_ptr());
    }
    yaml.free();
    rest.free();
}

static int has_route(const &Vec<struct Page> pages, const &char route) {
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        if (p.route.eq_cstr(route)) { return 1; }
    }
    return 0;
}

static void add_route(const &Site site, unsigned long li, const &char route, &Vec<struct Page> pages) {
    if (has_route(pages, route)) { return; }
    struct Page p;
    p.locale = li;
    p.route = String::from(route);
    struct String md_rel = String::new();
    struct String html_rel = String::new();
    p.url = String::new();
    route_to_paths(route, &md_rel, &html_rel, &p.url);
    p.src = util_path_join(site.source.as_ptr(), md_rel.as_ptr());
    p.out = util_path_join(site.output.as_ptr(), html_rel.as_ptr());
    md_rel.free();
    html_rel.free();
    p.front = Value::object();
    p.body_md = String::new();
    p.html = String::new();
    p.title = String::new();
    p.headings = Vec::new();
    pages.push(p);
}

// Walks a nav/sidebar item list ({text, link?, items?}*), adding every
// internal link that belongs to this locale.
static void walk_items(const &Site site, unsigned long li, const &Value items, &Vec<struct Page> pages) {
    if (items.kind_of() != VAL_ARRAY) { return; }
    &Locale loc = site.locales.at(li);
    for (unsigned long i = 0UL; i < items.array_len(); i = i + 1UL) {
        &Value it = items.array_at(i);
        const &char link = util_vstr(it, "link", "");
        struct String l = String::from(link);
        int internal = l.starts_with("/") && l.starts_with(loc.prefix.as_ptr());
        // The root locale's prefix "/" matches every locale's links:
        // only claim those no other locale owns.
        if (internal && loc.prefix.eq_cstr("/")) {
            for (unsigned long k = 0UL; k < site.locales.len; k = k + 1UL) {
                &Locale other = site.locales.at(k);
                if (k != li && l.starts_with(other.prefix.as_ptr())) { internal = 0; }
            }
        }
        if (internal) { add_route(site, li, link, pages); }
        l.free();
        ?&Value sub = it.object_get("items");
        match (sub) { case some(s): walk_items(site, li, s, pages); case null: { } }
    }
}

int pages_discover(const &Site site, &Vec<struct Page> pages) {
    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        &Locale loc = site.locales.at(li);
        add_route(site, li, loc.prefix.as_ptr(), pages);
        ?&Value nav = loc.data.object_get("nav");
        match (nav) { case some(n): walk_items(site, li, n, pages); case null: { } }
        ?&Value sb = loc.data.object_get("sidebar");
        match (sb) {
            case some(s): {
                for (unsigned long i = 0UL; i < s.array_len(); i = i + 1UL) {
                    ?&Value groups = s.array_at(i).object_get("groups");
                    match (groups) { case some(g): walk_items(site, li, g, pages); case null: { } }
                }
            }
            case null: { }
        }
    }
    // Read every page's source now, so a missing file fails the build early.
    int ok = 1;
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        struct String text = String::new();
        if (!util_read_file(p.src.as_ptr(), &text)) {
            eprint("safec-site: missing page source ");
            eprintln(p.src.as_ptr());
            ok = 0;
        } else {
            page_split_frontmatter(p, &text);
        }
        text.free();
    }
    return ok;
}
