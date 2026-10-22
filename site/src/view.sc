// safec-site — builds a PageView: every piece of a page's chrome (nav,
// sidebar, outline, prev/next, language menu, home hero) as finished HTML,
// so the scx templates only interpolate.
#include "site.h"

static void href(const &Site site, &String out, const &char link) {
    out.push(" href=\"");
    struct String r = String::new();
    link_rewrite(site, link, &r);
    util_push_esc(out, r.as_ptr());
    r.free();
    out.push("\"");
}

static int is_external(const &char link) {
    struct String l = String::from(link);
    int ext = l.starts_with("http://") || l.starts_with("https://");
    l.free();
    return ext;
}

// "/guide/introduction" vs page route "/guide/introduction": same page?
static int same_route(const &char link, const &Page page) {
    return page.route.eq_cstr(link);
}

static int group_has_route(const &Value items, const &Page page) {
    if (items.kind_of() != VAL_ARRAY) { return 0; }
    for (unsigned long i = 0UL; i < items.array_len(); i = i + 1UL) {
        if (same_route(util_vstr(items.array_at(i), "link", ""), page)) { return 1; }
    }
    return 0;
}

static void link_item(const &Site site, const &Value it, const &Page page, const &char cls, &String out) {
    const &char link = util_vstr(it, "link", "");
    out.push("<a class=\"");
    out.push(cls);
    if (same_route(link, page)) { out.push(" active"); }
    out.push("\"");
    href(site, out, link);
    if (is_external(link)) { out.push(" target=\"_blank\" rel=\"noreferrer\""); }
    out.push(">");
    util_push_esc(out, util_vstr(it, "text", ""));
    out.push("</a>");
}

static void build_nav(const &Site site, const &Locale loc, const &Page page, &String out) {
    ?&Value nav = loc.data.object_get("nav");
    match (nav) {
        case some(items): {
            for (unsigned long i = 0UL; i < items.array_len(); i = i + 1UL) {
                &Value it = items.array_at(i);
                ?&Value sub = it.object_get("items");
                match (sub) {
                    case some(s): {
                        out.push("<div class=\"nav-group");
                        if (group_has_route(s, page)) { out.push(" active"); }
                        out.push("\"><button class=\"nav-group-btn\" type=\"button\" aria-expanded=\"false\">");
                        util_push_esc(out, util_vstr(it, "text", ""));
                        out.push("<span class=\"caret\"></span></button><div class=\"nav-menu\">");
                        for (unsigned long k = 0UL; k < s.array_len(); k = k + 1UL) {
                            link_item(site, s.array_at(k), page, "nav-menu-link", out);
                        }
                        out.push("</div></div>");
                    }
                    case null: {
                        // The GitHub entry is rendered as an icon by the template.
                        if (!is_external(util_vstr(it, "link", ""))) { link_item(site, it, page, "nav-link", out); }
                    }
                }
            }
        }
        case null: { }
    }
}

// The locale's sidebar whose 'prefix' is the longest prefix of the route.
static ?&Value sidebar_for(const &Locale loc, const &Page page) {
    ?&Value best = (?&Value)null;
    unsigned long best_len = 0UL;
    ?&Value sb = loc.data.object_get("sidebar");
    match (sb) {
        case some(s): {
            for (unsigned long i = 0UL; i < s.array_len(); i = i + 1UL) {
                &Value e = s.array_at(i);
                const &char prefix = util_vstr(e, "prefix", "");
                struct String p = String::from(prefix);
                if (p.len > best_len && page.route.starts_with(prefix)) {
                    best = e.object_get("groups");
                    best_len = p.len;
                }
                p.free();
            }
        }
        case null: { }
    }
    return best;
}

static void build_sidebar(const &Site site, const &Locale loc, const &Page page, &String out) {
    ?&Value groups = sidebar_for(loc, page);
    match (groups) {
        case some(gs): {
            for (unsigned long g = 0UL; g < gs.array_len(); g = g + 1UL) {
                &Value grp = gs.array_at(g);
                out.push("<section class=\"sb-group\"><p class=\"sb-title\">");
                util_push_esc(out, util_vstr(grp, "text", ""));
                out.push("</p><ul>");
                ?&Value items = grp.object_get("items");
                match (items) {
                    case some(its): {
                        for (unsigned long k = 0UL; k < its.array_len(); k = k + 1UL) {
                            out.push("<li>");
                            link_item(site, its.array_at(k), page, "sb-link", out);
                            out.push("</li>");
                        }
                    }
                    case null: { }
                }
                out.push("</ul></section>");
            }
        }
        case null: { }
    }
}

