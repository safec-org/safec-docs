# Collections

`std/collections/` holds SafeC's container types. Every element container is a **generic struct** (see [Generics](/reference/generics#generic-structs-and-methods)): `Vec<T>`, `HashMap<K, V>`, `StrMap<V>`, `List<T>`, `Stack<T>`, `Queue<T>`, `BST<K, V, C>`, `BTree<V>`, `Slice<T>`, `StaticVec<T>` and `StaticMap<V>`. Elements are stored **by value** and the API never hands out `void*` or raw `T*`:

- element access returns `?&T`, a nullable reference that you must narrow before use — with `match`, `.default(x)`, `.is_null()`, or `std::expect_ref` (see [Nullable references](/reference/memory#nullable-references));
- removal returns `?T` (an optional value: `null` when there was nothing to remove);
- callbacks are typed `fn` values, and orderings are compile-time types (see [Orderings](#orderings)).

Each container is header-only: `#include <std/collections/vec.h>` is all you need, and every method is monomorphized for your element type, so element access inlines to plain loads and stores. Type arguments are written on the variable and inferred by the constructor from that target type:

```c
struct Vec<int> v = std::vec_new();                 // T = int, from the declaration
struct HashMap<int, long long> m = std::map_new();  // K, V from the declaration
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

## Orderings {#orderings}

```c
#include <std/cmp.h>
```

An *ordering* is any struct with `int cmp(const &T a, const &T b) const` returning `<0`, `0` or `>0`. Ordered containers and algorithms take the ordering as a **type parameter**, so after monomorphization each comparison is a direct call that the optimizer inlines — there is no function pointer on the hot path.

```c
generic<T> struct Less    { int cmp(const &T a, const &T b) const; };   // ascending, by < / >
generic<T> struct Greater { int cmp(const &T a, const &T b) const; };   // descending
generic<T> struct FnOrder { fn int(const &T, const &T) f; int cmp(...) const; };  // run-time comparator

generic<T> struct Less<T>    order_less();
generic<T> struct Greater<T> order_greater();
generic<T> struct FnOrder<T> order_fn(fn int(const &T, const &T) f);
```

Write your own ordering as a struct with a `cmp` method — for example to order records by one field — and it gets the same inlining. Use `FnOrder` only when the comparator is chosen at run time.



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
    int           extend(const &T items, unsigned long count);

    void          reverse();
    void          sort(fn int(const &T, const &T) cmp);
    long long     find(const &T key, fn int(const &T, const &T) cmp) const;   // -1 if absent
    int           contains(const &T key, fn int(const &T, const &T) cmp) const;
    struct Vec<T> clone() const;                       // shallow element copy
    void          foreach(fn void(&T, unsigned long) func);
    struct Vec<T> filter(fn int(const &T) pred) const;
    void          free();
};

generic<T>    struct Vec<T> vec_new();
generic<T>    struct Vec<T> vec_with_cap(unsigned long cap);
generic<T>    struct Vec<T> vec_from_arr(const &T items, unsigned long len);
generic<T, U> struct Vec<U> vec_map(const &Vec<T> v, fn U(const &T) f);

generic<T, C> void vec_sort_by(&Vec<T> v, C ord);   // compile-time ordering, inlined
generic<T>    void vec_sort(&Vec<T> v);              // vec_sort_by(v, Less<T>)
```

Sorting is introsort (median-of-three quicksort, heapsort fallback, insertion sort for short runs). `sort(cmp)` calls through a function pointer; `vec_sort` / `vec_sort_by` take a compile-time [ordering](#orderings) and inline every comparison — on 1,000,000 shuffled `int`s that is about 1.9× faster than C's `qsort` (see [Benchmarks](/benchmarks#collections-stdcollections-throughput-1000000-elements)).

```c
#include <std/collections/vec.h>
extern int printf(const char* fmt, ...);

static int is_even(const &int x) { return *x % 2 == 0; }

int main() {
    struct Vec<int> v = std::vec_new();
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

### HashMap\<K, V\>

```c
generic<K, V>
struct HashMap {
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

generic<K, V> struct HashMap<K, V> map_new();
generic<K, V> struct HashMap<K, V> map_with_cap(unsigned long cap);
```

Keys are hashed and compared **by their bytes**, so `K` must be fully described by its bytes: integers, enums, pointers, padding-free structs. For 1-, 2-, 4- and 8-byte keys the map reads the key as one word, hashes it with murmur3's 64-bit finalizer and compares with a single integer compare — the size test is an `if const`, resolved at compile time per instantiation. Other sizes use FNV-1a and a byte compare. Use `StrMap` for text keys.

### StrMap\<V\>

```c
generic<V>
struct StrMap {
    int           insert(const char* key, V val);   // copies the key
    ?&V           get(const char* key) const;
    int           contains(const char* key) const;
    ?V            remove(const char* key);
    unsigned long length() const;
    int           is_empty() const;
    void          clear();
    void          foreach(fn void(const char*, &V) func);
    void          free();
};

generic<V> struct StrMap<V> str_map_new();
```

```c
#include <std/collections/map.h>
extern int printf(const char* fmt, ...);

int main() {
    struct HashMap<int, long long> sq = std::map_new();
    int i = 0;
    while (i < 1000) { sq.insert(i, (long long)i * (long long)i); i = i + 1; }
    match (sq.get(12)) {
        case some(v): *v = *v + 1LL;          // 145
        case null: { }
    }
    long long gone = sq.remove(3).default(-1LL);   // 9

    struct StrMap<int> ages = std::str_map_new();
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

generic<T> struct List<T> list_new();
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
    int push(T value);  ?T pop();  ?&T peek() const;
    unsigned long length() const;  int is_empty() const;
    void clear();  int reserve(unsigned long n);  void free();
};
generic<T> struct Stack<T> stack_new();
generic<T> struct Stack<T> stack_with_cap(unsigned long cap);

generic<T>
struct Queue {
    int enqueue(T value);  ?T dequeue();  ?&T front() const;  ?&T back() const;
    unsigned long length() const;  int is_empty() const;
    void clear();  int reserve(unsigned long n);  void free();
};
generic<T> struct Queue<T> queue_new();
generic<T> struct Queue<T> queue_with_cap(unsigned long cap);
```

`Queue` is a power-of-two ring buffer: indices wrap with a mask, and growing re-lays the elements out linearly.

```c
struct Queue<int> q = std::queue_new();
q.enqueue(1); q.enqueue(2);
int first = q.dequeue().default(0);    // 1
q.free();
```



## bst -- Binary Search Tree {#bst}

```c
#include <std/collections/bst.h>
```

```c
generic<K, V, C>          // C: an ordering type, see #orderings
struct BST {
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

generic<K, V, C> struct BST<K, V, C> bst_new();             // stateless C (Less, Greater)
generic<K, V, C> struct BST<K, V, C> bst_new_with(C ord);   // e.g. order_fn(my_cmp)
```

Unbalanced, but every operation is **iterative**: a degenerate (sorted-insert) tree costs O(n) per lookup yet never overflows a small bare-metal stack. In-order traversal is Morris traversal (O(1) extra space, the tree is restored afterwards). Nodes come from a per-tree [`NodePool`](#node-pool).

```c
#include <std/collections/bst.h>
extern int printf(const char* fmt, ...);

static void show(const &int k, &int v) { unsafe { printf("%d=%d ", *k, *v); } }

int main() {
    struct BST<int, int, Less<int>> t = std::bst_new();
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
    int           insert(unsigned long key, V val);   // 0 ok, -1 if the pool is full
    ?&V           get(unsigned long key) const;
    int           contains(unsigned long key) const;
    ?V            remove(unsigned long key);
    unsigned long len() const;
    void          foreach(fn void(unsigned long, &V) cb);   // ascending
    void          clear();
};

generic<V> void btree_init(&BTree<V> t);
```

The pool makes a `BTree` large (tens of KB), so keep it in static storage or allocate it, rather than putting it on the stack:

```c
static struct BTree<int> g_index;

void setup() {
    std::btree_init(&g_index);
    g_index.insert(42UL, 7);
    int v = g_index.get(42UL).default(0);
}
```



## slice -- Bounds-Checked Views {#slice}

```c
#include <std/collections/slice.h>
```

```c
generic<T>
struct Slice {
    ?&T           ptr;   // first element; null for an empty slice
    unsigned long len;

    int             in_bounds(unsigned long idx) const;
    ?&T             get(unsigned long idx) const;
    &T              at(unsigned long idx) const;          // panics if out of range
    int             set(unsigned long idx, T val);
    struct Slice<T> sub(unsigned long start, unsigned long end) const;  // [start, end)
    unsigned long   length() const;
    int             is_empty() const;
    void            free();                                // only for slice_alloc()
};

generic<T> struct Slice<T> slice_of(&T first, unsigned long len);
generic<T> struct Slice<T> slice_alloc(unsigned long len);    // zeroed heap slice

// The same checks on a bare (first element, length) pair:
generic<T> ?&T  arr_at(&T first, unsigned long len, unsigned long idx);
generic<T> int  arr_set(&T first, unsigned long len, unsigned long idx, T val);
generic<T> void arr_fill(&T first, unsigned long len, T val);
generic<T> void arr_copy(&T dst, const &T src, unsigned long len);
generic<T> ?T   arr_min(const &T first, unsigned long len);   // null if empty
generic<T> ?T   arr_max(const &T first, unsigned long len);
generic<T> void arr_reverse(&T first, unsigned long len);
```

```c
int arr[8];
struct Slice<int> s = std::slice_of(&arr[0], 8UL);
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
    int push(T val);  ?T pop();  ?&T top() const;  ?&T get(unsigned long idx) const;
    unsigned long length() const;  int is_empty() const;  int is_full() const;  void clear();
};
generic<T> struct StaticVec<T> static_vec_init(&T storage, unsigned long cap);

generic<V> struct StaticMapSlot { unsigned long key; V val; int used; };
generic<V>
struct StaticMap {
    int insert(unsigned long key, V val);   // 0 if full
    ?&V get(unsigned long key) const;  int contains(unsigned long key) const;
    unsigned long length() const;  void clear();
};
generic<V> struct StaticMap<V> static_map_init(&StaticMapSlot<V> storage, unsigned long cap);
```

```c
static int g_buf[32];
static struct StaticMapSlot<int> g_slots[64];

void demo() {
    struct StaticVec<int> v = std::static_vec_init(&g_buf[0], 32UL);
    v.push(42);
    struct StaticMap<int> m = std::static_map_init(&g_slots[0], 64UL);
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
    ?&heap N take();              // an uninitialised node; null on OOM
    void     give(&heap N node);  // recycle a node
    void     free_all();
};
generic<N> struct NodePool<N> node_pool_new();
```

Compared with one `std::alloc` per node, this drops the allocator's 16-byte safety header and size-class padding per node and makes tearing down a container O(chunks). A node handed back with `give()` is reused by the next `take()`: a reference kept to a removed element dangles exactly as it would after `dealloc()`.



## string -- Mutable String

```c
#include <std/collections/string.h>
```

A growable, heap-allocated, NUL-terminated byte string with 40+ methods. `data` is `&heap char`. Only construction is a free function — everything else is a method.

### Struct and Methods

```c
struct String {
    &heap char    data;
    unsigned long len;
    unsigned long cap;

    // Access
    unsigned long  length() const;
    int            is_empty() const;
    const char*    as_ptr() const;
    int            char_at(unsigned long idx) const;         // -1 if OOB
    void           set_char(unsigned long idx, char c);

    // Capacity
    int            reserve(unsigned long additional);
    void           shrink_to_fit();

    // Append
    int            push_char(char c);
    int            push(const char* cstr);
    int            push_n(const char* data, unsigned long n);  // n raw bytes, need not be NUL-terminated
    int            push_str(&stack String other);
    int            push_int(long long v);
    int            push_uint(unsigned long long v);
    int            push_float(double v, int decimals);
    int            push_bool(int v);

    // Modification
    void           clear();
    void           truncate(unsigned long new_len);
    int            insert(unsigned long idx, const char* cstr);
    int            remove_range(unsigned long start, unsigned long end);
    void           replace_char(char from, char to);
    int            replace(const char* from, const char* to);       // first occurrence
    int            replace_all(const char* from, const char* to);
    void           reverse();                                       // in-place byte reversal
    int            pop_char();                                      // -1 if empty

    // Search
    long long      index_of(const char* needle) const;
    long long      last_index_of(const char* needle) const;
    int            contains(const char* needle) const;
    int            starts_with(const char* prefix) const;
    int            ends_with(const char* suffix) const;
    int            count(const char* needle) const;
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
    struct String  strip_prefix(const char* prefix) const;
    struct String  strip_suffix(const char* suffix) const;
    struct String  repeat(unsigned long n) const;
    struct String  capitalize() const;

    // Split -- writes into caller-provided 'out' array (max slots); returns
    // items written; overflow puts the remainder in the last slot
    unsigned long  split(const char* delim, &stack String out, unsigned long max) const;
    unsigned long  split_lines(&stack String out, unsigned long max) const;
    unsigned long  split_whitespace(&stack String out, unsigned long max) const;

    // Comparison
    int            eq(&stack String other) const;
    int            eq_cstr(const char* other) const;
    int            cmp(&stack String other) const;         // <0, 0, >0
    int            lt(&stack String other) const;
    int            gt(&stack String other) const;
    int            eq_ignore_case(&stack String other) const;
    int            eq_cstr_ignore_case(const char* other) const;

    // Query
    int            is_ascii() const;
    int            is_numeric() const;
    int            is_alphanumeric() const;

    // Conversion
    long long      parse_int(int* ok) const;
    double         parse_float(int* ok) const;

    struct String  clone() const;
    void           free();
};
```

### Constructors

```c
struct String string_new();
struct String string_from(const char* s);
struct String string_from_n(const char* s, unsigned long n);
struct String string_with_cap(unsigned long cap);
struct String string_repeat(const char* s, unsigned long n);
struct String string_join(const char* sep, &stack String parts, unsigned long count);
```

### Example

Verified against a real compile/run:

```c
#include <std/collections/string.h>
extern int printf(const char* fmt, ...);

int main() {
    struct String s = std::string_from("Hello");
    s.push(", SafeC!");
    printf("%s\n", s.as_ptr());              // Hello, SafeC!
    printf("contains=%d\n", s.contains("SafeC"));  // 1

    struct String upper = s.to_upper();
    printf("%s\n", upper.as_ptr());          // HELLO, SAFEC!

    struct String num = std::string_new();
    num.push("value = ");
    num.push_int(42LL);
    printf("%s\n", num.as_ptr());            // value = 42

    num.free();
    upper.free();
    s.free();
    return 0;
}
```



## ringbuffer -- SPSC Lock-Free Ring Buffer

```c
#include <std/collections/ringbuffer.h>
```

A single-producer / single-consumer **byte-oriented** power-of-two ring buffer. Uses atomic load/store on `head`/`tail` for correct producer/consumer ordering without OS locks. Suitable for ISR-to-task data transfer and audio pipelines.

Unlike the other collection types, `RingBuffer` is not element-based — it operates on raw byte streams. Use it with `unsigned char` buffers.

### Struct

```c
struct RingBuffer {
    &static unsigned char buf;   // backing store — must be static-lifetime
    unsigned long         cap;   // capacity in bytes (must be power of two)
    unsigned long         mask;  // cap - 1  (for fast modulo)
    volatile unsigned long head; // write position (producer)
    volatile unsigned long tail; // read position (consumer)

    unsigned long readable() const;          // bytes available to read
    unsigned long writable() const;          // bytes that can be written
    int           is_empty() const;
    int           is_full() const;

    unsigned long write(const &stack unsigned char data, unsigned long len);
    unsigned long read(&stack unsigned char out, unsigned long len);
    unsigned long peek(&stack unsigned char out, unsigned long len) const;
    unsigned long discard(unsigned long len);
    void          clear();
};

// Initialise with an existing static-lifetime backing store.
// `cap` must be a power of two.
struct RingBuffer ring_init(&static unsigned char buf, unsigned long cap);
```

### Example

```c
#include <std/collections/ringbuffer.h>
extern int printf(const char* fmt, ...);

static unsigned char g_storage[128];        // static backing store, no heap

int main() {
    struct RingBuffer rb = std::ring_init(&g_storage[0], 128UL);
    unsigned char tx[5];
    unsafe { tx[0] = 'H'; tx[1] = 'e'; tx[2] = 'l'; tx[3] = 'l'; tx[4] = 'o'; }
    rb.write(&tx[0], 5UL);
    unsafe { printf("readable: %lu\n", rb.readable()); }   // 5

    unsigned char rx[5];
    rb.read(&rx[0], 5UL);
    unsafe { printf("%c%c\n", rx[0], rx[4]); }             // Ho
    return 0;
}
```

`RING_STATIC(name, N)` (declare storage + ring in one line) is a function-like macro, so it is only available under `--compat-preprocessor`.

::: info
`buf` has region `&static unsigned char` — the backing store must outlive the `RingBuffer` struct itself. Use `RING_STATIC` for embedded globals. For heap-backed use, cast inside an `unsafe {}` block and supply your own lifetime discipline.
:::



