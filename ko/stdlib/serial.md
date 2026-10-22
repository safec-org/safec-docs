# serial -- 직렬화 (JSON / XML / HTML / CSV / YAML / Markdown / Protobuf)

`std/serial/`은 형식에 구애받지 않는 `Value` 트리와, 이 트리를 읽고 쓸 수 있는 다섯 가지 형식 백엔드(JSON, XML, HTML, CSV, YAML)를 제공합니다. SafeC에는 필드 이름 리플렉션이 없으므로(오직 개수만 알려주는 `fieldcount(T)`만 있고 이름은 알 수 없습니다), 구조체를 직렬화 가능하게 만들려면 자동 `#[derive]` 스타일 매크로 대신 `to_value()` 메서드를 직접 작성해야 합니다.

```c
#include <std/serial/value.h>
#include <std/serial/json.h>
#include <std/serial/xml.h>
#include <std/serial/html.h>
```

## `Value` 트리 {#the-value-tree}

```c
#define VAL_NULL   0
#define VAL_BOOL   1
#define VAL_INT    2
#define VAL_FLOAT  3
#define VAL_STRING 4
#define VAL_ARRAY  5
#define VAL_OBJECT 6

struct Value {
    int kind;   // one of the VAL_* constants above

    // Construction.
    static struct Value null();
    static struct Value from_bool(int v);
    static struct Value from_int(long long v);
    static struct Value from_float(double v);
    static struct Value from_str(const &char s);   // copies s
    static struct Value array();                   // empty; grow with v.array_push
    static struct Value object();                  // empty; grow with v.object_set

    int kind_of() const;
    int is_null() const;
    int as_bool() const;
    long long as_int() const;
    double as_float() const;
    const &char as_string() const;

    // Array/Object element access -- read-only views (the returned
    // reference aliases storage owned by this Value, don't free it
    // separately). 'array_at' trusts 'idx' is in range (see array_len());
    // 'object_get' is the one nullable lookup -- no entry for 'key' is a
    // real, expected outcome, not a caller error.
    unsigned long   array_len() const;
    &Value          array_at(unsigned long idx) const;
    unsigned long   object_len() const;
    ?&Value         object_get(const &char key) const;   // empty (null) if the key isn't present
    const &char     object_key_at(unsigned long idx) const;   // idx번째 항목, 삽입 순서
    &Value          object_value_at(unsigned long idx) const;

    // Building and lifetime.
    void            array_push(struct Value v);                    // takes ownership of v
    void            object_set(const &char key, struct Value v);   // takes ownership of v
    struct Value    clone() const;                                 // deep copy
    void            free();                                        // releases the whole tree

    // Text formats (any Format, see below).
    generic<F: Format> struct String encode(F f) const;                                  // a fresh String
    generic<F: Format> static struct Value decode(F f, const &char text, ?&int ok);    // *ok: 1, or 0
}
```

