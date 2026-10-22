// safec-site — the SafeC documentation site generator.
//
//   safec-site build [--base /safec-docs/] [--root <dir with site.yaml>]
//   safec-site check [--root DIR]          # build + fail on dead links/anchors
//   safec-site serve [--port 5173] [--root DIR]
//                                          # build with base "/", serve output/,
//                                          # rebuild when a source changes
//
// Run from site/ (the default --root is the current directory), e.g.
// 'safeguard run -- serve'.
#include "site.h"
#include <std/io.h>
#include <std/io_file.h>
#include <std/str.h>
#include <std/sys.h>
#include <std/dir.h>
#include <std/path.h>
#include <std/serial/json.h>
#include <std/http/http.h>

// ── output ───────────────────────────────────────────────────────────────────

static int write_or_report(const &char path, const &String data) {
    if (util_write_file(path, data)) { return 1; }
    eprint("safec-site: cannot write ");
    eprintln(path);
    return 0;
}

// ── dead-link check ──────────────────────────────────────────────────────────

// Every href into the site must name an emitted page, and a '#fragment'
// on it a heading id or an id="" on that page; links to other files must
// name a file under public/.
static int check_links(const &Site site, const &Vec<struct Page> pages) {
    int bad = 0;
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        unsigned long k = 0UL;
        for (;;) {
            struct String rest = p.html.substr(k, p.html.len);
            long long at = rest.index_of("href=\"");
            rest.free();
            if (at < 0LL) { break; }
            unsigned long s = k + (unsigned long)at + 6UL;
            unsigned long e = s;
            while (e < p.html.len && p.html.char_at(e) != (int)'"') { e = e + 1UL; }
            struct String target = p.html.substr(s, e);
            k = e;
            if (!target.starts_with(site.base.as_ptr())) { target.free(); continue; }
            struct String path = target.substr(site.base.len - 1UL, target.len);   // keep the leading '/'
            struct String frag = String::new();
            long long hash = path.find_char('#');
            if (hash >= 0LL) {
                frag = path.substr((unsigned long)hash + 1UL, path.len);
                path.truncate((unsigned long)hash);
            }
            int found = 0;
            for (unsigned long j = 0UL; j < pages.len; j = j + 1UL) {
                &Page q = pages.at(j);
                if (!q.url.eq(&path)) { continue; }
                found = 1;
                if (frag.len > 0UL) {
                    int has = 0;
                    for (unsigned long h = 0UL; h < q.headings.len; h = h + 1UL) {
                        if (q.headings.at(h).id.eq(&frag)) { has = 1; }
                    }
                    struct String needle = String::from("id=\"");
                    needle.push_str(&frag);
                    needle.push("\"");
                    if (!has && q.html.contains(needle.as_ptr())) { has = 1; }
                    needle.free();
                    if (!has) { found = 2; }
                }
            }
            if (found == 0) {
                struct String rel = path.strip_prefix("/");
                struct String file = path_join(site.public_dir.as_ptr(), rel.as_ptr());
                rel.free();
                if (path.eq_cstr("/") || path_kind(file.as_ptr()) == DIR_FILE) { found = 1; }
                file.free();
            }
            if (found != 1) {
                eprint(found == 2 ? "dead anchor: " : "dead link: ");
                eprint(p.src.as_ptr());
                eprint(" -> ");
                eprintln(target.as_ptr());
                bad = bad + 1;
            }
            target.free(); path.free(); frag.free();
        }
    }
    return bad;
}

// ── build ────────────────────────────────────────────────────────────────────

static void append_sitemap(const &Site site, const &Page page, &String xml) {
    xml.push("  <url><loc>");
    util_push_esc(xml, util_vstr(&site.cfg, "origin", ""));
    struct String u = String::new();
    link_rewrite(site, page.route.as_ptr(), &u);
    util_push_esc(xml, u.as_ptr());
    u.free();
    xml.push("</loc></url>\n");
}

