// safec-site — shared declarations for the SafeC documentation generator.
//
// Pipeline:
//
//   site.yaml + locales/*.yaml ──► Site / Locale          (config.sc)
//   pages discovered per locale ──► Page                   (pages.sc)
//   page markdown ──► HTML + heading list                  (render.sc)
//   Page + Site ──► PageView (prebuilt HTML fragments)     (view.sc)
//   PageView ──► full document via scx templates           (templates/*.scx)
//   search index, sitemap.xml, 404.html, public/ copy      (main.sc)
//
// Anything here that is really a general-purpose utility (file I/O on
// whole files, directory creation, path joining) is marked 'STD-CANDIDATE':
// it lives in the site for now and moves to std once the std additions
// proposed for this project are approved.
#pragma once
#include <std/collections/string.h>
#include <std/collections/vec.h>
#include <std/serial/value.h>

// ── util.sc ──────────────────────────────────────────────────────────────────

// STD-CANDIDATE (std/io_file: File::read_all / File::write_all).
// Reads the whole file at 'path' into 'out' (appending). 1 on success.
int  util_read_file(const &char path, &String out);
// Writes 'data' to 'path' (truncating), creating parent directories. 1 on success.
int  util_write_file(const &char path, const &String data);

// STD-CANDIDATE (std/dir: dir_create_all). Interim: shells out to mkdir -p.
int  util_mkdir_p(const &char path);

// STD-CANDIDATE (std/dir: dir_copy_tree). Interim: shells out to cp -R.
int  util_copy_tree(const &char from, const &char to);

// STD-CANDIDATE (std/path: path_join). 'a' + '/' + 'b' with exactly one '/'.
struct String util_path_join(const &char a, const &char b);
// STD-CANDIDATE (std/path: path_dirname). Everything before the last '/'.
struct String util_dirname(const &char p);

// Appends 's' HTML-escaped (& < > " ') to 'out'.
void util_push_esc(&String out, const &char s);

// Value helpers: the string at obj[key], or 'fallback' when missing / not a string.
const &char util_vstr(const &Value obj, const &char key, const &char fallback);

// ── config.sc ────────────────────────────────────────────────────────────────

struct Locale {
    struct String key;     // "en", "ko" — names locales/<key>.yaml
    struct String prefix;  // "/" or "/ko/"
    struct String dir;     // markdown subdirectory under 'source': "" or "ko"
    struct Value  data;    // the whole locales/<key>.yaml tree
};

struct Site {
    struct String root;    // directory holding site.yaml
    struct String source;  // markdown root (absolute or relative to cwd)
    struct String public_dir;
    struct String output;
    struct String theme;
    struct String base;    // "/safec-docs/"
    struct Value  cfg;     // the whole site.yaml tree
    struct Vec<struct Locale> locales;
};

// Loads <root>/site.yaml and every locales/<key>.yaml it names.
// 'base_override' (nullable) replaces site.yaml's 'base'. 1 on success.
int site_load(&Site site, const &char root, const ?&char base_override);

// ── pages.sc ─────────────────────────────────────────────────────────────────

struct Heading {
    int level;
    struct String id;
    struct String text;    // plain text (tags stripped), for TOC + search
};

struct Page {
    unsigned long locale;  // index into Site.locales
    struct String url;     // site-relative, no base: "/guide/introduction.html", "/stdlib/"
    struct String route;   // the link form used in config/markdown: "/guide/introduction", "/stdlib/"
    struct String src;     // markdown file path
    struct String out;     // output file path
    struct Value  front;   // frontmatter (VAL_OBJECT, possibly empty)
    struct String body_md; // markdown after the frontmatter
    struct String html;    // rendered content
    struct String title;
    struct Vec<struct Heading> headings;
};

// Fills 'pages' for every locale: the locale home plus every internal link
// in its nav and sidebar (interim — becomes a directory walk over 'source'
// once std/dir lands, with nav/sidebar then only ordering, not discovery).
int pages_discover(const &Site site, &Vec<struct Page> pages);

// Splits a leading '---' YAML block off 'text' into page.front / page.body_md.
void page_split_frontmatter(&Page page, const &String text);

// Maps a route ("/guide/introduction", "/book/", "/") to its markdown path
// relative to 'source' and its output path relative to 'output'.
void route_to_paths(const &char route, &String md_rel, &String html_rel, &String url);

// ── render.sc ────────────────────────────────────────────────────────────────

// Markdown -> HTML for one page, filling page.html, page.headings, page.title.
void render_page(const &Site site, &Page page);

// Rewrites one link target the way every href on the site is rewritten:
// internal routes get the base prefix and '.html'; external links and
// links to plain files (site.yaml 'file_extensions') are left alone.
void link_rewrite(const &Site site, const &char href, &String out);

// ── view.sc ──────────────────────────────────────────────────────────────────

// Everything a template needs, as finished strings: templates only
// interpolate ({x} escaped, {!x} raw), they never loop or branch.
struct PageView {
    struct String lang;
    struct String title;        // <title> text
    struct String description;
    struct String base;
    struct String head_extra;   // raw: icons, manifest, canonical
    struct String home_href;
    struct String logo_src;
    struct String site_title;
    struct String nav;          // raw: header nav
    struct String locale_menu;  // raw: language switch
    struct String sidebar;      // raw
    struct String toc;          // raw: "on this page"
    struct String content;      // raw: rendered markdown
    struct String prev_next;    // raw
    struct String hero;         // raw (home layout only)
    struct String features;     // raw (home layout only)
    struct String footer_message;
    struct String footer_copyright;
    struct String outline_label;
    struct String menu_label;
    struct String search_label;
    struct String theme_label;
    struct String return_top_label;
    struct String skip_label;
    struct String not_found_title;
    struct String not_found_quote;
    struct String not_found_link;
    struct String github;
    struct String page_class;   // "doc" | "home" | "not-found"
    struct String client_js;    // theme/site.js URL
};

void view_build(const &Site site, const &Vec<struct Page> pages, unsigned long idx, &PageView v);
void view_build_404(const &Site site, unsigned long locale, &PageView v);
void view_free(&PageView v);

// ── templates/*.scx ──────────────────────────────────────────────────────────

struct String tpl_doc(const &PageView v);
struct String tpl_home(const &PageView v);
struct String tpl_not_found(const &PageView v);

// ── search.sc ────────────────────────────────────────────────────────────────

// Appends one record per page section (heading) to 'index' (VAL_ARRAY).
void search_add_page(const &Site site, const &Page page, &Value index);
