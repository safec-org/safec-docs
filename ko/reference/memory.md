# 메모리와 리전

SafeC는 컴파일 타임에 라이프타임 안전성을 강제하는 리전 기반 메모리 모델을 사용합니다. 모든 참조는 데이터가 어디에 있고 얼마나 오래 유효한지를 컴파일러에게 알려주는 리전 한정자를 가지고 있습니다. 리전은 컴파일 타임 전용입니다 -- 런타임 메타데이터를 만들어내지 않으며, 생성된 코드에서는 원시 포인터로 소거됩니다.

## 네 가지 리전 {#the-four-regions}

| 리전 | 라이프타임 | 할당 | 해제 |
|--------|----------|------------|--------------|
| `stack` | 어휘적 스코프 | 자동 (스택 프레임) | 자동 (스코프 종료) |
| `static` | 프로그램 생애 전체 | 정적 저장소 | 없음 |
| `heap` | 동적 | 명시적 (`malloc`) | 명시적 (`free`) |
| `arena<R>` | 사용자 정의 | 리전 할당자 | 일괄 리셋 |

## 리전 한정 참조 {#region-qualified-references}

SafeC의 모든 참조는 리전 한정되어야 합니다. 이는 참조 대상 데이터가 정확히 어디에 있는지 컴파일러에게 알려줍니다.

```c
&stack int x_ref;              // 스택에 할당된 int를 가리킴
&heap float buf_ref;           // 힙에 할당된 float를 가리킴
&static Config cfg_ref;        // 정적 저장소를 가리킴
&arena<AudioPool> Frame f;     // 아레나에 할당된 Frame을 가리킴
```

모든 참조는 기본적으로 non-null입니다. Nullable 참조에는 선택적 접두사를 사용하십시오.

```c
?&stack Node next;             // null일 수 있음
?&heap Buffer cache;           // null일 수 있음
```

## 스택 리전 {#stack-region}

`stack` 리전은 지역 변수의 기본값입니다. 스택 참조는 자신이 생성된 스코프를 벗어날 수 없습니다.

```c
void example() {
    int x = 42;
    &stack int ref = &x;       // OK: ref는 x와 같은 스코프에 있음

    // return ref;             // ERROR: 스택 참조가 스코프를 벗어남
}
```

컴파일러는 스택 참조가 참조 대상보다 오래 살아남지 않도록 이스케이프 분석을 수행합니다.

```c
&stack int bad() {
    int local = 10;
    return &local;             // ERROR: 지역 변수에 대한 참조를 반환함
}
```

## 정적 리전 {#static-region}

`static` 리전은 프로그램 전체 생애 동안 살아있는 데이터를 위한 것입니다.

```c
static int counter = 0;

&static int get_counter() {
    return &counter;           // OK: counter는 영원히 살아있음
}
```

정적 참조는 가장 관대합니다 -- 절대 무효화되지 않으므로 어디에나 저장될 수 있습니다.

## 힙 리전 {#heap-region}

`heap` 리전은 동적으로 할당된 데이터를 위한 것입니다. 힙 참조는 명시적인 할당과 해제가 필요합니다.

```c
void example() {
    &heap int p = new int;
    *p = 42;
    std::dealloc(p);
}
```

컴파일러는 힙 참조를 추적하지만 자동으로 해제하지는 않습니다. 결정론적인 정리를 위해 `defer`를 사용하십시오.

```c
void process() {
    &heap char buf = new char[4096];
    defer std::dealloc(buf);

    // ... buf 사용 ...
}   // buf는 여기서 defer를 통해 해제됨
```

::: warning `(heap T*)`가 아니라 일반 포인터 타입으로 캐스트하십시오
`(heap int*)malloc(...)`은 파싱되지 않습니다 — 리전 한정자는 캐스트의
괄호 안에서 유효하지 않습니다. (`(int*)`처럼) 일반 포인터 타입으로
캐스트하십시오. 위에서 보인 것처럼 결과는 대입 시 `&heap` 참조로 암묵적으로
변환됩니다. `malloc(4096UL)`의 `UL` 접미사에도 유의하십시오 — `malloc`은
`unsigned long`(`size_t`)을 받으며, `int` → `unsigned long`의 암묵적
변환은 없습니다([타입](/ko/reference/types) 참고).
:::

