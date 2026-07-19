# Chapter 5: Understanding Regions

Every chapter so far has been "C, with a few stricter rules and some
extra keywords." This chapter is where that stops being true. **Regions**
are SafeC's one genuinely new idea — the thing that makes it a different
language from C rather than a linter for one — and everything from here
on assumes you have it.

## The problem regions solve

C gives you total freedom over memory, and total responsibility for using
it correctly. A pointer doesn't carry any information about how long the
memory behind it stays valid — the compiler will happily let you do this:

```c
/* plain C, not SafeC -- this is the bug we're about to prevent */
int *dangling(void) {
    int local = 10;
    return &local;   /* local's stack frame is gone once dangling() returns */
}
```

`local` lives on `dangling`'s stack frame. The moment the function
returns, that frame is gone — but the pointer you returned still points
at where it used to be. Read through it later, and you're reading
whatever now occupies that stack slot: maybe still `10`, maybe garbage
from the next function call, maybe an attacker-controlled value if you're
unlucky. This exact bug class — using memory after whatever owned it
stopped guaranteeing its validity — covers dangling pointers,
use-after-free, and a large fraction of C's historical security
vulnerabilities.

SafeC's answer is to make *where a value's memory lives* part of its
type, checked at compile time, for free at runtime:

```c
&stack int dangling() {
    int local = 10;
    return &local;
}
```

```
error: cannot return '&stack int': stack reference escapes function scope
```

Same bug, same shape — but it doesn't compile. The compiler tracked that
`local` lives in the `dangling` function's stack scope, saw that the
`&stack int` return type promises a caller a reference valid *at least*
through their own use of it, and rejected the mismatch before you ever
ran the program.

## The four regions

Every reference in SafeC carries a **region** — where the data behind it
lives, and therefore how long it stays valid:

| Region | Lifetime | Freed by |
|---|---|---|
| `&stack T` | The enclosing lexical scope | Automatic, at scope exit |
| `&heap T` | Until explicitly freed | You, via `free()` (or `defer free(...)`) |
| `&arena<R> T` | Until the arena `R` is reset or destroyed | Bulk, all at once |
| `&static T` | The entire program | Never |

```c
void print_x(&stack int ref) {
    printf("%d\n", *ref);
}

int main() {
    int v = 7;
    print_x(&v);   // &v is a &stack int -- valid for main()'s whole scope
    return 0;
}
```

`&stack` is the default you reach for most often — it's what an ordinary
local variable's address becomes when you take it with `&`. The escape
rule from the previous section is really just "a `&stack` reference can't
outlive the scope that created it," enforced whether that scope is the
current block, an enclosing one, or (as in `dangling()`) the whole
function.

### `&heap`: your responsibility, your rules

Heap memory works like C: `malloc` it, `free` it, nothing automatic in
between. SafeC's `&heap T` reference type just labels a pointer as
"pointing into heap memory" so the compiler can enforce the region rules
around it (you can't, for instance, quietly assign a `&stack` reference
where a `&heap` one is expected — Chapter 5 has more on that below); it
doesn't manage the memory for you.

```c
void heap_demo() {
    &heap int p = (int*)malloc(sizeof(int));
    *p = 42;
    defer free(p);
    printf("heap: %d\n", *p);
}   // free(p) runs here, via defer
```

`defer` (introduced properly in [Chapter 8](/book/ch08-error-handling))
schedules a statement to run when the enclosing scope exits, regardless
of how it exits — it's the idiomatic way to pair an allocation with its
matching `free` right next to each other in the source, instead of
hoping you remember to add the `free` at every exit path.

### `&static`: lives forever

`&static T` is for data that lives for the whole program — globals, and
references to them:

```c
static int counter = 0;

&static int get_counter() {
    return &counter;    // OK: counter lives forever, so does this reference
}
```

`&static` references are the most permissive of the four: since they
never become invalid, they can be stored anywhere, passed anywhere, and
(as you'll see in [Chapter 4](/book/ch04-functions)'s function-value
syntax) they're what an ordinary function's address is typed as.

### `&arena<R>`: bulk allocation, bulk deallocation

The fourth region is the one C has no real equivalent for. An **arena**
is a region you declare up front, allocate into with bump-pointer
speed (just an offset increment — no per-allocation bookkeeping), and
free either all at once or by explicit checkpoint:

```c
region Pool { capacity: 1024 }

void arena_demo() {
    &arena<Pool> struct Point p = new<Pool> struct Point;
    p->x = 1.0;
    p->y = 2.0;
    printf("arena point: %f %f\n", p->x, p->y);

    // A checkpoint/rewind pair for freeing just the *tail* of an arena's
    // allocations, keeping everything before the checkpoint:
    unsigned long mark = arena_mark<Pool>();
    &arena<Pool> int scratch = new<Pool> int;
    *scratch = 99;
    arena_free_to<Pool>(mark);   // frees 'scratch', keeps 'p'

    arena_reset<Pool>();          // frees everything in Pool at once
}
```

`region Pool { capacity: 1024 }` must be declared at file scope (never
inside a function) — think of it as declaring a named pool of memory the
rest of the program can allocate into. `new<Pool> T` allocates one `T`
from it. `arena_reset<Pool>()` rewinds the whole arena to empty in one
step (no destructors run — arena-allocated values must not hold external
resources that need cleanup); `arena_free_to<Pool>(mark)` is the more
surgical version, rewinding only back to a checkpoint from
`arena_mark<Pool>()`, so a "big table that should outlive this loop
iteration" and "scratch space that shouldn't" can share one arena. And
critically: **the compiler enforces that you can't touch a reference
after its arena has been reset or destroyed out from under it** —

