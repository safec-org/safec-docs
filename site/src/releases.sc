// safec-site — release notes and the changelog page (see site.h).
//
// Two sources, both read at build time:
//   - SafeC's CHANGELOG.md (Keep a Changelog shape: '## [Unreleased]',
//     '## [1.0.0-beta-7] - 2026-11-02', '### Added' ...) from the SafeC
//     checkout the site is built against ($SAFEC_HOME, the pinned SAFEC_REF
//     in CI; $SAFEC_CHANGELOG overrides the path). Optional.
//   - the release history: build/releases.json when the build fetched it
//     (tools/releases-snapshot.py, from GitHub's releases API + git tags),
//     else the checked-in snapshot data/releases.json.
// Every release becomes a post under <prefix>blog/releases/<tag> (its
// CHANGELOG section when there is one, else the tag's curated summary,
// then GitHub's pull-request list); <prefix>changelog shows the whole
// CHANGELOG — 'Unreleased' included — or, without one, the release list.
#include "site.h"
#include <std/serial/json.h>
#include <std/io.h>
#include <std/io_file.h>
#include <std/path.h>
#include <std/sys.h>

// Whether 'l' has anything but whitespace.
static int has_text_(const &String l) {
    struct String t = l.trim();
    int r = t.len > 0UL;
    t.free();
    return r;
}

struct ClSection {
    struct String version;   // "Unreleased", "1.0.0-beta-7" (no leading 'v')
    struct String heading;   // the heading text without brackets
    struct String date;      // "2026-11-02" or ""
    struct String body;      // markdown below the heading
};

static struct String strip_v(const &char s) {
    struct String t = String::from(s);
    struct String r = t.starts_with("v") ? t.substr(1UL, t.len) : t.clone();
    t.free();
    return r;
}

// '[x]: https://...' link reference definitions (std markdown has no
// reference links; Keep a Changelog puts compare links there).
static int is_link_def(const &String line) {
    if (!line.starts_with("[")) { return 0; }
    long long close = line.index_of("]:");
    return close > 0LL;
}

// Relative markdown link targets point into the SafeC repository.
static void absolutize_links(&String md) {
    struct String out = String::new();
    unsigned long i = 0UL;
    while (i < md.len) {
        if (md.char_at(i) == (int)']' && i + 1UL < md.len && md.char_at(i + 1UL) == (int)'(') {
            out.push("](");
            i = i + 2UL;
            struct String rest = md.substr(i, i + 12UL > md.len ? md.len : i + 12UL);
            int abs = rest.starts_with("http") || rest.starts_with("/") || rest.starts_with("#") || rest.starts_with("mailto:");
            rest.free();
            if (!abs) { out.push("https://github.com/safec-org/SafeC/blob/main/"); }
            continue;
        }
        out.push_char((char)md.char_at(i));
        i = i + 1UL;
    }
    md.clear();
    md.push_str(&out);
    out.free();
}

static void parse_changelog(const &String text, &Vec<struct ClSection> out) {
    struct Vec<struct String> lines = Vec::new();
    unsigned long start = 0UL;
    for (unsigned long i = 0UL; i <= text.len; i = i + 1UL) {
        if (i == text.len || text.char_at(i) == (int)'\n') {
            struct String l = text.substr(start, i);
            if (l.ends_with("\r")) { l.truncate(l.len - 1UL); }
            lines.push(l);
            start = i + 1UL;
        }
    }
    long long cur = -1LL;
    for (unsigned long i = 0UL; i < lines.len; i = i + 1UL) {
        &String l = lines.at(i);
        if (l.starts_with("## ")) {
            struct ClSection s;
            struct String h = l.substr(3UL, l.len);
            h.replace_all("[", "");
            h.replace_all("]", "");
            s.heading = h.trim();
            h.free();
            // "1.0.0-beta-7 - 2026-11-02", "v1.0.0 (2026-11-02)", "Unreleased"
            long long sp = s.heading.find_char(' ');
            struct String first = sp >= 0LL ? s.heading.substr(0UL, (unsigned long)sp) : s.heading.clone();
            s.version = strip_v(first.as_ptr());
            first.free();
            s.date = String::new();
            for (unsigned long k = 0UL; k + 10UL <= s.heading.len; k = k + 1UL) {
                int c0 = s.heading.char_at(k);
                if (c0 >= (int)'0' && c0 <= (int)'9' && s.heading.char_at(k + 4UL) == (int)'-' &&
                    s.heading.char_at(k + 7UL) == (int)'-' && (k == 0UL || s.heading.char_at(k - 1UL) == (int)' ' || s.heading.char_at(k - 1UL) == (int)'(')) {
                    std::replace(&s.date, s.heading.substr(k, k + 10UL)).drop();
                    break;
                }
            }
            s.body = String::new();
            out.push(s);
            cur = (long long)out.len - 1LL;
            continue;
        }
        if (cur < 0LL || is_link_def(l)) { continue; }
        &ClSection s = out.at((unsigned long)cur);
        s.body.push_str(l);
        s.body.push_char('\n');
    }
    for (unsigned long i = 0UL; i < out.len; i = i + 1UL) {
        &ClSection s = out.at(i);
        struct String t = s.body.trim();
        s.body.clear();
        s.body.push_str(&t);
        t.free();
        absolutize_links(&s.body);
    }
    lines.free();
}

