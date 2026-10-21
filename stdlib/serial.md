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
}

struct Value value_null();
struct Value value_bool(int v);
struct Value value_int(long long v);
struct Value value_float(double v);
struct Value value_string(const &char s);   // copies s
struct Value value_array();                 // empty; grow with value_array_push
struct Value value_object();                // empty; grow with value_object_set

void value_array_push(&Value arr, struct Value v);
void value_object_set(&Value obj, const &char key, struct Value v);

struct Value value_clone(const &Value v);
void value_free(&Value v);
```

::: warning Ownership
`value_array_push`/`value_object_set` **take ownership** of the `Value` you pass — don't free it yourself afterward, and call `value_clone()` first if you still need your own copy. `value_free()` recursively releases an entire tree (strings, array elements, object entries).
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

## Making a Struct Serializable {#making-a-struct-serializable}

Write a `to_value()` method that builds a `Value` tree from the struct's fields:

```c
#include <std/serial/value.h>
#include <std/serial/json.h>
#include <std/collections/string.h>
extern int printf(const char* fmt, ...);

struct Point { double x; double y; struct Value to_value() const; };

struct Value Point::to_value() const {
    struct Value v = value_object();
    value_object_set(&v, "x", value_float(self.x));
    value_object_set(&v, "y", value_float(self.y));
    return v;
}

int main() {
    struct Point p; p.x = 1.5; p.y = 2.5;

    struct Value pv = p.to_value();
    struct String json = value_to_json(&pv);
    unsafe { printf("%s\n", json.as_ptr()); }   // {"x":1.5,"y":2.5}

    json.free();
    value_free(&pv);
    return 0;
}
```

A `from_value(const &Value)` method covers the decode direction the same way — read fields back out with `object_get`/`array_at`/`as_int`/etc.

## JSON {#json}

```c
void json_write(const &Value v, &String out);   // appends; doesn't clear 'out' first
struct String value_to_json(const &Value v);      // convenience: fresh String

struct Value json_parse(const &char text, ?&int ok);
    // *ok = 1 and the parsed tree on success; *ok = 0 and value_null() on
    // failure. Ignores trailing content after the first complete value.
```

JSON is the only one of the five formats with no structural ambiguity — its syntax marks type explicitly, so it round-trips exactly, including numeric-looking or empty strings.

```c
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada"));
value_object_set(&v, "age", value_int(36));

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
value_free(&v);
value_free(&parsed);
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
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada <Countess>"));
value_object_set(&v, "age", value_int(36));

struct String xml = value_to_xml(&v, "person");
printf("%s\n", xml.as_ptr());
// <person><name>Ada &lt;Countess&gt;</name><age>36</age></person>

int ok = 0;
struct Value parsed = xml_parse(xml.as_ptr(), &ok);
// unwrap parsed.object_get("age") the same way as the JSON example above

xml.free();
value_free(&v);
value_free(&parsed);
```

`xml_parse`/`json_parse` only understand this module's own output shape — no attributes, no CDATA, no entities beyond the 5 `xml_write` emits (`&amp; &lt; &gt; &quot; &apos;`).

## HTML {#html}

```c
void html_escape(&String out, const &char s);   // usable standalone, independent of the Value renderer
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
struct Value v = value_object();
value_object_set(&v, "comment", value_string("<script>alert(1)</script>"));

struct String html = value_to_html(&v);
printf("%s\n", html.as_ptr());
// <dl><dt>comment</dt><dd>&lt;script&gt;alert(1)&lt;/script&gt;</dd></dl>

html.free();
value_free(&v);
```

`html_parse` reads this same `<dl>`/`<ul>`/`<em>null</em>`/bare-text shape back into a `Value` tree — it is not a general HTML parser, and (like `xml_parse`) is meant for round-tripping this module's own output, not arbitrary HTML documents.

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

value_free(&rows);
```

## YAML {#yaml}

```c
#include <std/serial/yaml.h>
```

A block-style subset of YAML 1.1: block scalars (plain, single- and double-quoted with their respective escaping rules), block sequences (`- item`, including the compact `- key: value` list-of-maps shorthand), block mappings (`key: value`, indentation-nested), `#` comments, and the `~`/`null`/`true`/`false`/numeric core-schema scalar forms.

**Not supported** (a full YAML 1.2 processor is a much larger undertaking): flow style (`[a, b]` / `{k: v}`), anchors/aliases, tags, multi-document streams, block scalar literals (`|`/`>`), and merge keys. A document using any of these either parses as plain scalar text or fails, per `yaml_parse`'s `ok` contract below.

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
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada"));
value_object_set(&v, "age", value_int(36));

struct String yaml = value_to_yaml(&v);
printf("%s", yaml.as_ptr());
// name: Ada
// age: 36

