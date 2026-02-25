# Collections

SafeC provides eight collection modules in `std/collections/`. Because the compiler does not support generic structs, all collections use `void*` internally with `generic<T>` wrapper functions for type-safe access. `T` is inferred from `T*` arguments at call sites via monomorphization.

## slice -- Bounds-Checked Array Access

```c
#include "collections/slice.h"
```

A `Slice` wraps a typed pointer + length, providing bounds-checked access. The struct stores `void*` and `elem_size`; generic functions provide type-safe construction and access.

### Struct

```c
struct Slice {
    void*         ptr;        // pointer to first element
    unsigned long len;        // number of elements
    unsigned long elem_size;  // stride in bytes
};
```

### Type-Erased API

```c
struct Slice  slice_void_from(void* ptr, unsigned long len, unsigned long elem_size);
struct Slice  slice_void_alloc(unsigned long len, unsigned long elem_size);
void          slice_free(struct Slice* s);
int           slice_in_bounds(struct Slice s, unsigned long idx);
void*         slice_get_raw(struct Slice s, unsigned long idx);
int           slice_set_raw(struct Slice s, unsigned long idx, const void* val);
struct Slice  slice_sub(struct Slice s, unsigned long start, unsigned long end);
unsigned long slice_len(struct Slice s);
int           slice_is_empty(struct Slice s);
```

### Generic Array Functions

These generic functions operate on raw `T*` arrays with explicit length parameters:

```c
generic<T> T*   slice_at(T* ptr, unsigned long len, unsigned long idx);
generic<T> void arr_set(T* ptr, unsigned long len, unsigned long idx, T val);
generic<T> T    arr_get(T* ptr, unsigned long idx);
generic<T> void arr_fill(T* ptr, unsigned long len, T val);
generic<T> void arr_copy(T* dst, T* src, unsigned long len);
generic<T> T    arr_min(T* ptr, unsigned long len);
generic<T> T    arr_max(T* ptr, unsigned long len);
generic<T> void arr_reverse(T* ptr, unsigned long len);
```

### Example

```c
#include "collections/slice.h"
#include "io.h"

int main() {
    int data[5] = {10, 20, 30, 40, 50};

    // Bounds-checked access
    int* p = slice_at(data, 5, 2);  // &data[2]
    println_int(*p);  // 30

    // Fill and reverse
    arr_fill(data, 5, 0);
    arr_reverse(data, 5);

    return 0;
}
```

---

## vec -- Dynamic Array

```c
#include "collections/vec.h"
```

A type-erased dynamic array with automatic growth. O(1) amortized push/pop.

### Struct

```c
struct Vec {
    void*         data;       // heap-allocated element buffer
    unsigned long len;        // current element count
    unsigned long cap;        // allocated element capacity
    unsigned long elem_size;  // size of each element in bytes
};
```

### Lifecycle

```c
struct Vec vec_new(unsigned long elem_size);
struct Vec vec_with_cap(unsigned long elem_size, unsigned long cap);
void       vec_free(struct Vec* v);
```

### Capacity

```c
int           vec_reserve(struct Vec* v, unsigned long new_cap);
void          vec_shrink(struct Vec* v);
unsigned long vec_len(struct Vec* v);
unsigned long vec_cap(struct Vec* v);
int           vec_is_empty(struct Vec* v);
```

### Element Access (Type-Erased)

```c
void* vec_get_raw(struct Vec* v, unsigned long idx);   // NULL if OOB
int   vec_set_raw(struct Vec* v, unsigned long idx, const void* elem);
void* vec_front_raw(struct Vec* v);                    // first element
void* vec_back_raw(struct Vec* v);                     // last element
```

### Mutation

```c
int  vec_push(struct Vec* v, const void* elem);
int  vec_pop(struct Vec* v, void* out);
int  vec_insert(struct Vec* v, unsigned long idx, const void* elem);
int  vec_remove(struct Vec* v, unsigned long idx, void* out);
void vec_clear(struct Vec* v);
int  vec_extend(struct Vec* v, const void* arr, unsigned long count);
```

