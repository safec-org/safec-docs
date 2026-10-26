# serial -- Serialization (JSON / XML / HTML / CSV / YAML / Markdown / Protobuf)

`std/serial/` provides a format-agnostic `Value` tree plus five format backends (JSON, XML, HTML, CSV, YAML) that all read and write it. Since SafeC has no field-name reflection (only `fieldcount(T)` — a count, no names), a struct becomes serializable by writing a `to_value()` method by hand rather than via an automatic `#[derive]`-style macro.

```c
#include <std/serial/value.h>
#include <std/serial/json.h>
#include <std/serial/xml.h>
#include <std/serial/html.h>
```

## The `Value` Tree {#the-value-tree}

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
    const &char     object_key_at(unsigned long idx) const;   // idx-th entry, insertion order
    &Value          object_value_at(unsigned long idx) const;

    // Building and lifetime.
    void            array_push(struct Value v);                    // takes ownership of v
    void            object_set(const &char key, struct Value v);   // takes ownership of v
    struct Value    clone() const;                                 // deep copy
    void            free() consume;                                // releases the whole tree

    // Text formats (any Format, see below).
    generic<F: Format> struct String encode(F f) const;                                  // a fresh String
    generic<F: Format> static struct Value decode(F f, const &char text, ?&int ok);    // *ok: 1, or 0
}
```

From outside `namespace std` the constructors are `std::Value::object()`, `std::Value::from_str("...")`, and so on. Each format module also keeps its own free functions (`json_write` / `value_to_json` / `json_parse`, `xml_parse`, `yaml_parse`, `csv_parse`, `html_parse`); `encode` / `decode` reach the same code through the [`Format`](#format) trait.

::: warning Ownership
`array_push`/`object_set` **take ownership** of the `Value` you pass — don't free it yourself afterward, and call `.clone()` first if you still need your own copy. `.free()` recursively releases an entire tree (strings, array elements, object entries).
:::

`object_get` returns a *nullable* reference — unwrap it with `match` (or `.is_null()`/`.default()`) rather than chaining straight into a field or method, the same rule that applies to every `?&T` in SafeC (see [Safety](/reference/safety#nullability-enforcement)):

```c
long long age = 0;
match (parsed.object_get("age")) {
    case null:    break;
    case some(a): age = a.as_int();
}
```

`array_at` is non-nullable (it trusts the index is in range, checked via `array_len()`), so it can be chained directly: `v.array_at(0).as_string()`.

Objects keep their entries in insertion order. `object_key_at` / `object_value_at` walk them in that order — what you need when the order of a parsed map matters (a YAML config's sections, a JSON object you re-emit):

```c
for (unsigned long i = 0UL; i < cfg.object_len(); i = i + 1UL) {
    std::print(cfg.object_key_at(i));
    std::print(" = ");
    std::println(cfg.object_value_at(i).as_string());
}
```

## Making a Struct Serializable {#making-a-struct-serializable}

Write a `to_value()` method that builds a `Value` tree from the struct's fields:

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

A `from_value(const &Value)` method covers the decode direction the same way — read fields back out with `object_get`/`array_at`/`as_int`/etc.

## Formats as a Trait {#format}

Every text backend is also a small struct whose two methods are that format's writer and parser, and the `Format` trait in `value.h` names that shape:

```c
trait Format {
    void         write(const &Value v, &String out) const;   // appends v's text to out
    struct Value parse(const &char text, ?&int ok) const;    // *ok: 1, or 0 on malformed input
}
```

| Backend | Header | Construct | Same as |
|---|---|---|---|
| `Json` | `json.h` | `Json::new()` | `json_write` / `json_parse` |
| `Yaml` | `yaml.h` | `Yaml::new()` | `yaml_write` / `yaml_parse` |
| `Csv` | `csv.h` | `Csv::new()` | `csv_write` / `csv_parse` |
| `Xml` | `xml.h` | `Xml::new("root")` | `xml_write(v, root, out)` / `xml_parse`; carries the root element's tag, which must outlive it |
| `Html` | `html.h` | `Html::new()` | `html_write` / `html_parse` |

`v.encode(f)` returns a fresh `String` holding the tree in format `f`; `Value::decode(f, text, &ok)` parses it. Both are [methods with their own type parameter](/reference/generics#methods-with-their-own-type-parameters) bounded by `Format`, so the format is chosen at compile time and the call is direct — no table of function pointers. Generic code takes a format the same way, and any struct of your own with `write` and `parse` methods of these shapes is a `Format` too:

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

Markdown and Protobuf are not `Format`s: Markdown has its own AST rather than a `Value` tree, and Protobuf is a binary encoding written field by field against a schema.

## JSON {#json}

```c
void json_write(const &Value v, &String out);   // appends; doesn't clear 'out' first
struct String value_to_json(const &Value v);      // convenience: fresh String

