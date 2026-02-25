# Preprocessor

SafeC includes a preprocessor that supports a safe subset of C preprocessing directives. By default, it operates in **safe mode**, which restricts features that lead to hard-to-debug macro issues. A compatibility mode is available for full C macro support.

## Directives

### `#include`

Include other SafeC files or C headers:

```c
#include "mymodule.h"          // local include (quoted)
#include <stdio.h>             // system include (angle brackets)
```

System C headers are imported natively via the `CHeaderImporter`, which extracts function and typedef declarations from `clang -ast-dump=json`. See [C Interop](/reference/ffi) for details.

Use `-I <dir>` to add include search paths.

### `#define` (Object-Like)

Object-like macros define simple text substitutions:

```c
#define MAX_SIZE 1024
#define VERSION "2.0"
#define DEBUG
```

In safe mode, **function-like macros are rejected**:

```c
// ERROR in safe mode:
#define MAX(a, b) ((a) > (b) ? (a) : (b))
```

Use `const` variables and generic functions instead:

```c
const int MAX_SIZE = 1024;

generic<T: Numeric>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}
```

### `#undef`

Remove a previously defined macro:

```c
#define FEATURE_X
// ... use FEATURE_X ...
#undef FEATURE_X
```

### `#pragma once`

Prevent a header from being included multiple times:

```c
#pragma once

// header contents...
```

## Conditional Compilation

### `#ifdef` / `#ifndef`

Test whether a macro is defined:

```c
#ifdef DEBUG
    printf("debug: x = %d\n", x);
#endif

#ifndef NDEBUG
    assert(x > 0);
#endif
```

### `#if` / `#elif` / `#else` / `#endif`

General conditional compilation with constant expressions:

```c
#if PLATFORM == 1
    // Linux-specific code
#elif PLATFORM == 2
    // macOS-specific code
#else
    // Generic fallback
#endif
```

### Nesting

Conditional blocks can be nested:

```c
#ifdef FEATURE_A
    #ifdef FEATURE_B
        // both A and B enabled
    #else
        // only A enabled
    #endif
#endif
```

## Predefined Macros

| Macro | Value | Description |
|-------|-------|-------------|
| `__FILE__` | String literal | Current source file name |
| `__LINE__` | Integer literal | Current line number |

Unlike C, SafeC deliberately omits `__TIME__` and `__DATE__` to support reproducible builds.

## Command-Line Flags

### `-D <name>[=value]`

Define a macro from the command line:

```bash
./build/safec program.sc -D DEBUG -D MAX_SIZE=2048 --emit-llvm -o program.ll
```

### `-I <dir>`

Add an include search directory:

```bash
./build/safec program.sc -I ./include -I ../lib/include --emit-llvm -o program.ll
```

### `--no-import-c-headers`

Disable the native C header import mechanism. With this flag, `#include <stdio.h>` will not invoke clang for AST extraction. You must provide `extern` declarations manually.

### `--compat-preprocessor`

Enable compatibility mode with full C preprocessor features (see below).

## Safe Mode vs Compatibility Mode

### Safe Mode (Default)

The default preprocessor mode restricts features that are common sources of bugs in C:

| Feature | Safe Mode | Rationale |
|---------|-----------|-----------|
| Object-like `#define` | Allowed | Simple constant substitution |
| Function-like macros | **Rejected** | Use `const` + generics instead |
| `##` (token pasting) | **Rejected** | Obfuscates code |
| `#` (stringification) | **Rejected** | Obfuscates code |
| `#include` | Allowed | File inclusion is necessary |
| `#ifdef` / `#if` | Allowed | Conditional compilation is necessary |
| `#pragma once` | Allowed | Header guard |

### Compatibility Mode (`--compat-preprocessor`)

When working with legacy C code or headers that require full macro support, enable compatibility mode:

```bash
./build/safec legacy.sc --compat-preprocessor --emit-llvm -o legacy.ll
```

In compatibility mode, function-like macros, token pasting, and stringification are all permitted.

## Compile-Time Alternatives

SafeC provides first-class language features that replace most preprocessor use cases:

| C Preprocessor Pattern | SafeC Alternative |
|----------------------|-------------------|
| `#define PI 3.14159` | `const double PI = 3.14159;` |
| `#define MAX(a,b) ...` | `generic<T> T max(T a, T b) { ... }` |
| `#ifdef DEBUG` | `if const (DEBUG) { ... }` |
| `#define ARRAY_SIZE 100` | `const int ARRAY_SIZE = 100;` |
| Conditional function body | `if const` with `consteval` conditions |

Using language-level constructs instead of macros provides:
- **Type safety**: `const` and generics are type-checked
- **Scoping**: `const` variables respect lexical scope
- **Debuggability**: no invisible text substitution
- **IDE support**: go-to-definition, rename, hover all work correctly

## Example

```c
#pragma once
#define VERSION 1

#ifdef DEBUG
    #define LOG_LEVEL 3
#else
    #define LOG_LEVEL 0
#endif

const int MAX_CONNECTIONS = 128;

// Prefer const over #define for typed constants
const double TIMEOUT_SEC = 30.0;

// Prefer generic functions over function-like macros
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```
