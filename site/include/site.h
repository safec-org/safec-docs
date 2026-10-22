// safec-site — shared declarations for the SafeC documentation generator.
//
// Pipeline:
//
//   site.yaml + locales/*.yaml ──► Site / Locale                (config.sc)
//   every .md under 'source' (std dir_walk) ──► Page            (pages.sc)
//   page markdown ──► std MdNode (GFM + raw HTML + containers + heading ids)
//     ──► HTML through a render hook: heading anchors, link rewriting,
//         highlighted code blocks; plus outline and search sections (render.sc)
//   Page + Site ──► PageView (prebuilt HTML fragments)          (view.sc)
//   PageView ──► full document via scx templates                (templates/*.scx)
//   search index, sitemap.xml, 404.html, public/ + theme copy   (main.sc)
#pragma once
#include <std/collections/string.h>
#include <std/collections/vec.h>
#include <std/serial/value.h>

// ── util.sc ──────────────────────────────────────────────────────────────────

// Writes 'data' to 'path', creating parent directories. 1 on success.
int  util_write_file(const &char path, const &String data);

// Appends 's' HTML-escaped (& < > " ') to 'out'.
void util_push_esc(&String out, const &char s);

// The string at obj[key], or 'fallback' when missing / not a string.
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
    struct String source;  // markdown root
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
void site_free(&Site site);

// ── pages.sc ─────────────────────────────────────────────────────────────────

struct Heading {
    int level;
    struct String id;
    struct String text;    // plain text, for the outline
};

// A stretch of a page between two headings, for the search index.
struct Section {
    struct String id;      // "" for the text before the first heading
    struct String title;
    struct String text;
};

struct Page {
    unsigned long locale;  // index into Site.locales
    struct String route;   // the link form: "/guide/introduction", "/stdlib/", "/"
    struct String url;     // the served path, no base: "/guide/introduction.html", "/stdlib/"
    struct String src;     // markdown file path
    struct String out;     // output file path
    struct Value  front;   // frontmatter (VAL_OBJECT, possibly empty)
    struct String body_md; // markdown after the frontmatter
    struct String html;    // rendered content
    struct String title;
    struct Vec<struct Heading> headings;
    struct Vec<struct Section> sections;
};

// Fills 'pages' with every .md file under site.source (minus site.yaml's
// 'exclude'), sorted, each assigned to the locale whose directory holds
// it. 1 on success.
int pages_discover(const &Site site, &Vec<struct Page> pages);

// Every file under site.source minus the excluded paths (relative,
// '/'-separated; top-level files first, then each directory sorted).
int source_files(const &Site site, &Vec<struct String> out);

// Splits a leading '---' YAML block off 'text' into page.front / page.body_md.
void page_split_frontmatter(&Page page, const &String text);

void pages_free(&Vec<struct Page> pages);

// ── render.sc ────────────────────────────────────────────────────────────────

// Markdown -> HTML for one page: fills html, headings, sections and title.
void render_page(const &Site site, &Page page);

// Rewrites one link target the way every href on the site is rewritten:
// internal routes get the base prefix and '.html'; external links and
// links to plain files (site.yaml 'file_extensions') are left alone.
void link_rewrite(const &Site site, const &char href, &String out);

// ── highlight.sc ─────────────────────────────────────────────────────────────

// Appends 'code' (len bytes) HTML-escaped, with <span class="tok-..."> runs
// for 'lang'.
void highlight_code(const &char lang, const &char code, unsigned long len, &String out);

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
    struct String search_placeholder;
    struct String no_results;   // search: "No results for"
    struct String copied;       // code blocks: "Copied"
    struct String theme_label;
    struct String lang_label;
    struct String return_top_label;
    struct String skip_label;
    struct String not_found_title;
    struct String not_found_quote;
    struct String not_found_link;
    struct String github;
    struct String page_class;   // "doc" | "home" | "not-found"
    struct String client_js;    // assets/site.js URL
    struct String css;          // assets/style.css URL
    struct String locale_key;   // "en" | "ko" (search index, client strings)
    struct String locale_prefix;
};

void view_build(const &Site site, const &Vec<struct Page> pages, unsigned long idx, &PageView v);
void view_build_404(const &Site site, unsigned long locale, &PageView v);
void view_free(&PageView v);

// ── templates/*.scx ──────────────────────────────────────────────────────────

struct String tpl_doc(const &PageView v);
struct String tpl_home(const &PageView v);
struct String tpl_not_found(const &PageView v);

// ── search.sc ────────────────────────────────────────────────────────────────

// Appends one record per page section to 'index' (VAL_ARRAY).
void search_add_page(const &Site site, const &Page page, &Value index);
