# scx Templating

`.scx` is SafeC's answer to JSX/TSX/RSX: HTML-like markup embedded directly
in source, for writing server-rendered pages without hand-building strings
tag by tag. Like JSX-to-`React.createElement` or `rsx!`-to-DOM-ops, it's a
transpiler — a `.scx` file is ordinary SafeC source, plus one extra piece of
syntax, rewritten into plain SafeC calls *before* `safec` ever sees the
file. `safeguard build`/`check`/`test` do this automatically for every
`.scx` file they discover; `safec` itself has no knowledge that `.scx`
exists.

This is deliberately the smallest useful version of "JSX for SafeC": it
produces an HTML **string** (`struct String`), not a virtual DOM — good fit
for server-side rendering (build a page, hand it to `std::http_serve` as a
response body), not for client-side reactivity or diffed re-rendering.

## Syntax

Markup may appear in exactly one place: as the entire operand of a `return`
statement.

```c
struct String render_home(const char* visitor_name) {
    return <html>
        <head><title>My Page</title></head>
        <body>
            <h1>Hello, {visitor_name}!</h1>
            <p>Welcome.</p>
        </body>
    </html>;
}
```

- Tags are identifiers (`[a-zA-Z][a-zA-Z0-9-]*`, hyphens allowed for custom
  elements), opened and closed like HTML/XML: `<div>...</div>` or
  self-closing `<br/>`.
- Attributes: `name="literal text"` or `name={expr}`.
- `{expr}` interpolates a SafeC expression's value into the output,
  **HTML-escaped** (`&`, `<`, `>`, `"`, `'` become entities) — the safe
  default, same as JSX auto-escaping `{expr}` children.
- `{!expr}` interpolates **raw, unescaped** — for embedding pre-rendered
  trusted HTML (see [Composing pages](#composing-pages) below).
- Everything outside a `return <markup>;` statement is unmodified, ordinary
  SafeC — imports, structs, plain functions. A `.scx` file's non-markup
  majority is exactly a `.sc` file; string/char literals and `//`/`/* */`
  comments are scanned over untouched, so a stray `<` or `return` inside
  one is never mistaken for markup.

Every `{expr}`/`{!expr}` expression must evaluate to `const char*` — scx
does no type-checking of its own (it runs as a text-level pass, before
Sema ever runs), so a type mismatch surfaces as an ordinary `safec` compile
error on the generated code, pointing at the synthesized call site.

## Why only `return <markup>;`

Real JSX/TSX integrate markup into the host language's full expression
grammar (assignable to a variable, passed as an argument, used in a
ternary, embedded in a loop) because their transpilers are built on a real
JavaScript/TypeScript parser that already understands expression position.
scx's transpiler is a lightweight, standalone text-level pass — it doesn't
share `safec`'s parser or its notion of expression context, and SafeC has
no statement-expression construct (`({ ... })`) to fall back on for
building a value out of multiple statements mid-expression.

Restricting markup to `return <markup>;` sidesteps all of that: the
generated builder statements go exactly where the original `return`
statement was, in the same function, with the same parameters and locals
already in scope — no hoisting, no capture analysis, no new grammar in
`safec` itself. In exchange, a "component" in scx is simply *a function
that returns markup* — write `render_x(...)` returning `struct String`,
and call it like any other function.

## Composing pages

Because a component is just a function, composition means calling one
function from another — render the child first, then splice its already-
built HTML in with `{!...}`:

```c
struct String render_item(const char* name, int qty) {
    return <li>{name} &times; {qty}</li>;
}

struct String render_cart(struct String items_html) {
    return <ul>{!items_html.as_ptr()}</ul>;
}
```

The caller builds `items_html` with ordinary SafeC code (a loop over
`render_item(...)` calls, concatenated with `String::push_str`), then
interpolates the finished, already-escaped-where-it-needed-to-be result raw
with `{!...}` — `{...}` would double-escape it. This mirrors how a
templating engine without a virtual DOM has to compose: build inner content
first, then splice, rather than a framework diffing a nested element tree
for you.

## Runtime

Generated code calls into a small runtime under `std/scx/scx.h`:

```c
namespace std {
void scx_append_esc(struct String* buf, const char* s); // HTML-escape + append
}
```

`safeguard` injects `#include <std/scx/scx.h>` and
`#include <std/collections/string.h>` automatically into any file that used
markup — hand-written `.scx` source never includes these itself. Static
text and attributes (known at transpile time) are HTML-escaped once, up
front, and coalesced into a single `String::push("...")` call per run of
static content; only `{expr}` interpolation sites cost a runtime call —
generated code looks like:

```c
struct String __scx0 = std::string_new();
unsafe {
    __scx0.push("<h1>Hello, ");
    std::scx_append_esc(&__scx0, (visitor_name));
    __scx0.push("!</h1>");
}
return __scx0;
```

## Feature-gating a frontend

`.scx` files are ordinary project sources — `safeguard` compiles every one
it finds under `src/` regardless of [feature flags](/advanced/safeguard#feature-flags),
the same as any `.sc` file. A frontend/backend split comes from *calling*
scx-rendered pages conditionally, not from excluding the file:

```c
// src/main.sc
#ifdef SAFEC_FEATURE_FRONTEND
struct String render_home(const char* visitor_name); // src/pages.scx
#endif

int main() {
#ifdef SAFEC_FEATURE_BACKEND
    std::http_serve((unsigned short)8123, handle); // handle() calls render_home()
#else
    struct String page = render_home("static build");
    unsafe { printf("%s\n", page.as_ptr()); }
    page.free();
#endif
    return 0;
}
```

See `examples/fullstack_demo/` in the SafeC repository for the complete,
working project this is drawn from — a `Package.toml` with
`frontend`/`backend`/`fullstack` features, a `src/pages.scx` with three
rendered pages, and a `src/main.sc` that serves them over HTTP in
`fullstack`/`backend` mode or renders one to stdout in `frontend`-only
mode:

```bash
cd examples/fullstack_demo
safeguard run                                             # fullstack: HTTP server, HTML pages
safeguard run --features backend  --no-default-features   # HTTP server, JSON only
safeguard run --features frontend --no-default-features   # no server — renders + prints one page
```
