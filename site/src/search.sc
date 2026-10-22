// safec-site — build-time search index.
//
// One JSON array per locale (assets/search-<key>.json), one record per
// page section — the text between two headings — so a hit can link
// straight to its anchor, the way VitePress's local search does:
//   { "u": "/safec-docs/reference/types.html#integers",
//     "p": "Types", "t": "Integer Types", "x": "plain section text…" }
// The wasm client fetches it on first search and does the tokenizing and
// ranking in SafeC.
#include "site.h"

void search_add_page(const &Site site, const &Page page, &Value index) {
    struct String url = String::new();
    link_rewrite(site, page.route.as_ptr(), &url);
    for (unsigned long i = 0UL; i < page.sections.len; i = i + 1UL) {
        &Section s = page.sections.at(i);
        struct Value rec = Value::object();
        struct String u = url.clone();
        if (s.id.len > 0UL) { u.push_char('#'); u.push_str(&s.id); }
        rec.object_set("u", Value::from_str(u.as_ptr()));
        rec.object_set("p", Value::from_str(page.title.as_ptr()));
        rec.object_set("t", Value::from_str(s.title.len > 0UL ? s.title.as_ptr() : page.title.as_ptr()));
        rec.object_set("x", Value::from_str(s.text.as_ptr()));
        index.array_push(rec);
        u.free();
    }
    url.free();
}
