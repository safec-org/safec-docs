# 결정론적 얼로케이터

`std/alloc/`는 하나의 고정 버퍼를 관리하는 네 가지 얼로케이터를 제공합니다 — 버퍼는 여러분이 직접 공급하거나(`X::over`, 어떤 리전이든 가능: static, 스택, 아레나) 얼로케이터가 직접 할당합니다(`X::new`). 어느 것도 생성 이후에는 `malloc`을 호출하지 않습니다. 생성은 타입의 [정적 메서드](/ko/reference/functions#static-methods)이며, `namespace std` 바깥에서는 `std::BumpAllocator::new(...)`처럼 호출합니다.

```c
#include <std/alloc/bump.h>
#include <std/alloc/slab.h>
#include <std/alloc/pool.h>
#include <std/alloc/tlsf.h>
#include <std/alloc/allocator.h>   // the traits, alloc_t and CountingAllocator (each header above includes it)
```

## 비교 {#comparison}

| 얼로케이터 | 할당 | 해제 | 적합한 용도 |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | 리셋 전용 | 프레임 단위 아레나, 스크래치 버퍼 |
| `SlabAllocator` | O(1) | O(1) | 같은 크기의 객체 다수 (패킷, 노드) |
| `PoolAllocator` | O(블록 수), first fit | O(블록 수), 병합 | 크기가 다양한 작은 힙 |
| `TlsfAllocator` | O(1) | O(1) | 범용 실시간 힙 |

## 트레이트로 본 얼로케이터 {#allocator-traits}

`std/alloc/allocator.h`는 얼로케이터가 할 수 있는 일을 기능 하나당 하나씩 작은 [트레이트](/ko/reference/generics#composing-traits)로 이름 붙이고, 제네릭 코드가 요구하는 두 가지 역할로 합성합니다. 제네릭 코드는 자신이 쓰는 가장 작은 트레이트로 바운드되므로, 그 기능을 가진 모든 얼로케이터와 함께 동작합니다. 각 호출은 컴파일 타임에 결정되며 런타임 디스패치는 없습니다.

```c
trait Allocate {
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // null when it can't
}
trait Deallocate {
    void dealloc(?&heap unsigned char ptr);                                // null is a no-op
}
trait Resettable {
    void reset();                                                 // frees every block at once
}
trait Allocator      : Allocate + Deallocate {}
trait ArenaAllocator : Allocate + Resettable {}
```

`alloc`은 `align`(2의 거듭제곱, `0`은 `1`로 취급)에 정렬된 초기화되지 않은 `size`바이트를 반환하며, 얼로케이터의 공간이 부족하거나 그 크기나 정렬을 제공하지 않으면 `null`을 반환합니다. `dealloc`은 `alloc`이 반환한 블록을 돌려주며, 블록 하나를 해제할 수 없는 얼로케이터(bump)에서는 아무 일도 하지 않습니다. `reset`은 모든 블록을 한 번에 해제하며, 그 전에 나눠 준 블록은 무효가 됩니다.

| 트레이트 | 구성 | 만족하는 타입 |
|---|---|---|
| `Allocate` | `alloc(size, align)` | 아래의 모든 얼로케이터 |
| `Deallocate` | `dealloc(ptr)` | 아래의 모든 얼로케이터 |
| `Resettable` | `reset()` | `BumpAllocator`, `PoolAllocator`, `SlabAllocator`, `TlsfAllocator` |
| `Allocator` | `Allocate + Deallocate` | `BumpAllocator`(`dealloc`은 no-op), `PoolAllocator`, `SlabAllocator`, `TlsfAllocator`, [`SecureAllocator`](/ko/stdlib/crypto#secureallocator), `CountingAllocator<A>` |
| `ArenaAllocator` | `Allocate + Resettable` | `BumpAllocator`, `PoolAllocator`, `SlabAllocator`, `TlsfAllocator` |

| 함수 / 구조체 | 바운드 | 하는 일 |
|---|---|---|
| [`alloc_t(&a)`, `alloc_array_t(&a, n)`](#typed) | `Allocate` | `T` 하나 / `T` `n`개를 `T`에 맞게 정렬해 할당 |
| [`dealloc_t(&a, obj)`](#typed) | `Deallocate` | `alloc_t` / `alloc_array_t`로 받은 블록을 반환 |
| [`CountingAllocator<A>`](#counting-allocator) | `Allocator` | 감싼 얼로케이터에 대한 호출을 셈. 그 자신도 `Allocator` |

각 얼로케이터가 `align`과 `dealloc`을 처리하는 방식:

| 얼로케이터 | 지원하는 `align` | `dealloc` |
|-----------|------------------|-----------|
| `BumpAllocator` | 모든 2의 거듭제곱 (아레나에 패딩을 넣어 주소를 정렬) | no-op — `reset()`이 전부 해제 |
| `SlabAllocator` | `obj_size`를 나누는 가장 큰 2의 거듭제곱까지, 최대 8 (`max_align()`). `size > obj_size`여도 `null` | 슬롯을 반환 |
| `PoolAllocator` | 8까지 | 인접한 빈 블록과 병합 |
| `TlsfAllocator` | 8까지 | 물리적으로 인접한 블록과 병합 |

그 범위를 넘으면 `alloc`은 잘못 정렬된 블록 대신 `null`을 반환합니다. pool, slab, TLSF의 한계는 8바이트 정렬된 버퍼를 전제로 합니다: `X::new`에서는 항상 성립하며, `X::over`에서는 여러분이 넘기는 버퍼가 그래야 합니다.

아래 예제는 리스트 빌더를 `Allocate`에 대해, 프레임 단위 처리를 `ArenaAllocator`에 대해 한 번씩만 작성하고, 둘 다 bump 아레나와 pool에서 실행한 다음, pool 위의 작업량을 셉니다:

```c
#include <std/alloc/pool.h>
#include <std/alloc/bump.h>
#include <std/io.h>

struct Node { int value; ?&heap Node next; };

// Builds a list of n nodes from any allocator that can allocate.
generic<A: Allocate>
?&heap Node build_list(&A a, int n) {
    ?&heap Node head = null;
    for (int i = 0; i < n; i = i + 1) {
        ?&heap Node nd = std::alloc_t(a);
        match (nd) {
            case some(x): { x.value = i; x.next = head; head = x; }
            case null:    { return head; }
        }
    }
    return head;
}

// One pass of work per frame: allocate freely, then free it all at once.
generic<A: ArenaAllocator>
int frame(&A a, int n) {
    ?&heap Node l = build_list(a, n);
    int sum = 0;
    while (1) {
        match (l) {
            case some(x): { sum = sum + x.value; l = x.next; }
            case null:    { a.reset(); return sum; }
        }
    }
    return sum;
}

int main() {
    struct BumpAllocator bump = std::BumpAllocator::new(4096UL);
    struct PoolAllocator pool = std::PoolAllocator::new(4096UL);
    int a = frame(&bump, 10);            // A = BumpAllocator
    int b = frame(&pool, 10);            // A = PoolAllocator

    // Count what a workload does on the pool.
    struct CountingAllocator<PoolAllocator> c = std::CountingAllocator::over(&pool);
    ?&heap Node one = std::alloc_t(&c);
    match (one) { case some(x): std::dealloc_t(&c, x); case null: { } }
    std::println_int((long long)c.allocs);   // 1
    std::println_int((long long)c.live());   // 0

    bump.destroy();
    pool.destroy();
    return (a == 45 && b == 45 && c.live() == 0UL) ? 0 : 1;
}
```

### 타입 지정 할당 {#typed}

바이트 수준 메서드보다 **타입 지정 헬퍼**를 우선 사용합니다. `alloc_t`와 `alloc_array_t`는 `Allocate`만, `dealloc_t`는 `Deallocate`만 요구합니다. `T`는 대입받는 변수에서 오며, 결과는 nullable 참조(얼로케이터가 할당할 수 없으면 `null`)이므로 사용 전에 좁혀야 합니다.

```c
generic<T, A: Allocate>   ?&heap T alloc_t(&A a);                         // one T, aligned for T
generic<T, A: Allocate>   ?&heap T alloc_array_t(&A a, unsigned long n);  // n Ts
generic<T, A: Deallocate> void     dealloc_t(&A a, &heap T obj);
```

```c
struct PoolAllocator pool = std::PoolAllocator::new(4096UL);
?&heap Packet p = std::alloc_t(&pool);                 // T = Packet
?&heap float xs = std::alloc_array_t(&pool, 64UL);     // 64 floats
match (p) { case some(x): std::dealloc_t(&pool, x); case null: { } }
```

`alloc_array_t`는 `n * sizeof(T)`가 오버플로하면 진단 메시지와 함께 중단합니다(`checked_mul_size`). 메모리는 초기화되지 않습니다. 이 헬퍼들은 얼로케이터를 받지 않고 범용 힙에서 할당하는 [`std::alloc` / `std::dealloc`](/ko/stdlib/mem)과는 별개입니다.

바이트 수준 메서드는 사용자 정의 컨테이너의 저장 공간처럼 정말로 타입 없는 바이트를 다루는 코드를 위해 남아 있습니다.

`destroy()`는 얼로케이터가 버퍼를 직접 할당한 경우(`X::new`)에만 버퍼를 해제합니다. `X::over`의 경우에는 버퍼를 잊기만 하며, 버퍼는 여전히 여러분의 것입니다.

### CountingAllocator {#counting-allocator}

임의의 `Allocator`를 빌려 모든 호출을 그대로 전달하면서 횟수를 세는 [바운드가 있는 제네릭 구조체](/ko/reference/generics#bounded-generic-structs)입니다. 그 자신도 `Allocator`이므로 감싼 얼로케이터가 들어갈 자리라면 어디든 — 다른 `CountingAllocator` 바깥까지 포함해 — 들어갈 수 있습니다. 테스트의 누수 검사나, 실제 작업량으로 아레나나 pool의 크기를 정할 때 사용합니다.

```c
generic<A: Allocator>
struct CountingAllocator {
    &A            inner;      // the allocator doing the work (not owned)
    unsigned long allocs;     // alloc calls that returned a block
    unsigned long frees;      // dealloc calls with a non-null block
    unsigned long failures;   // alloc calls that returned null
    unsigned long bytes;      // bytes requested by the successful allocs

    static struct CountingAllocator<A> over(&A inner);
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap unsigned char ptr);
    unsigned long live() const;   // blocks handed out and not given back yet
};
```

```c
struct PoolAllocator pool = std::PoolAllocator::new(65536UL);
struct CountingAllocator<PoolAllocator> c = std::CountingAllocator::over(&pool);
run_workload(&c);                       // any generic<A: Allocator> code
if (c.live() != 0UL) { /* blocks never given back */ }
```

`CountingAllocator`에는 `reset`이 없으므로 `ArenaAllocator`가 아닙니다: 감싼 얼로케이터를 직접 리셋합니다.

### 직접 만드는 얼로케이터 {#custom}

트레이트는 구조적입니다: 타입은 메서드를 갖추는 것만으로 트레이트를 따르며, 따로 선언할 것은 없습니다. 트레이트 시그니처의 `alloc`과 `dealloc`을 가진 타입은 `Allocator`이므로 `alloc_t`, `alloc_array_t`, `dealloc_t`, `CountingAllocator`와 모든 `generic<A: Allocator>` 코드가 받아들이며, `reset()`을 추가하면 `ArenaAllocator`도 됩니다. 예를 들어 살아 있는 블록 수를 세는 범용 힙은 다음과 같습니다:

```c
#include <std/alloc/allocator.h>

struct CountingHeap {
    unsigned long live;
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void        dealloc(?&heap unsigned char ptr);
};

?&heap unsigned char CountingHeap::alloc(unsigned long size, unsigned long align) {
    if ((align & (align - 1UL)) != 0UL || align > 16UL) return null;   // std::alloc gives 16
    ?&heap unsigned char p = std::alloc(size);
    if (!p.is_null()) self.live = self.live + 1UL;
    return p;
}

void CountingHeap::dealloc(?&heap unsigned char ptr) {
    if (ptr.is_null()) return;
    std::dealloc(ptr);
    self.live = self.live - 1UL;
}

struct Point { int x; int y; };

int main() {
    struct CountingHeap h;
    h.live = 0UL;
    ?&heap Point p = std::alloc_t(&h);                 // through the trait
    match (p) { case some(x): std::dealloc_t(&h, x); case null: { } }
    return (int)h.live;                                // 0
}
```

---

## BumpAllocator {#bump}

선형 얼로케이터입니다: 할당은 오프셋을 전진시키고, 아레나 전체는 `reset()`으로 한 번에 회수됩니다. 객체별 해제는 없습니다.

```c
struct BumpAllocator {
    unsigned long used;
    unsigned long cap;

    static struct BumpAllocator over([]unsigned char buffer);  // over your buffer
    static struct BumpAllocator new(unsigned long cap);                          // allocates cap bytes

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if full
    void          dealloc(?&heap unsigned char ptr);   // no-op (one block can't be freed)
    void          reset();                    // every earlier allocation becomes invalid
    unsigned long remaining() const;
    void          destroy() consume;
};
```

```c
#include <std/alloc/bump.h>
#include <std/io.h>

struct Particle { float x; float y; int life; };

static unsigned char g_frame[4096];          // per-frame scratch, no heap

int main() {
    struct BumpAllocator a = std::BumpAllocator::over(g_frame[..]);
    ?&heap Particle p = std::alloc_t(&a);
    ?&heap int counts = std::alloc_array_t(&a, 16UL);
    match (p) {
        case some(x): { x.x = 1.0f; x.y = 2.0f; x.life = 30; }
        case null:    std::println("arena full");
    }
    std::print("remaining: ");
    std::println_int((long long)a.remaining());
    a.reset();                               // next frame starts empty
    int rc = counts.is_null() ? 1 : 0;
    a.destroy();                             // BumpAllocator는 Drop 타입입니다
    return rc;
}
```

---

## SlabAllocator {#slab}

버퍼 안에 내장된 프리 리스트에서 고정 크기 객체를 꺼냅니다: O(1) 할당과 해제. 힙 기반 슬랩(`SlabAllocator::new`)은 모든 객체에 태그를 붙여 이중 해제나 외부 포인터를 만나면 중단(abort)합니다.

```c
struct SlabAllocator {
    unsigned long obj_size;

    // over your buffer of obj_size * count bytes (no double-free tags)
    static struct SlabAllocator over([]unsigned char buffer, unsigned long obj_size);
    static struct SlabAllocator new(unsigned long obj_size, unsigned long count);

    // byte level: one object for size <= obj_size bytes; null if full, size > obj_size
    // or align > max_align()
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // frees every slot at once
    unsigned long available() const;         // free slots
    unsigned long max_align() const;         // largest power of two dividing obj_size, <= 8
    void          destroy() consume;
};
```

`SlabAllocator::over`는 `obj_size * count`바이트 버퍼를 받습니다. 슬롯은 `obj_size`를 8의 배수로 올린 크기이고(빈 슬롯이 free list 링크를 담으므로 최소 8), 8바이트 정렬에서 시작합니다. 그래서 이 올림이 버퍼에 다 들어가지 않으면 `cap`이 `count`보다 작아질 수 있습니다. `SlabAllocator::new`는 모든 슬롯이 들어갈 공간을 할당합니다.

```c
#include <std/alloc/slab.h>
#include <std/io.h>

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    struct SlabAllocator sa = std::SlabAllocator::new(sizeof(struct Packet), 32UL);
    ?&heap Packet p1 = std::alloc_t(&sa);
    ?&heap Packet p2 = std::alloc_t(&sa);
    std::println_int((long long)sa.available());   // 30
    match (p2) {
        case some(p): std::dealloc_t(&sa, p);
        case null: { }
    }
    std::println_int((long long)sa.available());   // 31
    sa.destroy();
    return p1.is_null() ? 1 : 0;
}
```

---

## PoolAllocator {#pool}

가변 크기 first-fit 얼로케이터입니다: 각 블록은 작은 헤더를 가지며, 해제 시 인접한 빈 블록을 병합합니다. 단순하고 작지만 할당과 해제가 블록 목록을 순회합니다 — O(1)이 필요하면 TLSF를 씁니다.

```c
struct PoolAllocator {
    unsigned long cap;

    static struct PoolAllocator over([]unsigned char buffer);  // over your buffer (8-aligned)
    static struct PoolAllocator new(unsigned long cap);

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if no block fits or align > 8
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // the whole buffer is one free block again
    unsigned long available() const;         // total free bytes
    void          destroy() consume;
};
```

---

## TlsfAllocator {#tlsf}

Two-Level Segregated Fit: 크기 클래스에 대한 2단계 비트맵이 맞는 빈 블록을 O(1)에 찾고, 해제 시 물리적으로 인접한 블록과 O(1)에 병합합니다. 프리스탠딩 타깃에서 `std/heap.h`가 사용하는 것이 이것입니다.

```c
struct TlsfAllocator {
    unsigned long cap;

    static struct TlsfAllocator over([]unsigned char buffer);  // over your buffer
    static struct TlsfAllocator new(unsigned long cap);

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if exhausted or align > 8
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // the whole buffer is one free block again
    void          destroy() consume;
};
```

```c
#include <std/alloc/tlsf.h>

int main() {
    struct TlsfAllocator ta = std::TlsfAllocator::new(65536UL);   // 64 KiB
    ?&heap double a = std::alloc_array_t(&ta, 100UL);
    ?&heap double b = std::alloc_array_t(&ta, 200UL);
    match (b) { case some(x): std::dealloc_t(&ta, x); case null: { } }   // coalesced
    ?&heap double c = std::alloc_array_t(&ta, 180UL);                    // reuses b's space
    match (a) { case some(x): std::dealloc_t(&ta, x); case null: { } }
    match (c) { case some(x): std::dealloc_t(&ta, x); case null: { } }
    ta.destroy();
    return 0;
}
```

### TLSF를 사용해야 할 때 {#when-to-use-tlsf}

- 오디오, 모터 제어처럼 하드 리얼타임 제약이 있는 펌웨어
- `malloc`의 응답 시간이 반드시 유한해야 하는 모든 컨텍스트
- 힙 단편화가 우려되는 장시간 실행 임베디드 애플리케이션

---

인덱스 크기: `TLSF_FL_MAX`(1단계 클래스 32개) × `TLSF_SL_COUNT`(2단계 분할 16개).

## 얼로케이터 선택하기 {#choosing-an-allocator}

```
프레임 단위 스크래치 작업?        → BumpAllocator (매 프레임마다 리셋)
동일한 객체가 다수?                → SlabAllocator
고정 블록, 혼합된 내용?            → PoolAllocator
가변 크기, 실시간 보장 필요?       → TlsfAllocator
```

어느 얼로케이터와도 동작해야 하는 코드는 `generic<A: Allocator>`(또는 `reset()`으로 해제한다면 `ArenaAllocator`)를 받으므로, 그 코드를 건드리지 않고도 선택을 바꿀 수 있습니다.
