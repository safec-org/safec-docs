extern int printf(const char* fmt, ...);
#include <std/mem.h>

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
        n->left = null;
        n->right = null;
    }
    return n;
}

int checksum(&heap Node n) {
    match (n->left) {
        case null: return 1;
        case some(l): {
            match (n->right) {
                case some(r): return 1 + checksum(l) + checksum(r);
                case null: return 1 + checksum(l);
            }
        }
    }
    return 1;
}

void free_tree(&heap Node n) {
    match (n->left) { case some(l): free_tree(l); case null: { } }
    match (n->right) { case some(r): free_tree(r); case null: { } }
    std::dealloc(n);
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
        int check = 0;
        int i = 0;
        while (i < iterations) {
            &heap Node t = make_tree(depth);
            check = check + checksum(t);
            free_tree(t);
            i = i + 1;
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree));
    free_tree(longLivedTree);
    return 0;
}