`namespace std` 밖에서는 생성자를 `std::Value::object()`, `std::Value::from_str("...")`처럼 호출합니다. 각 포맷 모듈은 자체 자유 함수(`json_write` / `value_to_json` / `json_parse`, `xml_parse`, `yaml_parse`, `csv_parse`, `html_parse`)도 그대로 유지하며, `encode` / `decode`는 [`Format`](#format) 트레이트를 통해 같은 코드에 도달합니다.

::: warning 소유권
`array_push`/`object_set`은 전달받은 `Value`의 **소유권을 가져갑니다** — 그 이후에는 직접 해제하면 안 됩니다. 자신만의 복사본이 여전히 필요하다면 먼저 `.clone()`을 호출합니다. `.free()`는 트리 전체(문자열, 배열 요소, 객체 항목)를 재귀적으로 해제합니다.
:::

`object_get`은 *널이 될 수 있는* 참조를 반환합니다 — 필드나 메서드로 바로 체이닝하지 말고 `match`(또는 `.is_null()`/`.default()`)로 언래핑합니다. 이는 SafeC의 모든 `?&T`에 적용되는 것과 동일한 규칙입니다([안전성](/ko/reference/safety#nullability-enforcement) 참고).

```c
long long age = 0;
match (parsed.object_get("age")) {
    case null:    break;
    case some(a): age = a.as_int();
}
```

`array_at`은 널이 될 수 없으므로(`array_len()`으로 확인된 범위 내 인덱스를 신뢰합니다), `v.array_at(0).as_string()`처럼 바로 체이닝할 수 있습니다.

객체는 항목을 삽입 순서대로 보관합니다. `object_key_at` / `object_value_at`은 그 순서대로 항목을 순회합니다 — 파싱한 맵의 순서가 중요할 때(YAML 설정의 섹션, 다시 내보낼 JSON 객체) 필요한 기능입니다:

```c
for (unsigned long i = 0UL; i < cfg.object_len(); i = i + 1UL) {
    std::print(cfg.object_key_at(i));
    std::print(" = ");
    std::println(cfg.object_value_at(i).as_string());
}
```

## 구조체를 직렬화 가능하게 만들기 {#making-a-struct-serializable}

구조체 필드로부터 `Value` 트리를 만드는 `to_value()` 메서드를 작성합니다.

```c
#include <std/serial/value.h>
#include <std/serial/json.h>
#include <std/collections/string.h>
extern int printf(const char* fmt, ...);

struct Point { double x; double y; struct Value to_value() const; };

struct Value Point::to_value() const {
    struct Value v = Value::object();
    v.object_set("x", Value::from_float(self.x));
    v.object_set("y", Value::from_float(self.y));
    return v;
}

int main() {
    struct Point p; p.x = 1.5; p.y = 2.5;

    struct Value pv = p.to_value();
    struct String json = value_to_json(&pv);
    unsafe { printf("%s\n", json.as_ptr()); }   // {"x":1.5,"y":2.5}

    json.free();
    pv.free();
    return 0;
}
```

`from_value(const &Value)` 메서드는 동일한 방식으로 디코딩 방향을 담당합니다 — `object_get`/`array_at`/`as_int`/등을 사용해 필드를 다시 읽어냅니다.

## 트레이트로서의 포맷 {#format}

모든 텍스트 백엔드는 해당 포맷의 작성기와 파서를 두 메서드로 가진 작은 구조체이기도 하며, `value.h`의 `Format` 트레이트가 그 형태에 이름을 붙입니다:

```c
trait Format {
    void         write(const &Value v, &String out) const;   // appends v's text to out
    struct Value parse(const &char text, ?&int ok) const;    // *ok: 1, or 0 on malformed input
}
```

| 백엔드 | 헤더 | 생성 | 동등한 함수 |
|---|---|---|---|
| `Json` | `json.h` | `Json::new()` | `json_write` / `json_parse` |
| `Yaml` | `yaml.h` | `Yaml::new()` | `yaml_write` / `yaml_parse` |
| `Csv` | `csv.h` | `Csv::new()` | `csv_write` / `csv_parse` |
| `Xml` | `xml.h` | `Xml::new("root")` | `xml_write(v, root, out)` / `xml_parse`; 루트 요소의 태그를 담으며, 태그 문자열은 `Xml`보다 오래 살아야 합니다 |
| `Html` | `html.h` | `Html::new()` | `html_write` / `html_parse` |

`v.encode(f)`는 트리를 포맷 `f`로 담은 새 `String`을 반환하고, `Value::decode(f, text, &ok)`는 그것을 파싱합니다. 둘 다 `Format`으로 바운드된 [자기 타입 매개변수를 가진 메서드](/ko/reference/generics#methods-with-their-own-type-parameters)이므로, 포맷은 컴파일 타임에 정해지고 호출은 직접 호출입니다 — 함수 포인터 테이블이 없습니다. 제네릭 코드도 같은 방식으로 포맷을 받으며, 이런 형태의 `write`와 `parse` 메서드를 가진 직접 만든 구조체도 `Format`이 됩니다:

```c
#include <std/serial/value.h>
#include <std/serial/json.h>
#include <std/serial/yaml.h>
#include <std/serial/xml.h>
#include <std/io.h>

// Written once, works for every format: encode, then decode the text back.
generic<F: Format>
long long round_trip_age(F f, const &Value v) {
    struct String text = v.encode(f);
    int ok = 0;
    struct Value back = std::Value::decode(f, text.as_ptr(), &ok);
    long long age = -1LL;
    match (back.object_get("age")) {
        case some(a): age = a.as_int();
        case null:    { }
    }
    back.free();
    text.free();
    return ok ? age : -1LL;
}

int main() {
    struct Value v = std::Value::object();
    v.object_set("name", std::Value::from_str("Ada"));
    v.object_set("age", std::Value::from_int(36LL));

    struct String json = v.encode(std::Json::new());
    std::println(json.as_ptr());                               // {"name":"Ada","age":36}

    long long a1 = round_trip_age(std::Json::new(), &v);
    long long a2 = round_trip_age(std::Yaml::new(), &v);
    long long a3 = round_trip_age(std::Xml::new("person"), &v);

    json.free();
    v.free();
    return a1 == 36LL && a2 == 36LL && a3 == 36LL ? 0 : 1;
}
```

Markdown과 Protobuf는 `Format`이 아닙니다: Markdown은 `Value` 트리 대신 자체 AST를 가지며, Protobuf는 스키마에 맞춰 필드 단위로 작성하는 바이너리 인코딩입니다.

## JSON {#json}

```c
void json_write(const &Value v, &String out);   // 추가만 함; 'out'을 먼저 비우지 않음
struct String value_to_json(const &Value v);      // 편의 함수: 새 String 반환

struct Value json_parse(const &char text, ?&int ok);
    // 성공하면 *ok = 1과 파싱된 트리; 실패하면 *ok = 0과 Value::null().
    // 첫 완전한 값 이후의 후행 내용은 무시됩니다.
```

JSON은 다섯 가지 형식 중 구조적 모호함이 없는 유일한 형식입니다 — 문법이 타입을 명시적으로 표시하므로, 숫자처럼 보이는 문자열이나 빈 문자열을 포함해 정확히 왕복 변환됩니다.

```c
struct Value v = Value::object();
v.object_set("name", Value::from_str("Ada"));
v.object_set("age", Value::from_int(36));

struct String json = value_to_json(&v);
printf("%s\n", json.as_ptr());   // {"name":"Ada","age":36}

int ok = 0;
struct Value parsed = json_parse(json.as_ptr(), &ok);
long long age = 0;
match (parsed.object_get("age")) {
    case null:    break;
    case some(a): age = a.as_int();   // 36
}

json.free();
v.free();
parsed.free();
```

## XML {#xml}

```c
void xml_write(const &Value v, const &char tag, &String out);
struct String value_to_xml(const &Value v, const &char root_tag);

struct Value xml_parse(const &char text, ?&int ok);   // 루트 태그 자체는 반환되지 않고, 그 내용만 반환됨
```

매핑:

| `Value` 종류 | XML 형태 (태그 `T` 아래) |
|---|---|
| `VAL_OBJECT` | `<T><key1>...</key1><key2>...</key2></T>` — 각 키는 이름 있는 자식 엘리먼트가 됨 |
| `VAL_ARRAY` | `<T><item>...</item><item>...</item></T>` — 배열 요소는 자체 이름을 갖지 않음 |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | `<T>텍스트 내용</T>` |
| `VAL_NULL` | `<T/>` |

```c
struct Value v = Value::object();
v.object_set("name", Value::from_str("Ada <Countess>"));
v.object_set("age", Value::from_int(36));

struct String xml = value_to_xml(&v, "person");
printf("%s\n", xml.as_ptr());
// <person><name>Ada &lt;Countess&gt;</name><age>36</age></person>

int ok = 0;
struct Value parsed = xml_parse(xml.as_ptr(), &ok);
// parsed.object_get("age")를 위 JSON 예제와 같은 방식으로 언래핑

xml.free();
v.free();
parsed.free();
```

`xml_parse`/`json_parse`는 이 모듈 자체가 만들어낸 출력 형태만 이해합니다 — 속성도, CDATA도, `xml_write`가 내보내는 5개(`&amp; &lt; &gt; &quot; &apos;`) 외의 엔터티도 없습니다.

## HTML {#html}

```c
void html_escape(&String out, const &char s);   // 단독 사용 가능, Value 렌더러와 무관
void html_unescape(&String out, const &char s);   // &lt; &#39; &#x2014; &mdash; ... -> UTF-8 텍스트
void html_strip_tags(&String out, const &char s); // HTML 조각의 텍스트 내용
void html_write(const &Value v, &String out);
struct String value_to_html(const &Value v);

struct Value html_parse(const &char text, ?&int ok);
```

JSON/XML과 달리 HTML은 데이터 교환 형식이 아닙니다 — `html_write`는 `Value` 트리를 사람이 읽을 수 있는 문서 조각으로 렌더링합니다.

| `Value` 종류 | HTML 형태 |
|---|---|
| `VAL_OBJECT` | `<dl><dt>key</dt><dd>...</dd>...</dl>` (정의 목록) |
| `VAL_ARRAY` | `<ul><li>...</li>...</ul>` |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | 이스케이프된 텍스트, 감싸는 태그 없음 |
| `VAL_NULL` | `<em>null</em>` |

```c
struct Value v = Value::object();
v.object_set("comment", Value::from_str("<script>alert(1)</script>"));

struct String html = value_to_html(&v);
printf("%s\n", html.as_ptr());
// <dl><dt>comment</dt><dd>&lt;script&gt;alert(1)&lt;/script&gt;</dd></dl>

html.free();
v.free();
```

`html_parse`는 이와 동일한 `<dl>`/`<ul>`/`<em>null</em>`/맨 텍스트 형태를 다시 `Value` 트리로 읽어들입니다 — 이는 범용 HTML 파서가 아니며, (`xml_parse`와 마찬가지로) 임의의 HTML 문서가 아니라 이 모듈 자체의 출력을 왕복시키기 위한 것입니다.

`html_escape`, `html_unescape`, `html_strip_tags`는 `Value` 렌더러와 무관하게 모든 텍스트에 사용할 수 있습니다. `html_unescape`는 숫자 참조(`&#39;`, `&#x2014;`)와 자주 쓰이는 이름 참조(`amp lt gt quot apos nbsp copy reg trade hellip mdash ndash`, 곡선 따옴표, `laquo raquo middot bull times divide deg`, 화살표, `ne le ge`)를 디코딩하며, 그 밖의 것은 그대로 복사합니다. `html_strip_tags`는 태그와 주석을 제거하고, `<script>`/`<style>` 내용을 버리며, 인접한 블록의 단어가 붙지 않도록 블록 경계(`</p>`, `<br>`, `</li>`, `</td>`, `</h2>`, ...)마다 공백 하나를 넣습니다. 참조는 인코딩된 채로 두므로, 평문이 필요하면 이어서 `html_unescape`를 호출하십시오:

```c
struct String tags = String::new();
html_strip_tags(&tags, "<h2>Title</h2><p>Fish &amp; chips</p>");
struct String text = String::new();
html_unescape(&text, tags.as_ptr());     // "Title Fish & chips"
```

## CSV {#csv}

```c
#include <std/serial/csv.h>
```

CSV 문서는 행(row)들의 `VAL_ARRAY`이며, 각 행 자체는 `VAL_STRING` 필드들의 `VAL_ARRAY`입니다 — CSV는 고유한 타입 체계가 없으므로(모든 필드가 텍스트), `json_parse`와 달리 int/float/bool 추론이 없습니다. 어떤 열이 숫자를 담고 있다고 알고 있다면 각 필드를 직접 변환합니다(`String::parse_int()`/`parse_float()`).

```c
void csv_write(const &Value v, &String out);   // 'v'는 반드시 VAL_ARRAY의 VAL_ARRAY여야 함
struct String value_to_csv(const &Value v);

struct Value csv_parse(const &char text, ?&int ok);
```

콤마, 큰따옴표, 개행을 포함하는 필드는 RFC 4180에 따라 따옴표로 묶이며, 내장된 큰따옴표는 이중으로 표기됩니다(`""`). 파싱 시 `"\n"`과 `"\r\n"` 줄바꿈 모두 허용되며, 후행 개행은 선택 사항입니다. 잘못된 형식의 따옴표 필드(닫는 `"`가 없는 여는 `"`)를 만나면 `*ok`는 0으로 설정되고 반환값은 그때까지 파싱된 내용이 됩니다.

```c
int ok = 0;
struct Value rows = csv_parse("name,age\nAda,36\n", &ok);
&Value row0 = rows.array_at(0);
&Value name = row0.array_at(0);   // VAL_STRING "name"
printf("%s\n", name.as_string());

rows.free();
```

## YAML {#yaml}

```c
#include <std/serial/yaml.h>
```

설정 파일과 프런트매터를 다루는 YAML 1.1의 서브셋입니다: 블록 스칼라(평문, 각자의 이스케이프 규칙을 따르는 단일/이중 따옴표), 블록 시퀀스(`- item`, 맵 목록의 축약형인 `- key: value` 포함), 블록 매핑(`key: value`, 들여쓰기로 중첩), 한 줄짜리 플로우 컬렉션(`[a, b]`, `{k: v, n: [1, 2]}`, 중첩 가능, 따옴표 또는 평문 항목), 리터럴/폴디드 블록 스칼라(`|`, `>`, `-` 제거와 `+` 유지 chomping 지원), `#` 주석(줄 맨 앞 또는 공백 뒤 — `a#b`는 평문 스칼라), `~`/`null`/`true`/`false`/숫자 코어 스키마 스칼라 형태.

**지원하지 않는 것**(완전한 YAML 1.2 프로세서는 훨씬 더 큰 작업입니다): 여러 줄에 걸친 플로우 컬렉션, 앵커/앨리어스, 태그, 다중 문서 스트림, 병합 키. 이들 중 하나를 사용하는 문서는 평문 스칼라 텍스트로 파싱되거나(닫히지 않은 `[1, 2`는 문자열 `"[1, 2"`로 남습니다), 아래 `yaml_parse`의 `ok` 계약에 따라 실패합니다.

```c
void yaml_write(const &Value v, &String out);
struct String value_to_yaml(const &Value v);

struct Value yaml_parse(const &char text, ?&int ok);
    // 구조적 오류(들여쓰기 불일치, 종료되지 않은 따옴표 스칼라, 시퀀스/매핑
    // 항목도 아니고 맨 스칼라가 기대되는 위치의 맨 스칼라도 아닌 줄)가
    // 있으면 *ok = 0 -- 반환값은 오류 전까지 파싱된 내용입니다.
```

```c
struct Value v = Value::object();
v.object_set("name", Value::from_str("Ada"));
v.object_set("age", Value::from_int(36));

struct String yaml = value_to_yaml(&v);
printf("%s", yaml.as_ptr());
// name: Ada
// age: 36

yaml.free();
v.free();
```

페이지 프런트매터에서처럼 플로우 컬렉션과 블록 스칼라를 쓰는 예:

```c
int ok = 0;
struct Value fm = yaml_parse(
    "outline: [2, 4]\n"
    "hero: {name: SafeC, actions: [start, docs]}\n"
    "summary: >\n"
    "  Folded lines become\n"
    "  one paragraph.\n"
    "code: |\n"
    "  int x;\n"
    "  int y;\n", &ok);
// outline -> [2, 4] (VAL_INT의 VAL_ARRAY)
// summary -> "Folded lines become one paragraph.\n"
// code    -> "int x;\nint y;\n"
fm.free();
```

## Markdown {#markdown}

```c
#include <std/serial/markdown.h>
```

Markdown은 키/값 데이터가 아니라 문서 마크업이므로 `Value` 트리 대신 자체 AST를 가집니다. `MdNode::parse`는 CommonMark를 읽고, `MdNode::parse_opts`는 GitHub 방식(GFM)과 문서 사이트용 확장을 더합니다. 트리는 HTML(예: [HTTP](/ko/stdlib/http) 응답 본문)이나 다시 Markdown으로 렌더링하거나, 직접 순회할 수 있습니다.

**CommonMark**(항상 켜짐): ATX(`#`)와 setext(`===` / `---` 밑줄) 제목, 문단, 구분선, 느슨한 이어쓰기(lazy continuation)를 지원하는 인용 블록, 글머리표(`-` `*` `+`)와 번호(`1.` / `1)`) 목록 — 중첩, tight/loose, 항목 안에 어떤 블록(코드, 인용, 목록)이든 포함 가능 — 정보 문자열이 있는 펜스 코드(```` ``` ```` / `~~~`), 들여쓰기 코드. 인라인은 CommonMark의 구분자 규칙을 따릅니다: `*em*` / `_em_`, `**strong**` / `__strong__`(단어 안의 `_`는 강조가 아니므로 `snake_case_name`은 텍스트로 남습니다), `` `code` ``(강조보다 우선), 백슬래시 이스케이프, 엔티티/숫자 참조(`&copy;`, `&#x2014;`), 제목(title)이 있는 링크와 이미지(링크 텍스트 안에 어떤 인라인이든 가능: ``[`code` *and* em](/url "title")``), `<https://...>`와 `<user@host>` 자동 링크, 강제 줄바꿈(끝의 공백 두 개 또는 `\`). 지원하지 않는 것: 참조 스타일 링크(`[x][ref]`와 `[ref]: url`), 각주.

**확장**(`MdOptions` 필드. `MdOptions::gfm()`은 앞의 세 가지를 켭니다):

| 옵션 | 문법 |
|---|---|
| `tables` | GFM 표: 헤더 행, 구분 행(`\|:--\|:-:\|--:\|`가 왼쪽/가운데/오른쪽 정렬을 지정), 본문 행. 셀 안의 파이프는 `\|`로 이스케이프 |
| `strikethrough` | `~~text~~` |
| `autolinks` | 맨 `https://…`, `http://…`, `www.…` URL이 링크가 됨(끝의 문장 부호는 제외) |
| `html` | 원시 HTML 블록과 인라인 태그/주석을 이스케이프 없이 통과 — 기본값은 꺼짐이라 신뢰할 수 없는 입력은 무해하게 남음 |
| `containers` | `::: kind [title]` … `:::` 블록(`tip`, `warning`, `details`, … — 아무 단어나 가능), 중첩 가능. `<div class="custom-block kind">`와 `<p class="custom-block-title">`(제목, 없으면 kind를 대문자로)로 렌더링되며, `details`는 `<details>`/`<summary>` |
| `heading_ids` | 모든 제목에 `id` 부여: `## Title {#custom-id}`, 없으면 텍스트의 `md_slugify` 결과(중복 시 `-1`, `-2`, …) |

```c
#define MD_DOC             0
#define MD_HEADING         1  // level 1-6; id (heading_ids)
#define MD_PARAGRAPH       2
#define MD_LIST            3  // ordered, start, tight
#define MD_LIST_ITEM       4
#define MD_CODE_BLOCK      5  // text = code, info = info string ("c", "bash")
#define MD_BLOCKQUOTE      6
#define MD_HR              7
#define MD_TEXT            8  // inline text in 'text' (references decoded)
#define MD_BOLD            9
#define MD_ITALIC          10
#define MD_CODE_SPAN       11 // inline code in 'text'
#define MD_LINK            12 // children = link text; text = URL; title
#define MD_TABLE           13 // rows; the first, 'header' row is the thead
#define MD_TABLE_ROW       14
#define MD_TABLE_CELL      15 // inline children; align = MD_ALIGN_*
#define MD_CONTAINER       16 // info = kind; first child MD_CONTAINER_TITLE when titled
#define MD_HTML_BLOCK      17 // raw HTML in 'text'
#define MD_HTML_INLINE     18
#define MD_IMAGE           19 // children = alt text; text = src; title
#define MD_STRIKE          20
#define MD_BREAK           21 // hard line break
#define MD_CONTAINER_TITLE 22

struct MdOptions {
    int tables, html, containers, heading_ids, strikethrough, autolinks;
    static struct MdOptions commonmark();   // all off (what parse() uses)
    static struct MdOptions gfm();          // tables, strikethrough, autolinks
};

struct MdNode {
    int kind;
    int level;      // MD_HEADING
    int ordered;    // MD_LIST
    int start;      // MD_LIST: first number of an ordered list
    int tight;      // MD_LIST: no blank lines between items/blocks (no <p> in items)
    int align;      // MD_TABLE_CELL: MD_ALIGN_NONE/LEFT/CENTER/RIGHT
    int header;     // MD_TABLE_ROW/CELL: in the header row
    struct String text, info, id, title;
    struct Vec<&heap MdNode> children;

    static &heap MdNode parse(const &char text);                                // CommonMark
    static &heap MdNode parse_opts(const &char text, const &MdOptions opts);   // with extensions

    struct String render_html() const;       // text escaped; raw HTML only with the html option
    struct String render_markdown() const;   // round-trips: parse(render_markdown()) renders the same
    void          plain_text(&String out) const;   // text content, no markup
    void          free();                    // frees the subtree and the node itself
};

void md_slugify(const &char text, &String out);   // "2. Temporal Safety" -> "_2-temporal-safety"

generic<C>
struct String md_render_html_with(const &MdNode node,
                                  fn int(?&C, const &MdNode, int, &String) hook, ?&C ctx);
```

```c
#include <std/serial/markdown.h>
#include <std/io.h>

int main() {
    &heap MdNode doc = std::MdNode::parse("# Title\n\nSome **bold** text.\n");
    struct String html = doc.render_html();
    std::println(html.as_ptr());   // <h1>Title</h1>\n<p>Some <strong>bold</strong> text.</p>
    html.free();
    doc.free();
    return 0;
}
```

`md_slugify`는 GitHub/VitePress 방식의 앵커를 만듭니다: 소문자로 바꾸고, 공백·ASCII 문장 부호·제어 문자(및 곡선 따옴표)의 연속을 `-` 하나로 합치며, 앞뒤의 `-`를 없애고, 숫자로 시작하면 앞에 `_`를 붙입니다. 비ASCII 문자는 그대로 둡니다.

### 훅으로 렌더링하기 {#markdown-hooks}

`md_render_html_with`는 각 노드의 기본 HTML을 쓰기 전에 훅을 호출하므로, 나머지를 다시 구현하지 않고도 링크 대상을 바꾸거나, 제목에 앵커를 붙이거나, 코드에 구문 강조를 적용할 수 있습니다. 진입 시(`entering` = 1) 훅은 노드를 평소대로 렌더링하려면 `0`, 노드의 HTML 전체를 직접 썼으면 `1`, 여는 부분만 썼으면 `2`를 반환합니다. `2`인 경우 자식은 평소대로 렌더링되고, 이어서 `entering` = 0으로 훅이 다시 호출되며, 닫는 부분도 직접 썼으면 `1`(기본 닫기를 쓰려면 `0`)을 반환합니다.

```c
struct Site { const char* base; };

int site_hook(?&Site site, const &MdNode n, int entering, &String out) {
    if (n.kind == MD_LINK && entering) {          // prefix every absolute link
        out.push("<a href=\"");
        match (site) { case some(s): { unsafe { out.push(s.base); } } case null: { } }
        html_escape(out, n.text.as_ptr());
        out.push("\">");
        return 2;                                 // children + default </a>
    }
    if (n.kind == MD_HEADING && !entering) {      // anchor before </hN>
        out.push(" <a class=\"anchor\" href=\"#");
        out.push(n.id.as_ptr());
        out.push("\">#</a></h");
        out.push_int((long long)n.level);
        out.push(">\n");
        return 1;
    }
    if (n.kind == MD_HEADING) {
        out.push("<h"); out.push_int((long long)n.level);
        out.push(" id=\""); out.push(n.id.as_ptr()); out.push("\">");
        return 2;
    }
    return 0;
}

struct MdOptions o = MdOptions::gfm();
o.heading_ids = 1;
&heap MdNode doc = MdNode::parse_opts(text, &o);
struct Site site;
site.base = "/docs";
struct String html;
unsafe { html = md_render_html_with(doc, site_hook, (?&Site)&site); }
```

## Protobuf {#protobuf}

```c
#include <std/serial/protobuf.h>
```

Protocol Buffers proto3 바이너리 와이어 포맷입니다 — 바린트, 태그, 지그재그, 길이 구분, fixed32/fixed64 — `protoc`이 생성한 코드와 정확히 같으므로 다른 언어의 protobuf와 상호 운용됩니다. `.proto` 컴파일러는 없습니다: 스키마의 필드 번호를 써서 `to_proto()`/`from_proto()`를 직접 작성합니다. [RPC](/ko/stdlib/http#rpc)와 함께 씁니다.

```c
#define PB_WIRE_VARINT  0   // int32/int64/uint32/uint64/sint32/sint64/bool/enum
#define PB_WIRE_FIXED64 1   // fixed64/sfixed64/double
#define PB_WIRE_LEN     2   // string/bytes/embedded messages/packed repeated
#define PB_WIRE_FIXED32 5   // fixed32/sfixed32/float
// Group wire types 3/4 are not supported (proto3 never emits them).

// Writer: each *_field call appends its own tag, then the value.
void pb_write_varint(&String out, unsigned long long v);
void pb_write_tag(&String out, int field_num, int wire_type);
void pb_write_int64_field(&String out, int field_num, long long v);   // negative values take 10 bytes, like protoc
void pb_write_uint64_field(&String out, int field_num, unsigned long long v);
void pb_write_sint64_field(&String out, int field_num, long long v);  // zigzag
void pb_write_bool_field(&String out, int field_num, int v);
void pb_write_double_field(&String out, int field_num, double v);
void pb_write_float_field(&String out, int field_num, float v);
void pb_write_string_field(&String out, int field_num, const &char s);
void pb_write_bytes_field(&String out, int field_num, const &unsigned char data, unsigned long len); // also nested messages

// Reader: a zero-copy cursor over an existing buffer.
struct PbReader {
    const &unsigned char data;
    unsigned long        len;
    unsigned long        pos;

    static struct PbReader new(const &unsigned char data, unsigned long len);   // at data[0]

    int has_more() const;

    // Each returns 1 on success, 0 on truncated or malformed input.
    int read_varint(&unsigned long long out);
    int read_tag(&int field_num, &int wire_type);
    int read_int64(&long long out);
    int read_uint64(&unsigned long long out);
    int read_sint64(&long long out);
    int read_bool(&int out);
    int read_double(&double out);
    int read_float(&float out);
    // *dataOut points into the reader's buffer — copy it if it must outlive that buffer.
    int read_length_delimited(&&const unsigned char dataOut, &unsigned long lenOut);
    int skip_field(int wire_type);   // skip an unknown field (forward compatibility)
};
```

쓰기 함수들은 자유 함수로 남아 있습니다: 어떤 `String`에든 덧붙이므로, 메서드를 둘 protobuf 전용 구조체가 없기 때문입니다.

`read_length_delimited`는 `dataOut`(참조에 대한 참조)을 통해 리더 자신의 버퍼 안을 가리키는 참조를 씁니다 — 버퍼를 별칭으로 가리키므로, 버퍼보다 오래 살아야 하면 바이트를 복사합니다.

```c
#include <std/serial/protobuf.h>

// .proto: message Point { int32 x = 1; int32 y = 2; }
struct Point { int x; int y; };

int main() {
    struct String buf = std::String::new();
    std::pb_write_int64_field(&buf, 1, 3LL);
    std::pb_write_int64_field(&buf, 2, -4LL);

    struct Point p;
    p.x = 0; p.y = 0;
    struct PbReader r;
    // String holds char; reinterpreting its bytes as unsigned char needs unsafe.
    unsafe { r = std::PbReader::new((const &unsigned char)buf.as_ptr(), buf.length()); }
    while (r.has_more()) {
        int field = 0; int wire = 0;
        if (!r.read_tag(&field, &wire)) break;
        long long v = 0LL;
        if (wire == PB_WIRE_VARINT && r.read_int64(&v)) {
            if (field == 1) p.x = (int)v;
            if (field == 2) p.y = (int)v;
        } else if (!r.skip_field(wire)) break;
    }
    buf.free();
    return (p.x == 3 && p.y == -4) ? 0 : 1;
}
```

`std/serial/serial.h`는 `value.h`, `json.h`, `xml.h`, `html.h`만 포함합니다. `csv.h`, `yaml.h`, `markdown.h`, `protobuf.h`는 직접 include합니다.

## 왕복 변환 충실도 {#round-trip-fidelity}

JSON은 구조적 모호함이 없습니다 — 임의의 문자열 내용을 정확히 왕복시키는 것이 중요하다면 항상 JSON을 우선합니다. XML과 HTML은 모든 `Value` 종류를 평문 태그 텍스트로 매핑하는 데서 비롯된 두 가지 고유한 모호함을 공유합니다.

- **빈 문자열 대 빈 컨테이너.** `<t></t>`(자식도 텍스트도 없음)는 빈 문자열일 수도, 빈 객체/배열일 수도 있습니다. 두 파서 모두 항상 이를 `VAL_STRING ""`으로 해석합니다.
- **숫자/불린처럼 보이는 텍스트.** 숫자나 `true`/`false`처럼 보이는 텍스트 내용은, 실제로 그렇게 직렬화되었을 때와 마찬가지로 `VAL_INT`/`VAL_FLOAT`/`VAL_BOOL`로 다시 디코딩됩니다 — 값이 우연히 `"42"`인 문자열 필드는 XML/HTML을 거쳐 문자열 `"42"`가 아니라 정수 `42`로 왕복됩니다.

CSV는 타입 추론을 아예 하지 않음으로써 이 문제를 피합니다(모든 필드는 예외 없이 `VAL_STRING`으로 다시 파싱됩니다). YAML은 XML/HTML과 마찬가지로 `null`/불린/숫자처럼 보이는 스칼라를 해당 타입의 `Value` 종류로 추론합니다 — 같은 모호함이 형식 하나만큼 더 늘어난 셈입니다.

## 성능 참고 사항 {#performance-note}

각 형식의 문자열 이스케이프 함수(`json_append_escaped_`, `xml_append_escaped_`, `html_escape`)는 이스케이프가 필요 없는 바이트 구간을 바이트 하나당 `push_char()` 호출 하나가 아니라 단일 `String::push_n()` 호출로 일괄 처리합니다 — 실제 문자열 내용 대부분에는 특수 문자가 없으므로, 이렇게 하면 일반적인 경우 문자열당 경계 검사가 포함된 버퍼 증가 한 번과 `memcpy` 한 번만으로 처리되며, 바이트당 함수 호출이 발생하지 않습니다.
