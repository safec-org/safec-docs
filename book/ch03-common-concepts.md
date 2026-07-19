# Chapter 3: Common Concepts

This chapter is a fast tour of SafeC's basic vocabulary: variables, types,
operators, and control flow. If you already know C, most of this will
read as "yes, obviously" — the goal is to flag the handful of places where
SafeC's rules are *stricter* than C's, since those are exactly the spots
where C muscle memory produces a compile error instead of the program you
meant to write.

## Variables

```c
int age = 30;
auto name_len = 5;      // type inferred from the initializer -- an int
const double pi = 3.14159;
```

`auto` infers a variable's type from its initializer, nothing more exotic
— it works in ordinary declarations and `for`-loop init clauses, with no
flow analysis beyond that single initializer expression. `const` makes a
binding immutable, exactly like C's `const`, checked at compile time.

One C habit that doesn't carry over: **SafeC has no C-style
multi-variable declarations**. `int a = 1, b = 2;` doesn't parse — split
it into two statements. The same applies inside a `for` loop's init
clause: `for (int i = 0, j = 10; ...)` doesn't work either; declare `j`
before the loop instead.

## Types and conversions

SafeC's primitive types are the same set C gives you — `int`, `long`,
`long long`, `short`, `char`, their `unsigned` variants, `float`,
`double`, `bool` — with the same sizes on the platforms SafeC targets.
Where SafeC differs sharply from C is **implicit conversions**: C will
freely convert between an `int` and an `unsigned long` in a mixed
expression (the "usual arithmetic conversions"), silently reinterpreting
the value's meaning if the signs disagree. SafeC doesn't:

```c
unsigned long size = 10UL * sizeof(int);   // OK -- both operands unsigned long
// unsigned long bad = 10 * sizeof(int);   // ERROR: int and unsigned long differ
```

`sizeof` returns `unsigned long`; a bare `10` is `int`. Multiplying them
needs the same type on both sides, so the literal gets an explicit `UL`
suffix. The same rule applies to explicit casts between differently-sized
or differently-signed types generally — if a conversion isn't a pure
widening within the same signedness (`int` → `long`, `float` → `double`),
write it out:

```c
int x = 42;
double dx = (double)x;    // explicit cast required
```

This shows up constantly once you're calling into libc functions whose
signatures use `size_t`/`unsigned long` — get used to reaching for a `UL`
suffix or an explicit cast the moment the compiler complains about two
types "differing."

### Literal syntax gaps

A few literal forms C has that SafeC doesn't: no binary literals
(`0b1010`), and a leading `0` does **not** mean octal — `0777` is decimal
777, not octal 511, so if you actually need binary or octal values,
compute them from hex or write out the decimal value. Hex works exactly
as in C (`0xFF`), and it's the natural substitute for binary literals too
— `0xC` for what you might have reached for as `0b1100`:

```c
unsigned int flags = 0xCU;   // 0b1100
unsigned int mask  = 0xAU;   // 0b1010
printf("%u %u %u\n", flags & mask, flags | mask, flags ^ mask);
// 8 14 6
```

## Operators

Arithmetic, comparison, and logical operators are all exactly what you'd
expect from C:

```c
int a = 7;
int b = 3;
printf("%d %d %d %d\n", a + b, a - b, a * b, a / b);  // 10 4 21 2
printf("%d\n", (a > b) && (b > 0));                    // 1
```

Bitwise operators (`&`, `|`, `^`, `~`, `<<`, `>>`) are also unchanged from
C. What *is* new is a set of operators for explicit overflow behavior,
since plain `+`/`-`/`*` on signed integers still carries C's
undefined-behavior-on-overflow rule (unsigned still wraps, as in C):

```c
int max = 2147483647;         // INT_MAX
int wrapped   = max +| 1;     // wrapping add: -2147483648 (two's complement wrap)
int saturated = max +% 1;     // saturating add: 2147483647 (clamped, doesn't wrap)
```

`+|`/`-|`/`*|` always wrap (defined two's-complement behavior, useful for
hashing, checksums, ring-buffer indices); `+%`/`-%`/`*%` always saturate
(clamp to the type's min/max, useful for audio/signal processing and
anywhere "closest representable value" beats "wrap around"). See
[Overflow Operators](/reference/overflow) for the complete operator table
and `std::checked_mul_size`/`<stdckdint.h>` for detecting overflow instead
of resolving it a particular way.

## Control flow

`if`/`else`, `while`, and C-style `for` all work exactly as in C:

```c
int i = 0;
while (i < 3) {
    printf("while: %d\n", i);
    i = i + 1;
}

for (int j = 0; j < 3; j = j + 1) {
    printf("for: %d\n", j);
}

int k = 5;
if (k > 10) {
    printf("big\n");
} else if (k > 0) {
    printf("small positive\n");
} else {
    printf("non-positive\n");
}
```

SafeC also has `match` — a pattern-matching statement/expression that
looks like a much safer version of C's `switch` (in fact, `switch`/`case`
as *C* writes them don't work in SafeC at all — despite being reserved
words, there's no implementation behind them; `match` is the only
multi-way branch construct). It deserves its own space rather than a
quick mention here: [Chapter 7](/book/ch07-enums-and-match) covers it
alongside enums and tagged unions, the two things it's most useful for
matching against.

Next: [Chapter 4](/book/ch04-functions) covers functions — declarations,
parameters, and a few SafeC-specific attributes (`pure`, `inline`,
`must_use`) that don't have a direct C equivalent.
