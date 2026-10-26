# scx Templating

`.scx` is SafeC's answer to JSX/TSX/RSX: HTML-like markup embedded directly
in source, for writing server-rendered pages without hand-building strings
tag by tag. Like JSX-to-`React.createElement` or `rsx!`-to-DOM-ops, it's a
transpiler — a `.scx` file is ordinary SafeC source, plus markup as a
value, rewritten into plain SafeC calls *before* `safec` ever sees the
file. `safeguard build`/`check`/`test` do this automatically for every
`.scx` file they discover; `safec` itself has no knowledge that `.scx`
exists.

This is deliberately the smallest useful version of "JSX for SafeC": it
produces an HTML **string** (`struct String`), not a virtual DOM — good fit
for server-side rendering (build a page, hand it to `std::http_serve` as a
response body), not for client-side reactivity or diffed re-rendering.

## Syntax {#syntax}

Markup may appear in three statement positions: as the operand of `return`,
as a declaration's initializer, and as the right-hand side of an
assignment.

```c
struct String render_home(const &char visitor_name) {
    return <html>
        <head><title>My Page</title></head>
        <body>
            <h1>Hello, {visitor_name}!</h1>
            <p>Welcome.</p>
        </body>
    </html>;
}

void fill(&Page page, const &char title) {
    struct String heading = <h1 class="title">{title}</h1>;   // initializer
    if (page.draft) page.banner = <p class="draft">Draft</p>;  // assignment (any lvalue)
    page.body.push_str(&heading);
    heading.free();
}
```

- Tags are identifiers (`[a-zA-Z][a-zA-Z0-9-]*`, hyphens allowed for custom
  elements), opened and closed like HTML/XML: `<div>...</div>` or
  self-closing `<br/>`.
- Attributes: `name="literal text"`, `name={expr}`, or a bare `name`
  (written as `name=""`).
- `{expr}` interpolates a SafeC expression's value into the output,
  **HTML-escaped** (`&`, `<`, `>`, `"`, `'` become entities) — the safe
  default, same as JSX auto-escaping `{expr}` children.
