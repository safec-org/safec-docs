# The SafeC Book

Welcome to *The SafeC Book* — a from-scratch, worked-example tutorial for
learning SafeC as a language, in the tradition of Kernighan & Ritchie's
*The C Programming Language* and *The Rust Programming Language*. Where
those books teach C's small, orthogonal core and Rust's ownership model
through running examples rather than dry enumeration, this book does the
same for SafeC: a C superset whose one big idea is the **region system** —
compile-time-tracked memory provenance (`&stack`, `&heap`, `&arena<R>`,
`&static`) that catches use-after-free and aliasing bugs before your
program ever runs, at zero runtime cost.

This is a tutorial, not a reference. It introduces features in the order
you need them to write real programs, with complete, compilable examples
at every step — every code block in this book has been compiled and,
where it produces output, run, against the real `safec` compiler. For
exhaustive detail on any single feature (every operator's precedence,
every stdlib function's signature), see the [Reference](/reference/types)
and [Standard Library](/stdlib/) sections instead — this book will link to
them at the point where you're ready for that depth.

## Who this book is for

You should already know how to program in *some* C-family language (C,
C++, Java, Go, Rust — the exact one doesn't matter much) and be
comfortable with pointers as a concept, even if a specific language kept
them out of your hands. This book does not re-teach "what is a variable"
or "what is a loop" from first principles the way a total-beginner
programming book would — Chapter 3 moves through those quickly, as a
refresher of SafeC's specific syntax rather than the underlying concepts.
If you know C reasonably well, most of Chapters 1–4 will be fast going;
the real new material starts at Chapter 5.

## How to read this book

Chapters are meant to be read in order — each one builds on syntax and
vocabulary from the ones before it, the same way *The Rust Programming
Language*'s chapters do. If you already know C well, Chapter 5 (regions)
is the one chapter you shouldn't skip: it's the one genuinely new idea in
the whole language, and everything from Chapter 6 onward assumes you have
it.

1. [Getting Started](/book/ch01-getting-started) — installing the toolchain, `safeguard new`, compiling and running your first program
2. [A First Program](/book/ch02-first-program) — a complete temperature-converter CLI tool, introducing I/O and the edit-compile-run loop
3. [Common Concepts](/book/ch03-common-concepts) — variables, types, operators, control flow
4. [Functions](/book/ch04-functions) — declarations, parameters, overloading, `pure`
5. [Understanding Regions](/book/ch05-understanding-regions) — the region system: `&stack`, `&heap`, `&arena<R>`, `&static`, and the borrow rules
6. [Structs and Methods](/book/ch06-structs-and-methods) — defining types, methods, operator overloading
7. [Enums, Unions, and Match](/book/ch07-enums-and-match) — tagged unions and exhaustive pattern matching
8. [Error Handling](/book/ch08-error-handling) — optionals, `try`, `defer`/`errdefer`, no exceptions
9. [A Final Project: a Key-Value Store](/book/ch09-final-project) — putting it all together in a larger program

Let's get started.
