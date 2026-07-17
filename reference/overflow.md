# Overflow Operators

Integer overflow is a common source of bugs and security vulnerabilities in C. SafeC provides explicit overflow control through dedicated operator variants, giving the programmer full control over overflow behavior.

## Default Behavior

By default, SafeC follows C semantics for compatibility:

- **Signed integers**: overflow is undefined behavior (the compiler may optimize assuming it never happens)
- **Unsigned integers**: overflow wraps around (modular arithmetic)

```c
int x = 2147483647;           // INT_MAX
x = x + 1;                    // undefined behavior (signed overflow)

uint32_t y = 4294967295;        // UINT32_MAX
y = y + 1U;                   // wraps to 0 (defined behavior)
```

## Wrapping Operators

Wrapping operators guarantee modular arithmetic for both signed and unsigned types. The result wraps around on overflow, using two's complement.

| Operator | Description |
|----------|-------------|
| `+\|` | Wrapping addition |
| `-\|` | Wrapping subtraction |
| `*\|` | Wrapping multiplication |

> **8-bit operands promote to `int`.** Like C, arithmetic on `int8_t`/`uint8_t`
> (and `char`) always evaluates at `int` width before any narrowing — this
> applies to the wrapping/saturating operators too, so `uint8_t + uint8_t`
> is still an `int`-typed expression. Wrap the whole expression in a cast
> back to the 8-bit type when assigning the result (see `b`, `d`, `f`, and
> `brighten()` below). 16-bit-and-wider operands aren't affected.

### Examples

```c
int x = 2147483647;           // INT_MAX (2^31 - 1)
int y = x +| 1;               // -2147483648 (wraps to INT_MIN)
int z = x +| x;               // -2 (wraps around)

uint8_t a = 255;
uint8_t b = (uint8_t)(a +| 1);  // 0 (wraps around)

int big = 1000000;
int overflow = big *| big;     // wraps (1000000^2 mod 2^32)
```

### Use Cases

- Hash functions and checksums
- Sequence number arithmetic
- Cryptographic operations
- Ring buffer index calculations

```c
// Ring buffer with wrapping index
uint32_t write_idx = 0;
uint32_t read_idx = 0;
const uint32_t BUF_SIZE = 1024;

void push(int value) {
    buffer[write_idx % BUF_SIZE] = value;
    write_idx = write_idx +| 1U;  // wraps safely at UINT32_MAX
}
```

## Saturating Operators

Saturating operators clamp the result to the type's minimum or maximum value on overflow, instead of wrapping.

| Operator | Description |
|----------|-------------|
| `+%` | Saturating addition |
| `-%` | Saturating subtraction |
| `*%` | Saturating multiplication |

### Examples

```c
int x = 2147483647;           // INT_MAX
int y = x +% 1;               // 2147483647 (saturates at INT_MAX)
int z = x +% 100;             // 2147483647 (still INT_MAX)

int a = -2147483648;          // INT_MIN
int b = a -% 1;               // -2147483648 (saturates at INT_MIN)

uint8_t c = 250;
uint8_t d = (uint8_t)(c +% 10); // 255 (saturates at UINT8_MAX)
uint8_t e = 5;
uint8_t f = (uint8_t)(e -% 10); // 0 (saturates at 0 for unsigned)
```

### Use Cases

- Audio and signal processing (clipping)
- Color value calculations (clamped to 0-255)
- Sensor readings with physical limits
- Any domain where "closest representable value" is more useful than wrapping

```c
// Audio sample mixing with saturation
int16_t mix_samples(int16_t a, int16_t b) {
    return a +% b;             // clamps to [-32768, 32767]
}

// Color brightness adjustment
uint8_t brighten(uint8_t color, uint8_t amount) {
    return (uint8_t)(color +% amount); // clamps to 255, no wrap to dark
}

// Distance calculation that can't go negative
uint32_t safe_distance(uint32_t a, uint32_t b) {
    if (a > b) return a -% b;
    return b -% a;
}
```

## Comparison with Other Languages

| Language | Default | Wrapping | Saturating |
|----------|---------|----------|------------|
| C | UB (signed) / wrap (unsigned) | N/A | N/A |
| SafeC | UB (signed) / wrap (unsigned) | `+\|` `-\|` `*\|` | `+%` `-%` `*%` |
| Rust | Panic (debug) / wrap (release) | `.wrapping_add()` | `.saturating_add()` |
| Zig | Undefined (optimized) | `+%` | `@addWithOverflow` |
| Swift | Trap | `&+` | `clamping:` |

SafeC uses operator syntax rather than method calls, keeping expressions readable:

```c
// SafeC: natural operator syntax
int result = a +| b *| c;

// vs. method-based (other languages)
// int result = a.wrapping_add(b.wrapping_mul(c));
```

## Summary

| Category | Operators | Overflow Behavior |
|----------|-----------|-------------------|
| Default | `+` `-` `*` | UB for signed, wrap for unsigned |
| Wrapping | `+\|` `-\|` `*\|` | Two's complement wrap for all types |
| Saturating | `+%` `-%` `*%` | Clamp to min/max of type |