static void build_prev_next(const &Site site, const &Locale loc, const &Page page, &String out) {
    ?&Value groups = sidebar_for(loc, page);
    // Flatten the sidebar into (text, link) order.
    struct Vec<&Value> flat = Vec::new();
    match (groups) {
        case some(gs): {
            for (unsigned long g = 0UL; g < gs.array_len(); g = g + 1UL) {
                ?&Value items = gs.array_at(g).object_get("items");
                match (items) {
                    case some(its): {
                        for (unsigned long k = 0UL; k < its.array_len(); k = k + 1UL) { flat.push(its.array_at(k)); }
                    }
                    case null: { }
                }
            }
        }
        case null: { }
    }
    long long at = -1LL;
    for (unsigned long i = 0UL; i < flat.len; i = i + 1UL) {
        if (same_route(util_vstr(*flat.at(i), "link", ""), page)) { at = (long long)i; }
    }
    if (at < 0LL) { flat.free(); return; }
    ?&Value lv = loc.data.object_get("labels");
    out.push("<nav class=\"prev-next\">");
    if (at > 0LL) {
        &Value p = *flat.at((unsigned long)at - 1UL);
        out.push("<a class=\"pager prev\"");
        href(site, out, util_vstr(p, "link", ""));
        out.push("><span class=\"desc\">");
        match (lv) { case some(l): util_push_esc(out, util_vstr(l, "prev", "Previous page")); case null: out.push("Previous page"); }
        out.push("</span><span class=\"title\">");
        util_push_esc(out, util_vstr(p, "text", ""));
        out.push("</span></a>");
    } else {
        out.push("<span></span>");
    }
    if ((unsigned long)at + 1UL < flat.len) {
        &Value n = *flat.at((unsigned long)at + 1UL);
        out.push("<a class=\"pager next\"");
        href(site, out, util_vstr(n, "link", ""));
        out.push("><span class=\"desc\">");
        match (lv) { case some(l): util_push_esc(out, util_vstr(l, "next", "Next page")); case null: out.push("Next page"); }
        out.push("</span><span class=\"title\">");
        util_push_esc(out, util_vstr(n, "text", ""));
        out.push("</span></a>");
    }
    out.push("</nav>");
    flat.free();
}

// frontmatter 'outline': absent -> [2, 2]; "[2, 4]" -> 2..4; "deep" -> 2..6; N -> N..N.
static void outline_range(const &Page page, &int lo, &int hi) {
    *lo = 2; *hi = 2;
    ?&Value o = page.front.object_get("outline");
    match (o) {
        case some(v): {
            if (v.kind_of() == VAL_INT) { *lo = (int)v.as_int(); *hi = *lo; }
            else if (v.kind_of() == VAL_STRING) {
                struct String s = String::from(v.as_string());
                if (s.eq_cstr("deep")) { *hi = 6; }
                else if (s.starts_with("[") && s.len >= 5UL) {
                    // std yaml has no flow sequences: '[2, 4]' arrives as text.
                    *lo = s.char_at(1UL) - (int)'0';
                    *hi = s.char_at(s.len - 2UL) - (int)'0';
                }
                s.free();
            }
        }
        case null: { }
    }
}

static void build_toc(const &Page page, &String out) {
    int lo = 2;
    int hi = 2;
    outline_range(page, &lo, &hi);
    for (unsigned long i = 0UL; i < page.headings.len; i = i + 1UL) {
        &Heading h = page.headings.at(i);
        if (h.level < lo || h.level > hi) { continue; }
        out.push("<li class=\"toc-l");
        out.push_int((long long)(h.level - lo));
        out.push("\"><a href=\"#");
        util_push_esc(out, h.id.as_ptr());
        out.push("\">");
        util_push_esc(out, h.text.as_ptr());
        out.push("</a></li>");
    }
}