### Algorithms

```c
void      vec_reverse(struct Vec* v);
void      vec_sort(struct Vec* v, void* cmp);
long long vec_find(struct Vec* v, const void* key, void* cmp);
int       vec_contains(struct Vec* v, const void* key, void* cmp);
struct Vec vec_clone(struct Vec* v);
void      vec_foreach(struct Vec* v, void* fn);
struct Vec vec_filter(struct Vec* v, void* pred);
struct Vec vec_map_raw(struct Vec* v, unsigned long out_elem_size, void* fn);
```

- `cmp`: `int(*)(const void*, const void*)` -- comparator function
- `fn` (foreach): `void(*)(void* elem, unsigned long idx)`
- `pred` (filter): `int(*)(const void*)` -- return non-zero to keep
- `fn` (map): `void(*)(const void* in, void* out)`

### Generic Wrappers

```c
generic<T> int       vec_push_t(struct Vec* v, T val);
generic<T> T*        vec_at(struct Vec* v, unsigned long idx);
generic<T> int       vec_pop_t(struct Vec* v, T* out);
generic<T> struct Vec vec_from_arr(T* arr, unsigned long len);
```

### Example

```c
#include "collections/vec.h"
#include "io.h"

int main() {
    struct Vec v = vec_new(sizeof(int));

    // Push elements using generic wrapper
    vec_push_t(&v, 10);
    vec_push_t(&v, 20);
    vec_push_t(&v, 30);

    // Access by index
    int* p = vec_at(&v, 1);
    println_int(*p);  // 20

    // Pop last element
    int last;
    vec_pop_t(&v, &last);
    println_int(last);  // 30

    print("len = ");
    println_int(vec_len(&v));  // 2

    vec_free(&v);
    return 0;
}
```

---

## string -- Mutable String

```c
#include "collections/string.h"
```

A growable, heap-allocated, NUL-terminated byte string with 30+ methods.

### Struct

```c
struct String {
    char*         data;  // NUL-terminated buffer
    unsigned long len;   // byte length (not including NUL)
    unsigned long cap;   // allocated buffer size (including NUL slot)
};
```

### Lifecycle

```c
struct String string_new();
struct String string_from(const char* s);
struct String string_from_n(const char* s, unsigned long n);
struct String string_with_cap(unsigned long cap);
struct String string_repeat(const char* s, unsigned long n);
struct String string_clone(const struct String* s);
void          string_free(struct String* s);
```

### Access

```c
unsigned long string_len(const struct String* s);
int           string_is_empty(const struct String* s);
const char*   string_as_ptr(const struct String* s);    // NUL-terminated C string
int           string_char_at(const struct String* s, unsigned long idx);  // -1 if OOB
void          string_set_char(struct String* s, unsigned long idx, char c);
```

### Append

```c
int string_push_char(struct String* s, char c);
int string_push(struct String* s, const char* cstr);
int string_push_str(struct String* s, const struct String* other);
int string_push_int(struct String* s, long long v);
int string_push_uint(struct String* s, unsigned long long v);
int string_push_float(struct String* s, double v, int decimals);
int string_push_bool(struct String* s, int v);
```

### Modification

```c
void string_clear(struct String* s);
void string_truncate(struct String* s, unsigned long new_len);
int  string_insert(struct String* s, unsigned long idx, const char* cstr);
int  string_remove_range(struct String* s, unsigned long start, unsigned long end);
void string_replace_char(struct String* s, char from, char to);
int  string_replace(struct String* s, const char* from, const char* to);
int  string_replace_all(struct String* s, const char* from, const char* to);
```

### Search

```c
long long string_index_of(const struct String* s, const char* needle);
long long string_last_index_of(const struct String* s, const char* needle);
int       string_contains(const struct String* s, const char* needle);
int       string_starts_with(const struct String* s, const char* prefix);
int       string_ends_with(const struct String* s, const char* suffix);
int       string_count(const struct String* s, const char* needle);
```

### Transformation (Return New String)

