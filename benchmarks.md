# Benchmarks

How SafeC compares to C, C++, Rust, Zig, Go, and Python on wall-clock time and peak memory, across a few classic microbenchmarks — in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) and [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app), scaled down to what fits in one CI-sized machine. **Fastest** and **leanest** cells are bolded per benchmark.

::: warning Read this before the numbers below
This is a **single machine, single session, best-of-3** measurement — not the Benchmarks Game's much more rigorous multi-run statistical methodology. Treat every number here as "roughly this, on one Apple M1 Pro" rather than a universal truth about the language. Three benchmarks cannot characterize a language's overall performance; they characterize *these three workloads*, on *this machine*, with *these compiler versions*, on *this day*. What the numbers ARE good for: showing where SafeC's actual, current tradeoffs are — including ones its own documentation didn't know about until this page was built (see the multithreading section).
:::

## Methodology

- **Machine**: Apple M1 Pro, 10 cores, 32 GB RAM, macOS 26.5.1.
- **Toolchains**: `safec` 1.0.0 (this repo's build) · Apple Clang 21.0.0 (C/C++) · Go 1.26.5 · Zig 0.16.0 · Rust 1.97.1 · Python 3.14.6 (+ NumPy 2.5.1 for one SIMD row).
- **Build flags** — debug: `-O0` (C/C++), unoptimized IR (SafeC), `go build -gcflags="all=-N -l"`, Zig's default Debug mode, plain `rustc`. Release: `-O2` (C/C++, and SafeC's `clang -O2` backend step — matching what `safeguard build --release` actually does, not a hand-picked "best" flag), plain `go build`, `zig build-exe -O ReleaseFast`, `rustc -O`. Python has no debug/release distinction — one interpreted number, shown in both columns for reference.
- **Timing**: `/usr/bin/time -l`, best (lowest) wall-clock time of 3 runs. **Memory**: peak of the same 3 runs' "maximum resident set size."
- **Correctness**: every binary's output was checked against the known-correct value for its workload before being included — a benchmark that produced a wrong answer would be a bug in the harness, not a real result, so none are shown.
- All 34 source files used are included at the bottom of this page and are also downloadable as plain files (see the "raw" link on each).

## Single-threaded

### fib(37) — recursive Fibonacci

Naive recursive Fibonacci — pure function-call and integer-arithmetic overhead, no allocation, no I/O.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.13s | **1.3 MB (leanest)** | 0.12s |
| C | 0.13s | 1.3 MB | 0.09s |
| C++ | 0.13s | 1.3 MB | 0.10s |
| Rust | 0.17s | 1.5 MB | 0.12s |
| Zig | 0.15s | 1.7 MB | 1.26s |
| Go | **0.11s (fastest)** | 4.0 MB | 0.09s |
| Python | 2.66s | 14.5 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.10s |
| C | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.08s |
| C++ | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.08s |
| Rust | **0.07s (fastest)** | 1.5 MB | 0.12s |
| Zig | **0.07s (fastest)** | 1.5 MB | 4.91s |
| Go | 0.08s | 3.8 MB | 0.07s |
| Python | 2.66s | 14.5 MB | N/A (interpreted) |

<details>
<summary>Show fib source (all languages)</summary>

<details>
<summary>SafeC — <code>fib.sc</code> (<a href="/benchmarks/fib/safec/fib.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);

long long fib(int n) {
    if (n < 2) return (long long)n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    long long r = fib(37);
    printf("%lld\n", r);
    return 0;
}
```

</details>

<details>
<summary>C — <code>fib.c</code> (<a href="/benchmarks/fib/c/fib.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
long long fib(int n) {
    if (n < 2) return (long long)n;
    return fib(n - 1) + fib(n - 2);
}
int main() {
    long long r = fib(37);
    printf("%lld\n", r);
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>fib.cpp</code> (<a href="/benchmarks/fib/cpp/fib.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>
long long fib(int n) {
    if (n < 2) return (long long)n;
    return fib(n - 1) + fib(n - 2);
}
int main() {
    long long r = fib(37);
    printf("%lld\n", r);
    return 0;
}
```

</details>

<details>
<summary>Rust — <code>fib.rs</code> (<a href="/benchmarks/fib/rust/fib.rs" target="_blank">raw</a>)</summary>

```rust
fn fib(n: i64) -> i64 {
    if n < 2 { return n; }
    fib(n - 1) + fib(n - 2)
}
fn main() {
    println!("{}", fib(37));
}
```

</details>

<details>
<summary>Zig — <code>fib.zig</code> (<a href="/benchmarks/fib/zig/fib.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");
fn fib(n: i64) i64 {
    if (n < 2) return n;
    return fib(n - 1) + fib(n - 2);
}
pub fn main() void {
    const r = fib(37);
    std.debug.print("{d}\n", .{r});
}
```

</details>

<details>
<summary>Go — <code>fib.go</code> (<a href="/benchmarks/fib/go/fib.go" target="_blank">raw</a>)</summary>

```go
package main
import "fmt"
func fib(n int) int64 {
    if n < 2 {
        return int64(n)
    }
    return fib(n-1) + fib(n-2)
}
func main() {
    fmt.Println(fib(37))
}
```

</details>

<details>
<summary>Python — <code>fib.py</code> (<a href="/benchmarks/fib/python/fib.py" target="_blank">raw</a>)</summary>

```python
import sys
def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)
print(fib(37))
```

</details>

</details>

### n-body — 5-body orbital simulation

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 simulation steps. Floating-point heavy, no allocation, tiny working set.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.63s | 1.3 MB | 0.11s |
| C | 0.41s | **1.3 MB (leanest)** | 0.11s |
| C++ | 0.41s | 1.3 MB | 0.12s |
| Rust | 0.68s | 1.5 MB | 0.13s |
| Zig | 0.54s | 1.8 MB | 1.27s |
| Go | **0.27s (fastest)** | 4.2 MB | 0.08s |
| Python | 9.73s | 15.1 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.11s | **1.3 MB (leanest)** | 0.11s |
| C | **0.10s (fastest)** | 1.3 MB | 0.09s |
| C++ | **0.10s (fastest)** | **1.3 MB (leanest)** | 0.11s |
| Rust | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.11s | 1.5 MB | 5.19s |
| Go | **0.10s (fastest)** | 3.9 MB | 0.08s |
| Python | 9.73s | 15.1 MB | N/A (interpreted) |

<details>
<summary>Show n-body source (all languages)</summary>

<details>
<summary>SafeC — <code>nbody.sc</code> (<a href="/benchmarks/nbody/safec/nbody.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);
extern double sqrt(double x);

#define BODIES 5
#define PI 3.141592653589793
#define SOLAR_MASS (4.0 * PI * PI)
#define DAYS_PER_YEAR 365.24

double x[BODIES]; double y[BODIES]; double z[BODIES];
double vx[BODIES]; double vy[BODIES]; double vz[BODIES];
double mass[BODIES];

void init_bodies() {
    // Sun
    x[0]=0.0; y[0]=0.0; z[0]=0.0; vx[0]=0.0; vy[0]=0.0; vz[0]=0.0; mass[0]=SOLAR_MASS;
    // Jupiter
    x[1]=4.84143144246472090e+00; y[1]=-1.16032004402742839e+00; z[1]=-1.03622044471123109e-01;
    vx[1]=1.66007664274403694e-03*DAYS_PER_YEAR; vy[1]=7.69901118419740425e-03*DAYS_PER_YEAR; vz[1]=-6.90460016972063023e-05*DAYS_PER_YEAR;
    mass[1]=9.54791938424326609e-04*SOLAR_MASS;
    // Saturn
    x[2]=8.34336671824457987e+00; y[2]=4.12479856412430479e+00; z[2]=-4.03523417114321381e-01;
    vx[2]=-2.76742510726862411e-03*DAYS_PER_YEAR; vy[2]=4.99852801234917238e-03*DAYS_PER_YEAR; vz[2]=2.30417297573763929e-05*DAYS_PER_YEAR;
    mass[2]=2.85885980666130812e-04*SOLAR_MASS;
    // Uranus
    x[3]=1.28943695621391310e+01; y[3]=-1.51111514016986312e+01; z[3]=-2.23307578892655734e-01;
    vx[3]=2.96460137564761618e-03*DAYS_PER_YEAR; vy[3]=2.37847173959480950e-03*DAYS_PER_YEAR; vz[3]=-2.96589568540237556e-05*DAYS_PER_YEAR;
    mass[3]=4.36624404335156298e-05*SOLAR_MASS;
    // Neptune
    x[4]=1.53796971148509165e+01; y[4]=-2.59193146099879641e+01; z[4]=1.79258772950371181e-01;
    vx[4]=2.68067772490389322e-03*DAYS_PER_YEAR; vy[4]=1.62824170038242295e-03*DAYS_PER_YEAR; vz[4]=-9.51592254519715870e-05*DAYS_PER_YEAR;
    mass[4]=5.15138902046611451e-05*SOLAR_MASS;

    double px = 0.0; double py = 0.0; double pz = 0.0;
    int i = 0;
    while (i < BODIES) {
        px = px + vx[i]*mass[i];
        py = py + vy[i]*mass[i];
        pz = pz + vz[i]*mass[i];
        i = i + 1;
    }
    vx[0] = 0.0 - px / SOLAR_MASS;
    vy[0] = 0.0 - py / SOLAR_MASS;
    vz[0] = 0.0 - pz / SOLAR_MASS;
}

void advance(double dt) {
    int i = 0;
    while (i < BODIES) {
        int j = i + 1;
        while (j < BODIES) {
            double dx = x[i] - x[j];
            double dy = y[i] - y[j];
            double dz = z[i] - z[j];
            double d2 = dx*dx + dy*dy + dz*dz;
            double mag = dt / (d2 * sqrt(d2));
            vx[i] = vx[i] - dx * mass[j] * mag;
            vy[i] = vy[i] - dy * mass[j] * mag;
            vz[i] = vz[i] - dz * mass[j] * mag;
            vx[j] = vx[j] + dx * mass[i] * mag;
            vy[j] = vy[j] + dy * mass[i] * mag;
            vz[j] = vz[j] + dz * mass[i] * mag;
            j = j + 1;
        }
        i = i + 1;
    }
    i = 0;
    while (i < BODIES) {
        x[i] = x[i] + dt * vx[i];
        y[i] = y[i] + dt * vy[i];
        z[i] = z[i] + dt * vz[i];
        i = i + 1;
    }
}

double energy() {
    double e = 0.0;
    int i = 0;
    while (i < BODIES) {
        e = e + 0.5 * mass[i] * (vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i]);
        int j = i + 1;
        while (j < BODIES) {
            double dx = x[i] - x[j];
            double dy = y[i] - y[j];
            double dz = z[i] - z[j];
            double distance = sqrt(dx*dx + dy*dy + dz*dz);
            e = e - (mass[i] * mass[j]) / distance;
            j = j + 1;
        }
        i = i + 1;
    }
    return e;
}

int main() {
    init_bodies();
    printf("%.9f\n", energy());
    int n = 0;
    while (n < 2000000) {
        advance(0.01);
        n = n + 1;
    }
    printf("%.9f\n", energy());
    return 0;
}
```

