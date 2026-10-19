# 결정론적 얼로케이터

`std/alloc/`는 하나의 고정 버퍼를 관리하는 네 가지 얼로케이터를 제공합니다 — 버퍼는 여러분이 직접 공급하거나(`*_init`, 어떤 리전이든 가능: static, 스택, 아레나) 얼로케이터가 직접 할당합니다(`*_new`). 어느 것도 생성 이후에는 `malloc`을 호출하지 않습니다.

```c
#include <std/alloc/bump.h>
#include <std/alloc/slab.h>
#include <std/alloc/pool.h>
#include <std/alloc/tlsf.h>
```

## 비교 {#comparison}

| 얼로케이터 | 할당 | 해제 | 적합한 용도 |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | 리셋 전용 | 프레임 단위 아레나, 스크래치 버퍼 |
| `SlabAllocator` | O(1) | O(1) | 같은 크기의 객체 다수 (패킷, 노드) |
| `PoolAllocator` | O(블록 수), first fit | O(블록 수), 병합 | 크기가 다양한 작은 힙 |
| `TlsfAllocator` | O(1) | O(1) | 범용 실시간 힙 |

## 타입 지정 할당 {#typed}

모든 얼로케이터에는 **타입 지정 계층**이 있습니다 — 바이트 수준 메서드보다 이것을 우선 사용하세요. `T`는 대입받는 변수에서 오며, 결과는 nullable 참조(얼로케이터가 소진되면 `null`)이므로 사용 전에 좁혀야 합니다.

```c
generic<T> ?&heap T bump_alloc_t(&BumpAllocator a);                     // aligned for T
generic<T> ?&heap T bump_alloc_array_t(&BumpAllocator a, unsigned long n);
generic<T> ?&heap T slab_alloc_t(&SlabAllocator s);                     // null if T > obj_size
generic<T> void     slab_free_t(&SlabAllocator s, &heap T obj);
generic<T> ?&heap T pool_alloc_t(&PoolAllocator p);
generic<T> void     pool_free_t(&PoolAllocator p, &heap T obj);
generic<T> ?&heap T tlsf_alloc_t(&TlsfAllocator t);
generic<T> ?&heap T tlsf_alloc_array_t(&TlsfAllocator t, unsigned long n);
generic<T> void     tlsf_free_t(&TlsfAllocator t, &heap T obj);
```

바이트 수준 메서드(`alloc(size)`, `dealloc(ptr)`, …)는 사용자 정의 컨테이너의 저장 공간처럼 정말로 타입 없는 바이트를 다루는 코드를 위해 남아 있습니다.

`destroy()`는 얼로케이터가 버퍼를 직접 할당한 경우(`*_new`)에만 버퍼를 해제합니다. `*_init`의 경우에는 버퍼를 잊기만 하며, 버퍼는 여전히 여러분의 것입니다.

---

## BumpAllocator {#bump}

선형 얼로케이터입니다: 할당은 오프셋을 전진시키고, 아레나 전체는 `reset()`으로 한 번에 회수됩니다. 객체별 해제는 없습니다.

```c
struct BumpAllocator {
    unsigned long used;
    unsigned long cap;

    &heap void    alloc(unsigned long size, unsigned long align);   // byte level; null if full
    void          reset();                    // every earlier allocation becomes invalid
    unsigned long remaining() const;
    void          destroy();
};

struct BumpAllocator bump_init(&unsigned char buffer, unsigned long cap);
struct BumpAllocator bump_new(unsigned long cap);
```

```c
#include <std/alloc/bump.h>
#include <std/io.h>

struct Particle { float x; float y; int life; };

static unsigned char g_frame[4096];          // per-frame scratch, no heap

int main() {
    struct BumpAllocator a = std::bump_init(&g_frame[0], 4096UL);
    ?&heap Particle p = std::bump_alloc_t(&a);
    ?&heap int counts = std::bump_alloc_array_t(&a, 16UL);
    match (p) {
        case some(x): { x.x = 1.0f; x.y = 2.0f; x.life = 30; }
        case null:    std::println("arena full");
    }
    std::print("remaining: ");
    std::println_int((long long)a.remaining());
    a.reset();                               // next frame starts empty
    return counts.is_null() ? 1 : 0;
}
```

