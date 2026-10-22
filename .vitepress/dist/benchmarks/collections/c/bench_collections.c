// C baseline for ../safec/bench_collections.sc: same workloads, key order,
// and algorithms (growable array + qsort, linear-probing hash map with a
// murmur3-fmix64 hash, doubly linked list, power-of-two ring
// queue, unbalanced iterative BST).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define N 1000000UL

static double now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}
static void report(const char *what, double ms, long long check) {
    printf("%-16s %9.3f ms  %12.0f ops/s  check=%lld\n", what, ms, (double)N / (ms / 1000.0), check);
}
static int cmp_int(const void *a, const void *b) {
    int x = *(const int *)a, y = *(const int *)b; return x < y ? -1 : (x > y ? 1 : 0);
}

typedef struct { int *data; size_t len, cap; } Vec;
static void vec_push(Vec *v, int x) {
    if (v->len == v->cap) { v->cap = v->cap < 4 ? 4 : v->cap * 2; v->data = realloc(v->data, v->cap * sizeof(int)); }
    v->data[v->len++] = x;
}

static unsigned hash_int(int k) {   /* murmur3 fmix64, as std::HashMap uses for word-sized keys */
    unsigned long long x = (unsigned)k;
    x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33; x *= 0xc4ceb9fe1a85ec53ULL; x ^= x >> 33;
    return (unsigned)x;
}
typedef struct { int *keys, *vals; unsigned *hashes; unsigned char *st; size_t cap, len; } Map;
static void map_rehash(Map *m, size_t nc) {
    Map n = { malloc(nc * 4), malloc(nc * 4), malloc(nc * 4), calloc(nc, 1), nc, m->len };
    for (size_t i = 0; i < m->cap; i++) if (m->st[i] == 1) {
        size_t j = m->hashes[i] & (nc - 1);
        while (n.st[j]) j = (j + 1) & (nc - 1);
        n.keys[j] = m->keys[i]; n.vals[j] = m->vals[i]; n.hashes[j] = m->hashes[i]; n.st[j] = 1;
    }
    free(m->keys); free(m->vals); free(m->hashes); free(m->st); *m = n;
}
static void map_insert(Map *m, int k, int v) {
    if ((m->len + 1) * 4 > m->cap * 3) map_rehash(m, m->cap ? m->cap * 2 : 16);
    unsigned h = hash_int(k); size_t j = h & (m->cap - 1);
    while (m->st[j]) { if (m->hashes[j] == h && m->keys[j] == k) { m->vals[j] = v; return; } j = (j + 1) & (m->cap - 1); }
    m->keys[j] = k; m->vals[j] = v; m->hashes[j] = h; m->st[j] = 1; m->len++;
}
static int *map_get(Map *m, int k) {
    unsigned h = hash_int(k); size_t j = h & (m->cap - 1);
    while (m->st[j]) { if (m->st[j] == 1 && m->hashes[j] == h && m->keys[j] == k) return &m->vals[j]; j = (j + 1) & (m->cap - 1); }
    return NULL;
}

typedef struct Node { int v; struct Node *next, *prev; } Node;
typedef struct BNode { int k, v; struct BNode *l, *r; } BNode;

int main(void) {
    int *keys = malloc(N * sizeof(int));
    for (size_t i = 0; i < N; i++) keys[i] = (int)i;
    unsigned long long s = 88172645463325252ULL;
    for (size_t i = N - 1; i > 0; i--) {
        s ^= s << 13; s ^= s >> 7; s ^= s << 17;
        size_t j = (size_t)(s % (i + 1)); int t = keys[i]; keys[i] = keys[j]; keys[j] = t;
    }
    long long sum;

    double t0 = now_ms(); Vec v = {0};
    for (size_t i = 0; i < N; i++) vec_push(&v, keys[i]);
    double t1 = now_ms(); report("vec push", t1 - t0, (long long)v.len);
    qsort(v.data, v.len, sizeof(int), cmp_int);
    double t2 = now_ms(); report("vec sort", t2 - t1, v.data[N - 1]); free(v.data);

    Map m = {0}; double t3 = now_ms();
    for (size_t i = 0; i < N; i++) map_insert(&m, keys[i], keys[i]);
    double t4 = now_ms(); report("map insert", t4 - t3, (long long)m.len);
    sum = 0; for (size_t i = 0; i < N; i++) { int *p = map_get(&m, keys[i]); sum += p ? *p : 0; }
    double t5 = now_ms(); report("map get", t5 - t4, sum);

    Node *head = NULL, *tail = NULL; double t6 = now_ms();
    for (size_t i = 0; i < N; i++) {
        Node *n = malloc(sizeof *n); n->v = keys[i]; n->next = NULL; n->prev = tail;
        if (tail) tail->next = n; else head = n; tail = n;
    }
    sum = 0; while (head) { Node *n = head; head = n->next; if (head) head->prev = NULL; sum += n->v; free(n); }
    double t7 = now_ms(); report("list push+pop", t7 - t6, sum);

    size_t qc = 0, qh = 0, ql = 0; int *qd = NULL; double t8 = now_ms();
    for (size_t i = 0; i < N; i++) {
        if (ql == qc) {
            size_t nc = qc ? qc * 2 : 8; int *nd = malloc(nc * sizeof(int));
            for (size_t k = 0; k < ql; k++) nd[k] = qd[(qh + k) & (qc - 1)];
            free(qd); qd = nd; qh = 0; qc = nc;
        }
        qd[(qh + ql) & (qc - 1)] = keys[i]; ql++;
    }
    sum = 0; while (ql) { sum += qd[qh]; qh = (qh + 1) & (qc - 1); ql--; }
    double t9 = now_ms(); report("queue enq+deq", t9 - t8, sum); free(qd);

    BNode *root = NULL; size_t blen = 0; double ta = now_ms();
    for (size_t i = 0; i < N; i++) {
        int k = keys[i]; BNode **link = &root;
        while (*link) { int c = cmp_int(&k, &(*link)->k); if (!c) break; link = c < 0 ? &(*link)->l : &(*link)->r; }
        if (*link) { (*link)->v = k; continue; }
        BNode *n = malloc(sizeof *n); n->k = k; n->v = k; n->l = n->r = NULL; *link = n; blen++;
    }
    double tb = now_ms(); report("bst insert", tb - ta, (long long)blen);
    sum = 0;
    for (size_t i = 0; i < N; i++) {
        BNode *n = root; int k = keys[i];
        while (n) { int c = cmp_int(&k, &n->k); if (!c) { sum += n->v; break; } n = c < 0 ? n->l : n->r; }
    }
    double tc = now_ms(); report("bst get", tc - tb, sum);
    free(keys);
    return 0;
}
