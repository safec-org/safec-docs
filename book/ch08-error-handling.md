# Chapter 8: Error Handling

SafeC has no exceptions. There's no `throw`, no `catch`, no
stack-unwinding machinery running behind your back — a function that can
fail says so in its return type, and the caller is required to deal with
that possibility explicitly. This chapter covers the three pieces that
make that workable in practice: `?T` optionals for signaling absence,
`try` for propagating failure without a pyramid of `if` checks, and
`defer`/`errdefer` for cleanup that runs no matter how a function exits.

## `?T`: might not have a value

You've seen `?T` and `null` already, briefly, in earlier chapters — this
is where they get their proper introduction. `?T` means "a `T`, or
nothing":

```c
?int parse_positive(const char* s) {
    int v;
    unsafe { v = atoi(s); }
    if (v <= 0) {
        return null;
    }
    return v;
}
```

A plain `T` returned from a `?T`-returning function implicitly wraps to
"present" — `return v;` above needs no explicit "wrap this as present"
call. `null` is the one spelling of "absent," for both `?T` optionals and
the nullable references from [Chapter 5](/book/ch05-understanding-regions)
— there's no separate `some(x)`/`none` constructor you call in ordinary
code; those two names only exist as `match` patterns, the same
dot-free `null`/`some(x)` shape a nullable reference match uses.

Reading a `?T` back out requires going through one of a small set of
sanctioned operations — direct access isn't allowed, the same restriction
[Chapter 5](/book/ch05-understanding-regions) placed on nullable
references, and for the same reason: an "I forgot to check" bug here is
exactly the null-pointer-dereference class of bug SafeC is trying to
design out entirely.

```c
match (parse_positive("21")) {
    case none:    printf("nothing\n");
    case some(x): printf("got %d\n", x);
}
```

## `try`: propagate failure without the pyramid

Chaining several functions that can each fail tends to produce, in
languages without `try`, a staircase of nested "if it worked, do the next
thing" checks. `try` flattens that: it unwraps a `?T`, and if the value
was `null`, immediately returns `null` from the *enclosing* function
too — one line instead of a nested `if`:

```c
?int double_positive(const char* s) {
    int v = try parse_positive(s);   // if parse_positive returned null,
                                       // double_positive returns null here too
    return v * 2;
}
```

```c
double_positive("21");   // some(42)
double_positive("-5");   // none -- parse_positive's null propagated straight through
```

This is the same shape as Rust's `?` operator or Go's `if err != nil {
return err }` idiom, minus the boilerplate either of those needs at every
call site — `try` *is* the boilerplate, spelled as a single keyword.

## `defer`: cleanup that always runs

`defer` schedules a statement to run when the enclosing scope exits — by
falling off the end, by an early `return`, whatever the path — in
last-in-first-out order:

```c
void demo() {
    printf("start\n");
    defer printf("first deferred\n");
    defer printf("second deferred\n");
    printf("middle\n");
}
```

```
start
middle
second deferred
first deferred
```

This is the idiomatic way to pair an acquisition with its release right
next to each other in the source — `malloc`/`free`, `fopen`/`fclose`,
a mutex lock/unlock — instead of a release statement duplicated at every
early-return path, which is exactly the kind of thing that's easy to
forget at one of them and hard to notice you forgot.

## `errdefer`: cleanup only on the failure path

`errdefer` is `defer`'s narrower sibling — written the same way, but
intended to run only when the function is exiting because a `try`
propagated a failure, not on an ordinary successful return:

```c
?int process(int fail_at) {
    printf("processing\n");
    errdefer printf("rolling back\n");
    int r = risky_step(fail_at);
    if (r != 0) {
        return null;
    }
    return 42;
}
```

::: warning Currently runs on every explicit return, not just `try`-propagated failure
As implemented today, `errdefer` reliably fires on a `try`-propagated
failure (its intended case) — but it *also* fires on a plain explicit
`return`, including a successful one, which the description above would
suggest it shouldn't. In `process` above, even a completely successful
`return 42;` (no failure at all) currently triggers the `errdefer` and
prints "rolling back." Until this is fixed, don't rely on `errdefer`
being skipped on a successful explicit return — see [Control
Flow](/reference/control-flow#errdefer) for the up-to-date status of this
limitation.
:::

## Beyond `?T`: richer errors

`?T` tells you *that* something failed, not *why* — fine for `parse_positive`,
where "not a positive number" is the only failure mode worth naming, less
fine for something like a file-open call that might fail for several
distinct reasons a caller genuinely needs to tell apart. For that,
reach for `std::Result` (built on the same tagged-union mechanism [Chapter
7](/book/ch07-enums-and-match) taught you to build by hand) — see
[Standard Library](/stdlib/) once you need it; this chapter's `?T` is
still the right default for the common "did it work or not" case.

Next: [A Final Project](/book/ch09-final-project) — everything from the
last eight chapters, combined into one program worth actually keeping
around.