```c
struct String string_substr(const struct String* s, unsigned long start, unsigned long end);
struct String string_to_upper(const struct String* s);
struct String string_to_lower(const struct String* s);
struct String string_trim(const struct String* s);
struct String string_trim_left(const struct String* s);
struct String string_trim_right(const struct String* s);
struct String string_join(const char* sep, const struct String* parts, unsigned long count);
```

### Comparison

```c
int string_eq(const struct String* a, const struct String* b);
int string_eq_cstr(const struct String* s, const char* other);
int string_cmp(const struct String* a, const struct String* b);  // <0, 0, >0
int string_lt(const struct String* a, const struct String* b);
int string_gt(const struct String* a, const struct String* b);
```

### Conversion

```c
long long string_parse_int(const struct String* s, int* ok);
double    string_parse_float(const struct String* s, int* ok);
```

### Example

```c
#include "collections/string.h"
#include "io.h"

int main() {
    struct String s = string_from("Hello");
    string_push(&s, ", SafeC!");
    println(string_as_ptr(&s));  // Hello, SafeC!

    // Search
    if (string_contains(&s, "SafeC")) {
        println("Found SafeC");
    }

    // Transform
    struct String upper = string_to_upper(&s);
    println(string_as_ptr(&upper));  // HELLO, SAFEC!

    // Build a number string
    struct String num = string_new();
    string_push(&num, "value = ");
    string_push_int(&num, 42);
    println(string_as_ptr(&num));  // value = 42

    string_free(&num);
    string_free(&upper);
    string_free(&s);
    return 0;
}
```

---

## stack -- LIFO Stack

```c
#include "collections/stack.h"
```

A last-in-first-out stack backed by a growable array. O(1) amortized push/pop.

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
void         stack_free(struct Stack* s);

// Core operations
int           stack_push(struct Stack* s, const void* elem);   // 1 on success
int           stack_pop(struct Stack* s, void* out);            // 0 if empty
void*         stack_peek(struct Stack* s);                      // NULL if empty
unsigned long stack_len(struct Stack* s);
int           stack_is_empty(struct Stack* s);
void          stack_clear(struct Stack* s);
```

### Generic Wrappers

```c
generic<T> int stack_push_t(struct Stack* s, T val);
generic<T> T*  stack_peek_t(struct Stack* s);
generic<T> int stack_pop_t(struct Stack* s, T* out);
```

### Example

```c
#include "collections/stack.h"
#include "io.h"

int main() {
    struct Stack s = stack_new(sizeof(int));

    stack_push_t(&s, 10);
    stack_push_t(&s, 20);
    stack_push_t(&s, 30);

    int* top = stack_peek_t(&s);
    println_int(*top);  // 30

    int val;
    stack_pop_t(&s, &val);
    println_int(val);  // 30

    stack_free(&s);
    return 0;
}
```

---

## queue -- FIFO Queue

```c
#include "collections/queue.h"
```

A first-in-first-out circular buffer queue. Amortized O(1) enqueue/dequeue. Grows automatically when full.

### Struct

```c
struct Queue {
    void*         data;
    unsigned long head;       // index of front element
    unsigned long tail;       // index where next element will be written
    unsigned long len;        // current element count
    unsigned long cap;        // total allocated slots
    unsigned long elem_size;
};
```

### API

```c
// Lifecycle
struct Queue queue_new(unsigned long elem_size);
struct Queue queue_with_cap(unsigned long elem_size, unsigned long cap);
void         queue_free(struct Queue* q);

// Core operations
int           queue_enqueue(struct Queue* q, const void* elem);
int           queue_dequeue(struct Queue* q, void* out);
void*         queue_front(struct Queue* q);    // peek front; NULL if empty
void*         queue_back(struct Queue* q);     // peek back; NULL if empty
unsigned long queue_len(struct Queue* q);
int           queue_is_empty(struct Queue* q);
void          queue_clear(struct Queue* q);
```

### Generic Wrappers

```c
generic<T> int queue_enqueue_t(struct Queue* q, T val);
generic<T> T*  queue_front_t(struct Queue* q);
generic<T> int queue_dequeue_t(struct Queue* q, T* out);
```

### Example

```c
#include "collections/queue.h"
#include "io.h"