</details>

<details>
<summary>C — <code>nbody.c</code> (<a href="/benchmarks/nbody/c/nbody.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
#include <math.h>
#define BODIES 5
#define PI 3.141592653589793
#define SOLAR_MASS (4.0 * PI * PI)
#define DAYS_PER_YEAR 365.24

double x[BODIES], y[BODIES], z[BODIES];
double vx[BODIES], vy[BODIES], vz[BODIES];
double mass[BODIES];

void init_bodies() {
    x[0]=0.0; y[0]=0.0; z[0]=0.0; vx[0]=0.0; vy[0]=0.0; vz[0]=0.0; mass[0]=SOLAR_MASS;
    x[1]=4.84143144246472090e+00; y[1]=-1.16032004402742839e+00; z[1]=-1.03622044471123109e-01;
    vx[1]=1.66007664274403694e-03*DAYS_PER_YEAR; vy[1]=7.69901118419740425e-03*DAYS_PER_YEAR; vz[1]=-6.90460016972063023e-05*DAYS_PER_YEAR;
    mass[1]=9.54791938424326609e-04*SOLAR_MASS;
    x[2]=8.34336671824457987e+00; y[2]=4.12479856412430479e+00; z[2]=-4.03523417114321381e-01;
    vx[2]=-2.76742510726862411e-03*DAYS_PER_YEAR; vy[2]=4.99852801234917238e-03*DAYS_PER_YEAR; vz[2]=2.30417297573763929e-05*DAYS_PER_YEAR;
    mass[2]=2.85885980666130812e-04*SOLAR_MASS;
    x[3]=1.28943695621391310e+01; y[3]=-1.51111514016986312e+01; z[3]=-2.23307578892655734e-01;
    vx[3]=2.96460137564761618e-03*DAYS_PER_YEAR; vy[3]=2.37847173959480950e-03*DAYS_PER_YEAR; vz[3]=-2.96589568540237556e-05*DAYS_PER_YEAR;
    mass[3]=4.36624404335156298e-05*SOLAR_MASS;
    x[4]=1.53796971148509165e+01; y[4]=-2.59193146099879641e+01; z[4]=1.79258772950371181e-01;
    vx[4]=2.68067772490389322e-03*DAYS_PER_YEAR; vy[4]=1.62824170038242295e-03*DAYS_PER_YEAR; vz[4]=-9.51592254519715870e-05*DAYS_PER_YEAR;
    mass[4]=5.15138902046611451e-05*SOLAR_MASS;

    double px=0.0, py=0.0, pz=0.0;
    for (int i=0;i<BODIES;i++) { px+=vx[i]*mass[i]; py+=vy[i]*mass[i]; pz+=vz[i]*mass[i]; }
    vx[0] = -px/SOLAR_MASS; vy[0] = -py/SOLAR_MASS; vz[0] = -pz/SOLAR_MASS;
}

void advance(double dt) {
    for (int i=0;i<BODIES;i++) {
        for (int j=i+1;j<BODIES;j++) {
            double dx=x[i]-x[j], dy=y[i]-y[j], dz=z[i]-z[j];
            double d2 = dx*dx+dy*dy+dz*dz;
            double mag = dt / (d2*sqrt(d2));
            vx[i]-=dx*mass[j]*mag; vy[i]-=dy*mass[j]*mag; vz[i]-=dz*mass[j]*mag;
            vx[j]+=dx*mass[i]*mag; vy[j]+=dy*mass[i]*mag; vz[j]+=dz*mass[i]*mag;
        }
    }
    for (int i=0;i<BODIES;i++) { x[i]+=dt*vx[i]; y[i]+=dt*vy[i]; z[i]+=dt*vz[i]; }
}

double energy() {
    double e=0.0;
    for (int i=0;i<BODIES;i++) {
        e += 0.5*mass[i]*(vx[i]*vx[i]+vy[i]*vy[i]+vz[i]*vz[i]);
        for (int j=i+1;j<BODIES;j++) {
            double dx=x[i]-x[j], dy=y[i]-y[j], dz=z[i]-z[j];
            double distance = sqrt(dx*dx+dy*dy+dz*dz);
            e -= (mass[i]*mass[j])/distance;
        }
    }
    return e;
}

int main() {
    init_bodies();
    printf("%.9f\n", energy());
    for (int n=0;n<2000000;n++) advance(0.01);
    printf("%.9f\n", energy());
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>nbody.cpp</code> (<a href="/benchmarks/nbody/cpp/nbody.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>
#include <cmath>
#define BODIES 5
#define PI 3.141592653589793
#define SOLAR_MASS (4.0 * PI * PI)
#define DAYS_PER_YEAR 365.24

double x[BODIES], y[BODIES], z[BODIES];
double vx[BODIES], vy[BODIES], vz[BODIES];
double mass[BODIES];

void init_bodies() {
    x[0]=0.0; y[0]=0.0; z[0]=0.0; vx[0]=0.0; vy[0]=0.0; vz[0]=0.0; mass[0]=SOLAR_MASS;
    x[1]=4.84143144246472090e+00; y[1]=-1.16032004402742839e+00; z[1]=-1.03622044471123109e-01;
    vx[1]=1.66007664274403694e-03*DAYS_PER_YEAR; vy[1]=7.69901118419740425e-03*DAYS_PER_YEAR; vz[1]=-6.90460016972063023e-05*DAYS_PER_YEAR;
    mass[1]=9.54791938424326609e-04*SOLAR_MASS;
    x[2]=8.34336671824457987e+00; y[2]=4.12479856412430479e+00; z[2]=-4.03523417114321381e-01;
    vx[2]=-2.76742510726862411e-03*DAYS_PER_YEAR; vy[2]=4.99852801234917238e-03*DAYS_PER_YEAR; vz[2]=2.30417297573763929e-05*DAYS_PER_YEAR;
    mass[2]=2.85885980666130812e-04*SOLAR_MASS;
    x[3]=1.28943695621391310e+01; y[3]=-1.51111514016986312e+01; z[3]=-2.23307578892655734e-01;
    vx[3]=2.96460137564761618e-03*DAYS_PER_YEAR; vy[3]=2.37847173959480950e-03*DAYS_PER_YEAR; vz[3]=-2.96589568540237556e-05*DAYS_PER_YEAR;
    mass[3]=4.36624404335156298e-05*SOLAR_MASS;
    x[4]=1.53796971148509165e+01; y[4]=-2.59193146099879641e+01; z[4]=1.79258772950371181e-01;
    vx[4]=2.68067772490389322e-03*DAYS_PER_YEAR; vy[4]=1.62824170038242295e-03*DAYS_PER_YEAR; vz[4]=-9.51592254519715870e-05*DAYS_PER_YEAR;
    mass[4]=5.15138902046611451e-05*SOLAR_MASS;

    double px=0.0, py=0.0, pz=0.0;
    for (int i=0;i<BODIES;i++) { px+=vx[i]*mass[i]; py+=vy[i]*mass[i]; pz+=vz[i]*mass[i]; }
    vx[0] = -px/SOLAR_MASS; vy[0] = -py/SOLAR_MASS; vz[0] = -pz/SOLAR_MASS;
}

void advance(double dt) {
    for (int i=0;i<BODIES;i++) {
        for (int j=i+1;j<BODIES;j++) {
            double dx=x[i]-x[j], dy=y[i]-y[j], dz=z[i]-z[j];
            double d2 = dx*dx+dy*dy+dz*dz;
            double mag = dt / (d2*std::sqrt(d2));
            vx[i]-=dx*mass[j]*mag; vy[i]-=dy*mass[j]*mag; vz[i]-=dz*mass[j]*mag;
            vx[j]+=dx*mass[i]*mag; vy[j]+=dy*mass[i]*mag; vz[j]+=dz*mass[i]*mag;
        }
    }
    for (int i=0;i<BODIES;i++) { x[i]+=dt*vx[i]; y[i]+=dt*vy[i]; z[i]+=dt*vz[i]; }
}

double energy() {
    double e=0.0;
    for (int i=0;i<BODIES;i++) {
        e += 0.5*mass[i]*(vx[i]*vx[i]+vy[i]*vy[i]+vz[i]*vz[i]);
        for (int j=i+1;j<BODIES;j++) {
            double dx=x[i]-x[j], dy=y[i]-y[j], dz=z[i]-z[j];
            double distance = std::sqrt(dx*dx+dy*dy+dz*dz);
            e -= (mass[i]*mass[j])/distance;
        }
    }
    return e;
}

int main() {
    init_bodies();
    std::printf("%.9f\n", energy());
    for (int n=0;n<2000000;n++) advance(0.01);
    std::printf("%.9f\n", energy());
    return 0;
}
```

</details>

<details>
<summary>Rust — <code>nbody.rs</code> (<a href="/benchmarks/nbody/rust/nbody.rs" target="_blank">raw</a>)</summary>

```rust
const BODIES: usize = 5;
const PI: f64 = 3.141592653589793;
const SOLAR_MASS: f64 = 4.0 * PI * PI;
const DAYS_PER_YEAR: f64 = 365.24;

struct Bodies {
    x: [f64; BODIES],
    y: [f64; BODIES],
    z: [f64; BODIES],
    vx: [f64; BODIES],
    vy: [f64; BODIES],
    vz: [f64; BODIES],
    mass: [f64; BODIES],
}

fn init_bodies() -> Bodies {
    let mut b = Bodies {
        x: [0.0; BODIES], y: [0.0; BODIES], z: [0.0; BODIES],
        vx: [0.0; BODIES], vy: [0.0; BODIES], vz: [0.0; BODIES],
        mass: [0.0; BODIES],
    };
    b.mass[0] = SOLAR_MASS;

    b.x[1] = 4.84143144246472090e+00; b.y[1] = -1.16032004402742839e+00; b.z[1] = -1.03622044471123109e-01;
    b.vx[1] = 1.66007664274403694e-03 * DAYS_PER_YEAR; b.vy[1] = 7.69901118419740425e-03 * DAYS_PER_YEAR; b.vz[1] = -6.90460016972063023e-05 * DAYS_PER_YEAR;
    b.mass[1] = 9.54791938424326609e-04 * SOLAR_MASS;

    b.x[2] = 8.34336671824457987e+00; b.y[2] = 4.12479856412430479e+00; b.z[2] = -4.03523417114321381e-01;
    b.vx[2] = -2.76742510726862411e-03 * DAYS_PER_YEAR; b.vy[2] = 4.99852801234917238e-03 * DAYS_PER_YEAR; b.vz[2] = 2.30417297573763929e-05 * DAYS_PER_YEAR;
    b.mass[2] = 2.85885980666130812e-04 * SOLAR_MASS;

    b.x[3] = 1.28943695621391310e+01; b.y[3] = -1.51111514016986312e+01; b.z[3] = -2.23307578892655734e-01;
    b.vx[3] = 2.96460137564761618e-03 * DAYS_PER_YEAR; b.vy[3] = 2.37847173959480950e-03 * DAYS_PER_YEAR; b.vz[3] = -2.96589568540237556e-05 * DAYS_PER_YEAR;
    b.mass[3] = 4.36624404335156298e-05 * SOLAR_MASS;

    b.x[4] = 1.53796971148509165e+01; b.y[4] = -2.59193146099879641e+01; b.z[4] = 1.79258772950371181e-01;
    b.vx[4] = 2.68067772490389322e-03 * DAYS_PER_YEAR; b.vy[4] = 1.62824170038242295e-03 * DAYS_PER_YEAR; b.vz[4] = -9.51592254519715870e-05 * DAYS_PER_YEAR;
    b.mass[4] = 5.15138902046611451e-05 * SOLAR_MASS;

    let mut px = 0.0; let mut py = 0.0; let mut pz = 0.0;
    for i in 0..BODIES {
        px += b.vx[i] * b.mass[i];
        py += b.vy[i] * b.mass[i];
        pz += b.vz[i] * b.mass[i];
    }
    b.vx[0] = -px / SOLAR_MASS;
    b.vy[0] = -py / SOLAR_MASS;
    b.vz[0] = -pz / SOLAR_MASS;
    b
}

fn advance(b: &mut Bodies, dt: f64) {
    for i in 0..BODIES {
        for j in (i + 1)..BODIES {
            let dx = b.x[i] - b.x[j];
            let dy = b.y[i] - b.y[j];
            let dz = b.z[i] - b.z[j];
            let d2 = dx * dx + dy * dy + dz * dz;
            let mag = dt / (d2 * d2.sqrt());
            b.vx[i] -= dx * b.mass[j] * mag;
            b.vy[i] -= dy * b.mass[j] * mag;
            b.vz[i] -= dz * b.mass[j] * mag;
            b.vx[j] += dx * b.mass[i] * mag;
            b.vy[j] += dy * b.mass[i] * mag;
            b.vz[j] += dz * b.mass[i] * mag;
        }
    }
    for i in 0..BODIES {
        b.x[i] += dt * b.vx[i];
        b.y[i] += dt * b.vy[i];
        b.z[i] += dt * b.vz[i];
    }
}

fn energy(b: &Bodies) -> f64 {
    let mut e = 0.0;
    for i in 0..BODIES {
        e += 0.5 * b.mass[i] * (b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i]);
        for j in (i + 1)..BODIES {
            let dx = b.x[i] - b.x[j];
            let dy = b.y[i] - b.y[j];
            let dz = b.z[i] - b.z[j];
            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
            e -= (b.mass[i] * b.mass[j]) / distance;
        }
    }
    e
}

fn main() {
    let mut b = init_bodies();
    println!("{:.9}", energy(&b));
    for _ in 0..2000000 {
        advance(&mut b, 0.01);
    }
    println!("{:.9}", energy(&b));
}
```

</details>

<details>
<summary>Zig — <code>nbody.zig</code> (<a href="/benchmarks/nbody/zig/nbody.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");
const math = std.math;

const bodies = 5;
const pi = 3.141592653589793;
const solar_mass = 4.0 * pi * pi;
const days_per_year = 365.24;

var x: [bodies]f64 = undefined;
var y: [bodies]f64 = undefined;
var z: [bodies]f64 = undefined;
var vx: [bodies]f64 = undefined;
var vy: [bodies]f64 = undefined;
var vz: [bodies]f64 = undefined;
var mass: [bodies]f64 = undefined;

fn initBodies() void {
    x[0] = 0.0; y[0] = 0.0; z[0] = 0.0; vx[0] = 0.0; vy[0] = 0.0; vz[0] = 0.0; mass[0] = solar_mass;

    x[1] = 4.84143144246472090e+00; y[1] = -1.16032004402742839e+00; z[1] = -1.03622044471123109e-01;
    vx[1] = 1.66007664274403694e-03 * days_per_year; vy[1] = 7.69901118419740425e-03 * days_per_year; vz[1] = -6.90460016972063023e-05 * days_per_year;
    mass[1] = 9.54791938424326609e-04 * solar_mass;

    x[2] = 8.34336671824457987e+00; y[2] = 4.12479856412430479e+00; z[2] = -4.03523417114321381e-01;
    vx[2] = -2.76742510726862411e-03 * days_per_year; vy[2] = 4.99852801234917238e-03 * days_per_year; vz[2] = 2.30417297573763929e-05 * days_per_year;
    mass[2] = 2.85885980666130812e-04 * solar_mass;

    x[3] = 1.28943695621391310e+01; y[3] = -1.51111514016986312e+01; z[3] = -2.23307578892655734e-01;
    vx[3] = 2.96460137564761618e-03 * days_per_year; vy[3] = 2.37847173959480950e-03 * days_per_year; vz[3] = -2.96589568540237556e-05 * days_per_year;
    mass[3] = 4.36624404335156298e-05 * solar_mass;

    x[4] = 1.53796971148509165e+01; y[4] = -2.59193146099879641e+01; z[4] = 1.79258772950371181e-01;
    vx[4] = 2.68067772490389322e-03 * days_per_year; vy[4] = 1.62824170038242295e-03 * days_per_year; vz[4] = -9.51592254519715870e-05 * days_per_year;
    mass[4] = 5.15138902046611451e-05 * solar_mass;

    var px: f64 = 0.0;
    var py: f64 = 0.0;
    var pz: f64 = 0.0;
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        px += vx[i] * mass[i];
        py += vy[i] * mass[i];
        pz += vz[i] * mass[i];
    }
    vx[0] = -px / solar_mass;
    vy[0] = -py / solar_mass;
    vz[0] = -pz / solar_mass;
}

fn advance(dt: f64) void {
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        var j: usize = i + 1;
        while (j < bodies) : (j += 1) {
            const dx = x[i] - x[j];
            const dy = y[i] - y[j];
            const dz = z[i] - z[j];
            const d2 = dx * dx + dy * dy + dz * dz;
            const mag = dt / (d2 * math.sqrt(d2));
            vx[i] -= dx * mass[j] * mag;
            vy[i] -= dy * mass[j] * mag;
            vz[i] -= dz * mass[j] * mag;
            vx[j] += dx * mass[i] * mag;
            vy[j] += dy * mass[i] * mag;
            vz[j] += dz * mass[i] * mag;
        }
    }
    i = 0;
    while (i < bodies) : (i += 1) {
        x[i] += dt * vx[i];
        y[i] += dt * vy[i];
        z[i] += dt * vz[i];
    }
}

fn totalEnergy() f64 {
    var e: f64 = 0.0;
    var i: usize = 0;
    while (i < bodies) : (i += 1) {
        e += 0.5 * mass[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
        var j: usize = i + 1;
        while (j < bodies) : (j += 1) {
            const dx = x[i] - x[j];
            const dy = y[i] - y[j];
            const dz = z[i] - z[j];
            const distance = math.sqrt(dx * dx + dy * dy + dz * dz);
            e -= (mass[i] * mass[j]) / distance;
        }
    }
    return e;
}

pub fn main() void {
    initBodies();
    std.debug.print("{d:.9}\n", .{totalEnergy()});
    var n: usize = 0;
    while (n < 2000000) : (n += 1) {
        advance(0.01);
    }
    std.debug.print("{d:.9}\n", .{totalEnergy()});
}
```

</details>

<details>
<summary>Go — <code>nbody.go</code> (<a href="/benchmarks/nbody/go/nbody.go" target="_blank">raw</a>)</summary>

```go
package main

import (
	"fmt"
	"math"
)

const bodies = 5
const pi = 3.141592653589793
const solarMass = 4.0 * pi * pi
const daysPerYear = 365.24

var x, y, z, vx, vy, vz, mass [bodies]float64

func initBodies() {
	x[0], y[0], z[0], vx[0], vy[0], vz[0], mass[0] = 0, 0, 0, 0, 0, 0, solarMass

	x[1], y[1], z[1] = 4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01
	vx[1], vy[1], vz[1] = 1.66007664274403694e-03*daysPerYear, 7.69901118419740425e-03*daysPerYear, -6.90460016972063023e-05*daysPerYear
	mass[1] = 9.54791938424326609e-04 * solarMass

	x[2], y[2], z[2] = 8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01
	vx[2], vy[2], vz[2] = -2.76742510726862411e-03*daysPerYear, 4.99852801234917238e-03*daysPerYear, 2.30417297573763929e-05*daysPerYear
	mass[2] = 2.85885980666130812e-04 * solarMass

	x[3], y[3], z[3] = 1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01
	vx[3], vy[3], vz[3] = 2.96460137564761618e-03*daysPerYear, 2.37847173959480950e-03*daysPerYear, -2.96589568540237556e-05*daysPerYear
	mass[3] = 4.36624404335156298e-05 * solarMass

	x[4], y[4], z[4] = 1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01
	vx[4], vy[4], vz[4] = 2.68067772490389322e-03*daysPerYear, 1.62824170038242295e-03*daysPerYear, -9.51592254519715870e-05*daysPerYear
	mass[4] = 5.15138902046611451e-05 * solarMass

	var px, py, pz float64
	for i := 0; i < bodies; i++ {
		px += vx[i] * mass[i]
		py += vy[i] * mass[i]
		pz += vz[i] * mass[i]
	}
	vx[0] = -px / solarMass
	vy[0] = -py / solarMass
	vz[0] = -pz / solarMass
}

func advance(dt float64) {
	for i := 0; i < bodies; i++ {
		for j := i + 1; j < bodies; j++ {
			dx := x[i] - x[j]
			dy := y[i] - y[j]
			dz := z[i] - z[j]
			d2 := dx*dx + dy*dy + dz*dz
			mag := dt / (d2 * math.Sqrt(d2))
			vx[i] -= dx * mass[j] * mag
			vy[i] -= dy * mass[j] * mag
			vz[i] -= dz * mass[j] * mag
			vx[j] += dx * mass[i] * mag
			vy[j] += dy * mass[i] * mag
			vz[j] += dz * mass[i] * mag
		}
	}
	for i := 0; i < bodies; i++ {
		x[i] += dt * vx[i]
		y[i] += dt * vy[i]
		z[i] += dt * vz[i]
	}
}

func energy() float64 {
	e := 0.0
	for i := 0; i < bodies; i++ {
		e += 0.5 * mass[i] * (vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i])
		for j := i + 1; j < bodies; j++ {
			dx := x[i] - x[j]
			dy := y[i] - y[j]
			dz := z[i] - z[j]
			distance := math.Sqrt(dx*dx + dy*dy + dz*dz)
			e -= (mass[i] * mass[j]) / distance
		}
	}
	return e
}

func main() {
	initBodies()
	fmt.Printf("%.9f\n", energy())
	for n := 0; n < 2000000; n++ {
		advance(0.01)
	}
	fmt.Printf("%.9f\n", energy())
}
```

</details>

<details>
<summary>Python — <code>nbody.py</code> (<a href="/benchmarks/nbody/python/nbody.py" target="_blank">raw</a>)</summary>

```python
import math

BODIES = 5
PI = 3.141592653589793
SOLAR_MASS = 4.0 * PI * PI
DAYS_PER_YEAR = 365.24

x = [0.0]*BODIES; y = [0.0]*BODIES; z = [0.0]*BODIES
vx = [0.0]*BODIES; vy = [0.0]*BODIES; vz = [0.0]*BODIES
mass = [0.0]*BODIES

def init_bodies():
    mass[0] = SOLAR_MASS

    x[1]=4.84143144246472090e+00; y[1]=-1.16032004402742839e+00; z[1]=-1.03622044471123109e-01
    vx[1]=1.66007664274403694e-03*DAYS_PER_YEAR; vy[1]=7.69901118419740425e-03*DAYS_PER_YEAR; vz[1]=-6.90460016972063023e-05*DAYS_PER_YEAR
    mass[1]=9.54791938424326609e-04*SOLAR_MASS

    x[2]=8.34336671824457987e+00; y[2]=4.12479856412430479e+00; z[2]=-4.03523417114321381e-01
    vx[2]=-2.76742510726862411e-03*DAYS_PER_YEAR; vy[2]=4.99852801234917238e-03*DAYS_PER_YEAR; vz[2]=2.30417297573763929e-05*DAYS_PER_YEAR
    mass[2]=2.85885980666130812e-04*SOLAR_MASS

    x[3]=1.28943695621391310e+01; y[3]=-1.51111514016986312e+01; z[3]=-2.23307578892655734e-01
    vx[3]=2.96460137564761618e-03*DAYS_PER_YEAR; vy[3]=2.37847173959480950e-03*DAYS_PER_YEAR; vz[3]=-2.96589568540237556e-05*DAYS_PER_YEAR
    mass[3]=4.36624404335156298e-05*SOLAR_MASS

    x[4]=1.53796971148509165e+01; y[4]=-2.59193146099879641e+01; z[4]=1.79258772950371181e-01
    vx[4]=2.68067772490389322e-03*DAYS_PER_YEAR; vy[4]=1.62824170038242295e-03*DAYS_PER_YEAR; vz[4]=-9.51592254519715870e-05*DAYS_PER_YEAR
    mass[4]=5.15138902046611451e-05*SOLAR_MASS

    px=py=pz=0.0
    for i in range(BODIES):
        px += vx[i]*mass[i]; py += vy[i]*mass[i]; pz += vz[i]*mass[i]
    vx[0] = -px/SOLAR_MASS; vy[0] = -py/SOLAR_MASS; vz[0] = -pz/SOLAR_MASS

def advance(dt):
    for i in range(BODIES):
        for j in range(i+1, BODIES):
            dx = x[i]-x[j]; dy = y[i]-y[j]; dz = z[i]-z[j]
            d2 = dx*dx + dy*dy + dz*dz
            mag = dt / (d2 * math.sqrt(d2))
            vx[i] -= dx*mass[j]*mag; vy[i] -= dy*mass[j]*mag; vz[i] -= dz*mass[j]*mag
            vx[j] += dx*mass[i]*mag; vy[j] += dy*mass[i]*mag; vz[j] += dz*mass[i]*mag
    for i in range(BODIES):
        x[i] += dt*vx[i]; y[i] += dt*vy[i]; z[i] += dt*vz[i]

def energy():
    e = 0.0
    for i in range(BODIES):
        e += 0.5*mass[i]*(vx[i]*vx[i]+vy[i]*vy[i]+vz[i]*vz[i])
        for j in range(i+1, BODIES):
            dx = x[i]-x[j]; dy = y[i]-y[j]; dz = z[i]-z[j]
            distance = math.sqrt(dx*dx+dy*dy+dz*dz)
            e -= (mass[i]*mass[j])/distance
    return e

init_bodies()
print(f"{energy():.9f}")
for _ in range(2000000):
    advance(0.01)
print(f"{energy():.9f}")
```

</details>

</details>

### binary-trees — allocation/deallocation stress

Builds and discards millions of small binary trees (max depth 18) — this is the one that actually exercises each language's memory management strategy rather than its arithmetic, and it's where the results get interesting.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 2.48s | 33.5 MB | 0.11s |
| C | **1.65s (fastest)** | **17.4 MB (leanest)** | 0.11s |
| C++ | 1.88s | **17.4 MB (leanest)** | 0.09s |
| Rust | 3.13s | 17.6 MB | 0.13s |
| Zig | 4.80s | 17.9 MB | 1.27s |
| Go | 2.55s | 41.3 MB | 0.08s |
| Python | 21.15s | 86.9 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 1.91s | 33.5 MB | 0.13s |
| C | 1.53s | **17.4 MB (leanest)** | 0.10s |
| C++ | 1.68s | **17.4 MB (leanest)** | 0.09s |
| Rust | 1.77s | 17.6 MB | 0.14s |
| Zig | 1.55s | 17.6 MB | 5.28s |
| Go | **1.22s (fastest)** | 39.6 MB | 0.09s |
| Python | 21.15s | 86.9 MB | N/A (interpreted) |

::: tip A real, verified finding: SafeC uses ~2× the memory C does here
SafeC's `std::alloc`/`std::dealloc` prefix every heap block with a 16-byte
live/freed tag (for the compile-time and runtime double-free/UAF detection
described in [Formal Safety Model](/advanced/safety-model)) and defer the
real `free()` of a block for up to 64 further `dealloc()` calls (a
quarantine ring, so a same-pointer double-free stays reliably detectable
even though the platform allocator overwrites freed memory almost
immediately). For a workload that's almost nothing *but* alloc/dealloc
churn, both of those costs show up directly in peak RSS. This is the
actual, measured cost of that safety net on this workload — not a
one-off regression, and not something `-O2` erases.
:::

<details>
<summary>Show binary-trees source (all languages)</summary>

<details>
<summary>SafeC — <code>binarytrees.sc</code> (<a href="/benchmarks/binarytrees/safec/binarytrees.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);
#include <std/mem.sc>

struct Node {
    ?&heap Node left;
    ?&heap Node right;
};

&heap Node make_tree(int depth) {
    &heap Node n = new Node;
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        unsafe { n->left = (?&heap Node)(struct Node*)0; n->right = (?&heap Node)(struct Node*)0; }
    }
    return n;
}

int checksum(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left == (struct Node*)0) { return 1; }
        return 1 + checksum((&heap Node)raw->left) + checksum((&heap Node)raw->right);
    }
    return 0;
}