struct Value json_parse(const &char text, ?&int ok);
    // *ok = 1 and the parsed tree on success; *ok = 0 and Value::null() on
    // failure. Ignores trailing content after the first complete value.
```

JSON is the only one of the five formats with no structural ambiguity — its syntax marks type explicitly, so it round-trips exactly, including numeric-looking or empty strings.

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

struct Value xml_parse(const &char text, ?&int ok);   // root tag itself is not returned, only its content
```

Mapping:

| `Value` kind | XML shape (under tag `T`) |
|---|---|
| `VAL_OBJECT` | `<T><key1>...</key1><key2>...</key2></T>` — each key becomes a named child element |
| `VAL_ARRAY` | `<T><item>...</item><item>...</item></T>` — array elements have no name of their own |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | `<T>text content</T>` |
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
// unwrap parsed.object_get("age") the same way as the JSON example above

xml.free();
v.free();
parsed.free();
```

`xml_parse`/`json_parse` only understand this module's own output shape — no attributes, no CDATA, no entities beyond the 5 `xml_write` emits (`&amp; &lt; &gt; &quot; &apos;`).

## HTML {#html}

```c
void html_escape(&String out, const &char s);   // usable standalone, independent of the Value renderer
void html_unescape(&String out, const &char s);   // &lt; &#39; &#x2014; &mdash; ... -> UTF-8 text
void html_strip_tags(&String out, const &char s); // an HTML fragment's text content
void html_write(const &Value v, &String out);
struct String value_to_html(const &Value v);

struct Value html_parse(const &char text, ?&int ok);
```

Unlike JSON/XML, HTML isn't a data-interchange format — `html_write` renders a `Value` tree as a human-readable document fragment:

| `Value` kind | HTML shape |
|---|---|
| `VAL_OBJECT` | `<dl><dt>key</dt><dd>...</dd>...</dl>` (definition list) |
| `VAL_ARRAY` | `<ul><li>...</li>...</ul>` |
| `VAL_STRING`/`INT`/`FLOAT`/`BOOL` | escaped text, no wrapping tag |
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

`html_parse` reads this same `<dl>`/`<ul>`/`<em>null</em>`/bare-text shape back into a `Value` tree — it is not a general HTML parser, and (like `xml_parse`) is meant for round-tripping this module's own output, not arbitrary HTML documents.

`html_escape`, `html_unescape` and `html_strip_tags` work on any text, independent of the `Value` renderer. `html_unescape` decodes numeric references (`&#39;`, `&#x2014;`) and the common named ones (`amp lt gt quot apos nbsp copy reg trade hellip mdash ndash`, the curly quotes, `laquo raquo middot bull times divide deg`, the arrows, `ne le ge`); anything else is copied unchanged. `html_strip_tags` drops tags and comments, drops `<script>`/`<style>` content, and puts one space at each block boundary (`</p>`, `<br>`, `</li>`, `</td>`, `</h2>`, ...) so words from adjacent blocks don't run together; it leaves references encoded, so follow it with `html_unescape` for plain text:

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

A CSV document is a `VAL_ARRAY` of rows, each row itself a `VAL_ARRAY` of `VAL_STRING` fields — CSV has no native typing (every field is text), so unlike `json_parse` there's no int/float/bool inference; convert individual fields yourself (`String::parse_int()`/`parse_float()`) if a column is known to hold numbers.