static int build(&Site site, int check) {
    struct Vec<struct Page> pages = Vec::new();
    if (!pages_discover(site, &pages)) { pages_free(&pages); return 0; }
    int ok = 1;
    // Start from an empty output: public/ first (pages may overwrite nothing
    // there), then the theme assets, then the generated files.
    dir_remove_all(site.output.as_ptr());
    if (!dir_copy_tree(site.public_dir.as_ptr(), site.output.as_ptr())) { ok = 0; }
    struct String assets = path_join(site.output.as_ptr(), "assets");
    if (!dir_copy_tree(site.theme.as_ptr(), assets.as_ptr())) { ok = 0; }
    assets.free();

    struct String sitemap = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    struct Vec<struct Value> indexes = Vec::new();
    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) { indexes.push(Value::array()); }

    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) { render_page(site, pages.at(i)); }
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        struct PageView v;
        view_build(site, &pages, i, &v);
        struct String doc = v.page_class.eq_cstr("home") ? tpl_home(&v) : tpl_doc(&v);
        if (!write_or_report(p.out.as_ptr(), &doc)) { ok = 0; }
        doc.free();
        view_free(&v);
        search_add_page(site, p, indexes.at(p.locale));
        append_sitemap(site, p, &sitemap);
    }
    // 404.html at the root (GitHub Pages serves it for every miss), in the
    // root locale; the client swaps in the visitor's locale strings.
    struct PageView nf;
    view_build_404(site, 0UL, &nf);
    struct String nf_doc = tpl_not_found(&nf);
    struct String nf_path = path_join(site.output.as_ptr(), "404.html");
    if (!write_or_report(nf_path.as_ptr(), &nf_doc)) { ok = 0; }
    nf_doc.free(); nf_path.free(); view_free(&nf);

    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        struct String rel = String::from("assets/search-");
        rel.push_str(&site.locales.at(li).key);
        rel.push(".json");
        struct String path = path_join(site.output.as_ptr(), rel.as_ptr());
        struct String json = value_to_json(indexes.at(li));
        if (!write_or_report(path.as_ptr(), &json)) { ok = 0; }
        rel.free(); path.free(); json.free();
        indexes.at(li).free();
    }
    indexes.free();
    sitemap.push("</urlset>\n");
    struct String sm_path = path_join(site.output.as_ptr(), "sitemap.xml");
    if (!write_or_report(sm_path.as_ptr(), &sitemap)) { ok = 0; }
    sm_path.free(); sitemap.free();
    // .nojekyll: GitHub Pages must not drop files starting with '_'.
    struct String nj = path_join(site.output.as_ptr(), ".nojekyll");
    struct String empty = String::new();
    util_write_file(nj.as_ptr(), &empty);
    nj.free(); empty.free();

    if (check) {
        int bad = check_links(site, &pages);
        if (bad > 0) {
            eprint_int((long long)bad);
            eprintln(" dead link(s)");
            ok = 0;
        }
    }
    print("safec-site: ");
    print_int((long long)pages.len);
    print(" pages -> ");
    println(site.output.as_ptr());
    pages_free(&pages);
    return ok;
}

// ── serve ────────────────────────────────────────────────────────────────────
// Dev server over std/http: serves the output directory and, before an
// HTML page, rebuilds the site if any source, config or theme file changed
// since the last build (newest mtime under source/, site/locales and
// site/theme).

static struct String gServeRoot;
static struct String gSiteRoot;
static long long gBuiltAt = 0LL;

// Newest modification time among the sources and the site's own files.
static long long newest_input(const &Site site) {
    long long newest = 0LL;
    struct Vec<struct String> files = Vec::new();
    source_files(site, &files);
    for (unsigned long i = 0UL; i < files.len; i = i + 1UL) {
        struct String full = path_join(site.source.as_ptr(), files.at(i).as_ptr());
        struct FileStat st;
        if (file_stat(full.as_ptr(), &st) && st.mtime_ns > newest) { newest = st.mtime_ns; }
        full.free();
        files.at(i).free();
    }
    files.free();
    const char* extra[3] = { "site.yaml", "locales", "theme" };
    for (int k = 0; k < 3; k = k + 1) {
        struct String p;
        unsafe { p = path_join(site.root.as_ptr(), extra[k]); }
        struct Vec<struct String> more = Vec::new();
        if (path_kind(p.as_ptr()) == DIR_DIR) { dir_walk(p.as_ptr(), &more); }
        else { more.push(String::new()); }
        for (unsigned long i = 0UL; i < more.len; i = i + 1UL) {
            struct String full = path_join(p.as_ptr(), more.at(i).as_ptr());
            struct FileStat st;
            if (file_stat(full.as_ptr(), &st) && st.mtime_ns > newest) { newest = st.mtime_ns; }
            full.free();
            more.at(i).free();
        }
        more.free();
        p.free();
    }
    return newest;
}

static void rebuild_if_changed() {
    struct Site site;
    if (!site_load(&site, gSiteRoot.as_ptr(), "/")) { return; }
    long long newest = newest_input(&site);
    if (newest > gBuiltAt) {
        build(&site, 0);
        gBuiltAt = newest;
    }
    site_free(&site);
}

