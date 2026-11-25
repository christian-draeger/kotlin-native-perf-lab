// Measure HTTP/1.1 /json bursts on a fixed set of already established TCP connections.
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"math/bits"
	"net"
	"net/http"
	"os"
	"time"
)

const expectedBody = `{"id":42,"name":"benchmark-user","active":true}`

type connectionResult struct {
	Peer      string `json:"peer"`
	Completed uint64 `json:"completed"`
	Error     string `json:"error,omitempty"`
	histogram [64 * 16]uint64
	perSecond []uint64
}

type openedPeer struct {
	conn     net.Conn
	response []byte
	err      error
}

type result struct {
	Connections []connectionResult `json:"connections"`
	Requests    uint64             `json:"requests"`
	P50         uint64             `json:"p50_us"`
	P95         uint64             `json:"p95_us"`
	P99         uint64             `json:"p99_us"`
	PerSecond   []uint64           `json:"per_second_requests"`
	Duration    int64              `json:"duration_us"`
	Setup       int64              `json:"setup_us"`
}

func exchange(conn net.Conn, request, reply, expected []byte) error {
	if n, err := conn.Write(request); err != nil {
		return err
	} else if n != len(request) {
		return io.ErrShortWrite
	}
	if _, err := io.ReadFull(conn, reply); err != nil {
		return err
	}
	if !bytes.Equal(reply, expected) {
		return errors.New("response changed from the verified /json fixture")
	}
	return nil
}

func establish(address string, request []byte) (net.Conn, []byte, error) {
	conn, err := net.DialTimeout("tcp", address, 2*time.Second)
	if err != nil {
		return nil, nil, err
	}
	reply, err := verify(conn, request)
	if err != nil {
		conn.Close()
		return nil, nil, err
	}
	return conn, reply, nil
}

func verify(conn net.Conn, request []byte) ([]byte, error) {
	if err := conn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return nil, err
	}
	if n, err := conn.Write(request); err != nil {
		return nil, err
	} else if n != len(request) {
		return nil, io.ErrShortWrite
	}
	var wire bytes.Buffer
	reader := bufio.NewReader(io.TeeReader(conn, &wire))
	response, err := http.ReadResponse(reader, nil)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.Proto != "HTTP/1.1" || response.StatusCode != 200 || response.Close ||
		response.Header.Get("Content-Type") != "application/json" || response.ContentLength != int64(len(expectedBody)) {
		return nil, errors.New("expected a persistent HTTP/1.1 JSON response with the fixture body length")
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if !bytes.Equal(body, []byte(expectedBody)) || reader.Buffered() != 0 {
		return nil, errors.New("unexpected fixture JSON body or trailing bytes")
	}
	return wire.Bytes(), nil
}

// Preserve exact microsecond buckets through 31 µs, then use 16 buckets per power of two.
func bucket(us uint64) int {
	if us < 32 {
		return int(us)
	}
	shift := bits.Len64(us) - 5
	return shift*16 + int(us>>shift)
}

func percentile(histogram []uint64, requests, percent uint64) uint64 {
	if requests == 0 {
		return 0
	}
	rank := (requests*percent + 99) / 100
	for index, count := range histogram {
		if rank <= count {
			if index < 32 {
				return uint64(index)
			}
			shift := index/16 - 1
			return uint64((index%16+17)<<shift) - 1
		}
		rank -= count
	}
	panic("latency histogram does not match request count")
}

func load(conn net.Conn, request, expected []byte, countSeconds int, start time.Time) connectionResult {
	sample := connectionResult{
		Peer:      conn.LocalAddr().String(),
		perSecond: make([]uint64, countSeconds),
	}
	reply := make([]byte, len(expected))
	if err := conn.SetDeadline(start.Add(time.Duration(countSeconds+2) * time.Second)); err != nil {
		sample.Error = err.Error()
		return sample
	}
	for started := time.Now(); int(started.Sub(start)/time.Second) < countSeconds; {
		if err := exchange(conn, request, reply, expected); err != nil {
			sample.Error = err.Error()
			break
		}
		finished := time.Now()
		second := int(finished.Sub(start) / time.Second)
		if second < countSeconds {
			sample.Completed++
			sample.perSecond[second]++
			sample.histogram[bucket(uint64(finished.Sub(started).Microseconds()))]++
		}
		started = finished
	}
	if sample.Error == "" {
		if err := exchange(conn, request, reply, expected); err != nil {
			sample.Error = err.Error()
		}
	}
	return sample
}

func main() {
	startup := time.Now()
	address := flag.String("address", "", "server host and port")
	connections := flag.Int("connections", 64, "number of pre-opened TCP connections")
	seconds := flag.Int("seconds", 12, "seconds of closed-loop load")
	flag.Parse()
	if *address == "" || *connections < 1 || *seconds < 1 {
		log.Fatal("use a server address and positive connections and seconds")
	}

	request := []byte("GET /json HTTP/1.1\r\nHost: " + *address + "\r\n\r\n")
	opening := make(chan openedPeer, *connections)
	for range *connections {
		go func() {
			conn, response, err := establish(*address, request)
			opening <- openedPeer{conn, response, err}
		}()
	}
	peers := make([]openedPeer, 0, *connections)
	for range *connections {
		peer := <-opening
		if peer.err != nil {
			log.Fatalf("pre-open connection: %v", peer.err)
		}
		peers = append(peers, peer)
		defer peer.conn.Close()
	}

	start := time.Now()
	results := make(chan connectionResult, *connections)
	for _, peer := range peers {
		go func(peer net.Conn, expected []byte) {
			results <- load(peer, request, expected, *seconds, start)
		}(peer.conn, peer.response)
	}
	summary := result{
		Connections: make([]connectionResult, 0, *connections),
		PerSecond:   make([]uint64, *seconds),
		Duration:    (time.Duration(*seconds) * time.Second).Microseconds(),
		Setup:       start.Sub(startup).Microseconds(),
	}
	var histogram [64 * 16]uint64
	for range peers {
		sample := <-results
		summary.Connections = append(summary.Connections, sample)
		summary.Requests += sample.Completed
		for index, count := range sample.histogram {
			histogram[index] += count
		}
		for index, count := range sample.perSecond {
			summary.PerSecond[index] += count
		}
	}
	summary.P50 = percentile(histogram[:], summary.Requests, 50)
	summary.P95 = percentile(histogram[:], summary.Requests, 95)
	summary.P99 = percentile(histogram[:], summary.Requests, 99)
	if err := json.NewEncoder(os.Stdout).Encode(summary); err != nil {
		log.Fatal(err)
	}
}
