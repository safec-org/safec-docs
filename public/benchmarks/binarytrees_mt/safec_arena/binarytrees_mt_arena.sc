extern int printf(const char* fmt, ...);
extern int sscanf(const char* s, const char* fmt, ...);
#include <std/mem.sc>
#include <std/thread.sc>

#define NTHREADS 8

// Each worker thread gets its own named region -- arena state (buf/used/
// cap) is a single global per region name with no locking, so sharing one
// region across threads would be a real data race. Per-thread capacity is
// smaller than the single-threaded version's Scratch pool (10MB vs 20MB):
// worker threads only ever build trees up to depth 18 (2^19-1 nodes, 8MB)
// -- the depth-19 stretch tree is built by the main thread alone, before
// any workers spawn, so it gets its own separate, larger-capacity region.
region StretchPool { capacity: 20000000 }
region LongLived { capacity: 10000000 }
region Scratch0 { capacity: 10000000 }
region Scratch1 { capacity: 10000000 }
region Scratch2 { capacity: 10000000 }
region Scratch3 { capacity: 10000000 }
region Scratch4 { capacity: 10000000 }
region Scratch5 { capacity: 10000000 }
region Scratch6 { capacity: 10000000 }
region Scratch7 { capacity: 10000000 }

struct StretchNode {
    ?&arena<StretchPool> StretchNode left;
    ?&arena<StretchPool> StretchNode right;
};

&arena<StretchPool> StretchNode make_stretch(int depth) {
    &arena<StretchPool> StretchNode n = new<StretchPool> StretchNode;
    if (depth > 0) {
        n->left = make_stretch(depth - 1);
        n->right = make_stretch(depth - 1);
    } else {
        unsafe { n->left = (?&arena<StretchPool> StretchNode)(struct StretchNode*)0; n->right = (?&arena<StretchPool> StretchNode)(struct StretchNode*)0; }
    }
    return n;
}

int stretch_checksum(&arena<StretchPool> StretchNode n) {
    unsafe {
        struct StretchNode* raw = (struct StretchNode*)n;
        if (raw->left == (struct StretchNode*)0) { return 1; }
        return 1 + stretch_checksum((&arena<StretchPool> StretchNode)raw->left) + stretch_checksum((&arena<StretchPool> StretchNode)raw->right);
    }
}

struct LNode {
    ?&arena<LongLived> LNode left;
    ?&arena<LongLived> LNode right;
};

&arena<LongLived> LNode make_ltree(int depth) {
    &arena<LongLived> LNode n = new<LongLived> LNode;
    if (depth > 0) {
        n->left = make_ltree(depth - 1);
        n->right = make_ltree(depth - 1);
    } else {
        unsafe { n->left = (?&arena<LongLived> LNode)(struct LNode*)0; n->right = (?&arena<LongLived> LNode)(struct LNode*)0; }
    }
    return n;
}

int lchecksum(&arena<LongLived> LNode n) {
    unsafe {
        struct LNode* raw = (struct LNode*)n;
        if (raw->left == (struct LNode*)0) { return 1; }
        return 1 + lchecksum((&arena<LongLived> LNode)raw->left) + lchecksum((&arena<LongLived> LNode)raw->right);
    }
}

struct SNode0 {
    ?&arena<Scratch0> SNode0 left;
    ?&arena<Scratch0> SNode0 right;
};

&arena<Scratch0> SNode0 make_stree0(int depth) {
    &arena<Scratch0> SNode0 n = new<Scratch0> SNode0;
    if (depth > 0) {
        n->left = make_stree0(depth - 1);
        n->right = make_stree0(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch0> SNode0)(struct SNode0*)0; n->right = (?&arena<Scratch0> SNode0)(struct SNode0*)0; }
    }
    return n;
}

int schecksum0(&arena<Scratch0> SNode0 n) {
    unsafe {
        struct SNode0* raw = (struct SNode0*)n;
        if (raw->left == (struct SNode0*)0) { return 1; }
        return 1 + schecksum0((&arena<Scratch0> SNode0)raw->left) + schecksum0((&arena<Scratch0> SNode0)raw->right);
    }
}

void worker0(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch0> SNode0 t = make_stree0(depth);
            check = check + schecksum0(t);
            arena_reset<Scratch0>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode1 {
    ?&arena<Scratch1> SNode1 left;
    ?&arena<Scratch1> SNode1 right;
};

&arena<Scratch1> SNode1 make_stree1(int depth) {
    &arena<Scratch1> SNode1 n = new<Scratch1> SNode1;
    if (depth > 0) {
        n->left = make_stree1(depth - 1);
        n->right = make_stree1(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch1> SNode1)(struct SNode1*)0; n->right = (?&arena<Scratch1> SNode1)(struct SNode1*)0; }
    }
    return n;
}

