# Control Flow

SafeC supports all standard C control flow constructs and extends them with pattern matching, deferred execution, and compile-time branching.

## If / Else {#if-else}

Standard conditional branching:

```c
if (x > 0) {
    printf("positive\n");
} else if (x == 0) {
    printf("zero\n");
} else {
    printf("negative\n");
}
```

## While Loop {#while-loop}

```c
int i = 0;
while (i < 10) {
    printf("%d\n", i);
    i = i + 1;
}
```

## For Loop {#for-loop}

C-style for loops with init, condition, and increment:

```c
for (int i = 0; i < 10; i = i + 1) {
    printf("%d\n", i);
}
```

## Do-While Loop {#do-while-loop}

Executes the body at least once:

```c
int attempts = 0;
do {
    attempts = attempts + 1;
} while (!try_connect() && attempts < 3);
```

## Match Statement {#match-statement}

The `match` statement provides pattern matching without fall-through (unlike C `switch`). Each case is independent -- no `break` required.

```c
match (status_code) {
    case 200: printf("OK\n");
    case 404: printf("Not Found\n");
    case 400..499: printf("Client Error\n");
    case 500..599: printf("Server Error\n");
    default: printf("Unknown\n");
}
```

### Features {#features}

**Range patterns** match inclusive ranges of integers or chars, with
either `..` or `...`:

```c
match (status_code) {
    case 400..499: printf("client error\n");
    case 500..599: printf("server error\n");
    default: printf("other\n");
}

match (c) {
    case 'a'...'z': printf("lowercase\n");
    case 'A'..'Z':  printf("uppercase\n");
    case '0'...'9': printf("digit\n");
    default:        printf("other\n");
}
```

**Alternation patterns** match multiple values — use **comma**, not `|`:

```c
match (day) {
    case 1, 7: printf("weekend\n");
    case 2, 3, 4, 5, 6: printf("weekday\n");
}
```

::: warning Alternation uses `,`, not `|`
`case 1 | 7:` does not parse — `|` is not valid inside a pattern list. Use a
comma to separate alternatives, as shown above.
:::

**Wildcard** matches everything:

```c
match (value) {
    case 0: handle_zero();
    default: handle_other();
}
```

A match statement without a wildcard/`default` arm only warns if non-exhaustive (`match statement may not be exhaustive`) — it doesn't error, since falling through with no value produced is harmless in statement position.

### Matching Nullable References and Optionals {#matching-nullable-references-and-optionals}

