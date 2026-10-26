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

- **Examples** loads any runnable program from [the Book](/book/); every SafeC program elsewhere on this site has a *Run in Playground* link that opens it here.
- **Share** puts the program into the page's address (`#code=…`, base64url) and copies the link — nothing is uploaded anywhere.
- **Run** compiles and runs the program entirely in your browser, in a background worker, with a self-hosted SafeC compiler built to WebAssembly. Programs see a `printf`/stdout but no files, network or threads. The compiler is scc, SafeC's compiler written in SafeC ([selfhost/](https://github.com/safec-org/SafeC/tree/main/selfhost)); it accepts the language subset described in its `SUBSET.md` and does not run the ownership and thread-safety checks. For those, and for the full std, run programs locally:

```bash norun
safeguard new hello && cd hello
# paste the program into src/main.sc
safeguard run
```