```c
region Pool { capacity: 1024 }
int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;
    return 0;
}
```

```
error: use of 'p' (&arena<Pool> reference) after arena_reset<Pool>(),
       arena_destroy<Pool>(), or arena_free_to<Pool>() invalidated it
```

— the same category of bug `dangling()` demonstrated at the top of this
chapter, caught the same way: at compile time, before it can become a
runtime use-after-free. (This particular check is flow-insensitive — a
running count of resets seen so far in the function, not a full
simulation of every branch and loop — so it can occasionally flag code
that's actually safe at runtime; see [Memory &
Regions](/reference/memory#4-arena-references-die-on-reset) for the exact
shape of that limitation and the `unsafe` workaround when you're sure a
flagged reference really is still valid.)

## Aliasing: one writer, or many readers, never both

Region tracking answers "how long does this live." A separate rule
answers a different question: "who else can see this memory *right
now*." SafeC enforces the same discipline Rust calls the borrow checker,
stated simply: **at any point in the program, a value has either exactly
one mutable reference, or any number of immutable ones — never a mix.**

```c
int x = 42;
&stack const int a = &x;   // immutable borrow
&stack const int b = &x;   // OK: multiple immutable borrows can coexist
printf("borrows: %d %d\n", *a, *b);
```

Two immutable (`const`) borrows of the same variable are fine — neither
can write through it, so there's nothing for them to conflict over. But a
*mutable* borrow demands exclusivity:

```c
int x = 42;
&stack int a = &x;    // mutable borrow
&stack int b = &x;    // ERROR: x is already mutably borrowed
```

```
error: cannot borrow 'x' as mutable: already borrowed as mutable
```

Without this rule, `a` could write to `x` while something is reading
through `b`, and the reader would see a value change out from under it
mid-read — a data race even in single-threaded code, and the root cause
of an entire category of "it usually works but sometimes doesn't"
C bugs. SafeC's aliasing check turns that into a compile-time error
instead of a Heisenbug.

::: tip One syntax trap worth knowing about early
Notice `&stack const int` — `const` written *after* the region qualifier.
Writing it before (`const &stack int`) is currently accepted by the
parser but silently produces a *mutable* borrow instead, a known parser
bug. Get in the habit of writing `const` after the region qualifier
(`&stack const int`, `&heap const int`, ...) and you'll never hit it — see
[Safety](/reference/safety#aliasing-rules-borrow-checker) for the full
detail.
:::

Borrows are also scope-tracked: a mutable borrow taken inside an inner
block is released once that block exits, freeing the variable back up for
outer-scope borrows — you don't have to manually signal "I'm done with
this," the compiler infers it from when the borrow's own scope ends.

## Nullable references

Plain references (`&stack T`, `&heap T`, ...) are non-null by default —
there's no null-pointer-dereference bug class to worry about with them,
because the type system doesn't let a plain reference *be* null in the
first place. When absence is a real possibility, say so in the type with
a leading `?`:

```c
void nullable_demo(?&stack struct Point maybe) {
    match (maybe) {
        case null:    printf("no point\n");
        case some(p): printf("point: %f\n", p.x);
    }
}
```

`match` is the sanctioned way to read one of these — it forces you to
handle the empty case, and inside the `some(p)` arm, `p` is bound as the
plain, non-null, dereference-without-ceremony `struct Point` you actually
wanted. [Chapter 7](/book/ch07-enums-and-match) covers `match` in full;
[Chapter 8](/book/ch08-error-handling) covers `?T` optionals (the
non-reference sibling of `?&region T`) as SafeC's primary error-handling
mechanism.

## `unsafe`: the escape hatch, used sparingly

Sometimes you genuinely need to step outside what the region system can
verify — calling into a C library that hands you a raw pointer, doing
manual pointer arithmetic, or (as in `arena_demo` above) telling the
compiler "I know better than your flow-insensitive check this one time."
That's what `unsafe { ... }` is for:

```c
unsafe {
    int *raw = (int*)malloc(10UL * sizeof(int));
    raw[0] = 42;        // no bounds check inside unsafe
    free(raw);
}
```

Inside `unsafe`, bounds checks are suppressed, region escape analysis is
relaxed, and the aliasing rule isn't enforced — you're back to plain C's
"you're on your own" model, scoped to exactly the block where you opted
into it. `unsafe` doesn't propagate: calling a safe function from inside
an `unsafe` block doesn't make that callee unsafe too, and code outside
the block is checked exactly as strictly as ever. Keep unsafe blocks as
small as the specific operation that needs one — a whole function marked
`unsafe` defeats the point of having the marker be a precise, greppable
signal for "audit this part by hand."

## How this compares to what you might already know

If you've used Rust, the shape of this chapter probably felt familiar —
region-qualified references and borrow checking are SafeC's answer to the
same problem Rust's ownership system solves. The comparison is worth
being precise about, though: SafeC's regions are simpler than Rust's full
lifetime system (no lifetime parameters, no elision rules to memorize),
but that simplicity comes at a real cost, not just a syntax difference.
Rust can return a borrowed reference from a function, as long as its
lifetime is tied to an input parameter's; SafeC's `&stack` references
categorically cannot be returned from a function, full stop — the
`arena` region exists largely to give you back some of that
"data outlives this one function call" flexibility for cases where a
true stack reference won't do. See
[Comparison](/guide/comparison#no-lifetime-elision-because-there-s-no-lifetime-parameter-to-elide)
for a worked example of exactly where that gap shows up and how arenas
fill it.

Next: [Structs and Methods](/book/ch06-structs-and-methods) — now that
you can reason about *where* data lives, it's time to build your own
data types around it.