int schecksum1(&arena<Scratch1> SNode1 n) {
    unsafe {
        struct SNode1* raw = (struct SNode1*)n;
        if (raw->left == (struct SNode1*)0) { return 1; }
        return 1 + schecksum1((&arena<Scratch1> SNode1)raw->left) + schecksum1((&arena<Scratch1> SNode1)raw->right);
    }
}

void worker1(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch1> SNode1 t = make_stree1(depth);
            check = check + schecksum1(t);
            arena_reset<Scratch1>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode2 {
    ?&arena<Scratch2> SNode2 left;
    ?&arena<Scratch2> SNode2 right;
};

&arena<Scratch2> SNode2 make_stree2(int depth) {
    &arena<Scratch2> SNode2 n = new<Scratch2> SNode2;
    if (depth > 0) {
        n->left = make_stree2(depth - 1);
        n->right = make_stree2(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch2> SNode2)(struct SNode2*)0; n->right = (?&arena<Scratch2> SNode2)(struct SNode2*)0; }
    }
    return n;
}

int schecksum2(&arena<Scratch2> SNode2 n) {
    unsafe {
        struct SNode2* raw = (struct SNode2*)n;
        if (raw->left == (struct SNode2*)0) { return 1; }
        return 1 + schecksum2((&arena<Scratch2> SNode2)raw->left) + schecksum2((&arena<Scratch2> SNode2)raw->right);
    }
}

void worker2(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch2> SNode2 t = make_stree2(depth);
            check = check + schecksum2(t);
            arena_reset<Scratch2>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode3 {
    ?&arena<Scratch3> SNode3 left;
    ?&arena<Scratch3> SNode3 right;
};

&arena<Scratch3> SNode3 make_stree3(int depth) {
    &arena<Scratch3> SNode3 n = new<Scratch3> SNode3;
    if (depth > 0) {
        n->left = make_stree3(depth - 1);
        n->right = make_stree3(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch3> SNode3)(struct SNode3*)0; n->right = (?&arena<Scratch3> SNode3)(struct SNode3*)0; }
    }
    return n;
}

int schecksum3(&arena<Scratch3> SNode3 n) {
    unsafe {
        struct SNode3* raw = (struct SNode3*)n;
        if (raw->left == (struct SNode3*)0) { return 1; }
        return 1 + schecksum3((&arena<Scratch3> SNode3)raw->left) + schecksum3((&arena<Scratch3> SNode3)raw->right);
    }
}

void worker3(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch3> SNode3 t = make_stree3(depth);
            check = check + schecksum3(t);
            arena_reset<Scratch3>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode4 {
    ?&arena<Scratch4> SNode4 left;
    ?&arena<Scratch4> SNode4 right;
};

&arena<Scratch4> SNode4 make_stree4(int depth) {
    &arena<Scratch4> SNode4 n = new<Scratch4> SNode4;
    if (depth > 0) {
        n->left = make_stree4(depth - 1);
        n->right = make_stree4(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch4> SNode4)(struct SNode4*)0; n->right = (?&arena<Scratch4> SNode4)(struct SNode4*)0; }
    }
    return n;
}

int schecksum4(&arena<Scratch4> SNode4 n) {
    unsafe {
        struct SNode4* raw = (struct SNode4*)n;
        if (raw->left == (struct SNode4*)0) { return 1; }
        return 1 + schecksum4((&arena<Scratch4> SNode4)raw->left) + schecksum4((&arena<Scratch4> SNode4)raw->right);
    }
}

void worker4(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch4> SNode4 t = make_stree4(depth);
            check = check + schecksum4(t);
            arena_reset<Scratch4>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode5 {
    ?&arena<Scratch5> SNode5 left;
    ?&arena<Scratch5> SNode5 right;
};

&arena<Scratch5> SNode5 make_stree5(int depth) {
    &arena<Scratch5> SNode5 n = new<Scratch5> SNode5;
    if (depth > 0) {
        n->left = make_stree5(depth - 1);
        n->right = make_stree5(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch5> SNode5)(struct SNode5*)0; n->right = (?&arena<Scratch5> SNode5)(struct SNode5*)0; }
    }
    return n;
}

int schecksum5(&arena<Scratch5> SNode5 n) {
    unsafe {
        struct SNode5* raw = (struct SNode5*)n;
        if (raw->left == (struct SNode5*)0) { return 1; }
        return 1 + schecksum5((&arena<Scratch5> SNode5)raw->left) + schecksum5((&arena<Scratch5> SNode5)raw->right);
    }
}

