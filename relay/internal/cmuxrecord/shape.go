package cmuxrecord

import (
	"encoding/json"
	"fmt"
	"maps"
	"regexp"
	"slices"
	"strings"
)

type Change struct {
	Source string
	Path   string
	Detail string
}

func (c Change) String() string {
	return fmt.Sprintf("%s %s: %s", c.Source, c.Path, c.Detail)
}

type Comparison struct {
	Broken []Change
	Added  []Change
}

var volatileKey = regexp.MustCompile(`^([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9]+)$`)

type shape map[string]map[string]bool

func (s shape) add(path, kind string) {
	if s[path] == nil {
		s[path] = map[string]bool{}
	}
	s[path][kind] = true
}

func (s shape) walk(path string, value any) {
	switch v := value.(type) {
	case map[string]any:
		s.add(path, "object")
		for key, child := range v {
			if volatileKey.MatchString(key) {
				key = "*"
			}
			s.walk(path+"."+key, child)
		}
	case []any:
		s.add(path, "array")
		for _, child := range v {
			s.walk(path+"[]", child)
		}
	case string:
		s.add(path, "string")
	case float64:
		s.add(path, "number")
	case bool:
		s.add(path, "bool")
	case nil:
		s.add(path, "null")
	}
}

func shapeOf(raws ...json.RawMessage) shape {
	s := shape{}
	for _, raw := range raws {
		if len(raw) == 0 {
			continue
		}
		var value any
		if json.Unmarshal(raw, &value) == nil {
			s.walk("$", value)
		}
	}
	return s
}

func Compare(want, got *Recording) Comparison {
	var comparison Comparison
	for _, method := range want.Methods() {
		wantShape, wantFailed := callShapes(want, method)
		gotShape, gotFailed := callShapes(got, method)
		source := "cmux " + method
		switch {
		case len(gotShape) == 0 && !gotFailed:
			comparison.Broken = append(comparison.Broken, Change{source, "$", "the relay no longer calls it, or cmux never answered"})
		case gotFailed && !wantFailed:
			comparison.Broken = append(comparison.Broken, Change{source, "$", "used to succeed and now fails"})
		default:
			comparison.compare(source, wantShape, gotShape)
		}
	}
	for _, name := range want.EventNames() {
		wantShape := eventShapes(want, name)
		gotShape := eventShapes(got, name)
		source := "event " + name
		if len(gotShape) == 0 {
			comparison.Broken = append(comparison.Broken, Change{source, "$", "cmux no longer publishes it during the scenario"})
			continue
		}
		comparison.compare(source, wantShape, gotShape)
	}
	return comparison
}

func callShapes(recording *Recording, method string) (shape, bool) {
	var results []json.RawMessage
	failed := false
	for _, call := range recording.Calls {
		if call.Method != method {
			continue
		}
		if call.Error != nil {
			failed = true
			continue
		}
		results = append(results, call.Result)
	}
	return shapeOf(results...), failed && len(results) == 0
}

func eventShapes(recording *Recording, name string) shape {
	var events []json.RawMessage
	for _, event := range recording.Events {
		if eventName(event) == name {
			events = append(events, event)
		}
	}
	return shapeOf(events...)
}

func (c *Comparison) compare(source string, want, got shape) {
	for _, path := range slices.Sorted(maps.Keys(want)) {
		gotKinds, present := got[path]
		if !present {
			if !insideEmptyArray(path, got) && !insideNull(path, got) {
				c.Broken = append(c.Broken, Change{source, path, "missing"})
			}
			continue
		}
		wantKinds := want[path]
		if !compatible(wantKinds, gotKinds) {
			c.Broken = append(c.Broken, Change{source, path, fmt.Sprintf("was %s, now %s", kinds(wantKinds), kinds(gotKinds))})
		}
	}
	for _, path := range slices.Sorted(maps.Keys(got)) {
		if _, present := want[path]; !present {
			c.Added = append(c.Added, Change{source, path, "new " + kinds(got[path])})
		}
	}
}

func insideEmptyArray(path string, got shape) bool {
	for prefix := path; strings.Contains(prefix, "[]"); {
		prefix = prefix[:strings.LastIndex(prefix, "[]")]
		if got[prefix]["array"] {
			if _, hasElements := got[prefix+"[]"]; !hasElements {
				return true
			}
		}
	}
	return false
}

func insideNull(path string, got shape) bool {
	for {
		cut := max(strings.LastIndex(path, "."), strings.LastIndex(path, "[]"))
		if cut <= 0 {
			return false
		}
		path = path[:cut]
		if kinds, present := got[path]; present {
			return len(kinds) == 1 && kinds["null"]
		}
	}
}

func compatible(want, got map[string]bool) bool {
	for kind := range got {
		if kind != "null" && !want[kind] && !(len(want) == 1 && want["null"]) {
			return false
		}
	}
	return true
}

func kinds(set map[string]bool) string {
	return strings.Join(slices.Sorted(maps.Keys(set)), "|")
}
