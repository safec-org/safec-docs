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
//
// Generated pages (no .md file of their own) join the page list before
// rendering, so links to them are checked and they are searchable:
//   release notes + changelog, from SafeC's CHANGELOG.md and its
//     release history (data/releases.json)                       (releases.sc)
//   blog index pages (paginated), tag pages, Atom + RSS feeds     (blog.sc)
//   redirect pages for moved/alias URLs (site.yaml 'redirects')   (main.sc)
#pragma once
#include <std/collections/string.h>
#include <std/collections/vec.h>
#include <std/serial/value.h>

// ── util.sc ──────────────────────────────────────────────────────────────────

// Writes 'data' to 'path', creating parent directories. 1 on success.
int  util_write_file(const &char path, const &String data);

// Appends 's' HTML-escaped (& < > " ') to 'out'.
void util_push_esc(&String out, const &char s);

// Is 's' the empty string?
int util_empty(const &char s);

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
    struct String safec_home;   // $SAFEC_HOME ("" if unset): CHANGELOG.md, selfhost/
    // Release tags, newest first (releases.sc): the docs version selector.
    struct Vec<struct String> versions;
    // Served paths (no base) of generated non-page files — feeds, redirect
    // pages — that links may point at (the link check accepts them).
    struct Vec<struct String> extra_urls;
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
    int generated;         // 1: made by the generator (no .md file), see above
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

// A page for the markdown-relative path 'rel' ("guide/x.md", "blog/index.md",
// "ko/changelog.md"): locale, route, url, src and out filled in; front an
// empty object, body_md empty, generated 0.
struct Page page_new(const &Site site, const &char rel);

// The page's 'layout' frontmatter ("doc" when absent).
const &char page_layout(const &Page page);

void pages_free(&Vec<struct Page> pages);

// ── render.sc ────────────────────────────────────────────────────────────────

// Markdown -> HTML for one page: fills html, headings, sections and title.
// Full SafeC programs among the code blocks of the locale's Book pages are
// appended to 'examples' (VAL_ARRAY of {t: title, c: code}) for the
// playground's example picker.
void render_page(const &Site site, &Page page, &Value examples);

// Appends 'code' as the URL-hash payload the playground reads
// ("code=" + base64url of the UTF-8 bytes, no padding).
void playground_hash(const &char code, unsigned long len, &String out);

// Rewrites one link target the way every href on the site is rewritten:
// internal routes get the base prefix and '.html'; external links and
// links to plain files (site.yaml 'file_extensions') are left alone.
void link_rewrite(const &Site site, const &char href, &String out);

// ── highlight.sc ─────────────────────────────────────────────────────────────
#include "highlight.h"

// ── releases.sc ──────────────────────────────────────────────────────────────

// Adds a release-notes post per SafeC release (en + every other locale)
// and a changelog page per locale, from $SAFEC_HOME/CHANGELOG.md (or
// $SAFEC_CHANGELOG) and the release history (build/releases.json when the
// build fetched it, else data/releases.json); fills site.versions.
void releases_add_pages(&Site site, &Vec<struct Page> pages);

// ── blog.sc ──────────────────────────────────────────────────────────────────

// Is 'page' a blog post: under <locale prefix>blog/ with a 'date'?
int blog_is_post(const &Site site, const &Page page);

// Adds each locale's blog index pages (paginated), the tag index and one
// page per tag; links every post to its neighbours (front '_prev'/'_next').
void blog_add_pages(&Site site, &Vec<struct Page> pages);

// Writes <prefix>blog/feed.xml (Atom) and <prefix>blog/rss.xml (RSS 2.0)
// for each locale. 1 on success.
int blog_write_feeds(const &Site site, const &Vec<struct Page> pages);

// Appends a post's "date · author" line and tag links (HTML).
void blog_post_meta(const &Site site, const &Page page, &String out);

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
    struct String crumbs;       // raw: breadcrumbs above a doc page
    struct String version_menu; // raw: docs version selector (placeholder)
    struct String footer_cols;  // raw: footer link columns
    struct String page_script;  // raw: extra <script> tags at the end of <body>
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
    struct String page_class;   // layout: "doc" | "home" | "page" | "blog" | "post" | "playground" | "not-found"
    struct String client_js;    // assets/site.js URL
    struct String css;          // assets/style.css URL
    struct String locale_key;   // "en" | "ko" (search index, client strings)
    struct String locale_prefix;
};

// 'examples': the page's locale's playground examples (render_page).
void view_build(const &Site site, const &Vec<struct Page> pages, unsigned long idx, const &Value examples, &PageView v);
void view_build_404(const &Site site, unsigned long locale, &PageView v);
void view_free(&PageView v);

// ── templates/*.scx ──────────────────────────────────────────────────────────

struct String tpl_doc(const &PageView v);         // docs: sidebar + outline
struct String tpl_wide(const &PageView v);        // landing, page, blog, post, playground
struct String tpl_not_found(const &PageView v);

// ── search.sc ────────────────────────────────────────────────────────────────

// Appends one record per page section to 'index' (VAL_ARRAY).
void search_add_page(const &Site site, const &Page page, &Value index);
