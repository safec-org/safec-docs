// safec-site — the blog (see site.h).
//
// A post is any page under <locale prefix>blog/ whose frontmatter has a
// 'date' (YYYY-MM-DD):
//
//   ---
//   title: Welcome to the new SafeC website
//   date: 2026-10-23
//   author: The SafeC Team
//   tags: [announcement, website]
//   summary: One or two sentences for the index, the feeds and search.
//   ---
//
// Hand-written posts are blog/<slug>.md (ko/blog/<slug>.md in Korean);
// release notes are generated (releases.sc). For each locale this adds:
//   <prefix>blog/            the newest posts       (site.yaml blog.per_page)
//   <prefix>blog/page/N      older pages
//   <prefix>blog/tags/       every tag, with counts
//   <prefix>blog/tags/<tag>  the posts with that tag
//   <prefix>blog/feed.xml    Atom; <prefix>blog/rss.xml RSS 2.0 (newest 20)
// Listing pages are markdown pages whose body is one raw-HTML block, so
// they go through the same link check as everything else.
#include "site.h"
#include <std/serial/markdown.h>
#include <std/path.h>

static struct String blog_prefix(const &Locale loc) {
    struct String p = loc.prefix.clone();
    p.push("blog/");
    return p;
}

int blog_is_post(const &Site site, const &Page page) {
    struct String bp = blog_prefix(site.locales.at(page.locale));
    int under = page.route.starts_with(bp.as_ptr()) && !page.route.eq(&bp);
    bp.free();
    if (!under) { return 0; }
    const &char layout = util_vstr(&page.front, "layout", "post");
    struct String l = String::from(layout);
    int listing = l.eq_cstr("blog");
    l.free();
    struct String d = String::from(util_vstr(&page.front, "date", ""));
    int dated = d.len >= 10UL;
    d.free();
    return dated && !listing;
}

static const &char bstr(const &Locale loc, const &char key, const &char fallback) {
    ?&Value b = loc.data.object_get("blog");
    match (b) { case some(x): { return util_vstr(x, key, fallback); } case null: { } }
    return fallback;
}

// ── dates ────────────────────────────────────────────────────────────────────

static int date_part(const &char date, unsigned long at, unsigned long n) {
    struct String d = String::from(date);
    int v = 0;
    for (unsigned long i = at; i < at + n && i < d.len; i = i + 1UL) {
        int c = d.char_at(i);
        if (c >= (int)'0' && c <= (int)'9') { v = v * 10 + (c - (int)'0'); }
    }
    d.free();
    return v;
}

// 0 = Sunday (Sakamoto's method).
static int weekday(int y, int m, int d) {
    int t[12] = { 0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4 };
    if (m < 3) { y = y - 1; }
    return (y + y / 4 - y / 100 + y / 400 + t[m - 1] + d) % 7;
}

// "2026-10-23" in the locale's 'blog.dateFormat' ("{month} {d}, {y}").
static void format_date(const &Locale loc, const &char date, &String out) {
    int y = date_part(date, 0UL, 4UL);
    int m = date_part(date, 5UL, 2UL);
    int d = date_part(date, 8UL, 2UL);
    struct String f = String::from(bstr(loc, "dateFormat", "{month} {d}, {y}"));
    struct String month = String::new();
    ?&Value b = loc.data.object_get("blog");
    match (b) {
        case some(x): {
            ?&Value months = x.object_get("months");
            match (months) {
                case some(ms): {
                    if (m >= 1 && (unsigned long)m <= ms.array_len()) { month.push(ms.array_at((unsigned long)(m - 1)).as_string()); }
                }
                case null: { }
            }
        }
        case null: { }
    }
    struct String ys = String::new(); ys.push_int((long long)y);
    struct String ms2 = String::new(); ms2.push_int((long long)m);
    struct String ds = String::new(); ds.push_int((long long)d);
    f.replace_all("{month}", month.as_ptr());
    f.replace_all("{y}", ys.as_ptr());
    f.replace_all("{m}", ms2.as_ptr());
    f.replace_all("{d}", ds.as_ptr());
    out.push_str(&f);
    f.free(); month.free(); ys.free(); ms2.free(); ds.free();
}

