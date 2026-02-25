# Literals & Qualifiers

This page covers literal syntax, storage qualifiers, and variable attributes that are part of SafeC's C-compatible foundation.

## Integer Literals

SafeC supports multiple integer literal formats:

| Format | Prefix | Example | Value |
|--------|--------|---------|-------|
| Decimal | (none) | `42` | 42 |
| Hexadecimal | `0x` / `0X` | `0xFF` | 255 |
| Binary | `0b` / `0B` | `0b1010` | 10 |
| Octal | `0` | `0777` | 511 |

### Integer Suffixes

Suffixes control the type of an integer literal:

| Suffix | Type | Example |
|--------|------|---------|
| (none) | `int` | `42` |
| `U` / `u` | `unsigned int` | `42U` |
| `L` / `l` | `long` | `42L` |
| `LL` / `ll` | `long long` | `42LL` |
| `UL` / `ul` | `unsigned long` | `42UL` |
| `ULL` / `ull` | `unsigned long long` | `42ULL` |

```c
int a = 42;
unsigned int b = 42U;
long long c = 100000LL;
unsigned long long d = 0xFFFFFFFFFFFFFFFFULL;
```

## Float Literals

```c
double a = 3.14;
double b = 1.5e-2;        // 0.015 (scientific notation)
double c = 0.;             // 0.0 (trailing dot)
float d = 3.14f;           // f suffix for float
```

## Character Literals

Character literals are enclosed in single quotes:

```c
char a = 'A';
char newline = '\n';
char tab = '\t';
char hex = '\x41';         // 'A' via hex escape
char null = '\0';          // null byte
```

### Escape Sequences

| Escape | Meaning |
|--------|---------|
| `\n` | Newline |
| `\t` | Tab |
| `\r` | Carriage return |
| `\\` | Backslash |
| `\'` | Single quote |
| `\"` | Double quote |
| `\0` | Null byte |
| `\xNN` | Hex byte value |

## String Literals

String literals are enclosed in double quotes and are implicitly `&static char` references (null-terminated):

```c
const char *msg = "hello, world";
```

String literals can be passed directly to `extern` C functions because `&static` coerces to `char*` without requiring `unsafe`.

## Boolean Literals

```c
bool a = true;
bool b = false;
```

## Null Literal

```c
?&stack Node next = null;   // null for nullable references
?int val = none;             // none for optional values
```

## Storage Qualifiers

### `const`

Declares an immutable binding. The value cannot be modified after initialization.

```c
const int MAX_SIZE = 1024;
const double PI = 3.14159265358979;
```

`const` on function parameters prevents modification of the argument:

```c
int length(const char *str) {
    // str contents cannot be modified
}
```

### `static`

For local variables, `static` gives the variable static storage duration — it persists across function calls and is initialized only once:

```c
int call_count() {
    static int count = 0;
    count++;
    return count;
}
// First call returns 1, second returns 2, etc.
```

For global variables, `static` restricts visibility to the current translation unit (internal linkage):

```c
static int module_state = 0;   // not visible outside this file
```

### `extern`

Declares a variable or function defined in another translation unit:

```c
extern int global_counter;     // defined elsewhere
extern void c_function();      // C function declaration
```

### `volatile`

Prevents the compiler from optimizing away reads or writes. Used for memory-mapped I/O and hardware registers:

```c
volatile int *status_reg = (volatile int *)0x40001000;
int val = *status_reg;         // guaranteed to read from memory
*status_reg = 1;               // guaranteed to write to memory
```

See [Bare-Metal](/reference/baremetal) for more on volatile access patterns.

### `thread_local`

Declares a variable with thread-local storage duration. Each thread gets its own copy:

```c
thread_local int error_code = 0;
```

The aliases `_Thread_local` and `__thread` are also accepted for C/GCC compatibility:

```c
_Thread_local int tls_var = 0;     // C11 style
__thread int gcc_tls_var = 0;      // GCC extension style
```

Thread-local variables are initialized when each thread starts and are independent across threads.

### `atomic`

Declares an atomic variable for lock-free concurrent access:

```c
atomic int counter = 0;
```

See [Concurrency](/reference/concurrency) for atomic operations.

## Variable Attributes

### `align(N)`

Specifies the alignment requirement for a variable or struct field. `N` must be a power of two.

```c
align(16) float vec[4];           // 16-byte aligned (suitable for SIMD)
align(64) char cache_line[64];    // cache-line aligned
```

This maps to LLVM's alignment attributes and is useful for performance-critical code, SIMD operations, and hardware requirements.

### `section("name")`

Places a variable or function in a specific linker section:

```c
section(".isr_vector") void (*vectors[])() = { reset_handler, nmi_handler };
section(".rodata") const int lookup[256] = { /* ... */ };
```

See [Bare-Metal](/reference/baremetal) for linker section usage.

## Calling Conventions

SafeC supports platform-specific calling convention annotations for interoperability with system APIs and foreign code:

| Annotation | Convention | Platform |
|------------|-----------|----------|
| `__cdecl` | C default (caller cleans stack) | x86 |
| `__stdcall` | Callee cleans stack | x86 Windows |
| `__fastcall` | First args in registers | x86 |

```c
__stdcall int WinApiCallback(int msg, int wparam, int lparam) {
    // Windows API callback with stdcall convention
    return 0;
}

__cdecl void normal_func() {
    // explicit C calling convention
}
```

Calling conventions are primarily relevant for x86 Windows interop. On other platforms and architectures, the default calling convention is typically sufficient.

## Type Qualifiers Summary

| Qualifier | Effect |
|-----------|--------|
| `const` | Immutable binding |
| `static` | Persistent storage (local) or internal linkage (global) |
| `extern` | External linkage declaration |
| `volatile` | Prevents optimization of reads/writes |
| `atomic` | Lock-free concurrent access |
| `thread_local` | Per-thread storage |
| `align(N)` | Custom alignment |
| `section("name")` | Linker section placement |
