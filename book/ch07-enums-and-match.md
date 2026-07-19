# Chapter 7: Enums, Unions, and Match

Structs group related data together — a `Point` is always an `x` *and* a
`y`. This chapter is about the opposite shape: data that's *one of
several alternatives*. An enum for a fixed set of named values, a tagged
union for "one of these types, and here's which one," and `match` as the
one construct that handles both safely.

## Enums

An enum with an explicit underlying type looks just like C's, with the
size spelled out:

```c
enum Status : int {
    OK = 200,
    NotFound = 404,
    ServerError = 500
}
```

```c
enum Status s = NotFound;
printf("%d\n", (int)s);   // 404
```

Nothing surprising here if you've used C or C++ enums before — a small,
named set of integer constants, with the underlying storage type spelled
out explicitly rather than left to the compiler to pick.

## Unions: alternatives, not alongsides

A struct is "all of these fields, all the time." A `union` is "exactly
one of these fields, and the language remembers which":

```c
union Shape {
    double radius;
    double side;
}
```

This might look like it's declaring a C-style union — same fields, same
`|`-shaped mental model of "different interpretations of the same bytes."
It isn't, quite. **Every `union` in SafeC is a tagged union**: alongside
the fields, the compiler keeps a hidden marker recording which one is
actually live, and you can only get a value back out through `match` —
there's no way to accidentally read a field other than the one you set,
the way plain C's type-punning unions allow (and the way real-world C
bugs regularly exploit by accident).

**Constructing** a union value names the type and the field you're
filling, like a constructor call:

```c
union Shape circle = Shape.radius(2.0);
union Shape square = Shape.side(3.0);
```

**Reading** one back uses `match`, with a `.`-prefixed pattern per
variant — the leading dot is what marks this as a tagged-union pattern
rather than a plain identifier pattern (the kind you'd use matching
against an `enum`, or the `null`/`some(x)` patterns from [the previous
chapter](/book/ch05-understanding-regions)):

```c
double area(union Shape s) {
    return match (s) {
        case .radius(r): 3.14159 * r * r,
        case .side(sd):  sd * sd,
    };
}

area(circle);   // 12.56636
area(square);   // 9.0
```

Inside `case .radius(r):`, `r` is bound directly as the payload's type
(`double` here) — no manual unwrapping, no way to accidentally read `r`
as if it were the `side` variant instead. The compiler already knows,
from the tag, which field is live; `match` is just the syntax for asking
it.

## `match` as a statement vs. an expression

You've now seen both forms `match` can take. As a **statement**, each arm
runs a block, and there's no requirement that every possible case is
covered — an uncovered case just falls through having done nothing,
which is harmless enough that it's only a warning, not an error:

```c
match (day) {
    case Monday: printf("start of the week\n");
    case Friday: printf("almost done\n");
    // any other day: falls through silently -- a compiler warning, not an error
}
```

As an **expression** — used to produce a value, the way `area` above
returns whatever the matching arm evaluates to — every case genuinely has
to be covered, because there's no sensible value to produce on a path
that matches nothing. That's why `area`'s two variants (`radius`, `side`)
were enough on their own: a two-field union only has two possible tags,
so covering both *is* exhaustive. Add a `default:` arm any time you're
not sure every case is covered, or don't want to enumerate them all:

```c
int severity_of(enum Status s) {
    return match (s) {
        case OK:      0,
        case NotFound: 1,
        default:       2,   // catches ServerError, and anything else
    };
}
```

## Range and multi-value patterns

`match` also handles integer ranges and multiple values per arm, useful
for exactly the kind of status-code/bucket logic the `Status` enum above
hints at:

```c
match (status_code) {
    case 200:      printf("OK\n");
    case 400..499: printf("client error\n");
    case 500..599: printf("server error\n");
    default:       printf("other\n");
}

match (day) {
    case 1, 7:          printf("weekend\n");
    case 2, 3, 4, 5, 6:  printf("weekday\n");
}
```

Note the comma for alternation (`case 1, 7:`), not a pipe — and ranges
only work on integer patterns, not characters (`case 'a'..'z':` isn't
supported; match on the integer code point instead if you need
character-range logic).

Next: [Error Handling](/book/ch08-error-handling) — `?T` optionals are,
under the hood, exactly the two-variant tagged union this chapter just
taught you to build by hand (`none`/`some(x)`), which is why their
`match` syntax already looked familiar back in Chapter 5.
