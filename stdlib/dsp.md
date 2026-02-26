# DSP & Real-Time Utilities

SafeC provides four modules in `std/dsp/` for deterministic fixed-point arithmetic, digital signal processing, audio buffering, and real-time timer scheduling.

```c
#include "dsp/dsp_all.h"  // master header: fixed + dsp + audio_buffer + timer_wheel
```

---

## fixed — Q16.16 Fixed-Point Arithmetic

```c
#include "dsp/fixed.h"
```

`Fixed` is a Q16.16 fixed-point type: the integer part occupies bits 31–16, the fractional part bits 15–0. It is a `newtype` over `int`, so the compiler treats it as a distinct type while using the same bit representation.

### Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `FIXED_ONE` | 65536 | 1.0 |
| `FIXED_HALF` | 32768 | 0.5 |
| `FIXED_PI` | 205887 | ≈ π |
| `FIXED_E` | 178145 | ≈ e |

### Conversion

```c
Fixed  fixed_from_int(int x);       // x << 16
Fixed  fixed_from_float(double x);  // (Fixed)(x * 65536.0)
int    fixed_to_int(Fixed x);       // x >> 16
double fixed_to_float(Fixed x);     // x / 65536.0
```

### Arithmetic

```c
Fixed fixed_add(Fixed a, Fixed b);   // a + b
Fixed fixed_sub(Fixed a, Fixed b);   // a - b
Fixed fixed_mul(Fixed a, Fixed b);   // (long long)a * b >> 16
Fixed fixed_div(Fixed a, Fixed b);   // (long long)a << 16 / b
Fixed fixed_abs(Fixed x);
Fixed fixed_neg(Fixed x);
Fixed fixed_sqrt(Fixed x);           // 3-iteration Newton-Raphson
int   fixed_cmp(Fixed a, Fixed b);   // <0, 0, >0
```

::: tip
`fixed_mul` uses a 64-bit intermediate to avoid overflow for values up to ±32767. For safety-critical code where saturation is required, use the `*%` saturating operator: `a *% b`.
:::

### Example

```c
#include "dsp/fixed.h"
#include "io.h"

int main() {
    Fixed a = fixed_from_float(3.5);
    Fixed b = fixed_from_float(2.0);

    Fixed product = fixed_mul(a, b);       // 7.0
    Fixed root    = fixed_sqrt(product);   // ≈ 2.645

    print("7.0 * 2.0 = ");
    println_float(fixed_to_float(product));  // 7.000000

    print("sqrt(7.0) ≈ ");
    println_float(fixed_to_float(root));     // 2.645...
    return 0;
}
```

---

## dsp — Deterministic DSP Primitives

```c
#include "dsp/dsp.h"
```

All operations work on `Fixed*` arrays. No heap allocation.

### API

```c
// Dot product: sum of fixed_mul(a[i], b[i])
Fixed dsp_dot(const Fixed* a, const Fixed* b, unsigned long n);

// In-place scale: buf[i] = fixed_mul(buf[i], scale)
void  dsp_scale(Fixed* buf, unsigned long n, Fixed scale);

// Element-wise add: dst[i] += src[i]
void  dsp_add(Fixed* dst, const Fixed* src, unsigned long n);

// Sliding moving average (maintains state[0..n-1])
Fixed dsp_moving_avg(Fixed* state, unsigned long n, Fixed new_sample);

// First-order IIR low-pass: y = alpha*x + (1-alpha)*y_prev
Fixed dsp_iir_lp(Fixed* y_prev, Fixed alpha, Fixed x);

// Clamp to [lo, hi]
Fixed dsp_clip(Fixed x, Fixed lo, Fixed hi);

// Peak: maximum |buf[i]|
Fixed dsp_peak(const Fixed* buf, unsigned long n);

// RMS: sqrt(sum(buf[i]^2) / n)
Fixed dsp_rms(const Fixed* buf, unsigned long n);
```

### Example — 5-sample moving average

```c
#include "dsp/dsp.h"
#include "io.h"

int main() {
    Fixed state[5] = {0};  // moving average state

    Fixed samples[] = {
        fixed_from_float(1.0),
        fixed_from_float(3.0),
        fixed_from_float(5.0),
        fixed_from_float(2.0),
        fixed_from_float(4.0),
    };

    for (unsigned long i = 0; i < 5; i++) {
        Fixed avg = dsp_moving_avg(state, 5, samples[i]);
        print("avg = ");
        println_float(fixed_to_float(avg));
    }
    // Final avg ≈ 3.0 (mean of 1,3,5,2,4)
    return 0;
}
```

