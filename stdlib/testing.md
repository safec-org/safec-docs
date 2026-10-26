# Testing & Benchmarking (`std::test`)

`std/test/` provides unit testing, benchmarking, and a lightweight fuzz
harness — all freestanding-safe except `bench.h`'s wall-clock timing
(which needs a hosted `clock()`; in freestanding mode only iteration
counts are reported, timing is skipped).

The `ASSERT_*` convenience macros are function-like macros, so files using
them need `--compat-preprocessor` (see
[Preprocessor](/reference/preprocessor)) — the underlying
`test_assert_*` functions can be called directly without it if you'd
rather avoid the flag.

```c
#include <std/test/test.h>

void addition_works() {
    ASSERT_EQ(2 + 2, 4);
}

int main(void) {
    struct TestSuite t = std::TestSuite::new();
    t.add("addition works", addition_works);
    t.run();
    return t.all_passed() ? 0 : 1;
}
```

## Unit Testing (`test/test.h`) {#unit-testing-testtesth}

`TestSuite` (via `TestSuite::new()`, capacity `TEST_MAX` = 256 cases):
`add(name, func)` (`func`: `fn void()`), `run()` (executes every
registered test, printing each result), `print_summary()` (`"N passed, M
failed."`), `all_passed()`. `test_run_and_exit(&suite)` runs, prints
the summary, then calls `exit(1)` on any failure — convenient as a test
`main()`'s last line.

**Assertions** (call inside a test function — the first failure is
recorded into the currently-running `TestCase` via a global,
thread-local pointer):

| Macro | Function | Checks |
|---|---|---|
| `ASSERT_TRUE(e)` / `ASSERT_FALSE(e)` | `test_assert_true` | Expression is (non-)zero |
| `ASSERT_EQ(a,b)` / `ASSERT_NE(a,b)` | `test_assert_eq_i` | Equal/unequal as `long long` |
| `ASSERT_STR_EQ(a,b)` | `test_assert_eq_s` | Equal null-terminated strings |
| `ASSERT_NULL(p)` / `ASSERT_NOT_NULL(p)` | `test_assert_null`/`test_assert_not_null` | A `?&T` nullable reference is/isn't null (generic over `T`) |

`test_assert_eq_u` (unsigned `long long` equality) has no macro wrapper —
call it directly.

Test names are truncated to `TEST_NAME_MAX` (64) characters.

## Benchmarking (`test/bench.h`) {#benchmarking-testbenchh}

`BenchSuite` (via `BenchSuite::new()`, capacity `BENCH_MAX` = 64 cases):
`suite.add(name, func, arg, iters)` registers a case — `func` is
`fn void(?&T)` and `arg` a matching `?&T` (or `null`), called `iters`
times per run (`add` is a [method with its own type
parameter](/reference/generics#methods-with-their-own-type-parameters):
`T` comes from `func`, so a mismatched `arg` is a compile error); `run()` executes every case and records
`elapsed_s`/`ops_per_s` on each `BenchCase`, `print_results()` prints the
table.

```c
static void push_pop(?&Stack<int> s) {
    match (s) { case some(st): { st.push(1); st.pop(); } case null: { } }
}

void bench() {
    struct Stack<int> st = std::Stack::new();
    struct BenchSuite b = std::BenchSuite::new();
    unsafe { b.add("stack push/pop", push_pop, (?&Stack<int>)&st, 1000000UL); }
    b.run();
    b.print_results();
    st.free();
}
```

Benchmark names are truncated to `BENCH_NAME_MAX` (64) characters.

## Fuzzing (`test/fuzz.h`) {#fuzzing-testfuzzh}

`FuzzTarget` (via `FuzzTarget::new(func, iters)`, `func`:
`fn void([]const unsigned char data)`) — a
lightweight, structure-*un*aware, in-process mutator: `run(corpus)` starts from a seed corpus and applies `iters` random
mutations, feeding each to the target. The corpus and each input are byte slices. Not coverage-guided — this is for
quick, ad hoc fuzzing during development, not a replacement for
libFuzzer/AFL in CI. Crashes/panics in the target function aren't caught;
they surface as ordinary process crashes.