static void build_locale_menu(const &Site site, unsigned long li, const &Page page, &String out) {
    &Locale cur = site.locales.at(li);
    // The page's route with the current locale's prefix stripped.
    struct String tail = page.route.substr(cur.prefix.len, page.route.len);
    for (unsigned long k = 0UL; k < site.locales.len; k = k + 1UL) {
        &Locale l = site.locales.at(k);
        struct String target = l.prefix.clone();
        target.push_str(&tail);
        out.push("<a class=\"locale-link");
        if (k == li) { out.push(" active"); }
        out.push("\" lang=\"");
        util_push_esc(out, util_vstr(&l.data, "lang", ""));
        out.push("\"");
        href(site, out, target.as_ptr());
        out.push(">");
        util_push_esc(out, util_vstr(&l.data, "label", ""));
        out.push("</a>");
        target.free();
    }
    tail.free();
}

static void build_head(const &Site site, const &Page page, &String out) {
    ?&Value head = site.cfg.object_get("head");
    match (head) {
        case some(h): {
            for (unsigned long i = 0UL; i < h.array_len(); i = i + 1UL) {
                &Value e = h.array_at(i);
                out.push("<link");
                for (unsigned long k = 0UL; k < e.obj_val.len; k = k + 1UL) {
                    &ObjectEntry kv = e.obj_val.at(k);
                    const &char key = "";
                    match (kv.key) { case some(kk): key = kk; case null: { } }
                    struct String ks = String::from(key);
                    if (!ks.eq_cstr("tag")) {
                        out.push(" ");
                        out.push(key);
                        out.push("=\"");
                        if (ks.eq_cstr("href")) { out.push(site.base.as_ptr()); }
                        util_push_esc(out, kv.val.as_string());
                        out.push("\"");
                    }
                    ks.free();
                }
                out.push(">\n");
            }
        }
        case null: { }
    }
    out.push("<link rel=\"canonical\" href=\"");
    util_push_esc(out, util_vstr(&site.cfg, "origin", ""));
    struct String u = String::new();
    link_rewrite(site, page.route.as_ptr(), &u);
    util_push_esc(out, u.as_ptr());
    u.free();
    out.push("\">\n");
}

static void build_hero(const &Site site, const &Page page, &String hero, &String features) {
    ?&Value h = page.front.object_get("hero");
    match (h) {
        case some(hv): {
            hero.push("<h1 class=\"hero-name\">");
            util_push_esc(hero, util_vstr(hv, "name", ""));
            hero.push("</h1><p class=\"hero-text\">");
            util_push_esc(hero, util_vstr(hv, "text", ""));
            hero.push("</p><p class=\"hero-tagline\">");
            util_push_esc(hero, util_vstr(hv, "tagline", ""));
            hero.push("</p><div class=\"hero-actions\">");
            ?&Value acts = hv.object_get("actions");
            match (acts) {
                case some(a): {
                    for (unsigned long i = 0UL; i < a.array_len(); i = i + 1UL) {
                        &Value act = a.array_at(i);
                        hero.push("<a class=\"button ");
                        util_push_esc(hero, util_vstr(act, "theme", "brand"));
                        hero.push("\"");
                        href(site, hero, util_vstr(act, "link", "/"));
                        hero.push(">");
                        util_push_esc(hero, util_vstr(act, "text", ""));
                        hero.push("</a>");
                    }
                }
                case null: { }
            }
            hero.push("</div>");
        }
        case null: { }
    }
    ?&Value fs = page.front.object_get("features");
    match (fs) {
        case some(f): {
            for (unsigned long i = 0UL; i < f.array_len(); i = i + 1UL) {
                &Value it = f.array_at(i);
                features.push("<div class=\"feature\"><h2>");
                util_push_esc(features, util_vstr(it, "title", ""));
                features.push("</h2><p>");
                // VitePress renders 'details' as raw HTML (entities intact).
                features.push(util_vstr(it, "details", ""));
                features.push("</p></div>");
            }
        }
        case null: { }
    }
}