int main() {
    struct Queue q = queue_new(sizeof(int));

    queue_enqueue_t(&q, 1);
    queue_enqueue_t(&q, 2);
    queue_enqueue_t(&q, 3);

    int val;
    queue_dequeue_t(&q, &val);
    println_int(val);  // 1 (FIFO order)

    int* front = queue_front_t(&q);
    println_int(*front);  // 2

    queue_free(&q);
    return 0;
}
```

---

## list -- Doubly Linked List

```c
#include "collections/list.h"
```

A doubly linked list with push/pop on both ends, search, removal, and in-order traversal.

### Structs

```c
struct ListNode {
    void*            data;
    struct ListNode* next;
    struct ListNode* prev;
};

struct List {
    struct ListNode* head;
    struct ListNode* tail;
    unsigned long    len;
    unsigned long    elem_size;
};
```

### API

```c
// Lifecycle
struct List list_new(unsigned long elem_size);
void        list_free(struct List* l);

// Push / Pop
int           list_push_front(struct List* l, const void* elem);
int           list_push_back(struct List* l, const void* elem);
int           list_pop_front(struct List* l, void* out);
int           list_pop_back(struct List* l, void* out);
void*         list_front(struct List* l);      // NULL if empty
void*         list_back(struct List* l);       // NULL if empty
unsigned long list_len(struct List* l);
int           list_is_empty(struct List* l);
void          list_clear(struct List* l);

// Search & Iteration
struct ListNode* list_find(struct List* l, const void* val, void* cmp);
int              list_contains(struct List* l, const void* val, void* cmp);
void             list_remove_node(struct List* l, struct ListNode* node);
int              list_remove(struct List* l, const void* val, void* cmp);
void             list_foreach(struct List* l, void* fn);  // fn: void(*)(void* data)

// Reorder
void list_reverse(struct List* l);
```

- `cmp`: `int(*)(const void*, const void*)` -- comparator

### Generic Wrappers

```c
generic<T> int list_push_back_t(struct List* l, T val);
generic<T> T*  list_front_t(struct List* l);
generic<T> T*  list_back_t(struct List* l);
```

### Example

```c
#include "collections/list.h"
#include "io.h"

int main() {
    struct List l = list_new(sizeof(int));

    list_push_back_t(&l, 10);
    list_push_back_t(&l, 20);
    list_push_front_t(&l, 5);

    int* front = list_front_t(&l);
    println_int(*front);  // 5

    int* back = list_back_t(&l);
    println_int(*back);   // 20

    list_reverse(&l);

    front = list_front_t(&l);
    println_int(*front);  // 20

    list_free(&l);
    return 0;
}
```

---

## map -- Hash Map

```c
#include "collections/map.h"
```

An open-addressing hash map with linear probing and a djb2 hash function. Load factor threshold is 0.75; resizes automatically. Keys are compared byte-by-byte (`memcmp`). Use the `str_map_*` variants for C-string keys.

### Structs

```c
struct MapEntry {
    void*        key;
    void*        val;
    unsigned int hash;
    int          state;  // 0=empty, 1=occupied, 2=tombstone
};

struct HashMap {
    struct MapEntry* buckets;
    unsigned long    cap;        // must be power of 2
    unsigned long    len;        // live entries
    unsigned long    key_size;
    unsigned long    val_size;
};
```

### Byte-Key API

```c
// Lifecycle
struct HashMap map_new(unsigned long key_size, unsigned long val_size);
struct HashMap map_with_cap(unsigned long key_size, unsigned long val_size, unsigned long cap);
void           map_free(struct HashMap* m);

