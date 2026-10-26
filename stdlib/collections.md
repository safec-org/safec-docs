# Collections

`std/collections/` holds SafeC's container types. Every element container is a **generic struct** (see [Generics](/reference/generics#generic-structs-and-methods)): `Vec<T>`, `HashMap<K, V>`, `StrMap<V>`, `List<T>`, `Stack<T>`, `Queue<T>`, `BST<K, V, C>`, `BTree<V>`, `Slice<T>`, `StaticVec<T>` and `StaticMap<V>`. Elements are stored **by value** and the API never hands out `void*` or raw `T*` (bulk operations take and return built-in slices, `[]T`):

- element access returns `?&T`, a nullable reference that you must narrow before use — with `match`, `.default(x)`, `.is_null()`, or `std::expect_ref` (see [Nullable references](/reference/types#reading-a-nullable-value));
- removal returns `?T` (an optional value: `null` when there was nothing to remove);
- callbacks are typed `fn` values, and orderings are compile-time types (see [Orderings](#orderings));
- containers that share a capability spell it with the same method names, and [`traits.h`](#traits) names each capability as a trait, so generic code written once (`push_all`, `enqueue_all`, `drain_into`, or your own) takes every container that has it.

Each container is header-only: `#include <std/collections/vec.h>` is all you need, and every method is monomorphized for your element type, so element access inlines to plain loads and stores. Type arguments are written on the variable and inferred by the constructor from that target type:

```c
struct Vec<int> v = std::Vec::new();                 // T = int, from the declaration
struct HashMap<int, long long> m = std::HashMap::new();  // K, V from the declaration
```

::: tip References into a container are short-lived
A `?&T` returned by `get()`/`front()`/`find()` points into the container's storage. It stays valid until the next call that can move or drop elements — growing a `Vec` (`push`, `insert`, `reserve`), any `HashMap::insert`/`remove`, removing that `List`/`BST` node, or `free()`.
:::

## Overview {#overview}

| Header | Type | Storage | Notes |
|---|---|---|---|
| `vec.h` | `Vec<T>` | one growable heap buffer | introsort, compile-time `vec_sort_by` |
| `map.h` | `HashMap<K, V>`, `StrMap<V>` | open addressing, linear probing | word-sized keys hash/compare in one step |
| `list.h` | `List<T>` | doubly linked, nodes from a per-list pool | O(1) push/pop at both ends |
| `stack.h` | `Stack<T>` | growable heap buffer | LIFO |
| `queue.h` | `Queue<T>` | power-of-two ring | FIFO |
| `bst.h` | `BST<K, V, C>` | nodes from a per-tree pool | ordering `C` is a type; iterative, no recursion |
| `btree.h` | `BTree<V>` | fixed in-struct node pool | `unsigned long` keys, no heap after init |
| `slice.h` | `Slice<T>` | a view (optionally owned) | bounds-checked; `arr_*` helpers |
| `static_vec.h` | `StaticVec<T>`, `StaticMap<V>` | caller-provided array | zero heap |
| `string.h` | `String` | heap, NUL-terminated | 40+ methods |
| `ringbuffer.h` | `RingBuffer` | caller-provided static bytes | SPSC, lock-free |
| `node_pool.h` | `NodePool<N>` | chunked arena + free list | the node allocator behind `List`/`BST` |
| `traits.h` | capability traits | — | `Sequence`, `Lifo`, `Fifo`, `CountedFifo`, `Map`; generic helpers over them |

## Orderings {#orderings}

```c
#include <std/cmp.h>
```

An *ordering* is any struct with `int cmp(const &T a, const &T b) const` returning `<0`, `0` or `>0` — the `Order` trait. Ordered containers and algorithms take the ordering as a **type parameter bounded by `Order`** (`BST<K, V, C: Order>`, `vec_sort_by<T, C: Order>`), so a type without `cmp` is rejected where it is named, and after monomorphization each comparison is a direct call that the optimizer inlines — there is no function pointer on the hot path.

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

`Elem_` stands for "the element type": a trait fixes each method's name, static-ness and parameter count, and the parameter types are each ordering's own (they are checked where the generic body is instantiated). Write your own ordering as a struct with a `cmp` method — for example to order records by one field — and it is an `Order` with the same inlining. Use `FnOrder` only when the comparator is chosen at run time.

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

## Capability traits {#traits}

```c
#include <std/collections/traits.h>
```

The containers spell what they have in common with the same method names. `traits.h` names each of those capabilities as a [trait](/reference/generics#composing-traits), one capability each, and composes them into the roles generic code asks for. A container conforms by having the methods — there is nothing to declare — and generic code bounded by a trait takes every conforming container, each call resolved at compile time.

| Trait | Requires | Satisfied by |
|---|---|---|
| `Len` | `length()`, `is_empty()` | every container on this page except `NodePool`, plus `MpscQueue` and `LFQueue` |
| `Clear` | `clear()` | `Vec`, `Stack`, `Queue`, `List`, `HashMap`, `StrMap`, `BST`, `BTree`, `StaticVec`, `StaticMap`, `RingBuffer`, `String` |
| `Get` | `get(i) -> ?&T` (maps: `get(key)`), null when absent | |
| `Push` | `push(x)`: 1, or 0 when full / out of memory | |
| `Pop` | `pop() -> ?T`, null when empty | |
| `Enqueue` | `enqueue(x)`: 1, or 0 when full (`Chan`: blocks; 0 once closed) | |
| `Dequeue` | `dequeue() -> ?T`, null when empty (`Chan`: blocks; null once closed and drained) | |
| `Insert`, `Contains`, `Remove` | `insert(k, v)`, `contains(k)`, `remove(k) -> ?V` | |
| `Sequence` | `Len + Get + Push` | `Vec`, `StaticVec` |
| `Lifo` | `Len + Push + Pop` | `Vec`, `Stack`, `StaticVec` |
| `Fifo` | `Enqueue + Dequeue` | `Queue`, `RingBuffer` (one byte at a time), and from [`std/sync`](/stdlib/sync): `Chan`, `MpscQueue`, `LFQueue` |
| `CountedFifo` | `Fifo + Len` | `Queue`, `RingBuffer`, `MpscQueue`, `LFQueue` |
| `Map` | `Len + Insert + Get + Contains + Remove` | `HashMap`, `StrMap`, `BST`, `BTree` (`StaticMap` has no `remove`: it is `Len + Insert + Get + Contains`) |

Every map's `insert` returns nonzero on success and 0 when it is full or out of memory.

The helpers ask only for the capability they use:

| Function | Bound | Does |
|---|---|---|
| `push_all(&s, items)` | `S: Push` | pushes the slice `items` in order, stopping at the first one refused; returns how many went in |
| `enqueue_all(&q, items)` | `Q: Enqueue` | the same with `enqueue` (stops when full, or once a `Chan` is closed) |
| `drain_into(&q, &out)` | `Q: Dequeue`, `S: Push` | dequeues until `q` is empty (a `Chan`: closed and drained) and pushes each element onto `out`, in order; returns how many moved. Meant for a growable `out` such as a `Vec` |

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

A trait checks each method's name, static-ness and parameter count; the parameter types are checked where a generic body is instantiated. So `Vec`'s `contains(key, cmp)` (two parameters) keeps `Vec` out of `Contains` and `Map`, while `String`'s `push(cstr)` still counts as `Push` — `push_all` over a `String` fails where it passes a `char`. Iteration is not a trait: `foreach` exists on most containers, but its callback shape differs per container (`Vec`: `(&T, index)`, `List`: `(&T)`, maps: `(const &K, &V)`).



## vec -- Dynamic Array {#vec}

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

Sorting is introsort (median-of-three quicksort, heapsort fallback, insertion sort for short runs). `sort(cmp)` calls through a function pointer; `vec_sort` / `vec_sort_by` take a compile-time [ordering](#orderings) and inline every comparison — on 1,000,000 shuffled `int`s that is about 1.9× faster than C's `qsort` (see [Benchmarks](/benchmarks#collections)).

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



## map -- Hash Maps {#map}

```c
#include <std/collections/map.h>
```

Open addressing with linear probing; the table grows at a 0.75 load factor, and tombstones count toward it so remove-heavy workloads rehash instead of degrading. Hashes, keys, values and slot states live in separate arrays so a probe touches as little memory as possible.

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

Keys are hashed and compared **by their bytes**, so `K` must be fully described by its bytes: integers, enums, pointers, padding-free structs. For 1-, 2-, 4- and 8-byte keys the map reads the key as one word, hashes it with murmur3's 64-bit finalizer and compares with a single integer compare — the size test is an `if const`, resolved at compile time per instantiation. Other sizes use FNV-1a and a byte compare. Use `StrMap` for text keys.

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



## list -- Doubly Linked List {#list}

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

Nodes come from the list's own [`NodePool`](#node-pool): pushing doesn't call the allocator per element, popped nodes are recycled, and `free()` releases whole chunks instead of walking the list. A push-then-pop of 1,000,000 elements runs about 2× faster than a C `malloc`/`free`-per-node list.



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

`Queue` is a power-of-two ring buffer: indices wrap with a mask, and growing re-lays the elements out linearly.

```c
struct Queue<int> q = std::Queue::new();
q.enqueue(1); q.enqueue(2);
int first = q.dequeue().default(0);    // 1
q.free();
```



## bst -- Binary Search Tree {#bst}

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

Unbalanced, but every operation is **iterative**: a degenerate (sorted-insert) tree costs O(n) per lookup yet never overflows a small bare-metal stack. In-order traversal is Morris traversal (O(1) extra space, the tree is restored afterwards). Nodes come from a per-tree [`NodePool`](#node-pool).

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



## btree -- Ordered B-Tree Map {#btree}

```c
#include <std/collections/btree.h>
```

A B-tree (minimum degree `BTREE_ORDER` = 4, up to 7 keys per node) keyed by `unsigned long`, with every node taken from a fixed pool of `BTREE_POOL_SIZE` (256) nodes inside the struct — no heap use after construction, deterministic O(log n). Nodes emptied by a merge go back on a free list, so insert/remove churn doesn't exhaust the pool.

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

The pool makes a `BTree` large (tens of KB), so keep it in static storage or allocate it, rather than putting it on the stack:

```c
static struct BTree<int> g_index;

void setup() {
    g_index.init();
    g_index.insert(42UL, 7);
    int v = g_index.get(42UL).default(0);
}
```

Node shape: `BTREE_MAX_KEYS` (7) keys and `BTREE_MAX_CHILD` (8) children per node.

## slice -- Bounds-Checked Views {#slice}

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



## static\_vec -- Zero-Heap Collections {#static-vec}

```c
#include <std/collections/static_vec.h>
```

Fixed-capacity containers over storage you provide (a static or stack array), so nothing is ever allocated — the right choice for interrupt handlers and MCUs without a heap.

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

The container keeps a region-less reference to the storage, so the array must outlive it.



## node\_pool -- Per-Container Node Arena {#node-pool}

```c
#include <std/collections/node_pool.h>
```

`NodePool<N>` is the allocator behind `List` and `BST`, usable for your own node-based structures. It is an arena: nodes are bump-allocated from chunks that double in size (8 … 1024 nodes), removed nodes go on a free list for reuse, and `free_all()` releases every chunk at once.

```c
generic<N>
struct NodePool {
    static struct NodePool<N> new();
    ?&heap N take();              // an uninitialised node; null on OOM
    void     give(&heap N node);  // recycle a node
    void     free_all();
};
```

Compared with one `std::alloc` per node, this drops the allocator's 16-byte safety header and size-class padding per node and makes tearing down a container O(chunks). A node handed back with `give()` is reused by the next `take()`: a reference kept to a removed element dangles exactly as it would after `dealloc()`.

Chunk sizing: the first chunk holds `NODE_POOL_FIRST_CHUNK` (8) nodes and each later chunk doubles, up to `NODE_POOL_MAX_CHUNK` (1024); each chunk has a `NODE_POOL_CHUNK_HDR` (16-byte) header so nodes stay 16-byte aligned.

## string -- Mutable String {#string-mutable-string}

```c
#include <std/collections/string.h>
```

A growable, heap-allocated, NUL-terminated byte string with 40+ methods. `data` is `&heap char`. Only construction is a free function — everything else is a method.

### Struct and Methods {#struct-and-methods}

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

### Constructors {#constructors}

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

### Example {#example}

Verified against a real compile/run:

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



## ringbuffer -- SPSC Lock-Free Ring Buffer {#ringbuffer-spsc-lock-free-ring-buffer}

```c
#include <std/collections/ringbuffer.h>
```

A single-producer / single-consumer **byte-oriented** power-of-two ring buffer. Uses atomic load/store on `head`/`tail` for correct producer/consumer ordering without OS locks. Suitable for ISR-to-task data transfer and audio pipelines.

Unlike the other collection types, `RingBuffer` is not element-based — it operates on raw byte streams. Use it with `unsigned char` buffers.

### Struct {#struct}

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

### Example {#example-1}

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

`RING_STATIC(name, N)` (declare storage + ring in one line) is a function-like macro, so it is only available under `--compat-preprocessor`.

::: info
`buf` has region `&static unsigned char` — the backing store must outlive the `RingBuffer` struct itself. Use `RING_STATIC` for embedded globals. For heap-backed use, cast inside an `unsafe {}` block and supply your own lifetime discipline.
:::

`RING_DEFAULT_CAP` (4096 bytes) is the default capacity; define it before including the header to change it.