static void rfc822(const &char date, &String out) {
    const char* days[7] = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    const char* mons[12] = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    int y = date_part(date, 0UL, 4UL);
    int m = date_part(date, 5UL, 2UL);
    int d = date_part(date, 8UL, 2UL);
    if (m < 1 || m > 12) { m = 1; }
    if (d < 1) { d = 1; }
    unsafe { out.push(days[weekday(y, m, d)]); }
    out.push(", ");
    if (d < 10) { out.push_char('0'); }
    out.push_int((long long)d);
    out.push(" ");
    unsafe { out.push(mons[m - 1]); }
    out.push(" ");
    out.push_int((long long)y);
    out.push(" 00:00:00 +0000");
}

// ── tags ─────────────────────────────────────────────────────────────────────

static void tag_route(const &Locale loc, const &char tag, &String out) {
    out.push_str(&loc.prefix);
    out.push("blog/tags/");
    md_slugify(tag, out);
}

static void href_to(const &Site site, const &char route, &String out) {
    struct String h = String::new();
    link_rewrite(site, route, &h);
    util_push_esc(out, h.as_ptr());
    h.free();
}

void blog_post_meta(const &Site site, const &Page page, &String out) {
    &Locale loc = site.locales.at(page.locale);
    const &char date = util_vstr(&page.front, "date", "");
    out.push("<p class=\"post-meta\"><time datetime=\"");
    util_push_esc(out, date);
    out.push("\">");
    struct String fd = String::new();
    format_date(loc, date, &fd);
    util_push_esc(out, fd.as_ptr());
    fd.free();
    out.push("</time>");
    const &char author = util_vstr(&page.front, "author", "");
    struct String a = String::from(author);
    if (a.len > 0UL) { out.push(" · <span class=\"author\">"); util_push_esc(out, author); out.push("</span>"); }
    a.free();
    out.push("</p>");
    ?&Value tags = page.front.object_get("tags");
    match (tags) {
        case some(ts): {
            if (ts.kind_of() == VAL_ARRAY && ts.array_len() > 0UL) {
                out.push("<p class=\"post-tags\">");
                for (unsigned long i = 0UL; i < ts.array_len(); i = i + 1UL) {
                    if (ts.array_at(i).kind_of() != VAL_STRING) { continue; }
                    struct String r = String::new();
                    tag_route(loc, ts.array_at(i).as_string(), &r);
                    out.push("<a class=\"tag\" href=\"");
                    href_to(site, r.as_ptr(), out);
                    out.push("\">#");
                    util_push_esc(out, ts.array_at(i).as_string());
                    out.push("</a>");
                    r.free();
                }
                out.push("</p>");
            }
        }
        case null: { }
    }
}

// ── listings ─────────────────────────────────────────────────────────────────

struct PostRef {
    unsigned long idx;     // into the page list
    struct String date;
    struct String route;
};

static int cmp_post(const &PostRef a, const &PostRef b) {
    int c = b.date.cmp(&a.date);          // newest first
    if (c == 0) { c = a.route.cmp(&b.route); }
    return c;
}

static void push_one_line(&String out, const &char s) {
    struct String t = String::from(s);
    t.replace_char('\n', ' ');
    util_push_esc(out, t.as_ptr());
    t.free();
}

static void post_card(const &Site site, const &Page p, &String out) {
    out.push("<article class=\"post-card\">");
    struct String meta = String::new();
    blog_post_meta(site, p, &meta);
    out.push_str(&meta);
    meta.free();
    out.push("<h2 class=\"post-card-title\"><a href=\"");
    href_to(site, p.route.as_ptr(), out);
    out.push("\">");
    push_one_line(out, util_vstr(&p.front, "title", p.route.as_ptr()));
    out.push("</a></h2><p class=\"post-card-summary\">");
    push_one_line(out, util_vstr(&p.front, "summary", ""));
    out.push("</p></article>");
}