yaml.free();
value_free(&v);
```

## Markdown {#markdown}

```c
#include <std/serial/markdown.h>
```

Markdown is document markup, not key/value data, so it has its own small AST instead of the `Value` tree. Parse a CommonMark-like subset, then render it to HTML (for example as an [HTTP](/stdlib/http) response body) or back to Markdown.

Supported blocks: `#`–`######` headings, paragraphs, flat unordered (`-`, `*`, `+`) and ordered (`1.`) lists, fenced code blocks, blockquotes, horizontal rules. Supported inline: `**bold**`/`__bold__`, `*italic*`/`_italic_`, `` `code` ``, `[text](url)`. Not supported: tables, images, raw HTML, reference-style links, nested lists.

```c
#define MD_DOC        0
#define MD_HEADING    1
#define MD_PARAGRAPH  2
#define MD_LIST       3   // 'ordered' tells - from 1.
#define MD_LIST_ITEM  4
#define MD_CODE_BLOCK 5
#define MD_BLOCKQUOTE 6
#define MD_HR         7
#define MD_TEXT       8   // inline text in 'text'
#define MD_BOLD       9
#define MD_ITALIC     10
#define MD_CODE_SPAN  11  // inline code in 'text'
#define MD_LINK       12  // children are the link text; 'text' is the URL

struct MdNode {
    int kind;
    int level;                          // MD_HEADING: 1-6
    int ordered;                        // MD_LIST
    struct String text;
    struct Vec<&heap MdNode> children;
};

&heap MdNode  md_parse(const &char text);                  // MD_DOC root; free with md_free()
struct String md_render_html(const &heap MdNode doc);      // text is HTML-escaped
struct String md_render_markdown(const &heap MdNode doc);  // canonical form, e.g. *b* -> **b**
void          md_free(&heap MdNode node);
```

```c
#include <std/serial/markdown.h>
#include <std/io.h>

int main() {
    &heap MdNode doc = std::md_parse("# Title\n\nSome **bold** text.\n");
    struct String html = std::md_render_html(doc);
    std::println(html.as_ptr());   // contains <h1>Title</h1> and <strong>bold</strong>
    html.free();
    std::md_free(doc);
    return 0;
}
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
void pb_write_bytes_field(&String out, int field_num, const &unsigned char data, unsigned long len); // also nested messages

// Reader: a zero-copy cursor over an existing buffer.
struct PbReader {
    const &unsigned char data;
    unsigned long        len;
    unsigned long        pos;
};

struct PbReader pb_reader_new(const &unsigned char data, unsigned long len);
int pb_reader_has_more(const &PbReader r);

// Each returns 1 on success, 0 on truncated or malformed input.
int pb_read_varint(&PbReader r, &unsigned long long out);
int pb_read_tag(&PbReader r, &int field_num, &int wire_type);
int pb_read_int64(&PbReader r, &long long out);
int pb_read_uint64(&PbReader r, &unsigned long long out);
int pb_read_sint64(&PbReader r, &long long out);
int pb_read_bool(&PbReader r, &int out);
int pb_read_double(&PbReader r, &double out);
int pb_read_float(&PbReader r, &float out);
// *dataOut points into the reader's buffer — copy it if it must outlive that buffer.
int pb_read_length_delimited(&PbReader r, &&const unsigned char dataOut, &unsigned long lenOut);
int pb_skip_field(&PbReader r, int wire_type);   // skip an unknown field (forward compatibility)
```

`pb_read_length_delimited` writes a reference into the reader's own buffer through `dataOut` (a reference to a reference) — it aliases the buffer, so copy the bytes if they must outlive it.

```c
#include <std/serial/protobuf.h>

// .proto: message Point { int32 x = 1; int32 y = 2; }
struct Point { int x; int y; };

int main() {
    struct String buf = std::string_new();
    std::pb_write_int64_field(&buf, 1, 3LL);
    std::pb_write_int64_field(&buf, 2, -4LL);

    struct Point p;
    p.x = 0; p.y = 0;
    struct PbReader r;
    // String holds char; reinterpreting its bytes as unsigned char needs unsafe.
    unsafe { r = std::pb_reader_new((const &unsigned char)buf.as_ptr(), buf.length()); }
    while (std::pb_reader_has_more(&r)) {
        int field = 0; int wire = 0;
        if (!std::pb_read_tag(&r, &field, &wire)) break;
        long long v = 0LL;
        if (wire == PB_WIRE_VARINT && std::pb_read_int64(&r, &v)) {
            if (field == 1) p.x = (int)v;
            if (field == 2) p.y = (int)v;
        } else if (!std::pb_skip_field(&r, wire)) break;
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