void free_tree(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left != (struct Node*)0) {
            free_tree((&heap Node)raw->left);
            free_tree((&heap Node)raw->right);
        }
    }
    std::dealloc(n);
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    &heap Node stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    &heap Node longLivedTree = make_tree(maxDepth);

    int depth = minDepth;
    while (depth <= maxDepth) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int check = 0;
        int i = 0;
        while (i < iterations) {
            &heap Node t = make_tree(depth);
            check = check + checksum(t);
            free_tree(t);
            i = i + 1;
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>C — <code>binarytrees.c</code> (<a href="/benchmarks/binarytrees/c/binarytrees.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
#include <stdlib.h>

typedef struct Node {
    struct Node* left;
    struct Node* right;
} Node;

Node* make_tree(int depth) {
    Node* n = (Node*)malloc(sizeof(Node));
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = NULL;
        n->right = NULL;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == NULL) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != NULL) {
        free_tree(n->left);
        free_tree(n->right);
    }
    free(n);
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int check = 0;
        for (int i = 0; i < iterations; i++) {
            Node* t = make_tree(depth);
            check += checksum(t);
            free_tree(t);
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>binarytrees.cpp</code> (<a href="/benchmarks/binarytrees/cpp/binarytrees.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>

struct Node {
    Node* left;
    Node* right;
};

Node* make_tree(int depth) {
    Node* n = new Node();
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = nullptr;
        n->right = nullptr;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == nullptr) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != nullptr) {
        free_tree(n->left);
        free_tree(n->right);
    }
    delete n;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    std::printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int check = 0;
        for (int i = 0; i < iterations; i++) {
            Node* t = make_tree(depth);
            check += checksum(t);
            free_tree(t);
        }
        std::printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    std::printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>Rust — <code>binarytrees.rs</code> (<a href="/benchmarks/binarytrees/rust/binarytrees.rs" target="_blank">raw</a>)</summary>

```rust
struct Node {
    left: Option<Box<Node>>,
    right: Option<Box<Node>>,
}

fn make_tree(depth: i32) -> Box<Node> {
    if depth > 0 {
        Box::new(Node {
            left: Some(make_tree(depth - 1)),
            right: Some(make_tree(depth - 1)),
        })
    } else {
        Box::new(Node { left: None, right: None })
    }
}

fn checksum(n: &Node) -> i32 {
    match (&n.left, &n.right) {
        (Some(l), Some(r)) => 1 + checksum(l) + checksum(r),
        _ => 1,
    }
}

fn main() {
    let min_depth = 4;
    let max_depth = 18;

    let stretch_depth = max_depth + 1;
    let stretch_tree = make_tree(stretch_depth);
    println!("stretch tree of depth {} check: {}", stretch_depth, checksum(&stretch_tree));
    drop(stretch_tree);

    let long_lived_tree = make_tree(max_depth);

    let mut depth = min_depth;
    while depth <= max_depth {
        let iterations = 1i32 << (max_depth - depth + min_depth);
        let mut check = 0;
        for _ in 0..iterations {
            let t = make_tree(depth);
            check += checksum(&t);
        }
        println!("{} trees of depth {} check: {}", iterations, depth, check);
        depth += 2;
    }

    println!("long lived tree of depth {} check: {}", max_depth, checksum(&long_lived_tree));
}
```

</details>

<details>
<summary>Zig — <code>binarytrees.zig</code> (<a href="/benchmarks/binarytrees/zig/binarytrees.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");

const Node = struct {
    left: ?*Node,
    right: ?*Node,
};

fn makeTree(allocator: std.mem.Allocator, depth: i32) !*Node {
    const n = try allocator.create(Node);
    if (depth > 0) {
        n.left = try makeTree(allocator, depth - 1);
        n.right = try makeTree(allocator, depth - 1);
    } else {
        n.left = null;
        n.right = null;
    }
    return n;
}

fn checksum(n: *Node) i32 {
    if (n.left == null) return 1;
    return 1 + checksum(n.left.?) + checksum(n.right.?);
}

fn freeTree(allocator: std.mem.Allocator, n: *Node) void {
    if (n.left != null) {
        freeTree(allocator, n.left.?);
        freeTree(allocator, n.right.?);
    }
    allocator.destroy(n);
}

pub fn main() !void {
    
    const allocator = std.heap.c_allocator;

    const min_depth: i32 = 4;
    const max_depth: i32 = 18;

    const stretch_depth = max_depth + 1;
    const stretch_tree = try makeTree(allocator, stretch_depth);
    std.debug.print("stretch tree of depth {d} check: {d}\n", .{ stretch_depth, checksum(stretch_tree) });
    freeTree(allocator, stretch_tree);

    const long_lived_tree = try makeTree(allocator, max_depth);

    var depth: i32 = min_depth;
    while (depth <= max_depth) : (depth += 2) {
        const iterations = @as(i32, 1) << @as(u5, @intCast(max_depth - depth + min_depth));
        var check: i32 = 0;
        var i: i32 = 0;
        while (i < iterations) : (i += 1) {
            const t = try makeTree(allocator, depth);
            check += checksum(t);
            freeTree(allocator, t);
        }
        std.debug.print("{d} trees of depth {d} check: {d}\n", .{ iterations, depth, check });
    }

    std.debug.print("long lived tree of depth {d} check: {d}\n", .{ max_depth, checksum(long_lived_tree) });
    freeTree(allocator, long_lived_tree);
}
```

</details>

<details>
<summary>Go — <code>binarytrees.go</code> (<a href="/benchmarks/binarytrees/go/binarytrees.go" target="_blank">raw</a>)</summary>

```go
package main

import "fmt"

type Node struct {
	left, right *Node
}

func makeTree(depth int) *Node {
	n := &Node{}
	if depth > 0 {
		n.left = makeTree(depth - 1)
		n.right = makeTree(depth - 1)
	}
	return n
}

func checksum(n *Node) int {
	if n.left == nil {
		return 1
	}
	return 1 + checksum(n.left) + checksum(n.right)
}

func main() {
	minDepth := 4
	maxDepth := 18

	stretchDepth := maxDepth + 1
	stretchTree := makeTree(stretchDepth)
	fmt.Printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree))
	stretchTree = nil

	longLivedTree := makeTree(maxDepth)

	for depth := minDepth; depth <= maxDepth; depth += 2 {
		iterations := 1 << (maxDepth - depth + minDepth)
		check := 0
		for i := 0; i < iterations; i++ {
			t := makeTree(depth)
			check += checksum(t)
		}
		fmt.Printf("%d trees of depth %d check: %d\n", iterations, depth, check)
	}

	fmt.Printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree))
}
```

</details>

<details>
<summary>Python — <code>binarytrees.py</code> (<a href="/benchmarks/binarytrees/python/binarytrees.py" target="_blank">raw</a>)</summary>

```python
class Node:
    __slots__ = ("left", "right")
    def __init__(self, left, right):
        self.left = left
        self.right = right

