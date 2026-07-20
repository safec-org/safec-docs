extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/mem.h>
#include <std/mem.sc>
#include <std/str.sc>
#include <std/collections/bst.h>
#include <std/collections/bst.sc>
#include <std/collections/list.h>
#include <std/collections/list.sc>
#include <std/collections/map.h>
#include <std/collections/map.sc>
#include <std/time.sc>

#define N 1000000UL

double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

int main() {
  unsafe {
    srand48(7L);
    int* keys = (int*)alloc(sizeof(int) * N);
    unsigned long i = 0UL;
    while (i < N) { keys[i] = (int)i; i = i + 1UL; }
    // Shuffle so the BST isn't degenerate (sorted-order insert into an
    // unbalanced BST is the documented worst case).
    i = N - 1UL;
    while (i > 0UL) {
        unsigned long j = (unsigned long)(drand48() * (double)(i + 1UL));
        int tmp = keys[i]; keys[i] = keys[j]; keys[j] = tmp;
        i = i - 1UL;
    }

    // ── BST ──────────────────────────────────────────────────────────────
    struct BST t = std::bst_new((unsigned long)sizeof(int), (unsigned long)sizeof(int), (void*)std::bst_cmp_int);
    double t0 = now_ms();
    i = 0UL;
    while (i < N) {
        std::bst_insert(&t, (const void*)&keys[i], (const void*)&keys[i]);
        i = i + 1UL;
    }
    double t1 = now_ms();
    printf("bst    insert %lu: %.3fms (%.0f inserts/sec), len=%lu\n",
           N, t1 - t0, (double)N / ((t1 - t0) / 1000.0), std::bst_len(&t));
    std::bst_free(&t);

    // ── List (push_back) ────────────────────────────────────────────────
    struct List l = std::list_new((unsigned long)sizeof(int));
    double t2 = now_ms();
    i = 0UL;
    while (i < N) {
        std::list_push_back(&l, (const void*)&keys[i]);
        i = i + 1UL;
    }
    double t3 = now_ms();
    printf("list   push_back %lu: %.3fms (%.0f pushes/sec), len=%lu\n",
           N, t3 - t2, (double)N / ((t3 - t2) / 1000.0), std::list_len(&l));
    std::list_free(&l);

    // ── HashMap ──────────────────────────────────────────────────────────
    struct HashMap m = std::map_with_cap((unsigned long)sizeof(int), (unsigned long)sizeof(int), N);
    double t4 = now_ms();
    i = 0UL;
    while (i < N) {
        m.insert((const void*)&keys[i], (const void*)&keys[i]);
        i = i + 1UL;
    }
    double t5 = now_ms();
    printf("map    insert %lu: %.3fms (%.0f inserts/sec), len=%lu\n",
           N, t5 - t4, (double)N / ((t5 - t4) / 1000.0), m.length());

    // Get, same key order (already inserted, so this is a pure lookup pass).
    double t6 = now_ms();
    long long checksum = 0LL;
    i = 0UL;
    while (i < N) {
        int* v = (int*)m.get((const void*)&keys[i]);
        checksum = checksum + (long long)(*v);
        i = i + 1UL;
    }
    double t7 = now_ms();
    printf("map    get %lu: %.3fms (%.0f gets/sec), checksum=%lld\n",
           N, t7 - t6, (double)N / ((t7 - t6) / 1000.0), checksum);
    m.free();

    dealloc((void*)keys);
    return 0;
  }
}
