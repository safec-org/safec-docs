# Chapter 2: A First Program

*The C Programming Language* opens its first real chapter — past "hello,
world" — with a Celsius-to-Fahrenheit conversion table. It's a good choice
for a first program: small enough to fit on one screen, but big enough to
touch variables, a loop, formatted output, and a little arithmetic all at
once. We'll write the same program in SafeC, then extend it into a small
but genuinely usable command-line tool.

## The conversion

Fahrenheit and Celsius are related by `F = C * 9/5 + 32`. A single
conversion:

```c
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        unsafe { printf("usage: %s <celsius>\n", argv[0]); }
        return 1;
    }
    double c;
    unsafe { c = atof(argv[1]); }
    double f = c * 9.0 / 5.0 + 32.0;
    printf("%.1f C = %.1f F\n", c, f);
    return 0;
}
```

```bash
$ safeguard run -- 100
100.0 C = 212.0 F
```

Two things worth calling out, both things you'll see over and over in
SafeC code:

**`unsafe { printf("...", argv[0]); }`.** `argv` is a raw `char**` — a
plain C-style pointer, with none of SafeC's compile-time tracking attached
to it (command-line arguments come from the OS, outside anything the
compiler could verify). *Indexing* a raw pointer (`argv[0]`, `argv[1]`)
requires `unsafe`, the same as dereferencing one does — Chapter 5 explains
why in full; for now, the rule of thumb is: raw pointer, `unsafe` to touch
it.

**`atof(argv[1])` assigned through a separate `unsafe { c = ...; }`
statement**, rather than `double c = atof(argv[1]);` directly. This is
purely about *where* the unsafe operation (indexing `argv`) happens — the
call to `atof` itself isn't unsafe, only reading `argv[1]` to get its
argument is, so the assignment needs to be inside the block but the
declaration doesn't have to be.

## From one value to a table

K&R's version prints a whole table instead of converting one number. A
`for` loop gets us there:

```c
#include <stdio.h>

int main() {
    printf("Celsius  Fahrenheit\n");
    for (int c = 0; c <= 100; c = c + 10) {
        double f = (double)c * 9.0 / 5.0 + 32.0;
        printf("%7d  %10.1f\n", c, f);
    }
    return 0;
}
```

```
Celsius  Fahrenheit
      0        32.0
     10        50.0
     20        68.0
     30        86.0
     40       104.0
     50       122.0
     60       140.0
     70       158.0
     80       176.0
     90       194.0
    100       212.0
```

`c` is declared `int` (whole-degree steps make for a cleaner table), so
`(double)c` casts it to `double` before the floating-point arithmetic —
SafeC doesn't implicitly widen `int` to `double` for you the way plain C's
usual arithmetic conversions might lead you to expect from muscle memory;
every conversion between differently-sized/signed numeric types is
written out. You'll run into this constantly, and [Common
Concepts](/book/ch03-common-concepts) covers exactly which conversions
are and aren't implicit.

## A small tool: converting several values at once

Real command-line tools usually take more than one argument. Looping over
`argv` gets us a converter that handles as many values as you give it:

```c
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        unsafe { printf("usage: %s <celsius>...\n", argv[0]); }
        return 1;
    }
    for (int i = 1; i < argc; i = i + 1) {
        double c;
        unsafe { c = atof(argv[i]); }
        double f = c * 9.0 / 5.0 + 32.0;
        printf("%.1f C = %.1f F\n", c, f);
    }
    return 0;
}
```

```bash
$ safeguard run -- 0 100 37
0.0 C = 32.0 F
100.0 C = 212.0 F
37.0 C = 98.6 F
```

That `--` matters: it tells `safeguard run` "everything after this is an
argument to the program being run, not to `safeguard` itself" — without
it, `safeguard` would try to interpret `0`, `100`, `37` as its own flags.

## What we haven't covered yet

This program works, but it doesn't validate its input (a non-numeric
argument silently converts to `0.0`, since that's what `atof` does on
unparseable input — not a SafeC-specific behavior, that's plain C's
`atof`), and it uses a raw C-style array (`argv`) rather than any of
SafeC's own safer collection types. Both are deliberate: this chapter's
job was the edit-compile-run loop and a first taste of `unsafe`, not
robust error handling or SafeC's standard library. [Error
Handling](/book/ch08-error-handling) and the [Standard
Library](/stdlib/) reference come later, once you have more of the
language to build on. Next, [Common Concepts](/book/ch03-common-concepts)
fills in the rest of SafeC's basic vocabulary — types, operators, and
control flow — more systematically than these first two chapters have.