def make_tree(depth):
    if depth > 0:
        return Node(make_tree(depth - 1), make_tree(depth - 1))
    return Node(None, None)

def checksum(n):
    if n.left is None:
        return 1
    return 1 + checksum(n.left) + checksum(n.right)

min_depth = 4
max_depth = 18

stretch_depth = max_depth + 1
stretch_tree = make_tree(stretch_depth)
print(f"stretch tree of depth {stretch_depth} check: {checksum(stretch_tree)}")
stretch_tree = None

long_lived_tree = make_tree(max_depth)

depth = min_depth
while depth <= max_depth:
    iterations = 1 << (max_depth - depth + min_depth)
    check = 0
    for _ in range(iterations):
        t = make_tree(depth)
        check += checksum(t)
    print(f"{iterations} trees of depth {depth} check: {check}")
    depth += 2

print(f"long lived tree of depth {max_depth} check: {checksum(long_lived_tree)}")
```

</details>

</details>

## Multithreaded — binary-trees, 8 threads

Same binary-trees workload, parallelized across 8 worker threads (this machine has 10 cores) — each thread builds/checksums/frees an independent slice of the tree count at a given depth, joined before moving to the next depth. Release builds only.

| Language | 8-thread time | Peak memory | vs. single-thread |
|---|---|---|---|
| SafeC | 0.64s | 146.4 MB | 2.98× |
| C | 0.58s | **73.5 MB (leanest)** | 2.64× |
| C++ | 0.55s | 105.5 MB | 3.05× |
| Rust | 0.66s | 85.0 MB | 2.68× |
| Zig | 0.53s | 98.9 MB | 2.92× |
| Go | **0.41s (fastest)** | 127.2 MB | 2.98× |
| Python | 23.78s | 346.2 MB | 0.89× (slower than 1 thread) |

<details>
<summary>Show multithreaded binary-trees source (all languages)</summary>

<details>
<summary>SafeC — <code>binarytrees_mt.sc</code> (<a href="/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);
#include <std/mem.sc>
#include <std/thread.sc>

#define NTHREADS 8

struct Node {
    ?&heap Node left;
    ?&heap Node right;
};

&heap Node make_tree(int depth) {
    &heap Node n = new Node;
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        unsafe { n->left = (?&heap Node)(struct Node*)0; n->right = (?&heap Node)(struct Node*)0; }
    }
    return n;
}

int checksum(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left == (struct Node*)0) { return 1; }
        return 1 + checksum((&heap Node)raw->left) + checksum((&heap Node)raw->right);
    }
    return 0;
}

void free_tree(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left != (struct Node*)0) {
            free_tree((&heap Node)raw->left);
            free_tree((&heap Node)raw->right);
        }
    }
    std::dealloc(n);
}

struct WorkSlice {
    int start;
    int end;
    int depth;
    int result;
};

struct WorkSlice slices[NTHREADS];

void* worker(void* arg) {
    unsafe {
        struct WorkSlice* s = (struct WorkSlice*)arg;
        int check = 0;
        int i = s->start;
        while (i < s->end) {
            &heap Node t = make_tree(s->depth);
            check = check + checksum(t);
            free_tree(t);
            i = i + 1;
        }
        s->result = check;
    }
    return (void*)0;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    &heap Node stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    &heap Node longLivedTree = make_tree(maxDepth);

    int depth = minDepth;
    while (depth <= maxDepth) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) { perThread = 1; }

        unsigned long long tids[NTHREADS];
        int t = 0;
        int nSpawned = 0;
        int start = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) { end = iterations; }
            unsafe {
                slices[t].start = start;
                slices[t].end = end;
                slices[t].depth = depth;
                std::thread_create(&tids[t], (void*)worker, (void*)&slices[t]);
            }
            nSpawned = nSpawned + 1;
            start = end;
            t = t + 1;
        }
        int check = 0;
        int j = 0;
        while (j < nSpawned) {
            unsafe { std::thread_join(tids[j]); }
            check = check + slices[j].result;
            j = j + 1;
        }

        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>C — <code>binarytrees_mt.c</code> (<a href="/benchmarks/binarytrees_mt/c/binarytrees_mt.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

#define NTHREADS 8

typedef struct Node {
    struct Node* left;
    struct Node* right;
} Node;

Node* make_tree(int depth) {
    Node* n = (Node*)malloc(sizeof(Node));
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = NULL;
        n->right = NULL;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == NULL) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != NULL) {
        free_tree(n->left);
        free_tree(n->right);
    }
    free(n);
}

typedef struct { int start, end, depth, result; } WorkSlice;

void* worker(void* arg) {
    WorkSlice* s = (WorkSlice*)arg;
    int check = 0;
    for (int i = s->start; i < s->end; i++) {
        Node* t = make_tree(s->depth);
        check += checksum(t);
        free_tree(t);
    }
    s->result = check;
    return NULL;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) perThread = 1;

        pthread_t tids[NTHREADS];
        WorkSlice slices[NTHREADS];
        int nSpawned = 0, start = 0, t = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) end = iterations;
            slices[t].start = start; slices[t].end = end; slices[t].depth = depth;
            pthread_create(&tids[t], NULL, worker, &slices[t]);
            nSpawned++;
            start = end;
            t++;
        }
        int check = 0;
        for (int j = 0; j < nSpawned; j++) {
            pthread_join(tids[j], NULL);
            check += slices[j].result;
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>binarytrees_mt.cpp</code> (<a href="/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>
#include <thread>
#include <vector>

#define NTHREADS 8

struct Node {
    Node* left;
    Node* right;
};

Node* make_tree(int depth) {
    Node* n = new Node();
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = nullptr;
        n->right = nullptr;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == nullptr) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != nullptr) {
        free_tree(n->left);
        free_tree(n->right);
    }
    delete n;
}

void worker(int start, int end, int depth, int* result) {
    int check = 0;
    for (int i = start; i < end; i++) {
        Node* t = make_tree(depth);
        check += checksum(t);
        free_tree(t);
    }
    *result = check;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    std::printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) perThread = 1;

        std::vector<std::thread> threads;
        int results[NTHREADS] = {0};
        int nSpawned = 0, start = 0, t = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) end = iterations;
            threads.emplace_back(worker, start, end, depth, &results[t]);
            nSpawned++;
            start = end;
            t++;
        }
        int check = 0;
        for (int j = 0; j < nSpawned; j++) {
            threads[j].join();
            check += results[j];
        }
        std::printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    std::printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
```

