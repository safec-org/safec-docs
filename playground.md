---
layout: playground
title: Playground
summary: Edit and share SafeC programs in the browser, with the Book's examples one click away.
code: |
  extern int printf(const char* fmt, ...);

  region Frame { capacity: 4096 }

  struct Particle { float x; float v; };

  // The region is part of the type: p can't outlive the arena.
  void step(&arena<Frame> Particle p, float dt) {
      p.x = p.x + p.v * dt;
  }

  int main() {
      &arena<Frame> Particle p = new<Frame> Particle;
      p.x = 0.0;
      p.v = 2.5;
      for (int i = 0; i < 3; i = i + 1) {
          step(p, 0.5);
          printf("t=%d x=%.2f\n", i, p.x);
      }
      arena_reset<Frame>();   // O(1): no per-object free, no GC
      return 0;
  }
---

## About the playground {#about}

- **Examples** loads any runnable program from [the Book](/book/); every SafeC program elsewhere on this site that the playground can build (one that includes only the C and std headers bundled with its compiler, below) has a *Run in Playground* link that opens it here.
- **Share** puts the program into the page's address (`#code=…`, base64url) and copies the link — nothing is uploaded anywhere.
- **Run** compiles and runs the program entirely in your browser, in a background worker, with a self-hosted SafeC compiler built to WebAssembly. Programs see a `printf`/stdout but no files, network or threads. The compiler is scc, SafeC's compiler written in SafeC ([selfhost/](https://github.com/safec-org/SafeC/tree/main/selfhost)); it accepts the language subset described in its `SUBSET.md` and does not run the ownership and thread-safety checks. Programs can include the C library subsets `stdio.h`, `stdlib.h`, `string.h`, `ctype.h`, `math.h`, `stdbool.h`, `stdint.h`, `stddef.h`, `limits.h`, `time.h` and `assert.h`, and the std headers `std/io.h`, `std/str.h`, `std/mem.h`, `std/fmt.h`, `std/math.h`, `std/convert.h`, `std/panic.h`, `std/result.h`, `std/cmp.h`, `std/traits.h`, `std/bit.h`, `std/atomic.h`, `std/sys.h`, `std/time.h` (UTC only: there is no time zone, and the clock reads the epoch), `std/collections/{slice,vec,string,map}.h` and the small headers they use.
- **What scc leaves out**: inline `asm` and bitfields are rejected; SIMD vector types, `_Generic`, `thread_local`, the atomic built-ins, calling-convention keywords, `naked`, `section`, `interrupt` and `malloc_defer` aren't implemented; a program is one translation unit. `spawn` runs the thread function to completion at once (there are no threads), and pointers are 4 bytes (wasm32), so `sizeof` of a type holding pointers differs from a 64-bit native build.

For the checks scc skips, and for the full std, run programs locally:

```bash norun
safeguard new hello && cd hello
# paste the program into src/main.sc
safeguard run
```
