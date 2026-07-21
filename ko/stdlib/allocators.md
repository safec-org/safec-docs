# 결정론적 얼로케이터

SafeC는 `std/alloc/`에 네 가지 결정론적 얼로케이터를 제공합니다. 각각 최악의 경우에도 O(1) 할당 및 해제를 보장하며, 예상치 못한 단편화가 없고, 숨겨진 malloc 호출도 없습니다.

```c
#include "alloc/bump.h"
#include "alloc/slab.h"
#include "alloc/pool.h"
#include "alloc/tlsf.h"
// 또는 네 가지 모두를 한 번에 가져오려면:
#include "prelude.h"
```

## 비교 {#comparison}

| 얼로케이터 | 할당 | 해제 | 적합한 용도 |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | 리셋 전용 | 프레임 단위 아레나, 스크래치 버퍼 |
| `SlabAllocator` | O(1) | O(1) | 고정 크기 객체 다수 (예: 노드, 패킷) |
| `PoolAllocator` | O(1) | O(1) | 다양한 내용을 담는 고정 크기 블록 |
| `TlsfAllocator` | O(1) | O(1) | 범용 실시간 힙 |

---

## BumpAllocator {#bumpallocator}

선형(범프 포인터) 얼로케이터입니다. 할당은 포인터를 전진시키며, 개별 객체 해제는 지원하지 않습니다 — 아레나 전체를 리셋해야 합니다.

### 구조체 {#struct}

```c
struct BumpAllocator {
    void*         base;
    unsigned long used;
    unsigned long cap;

    void*         alloc(unsigned long size, unsigned long align);
    void          reset();
    unsigned long remaining() const;
    void          destroy();
}
```

### 생성자 {#constructors}

```c
// 호출자가 제공한 버퍼를 사용 (힙 없음)
BumpAllocator bump_init(void* buffer, unsigned long cap);

// `cap` 바이트 크기의 백킹 버퍼를 힙에 할당
BumpAllocator bump_new(unsigned long cap);
```

### 메서드 {#methods}

| 메서드 | 설명 |
|--------|-------------|
| `alloc(size, align)` | 정렬된 포인터를 반환하고 `used`를 전진시킵니다. 꽉 찼으면 NULL을 반환합니다. |
| `reset()` | `used = 0`으로 설정합니다. 이전에 할당된 모든 메모리는 무효화됩니다. |
| `remaining() const` | `cap - used`를 반환합니다. |
| `destroy()` | 힙에 할당된 경우 백킹 버퍼를 해제합니다. |

### 예제 {#example}

```c
#include "alloc/bump.h"
#include "io.h"

int main() {
    unsigned char buf[4096];
    BumpAllocator a = bump_init(buf, sizeof(buf));

    int* x = a.alloc(sizeof(int), 4);
    int* y = a.alloc(sizeof(int), 4);
    *x = 10;
    *y = 20;

    print("remaining: ");
    println_int(a.remaining());  // 4088

    a.reset();  // 전부 무효화; 다음 프레임을 위한 준비
    return 0;
}
```

---

## SlabAllocator {#slaballocator}

고정 크기 객체를 위한 프리리스트 기반 슬랩 얼로케이터입니다. 사전 할당된 풀을 기반으로 동작하며, 개별 할당/해제는 풀에 내장된 연결 프리리스트를 통해 O(1)로 이루어집니다.

### 구조체 {#struct-1}

```c
struct SlabAllocator {
    void*         pool;
    void*         freelist;
    unsigned long obj_size;
    unsigned long count;

    void*         alloc();
    void          dealloc(void* ptr);
    unsigned long available() const;
    void          destroy();
}
```

### 생성자 {#constructors-1}

```c
// 호출자가 제공한 버퍼를 사용
SlabAllocator slab_init(void* pool, unsigned long obj_size, unsigned long count);

// `obj_size` 바이트 크기의 객체 `count`개를 위한 풀을 힙에 할당
SlabAllocator slab_new(unsigned long obj_size, unsigned long count);
```

### 메서드 {#methods-1}

