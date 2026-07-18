# Operators

SafeC supports the full set of C operators plus SafeC-specific extensions for overflow control. This page is a complete reference for all operators, their precedence, and associativity.

## Arithmetic Operators

### Binary Arithmetic

| Operator | Description | Example |
|----------|-------------|---------|
| `+` | Addition | `a + b` |
| `-` | Subtraction | `a - b` |
| `*` | Multiplication | `a * b` |
| `/` | Division | `a / b` |
| `%` | Modulo (remainder) | `a % b` |

### Unary Arithmetic

| Operator | Description | Example |
|----------|-------------|---------|
| `+x` | Unary plus (identity) | `+a` |
| `-x` | Unary negation | `-a` |

### Increment and Decrement

| Operator | Description | Example |
|----------|-------------|---------|
| `++x` | Pre-increment (increment, then return) | `++counter` |
| `x++` | Post-increment (return, then increment) | `counter++` |
| `--x` | Pre-decrement (decrement, then return) | `--index` |
| `x--` | Post-decrement (return, then decrement) | `index--` |

```c
int x = 5;
int a = ++x;   // x is 6, a is 6
int b = x++;   // b is 6, x is 7
int c = --x;   // x is 6, c is 6
int d = x--;   // d is 6, x is 5
```

### Overflow-Aware Arithmetic

SafeC adds operator variants for explicit overflow control. See [Overflow Operators](/reference/overflow) for detailed usage.

| Operator | Description | Behavior |
|----------|-------------|----------|
| `+\|` | Wrapping addition | Two's complement wrap |
| `-\|` | Wrapping subtraction | Two's complement wrap |
| `*\|` | Wrapping multiplication | Two's complement wrap |
| `+%` | Saturating addition | Clamp to min/max |
| `-%` | Saturating subtraction | Clamp to min/max |
| `*%` | Saturating multiplication | Clamp to min/max |

## Assignment Operators

### Simple Assignment

```c
int x = 42;
x = 100;
```

### Compound Assignment

Compound assignment operators combine an arithmetic or bitwise operation with assignment. `a op= b` is equivalent to `a = a op b`.

| Operator | Description | Equivalent |
|----------|-------------|------------|
| `+=` | Add and assign | `a = a + b` |
| `-=` | Subtract and assign | `a = a - b` |
| `*=` | Multiply and assign | `a = a * b` |
| `/=` | Divide and assign | `a = a / b` |
| `%=` | Modulo and assign | `a = a % b` |
| `&=` | Bitwise AND and assign | `a = a & b` |
| `\|=` | Bitwise OR and assign | `a = a \| b` |
| `^=` | Bitwise XOR and assign | `a = a ^ b` |
| `<<=` | Left shift and assign | `a = a << b` |
| `>>=` | Right shift and assign | `a = a >> b` |

```c
int x = 10;
x += 5;     // x is 15
x -= 3;     // x is 12
x *= 2;     // x is 24
x /= 4;     // x is 6
x %= 5;     // x is 1

uint32_t flags = 0xFF00;
flags &= 0x0F0F;    // flags is 0x0F00
flags |= 0x00F0;    // flags is 0x0FF0
flags ^= 0x0FF0;    // flags is 0x0000
```

## Comparison Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `==` | Equal | `a == b` |
| `!=` | Not equal | `a != b` |
| `<` | Less than | `a < b` |
| `>` | Greater than | `a > b` |
| `<=` | Less than or equal | `a <= b` |
| `>=` | Greater than or equal | `a >= b` |

All comparison operators return `bool`.

## Logical Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `&&` | Logical AND (short-circuit) | `a && b` |
| `\|\|` | Logical OR (short-circuit) | `a \|\| b` |
| `!` | Logical NOT | `!a` |

Short-circuit evaluation: `&&` does not evaluate the right operand if the left is `false`; `||` does not evaluate the right operand if the left is `true`.

```c
// Short-circuiting affects *evaluation order*, not the unsafe-dereference
// rule: *ptr still needs 'unsafe' even though && guarantees it only runs
// when ptr is non-null — the compiler doesn't do flow-sensitive narrowing.
unsafe {
    if (ptr != (int*)0 && *ptr > 0) {
        // *ptr is only evaluated when ptr is non-null, thanks to &&
    }
}
```

## Bitwise Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `&` | Bitwise AND | `a & b` |
| `\|` | Bitwise OR | `a \| b` |
| `^` | Bitwise XOR | `a ^ b` |
| `~` | Bitwise NOT (complement) | `~a` |
| `<<` | Left shift | `a << n` |
| `>>` | Right shift | `a >> n` |

```c
// No binary literal syntax (see Literals & Qualifiers) — write the hex
// equivalent instead. flags = 0xC = 0b1100, mask = 0xA = 0b1010.
uint32_t flags = 0xC;
uint32_t mask  = 0xA;

uint32_t and_result = flags & mask;   // 0x8  (0b1000)
uint32_t or_result  = flags | mask;   // 0xE  (0b1110)
uint32_t xor_result = flags ^ mask;   // 0x6  (0b0110)
uint32_t not_result = ~flags;         // all bits flipped

uint32_t shifted = 1 << 4;           // 16 (bit 4 set)
```

