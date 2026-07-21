---
title: 비교
---

# 다른 언어와의 비교

SafeC는 시스템 프로그래밍 언어의 설계 공간 안에서 특정한 위치를 차지합니다. 이 페이지는 저수준, 안전이 중요한, 실시간 시스템에 가장 중요한 차원들을 기준으로 SafeC를 C, C++, Rust, Zig와 비교합니다.

## 기능 비교 {#feature-comparison}

| 기능 | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| 메모리 안전성 | No | No | Yes | No | Yes |
| C ABI | Native | Native | FFI | Native | Native |
| 숨겨진 런타임 | No | Partial | Partial | No | No |
| 컴파일 타임 평가 | No | constexpr | const fn | comptime | Compile-time-first |
| 가비지 컬렉터 | No | No | No | No | No |
| Unsafe 탈출구 | N/A | N/A | `unsafe` | Manual | `unsafe {}` |
| 메모리 모델 | Manual | Manual/RAII | Ownership | Allocators | Explicit regions |
| 전처리기 | Full (unsafe) | Full (unsafe) | None | Limited | Disciplined subset |
| 예외 | No (setjmp) | Yes | No (panic) | No | No |
| 제네릭 | No | Templates | Generics | comptime | Monomorphized |
| 구조체 메서드 | No | Classes | impl blocks | Methods | Struct methods |
| 연산자 오버로딩 | No | Yes | Traits | No | Yes |