static void listing_header(const &Site site, const &Locale loc, const &char title, const &char sub, &String out) {
    struct String bp = blog_prefix(loc);
    out.push("<header class=\"blog-head\"><h1>");
    util_push_esc(out, title);
    out.push("</h1>");
    struct String s = String::from(sub);
    if (s.len > 0UL) { out.push("<p class=\"blog-sub\">"); util_push_esc(out, sub); out.push("</p>"); }
    s.free();
    out.push("<p class=\"blog-links\"><a href=\"");
    href_to(site, bp.as_ptr(), out);
    out.push("\">"); util_push_esc(out, bstr(loc, "allPosts", "All posts")); out.push("</a><a href=\"");
    struct String r = bp.clone(); r.push("tags/");
    href_to(site, r.as_ptr(), out);
    r.free();
    out.push("\">"); util_push_esc(out, bstr(loc, "tags", "Tags")); out.push("</a><a href=\"");
    r = loc.prefix.clone(); r.push("changelog");
    href_to(site, r.as_ptr(), out);
    r.free();
    out.push("\">"); util_push_esc(out, bstr(loc, "changelogTitle", "Changelog")); out.push("</a><a class=\"feed\" href=\"");
    r = bp.clone(); r.push("feed.xml");
    href_to(site, r.as_ptr(), out);
    r.free();
    out.push("\">"); util_push_esc(out, bstr(loc, "feed", "Atom feed")); out.push("</a><a class=\"feed\" href=\"");
    r = bp.clone(); r.push("rss.xml");
    href_to(site, r.as_ptr(), out);
    r.free();
    out.push("\">RSS</a></p></header>");
    bp.free();
}

static void add_listing(&Site site, &Vec<struct Page> pages, const &char rel, const &char title, struct String html) {
    struct Page p = page_new(site, rel);
    p.generated = 1;
    p.front.object_set("title", Value::from_str(title));
    p.front.object_set("layout", Value::from_str("blog"));
    p.front.object_set("search", Value::from_bool(0));
    p.body_md.push_str(&html);
    p.body_md.push_char('\n');
    html.free();
    pages.push(p);
}

static void page_rel(const &Locale loc, const &char tail, &String out) {
    out.push_str(&loc.dir);
    if (loc.dir.len > 0UL) { out.push_char('/'); }
    out.push(tail);
}

static void set_neighbour(&Page p, const &char key, const &Page q) {
    struct Value o = Value::object();
    o.object_set("route", Value::from_str(q.route.as_ptr()));
    o.object_set("title", Value::from_str(util_vstr(&q.front, "title", "")));
    p.front.object_set(key, o);
}

