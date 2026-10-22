// safec-site — build-time search index.
//
// One JSON array per locale (assets/search-<key>.json), one record per
// page section — the text between two headings — so a hit can link
// straight to its anchor, the way VitePress's local search does:
//   { "u": "/safec-docs/reference/types.html#integers",
//     "p": "Types", "t": "Integer Types", "x": "plain section text…" }
// The wasm client fetches it lazily on first search and does the
// tokenizing/ranking in SafeC.
#include "site.h"

static void flush_section(const &Site site, const &Page page, const &String id, const &String title,
                          &String text, &Value index) {
    struct String trimmed = text.trim();
    if (trimmed.len == 0UL && title.len == 0UL) { trimmed.free(); text.clear(); return; }
    trimmed.replace_all("&lt;", "<");
    trimmed.replace_all("&gt;", ">");
    trimmed.replace_all("&quot;", "\"");
    trimmed.replace_all("&#39;", "'");
    trimmed.replace_all("&amp;", "&");
    struct Value rec = Value::object();
    struct String url = String::new();
    link_rewrite(site, page.route.as_ptr(), &url);
    if (id.len > 0UL) { url.push_char('#'); url.push_str(id); }
    rec.object_set("u", Value::from_str(url.as_ptr()));
    rec.object_set("p", Value::from_str(page.title.as_ptr()));
    rec.object_set("t", Value::from_str(title.len > 0UL ? title.as_ptr() : page.title.as_ptr()));
    rec.object_set("x", Value::from_str(trimmed.as_ptr()));
    index.array_push(rec);
    url.free();
    trimmed.free();
    text.clear();
}

void search_add_page(const &Site site, const &Page page, &Value index) {
    const char* s = page.html.as_ptr();
    struct String text = String::new();
    struct String id = String::new();
    struct String title = String::new();
    unsigned long hi = 0UL;          // next heading in page.headings
    int in_tag = 0;
    unsigned long i = 0UL;
    for (;;) {
        char c;
        unsafe { c = s[i]; }
        if (c == '\0') { break; }
        if (c == '<') {
            // A new heading starts a new section.
            int is_h = 0;
            unsafe { is_h = s[i + 1UL] == 'h' && s[i + 2UL] >= '1' && s[i + 2UL] <= '6' && s[i + 3UL] == ' '; }
            if (is_h && hi < page.headings.len) {
                flush_section(site, page, &id, &title, &text, index);
                &Heading h = page.headings.at(hi);
                id.clear(); id.push_str(&h.id);
                title.clear(); title.push_str(&h.text);
                hi = hi + 1UL;
                // Skip the heading element itself: its text is the title.
                unsafe { while (s[i] != '\0' && !(s[i] == '<' && s[i + 1UL] == '/' && s[i + 2UL] == 'h')) { i = i + 1UL; } }
                continue;
            }
            in_tag = 1;
            // Block boundaries become spaces so words don't run together.
            if (text.len > 0UL && !text.ends_with(" ")) { text.push_char(' '); }
        } else if (c == '>' && in_tag) {
            in_tag = 0;
        } else if (!in_tag) {
            if (c == '\n' || c == '\t') { if (!text.ends_with(" ")) { text.push_char(' '); } }
            else { text.push_char(c); }
        }
        i = i + 1UL;
    }
    flush_section(site, page, &id, &title, &text, index);
    text.free(); id.free(); title.free();
}