## Member Access Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `.` | Value member access | `point.x` |
| `->` | Pointer member access | `ptr->x` |
| `::` | Scope resolution / method definition | `Point::length()` |

The `.` operator accesses fields on values and references. The `->` operator dereferences a pointer and accesses a field in one step.

```c
struct Point { double x; double y; };

struct Point p = {3.0, 4.0};
double x1 = p.x;            // value access

// Getting a raw pointer and dereferencing it through -> both require
// 'unsafe' — there's no implicit '&stack Point' -> 'Point*' conversion.
struct Point *ptr;
unsafe { ptr = (struct Point*)&p; }
double x2;
unsafe { x2 = ptr->x; }      // pointer dereference + access
// equivalent to: (*ptr).x
```

The `::` operator is used for method definitions and static scope resolution:

```c
double Point::length() const {
    return sqrt_d(self.x * self.x + self.y * self.y);
}
```

## Subscript Operator

Array and slice indexing uses `[]`. Accesses are bounds-checked in safe contexts.

```c
int arr[5] = {10, 20, 30, 40, 50};
int x = arr[2];              // 30 (bounds-checked)

[]int s = arr[1..4];
int y = s[0];                // 20 (bounds-checked via slice length)
```

## Ternary Operator

The conditional (ternary) operator evaluates one of two expressions based on a condition:

```c
condition ? then_expr : else_expr
```

```c
int x = 10;
int y = 20;
int max = (x > y) ? x : y;       // max is 20

const char *label = (count == 1) ? "item" : "items";
```

The ternary operator has very low precedence — use parentheses around complex conditions for clarity.

## Comma Operator

In a `for` loop's increment clause, comma-separated expressions are evaluated left to right purely for side effects — this is the common, working use:

```c
int i = 0;
int j = 10;
for (; i < j; i++, j--) {
    // i counts up, j counts down
}
```

::: warning Comma as a value-producing expression doesn't work like C
Unlike C, `(a = 1, b = 2, a + b)` does **not** evaluate to the type/value of
its last sub-expression — SafeC's comma operator produces a tuple type
(`(int, int, int)` for that example), which then fails to convert to `int`
in a variable declaration or assignment. So `int x = (a = 1, b = 2, a + b);`
does not compile. Write the statements out separately instead:

```c
int a = 1;
int b = 2;
int x = a + b;   // x is 3
```
:::

::: warning No C-style multi-variable declarations
`for (int i = 0, j = 10; i < j; i++, j--)` doesn't parse — SafeC has no
C-style comma-separated multi-variable declaration (`int i = 0, j = 10;`
fails the same way as a standalone statement). Declare each variable on its
own line before the loop, as in the working example above.
:::

## Address-of and Dereference

| Operator | Description | Example |
|----------|-------------|---------|
| `&` | Address-of (creates a region-qualified reference) | `&x` |
| `*` | Dereference (access value through pointer/reference) | `*ptr` |

```c
int x = 42;
&stack int ref = &x;         // region-qualified reference
int y = *ref;                // dereference: y is 42
```

See [Memory & Regions](/reference/memory) for region-qualified reference details.

## Type-Related Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `(T)expr` | Explicit cast | `(double)x` |
| `sizeof(T)` | Size of a type in bytes | `sizeof(int)` |
| `sizeof(expr)` | Size of an expression's type in bytes | `sizeof(x)` |
| `alignof(T)` | Alignment requirement of a type | `alignof(double)` |

```c
int x = 42;
double d = (double)x;        // explicit cast

long s1 = sizeof(int);       // 4
long s2 = sizeof(x);         // 4
long a = alignof(double);    // 8 (platform-dependent)
```

See [Compile-Time Introspection](/advanced/introspection) for `typeof`, `fieldcount`, and `sizeof...`.

## Operator Precedence

From highest to lowest precedence:

| Precedence | Operators | Associativity |
|------------|-----------|---------------|
| 1 (highest) | `()` `[]` `.` `->` `x++` `x--` | Left-to-right |
| 2 | `++x` `--x` `+x` `-x` `!` `~` `*` `&` `(T)` `sizeof` `alignof` | Right-to-left |
| 3 | `*` `/` `%` `*\|` `*%` | Left-to-right |
| 4 | `+` `-` `+\|` `-\|` `+%` `-%` | Left-to-right |
| 5 | `<<` `>>` | Left-to-right |
| 6 | `<` `<=` `>` `>=` | Left-to-right |
| 7 | `==` `!=` | Left-to-right |
| 8 | `&` | Left-to-right |
| 9 | `^` | Left-to-right |
| 10 | `\|` | Left-to-right |
| 11 | `&&` | Left-to-right |
| 12 | `\|\|` | Left-to-right |
| 13 | `? :` | Right-to-left |
| 14 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `^=` `\|=` `<<=` `>>=` | Right-to-left |
| 15 (lowest) | `,` | Left-to-right |

## Operator Overloading

Struct types can overload binary operators by defining methods named `operator+`, `operator-`, etc. See [Functions](/reference/functions) for details.

Supported overloadable operators: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`.
