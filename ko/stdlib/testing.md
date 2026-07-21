# 테스트 및 벤치마킹 (`std::test`)

`std/test/`는 단위 테스트, 벤치마킹, 그리고 경량 퍼징(fuzz) 하네스를
제공한다 — `bench.h`의 실제 경과 시간(wall-clock) 측정을 제외하면 모두
프리스탠딩 환경에서 안전하다(이 기능은 호스트 환경의 `clock()`이
필요하며, 프리스탠딩 모드에서는 반복 횟수만 보고되고 시간 측정은
생략된다).

`ASSERT_*` 편의 매크로는 함수형 매크로이므로, 이를 사용하는 파일은
`--compat-preprocessor`가 필요하다([전처리기](/ko/reference/preprocessor)
참고) — 이 플래그를 피하고 싶다면 내부적으로 쓰이는 `test_assert_*`
함수를 직접 호출해도 된다.

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

## 단위 테스트 (`test/test.h`) {#unit-testing-testtesth}

`TestSuite`(`test_suite_init()`로 생성, 용량 `TEST_MAX` = 256개 케이스):
`add(name, func)`(`func`: `void func(void)`), `run()`(등록된 모든
테스트를 실행하며 각 결과를 출력), `print_summary()`(`"N passed, M
failed."`), `all_passed()`. `test_run_and_exit(&stack suite)`는 실행 후
요약을 출력하고, 실패가 하나라도 있으면 `exit(1)`을 호출한다 — 테스트용
`main()`의 마지막 줄로 쓰기 편리하다.

**단언(assertion)** (테스트 함수 안에서 호출 — 첫 번째 실패는 전역
스레드 로컬 포인터를 통해 현재 실행 중인 `TestCase`에 기록된다):

| 매크로 | 함수 | 검사 내용 |
|---|---|---|
| `ASSERT_TRUE(e)` / `ASSERT_FALSE(e)` | `test_assert_true` | 표현식이 0이(아니)다 |
| `ASSERT_EQ(a,b)` / `ASSERT_NE(a,b)` | `test_assert_eq_i` | `long long`으로서 같음/다름 |
| `ASSERT_STR_EQ(a,b)` | `test_assert_eq_s` | NUL로 끝나는 문자열이 같음 |
| `ASSERT_NULL(p)` / `ASSERT_NOT_NULL(p)` | `test_assert_null`/`test_assert_not_null` | 포인터가 `NULL`이다/아니다 |

`test_assert_eq_u`(부호 없는 `long long` 동등 비교)는 매크로 래퍼가
없으므로 직접 호출해야 한다.

## 벤치마킹 (`test/bench.h`) {#benchmarking-testbenchh}

`BenchSuite`(`bench_suite_init()`로 생성, 용량 `BENCH_MAX` = 64개
케이스): `add(name, func, arg, iters)`는 케이스를 등록하고(`func`:
`void func(void* arg)`, 실행당 `iters`번 호출됨), `run()`은 모든 케이스를
실행하고 각 `BenchCase`에 `elapsed_s`/`ops_per_s`를 기록하며,
`print_results()`는 결과 표를 출력한다.

## 퍼징 (`test/fuzz.h`) {#fuzzing-testfuzzh}

`FuzzTarget`(`fuzz_target_init(func, iters)`로 생성, `func`:
`void func(const unsigned char* data, unsigned long size)`) — 구조를
인식하지 못하는 경량 인프로세스 뮤테이터다: `run(corpus, corpus_size)`는
시드 코퍼스에서 시작해 `iters`번의 무작위 변이를 적용하고, 각 결과를
대상 함수에 전달한다. 커버리지 기반이 아니므로 개발 중 간단하고 임시적인
퍼징을 위한 것이지, CI에서 libFuzzer/AFL을 대체하는 용도는 아니다. 대상
함수 안에서 발생하는 크래시/패닉은 포착되지 않으며, 일반적인 프로세스
크래시로 그대로 드러난다.