void blog_add_pages(&Site site, &Vec<struct Page> pages) {
    long long per_page = 10LL;
    ?&Value bcfg = site.cfg.object_get("blog");
    match (bcfg) {
        case some(b): {
            ?&Value pp = b.object_get("per_page");
            match (pp) { case some(v): { if (v.kind_of() == VAL_INT && v.as_int() > 0LL) { per_page = v.as_int(); } } case null: { } }
        }
        case null: { }
    }
    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        &Locale loc = site.locales.at(li);
        struct Vec<struct PostRef> posts = Vec::new();
        for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
            &Page p = pages.at(i);
            if (p.locale != li || !blog_is_post(site, p)) { continue; }
            if (p.front.object_get("layout").is_null()) { p.front.object_set("layout", Value::from_str("post")); }
            struct PostRef r;
            r.idx = i;
            r.date = String::from(util_vstr(&p.front, "date", ""));
            r.route = p.route.clone();
            posts.push(r);
        }
        posts.sort(cmp_post);
        for (unsigned long k = 0UL; k < posts.len; k = k + 1UL) {
            &Page p = pages.at(posts.at(k).idx);
            if (k > 0UL) { set_neighbour(p, "_newer", pages.at(posts.at(k - 1UL).idx)); }
            if (k + 1UL < posts.len) { set_neighbour(p, "_older", pages.at(posts.at(k + 1UL).idx)); }
        }

        // Index pages.
        unsigned long per = (unsigned long)per_page;
        unsigned long npages = posts.len == 0UL ? 1UL : (posts.len + per - 1UL) / per;
        struct String bp = blog_prefix(loc);
        for (unsigned long pg = 0UL; pg < npages; pg = pg + 1UL) {
            struct String html = String::new();
            listing_header(site, loc, bstr(loc, "title", "Blog"), bstr(loc, "description", ""), &html);
            html.push("<div class=\"post-list\">");
            for (unsigned long k = pg * per; k < posts.len && k < (pg + 1UL) * per; k = k + 1UL) {
                post_card(site, pages.at(posts.at(k).idx), &html);
            }
            html.push("</div>");
            if (npages > 1UL) {
                html.push("<nav class=\"pagination\" aria-label=\"");
                util_push_esc(&html, bstr(loc, "pagination", "Pagination"));
                html.push("\">");
                for (unsigned long q = 0UL; q < npages; q = q + 1UL) {
                    struct String r = bp.clone();
                    if (q > 0UL) { r.push("page/"); r.push_int((long long)(q + 1UL)); }
                    html.push("<a href=\"");
                    href_to(site, r.as_ptr(), &html);
                    html.push(q == pg ? "\" class=\"current\" aria-current=\"page\">" : "\">");
                    html.push_int((long long)(q + 1UL));
                    html.push("</a>");
                    r.free();
                }
                html.push("</nav>");
            }
            struct String rel = String::new();
            if (pg == 0UL) { page_rel(loc, "blog/index.md", &rel); }
            else {
                struct String t = String::from("blog/page/");
                t.push_int((long long)(pg + 1UL));
                t.push(".md");
                page_rel(loc, t.as_ptr(), &rel);
                t.free();
            }
            struct String title = String::from(bstr(loc, "title", "Blog"));
            if (pg > 0UL) { title.push(" · "); title.push_int((long long)(pg + 1UL)); }
            add_listing(site, pages, rel.as_ptr(), title.as_ptr(), html);
            rel.free(); title.free();
        }

        // Tags: unique, in first-seen order of the newest-first post list.
        struct Vec<struct String> tags = Vec::new();
        struct Vec<unsigned long> counts = Vec::new();
        for (unsigned long k = 0UL; k < posts.len; k = k + 1UL) {
            ?&Value ts = pages.at(posts.at(k).idx).front.object_get("tags");
            match (ts) {
                case some(t): {
                    for (unsigned long j = 0UL; t.kind_of() == VAL_ARRAY && j < t.array_len(); j = j + 1UL) {
                        if (t.array_at(j).kind_of() != VAL_STRING) { continue; }
                        const &char name = t.array_at(j).as_string();
                        long long at = -1LL;
                        for (unsigned long x = 0UL; x < tags.len; x = x + 1UL) { if (tags.at(x).eq_cstr(name)) { at = (long long)x; } }
                        if (at < 0LL) { tags.push(String::from(name)); counts.push(1UL); }
                        else { counts.set((unsigned long)at, *counts.at((unsigned long)at) + 1UL); }
                    }
                }
                case null: { }
            }
        }
        struct String idx_html = String::new();
        listing_header(site, loc, bstr(loc, "tags", "Tags"), "", &idx_html);
        idx_html.push("<ul class=\"tag-cloud\">");
        for (unsigned long x = 0UL; x < tags.len; x = x + 1UL) {
            struct String r = String::new();
            tag_route(loc, tags.at(x).as_ptr(), &r);
            idx_html.push("<li><a class=\"tag\" href=\"");
            href_to(site, r.as_ptr(), &idx_html);
            idx_html.push("\">#");
            util_push_esc(&idx_html, tags.at(x).as_ptr());
            idx_html.push(" <span class=\"count\">");
            idx_html.push_int((long long)*counts.at(x));
            idx_html.push("</span></a></li>");
            r.free();
        }
        idx_html.push("</ul>");
        struct String rel = String::new();
        page_rel(loc, "blog/tags/index.md", &rel);
        add_listing(site, pages, rel.as_ptr(), bstr(loc, "tags", "Tags"), idx_html);
        rel.free();
        for (unsigned long x = 0UL; x < tags.len; x = x + 1UL) {
            struct String title = String::from(bstr(loc, "tagTitle", "Posts tagged"));
            title.push(" #");
            title.push_str(tags.at(x));
            struct String html = String::new();
            listing_header(site, loc, title.as_ptr(), "", &html);
            html.push("<div class=\"post-list\">");
            for (unsigned long k = 0UL; k < posts.len; k = k + 1UL) {
                &Page p = pages.at(posts.at(k).idx);
                ?&Value ts = p.front.object_get("tags");
                int has = 0;
                match (ts) {
                    case some(t): {
                        for (unsigned long j = 0UL; t.kind_of() == VAL_ARRAY && j < t.array_len(); j = j + 1UL) {
                            if (t.array_at(j).kind_of() == VAL_STRING && tags.at(x).eq_cstr(t.array_at(j).as_string())) { has = 1; }
                        }
                    }
                    case null: { }
                }
                if (has) { post_card(site, p, &html); }
            }
            html.push("</div>");
            struct String slug = String::new();
            md_slugify(tags.at(x).as_ptr(), &slug);
            struct String tail = String::from("blog/tags/");
            tail.push_str(&slug);
            tail.push(".md");
            struct String trel = String::new();
            page_rel(loc, tail.as_ptr(), &trel);
            add_listing(site, pages, trel.as_ptr(), title.as_ptr(), html);
            slug.free(); tail.free(); trel.free(); title.free();
        }
        // The feeds are files, not pages: tell the link check about them.
        struct String f = bp.clone(); f.push("feed.xml");
        site.extra_urls.push(f);
        struct String r2 = bp.clone(); r2.push("rss.xml");
        site.extra_urls.push(r2);
        bp.free();
        for (unsigned long x = 0UL; x < tags.len; x = x + 1UL) { tags.at(x).free(); }
        tags.free(); counts.free();
        for (unsigned long k = 0UL; k < posts.len; k = k + 1UL) { posts.at(k).date.free(); posts.at(k).route.free(); }
        posts.free();
    }
}

