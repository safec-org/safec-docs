package main

import (
	"fmt"
	"net/http"
	"os"
)

func handler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"message":"Hello, World!"}`))
}

func main() {
	http.HandleFunc("/", handler)
	port := os.Getenv("PORT") // default 8084
	if port == "" {
		port = "8084"
	}
	fmt.Println("Go server listening on " + port)
	http.ListenAndServe(":"+port, nil)
}
