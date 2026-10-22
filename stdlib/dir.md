# dir / path -- Files and Directories

`std/dir.h` lists, creates, copies and removes directories and tells what a path names; `std/path.h` takes path strings apart and puts them together without touching the file system. Both are core modules (files directly in `std/`), for hosted targets: `dir_posix.sc` backs Linux, macOS and the BSDs, `dir_win32.sc` Windows. Whole-file reads and writes are on `File` — see [`File::read_to_string` / `File::write_bytes`](/stdlib/io#whole-files).

```c
#include <std/dir.h>
#include <std/path.h>
```

## Listing a directory {#listing}

```c
#define DIR_NONE  0   // nothing there
#define DIR_FILE  1   // a regular file
#define DIR_DIR   2   // a directory
#define DIR_LINK  3   // a symbolic link (not followed) / Windows reparse point
#define DIR_OTHER 4   // device, pipe, socket, ...

struct DirEntry {
    struct String name;   // the entry's own name, no directory part
    int kind;             // DIR_FILE / DIR_DIR / DIR_LINK / DIR_OTHER
    static struct DirEntry new();
    void free();
};

struct Dir {
    static ?&Dir open(const &char path);   // null if it isn't a readable directory
    int  next(&DirEntry out);              // 1 = an entry was read (its name replaced), 0 = end
    void close();
};
```

`next` skips `.` and `..` and returns entries in whatever order the OS keeps them. Reuse one `DirEntry` for the whole listing and free it at the end:

```c
#include <std/dir.h>
#include <std/io.h>

int main() {
    match (std::Dir::open("docs")) {
        case some(d): {
            struct DirEntry e = DirEntry::new();
            while (d.next(&e)) {
                std::print(e.kind == DIR_DIR ? "dir   " : "file  ");
                std::println(e.name.as_ptr());
            }
            e.free();
            d.close();
        }
        case null: { std::eprintln("cannot open docs/"); return 1; }
    }
    return 0;
}
```

## What a path names {#stat}

```c
struct FileStat {
    int       kind;      // DIR_NONE .. DIR_OTHER (a link itself, not its target)
    long long size;      // bytes
    long long mtime_ns;  // last modification, ns since the Unix epoch
};

int file_stat(const &char path, &FileStat out);   // 0, with kind = DIR_NONE, if nothing is there
int path_kind(const &char path);                  // just the kind
int path_exists(const &char path);
```

Links are not followed. On Linux the data comes from the `statx` system call (no dependency on glibc's `stat` symbols, which only exist from glibc 2.33), on macOS from `lstat`, on Windows from `GetFileAttributesExA`. On other systems `kind` and `size` come from `opendir`/`fopen` probes and `mtime_ns` is 0.

## Creating, copying and removing {#tree-operations}

```c
int dir_create(const &char path);        // one directory; 1 also if it already exists
int dir_create_all(const &char path);    // and every missing parent (mkdir -p)
int dir_remove(const &char path);        // an empty directory
int dir_remove_all(const &char path);    // everything below too (rm -rf); 1 if nothing was there
int file_remove(const &char path);       // a file or a link
int file_copy(const &char from, const &char to);       // create/truncate 'to'
int dir_copy_tree(const &char from, const &char to);   // cp -R from/. to/
int dir_walk(const &char root, &Vec<struct String> out);
```

Every operation returns 1 on success and 0 on failure. `dir_walk` appends the path of every file below `root`, relative to it and `/`-separated (`guide/intro.md`), to `out`, sorted; it descends into directories but not into links to them. `dir_copy_tree` copies what links point at.

```c
// Build output: start clean, copy static files, list the sources.
std::dir_remove_all("dist");
std::dir_copy_tree("public", "dist");

struct Vec<struct String> pages = Vec::new();
std::dir_walk("docs", &pages);
for (unsigned long i = 0UL; i < pages.len; i = i + 1UL) {
    struct String ext = std::path_ext(pages.at(i).as_ptr());
    if (ext.eq_cstr("md")) { std::println(pages.at(i).as_ptr()); }
    ext.free();
}
```

## Path strings {#path}

```c
int path_is_sep(char c);                  // '/', and '\' on Windows targets
int path_is_absolute(const &char p);      // "/x"; on Windows also "\x", "C:\x", "C:/x"

struct String path_join(const &char a, const &char b);   // one separator between; an absolute 'b' wins
struct String path_dirname(const &char p);   // "a/b/c" -> "a/b", "c" -> "", "/c" -> "/"
struct String path_basename(const &char p);  // "a/b.c" -> "b.c", "a/b/" -> "b"
struct String path_ext(const &char p);       // "a/b.tar.gz" -> "gz", ".bashrc" -> ""
struct String path_stem(const &char p);      // "a/b.tar.gz" -> "b.tar"
struct String path_normalize(const &char p); // "a//b/./c/../d/" -> "a/b/d", "" -> "."
struct String path_relative(const &char from, const &char to);   // ("a/b", "a/c/d") -> "../c/d"
```

Nothing here touches the file system. Every function returns a fresh `String` to free, results use `/` (Windows APIs accept it), and `path_normalize` works lexically: `..` removes the component before it, stays at the front of a relative path and is dropped at the root of an absolute one.
