// safec-site — page discovery and frontmatter (see site.h).
#include "site.h"
#include <std/serial/yaml.h>
#include <std/io.h>
#include <std/io_file.h>
#include <std/dir.h>
#include <std/path.h>

void page_split_frontmatter(&Page page, const &String text) {
    page.front = Value::object();
    if (!text.starts_with("---\n") && !text.starts_with("---\r\n")) {
        page.body_md = text.clone();
        return;
    }
    struct String rest = text.substr(text.starts_with("---\r\n") ? 5UL : 4UL, text.len);
    long long end = rest.index_of("\n---");
    if (end < 0LL) {
        rest.free();
        page.body_md = text.clone();
        return;
    }
    struct String yaml = rest.substr(0UL, (unsigned long)end + 1UL);
    unsigned long body_at = (unsigned long)end + 4UL;
    while (body_at < rest.len && rest.char_at(body_at) != (int)'\n') { body_at = body_at + 1UL; }
    page.body_md = rest.substr(body_at, rest.len);
    int ok = 0;
    struct Value fm = yaml_parse(yaml.as_ptr(), &ok);
    if (ok && fm.kind_of() == VAL_OBJECT) {
        std::replace(&page.front, fm).drop();
    } else {
        fm.free();
        eprint("safec-site: ignoring malformed frontmatter in ");
        eprintln(page.src.as_ptr());
    }
    yaml.free();
    rest.free();
}

// Is 'rel' (a path under source) excluded by site.yaml's 'exclude' list?
static int excluded(const &Site site, const &String rel) {
    ?&Value ex = site.cfg.object_get("exclude");
    match (ex) {
        case some(list): {
            for (unsigned long i = 0UL; i < list.array_len(); i = i + 1UL) {
                &Value e = list.array_at(i);
                if (e.kind_of() != VAL_STRING) { continue; }
                const &char x = e.as_string();
                if (rel.eq_cstr(x)) { return 1; }
                struct String dir = String::from(x);
                dir.push_char('/');
                int under = rel.starts_with(dir.as_ptr());
                dir.free();
                if (under) { return 1; }
            }
        }
        case null: { }
    }
    // Dot-directories (.github, .vitepress, ...) are never pages.
    return rel.starts_with(".") || rel.contains("/.");
}

// The locale whose directory holds 'rel' (the root locale otherwise).
static unsigned long locale_of(const &Site site, const &String rel) {
    for (unsigned long i = 0UL; i < site.locales.len; i = i + 1UL) {
        &Locale l = site.locales.at(i);
        if (l.dir.len == 0UL) { continue; }
        struct String d = l.dir.clone();
        d.push_char('/');
        int in = rel.starts_with(d.as_ptr());
        d.free();
        if (in) { return i; }
    }
    return 0UL;
}

int source_files(const &Site site, &Vec<struct String> out) {
    // Top level by hand, so excluded trees (dist/, node_modules/, .git/)
    // are never walked at all; dir_walk below each kept directory.
    ?&Dir d = Dir::open(site.source.as_ptr());
    match (d) {
        case some(h): {
            struct DirEntry e = DirEntry::new();
            while (h.next(&e)) {
                if (excluded(site, &e.name)) { continue; }
                if (e.kind == DIR_DIR) {
                    struct String sub = path_join(site.source.as_ptr(), e.name.as_ptr());
                    struct Vec<struct String> inner = Vec::new();
                    dir_walk(sub.as_ptr(), &inner);
                    for (unsigned long i = 0UL; i < inner.len; i = i + 1UL) {
                        struct String rel = path_join(e.name.as_ptr(), inner.at(i).as_ptr());
                        if (!excluded(site, &rel)) { out.push(rel); } else { rel.free(); }
                    }
                    inner.free();
                    sub.free();
                } else {
                    out.push(e.name.clone());
                }
            }
            e.free();
            h.close();
            return 1;
        }
        case null: { }
    }
    eprint("safec-site: cannot list ");
    eprintln(site.source.as_ptr());
    return 0;
}

struct Page page_new(const &Site site, const &char rel_c) {
    struct String rel = String::from(rel_c);
    struct Page p;
    p.locale = locale_of(site, &rel);
    // "guide/x.md" -> "/guide/x" + "/guide/x.html"; "a/index.md" -> "/a/".
    struct String stem = rel.strip_suffix(".md");
    p.route = String::from("/");
    p.url = String::from("/");
    struct String out_rel = String::new();
    if (stem.eq_cstr("index") || stem.ends_with("/index")) {
        struct String dir = stem.strip_suffix("index");
        p.route.push_str(&dir);
        p.url.push_str(&dir);
        dir.free();
    } else {
        p.route.push_str(&stem);
        p.url.push_str(&stem);
        p.url.push(".html");
    }
    out_rel.push_str(&stem);
    out_rel.push(".html");
    stem.free();
    p.src = path_join(site.source.as_ptr(), rel.as_ptr());
    p.out = path_join(site.output.as_ptr(), out_rel.as_ptr());
    out_rel.free();
    p.front = Value::object();
    p.body_md = String::new();
    p.html = String::new();
    p.title = String::new();
    p.headings = Vec::new();
    p.sections = Vec::new();
    p.generated = 0;
    rel.free();
    return p;
}

const &char page_layout(const &Page page) {
    return util_vstr(&page.front, "layout", "doc");
}

int pages_discover(const &Site site, &Vec<struct Page> pages) {
    struct Vec<struct String> files = Vec::new();
    if (!source_files(site, &files)) { files.free(); return 0; }
    int ok = 1;
    for (unsigned long i = 0UL; i < files.len; i = i + 1UL) {
        &String rel = files.at(i);
        struct String ext = path_ext(rel.as_ptr());
        int md = ext.eq_cstr("md");
        ext.free();
        if (!md) { continue; }

        struct Page p = page_new(site, rel.as_ptr());
        struct String text = String::new();
        if (!File::read_to_string(p.src.as_ptr(), &text)) {
            eprint("safec-site: cannot read ");
            eprintln(p.src.as_ptr());
            ok = 0;
        }
        std::replace(&p.front, Value::null()).drop();     // page_split_frontmatter fills them
        std::replace(&p.body_md, String::new()).drop();
        page_split_frontmatter(&p, &text);
        text.free();
        pages.push(p);
    }
    files.free();
    return ok;
}

void pages_free(struct Vec<struct Page> pages) {
    pages.drop();   // and every page's strings, tree and lists
}
