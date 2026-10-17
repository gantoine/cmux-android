//go:build e2e

package e2e

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type relayProcess struct {
	address string
	token   string
	logPath string
	serve   *exec.Cmd
}

var relay relayProcess

func TestMain(m *testing.M) {
	os.Exit(run(m))
}

func run(m *testing.M) int {
	if err := reachCmux(); err != nil {
		fmt.Fprintln(os.Stderr, "cmux is not reachable:", err)
		return 1
	}
	dir, err := os.MkdirTemp("", "muxer-e2e")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer os.RemoveAll(dir)
	if err := startRelay(dir); err != nil {
		fmt.Fprintln(os.Stderr, "could not start the relay:", err)
		printLog()
		return 1
	}
	defer stopRelay()
	watchCtx, stopWatching := context.WithCancel(context.Background())
	defer stopWatching()
	go watchCmux(watchCtx, os.Getenv("E2E_DIAGNOSTICS_DIR"))
	code := m.Run()
	if code != 0 {
		printLog()
	}
	return code
}

func reachCmux() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	client := cmux.NewClient(cmux.DefaultSocketPath(), cmux.DefaultPasswordSource())
	defer client.Close()
	_, err := client.Identify(ctx)
	return err
}

func startRelay(dir string) error {
	binary := filepath.Join(dir, "muxer")
	build := exec.Command(filepath.Join(runtime.GOROOT(), "bin", "go"), "build", "-o", binary, "../cmd/muxer")
	if output, err := build.CombinedOutput(); err != nil {
		return fmt.Errorf("build: %v\n%s", err, output)
	}
	address, err := freeAddress()
	if err != nil {
		return err
	}
	stateDir := filepath.Join(dir, "state")
	env := append(os.Environ(), "MUXER_STATE_DIR="+stateDir)
	relay.address = address
	relay.logPath = filepath.Join(dir, "relay.log")
	logFile, err := os.Create(relay.logPath)
	if err != nil {
		return err
	}
	relay.serve = exec.Command(binary, "serve", "--listen", address, "--name", "e2e")
	relay.serve.Env = env
	relay.serve.Stdout, relay.serve.Stderr = logFile, logFile
	if err := relay.serve.Start(); err != nil {
		return err
	}
	if err := waitForHealth(address); err != nil {
		return err
	}
	code, err := pairingCode(binary, env, address)
	if err != nil {
		return err
	}
	relay.token, err = pair(address, code)
	return err
}

func stopRelay() {
	if relay.serve != nil && relay.serve.Process != nil {
		_ = relay.serve.Process.Signal(os.Interrupt)
		done := make(chan struct{})
		go func() {
			_ = relay.serve.Wait()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			_ = relay.serve.Process.Kill()
		}
	}
}

func printLog() {
	if relay.logPath == "" {
		return
	}
	if contents, err := os.ReadFile(relay.logPath); err == nil {
		fmt.Fprintf(os.Stderr, "--- relay log ---\n%s\n", contents)
	}
}

func freeAddress() (string, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer listener.Close()
	return listener.Addr().String(), nil
}

func waitForHealth(address string) error {
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		response, err := http.Get("http://" + address + "/v1/health")
		if err == nil {
			response.Body.Close()
			if response.StatusCode == http.StatusOK {
				return nil
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	return fmt.Errorf("relay at %s never became healthy", address)
}

var pairingCodePattern = regexp.MustCompile(`Code:\s+([A-Z0-9]{4}-[A-Z0-9]{4})`)

func pairingCode(binary string, env []string, address string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, binary, "pair", "--listen", address, "--url", "http://"+address)
	command.Env = env
	stdout, err := command.StdoutPipe()
	if err != nil {
		return "", err
	}
	if err := command.Start(); err != nil {
		return "", err
	}
	defer func() {
		_ = command.Process.Kill()
		_ = command.Wait()
	}()
	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		if match := pairingCodePattern.FindStringSubmatch(scanner.Text()); match != nil {
			return match[1], nil
		}
	}
	return "", fmt.Errorf("muxer pair printed no code")
}

func pair(address, code string) (string, error) {
	body, _ := json.Marshal(map[string]string{"code": code, "device_name": "e2e"})
	response, err := http.Post("http://"+address+"/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	var paired struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(response.Body).Decode(&paired); err != nil || paired.Token == "" {
		return "", fmt.Errorf("pairing failed with status %d", response.StatusCode)
	}
	return paired.Token, nil
}

func uniqueName(t *testing.T) string {
	name := strings.NewReplacer("/", "-", " ", "-").Replace(t.Name())
	return fmt.Sprintf("e2e-%s-%d", name, time.Now().UnixNano()%1_000_000)
}
