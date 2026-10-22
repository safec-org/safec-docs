package main

import "fmt"

type Node struct {
	left, right *Node
}

func makeTree(depth int) *Node {
	n := &Node{}
	if depth > 0 {
		n.left = makeTree(depth - 1)
		n.right = makeTree(depth - 1)
	}
	return n
}

func checksum(n *Node) int {
	if n.left == nil {
		return 1
	}
	return 1 + checksum(n.left) + checksum(n.right)
}

func main() {
	minDepth := 4
	maxDepth := 18

	stretchDepth := maxDepth + 1
	stretchTree := makeTree(stretchDepth)
	fmt.Printf("stretch tree of depth %d check: %d\n", stretchDepth, checksum(stretchTree))
	stretchTree = nil

	longLivedTree := makeTree(maxDepth)

	for depth := minDepth; depth <= maxDepth; depth += 2 {
		iterations := 1 << (maxDepth - depth + minDepth)
		check := 0
		for i := 0; i < iterations; i++ {
			t := makeTree(depth)
			check += checksum(t)
		}
		fmt.Printf("%d trees of depth %d check: %d\n", iterations, depth, check)
	}

	fmt.Printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree))
}
