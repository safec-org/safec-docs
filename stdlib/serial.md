# serial -- Serialization (JSON / XML / HTML)

`std/serial/` provides a format-agnostic `Value` tree plus three format backends (JSON, XML, HTML) that all read and write it. Since SafeC has no field-name reflection (only `fieldcount(T)` — a count, no names), a struct becomes serializable by writing a `to_value()` method by hand rather than via an automatic `#[derive]`-style macro.

```c
#include "serial/value.h"
#include "serial/json.h"
#include "serial/xml.h"
#include "serial/html.h"
```

## The `Value` Tree

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
    const char* as_string() const;

    unsigned long   array_len() const;
    struct Value*   array_at(unsigned long idx) const;
    unsigned long   object_len() const;
    struct Value*   object_get(const char* key) const;   // NULL if the key isn't present
}

struct Value value_null();
struct Value value_bool(int v);
struct Value value_int(long long v);
struct Value value_float(double v);
struct Value value_string(const char* s);   // copies s
struct Value value_array();                 // empty; grow with value_array_push
struct Value value_object();                // empty; grow with value_object_set

void value_array_push(struct Value* arr, struct Value v);
void value_object_set(struct Value* obj, const char* key, struct Value v);

struct Value value_clone(const struct Value* v);
void value_free(struct Value* v);
```

::: warning Ownership
`value_array_push`/`value_object_set` **take ownership** of the `Value` you pass — don't free it yourself afterward, and call `value_clone()` first if you still need your own copy. `value_free()` recursively releases an entire tree (strings, array elements, object entries).
:::

## Making a Struct Serializable

Write a `to_value()` method that builds a `Value` tree from the struct's fields:

```c
struct Point { double x; double y; };

struct Value Point::to_value() const {
    struct Value v = value_object();
    value_object_set(&v, "x", value_float(self.x));
    value_object_set(&v, "y", value_float(self.y));
    return v;
}

int main() {
    Point p; p.x = 1.5; p.y = 2.5;

    struct Value pv = p.to_value();
    struct String json = value_to_json(&pv);
    printf("%s\n", json.as_ptr());   // {"x":1.5,"y":2.5}

    json.free();
    value_free(&pv);
    return 0;
}
```

A `from_value(const struct Value*)` method covers the decode direction the same way — read fields back out with `object_get`/`array_at`/`as_int`/etc.

## JSON

```c
void json_write(const struct Value* v, struct String* out);   // appends; doesn't clear 'out' first
struct String value_to_json(const struct Value* v);            // convenience: fresh String

struct Value json_parse(const char* text, int* ok);
    // *ok = 1 and the parsed tree on success; *ok = 0 and value_null() on
    // failure. Ignores trailing content after the first complete value.
```

JSON is the only one of the three formats with no structural ambiguity — its syntax marks type explicitly, so it round-trips exactly, including numeric-looking or empty strings.

```c
struct Value v = value_object();
value_object_set(&v, "name", value_string("Ada"));
value_object_set(&v, "age", value_int(36));

struct String json = value_to_json(&v);
printf("%s\n", json.as_ptr());   // {"name":"Ada","age":36}

int ok = 0;
struct Value parsed = json_parse(json.as_ptr(), &ok);
struct Value* age = parsed.object_get("age");
printf("%lld\n", age->as_int());   // 36

json.free();
value_free(&v);
value_free(&parsed);
```

## XML

```c
void xml_write(const struct Value* v, const char* tag, struct String* out);
struct String value_to_xml(const struct Value* v, const char* root_tag);

struct Value xml_parse(const char* text, int* ok);   // root tag itself is not returned, only its content
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
printf("%lld\n", parsed.object_get("age")->as_int());   // 36

xml.free();
value_free(&v);
value_free(&parsed);
```

`xml_parse`/`json_parse` only understand this module's own output shape — no attributes, no CDATA, no entities beyond the 5 `xml_write` emits (`&amp; &lt; &gt; &quot; &apos;`).

## HTML

```c
void html_escape(struct String* out, const char* s);   // usable standalone, independent of the Value renderer
void html_write(const struct Value* v, struct String* out);
struct String value_to_html(const struct Value* v);

struct Value html_parse(const char* text, int* ok);
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

## Round-Trip Fidelity

JSON has no structural ambiguity — prefer it whenever exact round-tripping of arbitrary string content matters. XML and HTML share two inherent ambiguities from mapping every `Value` kind onto plain tagged text:

- **Empty string vs. empty container.** `<t></t>` (no children, no text) could be an empty string or an empty object/array; both parsers always resolve it to `VAL_STRING ""`.
- **Numeric-/bool-looking text.** Text content that looks like a number or `true`/`false` decodes back as `VAL_INT`/`VAL_FLOAT`/`VAL_BOOL`, the same as it would if it had actually been serialized from one — a string field whose value happens to be `"42"` round-trips through XML/HTML as the integer `42`, not the string `"42"`.

## Performance Note

Each format's string-escaping function (`json_append_escaped_`, `xml_append_escaped_`, `html_escape`) batches runs of bytes that need no escaping into a single `String::push_n()` call rather than one `push_char()` per byte — since most real-world string content has no special characters, this keeps the common case to one bounds-checked buffer growth and one `memcpy` per string instead of one function call per byte.