</details>

<details>
<summary>Rust — <code>binarytrees_mt.rs</code> (<a href="/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs" target="_blank">raw</a>)</summary>

```rust
use std::thread;

const NTHREADS: i32 = 8;

struct Node {
    left: Option<Box<Node>>,
    right: Option<Box<Node>>,
}

fn make_tree(depth: i32) -> Box<Node> {
    if depth > 0 {
        Box::new(Node {
            left: Some(make_tree(depth - 1)),
            right: Some(make_tree(depth - 1)),
        })
    } else {
        Box::new(Node { left: None, right: None })
    }
}

fn checksum(n: &Node) -> i32 {
    match (&n.left, &n.right) {
        (Some(l), Some(r)) => 1 + checksum(l) + checksum(r),
        _ => 1,
    }
}

fn main() {
    let min_depth = 4;
    let max_depth = 18;

    let stretch_depth = max_depth + 1;
    let stretch_tree = make_tree(stretch_depth);
    println!("stretch tree of depth {} check: {}", stretch_depth, checksum(&stretch_tree));
    drop(stretch_tree);

    let long_lived_tree = make_tree(max_depth);

    let mut depth = min_depth;
    while depth <= max_depth {
        let iterations = 1i32 << (max_depth - depth + min_depth);
        let mut per_thread = iterations / NTHREADS;
        if per_thread < 1 { per_thread = 1; }

        let mut handles = Vec::new();
        let mut start = 0;
        let mut n_spawned = 0;
        let mut t = 0;
        while t < NTHREADS && start < iterations {
            let mut end = start + per_thread;
            if t == NTHREADS - 1 || end > iterations { end = iterations; }
            let s = start;
            let e = end;
            handles.push(thread::spawn(move || {
                let mut check = 0;
                for _ in s..e {
                    let tree = make_tree(depth);
                    check += checksum(&tree);
                }
                check
            }));
            n_spawned += 1;
            start = end;
            t += 1;
        }
        let mut check = 0;
        for _ in 0..n_spawned {
            check += handles.remove(0).join().unwrap();
        }
        println!("{} trees of depth {} check: {}", iterations, depth, check);
        depth += 2;
    }

    println!("long lived tree of depth {} check: {}", max_depth, checksum(&long_lived_tree));
}
```

