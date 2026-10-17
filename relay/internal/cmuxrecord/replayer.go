package cmuxrecord

import (
	"bufio"
	"encoding/json"
	"net"
	"net/http"
	"sync"
	"time"
)

const heartbeatInterval = 10 * time.Second

type Replayer struct {
	recording *Recording
	byKey     map[string]Call
	byMethod  map[string]Call
	supported map[string]bool

	mu       sync.Mutex
	received []Call
	streams  map[*stream]bool
	nextSeq  int64
}

type stream struct {
	mu   sync.Mutex
	conn net.Conn
}

func (s *stream) write(line []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.conn.Write(append(append([]byte(nil), line...), '\n'))
	return err
}

func NewReplayer(recording *Recording) *Replayer {
	r := &Replayer{
		recording: recording,
		byKey:     map[string]Call{},
		byMethod:  map[string]Call{},
		supported: map[string]bool{},
		streams:   map[*stream]bool{},
	}
	for _, call := range recording.Calls {
		r.byKey[callKey(call.Method, call.Params)] = call
		r.byMethod[call.Method] = call
		if call.Method == "system.capabilities" && call.Result != nil {
			var capabilities struct {
				Methods []string `json:"methods"`
			}
			_ = json.Unmarshal(call.Result, &capabilities)
			for _, method := range capabilities.Methods {
				r.supported[method] = true
			}
		}
	}
	for _, event := range recording.Events {
		var header struct {
			Seq int64 `json:"seq"`
		}
		_ = json.Unmarshal(event, &header)
		r.nextSeq = max(r.nextSeq, header.Seq)
	}
	return r
}

func (r *Replayer) Serve(listener net.Listener) error {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		go r.handle(conn)
	}
}

func (r *Replayer) handle(conn net.Conn) {
	defer conn.Close()
	reader := bufio.NewReaderSize(conn, 1<<16)
	for {
		line, err := readLine(reader)
		if len(trimNewline(line)) > 0 {
			var req request
			if json.Unmarshal(line, &req) == nil {
				if req.Method == "events.stream" {
					r.stream(conn, req.ID)
					return
				}
				if _, werr := conn.Write(r.answer(req)); werr != nil {
					return
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func (r *Replayer) answer(req request) []byte {
	response := map[string]any{"id": req.ID}
	if req.Method == "auth.login" {
		response["ok"] = true
		response["result"] = map[string]any{"authenticated": true}
		return encodeLine(response)
	}
	r.mu.Lock()
	r.received = append(r.received, Call{Method: req.Method, Params: compact(req.Params)})
	r.mu.Unlock()
	call, found := r.byKey[callKey(req.Method, compact(req.Params))]
	if !found {
		call, found = r.byMethod[req.Method]
	}
	switch {
	case found && call.Error != nil:
		response["ok"] = false
		response["error"] = call.Error
	case found:
		response["ok"] = true
		response["result"] = call.Result
	case r.supported[req.Method]:
		response["ok"] = true
		response["result"] = map[string]any{}
	default:
		response["ok"] = false
		response["error"] = map[string]any{"code": "method_not_found", "message": "The recording has no " + req.Method + "."}
	}
	return encodeLine(response)
}

func (r *Replayer) stream(conn net.Conn, id string) {
	s := &stream{conn: conn}
	ack := map[string]any{}
	if r.recording.StreamAck != nil {
		_ = json.Unmarshal(r.recording.StreamAck, &ack)
	} else {
		ack["ok"] = true
	}
	ack["id"] = id
	if s.write(trimNewline(encodeLine(ack))) != nil {
		return
	}
	r.mu.Lock()
	r.streams[s] = true
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.streams, s)
		r.mu.Unlock()
	}()
	closed := make(chan struct{})
	go func() {
		_, _ = bufio.NewReader(conn).ReadBytes(0)
		close(closed)
	}()
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-closed:
			return
		case <-ticker.C:
			if r.recording.Heartbeat != nil && s.write(r.recording.Heartbeat) != nil {
				return
			}
		}
	}
}

func (r *Replayer) Emit(name string) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	emitted := 0
	for _, event := range r.recording.Events {
		if eventName(event) != name {
			continue
		}
		var fields map[string]any
		if json.Unmarshal(event, &fields) != nil {
			continue
		}
		r.nextSeq++
		fields["seq"] = r.nextSeq
		line := trimNewline(encodeLine(fields))
		for s := range r.streams {
			_ = s.write(line)
		}
		emitted++
	}
	return emitted
}

func (r *Replayer) Received(method string) []Call {
	r.mu.Lock()
	defer r.mu.Unlock()
	var calls []Call
	for _, call := range r.received {
		if method == "" || call.Method == method {
			calls = append(calls, call)
		}
	}
	return calls
}

func (r *Replayer) ControlHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /calls", func(w http.ResponseWriter, req *http.Request) {
		calls := r.Received(req.URL.Query().Get("method"))
		if calls == nil {
			calls = []Call{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(calls)
	})
	mux.HandleFunc("POST /emit", func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]int{"emitted": r.Emit(req.URL.Query().Get("name"))})
	})
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	return mux
}

func encodeLine(value any) []byte {
	encoded, _ := json.Marshal(value)
	return append(encoded, '\n')
}
