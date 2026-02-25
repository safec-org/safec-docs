# Control Flow

SafeC supports all standard C control flow constructs and extends them with pattern matching, deferred execution, and compile-time branching.

## If / Else

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

## While Loop

```c
int i = 0;
while (i < 10) {
    printf("%d\n", i);
    i = i + 1;
}
```

## For Loop

C-style for loops with init, condition, and increment:

```c
for (int i = 0; i < 10; i = i + 1) {
    printf("%d\n", i);
}
```

## Do-While Loop

Executes the body at least once:

```c
int attempts = 0;
do {
    attempts = attempts + 1;
} while (!try_connect() && attempts < 3);
```

## Match Statement

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

### Features

**Range patterns** match inclusive ranges:

```c
match (ch) {
    case 'a'..'z': printf("lowercase\n");
    case 'A'..'Z': printf("uppercase\n");
    case '0'..'9': printf("digit\n");
    default: printf("other\n");
}
```

**Alternation patterns** match multiple values:

```c
match (day) {
    case 1 | 7: printf("weekend\n");
    case 2 | 3 | 4 | 5 | 6: printf("weekday\n");
}
```

**Wildcard** matches everything:

```c
match (value) {
    case 0: handle_zero();
    default: handle_other();
}
```

## Switch Statement

For C compatibility, SafeC also supports traditional `switch` with fall-through semantics:

```c
switch (op) {
    case '+':
        result = a + b;
        break;
    case '-':
        result = a - b;
        break;
    default:
        printf("unknown op\n");
        break;
}
```

## Defer

`defer` schedules a statement to execute when the enclosing scope exits, in LIFO (last-in, first-out) order. This is ideal for resource cleanup.

```c
void process_file(const char *path) {
    FILE *f = fopen(path, "r");
    defer fclose(f);

    char *buf = (char*)malloc(4096);
    defer free(buf);

    // ... use f and buf ...
}   // free(buf) runs first, then fclose(f)
```

Deferred statements execute regardless of how the scope is exited -- whether by normal flow, `return`, or early exit.

### Multiple Defers

Defers execute in reverse order of declaration:

```c
void example() {
    defer printf("3\n");
    defer printf("2\n");
    defer printf("1\n");
}
// Output: 1, 2, 3
```

## Errdefer

`errdefer` is like `defer` but only executes if the function exits with an error (returns `none` from an optional, or an error from a result type):

```c
?int open_and_process(const char *path) {
    FILE *f = fopen(path, "r");
    if (f == null) return none;
    errdefer fclose(f);        // only runs if we return none below

    char *buf = (char*)malloc(4096);
    if (buf == null) return none;  // errdefer triggers: fclose(f) runs
    defer free(buf);

    // ... process ...
    return some(result);       // success: errdefer does NOT run
}
```

## Try Operator

The `try` operator unwraps an optional value, propagating `none` to the caller if the value is absent:

```c
?int parse_config(const char *path) {
    ?int fd = open_file(path);
    int file = try fd;         // if fd is none, return none immediately

    ?int value = read_int(file);
    return try value;
}
```

This is equivalent to:

```c
if (fd == none) return none;
int file = unwrap(fd);
```

## Labeled Break and Continue

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

## Goto and Labels

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

::: warning
Prefer structured control flow (`match`, loops, `break`/`continue`) over `goto`. The compiler does not verify that `goto` respects region lifetimes.
:::

## If Const (Compile-Time Branching)

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