</details>

<details>
<summary>Zig — <code>binarytrees_mt.zig</code> (<a href="/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");

const nthreads = 8;

const Node = struct {
    left: ?*Node,
    right: ?*Node,
};

fn makeTree(allocator: std.mem.Allocator, depth: i32) !*Node {
    const n = try allocator.create(Node);
    if (depth > 0) {
        n.left = try makeTree(allocator, depth - 1);
        n.right = try makeTree(allocator, depth - 1);
    } else {
        n.left = null;
        n.right = null;
    }
    return n;
}

fn checksum(n: *Node) i32 {
    if (n.left == null) return 1;
    return 1 + checksum(n.left.?) + checksum(n.right.?);
}

fn freeTree(allocator: std.mem.Allocator, n: *Node) void {
    if (n.left != null) {
        freeTree(allocator, n.left.?);
        freeTree(allocator, n.right.?);
    }
    allocator.destroy(n);
}

const WorkSlice = struct { start: i32, end: i32, depth: i32, result: i32 };

fn worker(allocator: std.mem.Allocator, s: *WorkSlice) void {
    var check: i32 = 0;
    var i: i32 = s.start;
    while (i < s.end) : (i += 1) {
        const t = makeTree(allocator, s.depth) catch unreachable;
        check += checksum(t);
        freeTree(allocator, t);
    }
    s.result = check;
}