// Reads a releases JSON array; appends releases whose tag isn't in 'out' yet.
static void load_releases(const &char path, &Value out) {
    struct String text = String::new();
    if (!File::read_to_string(path, &text)) { text.free(); return; }
    int ok = 0;
    struct Value v = json_parse(text.as_ptr(), &ok);
    text.free();
    if (!ok || v.kind_of() != VAL_ARRAY) {
        eprint("safec-site: ignoring malformed ");
        eprintln(path);
        v.free();
        return;
    }
    for (unsigned long i = 0UL; i < v.array_len(); i = i + 1UL) {
        &Value r = v.array_at(i);
        struct String tag = String::from(util_vstr(r, "tag_name", ""));
        int dup = tag.len == 0UL;
        for (unsigned long k = 0UL; k < out.array_len(); k = k + 1UL) {
            if (tag.eq_cstr(util_vstr(out.array_at(k), "tag_name", ""))) { dup = 1; }
        }
        if (!dup) { out.array_push(r.clone()); }
        tag.free();
    }
    v.free();
}

static int cmp_release(const &Value a, const &Value b) {
    struct String x = String::from(util_vstr(a, "published_at", ""));
    struct String y = String::from(util_vstr(b, "published_at", ""));
    int c = y.cmp(&x);   // newest first
    x.free(); y.free();
    return c;
}

// GitHub's generated body: '* title by @who in https://.../pull/N' ->
// '* title ([#N](https://.../pull/N))'; '**Full Changelog**: URL' dropped
// (the post links the release page).
static void tidy_github_body(const &char body, &String out) {
    struct String b = String::from(body);
    unsigned long start = 0UL;
    for (unsigned long i = 0UL; i <= b.len; i = i + 1UL) {
        if (i < b.len && b.char_at(i) != (int)'\n') { continue; }
        struct String l = b.substr(start, i);
        start = i + 1UL;
        if (l.ends_with("\r")) { l.truncate(l.len - 1UL); }
        if (l.starts_with("## What") || l.starts_with("**Full Changelog**") || l.starts_with("## New Contributors")) { l.free(); continue; }
        if (l.starts_with("* @") && l.contains("first contribution")) { l.free(); continue; }
        long long by = l.index_of(" by @");
        long long in = l.index_of(" in https://");
        if (l.starts_with("* ") && by > 0LL && in > by) {
            struct String title = l.substr(2UL, (unsigned long)by);
            struct String url = l.substr((unsigned long)in + 4UL, l.len);
            long long slash = url.rfind_char('/');
            struct String num = url.substr((unsigned long)slash + 1UL, url.len);
            out.push("- ");
            out.push_str(&title);
            out.push(" ([#");
            out.push_str(&num);
            out.push("](");
            out.push_str(&url);
            out.push("))\n");
            title.free(); url.free(); num.free();
        } else if (has_text_(&l)) {
            out.push_str(&l);
            out.push_char('\n');
        }
        l.free();
    }
    b.free();
}

// The first list item of 'md', as plain-ish text (the post summary).
static struct String first_item(const &String md) {
    struct String s = String::new();
    long long at = md.index_of("- ");
    if (at < 0LL) { return s; }
    unsigned long i = (unsigned long)at + 2UL;
    while (i < md.len) {
        int c = md.char_at(i);
        if (c == (int)'\n') {
            // a wrapped item continues on an indented line
            if (i + 2UL < md.len && md.char_at(i + 1UL) == (int)' ' && md.char_at(i + 2UL) == (int)' ') {
                s.push_char(' ');
                i = i + 1UL;
                while (i < md.len && md.char_at(i) == (int)' ') { i = i + 1UL; }
                continue;
            }
            break;
        }
        if (c != (int)'`' && c != (int)'*') { s.push_char((char)c); }
        i = i + 1UL;
    }
    return s;
}

// A locale's blog string: locales/<key>.yaml 'blog.<key>'.
static const &char blog_str(const &Locale loc, const &char key, const &char fallback) {
    ?&Value b = loc.data.object_get("blog");
    match (b) { case some(x): { return util_vstr(x, key, fallback); } case null: { } }
    return fallback;
}