static void common(const &Site site, const &Locale loc, &PageView v) {
    ?&Value lv = loc.data.object_get("labels");
    const &Value empty = &loc.data;
    v.lang = String::from(util_vstr(&loc.data, "lang", "en"));
    v.description = String::from(util_vstr(&loc.data, "description", ""));
    v.base = site.base.clone();
    v.home_href = String::new();
    link_rewrite(site, loc.prefix.as_ptr(), &v.home_href);
    v.logo_src = site.base.clone();
    v.logo_src.push("Logo.svg");
    v.site_title = String::from(util_vstr(&loc.data, "title", "SafeC"));
    v.github = String::from(util_vstr(&site.cfg, "repo", ""));
    v.client_js = site.base.clone();
    v.client_js.push("assets/site.js");
    ?&Value footer = loc.data.object_get("footer");
    match (footer) {
        case some(f): {
            v.footer_message = String::from(util_vstr(f, "message", ""));
            v.footer_copyright = String::from(util_vstr(f, "copyright", ""));
        }
        case null: { v.footer_message = String::new(); v.footer_copyright = String::new(); }
    }
    match (lv) {
        case some(l): { empty = l; }
        case null: { }
    }
    v.outline_label    = String::from(util_vstr(empty, "outline", "On this page"));
    v.menu_label       = String::from(util_vstr(empty, "sidebarMenu", "Menu"));
    v.search_label     = String::from(util_vstr(empty, "search", "Search"));
    v.theme_label      = String::from(util_vstr(empty, "darkMode", "Appearance"));
    v.return_top_label = String::from(util_vstr(empty, "returnToTop", "Return to top"));
    v.skip_label       = String::from(util_vstr(empty, "skipToContent", "Skip to content"));
    v.not_found_title  = String::from(util_vstr(empty, "notFoundTitle", "PAGE NOT FOUND"));
    v.not_found_quote  = String::from(util_vstr(empty, "notFoundQuote", ""));
    v.not_found_link   = String::from(util_vstr(empty, "notFoundLink", "Take me home"));
    v.head_extra = String::new();
    v.nav = String::new();
    v.locale_menu = String::new();
    v.sidebar = String::new();
    v.toc = String::new();
    v.content = String::new();
    v.prev_next = String::new();
    v.hero = String::new();
    v.features = String::new();
}

void view_build(const &Site site, const &Vec<struct Page> pages, unsigned long idx, &PageView v) {
    &Page page = pages.at(idx);
    &Locale loc = site.locales.at(page.locale);
    common(site, loc, v);
    v.title = page.title.clone();
    struct String st = String::from(util_vstr(&loc.data, "title", "SafeC"));
    if (v.title.len == 0UL) { v.title.push_str(&st); }
    else if (!v.title.eq(&st)) { v.title.push(" | "); v.title.push_str(&st); }
    st.free();
    const &char layout = util_vstr(&page.front, "layout", "doc");
    v.page_class = String::from(layout);
    build_head(site, page, &v.head_extra);
    build_nav(site, loc, page, &v.nav);
    build_locale_menu(site, page.locale, page, &v.locale_menu);
    build_sidebar(site, loc, page, &v.sidebar);
    build_toc(page, &v.toc);
    build_prev_next(site, loc, page, &v.prev_next);
    v.content.push_str(&page.html);
    if (v.page_class.eq_cstr("home")) { build_hero(site, page, &v.hero, &v.features); }
}

void view_build_404(const &Site site, unsigned long locale, &PageView v) {
    &Locale loc = site.locales.at(locale);
    common(site, loc, v);
    v.title = String::from("404 | ");
    v.title.push(util_vstr(&loc.data, "title", "SafeC"));
    v.page_class = String::from("not-found");
    // The language menu on 404 just points at each locale's home.
    for (unsigned long k = 0UL; k < site.locales.len; k = k + 1UL) {
        &Locale l = site.locales.at(k);
        v.locale_menu.push("<a class=\"locale-link\"");
        href(site, &v.locale_menu, l.prefix.as_ptr());
        v.locale_menu.push(">");
        util_push_esc(&v.locale_menu, util_vstr(&l.data, "label", ""));
        v.locale_menu.push("</a>");
    }
}

void view_free(&PageView v) {
    v.lang.free(); v.title.free(); v.description.free(); v.base.free(); v.head_extra.free();
    v.home_href.free(); v.logo_src.free(); v.site_title.free(); v.nav.free(); v.locale_menu.free();
    v.sidebar.free(); v.toc.free(); v.content.free(); v.prev_next.free(); v.hero.free();
    v.features.free(); v.footer_message.free(); v.footer_copyright.free(); v.outline_label.free();
    v.menu_label.free(); v.search_label.free(); v.theme_label.free(); v.return_top_label.free();
    v.skip_label.free(); v.not_found_title.free(); v.not_found_quote.free(); v.not_found_link.free();
    v.github.free(); v.page_class.free(); v.client_js.free();
}