// Core operations
int   map_insert(struct HashMap* m, const void* key, const void* val);
void* map_get(struct HashMap* m, const void* key);       // NULL if missing
int   map_contains(struct HashMap* m, const void* key);
int   map_remove(struct HashMap* m, const void* key);
unsigned long map_len(struct HashMap* m);
int   map_is_empty(struct HashMap* m);
void  map_clear(struct HashMap* m);
void  map_foreach(struct HashMap* m, void* fn);  // fn: void(*)(const void* key, void* val)
```

### String-Key Convenience

For maps keyed by `const char*` strings:

```c
struct HashMap str_map_new(unsigned long val_size);
int   str_map_insert(struct HashMap* m, const char* key, const void* val);
void* str_map_get(struct HashMap* m, const char* key);
int   str_map_contains(struct HashMap* m, const char* key);
int   str_map_remove(struct HashMap* m, const char* key);
```

### Generic Wrappers

```c
generic<T> int map_insert_t(struct HashMap* m, const void* key, T val);
generic<T> T*  map_get_t(struct HashMap* m, const void* key);
```

### Example

```c
#include "collections/map.h"
#include "io.h"

int main() {
    // Integer-keyed map
    struct HashMap m = map_new(sizeof(int), sizeof(int));
    int key = 42;
    int val = 100;
    map_insert(&m, &key, &val);

    int* found = map_get(&m, &key);
    if (found != 0) {
        println_int(*found);  // 100
    }
    map_free(&m);

    // String-keyed map
    struct HashMap sm = str_map_new(sizeof(double));
    double pi = 3.14159;
    str_map_insert(&sm, "pi", &pi);

    double* p = str_map_get(&sm, "pi");
    if (p != 0) {
        println_float(*p);  // 3.14159
    }
    map_free(&sm);

    return 0;
}
```

---

## bst -- Binary Search Tree

```c
#include "collections/bst.h"
```

An unbalanced binary search tree using a user-supplied comparator function. Keys and values are heap-copied. Provides in-order traversal (sorted ascending).

### Structs

```c
struct BSTNode {
    void*            key;
    void*            val;
    struct BSTNode*  left;
    struct BSTNode*  right;
};

struct BST {
    struct BSTNode* root;
    unsigned long   key_size;
    unsigned long   val_size;
    void*           cmp_fn;    // int(*)(const void*, const void*)
    unsigned long   len;
};
```

### API

```c
// Lifecycle
struct BST bst_new(unsigned long key_size, unsigned long val_size, void* cmp_fn);
void       bst_free(struct BST* t);

// Core operations
int   bst_insert(struct BST* t, const void* key, const void* val);
void* bst_get(struct BST* t, const void* key);       // NULL if not found
int   bst_contains(struct BST* t, const void* key);
int   bst_remove(struct BST* t, const void* key);
unsigned long bst_len(struct BST* t);
int   bst_is_empty(struct BST* t);
void  bst_clear(struct BST* t);

// Min / Max
void* bst_min_key(struct BST* t);   // NULL if empty
void* bst_max_key(struct BST* t);   // NULL if empty

// Traversal (in-order = sorted ascending)
void bst_foreach_inorder(struct BST* t, void* fn);  // fn: void(*)(const void* key, void* val)
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
generic<T> int bst_insert_t(struct BST* t, const void* key, T val);
generic<T> T*  bst_get_t(struct BST* t, const void* key);
```

### Example

```c
#include "collections/bst.h"
#include "io.h"

int main() {
    // Integer-keyed BST
    struct BST tree = bst_new(sizeof(int), sizeof(int), bst_cmp_int);

    int k1 = 30; int v1 = 300;
    int k2 = 10; int v2 = 100;
    int k3 = 50; int v3 = 500;

    bst_insert(&tree, &k1, &v1);
    bst_insert(&tree, &k2, &v2);
    bst_insert(&tree, &k3, &v3);

    int* found = bst_get(&tree, &k2);
    if (found != 0) {
        println_int(*found);  // 100
    }

    // Min and max keys
    int* min_k = bst_min_key(&tree);
    int* max_k = bst_max_key(&tree);
    print("min = ");
    println_int(*min_k);  // 10
    print("max = ");
    println_int(*max_k);  // 50

    bst_free(&tree);
    return 0;
}
```
