package cmux

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	defaultCallTimeout = 15 * time.Second
	maxIdleConns       = 4
)

type RPCError struct {
	Code    string
	Message string
	Data    json.RawMessage
}

func (e *RPCError) Error() string {
	if e.Message == "" {
		return e.Code
	}
	return e.Code + ": " + e.Message
}

func ErrorCode(err error) string {
	var rpcErr *RPCError
	if errors.As(err, &rpcErr) {
		return rpcErr.Code
	}
	return ""
}

var errNotSent = errors.New("request not sent")

var readOnlyMethods = map[string]bool{
	"system.identify":        true,
	"system.capabilities":    true,
	"mobile.workspace.list":  true,
	"mobile.terminal.replay": true,
	"notification.list":      true,
}

func isConnectionError(err error) bool {
	var rpcErr *RPCError
	return !errors.As(err, &rpcErr) && !errors.Is(err, context.DeadlineExceeded) && !errors.Is(err, context.Canceled)
}

var ErrAccessDenied = errors.New("cmux refused the connection; set socketControlMode to \"password\" or \"automation\" in ~/.config/cmux/cmux.json")

type PasswordSource func() (string, error)

type Client struct {
	socketPath string
	password   PasswordSource
	nextID     atomic.Uint64

	mu   sync.Mutex
	idle []*conn
}

func NewClient(socketPath string, password PasswordSource) *Client {
	return &Client{socketPath: socketPath, password: password}
}

func (c *Client) SocketPath() string {
	return c.socketPath
}

func (c *Client) Call(ctx context.Context, method string, params any, result any) error {
	if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, defaultCallTimeout)
		defer cancel()
	}
	for attempt := 0; ; attempt++ {
		err := c.callOnce(ctx, method, params, result)
		retryAfter, retryable := rateLimitDelay(err)
		if !retryable || attempt >= 2 {
			return err
		}
		select {
		case <-ctx.Done():
			return err
		case <-time.After(retryAfter):
		}
	}
}

func (c *Client) callOnce(ctx context.Context, method string, params any, result any) error {
	cn, reused, err := c.acquire(ctx)
	if err != nil {
		return err
	}
	raw, err := cn.roundTrip(ctx, c.nextID.Add(1), method, params)
	if err != nil && reused && (errors.Is(err, errNotSent) || (readOnlyMethods[method] && isConnectionError(err))) {
		cn.close()
		if cn, err = c.dial(ctx); err != nil {
			return err
		}
		raw, err = cn.roundTrip(ctx, c.nextID.Add(1), method, params)
	}
	if err != nil {
		cn.close()
		return err
	}
	c.release(cn)
	if result == nil || len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, result); err != nil {
		return fmt.Errorf("decode %s result: %w", method, err)
	}
	return nil
}

func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, cn := range c.idle {
		cn.close()
	}
	c.idle = nil
}

func (c *Client) acquire(ctx context.Context) (*conn, bool, error) {
	c.mu.Lock()
	if n := len(c.idle); n > 0 {
		cn := c.idle[n-1]
		c.idle = c.idle[:n-1]
		c.mu.Unlock()
		return cn, true, nil
	}
	c.mu.Unlock()
	cn, err := c.dial(ctx)
	return cn, false, err
}

func (c *Client) release(cn *conn) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.idle) >= maxIdleConns {
		cn.close()
		return
	}
	c.idle = append(c.idle, cn)
}

func (c *Client) dial(ctx context.Context) (*conn, error) {
	var dialer net.Dialer
	netConn, err := dialer.DialContext(ctx, "unix", c.socketPath)
	if err != nil {
		return nil, fmt.Errorf("connect to cmux at %s (is cmux running?): %w", c.socketPath, err)
	}
	cn := &conn{netConn: netConn, reader: bufio.NewReaderSize(netConn, 1<<16)}
	if err := c.authenticate(ctx, cn); err != nil {
		cn.close()
		return nil, err
	}
	return cn, nil
}

func (c *Client) authenticate(ctx context.Context, cn *conn) error {
	if c.password == nil {
		return nil
	}
	password, err := c.password()
	if err != nil {
		return err
	}
	if password == "" {
		return nil
	}
	_, err = cn.roundTrip(ctx, c.nextID.Add(1), "auth.login", map[string]string{"password": password})
	switch ErrorCode(err) {
	case "":
		return err
	case "auth_unconfigured":
		return nil
	case "auth_failed":
		return errors.New("cmux rejected the socket password; check ~/.local/state/cmux/socket-control-password")
	default:
		return err
	}
}

