package main

import "fmt"

const N = 20000000

var a [N]float64

func main() {
	for i := 0; i < N; i++ {
		a[i] = float64(i%1000) * 0.001
	}
	sum := 0.0
	for i := 0; i < N; i++ {
		sum += a[i] * a[i]
	}
	fmt.Printf("%.6f\n", sum)
}