static struct String fill(const &char tpl, const &char v, const &char date) {
    struct String s = String::from(tpl);
    s.replace_all("{v}", v);
    s.replace_all("{date}", date);
    return s;
}

static void add_release_post(&Site site, &Vec<struct Page> pages, unsigned long li, const &Value rel,
                             const &Vec<struct ClSection> cl) {
    &Locale loc = site.locales.at(li);
    const &char tag = util_vstr(rel, "tag_name", "");
    struct String version = strip_v(tag);
    struct String date = String::from(util_vstr(rel, "published_at", ""));
    if (date.len > 10UL) { date.truncate(10UL); }

    struct String highlights = String::new();
    for (unsigned long i = 0UL; i < cl.len; i = i + 1UL) {
        if (cl.at(i).version.eq(&version)) { highlights.push_str(&cl.at(i).body); }
    }
    if (highlights.len == 0UL) {
        // The tag message: "SafeC 1.0.0-beta-6\n\n- ..." — drop the title line.
        struct String msg = String::from(util_vstr(rel, "tag_message", ""));
        long long nl = msg.find_char('\n');
        if (nl >= 0LL && msg.starts_with("SafeC")) {
            struct String rest = msg.substr((unsigned long)nl + 1UL, msg.len);
            struct String t = rest.trim();
            highlights.push_str(&t);
            rest.free(); t.free();
        } else {
            highlights.push_str(&msg);
        }
        msg.free();
    }
    struct String prs = String::new();
    tidy_github_body(util_vstr(rel, "body", ""), &prs);

    struct String rel_path = loc.dir.clone();
    if (rel_path.len > 0UL) { rel_path.push_char('/'); }
    rel_path.push("blog/releases/");
    rel_path.push(tag);
    rel_path.push(".md");
    struct Page p = page_new(site, rel_path.as_ptr());
    rel_path.free();
    p.generated = 1;

    struct String title = fill(blog_str(loc, "releaseTitle", "SafeC {v}"), version.as_ptr(), date.as_ptr());
    struct String summary = first_item(&highlights);
    if (summary.len == 0UL) {
        summary.free();
        summary = fill(blog_str(loc, "releaseSummary", "Release notes for SafeC {v}."), version.as_ptr(), date.as_ptr());
    }
    p.front.object_set("title", Value::from_str(title.as_ptr()));
    p.front.object_set("date", Value::from_str(date.as_ptr()));
    p.front.object_set("author", Value::from_str(blog_str(loc, "releaseAuthor", "SafeC release notes")));
    struct Value tags = Value::array();
    tags.array_push(Value::from_str("release"));
    p.front.object_set("tags", tags);
    p.front.object_set("summary", Value::from_str(summary.as_ptr()));
    p.front.object_set("layout", Value::from_str("post"));
    p.front.object_set("release", Value::from_str(tag));
    p.front.object_set("_ts", Value::from_str(util_vstr(rel, "published_at", "")));

    struct String md = String::new();
    struct String intro = fill(blog_str(loc, "releaseIntro", "SafeC **{v}** was released on {date}."), version.as_ptr(), date.as_ptr());
    md.push_str(&intro);
    md.push("\n\n");
    const &char note = blog_str(loc, "releaseNote", "");
    if (!util_empty(note)) { md.push(note); md.push("\n\n"); }
    md.push(blog_str(loc, "releaseInstall", "Install it:"));
    md.push("\n\n```bash\ncurl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash -s -- --version=");
    md.push(tag);
    md.push("\n```\n\n```powershell\n& ([scriptblock]::Create((irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1))) -Version ");
    md.push(tag);
    md.push("\n```\n\n");
    if (highlights.len > 0UL) {
        md.push("## "); md.push(blog_str(loc, "highlights", "Highlights")); md.push("\n\n");
        md.push_str(&highlights);
        md.push("\n\n");
    }
    if (prs.len > 0UL) {
        md.push("## "); md.push(blog_str(loc, "pullRequests", "Pull requests")); md.push("\n\n");
        md.push_str(&prs);
        md.push("\n");
    }
    md.push("[");
    md.push(blog_str(loc, "releaseLink", "The release on GitHub"));
    md.push("](");
    md.push(util_vstr(rel, "html_url", "https://github.com/safec-org/SafeC/releases"));
    md.push(") · [");
    md.push(blog_str(loc, "changelogTitle", "Changelog"));
    md.push("](");
    md.push_str(&loc.prefix);
    md.push("changelog)\n");
    p.body_md.push_str(&md);
    pages.push(p);
    md.free(); intro.free(); title.free(); summary.free(); prs.free(); highlights.free();
    version.free(); date.free();
}

