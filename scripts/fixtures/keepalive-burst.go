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
const latencyWindow = 250 * time.Millisecond

type histogram struct {
	buckets [64 * 16]uint64
	count   uint64
	max     uint64
}

type windowResult struct {
	StartUS    int64  `json:"start_us"`
	DurationUS int64  `json:"duration_us"`
	Requests   uint64 `json:"requests"`
	P99        uint64 `json:"p99_us"`
	P999       uint64 `json:"p999_us"`
	Max        uint64 `json:"max_us"`
}

type connectionResult struct {
	Peer      string `json:"peer"`
	Hot       bool   `json:"hot"`
	Completed uint64 `json:"completed"`
	Error     string `json:"error,omitempty"`
	windows   []histogram
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
	P999        uint64             `json:"p999_us"`
	PerSecond   []uint64           `json:"per_second_requests"`
	Windows     []windowResult     `json:"latency_windows"`
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

func (h *histogram) record(us uint64) {
	h.buckets[bucket(us)]++
	h.count++
	h.max = max(h.max, us)
}

func (h *histogram) merge(other *histogram) {
	for index, count := range other.buckets {
		h.buckets[index] += count
	}
	h.count += other.count
	h.max = max(h.max, other.max)
}

func (h *histogram) percentile(perMille uint64) uint64 {
	if h.count == 0 {
		return 0
	}
	rank := (h.count*perMille + 999) / 1000
	for index, count := range h.buckets {
		if rank <= count {
			if index < 32 {
				return uint64(index)
			}
			shift := index/16 - 1
			return min(uint64((index%16+17)<<shift)-1, h.max)
		}
		rank -= count
	}
	panic("latency histogram does not match request count")
}

func load(conn net.Conn, request, expected []byte, duration time.Duration, start time.Time, hot bool) connectionResult {
	sample := connectionResult{Peer: conn.LocalAddr().String(), Hot: hot}
	reply := make([]byte, len(expected))
	end := start.Add(duration)
	if err := conn.SetDeadline(end.Add(2 * time.Second)); err != nil {
		sample.Error = err.Error()
		return sample
	}
	if hot {
		sample.windows = make([]histogram, (duration-1)/latencyWindow+1)
		for started := time.Now(); started.Before(end); {
			if err := exchange(conn, request, reply, expected); err != nil {
				sample.Error = err.Error()
				break
			}
			finished := time.Now()
			if finished.Before(end) {
				sample.Completed++
				window := finished.Sub(start) / latencyWindow
				sample.windows[window].record(uint64(finished.Sub(started).Microseconds()))
			}
			started = finished
		}
	} else {
		time.Sleep(time.Until(end))
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
	hot := flag.Int("hot-connections", 0, "number of pre-opened connections to load; zero means all")
	seconds := flag.Int("seconds", 12, "seconds of closed-loop load")
	duration := flag.Duration("duration", 0, "closed-loop duration such as 500ms; overrides -seconds")
	flag.Parse()
	if *duration == 0 {
		*duration = time.Duration(*seconds) * time.Second
	}
	if *hot == 0 {
		*hot = *connections
	}
	if *address == "" || *connections < 1 || *hot < 1 || *hot > *connections || duration.Microseconds() < 1 {
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
	for index, peer := range peers {
		go func() {
			results <- load(peer.conn, request, peer.response, *duration, start, index < *hot)
		}()
	}
	summary := result{
		Connections: make([]connectionResult, 0, *connections),
		PerSecond:   make([]uint64, (*duration-1)/time.Second+1),
		Duration:    duration.Microseconds(),
		Setup:       start.Sub(startup).Microseconds(),
	}
	windows := make([]histogram, (*duration-1)/latencyWindow+1)
	for range peers {
		sample := <-results
		summary.Connections = append(summary.Connections, sample)
		summary.Requests += sample.Completed
		for index := range sample.windows {
			windows[index].merge(&sample.windows[index])
		}
	}
	var full histogram
	for index := range windows {
		window := &windows[index]
		full.merge(window)
		offset := time.Duration(index) * latencyWindow
		summary.PerSecond[offset/time.Second] += window.count
		summary.Windows = append(summary.Windows, windowResult{
			StartUS: offset.Microseconds(), DurationUS: min(latencyWindow, *duration-offset).Microseconds(),
			Requests: window.count, P99: window.percentile(990), P999: window.percentile(999), Max: window.max,
		})
	}
	summary.P50 = full.percentile(500)
	summary.P95 = full.percentile(950)
	summary.P99 = full.percentile(990)
	summary.P999 = full.percentile(999)
	if err := json.NewEncoder(os.Stdout).Encode(summary); err != nil {
		log.Fatal(err)
	}
}