| 메서드 | 설명 |
|--------|-------------|
| `alloc()` | 프리리스트에서 객체 하나를 팝합니다. 꽉 찼으면 NULL을 반환합니다. |
| `dealloc(ptr)` | 객체를 프리리스트로 다시 푸시합니다. 포인터는 반드시 이 얼로케이터에서 나온 것이어야 합니다. |
| `available() const` | 남은 여유 슬롯 수입니다. |
| `destroy()` | 힙에 할당된 경우 백킹 풀을 해제합니다. |

### 예제 {#example-1}

```c
#include "alloc/slab.h"
#include "io.h"

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    SlabAllocator sa = slab_new(sizeof(struct Packet), 32);

    struct Packet* p1 = sa.alloc();
    struct Packet* p2 = sa.alloc();
    struct Packet* p3 = sa.alloc();

    p1->len = 10;

    print("available: ");
    println_int(sa.available());  // 29

    sa.dealloc(p2);

    print("available: ");
    println_int(sa.available());  // 30

    sa.destroy();
    return 0;
}
```

---

## PoolAllocator {#poolallocator}

고정 크기 블록 풀입니다. 슬랩 얼로케이터와 비슷하지만 모든 블록이 동일한 객체 타입을 담아야 한다는 제약이 없습니다 — `block_size` 바이트 이내에 들어맞는 데이터라면 무엇이든 같은 풀을 사용할 수 있습니다.

### 구조체 {#struct-2}

```c
struct PoolAllocator {
    void*         base;
    void*         next_free;
    unsigned long block_size;
    unsigned long capacity;

    void*         alloc();
    void          free(void* ptr);
    unsigned long available() const;
    void          destroy();
}
```

### 생성자 {#constructors-2}

```c
// 호출자가 제공한 버퍼를 사용
PoolAllocator pool_init(void* buffer, unsigned long block_size, unsigned long count);

// 힙에 할당
PoolAllocator pool_new(unsigned long block_size, unsigned long count);
```

### 메서드 {#methods-2}

| 메서드 | 설명 |
|--------|-------------|
| `alloc()` | 다음 여유 블록을 반환합니다. 소진되었으면 NULL입니다. |
| `free(ptr)` | 블록을 풀로 반환합니다. |
| `available() const` | 여유 블록 수입니다. |
| `destroy()` | 힙에 할당된 백킹 버퍼를 해제합니다. |

---

## TlsfAllocator {#tlsfallocator}

Two-Level Segregated Fit — **최악의 경우에도 O(1)**의 할당/해제를 보장하는 실시간 범용 얼로케이터입니다. `malloc`의 타이밍이 허용되지 않는 임베디드 펌웨어에 적합합니다.

### 구조체 {#struct-3}

```c
struct TlsfAllocator {
    void*         pool;
    unsigned long pool_size;
    // 내부 FL/SL 비트맵 (불투명)

    void* alloc(unsigned long size);
    void  free(void* ptr);
    void  destroy();
}
```

### 생성자 {#constructors-3}

```c
// 호출자가 제공한 메모리 리전을 TLSF 힙으로 사용
TlsfAllocator tlsf_init(void* pool, unsigned long size);

// 시스템 얼로케이터로부터 `size` 바이트 크기의 풀을 힙에 할당
TlsfAllocator tlsf_new(unsigned long size);
```

### 메서드 {#methods-3}

| 메서드 | 설명 |
|--------|-------------|
| `alloc(size)` | `size` 바이트를 할당합니다. 적합한 블록이 없으면 NULL을 반환합니다. O(1)이 보장됩니다. |
| `free(ptr)` | 이전에 할당된 블록을 해제합니다. O(1)이 보장됩니다. |
| `destroy()` | 힙에 할당된 풀을 해제합니다. |

### 예제 {#example-2}

```c
#include "alloc/tlsf.h"
#include "io.h"

int main() {
    TlsfAllocator ta = tlsf_new(65536);  // 64 KiB 풀

    void* a = ta.alloc(100);
    void* b = ta.alloc(200);
    void* c = ta.alloc(50);

    ta.free(b);              // 병합되어 여유 풀로 돌아감
    void* d = ta.alloc(180);  // 해제된 공간을 재사용

    ta.free(a);
    ta.free(c);
    ta.free(d);
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