---

## AudioBuffer — Lock-Free Multi-Channel Ring Buffer

```c
#include "dsp/audio_buffer.h"
```

A SPSC (single-producer / single-consumer) ring buffer for interleaved multi-channel audio frames. Uses power-of-two capacity and compiler barriers for correct ordering without OS locks.

### Struct

```c
struct AudioBuffer {
    Fixed*        buf;          // interleaved: frame0_ch0, frame0_ch1, ...
    unsigned long cap_frames;   // must be power of two
    unsigned long channels;
    unsigned long head;         // write cursor (volatile)
    unsigned long tail;         // read cursor (volatile)

    unsigned long write_frames(const Fixed* frames, unsigned long count);
    unsigned long read_frames(Fixed* out, unsigned long count);
    unsigned long peek_frames(Fixed* out, unsigned long count) const;
    void          mix_frames(const Fixed* frames, unsigned long count);
    unsigned long readable() const;
    unsigned long writable() const;
}

AudioBuffer audio_buffer_new(unsigned long cap_frames, unsigned long channels);
void        audio_buffer_free(struct AudioBuffer* ab);
```

### Example — producer / consumer

```c
#include "dsp/audio_buffer.h"
#include "io.h"

int main() {
    // 256-frame buffer, 2 channels (stereo)
    AudioBuffer ab = audio_buffer_new(256, 2);

    // Producer: write one stereo frame
    Fixed frame[2] = { fixed_from_float(0.5), fixed_from_float(-0.3) };
    ab.write_frames(frame, 1);

    print("readable: ");
    println_int(ab.readable());  // 1

    // Consumer: read it back
    Fixed out[2];
    ab.read_frames(out, 1);
    print("L = "); println_float(fixed_to_float(out[0]));   //  0.5
    print("R = "); println_float(fixed_to_float(out[1]));   // -0.3

    audio_buffer_free(&ab);
    return 0;
}
```

::: info
`mix_frames` adds `frames` into the existing buffer content at the current write position **without** advancing `head`. This is for mixing multiple sources into one output buffer before committing. Call `write_frames` afterward to advance the cursor.
:::

---

## TimerWheel — O(1) Real-Time Timer

```c
#include "dsp/timer_wheel.h"
```

A 256-slot timer wheel supporting up to 64 concurrent timers. `tick()` fires all expired timers in O(timers) — no priority queue needed.

### Constants

```c
#define WHEEL_SLOTS      256
#define WHEEL_MAX_TIMERS  64
```

### Struct

```c
struct TimerEntry {
    void(*callback)(void* ctx);
    void*         ctx;
    unsigned long expires;  // absolute tick when to fire
    unsigned long period;   // 0 = one-shot; >0 = repeat every `period` ticks
    int           active;
}

struct TimerWheel {
    TimerEntry    entries[WHEEL_MAX_TIMERS];
    unsigned long current_tick;
    unsigned int  used;

    int  add(void(*cb)(void*), void* ctx, unsigned long delay_ticks);
    int  add_periodic(void(*cb)(void*), void* ctx, unsigned long period_ticks);
    int  cancel(int id);
    void tick();
}

TimerWheel timer_wheel_new();
```

### Methods

| Method | Description |
|--------|-------------|
| `add(cb, ctx, delay)` | One-shot timer; fires after `delay` ticks. Returns timer id or -1 if full. |
| `add_periodic(cb, ctx, period)` | Repeating timer; fires every `period` ticks, rescheduled automatically. |
| `cancel(id)` | Deactivate timer. Returns 1 if found, 0 otherwise. |
| `tick()` | Advance `current_tick`, fire all timers with `expires == current_tick`. |

### Example

```c
#include "dsp/timer_wheel.h"
#include "io.h"

int blink_count = 0;
int done = 0;

void blink(void* ctx)     { blink_count++; }
void shutdown(void* ctx)  { done = 1; }

int main() {
    TimerWheel tw = timer_wheel_new();

    tw.add_periodic(blink, 0, 10);   // blink every 10 ticks
    tw.add(shutdown, 0, 55);          // stop after 55 ticks

    while (!done) {
        tw.tick();
    }

    print("blink count: ");
    println_int(blink_count);  // 5 (ticks 10,20,30,40,50)
    return 0;
}
```

::: warning
Timer expiry is checked with `expires == current_tick`. If `tick()` is called less frequently than expected (e.g. after an interrupt latency spike), timers may be missed. For critical deadlines, poll with `expires <= current_tick` instead, or use a hardware timer ISR to drive `tick()`.
:::
