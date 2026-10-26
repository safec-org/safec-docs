// safec-site — syntax highlighting shared by the generator (code blocks at
// build time) and the playground's wasm editor (as you type). Depends on
// std/collections/string only, so it builds for wasm32 too.
#pragma once
#include <std/collections/string.h>

// Appends 'code' (len bytes) HTML-escaped, with <span class="tok-..."> runs
// for 'lang' ("c"/"safec"/"sc", "cpp", "rust", "zig", "js", "bash",
// "powershell", "toml"; anything else is escaped, unhighlighted).
void highlight_code(const &char lang, const &char code, unsigned long len, &String out);