`<std/mem.h>`의 `malloc_defer(n)`은 위의 할당-후-`defer dealloc` 패턴을
하나의 호출로 설탕 처리(sugar)합니다. `malloc_defer`는 실제 함수가
아닙니다 — 변수 선언의 초기화식으로만 인식되는 파서 수준의 문법 설탕이며,
선언 부분은 `std::alloc(n)`(선언된 타입에서 `T`를 가져오는 제네릭
할당자)으로, 그 바로 뒤에는 합성된 `defer std::dealloc(buf);`로
탈설탕(desugar)됩니다. 타입이 있고 널 검사가 되는 뷰를 얻으려면 변수를 널
가능 힙 참조로 선언하십시오(`auto`로 선언하면 `T`를 가져올 타입이 없으므로
타입 소거된 `std::alloc_raw_(n)` / `std::dealloc_raw_` 쌍이 쓰이고, 변수는
바이트 포인터 `unsigned char*`가 됩니다):

```c
#include <std/mem.h>

void process2() {
    ?&heap int buf = malloc_defer(4096UL);   // 스코프 종료 시 자동으로 해제됨
    match (buf) {
        case some(p): { *p = 1; }   // ... p 사용 ...
        case null:    { }           // 메모리 부족
    }
}   // std::dealloc(buf)가 여기서 합성된 defer를 통해 실행됨
```

지연된 해제는 스코프가 끝날 때 실행되므로, 그 뒤의 문장들에 대해서는
참조를 해제한 것으로 치지 않습니다 — 이미 실행된 명시적 `dealloc`만
그렇습니다(아래 참고).

### 컴파일 타임 해제 후 사용 및 이중 해제 검사 {#compile-time-use-after-free-and-double-free-checking}

