package cmuxrecord

import (
	"bufio"
	"encoding/json"
	"errors"
	"net"
	"os"
	"sync"
)

const maxLine = 64 << 20

type request struct {
	ID     string          `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

type reply struct {
	ID     string          `json:"id"`
	Type   string          `json:"type"`
	OK     *bool           `json:"ok"`
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

type Recorder struct {
	upstream string
	listener net.Listener

	mu        sync.Mutex
	armed     bool
	calls     map[string]Call
	order     []string
	events    []json.RawMessage
	streamAck json.RawMessage
	heartbeat json.RawMessage
}

func NewRecorder(socketPath, upstream string) (*Recorder, error) {
	_ = os.Remove(socketPath)
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, err
	}
	r := &Recorder{upstream: upstream, listener: listener, calls: map[string]Call{}}
	go r.accept()
	return r, nil
}

func (r *Recorder) Close() error {
	return r.listener.Close()
}

func (r *Recorder) Arm() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.armed = true
	r.calls = map[string]Call{}
	r.order = nil
	r.events = nil
}

func (r *Recorder) Disarm() *Recording {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.armed = false
	recording := &Recording{Events: r.events, StreamAck: r.streamAck, Heartbeat: r.heartbeat}
	for _, key := range r.order {
		recording.Calls = append(recording.Calls, r.calls[key])
	}
	if recording.Events == nil {
		recording.Events = []json.RawMessage{}
	}
	return recording
}

func (r *Recorder) accept() {
	for {
		client, err := r.listener.Accept()
		if err != nil {
			return
		}
		go r.proxy(client)
	}
}

func (r *Recorder) proxy(client net.Conn) {
	defer client.Close()
	server, err := net.Dial("unix", r.upstream)
	if err != nil {
		return
	}
	defer server.Close()
	var pendingMu sync.Mutex
	pending := map[string]request{}
	streaming := false

	go func() {
		defer server.Close()
		reader := bufio.NewReaderSize(client, 1<<16)
		for {
			line, err := readLine(reader)
			if len(line) > 0 {
				var req request
				if json.Unmarshal(line, &req) == nil && req.Method != "" {
					pendingMu.Lock()
					pending[req.ID] = req
					if req.Method == "events.stream" {
						streaming = true
					}
					pendingMu.Unlock()
				}
				if _, werr := server.Write(line); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	reader := bufio.NewReaderSize(server, 1<<16)
	for {
		line, err := readLine(reader)
		if len(line) > 0 {
			pendingMu.Lock()
			isStream := streaming
			pendingMu.Unlock()
			r.observe(line, isStream, &pendingMu, pending)
			if _, werr := client.Write(line); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func (r *Recorder) observe(line []byte, streaming bool, pendingMu *sync.Mutex, pending map[string]request) {
	var msg reply
	if json.Unmarshal(line, &msg) != nil {
		return
	}
	trimmed := json.RawMessage(append([]byte(nil), trimNewline(line)...))
	r.mu.Lock()
	defer r.mu.Unlock()
	if streaming {
		switch msg.Type {
		case "event":
			if r.armed {
				r.events = append(r.events, trimmed)
			}
		case "ack":
			if r.streamAck == nil {
				r.streamAck = trimmed
			}
		case "":
		default:
			if r.heartbeat == nil {
				r.heartbeat = trimmed
			}
		}
		return
	}
	pendingMu.Lock()
	req, found := pending[msg.ID]
	delete(pending, msg.ID)
	pendingMu.Unlock()
	if !found || !r.armed || req.Method == "auth.login" {
		return
	}
	call := Call{Method: req.Method, Params: compact(req.Params)}
	if msg.OK != nil && *msg.OK {
		call.Result = compact(msg.Result)
	} else {
		call.Error = compact(msg.Error)
	}
	key := callKey(call.Method, call.Params)
	if _, seen := r.calls[key]; !seen {
		r.order = append(r.order, key)
	}
	r.calls[key] = call
}

func readLine(reader *bufio.Reader) ([]byte, error) {
	line, err := reader.ReadBytes('\n')
	if len(line) > maxLine {
		return nil, errors.New("cmux sent a line that is too long")
	}
	return line, err
}

func trimNewline(line []byte) []byte {
	for len(line) > 0 && (line[len(line)-1] == '\n' || line[len(line)-1] == '\r') {
		line = line[:len(line)-1]
	}
	return line
}

func compact(raw json.RawMessage) json.RawMessage {
	if len(raw) == 0 {
		return nil
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return raw
	}
	encoded, _ := json.Marshal(value)
	return encoded
}
