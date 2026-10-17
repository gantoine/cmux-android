package claude

import (
	"bytes"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
)

const maxLineBytes = 8 << 20

var unsafePathCharacters = regexp.MustCompile(`[^a-zA-Z0-9]`)
var sessionIDPattern = regexp.MustCompile(`^[a-zA-Z0-9-]{1,128}$`)

func ConfigDir() string {
	if dir := os.Getenv("CLAUDE_CONFIG_DIR"); dir != "" {
		return dir
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude")
}

func FindTranscript(configDir, cwd, sessionID string) (string, bool) {
	if configDir == "" || !sessionIDPattern.MatchString(sessionID) {
		return "", false
	}
	projects := filepath.Join(configDir, "projects")
	direct := filepath.Join(projects, unsafePathCharacters.ReplaceAllString(cwd, "-"), sessionID+".jsonl")
	if info, err := os.Stat(direct); err == nil && info.Mode().IsRegular() {
		return direct, true
	}
	matches, _ := filepath.Glob(filepath.Join(projects, "*", sessionID+".jsonl"))
	if len(matches) == 0 {
		return "", false
	}
	return matches[0], true
}

type Tail struct {
	path    string
	window  int64
	started bool
	offset  int64
	partial []byte
}

func NewTail(path string, window int64) *Tail {
	return &Tail{path: path, window: window}
}

func (t *Tail) Lines() (lines [][]byte, restarted bool, err error) {
	file, err := os.Open(t.path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, false, nil
		}
		return nil, false, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, false, err
	}
	if info.Size() < t.offset {
		t.offset, t.partial, t.started, restarted = 0, nil, false, true
	}
	midLine := false
	if !t.started {
		t.started = true
		if t.window > 0 && info.Size() > t.window {
			t.offset, midLine = info.Size()-t.window, true
		}
	}
	if info.Size() == t.offset {
		return nil, restarted, nil
	}
	if _, err := file.Seek(t.offset, io.SeekStart); err != nil {
		return nil, restarted, err
	}
	data, err := io.ReadAll(file)
	if err != nil {
		return nil, restarted, err
	}
	t.offset += int64(len(data))
	if midLine {
		_, data, _ = bytes.Cut(data, []byte{'\n'})
	}
	data = append(t.partial, data...)
	end := bytes.LastIndexByte(data, '\n')
	if end < 0 {
		t.partial = keepPartial(data)
		return nil, restarted, nil
	}
	t.partial = keepPartial(data[end+1:])
	for _, raw := range bytes.Split(data[:end], []byte{'\n'}) {
		if len(bytes.TrimSpace(raw)) > 0 {
			lines = append(lines, raw)
		}
	}
	return lines, restarted, nil
}

func keepPartial(data []byte) []byte {
	if len(data) > maxLineBytes {
		return nil
	}
	return append([]byte(nil), data...)
}
