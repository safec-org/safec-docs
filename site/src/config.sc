// safec-site — site.yaml / locales/<key>.yaml loading (see site.h).
#include "site.h"
#include <std/serial/yaml.h>
#include <std/io.h>
#include <std/io_file.h>
#include <std/path.h>
#include <std/sys.h>

static int load_yaml(const &char path, &Value out) {
    struct String text = String::new();
    if (!File::read_to_string(path, &text)) {
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

// <root>/<rel> normalized ('rel' itself when absolute).
static struct String resolve(const &char root, const &char rel) {
    struct String j = path_join(root, rel);
    struct String n = path_normalize(j.as_ptr());
    j.free();
    return n;
}

int site_load(&Site site, const &char root, const ?&char base_override) {
    // Every field gets a value first, so a site that fails to load can
    // still be freed whole.
    site.root = String::from(root);
    site.source = String::new();
    site.public_dir = String::new();
    site.output = String::new();
    site.theme = String::new();
    site.base = String::new();
    site.cfg = Value::null();
    site.locales = Vec::new();
    site.versions = Vec::new();
    site.extra_urls = Vec::new();
    site.safec_home = String::new();
    const ?&char home = sys_getenv("SAFEC_HOME");
    match (home) { case some(h): { site.safec_home.push(h); } case null: { } }
    struct String cfg_path = path_join(root, "site.yaml");
    int ok = load_yaml(cfg_path.as_ptr(), &site.cfg);   // overwrites the null tree
    cfg_path.free();
    if (!ok) { return 0; }

    std::replace(&site.source, resolve(root, util_vstr(&site.cfg, "source", ".."))).drop();
    std::replace(&site.public_dir, resolve(root, util_vstr(&site.cfg, "public", "../public"))).drop();
    std::replace(&site.output, resolve(root, util_vstr(&site.cfg, "output", "../dist"))).drop();
    std::replace(&site.theme, resolve(root, util_vstr(&site.cfg, "theme", "theme"))).drop();
    match (base_override) {
        case some(b): { std::replace(&site.base, String::from(b)).drop(); }
        case null: { std::replace(&site.base, String::from(util_vstr(&site.cfg, "base", "/"))).drop(); }
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
                loc.data = Value::null();
                struct String rel = String::from("locales/");
                rel.push_str(&loc.key);
                rel.push(".yaml");
                struct String path = path_join(root, rel.as_ptr());
                rel.free();
                if (!load_yaml(path.as_ptr(), &loc.data)) { path.free(); loc.key.free(); loc.data.free(); return 0; }
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

void site_free(struct Site site) {
    drop(site);   // every string, tree and list it owns
}
