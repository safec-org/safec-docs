// safec-site — small helpers over std (see site.h).
#include "site.h"
#include <std/io_file.h>
#include <std/dir.h>
#include <std/path.h>

int util_write_file(const &char path, const &String data) {
    struct String dir = path_dirname(path);
    if (dir.len > 0UL) { dir_create_all(dir.as_ptr()); }
    dir.free();
    return File::write_bytes(path, data.as_ptr(), data.len);
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
    if (obj.kind_of() != VAL_OBJECT) { return fallback; }
    ?&Value v = obj.object_get(key);
    match (v) {
        case some(x): {
            if (x.kind_of() == VAL_STRING) { return x.as_string(); }
        }
        case null: { }
    }
    return fallback;
}
