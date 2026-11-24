// Loopback fixture for checking the traffic generator. Its results never rank as server benchmarks.
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

type stock struct {
	Available bool `json:"available"`
	Units     int  `json:"units"`
}

type item struct {
	SKU         string `json:"sku"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Stock       stock  `json:"stock"`
}

func catalog(id int) []byte {
	data := struct {
		ID    int    `json:"id"`
		Items []item `json:"items"`
	}{ID: id, Items: make([]item, 32)}
	for index := range data.Items {
		data.Items[index] = item{
			SKU:         strconv.Itoa(id) + "-" + strconv.Itoa(index),
			Name:        "Café item " + strconv.Itoa(index),
			Description: "Roasted in München and packed for delivery.",
			Stock:       stock{Available: index > 0, Units: index * 3},
		}
	}
	body, err := json.Marshal(data)
	if err != nil {
		log.Fatal(err)
	}
	return body
}

func fixed(contentType string, body []byte) http.HandlerFunc {
	return func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", contentType)
		response.Write(body)
	}
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", fixed("text/plain", []byte("ok")))
	mux.HandleFunc("GET /json", fixed("application/json", []byte(`{"id":42,"name":"benchmark-user","active":true}`)))
	for _, id := range []int{123, 987} {
		mux.HandleFunc("GET /catalog/"+strconv.Itoa(id), fixed("application/json", catalog(id)))
	}
	mux.HandleFunc("POST /upload", func(response http.ResponseWriter, request *http.Request) {
		count, err := io.Copy(io.Discard, http.MaxBytesReader(response, request.Body, 64*1024))
		if err != nil {
			http.Error(response, "invalid upload", http.StatusBadRequest)
			return
		}
		response.Header().Set("Content-Type", "text/plain")
		io.WriteString(response, strconv.FormatInt(count, 10))
	})
	ready := fixed("text/plain", []byte("ready"))
	mux.HandleFunc("GET /wait", func(response http.ResponseWriter, request *http.Request) {
		time.Sleep(20 * time.Millisecond)
		ready(response, request)
	})
	first := bytes.Repeat([]byte("s"), 16*1024)
	second := bytes.Repeat([]byte("t"), 16*1024+3)
	mux.HandleFunc("GET /download", func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/octet-stream")
		if _, err := response.Write(first); err != nil {
			return
		}
		if err := http.NewResponseController(response).Flush(); err != nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
		response.Write(second)
	})
	server := &http.Server{
		Addr: "127.0.0.1:" + os.Getenv("PORT"), Handler: mux,
		ReadHeaderTimeout: 2 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