static void add_changelog_page(&Site site, &Vec<struct Page> pages, unsigned long li, const &Value rels,
                               const &Vec<struct ClSection> cl) {
    &Locale loc = site.locales.at(li);
    struct String rel_path = loc.dir.clone();
    if (rel_path.len > 0UL) { rel_path.push_char('/'); }
    rel_path.push("changelog.md");
    struct Page p = page_new(site, rel_path.as_ptr());
    rel_path.free();
    p.generated = 1;
    p.front.object_set("title", Value::from_str(blog_str(loc, "changelogTitle", "Changelog")));
    p.front.object_set("layout", Value::from_str("page"));
    struct String md = String::from("# ");
    md.push(blog_str(loc, "changelogTitle", "Changelog"));
    md.push("\n\n");
    md.push(blog_str(loc, "changelogIntro", ""));
    md.push("\n\n");
    if (cl.len > 0UL) {
        for (unsigned long i = 0UL; i < cl.len; i = i + 1UL) {
            &ClSection s = cl.at(i);
            md.push("## ");
            md.push_str(&s.heading);
            md.push("\n\n");
            // Released versions link their post.
            for (unsigned long k = 0UL; k < rels.array_len(); k = k + 1UL) {
                const &char tag = util_vstr(rels.array_at(k), "tag_name", "");
                struct String v = strip_v(tag);
                if (v.eq(&s.version)) {
                    md.push("[");
                    md.push(blog_str(loc, "releaseNotes", "Release notes"));
                    md.push("](");
                    md.push_str(&loc.prefix);
                    md.push("blog/releases/");
                    md.push(tag);
                    md.push(")\n\n");
                }
                v.free();
            }
            md.push_str(&s.body);
            md.push("\n\n");
        }
    } else {
        md.push(blog_str(loc, "changelogMissing", "SafeC's CHANGELOG.md was not available to this build; the release history follows."));
        md.push("\n\n");
        for (unsigned long k = 0UL; k < rels.array_len(); k = k + 1UL) {
            &Value r = rels.array_at(k);
            const &char tag = util_vstr(r, "tag_name", "");
            struct String date = String::from(util_vstr(r, "published_at", ""));
            if (date.len > 10UL) { date.truncate(10UL); }
            md.push("- [");
            md.push(tag);
            md.push("](");
            md.push_str(&loc.prefix);
            md.push("blog/releases/");
            md.push(tag);
            md.push(") — ");
            md.push_str(&date);
            md.push("\n");
            date.free();
        }
    }
    p.body_md.push_str(&md);
    md.free();
    pages.push(p);
}

void releases_add_pages(&Site site, &Vec<struct Page> pages) {
    struct Value rels = Value::array();
    struct String fetched = path_join(site.root.as_ptr(), "build/releases.json");
    struct String snapshot = path_join(site.root.as_ptr(), "data/releases.json");
    load_releases(fetched.as_ptr(), &rels);
    load_releases(snapshot.as_ptr(), &rels);
    fetched.free(); snapshot.free();
    // Sort newest first (through a Vec: Value has no in-place sort).
    struct Vec<struct Value> sorted = Vec::new();
    for (unsigned long i = 0UL; i < rels.array_len(); i = i + 1UL) { sorted.push(rels.array_at(i).clone()); }
    rels.free();
    sorted.sort(cmp_release);
    rels = Value::array();
    for (unsigned long i = 0UL; i < sorted.len; i = i + 1UL) {
        rels.array_push(sorted.at(i).clone());   // sorted keeps (and drops) its own
        site.versions.push(String::from(util_vstr(sorted.at(i), "tag_name", "")));
    }
    sorted.free();

    struct String cl_path = String::new();
    const ?&char env = sys_getenv("SAFEC_CHANGELOG");
    match (env) {
        case some(e): { cl_path.push(e); }
        case null: {
            if (site.safec_home.len > 0UL) {
                struct String j = path_join(site.safec_home.as_ptr(), "CHANGELOG.md");
                cl_path.push_str(&j);
                j.free();
            }
        }
    }
    struct Vec<struct ClSection> cl = Vec::new();
    struct String text = String::new();
    if (cl_path.len > 0UL && File::read_to_string(cl_path.as_ptr(), &text)) {
        parse_changelog(&text, &cl);
    } else {
        eprintln("safec-site: no CHANGELOG.md in $SAFEC_HOME (or $SAFEC_CHANGELOG); the changelog page lists releases only");
    }
    text.free(); cl_path.free();

    for (unsigned long li = 0UL; li < site.locales.len; li = li + 1UL) {
        for (unsigned long k = 0UL; k < rels.array_len(); k = k + 1UL) {
            add_release_post(site, pages, li, rels.array_at(k), &cl);
        }
        add_changelog_page(site, pages, li, &rels, &cl);
    }
    cl.free();   // and each section's strings
    rels.free();
}