// ── feeds ────────────────────────────────────────────────────────────────────

static void abs_url(const &Site site, const &char route, &String out) {
    out.push(util_vstr(&site.cfg, "origin", ""));
    link_rewrite(site, route, out);
}

static void xml_esc(&String out, const &char s) { util_push_esc(out, s); }

int blog_write_feeds(const &Site site, const &Vec<struct Page> pages) {
    int ok = 1;
    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        &Locale loc = site.locales.at(li);
        struct Vec<struct PostRef> posts = Vec::new();
        for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
            &Page p = pages.at(i);
            if (p.locale != li || !blog_is_post(site, p)) { continue; }
            struct PostRef r;
            r.idx = i;
            r.date = String::from(util_vstr(&p.front, "date", ""));
            r.route = p.route.clone();
            posts.push(r);
        }
        posts.sort(cmp_post);
        struct String bp = blog_prefix(loc);
        struct String self_atom = String::new(); { struct String r = bp.clone(); r.push("feed.xml"); abs_url(site, r.as_ptr(), &self_atom); r.free(); }
        struct String self_rss = String::new(); { struct String r = bp.clone(); r.push("rss.xml"); abs_url(site, r.as_ptr(), &self_rss); r.free(); }
        struct String home = String::new();
        abs_url(site, bp.as_ptr(), &home);
        struct String feed_title = String::from(util_vstr(&loc.data, "title", "SafeC"));
        feed_title.push(" ");
        feed_title.push(bstr(loc, "title", "Blog"));
        struct String updated = posts.len > 0UL ? posts.at(0UL).date.clone() : String::from("2026-01-01");

        struct String atom = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"");
        xml_esc(&atom, util_vstr(&loc.data, "lang", "en"));
        atom.push("\" xml:base=\"");
        xml_esc(&atom, util_vstr(&site.cfg, "origin", ""));
        atom.push("/\">\n<title>"); xml_esc(&atom, feed_title.as_ptr());
        atom.push("</title>\n<subtitle>"); xml_esc(&atom, bstr(loc, "description", ""));
        atom.push("</subtitle>\n<link rel=\"self\" href=\""); xml_esc(&atom, self_atom.as_ptr());
        atom.push("\"/>\n<link rel=\"alternate\" type=\"text/html\" href=\""); xml_esc(&atom, home.as_ptr());
        atom.push("\"/>\n<id>"); xml_esc(&atom, home.as_ptr());
        atom.push("</id>\n<updated>"); atom.push_str(&updated); atom.push("T00:00:00Z</updated>\n");

        struct String rss = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n<channel>\n<title>");
        xml_esc(&rss, feed_title.as_ptr());
        rss.push("</title>\n<link>"); xml_esc(&rss, home.as_ptr());
        rss.push("</link>\n<description>"); xml_esc(&rss, bstr(loc, "description", ""));
        rss.push("</description>\n<language>"); xml_esc(&rss, util_vstr(&loc.data, "lang", "en"));
        rss.push("</language>\n<atom:link href=\""); xml_esc(&rss, self_rss.as_ptr());
        rss.push("\" rel=\"self\" type=\"application/rss+xml\"/>\n<lastBuildDate>");
        rfc822(updated.as_ptr(), &rss);
        rss.push("</lastBuildDate>\n");

        for (unsigned long k = 0UL; k < posts.len && k < 20UL; k = k + 1UL) {
            &Page p = pages.at(posts.at(k).idx);
            struct String url = String::new();
            abs_url(site, p.route.as_ptr(), &url);
            const &char title = util_vstr(&p.front, "title", "");
            const &char date = util_vstr(&p.front, "date", "");
            atom.push("<entry>\n<title>"); xml_esc(&atom, title);
            atom.push("</title>\n<link rel=\"alternate\" type=\"text/html\" href=\""); xml_esc(&atom, url.as_ptr());
            atom.push("\"/>\n<id>"); xml_esc(&atom, url.as_ptr());
            atom.push("</id>\n<published>"); xml_esc(&atom, date); atom.push("T00:00:00Z</published>\n<updated>");
            xml_esc(&atom, date); atom.push("T00:00:00Z</updated>\n<author><name>");
            xml_esc(&atom, util_vstr(&p.front, "author", "SafeC"));
            atom.push("</name></author>\n");
            ?&Value tags = p.front.object_get("tags");
            match (tags) {
                case some(ts): {
                    for (unsigned long j = 0UL; ts.kind_of() == VAL_ARRAY && j < ts.array_len(); j = j + 1UL) {
                        if (ts.array_at(j).kind_of() != VAL_STRING) { continue; }
                        atom.push("<category term=\""); xml_esc(&atom, ts.array_at(j).as_string()); atom.push("\"/>\n");
                    }
                }
                case null: { }
            }
            atom.push("<summary>"); xml_esc(&atom, util_vstr(&p.front, "summary", ""));
            atom.push("</summary>\n<content type=\"html\">"); xml_esc(&atom, p.html.as_ptr());
            atom.push("</content>\n</entry>\n");

            rss.push("<item>\n<title>"); xml_esc(&rss, title);
            rss.push("</title>\n<link>"); xml_esc(&rss, url.as_ptr());
            rss.push("</link>\n<guid isPermaLink=\"true\">"); xml_esc(&rss, url.as_ptr());
            rss.push("</guid>\n<pubDate>"); rfc822(date, &rss);
            rss.push("</pubDate>\n<description>"); xml_esc(&rss, util_vstr(&p.front, "summary", ""));
            rss.push("</description>\n</item>\n");
            url.free();
        }
        atom.push("</feed>\n");
        rss.push("</channel>\n</rss>\n");

        struct String rel = bp.strip_prefix("/");
        struct String dir = path_join(site.output.as_ptr(), rel.as_ptr());
        struct String ap = path_join(dir.as_ptr(), "feed.xml");
        struct String rp = path_join(dir.as_ptr(), "rss.xml");
        if (!util_write_file(ap.as_ptr(), &atom) || !util_write_file(rp.as_ptr(), &rss)) { ok = 0; }
        rel.free(); dir.free(); ap.free(); rp.free();
        atom.free(); rss.free(); bp.free(); self_atom.free(); self_rss.free(); home.free();
        feed_title.free(); updated.free();
        for (unsigned long k = 0UL; k < posts.len; k = k + 1UL) { posts.at(k).date.free(); posts.at(k).route.free(); }
        posts.free();
    }
    return ok;
}