pub fn main() !void {
    const allocator = std.heap.c_allocator;

    const min_depth: i32 = 4;
    const max_depth: i32 = 18;

    const stretch_depth = max_depth + 1;
    const stretch_tree = try makeTree(allocator, stretch_depth);
    std.debug.print("stretch tree of depth {d} check: {d}\n", .{ stretch_depth, checksum(stretch_tree) });
    freeTree(allocator, stretch_tree);

    const long_lived_tree = try makeTree(allocator, max_depth);

    var depth: i32 = min_depth;
    while (depth <= max_depth) : (depth += 2) {
        const iterations = @as(i32, 1) << @as(u5, @intCast(max_depth - depth + min_depth));
        var per_thread = @divTrunc(iterations, nthreads);
        if (per_thread < 1) per_thread = 1;

        var threads: [nthreads]?std.Thread = [_]?std.Thread{null} ** nthreads;
        var slices: [nthreads]WorkSlice = undefined;
        var n_spawned: usize = 0;
        var start: i32 = 0;
        var t: usize = 0;
        while (t < nthreads and start < iterations) : (t += 1) {
            var end = start + per_thread;
            if (t == nthreads - 1 or end > iterations) end = iterations;
            slices[t] = WorkSlice{ .start = start, .end = end, .depth = depth, .result = 0 };
            threads[t] = try std.Thread.spawn(.{}, worker, .{ allocator, &slices[t] });
            n_spawned += 1;
            start = end;
        }
        var check: i32 = 0;
        var j: usize = 0;
        while (j < n_spawned) : (j += 1) {
            threads[j].?.join();
            check += slices[j].result;
        }
        std.debug.print("{d} trees of depth {d} check: {d}\n", .{ iterations, depth, check });
    }

    std.debug.print("long lived tree of depth {d} check: {d}\n", .{ max_depth, checksum(long_lived_tree) });
    freeTree(allocator, long_lived_tree);
}
```

</details>

<details>
<summary>Go — <code>binarytrees_mt.go</code> (<a href="/benchmarks/binarytrees_mt/go/binarytrees_mt.go" target="_blank">raw</a>)</summary>

```go
package main

import (
	"fmt"
	"sync"
)

const nthreads = 8

type Node struct {
	left, right *Node
}

func makeTree(depth int) *Node {
	n := &Node{}
	if depth > 0 {
		n.left = makeTree(depth - 1)
		n.right = makeTree(depth - 1)
	}
	return n
}

func checksum(n *Node) int {
	if n.left == nil {
		return 1
	}
	return 1 + checksum(n.left) + checksum(n.right)
}

func main() {
	minDepth := 4
	maxDepth := 18

	stretchDepth := maxDepth + 1
	stretchTree := makeTree(stretchDepth)
	fmt.Printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree))
	stretchTree = nil

	longLivedTree := makeTree(maxDepth)

	for depth := minDepth; depth <= maxDepth; depth += 2 {
		iterations := 1 << (maxDepth - depth + minDepth)
		perThread := iterations / nthreads
		if perThread < 1 {
			perThread = 1
		}

		var wg sync.WaitGroup
		results := make([]int, nthreads)
		nSpawned := 0
		start := 0
		for t := 0; t < nthreads && start < iterations; t++ {
			end := start + perThread
			if t == nthreads-1 || end > iterations {
				end = iterations
			}
			wg.Add(1)
			go func(start, end, depth, slot int) {
				defer wg.Done()
				check := 0
				for i := start; i < end; i++ {
					t := makeTree(depth)
					check += checksum(t)
				}
				results[slot] = check
			}(start, end, depth, t)
			nSpawned++
			start = end
		}
		wg.Wait()
		check := 0
		for j := 0; j < nSpawned; j++ {
			check += results[j]
		}
		fmt.Printf("%d trees of depth %d check: %d\n", iterations, depth, check)
	}

	fmt.Printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree))
}
```

</details>

<details>
<summary>Python — <code>binarytrees_mt.py</code> (<a href="/benchmarks/binarytrees_mt/python/binarytrees_mt.py" target="_blank">raw</a>)</summary>

```python
import threading

NTHREADS = 8

class Node:
    __slots__ = ("left", "right")
    def __init__(self, left, right):
        self.left = left
        self.right = right

def make_tree(depth):
    if depth > 0:
        return Node(make_tree(depth - 1), make_tree(depth - 1))
    return Node(None, None)

def checksum(n):
    if n.left is None:
        return 1
    return 1 + checksum(n.left) + checksum(n.right)

def worker(start, end, depth, results, slot):
    check = 0
    for _ in range(start, end):
        t = make_tree(depth)
        check += checksum(t)
    results[slot] = check

min_depth = 4
max_depth = 18

stretch_depth = max_depth + 1
stretch_tree = make_tree(stretch_depth)
print(f"stretch tree of depth {stretch_depth} check: {checksum(stretch_tree)}")
stretch_tree = None

long_lived_tree = make_tree(max_depth)

