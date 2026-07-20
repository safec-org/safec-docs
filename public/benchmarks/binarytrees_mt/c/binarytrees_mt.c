#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

#define NTHREADS 8

typedef struct Node {
    struct Node* left;
    struct Node* right;
} Node;

Node* make_tree(int depth) {
    Node* n = (Node*)malloc(sizeof(Node));
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = NULL;
        n->right = NULL;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == NULL) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != NULL) {
        free_tree(n->left);
        free_tree(n->right);
    }
    free(n);
}

typedef struct { int start, end, depth, result; } WorkSlice;

void* worker(void* arg) {
    WorkSlice* s = (WorkSlice*)arg;
    int check = 0;
    for (int i = s->start; i < s->end; i++) {
        Node* t = make_tree(s->depth);
        check += checksum(t);
        free_tree(t);
    }
    s->result = check;
    return NULL;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) perThread = 1;

        pthread_t tids[NTHREADS];
        WorkSlice slices[NTHREADS];
        int nSpawned = 0, start = 0, t = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) end = iterations;
            slices[t].start = start; slices[t].end = end; slices[t].depth = depth;
            pthread_create(&tids[t], NULL, worker, &slices[t]);
            nSpawned++;
            start = end;
            t++;
        }
        int check = 0;
        for (int j = 0; j < nSpawned; j++) {
            pthread_join(tids[j], NULL);
            check += slices[j].result;
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
