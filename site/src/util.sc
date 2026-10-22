// safec-site — small file/path/string helpers (see site.h; the
// STD-CANDIDATE ones are interim until the proposed std additions land).
#include "site.h"
#include <std/io_file.h>
#include <std/sys.h>

int util_read_file(const &char path, &String out) {
    ?&File fo = File::open(path, "rb");
    match (fo) {
        case some(f): {
            unsigned char buf[16384];
            for (;;) {
                long long r = f.read(&buf[0], 16384UL);
                if (r <= 0LL) { break; }
                unsafe { out.push_n((const char*)&buf[0], (unsigned long)r); }
            }
            f.close();
            return 1;
        }
        case null: { return 0; }
    }
    return 0;
}

int util_write_file(const &char path, const &String data) {
    struct String dir = util_dirname(path);
    if (dir.len > 0UL) { util_mkdir_p(dir.as_ptr()); }
    dir.free();
    ?&File fo = File::open(path, "wb");
    match (fo) {
        case some(f): {
            long long w;
            unsafe { w = f.write((const unsigned char*)data.as_ptr(), data.len); }
            f.close();
            return w == (long long)data.len ? 1 : 0;
        }
        case null: { return 0; }
    }
    return 0;
}

// Single-quotes 'p' for /bin/sh (' -> '\'').
static void sh_quote(&String cmd, const &char p) {
    cmd.push_char('\'');
    unsigned long i = 0UL;
    unsafe {
        while (p[i] != '\0') {
            if (p[i] == '\'') { cmd.push("'\\''"); } else { cmd.push_char(p[i]); }
            i = i + 1UL;
        }
    }
    cmd.push_char('\'');
}

int util_mkdir_p(const &char path) {
    // TODO(std/dir): dir_create_all(path).
    struct String cmd = String::from("mkdir -p ");
    sh_quote(&cmd, path);
    int rc = sys_system(cmd.as_ptr());
    cmd.free();
    return rc == 0 ? 1 : 0;
}

int util_copy_tree(const &char from, const &char to) {
    // TODO(std/dir): dir_copy_tree(from, to).
    util_mkdir_p(to);
    struct String cmd = String::from("cp -R ");
    struct String src = util_path_join(from, ".");
    sh_quote(&cmd, src.as_ptr());
    cmd.push(" ");
    sh_quote(&cmd, to);
    int rc = sys_system(cmd.as_ptr());
    cmd.free();
    src.free();
    return rc == 0 ? 1 : 0;
}

struct String util_path_join(const &char a, const &char b) {
    struct String s = String::from(a);
    if (s.len > 0UL && !s.ends_with("/")) { s.push_char('/'); }
    unsafe {
        unsigned long i = 0UL;
        while (b[i] == '/') { i = i + 1UL; }
        s.push((const char*)b + i);
    }
    return s;
}

struct String util_dirname(const &char p) {
    struct String s = String::from(p);
    long long slash = s.rfind_char('/');
    if (slash < 0LL) { s.clear(); return s; }
    s.truncate((unsigned long)slash);
    return s;
}

void util_push_esc(&String out, const &char s) {
    unsigned long i = 0UL;
    unsafe {
        while (s[i] != '\0') {
            char c = s[i];
            if (c == '&') { out.push("&amp;"); }
            else if (c == '<') { out.push("&lt;"); }
            else if (c == '>') { out.push("&gt;"); }
            else if (c == '"') { out.push("&quot;"); }
            else if (c == '\'') { out.push("&#39;"); }
            else { out.push_char(c); }
            i = i + 1UL;
        }
    }
}

const &char util_vstr(const &Value obj, const &char key, const &char fallback) {
    ?&Value v = obj.object_get(key);
    match (v) {
        case some(x): {
            if (x.kind_of() == VAL_STRING) { return x.as_string(); }
        }
        case null: { }
    }
    return fallback;
}
