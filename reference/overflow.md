# Overflow Operators

Integer overflow is a common source of bugs and security vulnerabilities in C. SafeC provides explicit overflow control through dedicated operator variants, giving the programmer full control over overflow behavior.

## Default Behavior

By default, SafeC follows C semantics for compatibility:

- **Signed integers**: overflow is undefined behavior (the compiler may optimize assuming it never happens)
- **Unsigned integers**: overflow wraps around (modular arithmetic)

```c
int x = 2147483647;           // INT_MAX
x = x + 1;                    // undefined behavior (signed overflow)

uint32 y = 4294967295;        // UINT32_MAX
y = y + 1;                    // wraps to 0 (defined behavior)
```

## Wrapping Operators

Wrapping operators guarantee modular arithmetic for both signed and unsigned types. The result wraps around on overflow, using two's complement.

| Operator | Description |
|----------|-------------|
| `+\|` | Wrapping addition |
| `-\|` | Wrapping subtraction |
| `*\|` | Wrapping multiplication |

### Examples

```c
int x = 2147483647;           // INT_MAX (2^31 - 1)
int y = x +| 1;               // -2147483648 (wraps to INT_MIN)
int z = x +| x;               // -2 (wraps around)

uint8 a = 255;
uint8 b = a +| 1;             // 0 (wraps around)

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
uint32 write_idx = 0;
uint32 read_idx = 0;
const uint32 BUF_SIZE = 1024;

void push(int value) {
    buffer[write_idx % BUF_SIZE] = value;
    write_idx = write_idx +| 1;   // wraps safely at UINT32_MAX
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

uint8 c = 250;
uint8 d = c +% 10;            // 255 (saturates at UINT8_MAX)
uint8 e = 5;
uint8 f = e -% 10;            // 0 (saturates at 0 for unsigned)
```

### Use Cases

- Audio and signal processing (clipping)
- Color value calculations (clamped to 0-255)
- Sensor readings with physical limits
- Any domain where "closest representable value" is more useful than wrapping

```c
// Audio sample mixing with saturation
int16 mix_samples(int16 a, int16 b) {
    return a +% b;             // clamps to [-32768, 32767]
}

// Color brightness adjustment
uint8 brighten(uint8 color, uint8 amount) {
    return color +% amount;    // clamps to 255, no wrap to dark
}

// Distance calculation that can't go negative
uint32 safe_distance(uint32 a, uint32 b) {
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
