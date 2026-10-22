#include <cstdio>

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
        int check = 0;
        for (int i = 0; i < iterations; i++) {
            Node* t = make_tree(depth);
            check += checksum(t);
            free_tree(t);
        }
        std::printf("%d trees of depth %d check: %d\n", iterations, depth, check);
    }

    std::printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
