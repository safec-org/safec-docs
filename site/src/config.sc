// safec-site — site.yaml / locales/<key>.yaml loading (see site.h).
#include "site.h"
#include <std/serial/yaml.h>
#include <std/io.h>

static int load_yaml(const &char path, &Value out) {
    struct String text = String::new();
    if (!util_read_file(path, &text)) {
        eprint("safec-site: cannot read ");
        eprintln(path);
        text.free();
        return 0;
    }
    int ok = 0;
    *out = yaml_parse(text.as_ptr(), &ok);
    text.free();
    if (!ok) {
        eprint("safec-site: malformed YAML in ");
        eprintln(path);
        return 0;
    }
    return 1;
}

// <root>/<rel>, or 'rel' itself when it is absolute.
static struct String resolve(const &char root, const &char rel) {
    unsafe { if (rel[0] == '/') { return String::from(rel); } }
    return util_path_join(root, rel);
}

int site_load(&Site site, const &char root, const ?&char base_override) {
    site.root = String::from(root);
    site.locales = Vec::new();
    struct String cfg_path = util_path_join(root, "site.yaml");
    int ok = load_yaml(cfg_path.as_ptr(), &site.cfg);
    cfg_path.free();
    if (!ok) { return 0; }

    site.source     = resolve(root, util_vstr(&site.cfg, "source", ".."));
    site.public_dir = resolve(root, util_vstr(&site.cfg, "public", "../public"));
    site.output     = resolve(root, util_vstr(&site.cfg, "output", "../dist"));
    site.theme      = resolve(root, util_vstr(&site.cfg, "theme", "theme"));
    match (base_override) {
        case some(b): { site.base = String::from(b); }
        case null: { site.base = String::from(util_vstr(&site.cfg, "base", "/")); }
    }
    if (!site.base.ends_with("/")) { site.base.push_char('/'); }

    ?&Value locs = site.cfg.object_get("locales");
    match (locs) {
        case some(arr): {
            unsigned long n = arr.array_len();
            for (unsigned long i = 0UL; i < n; i = i + 1UL) {
                &Value entry = arr.array_at(i);
                struct Locale loc;
                loc.key = String::from(util_vstr(entry, "key", "en"));
                struct String rel = String::from("locales/");
                rel.push_str(&loc.key);
                rel.push(".yaml");
                struct String path = util_path_join(root, rel.as_ptr());
                rel.free();
                if (!load_yaml(path.as_ptr(), &loc.data)) { path.free(); return 0; }
                path.free();
                loc.prefix = String::from(util_vstr(&loc.data, "prefix", "/"));
                // "/ko/" -> "ko"; "/" -> "".
                loc.dir = loc.prefix.substr(1UL, loc.prefix.len);
                if (loc.dir.ends_with("/")) { loc.dir.truncate(loc.dir.len - 1UL); }
                site.locales.push(loc);
            }
        }
        case null: { }
    }
    if (site.locales.len == 0UL) {
        eprintln("safec-site: site.yaml lists no locales");
        return 0;
    }
    return 1;
}
