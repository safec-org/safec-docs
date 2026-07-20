# Collections

SafeC provides eleven collection modules in `std/collections/`. These predate SafeC's generic-struct support (see [Generics](/reference/generics#generic-structs-and-methods)) and haven't been migrated to it: the structs themselves still use `void*`/raw-pointer fields internally for their element storage, with `generic<T>` wrapper *functions* layered on top for type-safe access, and (for several of them) real, non-generic instance *methods* for the type-erased operations (`v.length()`, `v.push(const void* elem)`, `m.get(const void* key)`, and so on). `T` is inferred from `T`-typed arguments at call sites via monomorphization, or — for a handful of wrappers where `T` only appears in the *return* type — from an explicit target type at the call site (see the box below). SafeC still has no `foo<int>(...)` explicit-type-argument syntax; inference is always implicit.

This design keeps a single compiled struct per collection type (no per-`T` code bloat), while preserving full type safety at every call site — an important property for embedded targets where binary size matters.

`ringbuffer` is an exception: it is byte-oriented and operates on `unsigned char` streams directly, using `&stack`/`&static` region annotations instead of `void*`.

::: tip Generic wrappers that return `T*` need a typed target to infer from
`vec_at`, `map_get_t`, `btree_get`, `bst_get_t`, `stack_peek_t`, `queue_front_t`, `list_front_t`, and `list_back_t` all share one shape: `generic<T> T* the_fn(..., non-T-typed args...)` — the type parameter `T` appears *only* in the return type. Inference is still call-site-only, but as a fallback, it also matches the return type against the *expected* type of the call — an explicit declared-variable type (`int* p = vec_at(&v, 1UL);`) or an existing typed variable's type on assignment (`p = vec_at(&v, 1UL);`) — so writing the call directly into one of those two positions works and is the idiomatic form used throughout this page. It does **not** work with no target at all (a bare `vec_at(&v, 1UL);` statement, or passing the call straight into another function's argument) — use the type-erased method/function and cast in those cases, e.g. `(int*)v.get_raw(idx)`.
:::

## slice -- Bounds-Checked Array Access

```c
#include "collections/slice.h"
```

A `Slice` wraps a typed pointer + length, providing bounds-checked access. The struct stores `void*` and `elem_size`, with real instance methods for access; a `generic<T>` constructor and a handful of `generic<T>` free functions provide type-safe construction and whole-array operations.

### Struct and Methods

```c
struct Slice {
    void*         ptr;        // pointer to first element
    unsigned long len;        // number of elements
    unsigned long elem_size;  // stride in bytes

    int           in_bounds(unsigned long idx) const;
    void*         get_raw(unsigned long idx) const;    // NULL if OOB
    int           set_raw(unsigned long idx, const void* val);
    struct Slice  sub(unsigned long start, unsigned long end) const;
    unsigned long length() const;
    int           is_empty() const;
    void          free();
};
```

### Construction

```c
struct Slice  slice_void_from(void* ptr, unsigned long len, unsigned long elem_size);
struct Slice  slice_void_alloc(unsigned long len, unsigned long elem_size);

generic<T> struct Slice slice_of(&stack T ptr, unsigned long len);
```

`slice_of<T>` takes a `&stack T` reference to the *first* element (`&arr[0]`, not the bare array) — `T` is inferred from it.

### Generic Array Functions