static const char* mime_for(const &String path) {
    if (path.ends_with(".html")) { return "text/html; charset=utf-8"; }
    if (path.ends_with(".css")) { return "text/css; charset=utf-8"; }
    if (path.ends_with(".js") || path.ends_with(".mjs")) { return "text/javascript; charset=utf-8"; }
    if (path.ends_with(".json")) { return "application/json"; }
    if (path.ends_with(".wasm")) { return "application/wasm"; }
    if (path.ends_with(".svg")) { return "image/svg+xml"; }
    if (path.ends_with(".png")) { return "image/png"; }
    if (path.ends_with(".ico")) { return "image/x-icon"; }
    if (path.ends_with(".xml")) { return "application/xml"; }
    if (path.ends_with(".webmanifest")) { return "application/manifest+json"; }
    return "text/plain; charset=utf-8";
}

static struct HttpResponse serve_handler(&HttpRequest req) {
    struct String path = req.path.clone();
    long long q = path.find_char('?');
    if (q >= 0LL) { path.truncate((unsigned long)q); }
    if (path.ends_with("/")) { path.push("index.html"); }
    else {
        struct String ext = path_ext(path.as_ptr());
        if (ext.len == 0UL) { path.push(".html"); }   // GitHub Pages serves /x as /x.html too
        ext.free();
    }
    if (path.contains("..")) { path.clear(); path.push("/404.html"); }
    if (path.ends_with(".html")) { rebuild_if_changed(); }

    struct HttpResponse resp;
    resp.status = 200;
    resp.body = String::new();
    struct String rel = path.strip_prefix("/");
    struct String file = path_join(gServeRoot.as_ptr(), rel.as_ptr());
    rel.free();
    if (!File::read_to_string(file.as_ptr(), &resp.body)) {
        resp.status = 404;
        struct String nf = path_join(gServeRoot.as_ptr(), "404.html");
        File::read_to_string(nf.as_ptr(), &resp.body);
        nf.free();
        path.clear();
        path.push("404.html");
    }
    resp.headers = String::from("Content-Type: ");
    unsafe { resp.headers.push(mime_for(&path)); }
    resp.headers.push("\r\nCache-Control: no-store");
    file.free();
    path.free();
    return resp;
}

// ── main ─────────────────────────────────────────────────────────────────────

static void usage() {
    eprintln("usage: safec-site build [--base PATH] [--root DIR]");
    eprintln("       safec-site check [--root DIR]");
    eprintln("       safec-site serve [--port N] [--root DIR]");
}

int main(int argc, char** argv) {
    if (argc < 2) { usage(); return 2; }
    // argv is the one raw-pointer boundary: copy it into Strings.
    struct Vec<struct String> args = Vec::new();
    for (int i = 0; i < argc; i = i + 1) {
        unsafe { args.push(String::from(argv[i])); }
    }
    struct String cmd = args.at(1UL).clone();
    struct String root = String::from(".");
    struct String base = String::new();
    int has_base = 0;
    int port = 5173;
    for (unsigned long i = 2UL; i < args.len; i = i + 1UL) {
        &String a = args.at(i);
        struct String val = String::new();
        if (i + 1UL < args.len) { val.push_str(args.at(i + 1UL)); }
        if (a.eq_cstr("--base")) { base.clear(); base.push_str(&val); has_base = 1; i = i + 1UL; }
        else if (a.eq_cstr("--root")) { root.clear(); root.push_str(&val); i = i + 1UL; }
        else if (a.eq_cstr("--port")) { port = sys_atoi(val.as_ptr()); i = i + 1UL; }
        else { usage(); return 2; }
        val.free();
    }

    int serve = cmd.eq_cstr("serve");
    if (serve) { base.clear(); base.push("/"); has_base = 1; }
    else if (!cmd.eq_cstr("build") && !cmd.eq_cstr("check")) { usage(); return 2; }

    struct Site site;
    const ?&char base_opt = (const ?&char)null;
    if (has_base) { base_opt = base.as_ptr(); }
    if (!site_load(&site, root.as_ptr(), base_opt)) { return 1; }
    gBuiltAt = newest_input(&site);
    int ok = build(&site, cmd.eq_cstr("check"));
    if (!serve) { return ok ? 0 : 1; }

    gServeRoot = site.output.clone();
    gSiteRoot = root.clone();
    print("safec-site: serving ");
    print(site.output.as_ptr());
    print(" at http://localhost:");
    print_int((long long)port);
    println("/");
    return http_serve((unsigned short)port, serve_handler) == 0 ? 0 : 1;
}