- `{!expr}` interpolates **raw, unescaped** — for embedding pre-rendered
  trusted HTML (see [Composing pages](#composing-pages) below).
- The markup may be a *fragment*: several top-level elements, plus
  `<!doctype html>` declarations and `<!-- comments -->`, which are kept
  verbatim — so a whole page can be `return <!doctype html><html>...</html>;`.
- `<script>` and `<style>` hold raw text: everything up to the closing tag
  is copied as written, so JavaScript and CSS braces need no escaping (and
  there is no `{...}` interpolation inside them).
- Everything outside markup is unmodified, ordinary SafeC — imports,
  structs, plain functions. A `.scx` file's non-markup majority is exactly
  a `.sc` file; string/char literals and `//`/`/* */` comments are scanned
  over untouched, so a stray `<` or `return` inside one is never mistaken
  for markup. A comparison is never mistaken for markup either: markup
  starts only where an expression has to start (after `return` or `=`).
- A quoted `#include "header.h"` resolves next to the `.scx` file, as it
  would for a `.sc` file (the transpiled source is compiled from
  `build/scx/`, so scx rewrites the path to an absolute one).

The output follows HTML's rules for empty elements: a void element (`br`,
`hr`, `img`, `input`, `link`, `meta`, `source`, ...) is written as a start
tag only, every other empty element gets its closing tag (`<div></div>`,
`<script src="app.js"></script>` — `<div/>` would parse as an *open* tag in
HTML), and only inside `<svg>`/`<math>` are empty elements written
self-closed (`<path d="..."/>`).

Every `{expr}`/`{!expr}` expression must evaluate to a C string,
`const &char` — a string literal, a `const &char` parameter, or a
`String`'s `as_ptr()`. Format numbers into a buffer first (for example with
[`std::fmt_int`](/stdlib/utilities#fmt-formatting-into-buffers)). scx
does no type-checking of its own (it runs as a text-level pass, before
Sema ever runs), so a type mismatch surfaces as an ordinary `safec` compile
error on the generated code, pointing at the synthesized call site.

## Why statements, not any expression {#why-only-return-markup}

Real JSX/TSX integrate markup into the host language's full expression
grammar (passed as an argument, used in a ternary, embedded in a loop)
because their transpilers are built on a real JavaScript/TypeScript parser
that already understands expression position. scx's transpiler is a
lightweight, standalone text-level pass — it doesn't share `safec`'s
parser, and SafeC has no statement-expression construct (`({ ... })`) to
fall back on for building a value out of multiple statements
mid-expression.

Restricting markup to whole statements — `return <markup>;`,
`T name = <markup>;`, `lvalue = <markup>;` — sidesteps all of that: the
generated builder statements go exactly where the original statement was,
in the same function, with the same parameters and locals already in scope
— no hoisting, no capture analysis, no new grammar in `safec` itself. A
"component" in scx is simply *a function that returns markup* — write
`render_x(...)` returning `struct String`, and call it like any other
function.

## Composing pages {#composing-pages}

Because a component is just a function, composition means calling one
function from another — render the child first, then splice its already-
built HTML in with `{!...}`:

```c
struct String render_item(const &char name, int qty) {
    char qty_text[24];
    std::fmt_int(&qty_text[0], 24UL, (long long)qty);
    return <li>{name} &times; {qty_text}</li>;
}

struct String render_cart(const &String items_html) {
    return <ul>{!items_html.as_ptr()}</ul>;
}
```

The caller builds `items_html` with ordinary SafeC code (a loop over
`render_item(...)` calls, each appended with `push(item.as_ptr())`) and
passes it by reference, then
interpolates the finished, already-escaped-where-it-needed-to-be result raw
with `{!...}` — `{...}` would double-escape it. This mirrors how a
templating engine without a virtual DOM has to compose: build inner content
first, then splice, rather than a framework diffing a nested element tree
for you.

## Runtime {#runtime}

Generated code calls into a small runtime under `std/scx/scx.h`:

```c
namespace std {
void scx_append_esc(&String buf, const &char s); // HTML-escape + append
}
```

`safeguard` injects `#include <std/scx/scx.h>` and
`#include <std/collections/string.h>` automatically into any file that used
markup — hand-written `.scx` source never includes these itself. Literal
text between tags is author-written and emitted as-is, like JSX (so an
entity such as `&times;` passes straight through); static attribute
values are HTML-escaped once at transpile time. Each run of static content
becomes a single `String::push("...")` call, and only `{expr}`
interpolation sites cost a runtime call — generated code looks like:

```c
struct String __scx0 = std::String::new();
unsafe {
    __scx0.push("<h1>Hello, ");
    std::scx_append_esc(&__scx0, (visitor_name));
    __scx0.push("!</h1>");
}
return __scx0;
```

## Editor Support {#editor-support}

`sc-lsp` (see [Compiler Architecture](/advanced/compiler)'s tooling section
and `sc-language-server`'s README) understands `.scx` directly — open one
in an editor with the SafeC extension installed and you get the same
diagnostics, hover, go-to-definition, completion, and document outline a
`.sc` file gets. Under the hood the server transpiles the buffer with the
same transpiler `safeguard build` uses (one shared copy, so the two can't
drift apart), analyzes the generated SafeC, then maps every reported
position back onto the `.scx` buffer's own lines — you never see the
generated code. That mapping is line-level: it's exact everywhere outside
a markup expansion (which is most of a typical file), and for a
diagnostic *inside* one — e.g. a type error on a `{expr}` interpolation —
it points at the line the enclosing markup statement starts on rather
than the exact sub-line, since a markup block doesn't correspond 1:1 to
the code it expands to. Malformed markup itself (a mismatched closing tag,
an unterminated `{`) is reported the same way, at the exact line the
transpiler caught it.

## Feature-gating a frontend {#feature-gating-a-frontend}

`.scx` files are ordinary project sources — `safeguard` compiles every one
it finds under `src/` regardless of [feature flags](/advanced/safeguard#feature-flags),
the same as any `.sc` file. A frontend/backend split comes from *calling*
scx-rendered pages conditionally, not from excluding the file:

```c norun
// src/main.sc
#ifdef SAFEC_FEATURE_FRONTEND
struct String render_home(const &char visitor_name); // src/pages.scx
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
