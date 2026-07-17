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
    struct TestSuite t = std::test_suite_init();
    unsafe { t.add("addition works", (void*)addition_works); }
    t.run();
    return t.all_passed() ? 0 : 1;
}
```

## Unit Testing (`test/test.h`)

`TestSuite` (via `test_suite_init()`, capacity `TEST_MAX` = 256 cases):
`add(name, func)` (`func`: `void func(void)`), `run()` (executes every
registered test, printing each result), `print_summary()` (`"N passed, M
failed."`), `all_passed()`. `test_run_and_exit(&stack suite)` runs, prints
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
| `ASSERT_NULL(p)` / `ASSERT_NOT_NULL(p)` | `test_assert_null`/`test_assert_not_null` | Pointer is/isn't `NULL` |

`test_assert_eq_u` (unsigned `long long` equality) has no macro wrapper —
call it directly.

## Benchmarking (`test/bench.h`)

`BenchSuite` (via `bench_suite_init()`, capacity `BENCH_MAX` = 64 cases):
`add(name, func, arg, iters)` registers a case (`func`:
`void func(void* arg)`, called `iters` times per run), `run()` executes
every case and records `elapsed_s`/`ops_per_s` on each `BenchCase`,
`print_results()` prints the table.

## Fuzzing (`test/fuzz.h`)

`FuzzTarget` (via `fuzz_target_init(func, iters)`, `func`:
`void func(const unsigned char* data, unsigned long size)`) — a
lightweight, structure-*un*aware, in-process mutator: `run(corpus,
corpus_size)` starts from a seed corpus and applies `iters` random
mutations, feeding each to the target. Not coverage-guided — this is for
quick, ad hoc fuzzing during development, not a replacement for
libFuzzer/AFL in CI. Crashes/panics in the target function aren't caught;
they surface as ordinary process crashes.