depth = min_depth
while depth <= max_depth:
    iterations = 1 << (max_depth - depth + min_depth)
    per_thread = max(1, iterations // NTHREADS)

    threads = []
    results = [0] * NTHREADS
    n_spawned = 0
    start = 0
    t = 0
    while t < NTHREADS and start < iterations:
        end = start + per_thread
        if t == NTHREADS - 1 or end > iterations:
            end = iterations
        th = threading.Thread(target=worker, args=(start, end, depth, results, t))
        th.start()
        threads.append(th)
        n_spawned += 1
        start = end
        t += 1
    check = 0
    for j in range(n_spawned):
        threads[j].join()
        check += results[j]
    print(f"{iterations} trees of depth {depth} check: {check}")
    depth += 2

print(f"long lived tree of depth {max_depth} check: {checksum(long_lived_tree)}")
```

</details>

</details>

## SIMD — sum of squares over 20,000,000 f64 values

A large-array reduction (`sum(a[i]*a[i])`), comparing a plain scalar loop against each language's explicit vector type at the same optimization level (`-O2`/release) — so this isolates what writing explicit SIMD buys *on top of* whatever the backend's ordinary auto-vectorizer already does to the scalar loop, not "vectorized vs. deliberately crippled." SafeC uses its native `vec<double,4>` type; C/C++ use GCC/Clang vector extensions; Zig uses `@Vector`; Rust uses stable-channel AArch64 NEON intrinsics (`std::simd`/`portable_simd` needs Rust nightly, not used here). Go has no portable SIMD type in its standard toolchain, so only a scalar number exists for it. Python's plain loop is included for scale, alongside NumPy — the realistic way anyone actually gets vectorized numeric performance in Python.

| Language | Scalar time | Explicit-SIMD time | Speedup |
|---|---|---|---|
| SafeC | 0.04s | **0.03s (fastest)** | 1.33× |
| C | 0.04s | **0.03s (fastest)** | 1.33× |
| C++ | 0.04s | **0.03s (fastest)** | 1.33× |
| Rust | 0.04s | **0.03s (fastest)** | 1.33× |
| Zig | 0.04s | **0.03s (fastest)** | 1.33× |
| Go | 0.06s | N/A | — |
| Python | 3.50s | N/A | — |
| Python + NumPy | — | 0.15s | 23.33× |

<details>
<summary>Show SIMD source (all languages)</summary>

<details>
<summary>SafeC — <code>simd_scalar.sc</code> (<a href="/benchmarks/simd/safec/simd_scalar.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);
#define N 20000000

double a[N];

int main() {
    int i = 0;
    while (i < N) {
        a[i] = (double)(i % 1000) * 0.001;
        i = i + 1;
    }
    double sum = 0.0;
    i = 0;
    while (i < N) {
        sum = sum + a[i] * a[i];
        i = i + 1;
    }
    printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>SafeC — <code>simd_vec.sc</code> (<a href="/benchmarks/simd/safec/simd_vec.sc" target="_blank">raw</a>)</summary>

```c
extern int printf(const char* fmt, ...);
#define N 20000000

double a[N];

int main() {
    int i = 0;
    while (i < N) {
        a[i] = (double)(i % 1000) * 0.001;
        i = i + 1;
    }
    vec<double, 4> acc = {0.0, 0.0, 0.0, 0.0};
    i = 0;
    int limit = (N / 4) * 4;
    while (i < limit) {
        vec<double, 4> v;
        v[0] = a[i]; v[1] = a[i+1]; v[2] = a[i+2]; v[3] = a[i+3];
        acc = acc + v * v;
        i = i + 4;
    }
    double sum = acc[0] + acc[1] + acc[2] + acc[3];
    while (i < N) {
        sum = sum + a[i] * a[i];
        i = i + 1;
    }
    printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>C — <code>simd_scalar.c</code> (<a href="/benchmarks/simd/c/simd_scalar.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
#define N 20000000
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    double sum = 0.0;
    for (int i = 0; i < N; i++) sum += a[i] * a[i];
    printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>C — <code>simd_vec.c</code> (<a href="/benchmarks/simd/c/simd_vec.c" target="_blank">raw</a>)</summary>

```c
#include <stdio.h>
#define N 20000000
typedef double v4d __attribute__((vector_size(32)));
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    v4d acc = {0.0, 0.0, 0.0, 0.0};
    int limit = (N / 4) * 4;
    int i = 0;
    for (; i < limit; i += 4) {
        v4d v = { a[i], a[i+1], a[i+2], a[i+3] };
        acc += v * v;
    }
    double sum = acc[0] + acc[1] + acc[2] + acc[3];
    for (; i < N; i++) sum += a[i] * a[i];
    printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>simd_scalar.cpp</code> (<a href="/benchmarks/simd/cpp/simd_scalar.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>
#define N 20000000
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    double sum = 0.0;
    for (int i = 0; i < N; i++) sum += a[i] * a[i];
    std::printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>C++ — <code>simd_vec.cpp</code> (<a href="/benchmarks/simd/cpp/simd_vec.cpp" target="_blank">raw</a>)</summary>

```cpp
#include <cstdio>
#define N 20000000
typedef double v4d __attribute__((vector_size(32)));
double a[N];
int main() {
    for (int i = 0; i < N; i++) a[i] = (double)(i % 1000) * 0.001;
    v4d acc = {0.0, 0.0, 0.0, 0.0};
    int limit = (N / 4) * 4;
    int i = 0;
    for (; i < limit; i += 4) {
        v4d v = { a[i], a[i+1], a[i+2], a[i+3] };
        acc += v * v;
    }
    double sum = acc[0] + acc[1] + acc[2] + acc[3];
    for (; i < N; i++) sum += a[i] * a[i];
    std::printf("%.6f\n", sum);
    return 0;
}
```

</details>

<details>
<summary>Rust — <code>simd_scalar.rs</code> (<a href="/benchmarks/simd/rust/simd_scalar.rs" target="_blank">raw</a>)</summary>

```rust
const N: usize = 20_000_000;
fn main() {
    let mut a = vec![0.0f64; N];
    for i in 0..N {
        a[i] = (i % 1000) as f64 * 0.001;
    }
    let mut sum = 0.0f64;
    for i in 0..N {
        sum += a[i] * a[i];
    }
    println!("{:.6}", sum);
}
```

</details>

<details>
<summary>Rust — <code>simd_vec.rs</code> (<a href="/benchmarks/simd/rust/simd_vec.rs" target="_blank">raw</a>)</summary>

```rust
use std::arch::aarch64::*;

const N: usize = 20_000_000;

fn main() {
    let mut a = vec![0.0f64; N];
    for i in 0..N {
        a[i] = (i % 1000) as f64 * 0.001;
    }

    let limit = (N / 2) * 2;
    let mut sum: f64;
    unsafe {
        let mut acc = vdupq_n_f64(0.0);
        let mut i = 0;
        while i < limit {
            let v = vld1q_f64(a.as_ptr().add(i));
            acc = vfmaq_f64(acc, v, v);
            i += 2;
        }
        sum = vaddvq_f64(acc);
        while i < N {
            sum += a[i] * a[i];
            i += 1;
        }
    }
    println!("{:.6}", sum);
}
```

</details>

<details>
<summary>Zig — <code>simd_scalar.zig</code> (<a href="/benchmarks/simd/zig/simd_scalar.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");
const N = 20000000;
var a: [N]f64 = undefined;
pub fn main() void {
    var i: usize = 0;
    while (i < N) : (i += 1) a[i] = @as(f64, @floatFromInt(i % 1000)) * 0.001;
    var sum: f64 = 0.0;
    i = 0;
    while (i < N) : (i += 1) sum += a[i] * a[i];
    std.debug.print("{d:.6}\n", .{sum});
}
```

</details>

<details>
<summary>Zig — <code>simd_vec.zig</code> (<a href="/benchmarks/simd/zig/simd_vec.zig" target="_blank">raw</a>)</summary>

```zig
const std = @import("std");
const N = 20000000;
var a: [N]f64 = undefined;
pub fn main() void {
    var i: usize = 0;
    while (i < N) : (i += 1) a[i] = @as(f64, @floatFromInt(i % 1000)) * 0.001;
    var acc: @Vector(4, f64) = @splat(0.0);
    const limit = (N / 4) * 4;
    i = 0;
    while (i < limit) : (i += 4) {
        const v: @Vector(4, f64) = a[i..][0..4].*;
        acc += v * v;
    }
    var sum: f64 = @reduce(.Add, acc);
    while (i < N) : (i += 1) sum += a[i] * a[i];
    std.debug.print("{d:.6}\n", .{sum});
}
```

</details>

<details>
<summary>Go — <code>simd_scalar.go</code> (<a href="/benchmarks/simd/go/simd_scalar.go" target="_blank">raw</a>)</summary>

```go
package main

import "fmt"

const N = 20000000

var a [N]float64

func main() {
	for i := 0; i < N; i++ {
		a[i] = float64(i%1000) * 0.001
	}
	sum := 0.0
	for i := 0; i < N; i++ {
		sum += a[i] * a[i]
	}
	fmt.Printf("%.6f\n", sum)
}
```

</details>

<details>
<summary>Python — <code>simd_numpy.py</code> (<a href="/benchmarks/simd/python/simd_numpy.py" target="_blank">raw</a>)</summary>

```python
import numpy as np
N = 20_000_000
a = (np.arange(N, dtype=np.int64) % 1000).astype(np.float64) * 0.001
s = np.sum(a * a)
print(f"{s:.6f}")
```

</details>

<details>
<summary>Python — <code>simd_scalar.py</code> (<a href="/benchmarks/simd/python/simd_scalar.py" target="_blank">raw</a>)</summary>

```python
N = 20_000_000
a = [0.0] * N
for i in range(N):
    a[i] = (i % 1000) * 0.001
s = 0.0
for i in range(N):
    s += a[i] * a[i]
print(f"{s:.6f}")
```

</details>

</details>