void worker5(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch5> SNode5 t = make_stree5(depth);
            check = check + schecksum5(t);
            arena_reset<Scratch5>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode6 {
    ?&arena<Scratch6> SNode6 left;
    ?&arena<Scratch6> SNode6 right;
};

&arena<Scratch6> SNode6 make_stree6(int depth) {
    &arena<Scratch6> SNode6 n = new<Scratch6> SNode6;
    if (depth > 0) {
        n->left = make_stree6(depth - 1);
        n->right = make_stree6(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch6> SNode6)(struct SNode6*)0; n->right = (?&arena<Scratch6> SNode6)(struct SNode6*)0; }
    }
    return n;
}

int schecksum6(&arena<Scratch6> SNode6 n) {
    unsafe {
        struct SNode6* raw = (struct SNode6*)n;
        if (raw->left == (struct SNode6*)0) { return 1; }
        return 1 + schecksum6((&arena<Scratch6> SNode6)raw->left) + schecksum6((&arena<Scratch6> SNode6)raw->right);
    }
}

void worker6(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch6> SNode6 t = make_stree6(depth);
            check = check + schecksum6(t);
            arena_reset<Scratch6>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

struct SNode7 {
    ?&arena<Scratch7> SNode7 left;
    ?&arena<Scratch7> SNode7 right;
};

&arena<Scratch7> SNode7 make_stree7(int depth) {
    &arena<Scratch7> SNode7 n = new<Scratch7> SNode7;
    if (depth > 0) {
        n->left = make_stree7(depth - 1);
        n->right = make_stree7(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch7> SNode7)(struct SNode7*)0; n->right = (?&arena<Scratch7> SNode7)(struct SNode7*)0; }
    }
    return n;
}

int schecksum7(&arena<Scratch7> SNode7 n) {
    unsafe {
        struct SNode7* raw = (struct SNode7*)n;
        if (raw->left == (struct SNode7*)0) { return 1; }
        return 1 + schecksum7((&arena<Scratch7> SNode7)raw->left) + schecksum7((&arena<Scratch7> SNode7)raw->right);
    }
}

void worker7(void* arg) {
    unsafe {
        int* slice = (int*)arg;
        int start = slice[0];
        int end = slice[1];
        int depth = slice[2];
        int check = 0;
        int i = start;
        while (i < end) {
            &arena<Scratch7> SNode7 t = make_stree7(depth);
            check = check + schecksum7(t);
            arena_reset<Scratch7>();
            i = i + 1;
        }
        slice[3] = check;
    }
}

int workSlices[NTHREADS * 4];

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    &arena<StretchPool> StretchNode stretchTree = make_stretch(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, stretch_checksum(stretchTree));

    &arena<LongLived> LNode longLivedTree = make_ltree(maxDepth);

    int depth = minDepth;
    while (depth <= maxDepth) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) { perThread = 1; }

        unsigned long long tids[NTHREADS];
        int nSpawned = 0;
        int start = 0;
        int t = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) { end = iterations; }
            unsafe {
                workSlices[t * 4 + 0] = start;
                workSlices[t * 4 + 1] = end;
                workSlices[t * 4 + 2] = depth;
                if (t == 0) { std::thread_create(&tids[t], (void*)worker0, (void*)&workSlices[t * 4]); }
                else if (t == 1) { std::thread_create(&tids[t], (void*)worker1, (void*)&workSlices[t * 4]); }
                else if (t == 2) { std::thread_create(&tids[t], (void*)worker2, (void*)&workSlices[t * 4]); }
                else if (t == 3) { std::thread_create(&tids[t], (void*)worker3, (void*)&workSlices[t * 4]); }
                else if (t == 4) { std::thread_create(&tids[t], (void*)worker4, (void*)&workSlices[t * 4]); }
                else if (t == 5) { std::thread_create(&tids[t], (void*)worker5, (void*)&workSlices[t * 4]); }
                else if (t == 6) { std::thread_create(&tids[t], (void*)worker6, (void*)&workSlices[t * 4]); }
                else if (t == 7) { std::thread_create(&tids[t], (void*)worker7, (void*)&workSlices[t * 4]); }
            }
            nSpawned = nSpawned + 1;
            start = end;
            t = t + 1;
        }
        int check = 0;
        int j = 0;
        while (j < nSpawned) {
            unsafe { std::thread_join(tids[j]); }
            check = check + workSlices[j * 4 + 3];
            j = j + 1;
        }

        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, lchecksum(longLivedTree));
    return 0;
}
