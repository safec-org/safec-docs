# dir / path -- 파일과 디렉터리

`std/dir.h`는 디렉터리를 나열·생성·복사·삭제하고 경로가 무엇을 가리키는지 알려 주며, `std/path.h`는 파일 시스템을 건드리지 않고 경로 문자열을 나누고 합칩니다. 둘 다 코어 모듈(`std/` 바로 아래 파일)이고 호스트 타깃용입니다: `dir_posix.sc`가 Linux, macOS, BSD를, `dir_win32.sc`가 Windows를 담당합니다. 파일 전체 읽기/쓰기는 `File`에 있습니다 — [`File::read_to_string` / `File::write_bytes`](/ko/stdlib/io#whole-files)를 참고하십시오.

```c
#include <std/dir.h>
#include <std/path.h>
```

## 디렉터리 나열 {#listing}

```c
#define DIR_NONE  0   // 아무것도 없음
#define DIR_FILE  1   // 일반 파일
#define DIR_DIR   2   // 디렉터리
#define DIR_LINK  3   // 심볼릭 링크(따라가지 않음) / Windows 리파스 포인트
#define DIR_OTHER 4   // 장치, 파이프, 소켓, ...

struct DirEntry {
    struct String name;   // 항목 자체의 이름, 디렉터리 부분 없음
    int kind;             // DIR_FILE / DIR_DIR / DIR_LINK / DIR_OTHER
    static struct DirEntry new();
    void free();
};

struct Dir {
    static ?&Dir open(const &char path);   // 읽을 수 있는 디렉터리가 아니면 null
    int  next(&DirEntry out);              // 1 = 항목을 읽음(이름이 교체됨), 0 = 끝
    void close();
};
```

`next`는 `.`과 `..`을 건너뛰고, OS가 보관하는 순서대로 항목을 반환합니다. 나열하는 동안 `DirEntry` 하나를 재사용하고 마지막에 해제하십시오:

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

## 경로가 가리키는 것 {#stat}

```c
struct FileStat {
    int       kind;      // DIR_NONE .. DIR_OTHER (링크의 대상이 아니라 링크 자체)
    long long size;      // 바이트
    long long mtime_ns;  // 마지막 수정 시각, Unix epoch 이후 ns
};

int file_stat(const &char path, &FileStat out);   // 아무것도 없으면 0, kind = DIR_NONE
int path_kind(const &char path);                  // 종류만
int path_exists(const &char path);
```

링크는 따라가지 않습니다. Linux에서는 `statx` 시스템 콜에서(glibc 2.33부터만 실제 심볼로 존재하는 glibc의 `stat` 심볼에 의존하지 않음), macOS에서는 `lstat`에서, Windows에서는 `GetFileAttributesExA`에서 정보를 얻습니다. 그 밖의 시스템에서는 `kind`와 `size`를 `opendir`/`fopen` 탐색으로 구하고 `mtime_ns`는 0입니다.

## 생성, 복사, 삭제 {#tree-operations}

```c
int dir_create(const &char path);        // 디렉터리 하나, 이미 있어도 1
int dir_create_all(const &char path);    // 없는 상위 디렉터리까지 모두 (mkdir -p)
int dir_remove(const &char path);        // 빈 디렉터리
int dir_remove_all(const &char path);    // 아래의 모든 것까지 (rm -rf), 아무것도 없었으면 1
int file_remove(const &char path);       // 파일 또는 링크
int file_copy(const &char from, const &char to);       // 'to'를 생성/잘라내기
int dir_copy_tree(const &char from, const &char to);   // cp -R from/. to/
int dir_walk(const &char root, &Vec<struct String> out);
```

모든 연산은 성공 시 1, 실패 시 0을 반환합니다. `dir_walk`는 `root` 아래 모든 파일의 경로를 `root` 기준 상대 경로로, `/`로 구분해(`guide/intro.md`) 정렬된 순서로 `out`에 덧붙입니다. 디렉터리로는 내려가지만 디렉터리를 가리키는 링크로는 내려가지 않습니다. `dir_copy_tree`는 링크가 가리키는 내용을 복사합니다.

```c
// 빌드 출력: 깨끗하게 시작하고, 정적 파일을 복사하고, 소스를 나열.
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

## 경로 문자열 {#path}

```c
int path_is_sep(char c);                  // '/', Windows 타깃에서는 '\'도
int path_is_absolute(const &char p);      // "/x", Windows에서는 "\x", "C:\x", "C:/x"도

struct String path_join(const &char a, const &char b);   // 사이에 구분자 하나, 절대 경로 'b'가 우선
struct String path_dirname(const &char p);   // "a/b/c" -> "a/b", "c" -> "", "/c" -> "/"
struct String path_basename(const &char p);  // "a/b.c" -> "b.c", "a/b/" -> "b"
struct String path_ext(const &char p);       // "a/b.tar.gz" -> "gz", ".bashrc" -> ""
struct String path_stem(const &char p);      // "a/b.tar.gz" -> "b.tar"
struct String path_normalize(const &char p); // "a//b/./c/../d/" -> "a/b/d", "" -> "."
struct String path_relative(const &char from, const &char to);   // ("a/b", "a/c/d") -> "../c/d"
```

여기의 어떤 함수도 파일 시스템을 건드리지 않습니다. 모든 함수는 해제해야 하는 새 `String`을 반환하고, 결과는 `/`를 사용하며(Windows API도 받아들임), `path_normalize`는 어휘적으로 동작합니다: `..`은 앞의 구성 요소를 지우고, 상대 경로의 맨 앞에서는 남으며, 절대 경로의 루트에서는 사라집니다.
