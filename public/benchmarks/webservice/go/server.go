package main

import (
	"fmt"
	"net/http"
)

func handler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"message":"Hello, World!"}`))
}

func main() {
	http.HandleFunc("/", handler)
	fmt.Println("Go server listening on 8084")
	http.ListenAndServe(":8084", nil)
}