---

## SlabAllocator {#slab}

버퍼 안에 내장된 프리 리스트에서 고정 크기 객체를 꺼냅니다: O(1) 할당과 해제. 힙 기반 슬랩(`slab_new`)은 모든 객체에 태그를 붙여 이중 해제나 외부 포인터를 만나면 중단(abort)합니다.

```c
struct SlabAllocator {
    unsigned long obj_size;
    &heap void    alloc();                    // byte level; null if full
    void          dealloc(void* ptr);
    unsigned long available() const;          // free slots
    void          destroy();
};

struct SlabAllocator slab_init(&unsigned char buffer, unsigned long obj_size, unsigned long count);
struct SlabAllocator slab_new(unsigned long obj_size, unsigned long count);
```

```c
#include <std/alloc/slab.h>
#include <std/io.h>

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    struct SlabAllocator sa = std::slab_new(sizeof(struct Packet), 32UL);
    ?&heap Packet p1 = std::slab_alloc_t(&sa);
    ?&heap Packet p2 = std::slab_alloc_t(&sa);
    std::println_int((long long)sa.available());   // 30
    match (p2) {
        case some(p): std::slab_free_t(&sa, p);
        case null: { }
    }
    std::println_int((long long)sa.available());   // 31
    sa.destroy();
    return p1.is_null() ? 1 : 0;
}
```

---

## PoolAllocator {#pool}

가변 크기 first-fit 얼로케이터입니다: 각 블록은 작은 헤더를 가지며, 해제 시 인접한 빈 블록을 병합합니다. 단순하고 작지만 할당과 해제가 블록 목록을 순회합니다 — O(1)이 필요하면 TLSF를 쓰세요.

```c
struct PoolAllocator {
    unsigned long cap;
    &heap void    alloc(unsigned long size);  // byte level; null if no block fits
    void          dealloc(void* ptr);
    unsigned long available() const;         // total free bytes
    void          destroy();
};

struct PoolAllocator pool_init(&unsigned char buffer, unsigned long cap);
struct PoolAllocator pool_new(unsigned long cap);
```

---

## TlsfAllocator {#tlsf}

Two-Level Segregated Fit: 크기 클래스에 대한 2단계 비트맵이 맞는 빈 블록을 O(1)에 찾고, 해제 시 물리적으로 인접한 블록과 O(1)에 병합합니다. 프리스탠딩 타깃에서 `std/heap.h`가 사용하는 것이 이것입니다.

```c
struct TlsfAllocator {
    unsigned long cap;
    &heap void    alloc(unsigned long size);  // byte level; null if exhausted
    void          free(&heap void ptr);
    void          destroy();
};

struct TlsfAllocator tlsf_init(&unsigned char buffer, unsigned long cap);
struct TlsfAllocator tlsf_new(unsigned long cap);
```

```c
#include <std/alloc/tlsf.h>

int main() {
    struct TlsfAllocator ta = std::tlsf_new(65536UL);   // 64 KiB
    ?&heap double a = std::tlsf_alloc_array_t(&ta, 100UL);
    ?&heap double b = std::tlsf_alloc_array_t(&ta, 200UL);
    match (b) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }   // coalesced
    ?&heap double c = std::tlsf_alloc_array_t(&ta, 180UL);                  // reuses b's space
    match (a) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }
    match (c) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }
    ta.destroy();
    return 0;
}
```

### TLSF를 사용해야 할 때 {#when-to-use-tlsf}

- 오디오, 모터 제어처럼 하드 리얼타임 제약이 있는 펌웨어
- `malloc`의 응답 시간이 반드시 유한해야 하는 모든 컨텍스트
- 힙 단편화가 우려되는 장시간 실행 임베디드 애플리케이션

---

## 얼로케이터 선택하기 {#choosing-an-allocator}

```
프레임 단위 스크래치 작업?        → BumpAllocator (매 프레임마다 리셋)
동일한 객체가 다수?                → SlabAllocator
고정 블록, 혼합된 내용?            → PoolAllocator
가변 크기, 실시간 보장 필요?       → TlsfAllocator
```