These operate on raw `T*` arrays with explicit length parameters — pass an actual `T*` variable (an array name alone doesn't infer; assign it to a pointer variable first):

```c
generic<T> T*   arr_at(T* ptr, unsigned long len, unsigned long idx);   // NULL if OOB
generic<T> void arr_set(T* ptr, unsigned long len, unsigned long idx, T val);
generic<T> T    arr_get(T* ptr, unsigned long idx);
generic<T> void arr_fill(T* ptr, unsigned long len, T val);
generic<T> void arr_copy(T* dst, T* src, unsigned long len);
generic<T> T    arr_min(T* ptr, unsigned long len);
generic<T> T    arr_max(T* ptr, unsigned long len);
generic<T> void arr_reverse(T* ptr, unsigned long len);
```

### Example

Verified against a real compile/run:

```c
#include <std/collections/slice.sc>

int main() {
    int data[5];
    data[0] = 10; data[1] = 20; data[2] = 30; data[3] = 40; data[4] = 50;
    int* dp = data;   // arr_* wants an actual T*, not the bare array

    struct Slice s = std::slice_of(&data[0], 5UL);
    printf("len=%lu\n", s.length());              // 5
    printf("in_bounds(10)=%d\n", s.in_bounds(10UL)); // 0

    unsafe {
        int* p = (int*)s.get_raw(2UL);
        printf("s[2]=%d\n", *p);                   // 30
    }

    int* q = std::arr_at(dp, 5UL, 3UL);
    unsafe { printf("arr_at(3)=%d\n", *q); }        // 40

    std::arr_reverse(dp, 5UL);
    printf("data[0]=%d\n", data[0]);                // 50
    return 0;
}
```

---

## vec -- Dynamic Array

```c
#include "collections/vec.h"
```

A dynamic array with automatic growth. O(1) amortized push/pop. `data` is `&heap void`.

### Struct and Methods

```c
struct Vec {
    &heap void    data;
    unsigned long len;
    unsigned long cap;
    unsigned long elem_size;

    // Capacity
    int           reserve(unsigned long new_cap);
    void          shrink();
    unsigned long length() const;
    unsigned long total_capacity() const;
    int           is_empty() const;

    // Element access (type-erased)
    &heap void    get_raw(unsigned long idx);   // NULL if OOB
    int           set_raw(unsigned long idx, const void* elem);
    &heap void    front_raw();
    &heap void    back_raw();

    // Mutation
    int           push(const void* elem);
    int           pop(void* out);
    int           insert(unsigned long idx, const void* elem);
    int           remove(unsigned long idx, void* out);
    void          clear();
    int           extend(const void* arr, unsigned long count);

    // Algorithms
    void          reverse();
    void          sort(void* cmp);                          // cmp: int(*)(const void*, const void*)
    long long     find(const void* key, void* cmp) const;    // -1 if not found
    int           contains(const void* key, void* cmp) const;
    struct Vec    clone() const;
    void          foreach(void* func);                       // func: void(*)(void* elem, unsigned long idx)
    struct Vec    filter(void* pred) const;                  // pred: int(*)(const void*)
    struct Vec    map_raw(unsigned long out_elem_size, void* func) const;

    void          free();
};
```

### Constructors

```c
struct Vec vec_new(unsigned long elem_size);
struct Vec vec_with_cap(unsigned long elem_size, unsigned long cap);
```

### Generic Wrappers

```c
generic<T> int       vec_push_t(&stack Vec v, T val);
generic<T> T*        vec_at(&stack Vec v, unsigned long idx);      // see tip above -- needs a typed target
generic<T> int       vec_pop_t(&stack Vec v, T* out);
generic<T> struct Vec vec_from_arr(T* arr, unsigned long len);
```

`vec_pop_t`'s `out` parameter wants an actual `T*` (a raw pointer, not a `&stack T` reference) — get one with an `unsafe` cast, as in the example.

### Example

Verified against a real compile/run:

```c
#include <std/mem.sc>
#include <std/collections/vec.sc>

int main() {
    struct Vec v = std::vec_new(sizeof(int));

    std::vec_push_t(&v, 10);
    std::vec_push_t(&v, 20);
    std::vec_push_t(&v, 30);

    int* p = std::vec_at(&v, 1UL);   // T=int inferred from the 'int*' target
    unsafe { printf("v[1]=%d\n", *p); }   // 20

    int last = 0;
    int* lastp;
    unsafe { lastp = (int*)&last; }
    std::vec_pop_t(&v, lastp);
    printf("popped=%d\n", last);   // 30

    printf("len=%lu\n", v.length());   // 2

    v.free();
    return 0;
}
```

---

## string -- Mutable String

```c
#include "collections/string.h"
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
#include <std/mem.sc>
#include <std/str.sc>
#include <std/convert.sc>
#include <std/collections/string.sc>

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

---

## stack -- LIFO Stack

```c
#include "collections/stack.h"
```

A last-in-first-out stack backed by a growable array. O(1) amortized push/pop. Free-function API — this module hasn't picked up struct methods. `s` is a region-less `&Stack` reference (not a raw pointer) — pass `&s` at the call site exactly as before; it now accepts a stack-local, static/global, or heap-owned `Stack` interchangeably, whichever the caller happens to have.

### Struct

```c
struct Stack {
    void*         data;
    unsigned long top;        // number of elements
    unsigned long cap;
    unsigned long elem_size;
};
```

### API

```c
// Lifecycle
struct Stack stack_new(unsigned long elem_size);
struct Stack stack_with_cap(unsigned long elem_size, unsigned long cap);
void         stack_free(&Stack s);

// Core operations
int           stack_push(&Stack s, const void* elem);   // 1 on success
int           stack_pop(&Stack s, void* out);            // 0 if empty
void*         stack_peek(&Stack s);                      // NULL if empty
unsigned long stack_len(&Stack s);
int           stack_is_empty(&Stack s);
void          stack_clear(&Stack s);
```

### Generic Wrappers

```c
generic<T> int stack_push_t(&Stack s, T val);
generic<T> T*  stack_peek_t(&Stack s);   // see tip above -- needs a typed target
generic<T> int stack_pop_t(&Stack s, T* out);
```

### Example

```c
#include <std/collections/stack.sc>

int main() {
    struct Stack s = std::stack_new(sizeof(int));

    std::stack_push_t(&s, 10);
    std::stack_push_t(&s, 20);
    std::stack_push_t(&s, 30);

    int* top = std::stack_peek_t(&s);   // T=int inferred from the 'int*' target
    unsafe { printf("%d\n", *top); }    // 30

    int val = 0;
    int* valp;
    unsafe { valp = (int*)&val; }
    std::stack_pop_t(&s, valp);
    printf("%d\n", val);        // 30

    std::stack_free(&s);
    return 0;
}
```

---

## queue -- FIFO Queue

```c
#include "collections/queue.h"
```

A first-in-first-out circular buffer queue. Amortized O(1) enqueue/dequeue. Grows automatically when full. Free-function API. `q` is a region-less `&Queue` reference — call sites are unchanged (`&q`), and it now accepts a `Queue` living in any region.

### Struct

```c
struct Queue {
    void*         data;
    unsigned long head;       // index of front element
    unsigned long tail;       // index where next element will be written
    unsigned long len;
    unsigned long cap;
    unsigned long elem_size;
};
```

### API

```c
// Lifecycle
struct Queue queue_new(unsigned long elem_size);
struct Queue queue_with_cap(unsigned long elem_size, unsigned long cap);
void         queue_free(&Queue q);

// Core operations
int           queue_enqueue(&Queue q, const void* elem);
int           queue_dequeue(&Queue q, void* out);
void*         queue_front(&Queue q);    // peek front; NULL if empty
void*         queue_back(&Queue q);     // peek back; NULL if empty
unsigned long queue_len(&Queue q);
int           queue_is_empty(&Queue q);
void          queue_clear(&Queue q);
```

### Generic Wrappers

```c
generic<T> int queue_enqueue_t(&Queue q, T val);
generic<T> T*  queue_front_t(&Queue q);   // see tip above -- needs a typed target
generic<T> int queue_dequeue_t(&Queue q, T* out);
```

### Example

```c
#include <std/collections/queue.sc>

int main() {
    struct Queue q = std::queue_new(sizeof(int));

    std::queue_enqueue_t(&q, 1);
    std::queue_enqueue_t(&q, 2);
    std::queue_enqueue_t(&q, 3);

    int val = 0;
    int* valp;
    unsafe { valp = (int*)&val; }
    std::queue_dequeue_t(&q, valp);
    printf("%d\n", val);   // 1 (FIFO order)

    int* front = std::queue_front_t(&q);   // T=int inferred from the 'int*' target
    unsafe {
        printf("%d\n", *front);   // 2
    }

    std::queue_free(&q);
    return 0;
}
```

---

## list -- Doubly Linked List

```c
#include "collections/list.h"
```

A doubly linked list with push/pop on both ends, search, removal, and in-order traversal. Free-function API. `l` is a region-less `&List` reference — call sites are unchanged (`&l`). `next`/`prev`/`head`/`tail` are `?&heap ListNode` (nullable, heap-owned) rather than raw pointers — the *implementation* still walks them with ordinary raw-pointer chasing inside `unsafe {}` (a raw pointer and a `?&heap T` field convert to each other implicitly there, with no cast needed), but everything the header exposes to a caller is null-checked.

### Structs

```c
struct ListNode {
    void*           data;
    ?&heap ListNode next;
    ?&heap ListNode prev;
};

struct List {
    ?&heap ListNode head;
    ?&heap ListNode tail;
    unsigned long   len;
    unsigned long   elem_size;
};
```

### API

```c
// Lifecycle
struct List list_new(unsigned long elem_size);
void        list_free(&List l);

// Push / Pop
int           list_push_front(&List l, const void* elem);
int           list_push_back(&List l, const void* elem);
int           list_pop_front(&List l, void* out);
int           list_pop_back(&List l, void* out);
void*         list_front(&List l);      // NULL if empty
void*         list_back(&List l);       // NULL if empty
unsigned long list_len(&List l);
int           list_is_empty(&List l);
void          list_clear(&List l);

// Search & Iteration
?&heap ListNode list_find(&List l, const void* val, void* cmp);   // empty if not found; cmp: int(*)(const void*, const void*)
int             list_contains(&List l, const void* val, void* cmp);
void            list_remove_node(&List l, &heap ListNode node);   // node must already be in the list
int             list_remove(&List l, const void* val, void* cmp);
void            list_foreach(&List l, void* fn);  // fn: void(*)(void* data)

// Reorder
void list_reverse(&List l);
```

### Generic Wrappers

```c
generic<T> int list_push_back_t(&List l, T val);
generic<T> T*  list_front_t(&List l);   // see tip above -- needs a typed target
generic<T> T*  list_back_t(&List l);    // see tip above -- needs a typed target
```

::: warning No `list_push_front_t`
Only `list_push_back_t` exists as a generic wrapper — there's no generic front-push. Use `list_push_front(&List l, const void* elem)` with an `&x` (through `unsafe`) if you need to push onto the front with a typed value; a previous version of this page's example called a `list_push_front_t` that isn't actually declared anywhere in `list.h`.
:::

### Example

Verified against a real compile/run:

```c
#include <std/collections/list.sc>

int main() {
    struct List l = std::list_new(sizeof(int));

    std::list_push_back_t(&l, 10);
    std::list_push_back_t(&l, 20);

    int five = 5;
    unsafe { std::list_push_front(&l, (const void*)&five); }

    int* front = std::list_front_t(&l);   // T=int inferred from the 'int*' target
    int* back = std::list_back_t(&l);
    unsafe {
        printf("%d\n", *front);   // 5
        printf("%d\n", *back);    // 20
    }

    std::list_reverse(&l);

    front = std::list_front_t(&l);
    unsafe {
        printf("%d\n", *front);   // 20
    }

    std::list_free(&l);
    return 0;
}
```

---

## map -- Hash Map

```c
#include "collections/map.h"
```

An open-addressing hash map with linear probing and a djb2 hash function. Load factor threshold is 0.75; resizes automatically. Keys are compared byte-by-byte (`memcmp`). Use the `str_map_*` variants for C-string keys.

### Struct and Methods

```c
struct MapEntry {
    void*        key;
    void*        val;
    unsigned int hash;
    int          state;  // 0=empty, 1=occupied, 2=tombstone
};

struct HashMap {
    struct MapEntry* buckets;
    unsigned long    cap;         // must be power of 2
    unsigned long    len;         // live entries
    unsigned long    tombstones;  // removed-but-not-reclaimed slots -- counted toward
                                   // the resize threshold alongside 'len' so a remove-heavy
                                   // workload can't quietly degrade every probe toward O(cap)
    unsigned long    key_size;
    unsigned long    val_size;

    int           insert(const void* key, const void* val);
    void*         get(const void* key) const;    // NULL if missing
    int           contains(const void* key) const;
    int           remove(const void* key);
    unsigned long length() const;
    int           is_empty() const;
    void          clear();
    void          foreach(void* func);   // func: void(*)(const void* key, void* val)

    void          free();
};
```

### Constructors

```c
struct HashMap map_new(unsigned long key_size, unsigned long val_size);
struct HashMap map_with_cap(unsigned long key_size, unsigned long val_size, unsigned long cap);
```

### String-Key Convenience

For maps keyed by `const char*` strings — these remain free functions, including `free`. `m` is a region-less `&HashMap` reference (call sites unchanged, `&m`):

```c
struct HashMap str_map_new(unsigned long val_size);
int   str_map_insert(&HashMap m, const char* key, const void* val);
void* str_map_get(&HashMap m, const char* key);
int   str_map_contains(&HashMap m, const char* key);
int   str_map_remove(&HashMap m, const char* key);
// no str_map_free -- str_map_new returns a struct HashMap, so free it
// the same way as any other HashMap: sm.free()
```

### Generic Wrappers

```c
generic<T> int map_insert_t(&stack HashMap m, const void* key, T val);
generic<T> T*  map_get_t(&stack HashMap m, const void* key);   // see tip above -- needs a typed target
```

### Example

Verified against a real compile/run:

```c
#include <std/mem.sc>
#include <std/str.sc>
#include <std/collections/map.sc>

int main() {
    // Integer-keyed map
    struct HashMap m = std::map_new(sizeof(int), sizeof(int));
    int key = 42;
    std::map_insert_t(&m, &key, 100);

    unsafe {
        int* found = std::map_get_t(&m, (const void*)&key);   // T=int inferred from the 'int*' target
        if (found != (int*)0) {
            printf("%d\n", *found);   // 100
        }
    }
    m.free();

    // String-keyed map
    struct HashMap sm = std::str_map_new(sizeof(double));
    double pi = 3.14159;
    unsafe { std::str_map_insert(&sm, "pi", (const void*)&pi); }

    unsafe {
        double* p = (double*)std::str_map_get(&sm, "pi");
        if (p != (double*)0) {
            printf("%f\n", *p);   // 3.14159
        }
    }
    sm.free();

    return 0;
}
```

---

## btree -- Ordered B-Tree Map

```c
#include "collections/btree.h"
```

A pool-based B-tree (order 4) backed by a **256-node static pool** — inserts beyond that fail rather than growing further. O(log n) insert/lookup. All keys are `unsigned long`; values are `void*`. Provides sorted in-order traversal.

### Struct and Methods

```c
#define BTREE_ORDER      4      // min keys per non-root node
#define BTREE_MAX_KEYS   7      // 2*ORDER - 1
#define BTREE_MAX_CHILD  8      // 2*ORDER
#define BTREE_POOL_SIZE  256    // max nodes in the static pool

struct BTreeNode {
    unsigned long keys[BTREE_MAX_KEYS];
    void*         vals[BTREE_MAX_KEYS];
    unsigned long children[BTREE_MAX_CHILD];  // indices into the node pool, 0 = null
    int           n;      // current number of keys
    int           leaf;   // 1 if leaf node
};

struct BTree {
    struct BTreeNode pool[BTREE_POOL_SIZE];
    int              pool_used;
    unsigned long    root;    // index into pool, 0 = empty tree
    unsigned long    count;   // total key-value pairs

    int           insert(unsigned long key, void* val);   // 0 on success, -1 if pool full
    void*         get(unsigned long key) const;           // NULL if missing
    int           remove(unsigned long key);               // 1 if found+removed
    unsigned long len() const;
    int           contains(unsigned long key) const;
    void          foreach(void* cb, void* user) const;     // cb: void(*)(key, val, user), ascending order
    void          clear();
};
```

::: warning No `btree_new()` -- zero-initialize instead
There's no constructor free function. Declare a plain `struct BTree t;` and set `pool_used = 0; root = 0UL; count = 0UL;` yourself before use — verified working below. (`pool` itself doesn't need zeroing; nodes are claimed from it lazily as `pool_used` grows.)
:::

### Generic Wrappers

Values are stored by pointer; the caller manages the pointee's lifetime.

```c
generic<T> int  btree_insert(&stack BTree t, unsigned long key, T* val);
generic<T> T*   btree_get(const &stack BTree t, unsigned long key);   // see tip above -- needs a typed target
```

`btree_insert`'s `val` wants an actual `T*` — get one with an `unsafe` cast, as in the example.

### Example

Verified against a real compile/run:

```c
#include <std/collections/btree.sc>

void print_entry(unsigned long key, void* val, void* user) {
    unsafe { printf("%lu -> %d\n", key, *(int*)val); }
}

int main() {
    struct BTree t;
    t.pool_used = 0;
    t.root      = 0UL;
    t.count     = 0UL;

    int v10 = 100; int v20 = 200; int v5 = 50;
    int* p10;
    int* p20;
    int* p5;
    unsafe { p10 = (int*)&v10; p20 = (int*)&v20; p5 = (int*)&v5; }

    std::btree_insert(&t, 10UL, p10);
    std::btree_insert(&t, 20UL, p20);
    std::btree_insert(&t,  5UL, p5);

    int* found = std::btree_get(&t, 10UL);   // T=int inferred from the 'int*' target
    unsafe {
        printf("%d\n", *found);   // 100
    }

    // In-order traversal: 5, 10, 20
    t.foreach((void*)print_entry, (void*)0);
    return 0;
}
```

---

## ringbuffer -- SPSC Lock-Free Ring Buffer

```c
#include "collections/ringbuffer.h"
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

### Static macro

The `RING_STATIC` macro is the idiomatic way to create a ring buffer with no heap allocation:

```c
// Declare + initialise a 256-byte ring buffer backed by a static array
RING_STATIC(uart_rx, 256);

// Expands to:
//   static unsigned char uart_rx_storage_[256];
//   static struct RingBuffer uart_rx = { uart_rx_storage_, 256, 255, 0, 0 };
```

### Example

```c
#include "collections/ringbuffer.h"

// Static 128-byte buffer — no heap required
RING_STATIC(rb, 128);

int main() {
    unsigned char tx[] = {'H', 'e', 'l', 'l', 'o'};
    rb.write(tx, 5);

    printf("readable: %lu\n", rb.readable());  // 5

    unsigned char rx[5];
    rb.read(rx, 5);

    int i = 0;
    while (i < 5) { printf("%c", rx[i]); i = i + 1; }
    printf("\n");  // Hello

    return 0;
}
```

::: info
`buf` has region `&static unsigned char` — the backing store must outlive the `RingBuffer` struct itself. Use `RING_STATIC` for embedded globals. For heap-backed use, cast inside an `unsafe {}` block and supply your own lifetime discipline.
:::

---

## static\_collections -- Zero-Heap Compile-Time Collections

```c
#include "collections/static_vec.h"
```

Header-only macros that declare fixed-capacity collections on the stack or as static globals. No heap allocation, no function call overhead. All element access goes through raw-pointer field access, so **every macro invocation that touches the data needs an enclosing `unsafe {}` block**.

### Static Vec

```c
STATIC_VEC_DECL(MyVec, int, 32);   // declares: struct MyVec { int data[32]; unsigned long len; unsigned long cap; }
MyVec v;
STATIC_VEC_INIT(&v, 32);           // note: pointer + capacity NUMBER, not the type name

unsafe {
    STATIC_VEC_PUSH(&v, 42);       // (vec)->data[(vec)->len++] = val -- 1 on success, 0 if full
    STATIC_VEC_POP(&v, &out);      // *out = (vec)->data[--(vec)->len] -- 1 on success, 0 if empty
    STATIC_VEC_TOP(&v);            // (vec)->data[(vec)->len - 1]
    STATIC_VEC_AT(&v, i);          // (vec)->data[i], unchecked
    STATIC_VEC_LEN(&v);            // (vec)->len
    STATIC_VEC_EMPTY(&v);          // (vec)->len == 0
}
```

::: warning `STATIC_VEC_INIT` takes a capacity number, not the type name
`STATIC_VEC_INIT(vec, Cap)` expands to `(vec)->len = 0; (vec)->cap = (Cap);` — the second
argument is the same numeric capacity you passed to `STATIC_VEC_DECL`, e.g.
`STATIC_VEC_INIT(&v, 32)`, not the struct's type name. A previous version of this page
showed `STATIC_VEC_INIT(v, MyVec)`, which doesn't match the macro's real parameters.
:::

### Static Map (Open-Addressing Hash)

```c
STATIC_MAP_DECL(MyMap, 64);   // key=unsigned long, val=void*, 64 buckets
MyMap m;
STATIC_MAP_INIT(&m, 64);

unsafe {
    STATIC_MAP_INSERT(&m, key, &val);   // insert or update; key is unsigned long
    STATIC_MAP_GET(&m, key);            // returns void*, or NULL
    STATIC_MAP_LEN(&m);                 // (m)->count
}
```

`STATIC_MAP_DECL` has no element-type parameter (unlike `STATIC_VEC_DECL`) — values are always stored as `void*`, cast on the way out.

### Example

Verified against a real compile/run:

```c
#include <std/collections/static_vec.h>

STATIC_VEC_DECL(IntVec, int, 16);
STATIC_MAP_DECL(IntMap, 8);

int main() {
    IntVec v;
    STATIC_VEC_INIT(&v, 16);
    unsafe {
        STATIC_VEC_PUSH(&v, 10);
        STATIC_VEC_PUSH(&v, 20);
        STATIC_VEC_PUSH(&v, 30);
        printf("top=%d\n", STATIC_VEC_TOP(&v));   // 30
        printf("len=%lu\n", STATIC_VEC_LEN(&v));  // 3

        int out = 0;
        STATIC_VEC_POP(&v, &out);
        printf("popped=%d len=%lu\n", out, STATIC_VEC_LEN(&v));  // popped=30 len=2
        printf("at1=%d\n", STATIC_VEC_AT(&v, 1));                 // 20
    }

    IntMap m;
    STATIC_MAP_INIT(&m, 8);
    int val1 = 100;
    unsafe {
        STATIC_MAP_INSERT(&m, 5UL, &val1);
        int* found = (int*)STATIC_MAP_GET(&m, 5UL);
        printf("map[5]=%d\n", *found);   // 100
    }
    return 0;
}
```

---

## bst -- Binary Search Tree

```c
#include "collections/bst.h"
```

An unbalanced binary search tree using a user-supplied comparator function. Nodes are heap-owned. Provides in-order traversal (sorted ascending). Free-function API. `t` is a region-less `&BST` reference (call sites unchanged, `&t`).

### Structs

```c
struct BSTNode {
    void*          key;
    void*          val;
    ?&heap BSTNode left;   // empty (null) for a leaf's missing child
    ?&heap BSTNode right;
};

struct BST {
    ?&heap BSTNode root;   // empty (null) for an empty tree; heap-owned by this BST
    unsigned long  key_size;
    unsigned long  val_size;
    void*          cmp_fn;   // int(*)(const void*, const void*)
    unsigned long  len;
};
```

### API

```c
// Lifecycle
struct BST bst_new(unsigned long key_size, unsigned long val_size, void* cmp_fn);
void       bst_free(&BST t);

// Core operations
int   bst_insert(&BST t, const void* key, const void* val);
void* bst_get(&BST t, const void* key);       // NULL if not found
int   bst_contains(&BST t, const void* key);
int   bst_remove(&BST t, const void* key);
unsigned long bst_len(&BST t);
int   bst_is_empty(&BST t);
void  bst_clear(&BST t);

// Min / Max
void* bst_min_key(&BST t);   // NULL if empty
void* bst_max_key(&BST t);   // NULL if empty

// Traversal (in-order = sorted ascending)
void bst_foreach_inorder(&BST t, void* fn);  // fn: void(*)(const void* key, void* val)
```

### Built-In Comparators

Pass these as the `cmp_fn` argument:

```c
int bst_cmp_int(const void* a, const void* b);    // int keys
int bst_cmp_ll(const void* a, const void* b);     // long long keys
int bst_cmp_str(const void* a, const void* b);    // const char* keys
int bst_cmp_uint(const void* a, const void* b);   // unsigned int keys
```

### Generic Wrappers

```c
generic<T> int bst_insert_t(&BST t, const void* key, T val);
generic<T> T*  bst_get_t(&BST t, const void* key);   // see tip above -- needs a typed target
```

### Example

```c
#include <std/collections/bst.sc>

int main() {
    struct BST tree = std::bst_new(sizeof(int), sizeof(int), (void*)std::bst_cmp_int);

    int k1 = 30; int v1 = 300;
    int k2 = 10; int v2 = 100;
    int k3 = 50; int v3 = 500;

    unsafe {
        std::bst_insert(&tree, (const void*)&k1, (const void*)&v1);
        std::bst_insert(&tree, (const void*)&k2, (const void*)&v2);
        std::bst_insert(&tree, (const void*)&k3, (const void*)&v3);

        int* found = std::bst_get_t(&tree, (const void*)&k2);   // T=int inferred from the 'int*' target
        if (found != (int*)0) {
            printf("%d\n", *found);   // 100
        }

        int* min_k = (int*)std::bst_min_key(&tree);
        int* max_k = (int*)std::bst_max_key(&tree);
        printf("min = %d\n", *min_k);   // 10
        printf("max = %d\n", *max_k);   // 50
    }

    std::bst_free(&tree);
    return 0;
}
```