`match` also destructures pointers (`T*`), nullable references (`?&region T`), and optionals (`?T`) — this is the primary sanctioned way to read one of these, alongside `is_null()`/`is_none()`/`.default(fallback)` (see [Types](/reference/types#reading-a-nullable-value)); direct dereference/member-access/force-unwrap (`!`) require `unsafe`.

```c
struct Node { int value; };

void describe(?&stack Node next) {
    match (next) {
        case null:    printf("empty\n");
        case some(n): printf("value=%d\n", n.value);  // n bound as the payload type directly
    }
}

?int maybe = compute();
match (maybe) {
    case none:    printf("nothing\n");
    case some(x): printf("got %d\n", x);
}
```

Pattern names are `null`/`some(x)` for pointers and nullable references, `none`/`some(x)` for optionals — plain identifiers, not dot-prefixed (unlike tagged-union variant patterns).

### Match as an Expression {#match-as-an-expression}

`match` can also be used as an expression, producing a value from whichever arm ran. Unlike the statement form, a match **expression** must be provably exhaustive — every tagged-union variant covered (or a `default`/wildcard arm), or, for nullable/optional subjects, both `null`/`none` and `some(x)` covered — otherwise it's a compile error, since it has to produce a value on every path:

```c
int result = match (status_code) {
    case 200:      1,
    case 400..499: -1,
    default:       0,
};

int describe_len(?&stack Node next) {
    return match (next) {
        case null:    -1,
        case some(n): n.value,
    };
}
```

## Switch Statement {#switch-statement}

Real C fall-through dispatch, distinct from `match` above (which never
falls through, and whose `break` passes through to an *enclosing loop*,
not the match itself):

```c
switch (n) {
case 1:
case 2:
    do_low();
    break;
case 3:
    do_three();
    // falls through — no break
case 4:
    do_three_or_four();
    break;
default:
    do_other();
}
```

Case values are literal (optionally negated) integer or char constants —
not general constant expressions, matching C's "case labels must be
integer constant expressions" rule closely enough to compile to a real
`switch`/jump-table instruction rather than a chain of comparisons.
Unlabeled `break` exits the switch; `continue` is not consumed by a
switch — it keeps referring to whatever loop (if any) already encloses
it, same as real C.

## Defer {#defer}

`defer` schedules a statement to execute when the enclosing scope exits, in LIFO (last-in, first-out) order. This is ideal for resource cleanup.

```c
void process_file(const &char path) {
    FILE *f = fopen(path, "r");
    defer fclose(f);

    ?&char buf = malloc(4096);             // C's void* -> ?&char
    defer free(buf);

    // ... use f and buf ...
}   // free(buf) runs first, then fclose(f)
```

Deferred statements execute regardless of how the scope is exited -- whether by normal flow, `return`, or early exit.

### Multiple Defers {#multiple-defers}

Defers execute in reverse order of declaration:

```c
void example() {
    defer printf("3\n");
    defer printf("2\n");
    defer printf("1\n");
}
// Output: 1, 2, 3
```

## Try Operator {#try-operator}

The `try` operator unwraps an optional value, propagating the empty case (`null`) to the caller immediately if the value is absent — equivalent to `if (x.is_none()) return null; T value = ...;` written inline:

```c
?int parse_config(const &char path) {
    ?int fd = open_file(path);
    int file = try fd;         // if fd is empty, return null immediately

    ?int value = read_int(file);
    return try value;
}
```

A bare `T` returned from a `?T`-returning function implicitly wraps to "present" (`return file;` above needs no explicit wrap), and `return null;` produces the empty case — there's no separate `some(x)` constructor to call in ordinary code (`some`/`none` only appear as `match` patterns, see above).

## Errdefer {#errdefer}

`errdefer` is written like `defer`, but is meant to run only on a `try`-propagated failure exit rather than every exit:

```c
?int open_and_process(const &char path) {
    ?int fd = open_file(path);
    int f = try fd;             // propagates null on failure
    errdefer close_fd(f);       // intended to run only if a later 'try' below fails

    ?int size = read_size(f);
    int n = try size;           // on failure: close_fd(f) runs, then null propagates

    // ... process ...
    return n;
}
```

A normal (non-`try`-triggered) exit — a plain explicit `return`, including
a successful one, or falling off the end of the function — runs every
registered `defer`, but skips `errdefer`, as the names suggest. A
`try`-propagated failure exit runs both: general cleanup (`defer`) still
owes its work, on top of whatever error-specific cleanup (`errdefer`)
was registered.

## Labeled Break and Continue {#labeled-break-and-continue}

Labels allow breaking out of or continuing nested loops:

```c
outer: for (int i = 0; i < 10; i = i + 1) {
    for (int j = 0; j < 10; j = j + 1) {
        if (i + j > 15) break outer;
        if (j % 2 == 0) continue;
        printf("%d %d\n", i, j);
    }
}
```

## Goto and Labels {#goto-and-labels}

For C compatibility and low-level control flow:

```c
void state_machine(int input) {
    goto start;

start:
    if (input == 0) goto done;
    input = input - 1;
    goto start;

done:
    printf("finished\n");
}
```

The compiler checks three rules:

- **A label is defined once per function.** A second `L:` in the same function is an error (`duplicate label 'L'`).
- **No jump past a declaration.** A `goto` may not jump into the scope of a local variable past its declaration: at the label the variable would be in scope but never initialized (C++'s "jump bypasses variable initialization", applied to every non-static local). Jumping *into* a nested block, such as a loop body, is allowed when no declaration in scope at the label is skipped; jumping backwards, or out of a block, is always fine.
- **Labels inside `match` arms are not goto targets** (`use of undeclared label`).

```c
int f(int x) {
    if (x == 1) goto resume;      // error: jumps over the declaration of 'y'
    int y = x * 2;
resume:
    return y;
}

int g(int x) {
    if (x > 0) goto inside;       // fine: nothing in scope at 'inside' is skipped
    while (x < 10) {
        x = x + 1;
    inside:
        x = x * 2;
    }
    return x;
}
```

A label may also follow a `return` in the same block — it is still reachable through a `goto`. [`std/async`](/stdlib/async#coroutines)'s stackless coroutines are built from exactly this: a `switch` on a saved resume point `goto`s to a label after the `return` of the previous suspension, and the jump rule is what rejects a local that would have to survive across a suspension.

::: warning
Prefer structured control flow (`match`, loops, `break`/`continue`) over `goto`. The compiler does not verify that `goto` respects region lifetimes.
:::

## If Const (Compile-Time Branching) {#if-const-compile-time-branching}

`if const` evaluates a condition at compile time and selects a branch. The dead branch is eliminated entirely and is not type-checked.

```c
const int PLATFORM = 1;

void init() {
    if const (PLATFORM == 1) {
        init_linux();
    } else if const (PLATFORM == 2) {
        init_macos();
    } else {
        init_generic();
    }
}
```

This is the SafeC replacement for `#ifdef`-based conditional compilation. Unlike preprocessor conditionals, `if const` respects scoping and can reference `const` and `consteval` values.