```c
void csv_write(const &Value v, &String out);   // 'v' must be VAL_ARRAY of VAL_ARRAY
struct String value_to_csv(const &Value v);

struct Value csv_parse(const &char text, ?&int ok);
```

Fields containing a comma, double quote, or newline are quoted per RFC 4180, with embedded double quotes doubled (`""`). Both `"\n"` and `"\r\n"` line endings are accepted on parse; a trailing newline is optional. On a malformed quoted field (an opening `"` with no matching close), `*ok` is set to 0 and the return value is whatever was parsed so far.

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

A subset of YAML 1.1 that covers configuration files and front matter: block scalars (plain, single- and double-quoted with their respective escaping rules), block sequences (`- item`, including the compact `- key: value` list-of-maps shorthand), block mappings (`key: value`, indentation-nested), flow collections on one line (`[a, b]`, `{k: v, n: [1, 2]}`, nested, with quoted or plain items), literal and folded block scalars (`|`, `>`, with `-` strip and `+` keep chomping), `#` comments (at the start of a line or after whitespace — `a#b` is a plain scalar), and the `~`/`null`/`true`/`false`/numeric core-schema scalar forms.

**Not supported** (a full YAML 1.2 processor is a much larger undertaking): flow collections spanning several lines, anchors/aliases, tags, multi-document streams, and merge keys. A document using any of these either parses as plain scalar text (an unterminated `[1, 2` stays the string `"[1, 2"`) or fails, per `yaml_parse`'s `ok` contract below.

```c
void yaml_write(const &Value v, &String out);
struct String value_to_yaml(const &Value v);

struct Value yaml_parse(const &char text, ?&int ok);
    // *ok = 0 on a structural error (inconsistent indentation, an
    // unterminated quoted scalar, a line that's neither a valid
    // sequence/mapping entry nor a bare scalar where one is expected) --
    // the return value is whatever was parsed before the error.
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

Flow collections and block scalars, as in a page's front matter:

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
// outline -> [2, 4] (VAL_ARRAY of VAL_INT)
// summary -> "Folded lines become one paragraph.\n"
// code    -> "int x;\nint y;\n"
fm.free();
```

## Markdown {#markdown}

```c
#include <std/serial/markdown.h>
```

Markdown is document markup, not key/value data, so it has its own AST instead of the `Value` tree. `MdNode::parse` reads CommonMark; `MdNode::parse_opts` adds GitHub-flavored and documentation-site extensions. Render the tree to HTML (for example as an [HTTP](/stdlib/http) response body), back to Markdown, or walk it yourself.

