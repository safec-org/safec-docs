#include <cstdio>
#include <thread>
#include <vector>

#define NTHREADS 8

struct Node {
    Node* left;
    Node* right;
};

Node* make_tree(int depth) {
    Node* n = new Node();
    if (depth > 0) {
        n->left = make_tree(depth - 1);
        n->right = make_tree(depth - 1);
    } else {
        n->left = nullptr;
        n->right = nullptr;
    }
    return n;
}

int checksum(Node* n) {
    if (n->left == nullptr) return 1;
    return 1 + checksum(n->left) + checksum(n->right);
}

void free_tree(Node* n) {
    if (n->left != nullptr) {
        free_tree(n->left);
        free_tree(n->right);
    }
    delete n;
}

void worker(int start, int end, int depth, int* result) {
    int check = 0;
    for (int i = start; i < end; i++) {
        Node* t = make_tree(depth);
        check += checksum(t);
        free_tree(t);
    }
    *result = check;
}

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    Node* stretchTree = make_tree(stretchDepth);
    std::printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree));
    free_tree(stretchTree);

    Node* longLivedTree = make_tree(maxDepth);

    for (int depth = minDepth; depth <= maxDepth; depth += 2) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int perThread = iterations / NTHREADS;
        if (perThread < 1) perThread = 1;

        std::vector<std::thread> threads;
        int results[NTHREADS] = {0};
        int nSpawned = 0, start = 0, t = 0;
        while (t < NTHREADS && start < iterations) {
            int end = start + perThread;
            if (t == NTHREADS - 1 || end > iterations) end = iterations;
            threads.emplace_back(worker, start, end, depth, &results[t]);
            nSpawned++;
            start = end;
            t++;
        }
        int check = 0;
        for (int j = 0; j < nSpawned; j++) {
            threads[j].join();
            check += results[j];
        }
        std::printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    std::printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
