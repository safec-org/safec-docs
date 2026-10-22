package main

import (
	"fmt"
	"sync"
)

const nthreads = 8

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
		perThread := iterations / nthreads
		if perThread < 1 {
			perThread = 1
		}

		var wg sync.WaitGroup
		results := make([]int, nthreads)
		nSpawned := 0
		start := 0
		for t := 0; t < nthreads && start < iterations; t++ {
			end := start + perThread
			if t == nthreads-1 || end > iterations {
				end = iterations
			}
			wg.Add(1)
			go func(start, end, depth, slot int) {
				defer wg.Done()
				check := 0
				for i := start; i < end; i++ {
					t := makeTree(depth)
					check += checksum(t)
				}
				results[slot] = check
			}(start, end, depth, t)
			nSpawned++
			start = end
		}
		wg.Wait()
		check := 0
		for j := 0; j < nSpawned; j++ {
			check += results[j]
		}
		fmt.Printf("%d trees of depth %d check: %d\n", iterations, depth, check)
	}

	fmt.Printf("long lived tree of depth %d check: %d\n", maxDepth, checksum(longLivedTree))
}