**CommonMark** (always on): ATX (`#`) and setext (`===` / `---` underlined) headings, paragraphs, thematic breaks, block quotes with lazy continuation, bullet (`-` `*` `+`) and ordered (`1.` / `1)`) lists — nested, tight or loose, items holding any blocks (code, quotes, lists) — fenced code (```` ``` ```` / `~~~`) with an info string, and indented code. Inlines follow CommonMark's delimiter rules: `*em*` / `_em_`, `**strong**` / `__strong__` (no intraword `_`, so `snake_case_name` stays text), `` `code` `` (taking precedence over emphasis), backslash escapes, entity and numeric references (`&copy;`, `&#x2014;`), links and images with titles whose text holds any inlines (``[`code` *and* em](/url "title")``), `<https://...>` and `<user@host>` autolinks, and hard line breaks (two trailing spaces or `\`). Not supported: reference-style links (`[x][ref]` with `[ref]: url`) and footnotes.

**Extensions** (`MdOptions` fields; `MdOptions::gfm()` turns on the first three):

| Option | Syntax |
|---|---|
| `tables` | GFM tables: a header row, a delimiter row (`\|:--\|:-:\|--:\|` sets left/center/right alignment), body rows; `\|` escapes a pipe inside a cell |
| `strikethrough` | `~~text~~` |
| `autolinks` | bare `https://…`, `http://…` and `www.…` URLs become links (trailing punctuation excluded) |
| `html` | raw HTML blocks and inline tags/comments pass through unescaped — off by default, so untrusted input stays inert |
| `containers` | `::: kind [title]` … `:::` blocks (`tip`, `warning`, `details`, … — any word), nestable; rendered as `<div class="custom-block kind">` with a `<p class="custom-block-title">` (the title, or the kind upper-cased), `details` as `<details>`/`<summary>` |
| `heading_ids` | every heading gets an `id`: `## Title {#custom-id}`, else `md_slugify` of its text, deduplicated with `-1`, `-2`, … |

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
    void          free() consume;            // frees the subtree and the node itself
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

`md_slugify` makes the GitHub/VitePress-style anchor: lower-cased, every run of whitespace, ASCII punctuation and control characters (and curly quotes) collapsed to one `-`, no leading or trailing `-`, a `_` before a leading digit; non-ASCII letters are kept.

### Rendering with a hook {#markdown-hooks}

`md_render_html_with` calls a hook for every node before writing its default HTML, which lets a renderer rewrite link targets, add heading anchors or syntax-highlight code without re-implementing the rest. On entry (`entering` = 1) the hook returns `0` to render the node normally, `1` when it wrote the node's whole HTML itself, or `2` when it wrote only the opening: the children are rendered as usual, then the hook is called again with `entering` = 0 and returns `1` if it wrote the closing too (`0` for the default one).

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

Protocol Buffers proto3 binary wire format — varints, tags, zigzag, length-delimited, fixed32/fixed64 — exactly as `protoc`-generated code produces it, so it interoperates with protobuf in other languages. There is no `.proto` compiler: write `to_proto()`/`from_proto()` by hand using your schema's field numbers. Pairs with [RPC](/stdlib/http#rpc).

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
void pb_write_bytes_field(&String out, int field_num, []const unsigned char data); // also nested messages

// Reader: a zero-copy cursor over an existing buffer.
struct PbReader {
    const &unsigned char data;
    unsigned long        len;
    unsigned long        pos;

    static struct PbReader new([]const unsigned char data);   // at data[0]

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
    // A slice of the reader's own buffer (zero-copy) — copy it if it must outlive
    // that buffer. null on a truncated encoding.
    ?[]const unsigned char read_length_delimited();
    int skip_field(int wire_type);   // skip an unknown field (forward compatibility)
};
```

The writers stay free functions: they append to any `String`, so there is no protobuf-specific struct for them to be methods of.

`read_length_delimited` returns a slice of the reader's own buffer (`null` when the encoding is truncated) — it aliases the buffer, so copy the bytes (e.g. `String::from_bytes`) if they must outlive it.

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
    r = std::PbReader::new(buf.as_bytes());   // []const char is a byte slice
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

`std/serial/serial.h` includes only `value.h`, `json.h`, `xml.h` and `html.h`; include `csv.h`, `yaml.h`, `markdown.h` and `protobuf.h` directly.

## Round-Trip Fidelity {#round-trip-fidelity}

JSON has no structural ambiguity — prefer it whenever exact round-tripping of arbitrary string content matters. XML and HTML share two inherent ambiguities from mapping every `Value` kind onto plain tagged text:

- **Empty string vs. empty container.** `<t></t>` (no children, no text) could be an empty string or an empty object/array; both parsers always resolve it to `VAL_STRING ""`.
- **Numeric-/bool-looking text.** Text content that looks like a number or `true`/`false` decodes back as `VAL_INT`/`VAL_FLOAT`/`VAL_BOOL`, the same as it would if it had actually been serialized from one — a string field whose value happens to be `"42"` round-trips through XML/HTML as the integer `42`, not the string `"42"`.

CSV avoids this by never inferring types at all (every field parses back as `VAL_STRING`, full stop). YAML, like XML/HTML, infers `null`/bool/numeric-looking scalars back to their typed `Value` kind — the same ambiguity, one more format.

## Performance Note {#performance-note}

Each format's string-escaping function (`json_append_escaped_`, `xml_append_escaped_`, `html_escape`) batches runs of bytes that need no escaping into a single `String::push_n()` call rather than one `push_char()` per byte — since most real-world string content has no special characters, this keeps the common case to one bounds-checked buffer growth and one `memcpy` per string instead of one function call per byte.
