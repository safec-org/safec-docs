---
layout: home
hero:
  name: SafeC
  text: Safe, Deterministic Systems Programming
  tagline: A region-aware evolution of C with compile-time memory safety, zero hidden costs, and full C ABI compatibility.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Language Reference
      link: /reference/types
    - theme: alt
      text: GitHub
      link: https://github.com/mkaudio-company/SafeC
features:
  - title: Region-Based Memory Safety
    details: Stack, heap, arena, and static regions enforced at compile time. No garbage collector, no runtime overhead.
  - title: Zero Hidden Cost
    details: No implicit allocations, no hidden runtime, no background GC, no implicit exceptions. Every operation has a visible cost.
  - title: C ABI Compatible
    details: C struct layout, C calling conventions, native #include &lt;stdio.h&gt; support. Link SafeC objects into any C project.
  - title: Compile-Time First
    details: consteval functions, static_assert, if const, generics via monomorphization. Everything knowable at compile time is resolved at compile time.
  - title: Bare-Metal Ready
    details: --freestanding mode, naked functions, interrupt handlers, inline assembly, volatile I/O, section placement. Build kernels and firmware.
  - title: Modern Language Features
    details: Generics, struct methods, operator overloading, pattern matching, optional types, slices, defer, and typed channels.
---

## Hello, SafeC

```c
extern int printf(const char* fmt, ...);

region AudioPool { capacity: 65536 }

struct Sample {
    float left;
    float right;
};

int main() {
    // Arena allocation — deterministic, no malloc
    &arena<AudioPool> Sample s = new<AudioPool> Sample;
    s.left = 0.5;
    s.right = -0.3;

    printf("L=%.2f R=%.2f\n", s.left, s.right);
    arena_reset<AudioPool>();
    return 0;
}
```