func (c *Client) Stream(ctx context.Context, params any, handle func(json.RawMessage) error) error {
	cn, err := c.dial(ctx)
	if err != nil {
		return err
	}
	defer cn.close()
	stop := context.AfterFunc(ctx, cn.close)
	defer stop()
	request, err := encodeRequest(c.nextID.Add(1), "events.stream", params)
	if err != nil {
		return err
	}
	if _, err := cn.netConn.Write(request); err != nil {
		return err
	}
	for {
		line, err := cn.readLine()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return err
		}
		if err := handle(line); err != nil {
			return err
		}
	}
}

type conn struct {
	netConn net.Conn
	reader  *bufio.Reader
	once    sync.Once
}

func (cn *conn) close() {
	cn.once.Do(func() { _ = cn.netConn.Close() })
}

func (cn *conn) roundTrip(ctx context.Context, id uint64, method string, params any) (json.RawMessage, error) {
	request, err := encodeRequest(id, method, params)
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = cn.netConn.SetDeadline(deadline)
		defer func() { _ = cn.netConn.SetDeadline(time.Time{}) }()
	}
	interrupt := context.AfterFunc(ctx, func() { _ = cn.netConn.SetDeadline(time.Unix(1, 0)) })
	defer interrupt()
	if _, err := cn.netConn.Write(request); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("%w: %w", errNotSent, err)
	}
	line, err := cn.readLine()
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	return decodeResponse(line)
}

func (cn *conn) readLine() ([]byte, error) {
	line, err := cn.reader.ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	return bytes.TrimRight(line, "\r\n"), nil
}

func encodeRequest(id uint64, method string, params any) ([]byte, error) {
	if params == nil {
		params = struct{}{}
	}
	payload, err := json.Marshal(struct {
		ID     string `json:"id"`
		Method string `json:"method"`
		Params any    `json:"params"`
	}{strconv.FormatUint(id, 10), method, params})
	if err != nil {
		return nil, err
	}
	return append(payload, '\n'), nil
}

func decodeResponse(line []byte) (json.RawMessage, error) {
	if bytes.HasPrefix(line, []byte("ERROR:")) {
		if bytes.Contains(line, []byte("Access denied")) {
			return nil, ErrAccessDenied
		}
		return nil, &RPCError{Code: "error", Message: strings.TrimSpace(string(line[len("ERROR:"):]))}
	}
	var response struct {
		OK     bool            `json:"ok"`
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    string          `json:"code"`
			Message string          `json:"message"`
			Data    json.RawMessage `json:"data"`
		} `json:"error"`
	}
	if err := json.Unmarshal(line, &response); err != nil {
		return nil, fmt.Errorf("decode cmux response: %w", err)
	}
	if !response.OK {
		if response.Error == nil {
			return nil, &RPCError{Code: "unknown_error"}
		}
		return nil, &RPCError{Code: response.Error.Code, Message: response.Error.Message, Data: response.Error.Data}
	}
	return response.Result, nil
}

func rateLimitDelay(err error) (time.Duration, bool) {
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) {
		return 0, false
	}
	if rpcErr.Code != "rate_limited" && rpcErr.Code != "overloaded" && rpcErr.Code != "viewport_transition" {
		return 0, false
	}
	var data struct {
		RetryAfterMS int `json:"retry_after_ms"`
	}
	_ = json.Unmarshal(rpcErr.Data, &data)
	if data.RetryAfterMS <= 0 {
		data.RetryAfterMS = 100
	}
	return time.Duration(data.RetryAfterMS) * time.Millisecond, true
}

func DefaultSocketPath() string {
	if path := os.Getenv("CMUX_SOCKET_PATH"); path != "" {
		return path
	}
	home, _ := os.UserHomeDir()
	stateDir := filepath.Join(home, ".local", "state", "cmux")
	stable := filepath.Join(stateDir, "cmux.sock")
	if _, err := os.Stat(stable); err == nil {
		return stable
	}
	if marker, err := os.ReadFile(filepath.Join(stateDir, "last-socket-path")); err == nil {
		if path := strings.TrimSpace(string(marker)); path != "" {
			return path
		}
	}
	return stable
}

func DefaultPasswordSource() PasswordSource {
	return func() (string, error) {
		if password := os.Getenv("CMUX_SOCKET_PASSWORD"); password != "" {
			return password, nil
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		contents, err := os.ReadFile(filepath.Join(home, ".local", "state", "cmux", "socket-control-password"))
		if errors.Is(err, os.ErrNotExist) {
			return "", nil
		}
		if err != nil {
			return "", err
		}
		return strings.TrimSpace(string(contents)), nil
	}
}