위 "메모리 안전성" 행은 필연적으로 단순한 예/아니오 표시입니다 — 각 언어가 실제로 무엇을(공간 안전성, use-after-free, 이중 해제, null 안전성, 데이터 레이스 등) 속성별로 검사하는지, 그리고 그 검사가 컴파일 타임에 일어나는지, 런타임에 일어나는지, 아니면 옵트인 빌드 모드로 일어나는지에 대한 세부 분석은 [형식 안전 모델의 메모리 안전성 비교](/ko/advanced/safety-model#memory-safety-compared-to-c-c-rust-and-zig)를 참고하세요.

## C와 비교 {#compared-to-c}

SafeC는 C의 상위 집합(superset)입니다. 유효한 C 패턴은 SafeC에서도 동작하지만, SafeC는 C에는 없는 컴파일 타임 안전 검사를 추가합니다.

### SafeC가 추가하는 것 {#what-safec-adds}

**리전이 표기된 참조**는 댕글링 포인터와 use-after-free를 방지합니다:

```c
// C: 컴파일은 되지만 런타임에 정의되지 않은 동작
int* dangling() {
    int x = 42;
    return &x;  // 소멸된 스택 변수를 가리키는 포인터를 반환
}

// SafeC: 컴파일 타임 오류
&stack int dangling() {
    int x = 42;
    return &x;  // ERROR: stack reference escapes function scope
}
```

**경계 검사**는 범위를 벗어난 접근을 잡아냅니다:

```c
// C: 조용한 버퍼 오버플로
int arr[4];
arr[10] = 99;  // 정의되지 않은 동작, 진단 없음

// SafeC: 상수 인덱스에 대한 컴파일 타임 오류
int arr[4];
arr[10] = 99;  // ERROR: index 10 out of bounds for array of size 4
```

**대여 검사**는 별칭 위반을 방지합니다:

```c
int x = 10;
&stack int a = &x;       // 가변 대여
&stack int b = &x;       // ERROR: x is already mutably borrowed
```

**규율 있는 전처리기**는 유용한 매크로 패턴은 유지하면서 위험한 것들은 거부합니다:

```c
// 허용됨: 객체형 매크로, #ifdef, #pragma once
#define BUFFER_SIZE 1024
#ifdef DEBUG
    // ...
#endif

// 기본적으로 거부됨 (옵트인 "안전 모드"가 아니라 이것이 일반적인
// 컴파일 경로입니다; --compat-preprocessor가 이를 허용하는 옵트인 플래그입니다):
#define MAX(a, b) ((a) > (b) ? (a) : (b))  // ERROR unless --compat-preprocessor
// 대신 사용: generic<T> T max(T a, T b)
```

### 그대로 유지되는 것 {#what-stays-the-same}

- 구조체 레이아웃은 동일합니다
- 호출 규약은 동일합니다
- 포인터 연산은 동일하게 동작합니다
- `sizeof`, `alignof`는 동일하게 동작합니다
- C 헤더는 `#include`로 동작합니다
- 오브젝트 파일은 서로 링크됩니다

### 마이그레이션 경로 {#migration-path}

SafeC는 한 번에 파일 하나씩 도입할 수 있습니다. `.c`를 `.sc`로 이름을 바꾸고, 컴파일러가 보고하는 진단을 수정한 다음, 결과 오브젝트를 기존 C 오브젝트와 함께 링크하면 됩니다. 래퍼 계층도, 바인딩 생성기도 필요 없습니다.

## C++와 비교 {#compared-to-c-1}

C++와 SafeC는 비슷한 문제 — C에 안전성과 추상화를 추가하는 것 — 를 풀지만, 숨겨진 동작에 대해 근본적으로 다른 철학을 가지고 있습니다.

### C++의 숨겨진 비용 {#hidden-costs-in-c}

```cpp
// C++: 여기서 함수 호출이 몇 번 일어날까요?
std::string greet(std::string name) {
    return "Hello, " + name + "!";
}
// 답: 최소 3번의 할당, 2번의 복사 또는 이동,
// 3번의 소멸자 호출 — 소스에는 아무것도 보이지 않습니다
```

```c
// SafeC: 모든 연산이 눈에 보입니다
extern int snprintf(char* buf, long n, const char* fmt, ...);

int greet(const char* name, char* out, int outLen) {
    return snprintf(out, outLen, "Hello, %s!", name);
}
```

### 암묵적 특수 멤버 함수 없음 {#no-implicit-special-member-functions}

C++는 생성자, 소멸자, 복사/이동 연산자를 암묵적으로 생성합니다. SafeC는 그렇게 하지 않습니다. 구조체 생성은 항상 애그리게이트 초기화이거나 필드별 할당입니다. 정리는 항상 명시적입니다.

```c
struct Buffer {
    char* data;
    int size;
};

// 암묵적 생성자 없음 — 필드를 명시적으로 초기화합니다
Buffer b;
b.data = (char*)malloc(1024);
b.size = 1024;

// 암묵적 소멸자 없음 — 명시적으로 해제합니다
defer free(b.data);
```

### 예외 없음 {#no-exceptions}

C++ 예외는 숨겨진 제어 흐름 경로, 스택 언와인딩 메커니즘, 예측 불가능한 타이밍을 추가합니다. SafeC에는 예외 메커니즘이 없습니다. 에러는 값으로 반환됩니다:

```c
// 에러 처리는 항상 눈에 보입니다
int result = loadConfig(path, &config);
if (result != 0) {
    printf("failed to load config: error %d\n", result);
    return result;
}
```

### RTTI나 가상 디스패치 없음 {#no-rtti-or-virtual-dispatch}

SafeC에는 `virtual` 함수도, `dynamic_cast`도, vtable도 없습니다. 다형성은 제네릭(컴파일 타임에 단형화됨)이나 함수 포인터(명시적)를 통해 달성됩니다 — `fn_eval`(vtable 없는 타입별 메서드 결정)을 포함해 이 전체 그림, 그리고 실제로 필요할 때 명시적 런타임 디스패치를 구축하는 방법은 [다형성과 OOP](/ko/reference/polymorphism)를 참고하세요.

### SafeC가 C++에서 유지하는 것 {#what-safec-keeps-from-c}

- 구조체 메서드 (`this` 대신 `self` 사용)
- 연산자 오버로딩 (명시적, 옵트인)
- 제네릭 (템플릿이 아닌 단형화를 통해)

## Rust와 비교 {#compared-to-rust}

Rust와 SafeC는 가비지 컬렉션 없이 메모리 안전성을 달성한다는 목표를 공유하지만, 서로 다른 메커니즘을 통해 이를 이룹니다.

### 소유권 vs 리전 {#ownership-vs-regions}

Rust는 **소유권 이전**을 추적합니다: 값은 한 번에 하나의 소유자를 가지며, 소유권은 이동되거나 대여될 수 있습니다.

SafeC는 **리전 소속**을 추적합니다: 참조는 자신이 어느 메모리 리전을 가리키는지 알고 있으며, 컴파일러는 참조가 자신의 리전을 벗어나는 것을 방지합니다.

```rust
// Rust: 소유권 이전
fn process(data: Vec<u8>) {
    // data는 여기서 소유되며, 함수가 끝나면 drop됩니다
}

let v = vec![1, 2, 3];
process(v);
// v는 더 이상 접근할 수 없습니다 — 소유권이 이동되었습니다
```

```c
// SafeC: 리전 기반
region Pool { capacity: 4096 }

void process(&arena<Pool> int data) {
    // data는 Pool을 가리키며, 벗어날 수 없습니다
}

&arena<Pool> int v = new<Pool> int;
*v = 42;
process(v);
// v는 여전히 접근 가능합니다 — 소유권 이전이 없습니다
```

### 라이프타임 생략이 없는 이유 — 생략할 라이프타임 매개변수 자체가 없기 때문 {#no-lifetime-elision-because-there-s-no-lifetime-parameter-to-elide}

Rust는 `fn first(s: &str) -> &str`와 같은 흔한 경우에 라이프타임을 생략하며, 반환된 대여의 라이프타임이 `s`의 라이프타임에 묶여 있다고 추론합니다. SafeC는 생략할 대응물이 없는데, 애초에 라이프타임 *매개변수* 자체가 없기 때문입니다 — 리전은 호출 지점마다 추론되는 구간이 아니라 고정된, 닫힌 집합(`stack`, `heap`, `arena<R>`, `static`, 그리고 추적을 포기하는 리전 없는 `&T`/`?&T` 형태)입니다. 실질적인 결과는 "표기가 항상 명시적이다"보다 더 엄격합니다: **`&stack` 참조는 함수에서 절대 반환될 수 없습니다. 예외 없이** — 호출자 자신의 스택 참조를 그대로 돌려주는 경우조차도 마찬가지입니다. `longest`의 Rust 시그니처(`fn longest<'a>(a: &'a str, b: &'a str) -> &'a str`)는 `&stack` 매개변수에 대한 직접적인 SafeC 대응물이 없습니다. 반환 값을 묶을 라이프타임 변수 `'a`가 존재하지 않기 때문입니다 — 가장 가까운 형태는 리전 없는 `&str` 반환인데, 이는 특정 라이프타임을 추론하는 대신 아예 추적하지 않음으로써 질문 자체를 회피합니다.

```rust
// Rust: 반환된 대여의 라이프타임은 입력 중 하나에 묶입니다
fn longest<'a>(a: &'a str, b: &'a str) -> &'a str {
    if a.len() > b.len() { a } else { b }
}
```

함수가 호출자가 넘긴 데이터에 대한 참조를 정말로 되돌려줘야 할 때는, `&stack` 대신 `&arena<R>`(또는 `&static`)을 사용하세요 — 추적되는 것은 라이프타임 관계가 아니라 리전 소속이므로, 모두가 동일한 리전에 동의하는 한 arena 참조는 자유롭게 반환되고, 저장되고, 전달될 수 있습니다:

```c
region Pool { capacity: 8192 }

// &arena<Pool>은 반환될 수 있지만 &stack은 그럴 수 없습니다
&arena<Pool> int longest(&arena<Pool> int a, &arena<Pool> int b) {
    if (*a > *b) { return a; }
    return b;
}
```

이는 [리전 기반 안전 모델](/ko/guide/design#region-based-safety-model)이 설명하는 것과 동일한 트레이드오프입니다: SafeC는 arena 형태의 코드에 대해서는 Rust보다 더 관대하고(순환/공유되는 arena 참조를 둘러싼 대여 검사기와의 다툼이 없습니다), 스택 참조에 대해서는 더 엄격합니다(라이프타임 추론이 없으므로 반환된 스택 참조가 안전함을 증명할 방법이 없어, 표기를 요구하는 대신 아예 허용하지 않습니다).

### 대여 검사기와의 "다툼"이 없음 {#no-borrow-checker-fights}

Rust의 대여 검사기는 강력하지만 유효한 프로그램을 거부할 수 있습니다. 특히 자기 참조 구조체, 그래프 자료 구조, arena 할당 패턴을 포함하는 경우가 그렇습니다. SafeC의 리전 모델은 이런 패턴에 대해 더 관대합니다:

```c
// 같은 arena에 대한 여러 참조 — 소유권 충돌 없음
region Pool { capacity: 8192 }

&arena<Pool> Node a = new<Pool> Node;
&arena<Pool> Node b = new<Pool> Node;
a.next = b;  // 두 참조가 공존합니다 — arena가 유효성을 보장합니다
b.prev = a;  // 동일한 arena 안에서는 순환 참조도 괜찮습니다
```

### 기본적으로 C ABI {#c-abi-by-default}

Rust는 C 상호운용을 위해 `extern "C"`와 `#[repr(C)]` 표기가 필요합니다. SafeC는 기본적으로 C ABI를 사용합니다:

```rust
// Rust: 명시적인 C 상호운용 표기
#[repr(C)]
struct Point {
    x: f64,
    y: f64,
}

extern "C" {
    fn c_process_point(p: *const Point);
}
```

```c
// SafeC: 기본적으로 C ABI, 표기가 필요 없음
struct Point {
    double x;
    double y;
};

extern void c_process_point(const Point* p);
```

### 서로 다른 트레이드오프 {#different-trade-offs}

| 측면 | Rust | SafeC |
|---|---|---|
| 학습 곡선 | 가파름 (소유권, 라이프타임, 트레이트) | 완만함 (C + 리전 표기) |
| 생태계 | 큼 (crates.io) | 작음 (성장 중) |
| 안전 모델 | 소유권 + 대여 검사기 | 리전 타입 + 이스케이프 분석 |
| 런타임 | 최소한 (패닉 핸들러, 할당자) | 없음 (`--freestanding`) |
| C 상호운용 | FFI 계층 필요 | 네이티브, 제로 코스트 |
| 자기 참조 구조체 | 어려움 (Pin, unsafe) | 자연스러움 (arena 리전) |
| 비동기 | 내장 (async/await) | 내장되지 않음 (pthreads, 채널) |

## Zig와 비교 {#compared-to-zig}

Zig와 SafeC는 숨겨진 비용 없음과 명시적 제어라는 철학을 공유하지만, 메모리 모델과 컴파일 타임 시스템에서 차이가 있습니다.

### 할당자 vs 리전 {#allocators-vs-regions}

Zig는 할당자를 함수 매개변수로 전달합니다. 호출자가 메모리가 어디서 오는지 결정합니다. SafeC는 메모리 출처를 타입 시스템 안에 인코딩하는 리전 타입을 사용합니다.

```zig
// Zig: 매개변수로 전달되는 할당자
fn createBuffer(allocator: std.mem.Allocator, size: usize) ![]u8 {
    return allocator.alloc(u8, size);
}
```

```c
// SafeC: 타입에 인코딩된 리전
region Audio { capacity: 65536 }

&arena<Audio> float createBuffer() {
    return new<Audio> float;  // 리전이 타입의 일부입니다
}
```

Zig의 접근 방식은 더 유연합니다(어떤 할당자든 갈아 끼울 수 있습니다). SafeC의 접근 방식은 컴파일 타임 안전 보장을 제공합니다(컴파일러가 리전 라이프타임을 검증합니다).

### comptime vs consteval {#comptime-vs-consteval}

두 언어 모두 컴파일 타임 계산을 강조하지만, 메커니즘은 다릅니다:

```zig
// Zig: comptime
fn fibonacci(comptime n: u32) u32 {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

const fib10 = fibonacci(10);
```

```c
// SafeC: consteval
consteval int fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

const int fib10 = fibonacci(10);

static_assert(fib10 == 55, "fibonacci broken");
```

Zig의 `comptime`은 더 범용적입니다(타입을 다루고 코드를 생성할 수 있습니다). SafeC의 `consteval`은 더 초점이 맞춰져 있습니다(컴파일 타임 값 계산과 분기 제거).

### 전처리기 {#preprocessor}

Zig에는 전처리기가 없습니다. SafeC는 안전한 매크로는 허용하고 위험한 패턴은 거부하는 규율 있는 전처리기 부분집합을 가지고 있습니다:

```c
// SafeC 전처리기: 안전한 부분집합
#define VERSION 3
#define BUFFER_SIZE 1024
#ifdef PLATFORM_LINUX
    // 플랫폼별 코드
#endif
#pragma once  // 헤더 가드

// 안전 모드에서는 거부됨:
// #define SQUARE(x) ((x) * (x))  // 함수형 매크로
```

### 에러 처리 {#error-handling}

Zig는 에러 유니온과 `try`/`catch`를 사용합니다. SafeC는 반환 값을 사용합니다:

```zig
// Zig
fn readFile(path: []const u8) ![]u8 {
    const file = try std.fs.openFile(path, .{});
    defer file.close();
    return file.readToEndAlloc(allocator, max_size);
}
```

```c
// SafeC
int readFile(const char* path, char* buf, int bufLen) {
    int fd = open(path, 0);
    if (fd < 0) return -1;
    defer close(fd);
    return (int)read(fd, buf, bufLen);  // read()는 long을 반환합니다 — 암묵적 축소 변환 없음
}
```

## SafeC를 선택해야 할 때 {#when-to-choose-safec}

다음과 같은 경우 SafeC는 좋은 선택입니다:

- **이미 C를 사용 중이고** 새 언어로 다시 작성하지 않고 안전성을 추가하고 싶을 때
- **C ABI 호환성이 중요할 때** — 코드가 기존 C 프로젝트, 커널, 펌웨어에 링크될 때
- **결정론적 동작이 필요할 때** — 실시간 오디오, 임베디드 시스템, 안전이 중요한 소프트웨어
- **숨겨진 비용 없음을 원할 때** — 모든 할당, 모든 연산이 소스에 드러나야 할 때
- **베어메탈 지원이 필요할 때** — 런타임도, 할당자도, 표준 라이브러리 가정도 없이
- **점진적 도입이 중요할 때** — 프로젝트 전체가 아니라 한 번에 파일 하나씩 다시 작성

다음과 같은 경우 SafeC는 최선의 선택이 아닐 수 있습니다:

- 성숙한 라이브러리 생태계가 필요한 경우(Rust의 crates.io가 훨씬 더 성숙합니다)
- 자동 메모리 관리(소유권 이전, RAII)를 선호하는 경우
- 고동시성 네트워크 서비스를 위한 async/await가 필요한 경우
- 팀이 이미 Rust나 Zig에 생산적인 경우

## 요약 {#summary}

| | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| **철학** | 프로그래머를 신뢰 | 추상화 | 안전 우선 | 단순성 | 안전성 + 투명성 |
| **메모리** | 수동 | RAII | 소유권 | 할당자 | 리전 |
| **비용 모델** | 명시적 | 숨겨짐 | 대체로 명시적 | 명시적 | 명시적 |
| **C 상호운용** | 네이티브 | 네이티브 | FFI 계층 | 네이티브 | 네이티브 |
| **C로부터의 학습 곡선** | N/A | 완만함 | 높음 | 완만함 | 낮음 |
| **안전성** | 없음 | 부분적 | 강력함 | 부분적 | 강력함 |

SafeC의 위치: **Rust의 안전성, C의 투명성, 어느 쪽도 바꿀 필요 없는 상호운용성**.

## 다음 단계 {#next-steps}

- [타입](/ko/reference/types) — SafeC의 타입 시스템을 배웁니다
- [메모리와 리전](/ko/reference/memory) — 리전 기반 메모리 안전성을 깊이 있게 살펴봅니다
- [C 상호운용](/ko/reference/ffi) — FFI 정책과 상호운용 패턴
