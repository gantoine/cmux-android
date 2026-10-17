package cmuxrecord

import (
	"bytes"
	"encoding/json"
	"os"
	"slices"
	"strings"
)

type Call struct {
	Method string          `json:"method"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  json.RawMessage `json:"error,omitempty"`
}

type Recording struct {
	Calls     []Call            `json:"calls"`
	Events    []json.RawMessage `json:"events"`
	StreamAck json.RawMessage   `json:"stream_ack,omitempty"`
	Heartbeat json.RawMessage   `json:"heartbeat,omitempty"`
}

func Load(path string) (*Recording, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var recording Recording
	if err := json.Unmarshal(contents, &recording); err != nil {
		return nil, err
	}
	return &recording, nil
}

func (r *Recording) Save(path string) error {
	encoded, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(encoded, '\n'), 0o644)
}

func (r *Recording) Methods() []string {
	var methods []string
	for _, call := range r.Calls {
		if !slices.Contains(methods, call.Method) {
			methods = append(methods, call.Method)
		}
	}
	slices.Sort(methods)
	return methods
}

func (r *Recording) EventNames() []string {
	var names []string
	for _, event := range r.Events {
		if name := eventName(event); name != "" && !slices.Contains(names, name) {
			names = append(names, name)
		}
	}
	slices.Sort(names)
	return names
}

func eventName(event json.RawMessage) string {
	var header struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal(event, &header)
	return header.Name
}

func callKey(method string, params json.RawMessage) string {
	return method + " " + canonical(params)
}

func canonical(raw json.RawMessage) string {
	if len(bytes.TrimSpace(raw)) == 0 {
		return "{}"
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return strings.TrimSpace(string(raw))
	}
	if object, ok := value.(map[string]any); ok && len(object) == 0 {
		return "{}"
	}
	encoded, _ := json.Marshal(value)
	return string(encoded)
}
