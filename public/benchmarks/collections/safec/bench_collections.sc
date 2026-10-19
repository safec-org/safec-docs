// std::collections throughput, 1,000,000 elements (see benchmarks.md).
// Same workloads, same key order and same algorithms as ../c/bench_collections.c.
extern int printf(const char* fmt, ...);
#include <std/mem.h>
#include <std/time.h>
#include <std/collections/vec.h>
#include <std/collections/map.h>
#include <std/collections/list.h>
#include <std/collections/bst.h>
#include <std/collections/queue.h>

#define N 1000000UL

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static void report(const char* what, double ms, long long check) {
    unsafe { printf("%-16s %9.3f ms  %12.0f ops/s  check=%lld\n", what, ms, (double)N / (ms / 1000.0), check); }
}

static int cmp_int(const &int a, const &int b) { return *a < *b ? -1 : (*a > *b ? 1 : 0); }

int main() {
    // Deterministic shuffle (xorshift64) so both versions see identical keys.
    struct Vec<int> keys = std::vec_with_cap(N);
    unsigned long i = 0UL;
    while (i < N) { keys.push((int)i); i = i + 1UL; }
    unsigned long long s = 88172645463325252ULL;
    i = N - 1UL;
    while (i > 0UL) {
        s = s ^ (s << 13); s = s ^ (s >> 7); s = s ^ (s << 17);
        unsigned long j = (unsigned long)(s % (unsigned long long)(i + 1UL));
        int tmp = *keys.at(i); *keys.at(i) = *keys.at(j); *keys.at(j) = tmp;
        i = i - 1UL;
    }

    // Vec: push + sort
    double t0 = now_ms();
    struct Vec<int> v = std::vec_new();
    i = 0UL;
    while (i < N) { v.push(*keys.at(i)); i = i + 1UL; }
    double t1 = now_ms();
    report("vec push", t1 - t0, (long long)v.length());
    std::vec_sort(&v);
    double t2 = now_ms();
    report("vec sort", t2 - t1, (long long)*v.at(N - 1UL));
    v.free();

    // HashMap<int,int>: insert, get
    struct HashMap<int, int> m = std::map_new();
    double t3 = now_ms();
    i = 0UL;
    while (i < N) { int k = *keys.at(i); m.insert(k, k); i = i + 1UL; }
    double t4 = now_ms();
    report("map insert", t4 - t3, (long long)m.length());
    long long sum = 0LL;
    i = 0UL;
    while (i < N) { sum = sum + (long long)m.get(*keys.at(i)).default(0); i = i + 1UL; }
    double t5 = now_ms();
    report("map get", t5 - t4, sum);
    m.free();

    // List<int>: push_back + pop_front
    struct List<int> l = std::list_new();
    double t6 = now_ms();
    i = 0UL;
    while (i < N) { l.push_back(*keys.at(i)); i = i + 1UL; }
    sum = 0LL;
    while (!l.is_empty()) { sum = sum + (long long)l.pop_front().default(0); }
    double t7 = now_ms();
    report("list push+pop", t7 - t6, sum);

    // Queue<int>: enqueue + dequeue
    struct Queue<int> q = std::queue_new();
    double t8 = now_ms();
    i = 0UL;
    while (i < N) { q.enqueue(*keys.at(i)); i = i + 1UL; }
    sum = 0LL;
    while (!q.is_empty()) { sum = sum + (long long)q.dequeue().default(0); }
    double t9 = now_ms();
    report("queue enq+deq", t9 - t8, sum);
    q.free();

    // BST<int,int>: insert (shuffled, so not degenerate), then get
    struct BST<int, int, Less<int>> t = std::bst_new();
    double ta = now_ms();
    i = 0UL;
    while (i < N) { int k = *keys.at(i); t.insert(k, k); i = i + 1UL; }
    double tb = now_ms();
    report("bst insert", tb - ta, (long long)t.length());
    sum = 0LL;
    i = 0UL;
    while (i < N) { sum = sum + (long long)t.get(*keys.at(i)).default(0); i = i + 1UL; }
    double tc = now_ms();
    report("bst get", tc - tb, sum);
    t.free();

    keys.free();
    return 0;
}
