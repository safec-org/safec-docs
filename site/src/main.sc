// safec-site — the SafeC documentation site generator.
//
//   safeguard run -- build [--base /safec-docs/] [--root <dir with site.yaml>]
//   safeguard run -- serve [--port 5173]     # build with base "/", then serve dist/
//   safeguard run -- check                   # build + fail on dead internal links
//
// Run from site/ (the default --root is the current directory).
#include "site.h"
#include <std/io.h>
#include <std/str.h>
#include <std/sys.h>
#include <std/serial/json.h>
#include <std/http/http.h>

// ── output ───────────────────────────────────────────────────────────────────

static int write_or_report(const &char path, const &String data) {
    if (util_write_file(path, data)) { return 1; }
    eprint("safec-site: cannot write ");
    eprintln(path);
    return 0;
}

static int copy_theme(const &Site site) {
    // theme/ -> <output>/assets/ (style.css, site.js, site.wasm).
    struct String assets = util_path_join(site.output.as_ptr(), "assets");
    int ok = util_copy_tree(site.theme.as_ptr(), assets.as_ptr());
    assets.free();
    return ok;
}

// ── dead-link check ──────────────────────────────────────────────────────────

// Every href into the site must name an emitted page, and a '#fragment'
// on it must name a heading id or <a id> on that page. Plain files
// (public/…) are checked for existence on disk.
static int check_links(const &Site site, const &Vec<struct Page> pages) {
    int bad = 0;
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        const char* s = p.html.as_ptr();
        unsigned long k = 0UL;
        for (;;) {
            const ?&char hit;
            unsafe { hit = str_find((const char*)(s + k), "href=\""); }
            unsigned long at = 0UL;
            match (hit) {
                case some(h): { unsafe { at = (unsigned long)(h - s) + 6UL; } }
                case null: { break; }
            }
            unsigned long e = at;
            unsafe { while (s[e] != '\0' && s[e] != '"') { e = e + 1UL; } }
            struct String target;
            unsafe { target = String::from_n(s + at, e - at); }
            k = e;
            if (!target.starts_with(site.base.as_ptr())) { target.free(); continue; }
            struct String path = target.substr(site.base.len - 1UL, target.len);  // keep leading '/'
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
                // Not a page: a file under public/?
                struct String file = util_path_join(site.public_dir.as_ptr(), path.as_ptr());
                struct String tmp = String::new();
                if (path.eq_cstr("/") || util_read_file(file.as_ptr(), &tmp)) { found = 1; }
                file.free(); tmp.free();
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

static int build(&Site site, &Vec<struct Page> pages, int check) {
    if (!pages_discover(site, pages)) { return 0; }
    int ok = 1;
    struct String sitemap = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    struct Vec<struct Value> indexes = Vec::new();
    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) { indexes.push(Value::array()); }

    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) { render_page(site, pages.at(i)); }
    for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
        &Page p = pages.at(i);
        struct PageView v;
        view_build(site, pages, i, &v);
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
    struct String nf_path = util_path_join(site.output.as_ptr(), "404.html");
    if (!write_or_report(nf_path.as_ptr(), &nf_doc)) { ok = 0; }
    nf_doc.free(); nf_path.free(); view_free(&nf);

    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        struct String rel = String::from("assets/search-");
        rel.push_str(&site.locales.at(li).key);
        rel.push(".json");
        struct String path = util_path_join(site.output.as_ptr(), rel.as_ptr());
        struct String json = value_to_json(indexes.at(li));
        if (!write_or_report(path.as_ptr(), &json)) { ok = 0; }
        rel.free(); path.free(); json.free();
        indexes.at(li).free();
    }
    indexes.free();
    sitemap.push("</urlset>\n");
    struct String sm_path = util_path_join(site.output.as_ptr(), "sitemap.xml");
    if (!write_or_report(sm_path.as_ptr(), &sitemap)) { ok = 0; }
    sm_path.free(); sitemap.free();

    // public/ verbatim, then the theme assets.
    if (!util_copy_tree(site.public_dir.as_ptr(), site.output.as_ptr())) { ok = 0; }
    if (!copy_theme(site)) { ok = 0; }
    // .nojekyll: GitHub Pages must not drop files starting with '_'.
    struct String nj = util_path_join(site.output.as_ptr(), ".nojekyll");
    struct String empty = String::new();
    util_write_file(nj.as_ptr(), &empty);
    nj.free(); empty.free();

    if (check) {
        int bad = check_links(site, pages);
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
    return ok;
}

// ── serve ────────────────────────────────────────────────────────────────────
// Dev server: serves the built output directory. Every request for an HTML
// page rebuilds the site first, so edits show up on reload.
// TODO(std web): MIME/ETag/Range via std/http's static file serving and
// live reload over SSE once those land on claude/web.

static struct String gServeRoot;
static struct String gSiteRoot;

static const char* mime_for(const &String path) {
    if (path.ends_with(".html")) { return "text/html; charset=utf-8"; }
    if (path.ends_with(".css")) { return "text/css; charset=utf-8"; }
    if (path.ends_with(".js")) { return "text/javascript; charset=utf-8"; }
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
    else if (path.rfind_char('.') < path.rfind_char('/')) { path.push(".html"); }
    if (path.contains("..")) { path.clear(); path.push("/404.html"); }

    if (path.ends_with(".html")) {
        struct Site site;
        struct Vec<struct Page> pages = Vec::new();
        if (site_load(&site, gSiteRoot.as_ptr(), "/")) { build(&site, &pages, 0); }
    }

    struct HttpResponse resp;
    resp.status = 200;
    resp.body = String::new();
    struct String file = util_path_join(gServeRoot.as_ptr(), path.as_ptr());
    if (!util_read_file(file.as_ptr(), &resp.body)) {
        resp.status = 404;
        struct String nf = util_path_join(gServeRoot.as_ptr(), "404.html");
        util_read_file(nf.as_ptr(), &resp.body);
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
    eprintln("       safec-site serve [--port N] [--root DIR]");
    eprintln("       safec-site check [--root DIR]");
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
    struct Vec<struct Page> pages = Vec::new();
    int ok = build(&site, &pages, cmd.eq_cstr("check"));
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