`std::dealloc(p)`(또는 할당자의 `.dealloc(p)` 메서드)로 해제된
`&heap T` 참조는 아레나 참조와 같은 데이터플로우 분석으로 추적됩니다
([아레나 참조는 리셋 시 소멸합니다](#_4-arena-references-die-on-reset) 참고).
그 지점 이후에 참조를 읽는 것은 컴파일 오류이며, 다시 해제하는 것은
이중 해제로서 역시 컴파일 타임에 잡힙니다.

```c
#include <std/mem.h>

void f() {
    &heap int p = new int;
    *p = 42;
    std::dealloc(p);
    *p = 43;              // ERROR: 'p'(&heap 참조)가 std::dealloc()에
                           //        의해 해제된 후 사용됨
    std::dealloc(p);       // ERROR: 동일한 검사 -- 이것이 이중 해제임
}
```

재바인딩은 아레나 참조와 마찬가지로 `p`를 복구합니다.

```c
&heap int p = new int;
std::dealloc(p);
p = new int;        // OK: 새로운 할당, 새로운 바인딩
*p = 42;             // OK
std::dealloc(p);
```

이 분석은 변수 이름이 아니라 포인터 하나하나를 추적합니다. 해제는
그 포인터의 모든 복사본(대입을 몇 번 거쳤든), 그 포인터가 저장된 모든
구조체 필드와 배열 원소에 미치며, 피호출 함수가 매개변수나 그 필드를
해제하면 함수별 요약을 통해 호출자 쪽 복사본에도 미칩니다. 피호출 함수
자체에는 문제가 없지만 호출자가 넘긴 것이 이미 해제된 경우에는 호출
지점에서 오류를 보고합니다.

```c
#include <std/mem.h>

struct Box { &heap int v; };

void release(&heap int p) { std::dealloc(p); }
void release_box(&Box b)  { std::dealloc(b.v); }
int  peek(&Box b)         { return *b.v; }

void g() {
    &heap int p = new int;
    &heap int q = p;           // 복사를 몇 번 거쳐도...
    struct Box b;
    b.v = q;                   // ...필드나 배열 원소에 저장해도 추적됨
    release(b.v);              // release()는 매개변수를 해제함
    *p = 1;                    // ERROR: use of 'p' (&heap reference) after
                               //        std::dealloc() freed it
    int n = peek(&b);          // ERROR: call to 'peek' reads a &heap reference
                               //        after std::dealloc() freed it
    release_box(&b);           // ERROR: call to 'release_box' frees memory
                               //        after std::dealloc() freed it
}
```

::: warning 검사하지 않고 신뢰하는 것
이 모델 안에서 검사는 건전합니다. 전체 설명은
[시간 안전성](/ko/advanced/safety-model#_2-temporal-safety)에 있습니다.
모델 밖에 있는 것은 다음과 같습니다.

- 원시 libc `free(p)`, 본문이 없는 그 밖의 함수(`extern` 선언, C
  임포트), 함수 포인터나 `fn` 값을 통한 호출, `spawn`이 시작하는
  함수는 아무것도 해제하지 않고 인자에 대한 포인터를 보관하지 않는다고
  가정합니다. 이런 방식으로 해제된 포인터는 컴파일 타임에 잡히지 않으며,
  대신 [안전성](/ko/reference/safety)에 설명된 런타임 이중 해제/유효하지
  않은 해제 보호에 의존합니다.
- 원시 포인터, 또는 `unsafe {}` 안의 포인터 인덱싱으로 도달한
  메모리(`std::Vec` 등 std 컨테이너의 버퍼)는 추적하지 않으며, 그곳에서
  다시 읽어 온 포인터는 유효한 것으로 간주합니다.
- 한 함수의 서로 다른 매개변수(및 전역 변수)는 같은 할당을 가리키지
  않는다고 가정합니다.

실제로는 올바른 두 가지 형태가 여전히 보고됩니다. 서로 반대되는
조건(`if (owned)` / `if (!owned)`)으로 보호된 해제와 사용, 그리고 연결
구조를 안전한 참조로 순회하며 노드를 하나씩 해제하는 루프입니다(재귀로
작성하거나, `unsafe {}` 안에서 원시 포인터로 순회하면 됩니다).
`unsafe {}`는 다른 모든 리전 규칙과 마찬가지로 그 안의 보고를 억제하며,
그 안에서 일어난 해제는 뒤따르는 코드에 그대로 반영됩니다.
:::

### 누수 검사: 소유 타입 {#leak-checking}

위의 검사는 *참조*를 다룹니다: 해제 후의 사용이나 해제를 잡아냅니다. 메모리를 반드시 해제하라고 요구하지는 않으므로, 해제되지 않는 맨 `&heap T`는 보고되지 않습니다. 대신 **소유 타입**에 대해서는 누수를 잡아냅니다: `drop()` 메서드를 가진 구조체(`std::Vec`, `std::String`, 또는 `drop()`에서 버퍼를 해제하는 `Buf` 같은)는 모든 경로에서 drop 또는 move되어야 하므로, 그것이 소유한 할당을 잊을 수 없습니다.

```c
struct Buf { &heap char data; void drop(); };
void Buf::drop() { std::dealloc(self.data); }

void f(int c) {
    struct Buf b = Buf::new(4096UL);
    if (c) return;     // ERROR: value of Drop type 'Buf' leaks when the function returns
    b.drop();
}
```

스코프보다 오래 사는 할당은 그런 타입으로 감싸십시오. [소유권과 Drop](/ko/reference/ownership)을 참고하십시오.

## 아레나 리전 {#arena-regions}

아레나는 일괄 할당과 일괄 해제를 제공합니다. 리전을 선언하고, `new<R>`으로 그 안에 할당하며, `arena_reset<R>()`으로 모든 할당을 한 번에 리셋합니다.

### 리전 선언 {#declaring-a-region}

```c
region AudioPool {
    capacity: 4096
}
```

### 아레나에 할당하기 {#allocating-in-an-arena}

```c
&arena<AudioPool> Sample s = new<AudioPool> Sample;
```

`new<R> T` 표현식은 아레나로부터 범프 포인터(bump-pointer) 할당을 수행합니다. 오프셋 증가와 용량 검사뿐이므로 빠릅니다. 리전의 남은 용량에 맞지 않는 할당은 경계를 벗어난 인덱스와 마찬가지로 프로그램을 중단시킵니다 — 아레나 끝을 넘어선 메모리를 절대 내주지 않습니다.

### 아레나 리셋 {#resetting-an-arena}

```c
arena_reset<AudioPool>();
```

이는 아레나의 오프셋을 0으로 리셋하여 모든 할당을 한 번에 효과적으로 해제합니다. 소멸자는 호출되지 않습니다 -- 아레나에 할당된 객체는 외부 리소스를 소유해서는 안 됩니다.

### 아레나 소멸 {#destroying-an-arena}

`arena_destroy<R>()`는 `arena_reset<R>()`보다 한 단계 더 나아갑니다: 범프 오프셋을 되감는 것에 그치지 않고 리전의 malloc된 백업 버퍼를 완전히 해제합니다.

```c
arena_destroy<AudioPool>();
```

두 번째 호출은 안전한 no-op입니다(버퍼 포인터가 첫 호출 후 null이 됨).
이후의 `new<AudioPool> T`는 투명하게 새 백업 버퍼를 다시 `malloc`합니다.
아레나를 당분간 재사용하지 않고 메모리를 시스템에 반환하고 싶을 때는
`arena_destroy<R>()`를, 곧 다시 할당을 계속할 것이고 재-`malloc` 비용을
피하고 싶을 때는 `arena_reset<R>()`을 사용하십시오.

### 아레나 부분 해제 {#partially-freeing-an-arena}

`arena_reset<R>()`은 항상 0까지 완전히 되감습니다. `arena_mark<R>()` /
`arena_free_to<R>()`는 아레나 할당의 *꼬리(tail)* 부분만 해제하기 위한
체크포인트/되감기 쌍입니다 — 체크포인트를 찍고, 수명이 짧은 작업용
데이터를 얼마간 할당한 뒤, 그 데이터만큼만 되감는 스크래치 공간 패턴입니다.

```c
region AudioPool { capacity: 4096 }

void process() {
    for (int i = 0; i < 10; i = i + 1) {
        unsigned long mark = arena_mark<AudioPool>();          // 체크포인트
        &arena<AudioPool> struct Sample scratch = new<AudioPool> struct Sample;
        // ... 이번 반복에서만 scratch 사용 ...
        arena_free_to<AudioPool>(mark);                        // 'scratch'만 해제
    }
}
```

`arena_mark<R>()`은 리전의 현재 바이트 오프셋(`unsigned long`)을 불투명한
체크포인트 값으로 반환합니다. `arena_free_to<R>(mark)`는 런타임에 오프셋을
그 지점으로 되감으며, 오래되었거나 지나치게 큰 `mark`가 `used`를 줄이기만
할 뿐 절대 늘릴 수 없도록 클램프됩니다 — 검증됨: 체크포인트 *이전*에
이루어진 할당은, 이후의 할당이 그 체크포인트로 해제되고 다른 무언가가
그 공간을 재사용하더라도 원래 값을 유지합니다.

`arena_free_to<R>()`는 되감는 대상 체크포인트 *이후*에 할당된 참조만
무효화합니다 — `R`에 대해 `arena_mark<R>()`가 한 번도 호출되기 전에
바인딩된 참조는 `arena_free_to<R>()` 호출에도 손상되지 않고 살아남습니다.
해제되는 스크래치 범위 밖의 메모리를 가리키고 있기 때문입니다.

```c
&arena<AudioPool> struct Sample keep = new<AudioPool> struct Sample;
unsigned long mark = arena_mark<AudioPool>();
&arena<AudioPool> struct Sample scratch = new<AudioPool> struct Sample;
arena_free_to<AudioPool>(mark);
keep->v = 5;      // OK: 'keep'은 어떤 mark()보다도 먼저 바인딩됨 — free_to()에 의해 절대 stale해지지 않음
scratch->v = 5;   // ERROR: 'scratch'는 mark 이후에 바인딩됨 — 올바르게 잡힘
```

`arena_mark<R>()` 호출은 각각 별개의 체크포인트로 추적되므로, 중첩된
mark도 정밀하게 다룹니다. 안쪽 mark로 되감아도 바깥 mark와 안쪽 mark
사이에 할당된 참조는 유효하게 남습니다.

```c
region Pool { capacity: 4096 }

void nested() {
    unsigned long outer = arena_mark<Pool>();
    &arena<Pool> struct Sample a = new<Pool> struct Sample;
    unsigned long inner = arena_mark<Pool>();
    &arena<Pool> struct Sample b = new<Pool> struct Sample;
    arena_free_to<Pool>(inner);
    a->v = 1;      // OK: 'a'는 'inner' 이전에 할당됨
    b->v = 1;      // ERROR: 'b'(&arena<Pool> 참조)가 arena_reset<Pool>(),
                   //        arena_destroy<Pool>(), 또는 arena_free_to<Pool>()에
                   //        의해 무효화된 후 사용됨
    arena_free_to<Pool>(outer);
    a->v = 2;      // ERROR: 'a'에 대한 같은 오류
}
```

검사기가 식별할 수 없는 mark(호출자에게서 받았으나 해석할 수 없는
mark, 또는 여러 mark 중 하나일 수 있는 값)만이 `arena_free_to<R>()`가
그보다 넓게, 보수적으로 무효화하게 만듭니다. 전달받은 mark로 되감는
피호출 함수는 호출 지점에 반영됩니다.

루프 안에서 mark/free_to 쌍은 문제없으며(위의 스크래치 예제처럼 각
반복의 free_to는 그 반복의 할당만 되감습니다), 같은 본문에서 더 일찍
사용된 참조를 무효화하는 `arena_free_to<R>()`는 다음 반복에서 잡힙니다.

```c
region Pool { capacity: 4096 }

void process() {
    unsigned long mark = arena_mark<Pool>();
    &arena<Pool> struct Sample keep = new<Pool> struct Sample;
    for (int i = 0; i < 3; i = i + 1) {
        keep->v = 1;                 // ERROR: 아래의 free_to에 의해 이전
                                      // 반복에서 무효화되었을 수 있음
        arena_free_to<Pool>(mark);   // 짝 없음 -- 이 루프 본문에는 mark()가 없음
    }
}
```

`unsafe {}`는 다른 모든 리전 규칙과 마찬가지로 이 검사도 우회합니다.

### 아레나 런타임 표현 {#arena-runtime-representation}

LLVM 레벨에서 각 아레나는 전역 구조체 `{ptr, i64 used, i64 cap}`입니다.

- `ptr` -- 아레나의 백업 메모리에 대한 기준 포인터
- `used` -- 현재 범프 오프셋
- `cap` -- 총 용량(바이트)

`new<R> T`(또는 `new<R> T[n]`)는 `sizeof(T)`(곱하기 `n`)가 `cap - used`에 들어가는지 검사해 들어가지 않으면 중단하고, 들어가면 `used`를 증가시키고 `ptr + old_used`를 반환합니다. `arena_reset<R>()`은 `used`를 다시 0으로 설정합니다.

## 리전별 배열 {#arrays-by-region}

배열의 크기를 언제 정할 수 있는지는 배열이 어디에 사느냐에 달려 있습니다:

| 리전 | 선언 | 크기 |
|--------|-------------|------|
| 스택 | `int buf[8];` | 컴파일 타임 상수 (런타임 크기는 `unsafe` 안에서만 허용) |
| 정적 | `static int table[16];` | 컴파일 타임 상수 |
| 힙 | `&heap int h = new int[n];` | 임의의 런타임 `n`; `std::dealloc(h)`로 해제 |
| 아레나 | `&arena<R> int a = new<R> int[n];` | 임의의 런타임 `n`이지만 `R`의 남은 용량에 들어가야 함 — 그렇지 않으면 프로그램이 중단됨 |

`new`는 첫 원소에 대한 참조를 반환합니다. 참조에는 길이가 없고 인덱싱할 수 없으므로, 배열은 경계 검사가 있는 [`Slice<T>`](/ko/stdlib/collections#slice)(`std::Slice::of(first, n)`)나 `[]T` 슬라이스로 넘기고 순회하십시오. 참조의 배열도 마찬가지입니다 — 스택에서는 `&T items[N]`, 함수 경계를 넘을 때는 `Slice<&T>` — 이것이 C의 `T**` "포인터 배열"을 대신합니다:

```c
#include <std/collections/slice.h>
struct Sample { int v; };
region Frame { capacity: 4096 }

static int g_table[16];                      // static: size fixed at compile time

void fill(struct Slice<int> s, int v) {
    for (unsigned long i = 0UL; i < s.length(); i = i + 1UL) s.set(i, v);
}

int arrays(unsigned long n) {
    int local[8];                            // stack: size fixed at compile time
    // int bad[n];                           // error: 'n' is not a compile-time constant
    &heap int h = new int[n];                // heap: runtime size
    &arena<Frame> int a = new<Frame> int[n]; // arena: runtime size, within Frame's 4096 bytes

    fill(std::Slice::of(&local[0], 8UL), 1);  // view each one through a bounds-checked Slice
    fill(std::Slice::of(h, n), 2);
    fill(std::Slice::of(a, n), 3);
    int t = local[7] + *std::Slice::of(h, n).at(n - 1UL) + *std::Slice::of(a, n).at(0UL) + g_table[0];

    std::dealloc(h);
    arena_reset<Frame>();
    return t;                                // 1 + 2 + 3 + 0
}

// Arrays of references follow the same rules.
int count_live(struct Slice<&Sample> items) { return (int)items.length(); }

int main() {
    struct Sample a; a.v = 1;
    struct Sample b; b.v = 2;
    &Sample fixed[2] = { &a, &b };           // stack array of references
    int c = count_live(std::Slice::of(&fixed[0], 2UL));
    return arrays(4UL) == 6 && c == 2 ? 0 : 1;
}
```

## 참조에 대한 참조 {#references-to-references}

참조는 다른 참조를 가리킬 수 있습니다 — C의 `T**`를 대신합니다. 각 단계가 무엇을 가질 수 있는지에 따라 형태를 고르십시오:

| 타입 | 바깥 | 안쪽 | 대표적인 용도 |
|------|-------|-------|-------------|
| `&&T` | 항상 있음 | 항상 있음 | 항상 참조를 받는 출력 매개변수 |
| `&?&T` | 항상 있음 | null일 수 있음 | 결과가 "없음"일 수 있는 출력 매개변수 (토크나이저 위치, 조회) |
| `?&&T` | 선택적 | 항상 있음 | 선택적 출력 매개변수 (`strtol`의 `end`) |
| `?&?&T` | 선택적 | null일 수 있음 | 결과가 "없음"일 수 있는 선택적 출력 매개변수 |

```c
static int g_value = 7;

void always(&&int out)      { *out = &g_value; }   // writes a non-null reference
void maybe(&?&int out)      { *out = null; }       // may write null
void optional(?&&int out) {
    match (out) {
        case some(o): { *o = &g_value; }
        case null:    { }                          // caller didn't ask
    }
}
```

`&&T`와 `& &T`는 같은 의미입니다. 참조의 배열은 참조에 대한 참조가 아닙니다 — 위처럼 배열이나 `Slice<&T>`로 감싸십시오.

## 리전 규칙 {#region-rules}

컴파일러는 메모리 안전성을 보장하기 위해 여러 규칙을 강제합니다.

### 1. 참조는 자신의 리전보다 오래 살 수 없습니다 {#_1-references-cannot-outlive-their-region}

```c
&stack int ref;
{
    int x = 10;
    ref = &x;          // ERROR: x의 스코프가 ref보다 짧음
}
// 여기서 ref는 댕글링(dangling) 상태가 됨
```

### 2. 참조는 더 오래 사는 리전으로 이스케이프할 수 없습니다 {#_2-references-cannot-escape-to-a-longer-lived-region}

수명이 짧은 리전에 대한 참조는 더 오래 사는 위치에 저장될 수 없습니다.

```c
static &stack int global_ref;  // ERROR: 스택 참조가 static에 저장됨

void bad() {
    int local = 5;
    global_ref = &local;       // ERROR: 스택 참조가 static으로 이스케이프함
}
```

### 3. 암묵적인 교차 리전 변환은 없습니다 {#_3-no-implicit-cross-region-conversion}

서로 다른 리전의 참조는 서로 교환하여 사용할 수 없습니다.

```c
void takes_heap(&heap int p);

void example() {
    int x = 42;
    takes_heap(&x);            // ERROR: &stack int는 &heap int가 아님
}
```

### 4. 아레나 참조는 리셋 시 소멸합니다 {#_4-arena-references-die-on-reset}

`arena_reset<R>()`과 `arena_destroy<R>()`는 리전 `R`에 속한 *모든*
남아있는 참조를 무효화합니다. 이들이 가리키는 메모리가 이후의 `new<R>`에
의해 다시 배분될 수 있기 때문입니다. reset이나 destroy 이후에는 `R`을
가리키는 모든 참조(그리고 변수, 필드, 배열 원소에 있는 그 복사본)가
해제되었을 수 있으며, 이를 읽는 것은 컴파일 오류입니다.
`arena_free_to<R>()`는 범위가 더 좁아서
([아레나 부분 해제](#partially-freeing-an-arena) 참고), 되감는 대상
체크포인트 이후에 할당된 참조만 무효화합니다.

```c
region Pool { capacity: 1024 }
int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;   // ERROR: 'p'(&arena<Pool> 참조)가 arena_reset<Pool>(),
               //        arena_destroy<Pool>(), 또는 arena_free_to<Pool>()에
               //        의해 무효화된 후 사용됨
    return 0;
}
```

이 검사는 텍스트 순서가 아니라 함수의 제어 흐름을 따라가는 데이터플로우
분석입니다 — 알아두어야 할 두 가지 경우가 있습니다.

- **if/else 분기는 서로에게 번지지 않습니다.** 한 분기 안에서의 reset은
  *그 분기*의 나머지 부분과 *if 문 이후*의 코드(그 분기가 실행되었을 수
  있는 곳)에만 영향을 주며, *다른* 분기에는 영향을 주지 않습니다.
  ```c
  if (cond) {
      arena_reset<Pool>();
  } else {
      *p = 2;   // OK: 이 분기는 절대 Pool을 리셋하지 않음
  }
  *p = 3;       // ERROR: 'then' 분기가 실행되었을 수 있으므로, 이는
                //        stale할 가능성이 있음
  ```
- **루프 본문 어디에서든의 reset은 이후의 코드뿐 아니라 본문 전체를
  무효화합니다.** 이후 반복이 이전 반복의 reset *이후*에 본문의 맨
  위부터 다시 실행되므로, reset 호출보다 텍스트상 *이전*에 있는 사용도
  플래그됩니다.
  ```c
  while (cond) {
      *p = 1;                // ERROR: *이전* 반복이 여기서 Pool을 리셋했을 수 있음
      arena_reset<Pool>();
  }
  ```
  각 루프는 상태가 더 이상 바뀌지 않을 때까지 분석하므로, reset이 본문
  어디에(또는 어떤 표현식 안에) 있든 다음 반복에서 본문의 앞쪽 코드에
  닿고, 루프 뒤의 코드에는 그 뒤에 올 수 있는 출구를 통해서만 닿습니다.

`switch`의 fallthrough, `match`, `?:`, `&&`/`||`,
`break`/`continue`/`goto`/`return`/`try`, 그리고 각 반환 지점에서 실행되는
`defer`/`errdefer`도 마찬가지이며, arena reset뿐 아니라 힙 해제에도
똑같이 적용됩니다. 리전을 reset하거나 전달받은 mark로 되감는 함수는 호출
지점에서 호출자의 참조를 무효화합니다. 이 검사는 건전합니다 — 실제
use-after-reset이나 use-after-free_to는 결코 놓치지 않습니다 — 단,
[시간 안전성](/ko/advanced/safety-model#_2-temporal-safety)에 나열된 신뢰
경계 안에서입니다.

Stale해진 참조를 새 할당에 재바인딩하면 복구됩니다 — 이 검사는 *특정*
stale 바인딩을 읽는 것에 관한 것이지, 변수 이름에 관한 것이 아닙니다.

```c
&arena<Pool> int p = new<Pool> int;
arena_reset<Pool>();
p = new<Pool> int;   // OK: reset 이후에 이루어진 새 할당
*p = 42;              // OK
```

## Unsafe 탈출구 {#unsafe-escape-hatch}

리전 규칙이 지나치게 제한적일 때, `unsafe {}` 블록으로 이를 우회할 수 있습니다.

```c
unsafe {
    int *raw = (int*)malloc(sizeof(int));
    *raw = 42;
    free(raw);
}
```

전체 unsafe 모델은 [안전성](/ko/reference/safety)을 참고하십시오.

## Outliving 참조: 리전이 없는 `&T` / `?&T` {#outliving-references-t-t-with-no-region}

리전 한정자를 아예 생략하는 것 — `&T`(non-nullable)나 `?&T`(nullable) —
는 **선언되거나 추적되는 리전이 전혀 없는** 참조를 만듭니다. 이는 위의
리전 이스케이프/라이프타임 분석에 추가되는 다섯 번째 항목이 아니라,
두 가지 겹치는 경우를 위해 리전이 필요하다는 조건 자체에서 벗어나는
탈출구입니다.

1. **`extern` 경계를 넘는 경우.** C 함수가 호출이 반환된 이후에도 *보유*할
   수 있는 포인터(등록된 콜백의 컨텍스트, 불투명한 핸들)는 정직하게
   `&stack`(호출과 함께 소멸)이나 `&heap`/`&static`(SafeC가 ABI 경계를
   넘어 검증할 수 없는 라이프타임 보장을 주장함)일 수 없습니다.
2. **그 자체로 특정 리전에 얽매이지 않는 경우.** 호출 지속 시간 동안
   참조를 통해 읽거나 쓰기만 하는 대부분의 함수는 호출자의 값이
   `&stack`, `&heap`, `&static`, `&arena<R>` 중 무엇인지 신경 쓰지
   않습니다 — 매개변수 타입으로 특정 리전 하나를 선택하는 것은 안전성의
   이득 없이 호출할 수 있는 대상만 좁힐 뿐입니다. 구체적인 리전은
   매개변수 자체의 의미가 진짜로 하나의 라이프타임에 고정될 때만
   사용하십시오(예: 자신의 반환 이후에도 참조를 저장하는 콜리(callee)는
   `&stack`이 아니라 `&heap`/`&static`이 필요합니다).

```c
struct Point { int x; int y; }

// 리전이 고정되지 않음 -- 호출자는 &stack, &heap, &static, &arena<R> 중 아무거나 전달 가능
int sumPoint(&Point p) { return p.x + p.y; }

struct Point local;
local.x = 3; local.y = 4;
&stack Point ref = &local;
sumPoint(ref);        // &stack Point -> &Point: 암묵적, unsafe 없음, 리전 검사 없음
```

*어떤* 리전의 참조든 암묵적으로(unsafe나 캐스트 없이) `&T`/`?&T`로
변환되며, `&T`/`?&T`도 마찬가지로 암묵적으로 원시 포인터로 다시
변환됩니다 — 애초에 검사할 리전이 없기 때문입니다. Nullable 여부는
여기서는 일반적이고 독립적인 선택입니다 — 값이 진짜로 부재할 수 있는
경우에만 `?&T`를 사용하고, 그렇지 않으면 `&T`를 사용하십시오. 이는
`?&T`에도 동일하게 적용되는 nullable 참조 읽기 문법(`match`/`is_null()`/
`.default()`/`!`)에 대해서는 [안전성](/ko/reference/safety)을 참고하십시오.

## FFI와 리전 {#ffi-and-regions}

C 함수를 호출할 때, 리전 정보는 소거됩니다.

- `&static T`는 자동으로 `T*`로 변환됩니다(안전하며 `unsafe` 불필요)
- `&T`/`?&T`(리전 없음)는 양방향으로 `T*`/`void*`와 자동으로 상호 변환됩니다
  (안전하며 `unsafe` 불필요) — 위의 "Outliving 참조" 참고
- 그 외 리전 참조(`&stack`, `&heap`, `&arena<R>`)는 C에 전달하려면
  `unsafe {}`가 필요합니다

```c
extern int printf(const char *fmt, ...);

void example() {
    static const char *msg = "hello\n";
    printf(msg);                // OK: &static → 원시 포인터는 안전함
}
```

::: warning `static const char*` 초기화식은 전역이 아니라 지역이어야 합니다
파일 스코프의 `static const char *msg = "hello\n";`는 값이 정말로
문자열 리터럴 상수임에도 불구하고 "전역 'msg'의 초기화식이 컴파일
타임 상수 표현식이 아님" 오류로 실패합니다. 같은 선언이 위에서 보인
것처럼 함수 안의 지역(`static` 저장소) 변수로는 정상적으로 동작합니다.
:::

자세한 내용은 [C 상호운용](/ko/reference/ffi)을 참고하십시오.
