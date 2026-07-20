extern int printf(const char* fmt, ...);
#include <std/mem.sc>
#include <std/thread.sc>

#define NTHREADS 8

struct Node {
    ?&heap Node left;
    ?&heap Node right;
};

&heap Node make_tree(int depth) {
    &heap Node n = new Node;
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        unsafe { n->left = (?&heap Node)(struct Node*)0; n->right = (?&heap Node)(struct Node*)0; }
    }
    return n;
}

int checksum(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left == (struct Node*)0) { return 1; }
        return 1 + checksum((&heap Node)raw->left) + checksum((&heap Node)raw->right);
    }
    return 0;
}

void free_tree(&heap Node n) {
    unsafe {
        struct Node* raw = (struct Node*)n;
        if (raw->left != (struct Node*)0) {
            free_tree((&heap Node)raw->left);
            free_tree((&heap Node)raw->right);
        }
    }
    std::dealloc(n);
}

struct WorkSlice {
    int start;
    int end;
    int depth;
    int result;
};

struct WorkSlice slices[NTHREADS];

void* worker(void* arg) {
    unsafe {
        struct WorkSlice* s = (struct WorkSlice*)arg;
        int check = 0;
        int i = s->start;
        while (i < s->end) {
            &heap Node t = make_tree(s->depth);
            check = check + checksum(t);
            free_tree(t);
            i = i + 1;
        }
        s->result = check;
    }
    return (void*)0;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    &heap Node stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    &heap Node longLivedTree = make_tree(maxDepth);

    int depth = minDepth;
    while (depth <= maxDepth) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) { perThread = 1; }

        unsigned long long tids[NTHREADS];
        int t = 0;
        int nSpawned = 0;
        int start = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) { end = iterations; }
            unsafe {
                slices[t].start = start;
                slices[t].end = end;
                slices[t].depth = depth;
                std::thread_create(&tids[t], (void*)worker, (void*)&slices[t]);
            }
            nSpawned = nSpawned + 1;
            start = end;
            t = t + 1;
        }
        int check = 0;
        int j = 0;
        while (j < nSpawned) {
            unsafe { std::thread_join(tids[j]); }
            check = check + slices[j].result;
            j = j + 1;
        }

        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
