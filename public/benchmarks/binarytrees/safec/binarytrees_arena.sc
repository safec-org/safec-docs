extern int printf(const char* fmt, ...);
#include <std/mem.sc>

// maxDepth=18 -> a make_tree(18) tree has 2^19-1 = 524,287 nodes; the
// stretch tree is make_tree(19) -> 2^20-1 = 1,048,575 nodes. Node is 2
// pointers = 16 bytes. Scratch holds one tree at a time (stretch tree
// first, then each of the loop's progressively-built-and-discarded trees,
// largest of which is also depth 18) -- capacity sized for the larger of
// the two (the stretch tree) with real margin, since arena allocation has
// no runtime bounds check: overflowing capacity here is real memory
// corruption, not a caught error. LongLived holds the one depth-18 tree
// kept alive for the whole run, in its own region so Scratch's resets
// between trees can't touch it.
region Scratch { capacity: 20000000 }
region LongLived { capacity: 10000000 }

struct SNode {
    ?&arena<Scratch> SNode left;
    ?&arena<Scratch> SNode right;
};

struct LNode {
    ?&arena<LongLived> LNode left;
    ?&arena<LongLived> LNode right;
};

&arena<Scratch> SNode make_stree(int depth) {
    &arena<Scratch> SNode n = new<Scratch> SNode;
    if (depth > 0) {
        n->left = make_stree(depth - 1);
        n->right = make_stree(depth - 1);
    } else {
        unsafe { n->left = (?&arena<Scratch> SNode)(struct SNode*)0; n->right = (?&arena<Scratch> SNode)(struct SNode*)0; }
    }
    return n;
}

int schecksum(&arena<Scratch> SNode n) {
    unsafe {
        struct SNode* raw = (struct SNode*)n;
        if (raw->left == (struct SNode*)0) { return 1; }
        return 1 + schecksum((&arena<Scratch> SNode)raw->left) + schecksum((&arena<Scratch> SNode)raw->right);
    }
}

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

int main() {
    int minDepth = 4;
    int maxDepth = 18;

    int stretchDepth = maxDepth + 1;
    &arena<Scratch> SNode stretchTree = make_stree(stretchDepth);
    printf("stretch tree of depth %d check: %d\n", stretchDepth, schecksum(stretchTree));
    arena_reset<Scratch>();

    &arena<LongLived> LNode longLivedTree = make_ltree(maxDepth);

    int depth = minDepth;
    while (depth <= maxDepth) {
        int iterations = 1 << (maxDepth - depth + minDepth);
        int check = 0;
        int i = 0;
        while (i < iterations) {
            &arena<Scratch> SNode t = make_stree(depth);
            check = check + schecksum(t);
            arena_reset<Scratch>();
            i = i + 1;
        }
        printf("%d trees of depth %d check: %d\n", iterations, depth, check);
        depth = depth + 2;
    }

    printf("long lived tree of depth %d check: %d\n", maxDepth, lchecksum(longLivedTree));
    return 0;
}
