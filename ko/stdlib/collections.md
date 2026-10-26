# 컬렉션

`std/collections/`에는 SafeC의 컨테이너 타입이 있습니다. 모든 원소 컨테이너는 **제네릭 구조체**입니다([제네릭](/ko/reference/generics#generic-structs-and-methods) 참고): `Vec<T>`, `HashMap<K, V>`, `StrMap<V>`, `List<T>`, `Stack<T>`, `Queue<T>`, `BST<K, V, C>`, `BTree<V>`, `Slice<T>`, `StaticVec<T>`, `StaticMap<V>`. 원소는 **값으로** 저장되며, API는 결코 `void*`나 원시 `T*`를 내주지 않습니다(일괄 연산은 내장 슬라이스 `[]T`를 받고 돌려줍니다):

- 원소 접근은 `?&T`를 반환합니다. 이는 사용 전에 좁혀야 하는 nullable 참조입니다 — `match`, `.default(x)`, `.is_null()`, 또는 `std::expect_ref`로 좁힙니다([Nullable 참조](/ko/reference/types#reading-a-nullable-value) 참고).
- 제거는 `?T`를 반환합니다(옵셔널 값: 제거할 것이 없었으면 `null`).
- 콜백은 타입이 지정된 `fn` 값이고, 순서 관계는 컴파일 타임 타입입니다([순서 관계](#orderings) 참고).
- 같은 기능을 가진 컨테이너는 그 기능을 같은 메서드 이름으로 제공하며, [`traits.h`](#traits)가 각 기능을 트레이트로 이름 붙이므로 한 번 작성한 제네릭 코드(`push_all`, `enqueue_all`, `drain_into`, 또는 직접 만든 것)가 그 기능을 가진 모든 컨테이너를 받습니다.

각 컨테이너는 헤더 전용입니다: `#include <std/collections/vec.h>`만 있으면 되며, 모든 메서드는 여러분의 원소 타입에 대해 단형화되므로 원소 접근은 평범한 load/store로 인라인됩니다. 타입 인자는 변수에 쓰고, 생성자가 그 대상 타입으로부터 추론합니다:

```c
struct Vec<int> v = std::Vec::new();                 // T = int, from the declaration
struct HashMap<int, long long> m = std::HashMap::new();  // K, V from the declaration
```

::: tip 컨테이너 안을 가리키는 참조는 수명이 짧습니다
`get()`/`front()`/`find()`가 반환하는 `?&T`는 컨테이너의 저장 공간 안을 가리킵니다. 이 참조는 원소를 옮기거나 버릴 수 있는 다음 호출 전까지만 유효합니다 — `Vec`의 성장(`push`, `insert`, `reserve`), 모든 `HashMap::insert`/`remove`, 해당 `List`/`BST` 노드의 제거, 또는 `free()`.
:::

## 개요 {#overview}

| 헤더 | 타입 | 저장 방식 | 비고 |
|---|---|---|---|
| `vec.h` | `Vec<T>` | 성장 가능한 힙 버퍼 하나 | 인트로소트, 컴파일 타임 `vec_sort_by` |
| `map.h` | `HashMap<K, V>`, `StrMap<V>` | 오픈 어드레싱, 선형 탐사 | 워드 크기 키는 한 번에 해싱/비교 |
| `list.h` | `List<T>` | 이중 연결, 리스트별 풀에서 노드 할당 | 양쪽 끝에서 O(1) push/pop |
| `stack.h` | `Stack<T>` | 성장 가능한 힙 버퍼 | LIFO |
| `queue.h` | `Queue<T>` | 2의 거듭제곱 링 | FIFO |
| `bst.h` | `BST<K, V, C>` | 트리별 풀에서 노드 할당 | 순서 관계 `C`는 타입; 반복적, 재귀 없음 |
| `btree.h` | `BTree<V>` | 구조체 내부의 고정 노드 풀 | `unsigned long` 키, 초기화 후 힙 사용 없음 |
| `slice.h` | `Slice<T>` | 뷰(선택적으로 소유) | 경계 검사; `arr_*` 헬퍼 |
| `static_vec.h` | `StaticVec<T>`, `StaticMap<V>` | 호출자가 제공한 배열 | 힙 사용 없음 |
| `string.h` | `String` | 힙, NUL 종료 | 40개 이상의 메서드 |
| `ringbuffer.h` | `RingBuffer` | 호출자가 제공한 static 바이트 | SPSC, 락프리 |
| `node_pool.h` | `NodePool<N>` | 청크 단위 아레나 + 프리 리스트 | `List`/`BST`의 노드 얼로케이터 |
| `traits.h` | 기능 트레이트 | — | `Sequence`, `Lifo`, `Fifo`, `CountedFifo`, `Map`; 이들에 대한 제네릭 헬퍼 |

## 순서 관계 {#orderings}

```c
#include <std/cmp.h>
```

*순서 관계(ordering)*는 `<0`, `0`, `>0`을 반환하는 `int cmp(const &T a, const &T b) const`를 가진 임의의 구조체이며, 이것이 `Order` 트레이트입니다. 정렬된 컨테이너와 알고리즘은 순서 관계를 **`Order`로 바운드된 타입 매개변수**로 받으므로(`BST<K, V, C: Order>`, `vec_sort_by<T, C: Order>`), `cmp`가 없는 타입은 그 타입을 쓴 자리에서 거부되고, 단형화 후 각 비교는 옵티마이저가 인라인하는 직접 호출이 됩니다 — 핫 패스에 함수 포인터가 없습니다.

```c
trait Order {
    int cmp(const &Elem_ a, const &Elem_ b) const;   // <0: a first, 0: equivalent, >0: b first
}

generic<T> struct Less    { int cmp(const &T a, const &T b) const; };   // ascending, by < / >
generic<T> struct Greater { int cmp(const &T a, const &T b) const; };   // descending
generic<T> struct FnOrder { fn int(const &T, const &T) f; int cmp(...) const; };  // run-time comparator

// Values, when one is needed (stateless orderings are usually just a type argument):
static struct Less<T>    Less::new();
static struct Greater<T> Greater::new();
static struct FnOrder<T> FnOrder::of(fn int(const &T, const &T) f);
```

`Elem_`은 "원소 타입"을 뜻합니다: 트레이트는 각 메서드의 이름, 정적 여부, 매개변수 개수를 고정하고, 매개변수 타입은 각 순서 관계의 것을 따릅니다(제네릭 본문이 인스턴스화되는 자리에서 검사됩니다). 예를 들어 레코드를 한 필드로 정렬하려면 `cmp` 메서드를 가진 구조체로 순서 관계를 직접 작성하면 되고, 그것은 똑같이 인라인되는 `Order`입니다. `FnOrder`는 비교 함수가 런타임에 선택될 때만 사용합니다.

```c
#include <std/collections/vec.h>
#include <std/collections/bst.h>

struct Job { int priority; int id; };

// An Order of your own: higher priority first. No function pointer anywhere.
struct ByPriority {
    int unused_;
    int cmp(const &Job a, const &Job b) const;
};
int ByPriority::cmp(const &Job a, const &Job b) const { return b.priority - a.priority; }

int main() {
    struct Vec<struct Job> jobs = std::Vec::new();
    struct Job j;
    j.priority = 1; j.id = 10; jobs.push(j);
    j.priority = 5; j.id = 11; jobs.push(j);
    j.priority = 3; j.id = 12; jobs.push(j);

    struct ByPriority ord;
    ord.unused_ = 0;
    std::vec_sort_by(&jobs, ord);                   // ids 11, 12, 10

    struct BST<int, int, Greater<int>> desc = std::BST::new();   // largest key first
    desc.insert(1, 100); desc.insert(7, 700);
    int top = desc.min_key().default(0);            // 7: "min" under Greater

    int ok = jobs.at(0UL).id == 11 && top == 7;
    desc.free();
    jobs.free();
    return ok ? 0 : 1;
}
```

## 기능 트레이트 {#traits}

```c
#include <std/collections/traits.h>
```

컨테이너들은 공통으로 가진 기능을 같은 메서드 이름으로 제공합니다. `traits.h`는 그 기능 각각을 하나씩 [트레이트](/ko/reference/generics#composing-traits)로 이름 붙이고, 이를 제네릭 코드가 요구하는 역할로 합성합니다. 컨테이너는 메서드를 가지는 것만으로 트레이트를 준수하며 — 따로 선언할 것이 없습니다 — 트레이트로 바운드된 제네릭 코드는 준수하는 모든 컨테이너를 받고, 각 호출은 컴파일 타임에 결정됩니다.

| 트레이트 | 요구 사항 | 준수하는 타입 |
|---|---|---|
| `Len` | `length()`, `is_empty()` | 이 페이지의 `NodePool`을 제외한 모든 컨테이너, 그리고 `MpscQueue`와 `LFQueue` |
| `Clear` | `clear()` | `Vec`, `Stack`, `Queue`, `List`, `HashMap`, `StrMap`, `BST`, `BTree`, `StaticVec`, `StaticMap`, `RingBuffer`, `String` |
| `Get` | `get(i) -> ?&T`(맵: `get(key)`), 없으면 null | |
| `Push` | `push(x)`: 1, 가득 찼거나 메모리가 부족하면 0 | |
| `Pop` | `pop() -> ?T`, 비어 있으면 null | |
| `Enqueue` | `enqueue(x)`: 1, 가득 차면 0 (`Chan`: 블로킹, 닫힌 뒤에는 0) | |
| `Dequeue` | `dequeue() -> ?T`, 비어 있으면 null (`Chan`: 블로킹, 닫히고 다 비워지면 null) | |
| `Insert`, `Contains`, `Remove` | `insert(k, v)`, `contains(k)`, `remove(k) -> ?V` | |
| `Sequence` | `Len + Get + Push` | `Vec`, `StaticVec` |
| `Lifo` | `Len + Push + Pop` | `Vec`, `Stack`, `StaticVec` |
| `Fifo` | `Enqueue + Dequeue` | `Queue`, `RingBuffer`(한 바이트씩), 그리고 [`std/sync`](/ko/stdlib/sync)의 `Chan`, `MpscQueue`, `LFQueue` |
| `CountedFifo` | `Fifo + Len` | `Queue`, `RingBuffer`, `MpscQueue`, `LFQueue` |
| `Map` | `Len + Insert + Get + Contains + Remove` | `HashMap`, `StrMap`, `BST`, `BTree` (`StaticMap`은 `remove`가 없어 `Len + Insert + Get + Contains`입니다) |

모든 맵의 `insert`는 성공하면 0이 아닌 값을, 가득 찼거나 메모리가 부족하면 0을 반환합니다.

헬퍼는 자신이 쓰는 기능만 요구합니다:

| 함수 | 바운드 | 동작 |
|---|---|---|
| `push_all(&s, items)` | `S: Push` | 슬라이스 `items`를 순서대로 push하고, 처음 거부된 원소에서 멈춥니다. 들어간 개수를 반환합니다 |
| `enqueue_all(&q, items)` | `Q: Enqueue` | `enqueue`로 같은 일을 합니다(가득 차거나 `Chan`이 닫히면 멈춤) |
| `drain_into(&q, &out)` | `Q: Dequeue`, `S: Push` | `q`가 빌 때까지(`Chan`이면 닫히고 다 비워질 때까지) dequeue하여 각 원소를 순서대로 `out`에 push합니다. 옮긴 개수를 반환합니다. `Vec`처럼 늘어날 수 있는 `out`을 위한 것입니다 |

```c
#include <std/collections/traits.h>
#include <std/collections/vec.h>
#include <std/collections/stack.h>
#include <std/collections/queue.h>
#include <std/collections/map.h>
#include <std/collections/bst.h>
#include <std/sync/lockfree.h>

// Any FIFO with a length: Queue, MpscQueue, LFQueue, RingBuffer.
generic<Q: CountedFifo>
unsigned long backlog_after(&Q q, []const int items) {
    std::enqueue_all(q, items);
    q.dequeue();                       // one consumed
    return q.length();
}

// Any map: HashMap, StrMap, BST, BTree.
generic<M: Map>
int squares(&M m) {
    int i = 0;
    while (i < 10) { m.insert(i, i * i); i = i + 1; }
    m.remove(3);
    return m.contains(3) ? -1 : (int)m.length();
}

int main() {
    int xs[4] = {1, 2, 3, 4};

    struct Queue<int> q = std::Queue::new();
    struct LFQueue<int> lf = std::LFQueue::new(8UL);
    unsigned long b1 = backlog_after(&q, xs[..]);        // 3
    unsigned long b2 = backlog_after(&lf, xs[..]);       // 3

    struct Vec<int> got = std::Vec::new();
    unsigned long moved = std::drain_into(&q, &got);       // 3: q -> got, in order

    struct Stack<int> st = std::Stack::new();
    std::push_all(&st, xs[..]);                            // a Lifo: pop() gives 4 first

    struct HashMap<int, int> hm = std::HashMap::new();
    struct BST<int, int, Less<int>> t = std::BST::new();
    int n1 = squares(&hm);                                 // 9
    int n2 = squares(&t);                                  // 9

    int ok = b1 == 3UL && b2 == 3UL && moved == 3UL && got.get(0UL).default(0) == 2
          && st.pop().default(0) == 4 && n1 == 9 && n2 == 9;
    t.free(); hm.free(); st.free(); got.free(); lf.destroy(); q.free();
    return ok ? 0 : 1;
}
```

트레이트는 각 메서드의 이름, 정적 여부, 매개변수 개수를 검사하고, 매개변수 타입은 제네릭 본문이 인스턴스화되는 자리에서 검사됩니다. 그래서 `Vec`의 `contains(key, cmp)`(매개변수 두 개)는 `Vec`을 `Contains`와 `Map`에서 제외하지만, `String`의 `push(cstr)`는 여전히 `Push`로 셉니다 — `String`에 대한 `push_all`은 `char`를 넘기는 자리에서 실패합니다. 반복은 트레이트가 아닙니다: `foreach`는 대부분의 컨테이너에 있지만 콜백 형태가 컨테이너마다 다릅니다(`Vec`: `(&T, index)`, `List`: `(&T)`, 맵: `(const &K, &V)`).



## vec -- 동적 배열 {#vec}

```c
#include <std/collections/vec.h>
```

```c
generic<T>
struct Vec {
    ?&heap T      data;   // null while cap == 0
    unsigned long len;
    unsigned long cap;

    static struct Vec<T> new();                                        // empty, no allocation
    static struct Vec<T> with_cap(unsigned long cap);
    static struct Vec<T> from_slice([]const T items);                  // copies the elements

    int           reserve(unsigned long new_cap);    // 0 on OOM
    void          shrink();                           // cap := len
    unsigned long length() const;
    unsigned long total_capacity() const;
    int           is_empty() const;

    ?&T           get(unsigned long idx) const;       // null if out of range
    &T            at(unsigned long idx) const;        // panics if out of range
    int           set(unsigned long idx, T value);     // 0 if out of range
    ?&T           front();
    ?&T           back();

    int           push(T value);                       // 0 on OOM
    ?T            pop();                               // null if empty
    int           insert(unsigned long idx, T value);  // idx <= len
    ?T            remove(unsigned long idx);           // null if out of range
    void          clear();                             // len := 0, keeps the buffer
    int extend([]const T items);

    void          reverse();
    void          sort(fn int(const &T, const &T) cmp);
    long long     find(const &T key, fn int(const &T, const &T) cmp) const;   // -1 if absent
    int           contains(const &T key, fn int(const &T, const &T) cmp) const;
    struct Vec<T> clone() const;                       // shallow element copy
    void          foreach(fn void(&T, unsigned long) func);
    struct Vec<T> filter(fn int(const &T) pred) const;
    void          free();
};

generic<T, U> struct Vec<U> vec_map(const &Vec<T> v, fn U(const &T) f);

generic<T, C: Order> void vec_sort_by(&Vec<T> v, C ord);   // compile-time ordering, inlined
generic<T>    void vec_sort(&Vec<T> v);              // vec_sort_by(v, Less<T>)
```

정렬은 인트로소트입니다(세 값의 중앙값 퀵소트, 힙소트 대체, 짧은 구간에는 삽입 정렬). `sort(cmp)`는 함수 포인터를 통해 호출하고, `vec_sort` / `vec_sort_by`는 컴파일 타임 [순서 관계](#orderings)를 받아 모든 비교를 인라인합니다 — 섞인 `int` 1,000,000개에서 C의 `qsort`보다 약 1.9배 빠릅니다([벤치마크](/ko/benchmarks#collections) 참고).

```c
#include <std/collections/vec.h>
extern int printf(const char* fmt, ...);

static int is_even(const &int x) { return *x % 2 == 0; }

int main() {
    struct Vec<int> v = std::Vec::new();
    int i = 0;
    while (i < 10) { v.push(9 - i); i = i + 1; }
    std::vec_sort(&v);                          // 0 1 2 ... 9
    *v.at(0UL) = 100;                           // write through the reference
    match (v.get(20UL)) {
        case some(x): unsafe { printf("%d\n", *x); }
        case null:    unsafe { printf("out of range\n"); }
    }
    struct Vec<int> evens = v.filter(is_even);
    unsafe { printf("%lu evens, last=%d\n", evens.length(), v.pop().default(-1)); }
    evens.free();
    v.free();
    return 0;
}
```



## map -- 해시 맵 {#map}

```c
#include <std/collections/map.h>
```

선형 탐사를 사용하는 오픈 어드레싱입니다. 테이블은 부하율 0.75에서 커지며, 툼스톤도 부하율에 포함되므로 제거가 많은 워크로드는 성능이 저하되는 대신 재해싱됩니다. 해시, 키, 값, 슬롯 상태는 별도 배열에 있어서 탐사가 가능한 한 적은 메모리만 건드립니다.

### HashMap\<K, V\> {#hashmap-k-v}

```c
generic<K, V>
struct HashMap {
    static struct HashMap<K, V> new();                        // empty, no allocation
    static struct HashMap<K, V> with_cap(unsigned long cap);  // room for cap entries

    int           insert(K key, V val);      // insert or overwrite; 0 on OOM
    ?&V           get(K key) const;          // null if missing
    int           contains(K key) const;
    ?V            remove(K key);             // the removed value, or null
    unsigned long length() const;
    int           is_empty() const;
    void          clear();
    void          foreach(fn void(const &K, &V) func);
    int           reserve(unsigned long n);  // room for n entries without resizing
    void          free();
};
```

키는 **바이트 단위로** 해싱되고 비교되므로, `K`는 자신의 바이트로 완전히 기술되어야 합니다: 정수, 열거형, 포인터, 패딩 없는 구조체. 1, 2, 4, 8바이트 키의 경우 맵은 키를 하나의 워드로 읽어 murmur3의 64비트 finalizer로 해싱하고 단 한 번의 정수 비교로 비교합니다 — 크기 검사는 `if const`이므로 인스턴스화마다 컴파일 타임에 결정됩니다. 그 밖의 크기는 FNV-1a와 바이트 비교를 사용합니다. 텍스트 키에는 `StrMap`을 씁니다.

### StrMap\<V\> {#strmap-v}

```c
generic<V>
struct StrMap {
    static struct StrMap<V> new();
    int           insert(const &char key, V val);   // copies the key
    ?&V           get(const &char key) const;
    int           contains(const &char key) const;
    ?V            remove(const &char key);
    unsigned long length() const;
    int           is_empty() const;
    void          clear();
    void          foreach(fn void(const &char, &V) func);
    void          free();
};
```

```c
#include <std/collections/map.h>
extern int printf(const char* fmt, ...);

int main() {
    struct HashMap<int, long long> sq = std::HashMap::new();
    int i = 0;
    while (i < 1000) { sq.insert(i, (long long)i * (long long)i); i = i + 1; }
    match (sq.get(12)) {
        case some(v): *v = *v + 1LL;          // 145
        case null: { }
    }
    long long gone = sq.remove(3).default(-1LL);   // 9

    struct StrMap<int> ages = std::StrMap::new();
    ages.insert("ada", 36);
    unsafe { printf("%lld %lld %d\n", sq.get(12).default(0LL), gone, ages.get("ada").default(0)); }
    ages.free();
    sq.free();
    return 0;
}
```



## list -- 이중 연결 리스트 {#list}

```c
#include <std/collections/list.h>
```

```c
generic<T>
struct List {
    static struct List<T> new();
    int           push_front(T value);
    int           push_back(T value);
    ?T            pop_front();
    ?T            pop_back();
    ?&T           front() const;
    ?&T           back() const;
    unsigned long length() const;
    int           is_empty() const;
    ?&T           find(const &T key, fn int(const &T, const &T) cmp) const;
    int           contains(const &T key, fn int(const &T, const &T) cmp) const;
    int           remove(const &T key, fn int(const &T, const &T) cmp);  // first match
    void          foreach(fn void(&T) func);
    void          reverse();
    void          clear();
    void          free();
};
```

노드는 리스트 자신의 [`NodePool`](#node-pool)에서 나옵니다: push가 원소마다 얼로케이터를 호출하지 않고, pop된 노드는 재활용되며, `free()`는 리스트를 순회하는 대신 청크 단위로 통째로 해제합니다. 원소 1,000,000개를 push한 뒤 pop하는 작업은 노드마다 C `malloc`/`free`를 쓰는 리스트보다 약 2배 빠릅니다.



## stack / queue {#stack-queue}

```c
#include <std/collections/stack.h>
#include <std/collections/queue.h>
```

```c
generic<T>
struct Stack {
    static struct Stack<T> new();  static struct Stack<T> with_cap(unsigned long cap);
    int push(T value);  ?T pop();  ?&T peek() const;
    unsigned long length() const;  int is_empty() const;
    void clear();  int reserve(unsigned long n);  void free();
};

generic<T>
struct Queue {
    static struct Queue<T> new();  static struct Queue<T> with_cap(unsigned long cap);
    int enqueue(T value);  ?T dequeue();  ?&T front() const;  ?&T back() const;
    unsigned long length() const;  int is_empty() const;
    void clear();  int reserve(unsigned long n);  void free();
};
```

`Queue`는 2의 거듭제곱 링 버퍼입니다: 인덱스는 마스크로 순환하며, 성장할 때 원소를 선형으로 다시 배치합니다.

```c
struct Queue<int> q = std::Queue::new();
q.enqueue(1); q.enqueue(2);
int first = q.dequeue().default(0);    // 1
q.free();
```



## bst -- 이진 탐색 트리 {#bst}

```c
#include <std/collections/bst.h>
```

```c
generic<K, V, C: Order>   // C: an ordering type, see #orderings
struct BST {
    static struct BST<K, V, C> new();            // stateless C (Less, Greater)
    static struct BST<K, V, C> new_with(C ord);  // e.g. FnOrder::of(my_cmp)

    int           insert(K key, V val);    // insert or overwrite
    ?&V           get(K key) const;
    int           contains(K key) const;
    ?V            remove(K key);
    unsigned long length() const;
    int           is_empty() const;
    ?&K           min_key() const;
    ?&K           max_key() const;
    void          foreach_inorder(fn void(const &K, &V) func);   // ascending
    void          clear();
    void          free();
};
```

균형을 잡지 않지만 모든 연산이 **반복적**입니다: 퇴화된(정렬된 순서로 삽입된) 트리는 조회마다 O(n)이 들지만, 작은 베어메탈 스택을 넘치게 하는 일은 결코 없습니다. 중위 순회는 Morris 순회입니다(추가 공간 O(1), 순회 후 트리는 복원됩니다). 노드는 트리별 [`NodePool`](#node-pool)에서 나옵니다.

```c
#include <std/collections/bst.h>
extern int printf(const char* fmt, ...);

static void show(const &int k, &int v) { unsafe { printf("%d=%d ", *k, *v); } }

int main() {
    struct BST<int, int, Less<int>> t = std::BST::new();
    t.insert(50, 5); t.insert(30, 3); t.insert(70, 7);
    t.foreach_inorder(show);                       // 30=3 50=5 70=7
    unsafe { printf("\nmin=%d removed=%d\n", t.min_key().default(0), t.remove(50).default(0)); }
    t.free();
    return 0;
}
```



## btree -- 순서가 있는 B-트리 맵 {#btree}

```c
#include <std/collections/btree.h>
```

`unsigned long`을 키로 하는 B-트리(최소 차수 `BTREE_ORDER` = 4, 노드당 최대 7개 키)이며, 모든 노드는 구조체 내부의 `BTREE_POOL_SIZE`(256)개 노드로 된 고정 풀에서 가져옵니다 — 생성 후 힙 사용 없음, 결정론적 O(log n). 병합으로 비워진 노드는 프리 리스트로 돌아가므로, 삽입/제거가 반복되어도 풀이 고갈되지 않습니다.

```c
generic<V>
struct BTree {
    void          init();   // empty tree, in place (scalar fields only, no pool copy)
    int           insert(unsigned long key, V val);   // insert or overwrite; 0 if the pool is full
    ?&V           get(unsigned long key) const;
    int           contains(unsigned long key) const;
    ?V            remove(unsigned long key);
    unsigned long length() const;
    int           is_empty() const;
    void          foreach(fn void(unsigned long, &V) cb);   // ascending
    void          clear();
};
```

풀 때문에 `BTree`는 크므로(수십 KB), 스택에 두지 말고 정적 저장소에 두거나 할당합니다:

```c
static struct BTree<int> g_index;

void setup() {
    g_index.init();
    g_index.insert(42UL, 7);
    int v = g_index.get(42UL).default(0);
}
```

노드 형태: 노드당 키 `BTREE_MAX_KEYS`(7)개, 자식 `BTREE_MAX_CHILD`(8)개.

## slice -- 경계 검사 뷰 {#slice}

```c
#include <std/collections/slice.h>
```

```c
generic<T>
struct Slice {
    ?&T           ptr;   // first element; null for an empty slice
    unsigned long len;

    static struct Slice<T> from([]T s);                       // a view of a built-in slice
    static struct Slice<T> of(&T first, unsigned long len);   // a view of a 'new T[len]' array
    static struct Slice<T> alloc(unsigned long len);          // zeroed heap slice

    int             in_bounds(unsigned long idx) const;
    ?&T             get(unsigned long idx) const;
    &T              at(unsigned long idx) const;          // panics if out of range
    int             set(unsigned long idx, T val);
    struct Slice<T> sub(unsigned long start, unsigned long end) const;  // [start, end)
    unsigned long   length() const;
    int             is_empty() const;
    []T             as_slice() const;                      // the same elements as a built-in slice
    void            free();                                // only for Slice::alloc()
};

// The same checks on a built-in slice:
generic<T> ?&T  arr_at([]T s, unsigned long idx);
generic<T> int  arr_set([]T s, unsigned long idx, T val);
generic<T> void arr_fill([]T s, T val);
generic<T> unsigned long arr_copy([]T dst, []const T src);
generic<T> ?T   arr_min([]const T s);   // null if empty
generic<T> ?T   arr_max([]const T s);
generic<T> void arr_reverse([]T s);
```

```c
int arr[8];
struct Slice<int> s = std::Slice::from(arr[..]);
struct Slice<int> mid = s.sub(2UL, 5UL);   // elements 2, 3, 4
int x = mid.get(0UL).default(0);
```



## static\_vec -- 힙을 쓰지 않는 컬렉션 {#static-vec}

```c
#include <std/collections/static_vec.h>
```

여러분이 제공하는 저장 공간(static 또는 스택 배열) 위의 고정 용량 컨테이너이므로 아무것도 할당되지 않습니다 — 인터럽트 핸들러와 힙이 없는 MCU에 알맞은 선택입니다.

```c
generic<T>
struct StaticVec {
    static struct StaticVec<T> over([]T storage);   // capacity = storage.len
    int push(T val);  ?T pop();  ?&T top() const;  ?&T get(unsigned long idx) const;
    unsigned long length() const;  int is_empty() const;  int is_full() const;  void clear();
};

generic<V> struct StaticMapSlot { unsigned long key; V val; int used; };
generic<V>
struct StaticMap {
    static struct StaticMap<V> over([]StaticMapSlot<V> storage);
    int insert(unsigned long key, V val);   // 0 if full
    ?&V get(unsigned long key) const;  int contains(unsigned long key) const;
    unsigned long length() const;  int is_empty() const;  void clear();
};
```

```c
static int g_buf[32];
static struct StaticMapSlot<int> g_slots[64];

void demo() {
    struct StaticVec<int> v = std::StaticVec::over(g_buf[..]);
    v.push(42);
    struct StaticMap<int> m = std::StaticMap::over(g_slots[..]);
    m.insert(5UL, 100);
    int hundred = m.get(5UL).default(0);
}
```

컨테이너는 저장 공간에 대한 리전 없는 참조를 유지하므로, 배열은 컨테이너보다 오래 살아야 합니다.



## node\_pool -- 컨테이너별 노드 아레나 {#node-pool}

```c
#include <std/collections/node_pool.h>
```

`NodePool<N>`은 `List`와 `BST` 뒤에 있는 얼로케이터이며, 여러분 자신의 노드 기반 자료구조에도 사용할 수 있습니다. 이것은 아레나입니다: 노드는 크기가 두 배씩 커지는(8 … 1024 노드) 청크에서 범프 할당되고, 제거된 노드는 재사용을 위해 프리 리스트로 가며, `free_all()`은 모든 청크를 한 번에 해제합니다.

```c
generic<N>
struct NodePool {
    static struct NodePool<N> new();
    ?&heap N take();              // an uninitialised node; null on OOM
    void     give(&heap N node);  // recycle a node
    void     free_all();
};
```

노드마다 `std::alloc`을 한 번씩 호출하는 것과 비교하면, 노드마다 붙던 얼로케이터의 16바이트 안전 헤더와 크기 클래스 패딩이 사라지고 컨테이너 해체가 O(청크 수)가 됩니다. `give()`로 돌려준 노드는 다음 `take()`에서 재사용됩니다: 제거된 원소에 대한 참조를 붙들고 있으면 `dealloc()` 이후와 똑같이 댕글링됩니다.

청크 크기: 첫 청크는 노드 `NODE_POOL_FIRST_CHUNK`(8)개를 담고 이후 청크마다 두 배가 되어 `NODE_POOL_MAX_CHUNK`(1024)까지 커집니다. 각 청크에는 노드를 16바이트로 정렬하기 위한 `NODE_POOL_CHUNK_HDR`(16바이트) 헤더가 붙습니다.

## string -- 가변 문자열 {#string-mutable-string}

```c
#include <std/collections/string.h>
```

40개 이상의 메서드를 가진, 성장 가능하고 힙에 할당되며 NUL로 끝나는 바이트 문자열입니다. `data`는 `&heap char`입니다. 생성만 자유 함수이고, 나머지는 모두 메서드입니다.

### 구조체와 메서드 {#struct-and-methods}

```c
struct String {
    &heap char    data;
    unsigned long len;
    unsigned long cap;

    // Access
    unsigned long  length() const;
    int            is_empty() const;
    const &char    as_ptr() const;
    int            char_at(unsigned long idx) const;         // -1 if OOB
    void           set_char(unsigned long idx, char c);

    // Capacity
    int            reserve(unsigned long additional);
    void           shrink_to_fit();

    // Append
    int            push_char(char c);
    int            push(const &char cstr);
    int            push_bytes([]const char bytes);  // raw bytes, need not be NUL-terminated
    int            push_str(&String other);
    int            push_int(long long v);
    int            push_uint(unsigned long long v);
    int            push_float(double v, int decimals);
    int            push_bool(int v);

    // Modification
    void           clear();
    void           truncate(unsigned long new_len);
    int            insert(unsigned long idx, const &char cstr);
    int            remove_range(unsigned long start, unsigned long end);
    void           replace_char(char from, char to);
    int            replace(const &char from, const &char to);       // first occurrence
    int            replace_all(const &char from, const &char to);
    void           reverse();                                       // in-place byte reversal
    int            pop_char();                                      // -1 if empty

    // Search
    long long      index_of(const &char needle) const;
    long long      last_index_of(const &char needle) const;
    int            contains(const &char needle) const;
    int            starts_with(const &char prefix) const;
    int            ends_with(const &char suffix) const;
    int            count(const &char needle) const;
    long long      find_char(char c) const;
    long long      rfind_char(char c) const;

    // Transformation (return a new String)
    struct String  substr(unsigned long start, unsigned long end) const;
    struct String  to_upper() const;
    struct String  to_lower() const;
    struct String  trim() const;
    struct String  trim_left() const;
    struct String  trim_right() const;
    struct String  pad_left(unsigned long width, char fill) const;
    struct String  pad_right(unsigned long width, char fill) const;
    struct String  strip_prefix(const &char prefix) const;
    struct String  strip_suffix(const &char suffix) const;
    struct String  repeat(unsigned long n) const;
    struct String  capitalize() const;

    // Split -- writes into caller-provided 'out' array (max slots); returns
    // items written; overflow puts the remainder in the last slot
    unsigned long  split(const &char delim, &String out, unsigned long max) const;
    unsigned long  split_lines(&String out, unsigned long max) const;
    unsigned long  split_whitespace(&String out, unsigned long max) const;

    // Comparison
    int            eq(&String other) const;
    int            eq_cstr(const &char other) const;
    int            cmp(&String other) const;         // <0, 0, >0
    int            lt(&String other) const;
    int            gt(&String other) const;
    int            eq_ignore_case(&String other) const;
    int            eq_cstr_ignore_case(const &char other) const;

    // Query
    int            is_ascii() const;
    int            is_numeric() const;
    int            is_alphanumeric() const;

    // Conversion
    long long      parse_int(?&int ok) const;
    double         parse_float(?&int ok) const;

    struct String  clone() const;
    void           free();
};
```

### 생성자 {#constructors}

```c
// static methods of String: String::new(), std::String::from("x") from outside namespace std
static struct String String::new();
static struct String String::from(const &char s);
static struct String String::from_bytes([]const char bytes);
static struct String String::with_cap(unsigned long cap);
static struct String String::repeat_str(const &char s, unsigned long n);   // s repeated n times
// parts[0..count) concatenated with sep between each (null sep: none)
static struct String String::join(const ?&char sep, []String parts);
```

### 예제 {#example}

실제 컴파일/실행으로 검증되었습니다:

```c
#include <std/collections/string.h>
extern int printf(const char* fmt, ...);

int main() {
    struct String s = std::String::from("Hello");
    s.push(", SafeC!");
    printf("%s\n", s.as_ptr());              // Hello, SafeC!
    printf("contains=%d\n", s.contains("SafeC"));  // 1

    struct String upper = s.to_upper();
    printf("%s\n", upper.as_ptr());          // HELLO, SAFEC!

    struct String num = std::String::new();
    num.push("value = ");
    num.push_int(42LL);
    printf("%s\n", num.as_ptr());            // value = 42

    num.free();
    upper.free();
    s.free();
    return 0;
}
```



## ringbuffer -- SPSC 락프리 링 버퍼 {#ringbuffer-spsc-lock-free-ring-buffer}

```c
#include <std/collections/ringbuffer.h>
```

단일 생산자/단일 소비자 **바이트 지향** 2의 거듭제곱 링 버퍼입니다. OS 락 없이 올바른 생산자/소비자 순서를 보장하기 위해 `head`/`tail`에 원자적 load/store를 사용합니다. ISR에서 태스크로의 데이터 전달과 오디오 파이프라인에 적합합니다.

다른 컬렉션 타입과 달리 `RingBuffer`는 원소 기반이 아닙니다 — 원시 바이트 스트림을 다룹니다. `unsigned char` 버퍼와 함께 사용합니다.

### 구조체 {#struct}

```c
struct RingBuffer {
    &static unsigned char buf;   // backing store — must be static-lifetime
    unsigned long         cap;   // capacity in bytes (must be power of two)
    unsigned long         mask;  // cap - 1  (for fast modulo)
    volatile unsigned long head; // write position (producer)
    volatile unsigned long tail; // read position (consumer)

    // Initialise over an existing static-lifetime backing store;
    // `cap` is rounded down to a power of two.
    static struct RingBuffer over([]unsigned char buf);

    unsigned long length() const;            // bytes available to read
    unsigned long writable() const;          // bytes that can be written
    int           is_empty() const;
    int           is_full() const;

    unsigned long write([]const unsigned char data);
    unsigned long read([]unsigned char out);
    unsigned long peek([]unsigned char out) const;

    // One byte at a time (the Fifo protocol, see #traits)
    int            enqueue(unsigned char b);   // 1, or 0 if full
    ?unsigned char dequeue();                  // null if empty

    unsigned long discard(unsigned long len);
    void          clear();
};
```

### 예제 {#example-1}

```c
#include <std/collections/ringbuffer.h>
extern int printf(const char* fmt, ...);

static unsigned char g_storage[128];        // static backing store, no heap

int main() {
    struct RingBuffer rb = std::RingBuffer::over(g_storage[..]);
    unsigned char tx[5];
    unsafe { tx[0] = 'H'; tx[1] = 'e'; tx[2] = 'l'; tx[3] = 'l'; tx[4] = 'o'; }
    rb.write(tx[0..5UL]);
    unsafe { printf("readable: %lu\n", rb.length()); }     // 5

    unsigned char rx[5];
    rb.read(rx[0..5UL]);
    unsafe { printf("%c%c\n", rx[0], rx[4]); }             // Ho
    return 0;
}
```

`RING_STATIC(name, N)`(저장 공간 + 링을 한 줄로 선언)은 함수형 매크로이므로 `--compat-preprocessor`에서만 사용할 수 있습니다.

::: info
`buf`의 리전은 `&static unsigned char`입니다 — 백킹 저장소는 `RingBuffer` 구조체 자체보다 오래 살아야 합니다. 임베디드 전역에는 `RING_STATIC`을 사용합니다. 힙 기반으로 사용하려면 `unsafe {}` 블록 안에서 캐스트하고 수명 관리를 직접 책임집니다.
:::

`RING_DEFAULT_CAP`(4096바이트)이 기본 용량입니다. 바꾸려면 헤더를 include하기 전에 정의합니다.
