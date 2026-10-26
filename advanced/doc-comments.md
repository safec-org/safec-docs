# Doc Comments

[`safeguard doc`](/advanced/safeguard#safeguard-doc) builds a library's API reference from its declarations and the comments on them. This page is the convention it reads: which comments document what, how they are written, and which declarations appear. The [std API reference](/api/) is built the same way from std's sources.

## Where a doc comment goes {#placement}

A doc comment is a run of comment lines **directly above** a declaration — no blank line between — or a comment **on the declaration's own line**, after it:

```c
/// A point with integer coordinates.
///
/// Created with [Point::new]; compare with [Point::eq].
struct Point {
    int x;   ///< horizontal coordinate
    int y;   ///< vertical coordinate

    /// A point at (`x`, `y`).
    static struct Point new(int x, int y);

    /// Squared distance from the origin.
    long long norm2() const;
};

/** Kinds of shape [shape_area] knows. */
enum ShapeKind {
    SHAPE_SQUARE = 1,   ///< four equal sides
    SHAPE_CIRCLE,       ///< `s` is the radius
};
```

| Form | Documents |
|------|-----------|
| `/// text` (lines) | the declaration below |
| `/** text */` | the declaration below |
| `int x; ///< text` or `int x; // text` | the declaration on the same line — what fields, enumerators and short methods use; comment-only lines below it, aligned with it, continue it |
| `//! text` | the file itself (its module page) |
| `// text` (lines) | the declaration below — **std's convention** (see below) |

A block with any `///` or `/**` line uses only those lines, so ordinary `//` notes can sit beside a doc comment without being published. Both a leading comment and a trailing one on the same declaration are shown, the leading one first.

Everything that can be declared is documented: namespaces' contents (shown with their path, `geo::Point`), structs and unions with their fields and methods, enums with their enumerators, traits with their required methods, functions (generic ones with their `generic<T: Bound>` parameters), macros, constants and globals, type aliases and newtypes.

**Declared in a header, defined elsewhere.** A method declared in its struct and defined out of line (`long long Point::norm2() const { … }`), or a function declared in a header and defined in a `.sc` file, is one item: the header's comment wins, the definition's is used when the header has none, and the page links to both locations.

## Module docs {#module-docs}

Each header is a module, nested by directory: `include/geo/shapes.h` is `geo::shapes`. Its doc is its `//!` lines:

```c
#pragma once
//! Points and vectors in the plane.
//!
//! ```c
//! struct Point p = Point::new(3, 4);
//! ```
```

Without `//!` lines, the file's first comment block is the module's doc when something other than a declaration follows it (a blank line, `#include`, `#pragma`) — the layout every std header uses. A license header (`Copyright …`, `SPDX-License-Identifier`) is skipped. The package's `README.md` (or `[package] readme`) is the front page.

## Markdown {#markdown}

Doc comments are Markdown — CommonMark with GitHub's tables, strikethrough and autolinks. Raw HTML is shown as text, not inserted. Headings in a doc comment sit below the page's own, so `# Examples` is a sub-heading of the item.

### Code examples {#code-examples}

Fenced code blocks are highlighted; a fence without a language is SafeC:

````c
/// Area of a shape of `kind` and size `s`, rounded down.
///
/// # Examples
///
/// ```
/// long long a = geo::shape_area(SHAPE_SQUARE, 3);   // 9
/// ```
long long shape_area(enum ShapeKind kind, int s);
````

Examples are shown, not compiled: put the ones that must keep working in `tests/`.

### Intra-doc links {#intra-doc-links}

A name in square brackets links to that item's page:

| Write | Links to |
|-------|----------|
| `[Point]`, `` [`Point`] `` | the struct `Point` |
| `[Point::norm2]`, `[Point::norm2()]` | the method on `Point`'s page (`#method.norm2`) |
| `[geo::Point]` | the same, by its namespace path |
| `[shape_area]` | the function |
| `[SHAPE_CIRCLE]`, `[ShapeKind::SHAPE_CIRCLE]` | the enumerator |
| `[geo::shapes]` | a module |
| `[String]`, `[std::Vec::push]` | items in std — links into the [std API reference](/api/) |
| `[the norm](Point::norm2)` | any link text, with an item as the target |

A name that doesn't resolve stays plain text. In signatures, every type and function name that resolves is linked the same way.

## std's plain comments {#std-convention}

std documents its API with ordinary `//` comments directly above or beside declarations, written as prose rather than Markdown. The reference reads them too, with one adjustment: a run of lines indented by two or more spaces, after a blank comment line or a line ending in `:`, is a code example:

```c
// Copies 'len' elements starting at 'items'.
generic<T>
struct Vec<T> Vec::from_arr(const &T items, unsigned long len);

// SafeC Standard Library — Vec<T> (dynamic array)
//
//   struct Vec<int> v = std::Vec::new();
//   v.push(3);
```

A separator line — `// ── Section ──`, `// -----`, `// =====` — ends a comment block: it and the lines above it are not part of the declaration's doc. New code should prefer `///`: it is unambiguous Markdown, and a `///` block leaves nearby `//` notes out of the docs.

## What is documented {#visibility}

- In a package with headers, the reference is its **headers** (`include/` and `src/`): what a dependent can `#include`. Declarations only in `.sc` files are implementation. A package without headers documents its `.sc` files.
- `static` functions and globals are private to their file and left out.
- Names that start or end with `_` (std's type-erased `_raw_` helpers) are hidden, and so is any declaration whose doc comment has a line that is just `@hidden` (or `#[doc(hidden)]`).
- An `extern` declaration of a C function is shown only when it has a doc comment.

`safeguard doc --private` shows the hidden items too.
