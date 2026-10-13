package cmux

import (
	"bytes"
	"context"
	"encoding/json"
)

type Terminal struct {
	ID               string  `json:"id"`
	Title            string  `json:"title"`
	CurrentDirectory *string `json:"current_directory"`
	IsReady          bool    `json:"is_ready"`
	IsFocused        bool    `json:"is_focused"`
	AgentSource      *string `json:"agent_source"`
	AgentState       *string `json:"agent_state"`
}

type Workspace struct {
	ID               string     `json:"id"`
	WindowID         *string    `json:"window_id"`
	Title            string     `json:"title"`
	Description      *string    `json:"description"`
	CustomColor      *string    `json:"custom_color"`
	CurrentDirectory *string    `json:"current_directory"`
	IsSelected       bool       `json:"is_selected"`
	IsPinned         bool       `json:"is_pinned"`
	GroupID          *string    `json:"group_id"`
	Preview          *string    `json:"preview"`
	PreviewAt        *float64   `json:"preview_at"`
	LastActivityAt   *float64   `json:"last_activity_at"`
	HasUnread        bool       `json:"has_unread"`
	UnreadCount      int        `json:"unread_count"`
	Terminals        []Terminal `json:"terminals"`
}

type WorkspaceList struct {
	Workspaces []Workspace `json:"workspaces"`
}

type Cursor struct {
	Row      int    `json:"row"`
	Column   int    `json:"column"`
	Visible  bool   `json:"visible"`
	Style    string `json:"style"`
	Blinking bool   `json:"blinking"`
}

type Style struct {
	ID            int    `json:"id"`
	Foreground    string `json:"foreground"`
	Background    string `json:"background"`
	Bold          bool   `json:"bold"`
	Faint         bool   `json:"faint"`
	Italic        bool   `json:"italic"`
	Underline     bool   `json:"underline"`
	Strikethrough bool   `json:"strikethrough"`
	Overline      bool   `json:"overline"`
	Blink         bool   `json:"blink"`
	Inverse       bool   `json:"inverse"`
	Invisible     bool   `json:"invisible"`
}

type Span struct {
	Row       int    `json:"row"`
	Column    int    `json:"column"`
	StyleID   int    `json:"style_id"`
	Text      string `json:"text"`
	CellWidth *int   `json:"cell_width"`
}

type RenderGrid struct {
	Format             string  `json:"format"`
	Columns            int     `json:"columns"`
	Rows               int     `json:"rows"`
	Cursor             *Cursor `json:"cursor"`
	Styles             []Style `json:"styles"`
	RowSpans           []Span  `json:"row_spans"`
	ActiveScreen       string  `json:"active_screen"`
	TerminalForeground string  `json:"terminal_foreground"`
	TerminalBackground string  `json:"terminal_background"`
	ScrollbackRows     int     `json:"scrollback_rows"`
	ScrollbackSpans    []Span  `json:"scrollback_spans"`
	HistoryRows        int     `json:"history_rows"`
}

type Replay struct {
	WorkspaceID string      `json:"workspace_id"`
	SurfaceID   string      `json:"surface_id"`
	Columns     int         `json:"columns"`
	Rows        int         `json:"rows"`
	RenderGrid  *RenderGrid `json:"render_grid"`
}

type Notification struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Subtitle    string `json:"subtitle"`
	Body        string `json:"body"`
	WorkspaceID string `json:"workspace_id"`
	SurfaceID   string `json:"surface_id"`
	TabTitle    string `json:"tab_title"`
	CreatedAt   string `json:"created_at"`
	IsRead      bool   `json:"is_read"`
}

type Identity struct {
	App     string `json:"app"`
	Version string `json:"version"`
	Build   string `json:"build"`
}

type CreatedWorkspace struct {
	WorkspaceID string `json:"workspace_id"`
	SurfaceID   string `json:"surface_id"`
}

type Viewport struct {
	ClientID   string
	Columns    int
	Rows       int
	Generation int
}

func (c *Client) Identify(ctx context.Context) (Identity, error) {
	var identity Identity
	err := c.Call(ctx, "system.identify", nil, &identity)
	return identity, err
}

func (c *Client) Workspaces(ctx context.Context) ([]Workspace, error) {
	var list WorkspaceList
	err := c.Call(ctx, "mobile.workspace.list", nil, &list)
	return list.Workspaces, err
}

func (c *Client) Replay(ctx context.Context, workspaceID, surfaceID string) (Replay, error) {
	var replay Replay
	err := c.Call(ctx, "mobile.terminal.replay", map[string]any{
		"workspace_id": workspaceID,
		"surface_id":   surfaceID,
	}, &replay)
	return replay, err
}

func (c *Client) History(ctx context.Context, workspaceID, surfaceID string, maxRows int) (Replay, error) {
	var replay Replay
	err := c.Call(ctx, "mobile.terminal.replay", map[string]any{
		"workspace_id":        workspaceID,
		"surface_id":          surfaceID,
		"anchor":              "screen",
		"max_scrollback_rows": maxRows,
	}, &replay)
	return replay, err
}

func (c *Client) Input(ctx context.Context, workspaceID, surfaceID, text string) error {
	return c.Call(ctx, "mobile.terminal.input", map[string]any{
		"workspace_id": workspaceID,
		"surface_id":   surfaceID,
		"text":         text,
	}, nil)
}

func (c *Client) SendKey(ctx context.Context, workspaceID, surfaceID, key string) error {
	return c.Call(ctx, "surface.send_key", map[string]any{
		"workspace_id": workspaceID,
		"surface_id":   surfaceID,
		"key":          key,
	}, nil)
}

func (c *Client) Paste(ctx context.Context, workspaceID, surfaceID, text string, submit bool) error {
	submitKey := "none"
	if submit {
		submitKey = "return"
	}
	return c.Call(ctx, "mobile.terminal.paste", map[string]any{
		"workspace_id": workspaceID,
		"surface_id":   surfaceID,
		"text":         text,
		"submit_key":   submitKey,
	}, nil)
}

func (c *Client) Scroll(ctx context.Context, workspaceID, surfaceID string, deltaLines int) error {
	return c.Call(ctx, "mobile.terminal.scroll", map[string]any{
		"workspace_id": workspaceID,
		"surface_id":   surfaceID,
		"delta_lines":  deltaLines,
	}, nil)
}

func (c *Client) SetViewport(ctx context.Context, workspaceID, surfaceID string, viewport Viewport) error {
	return c.Call(ctx, "mobile.terminal.viewport", map[string]any{
		"workspace_id":        workspaceID,
		"surface_id":          surfaceID,
		"client_id":           viewport.ClientID,
		"viewport_columns":    viewport.Columns,
		"viewport_rows":       viewport.Rows,
		"viewport_generation": viewport.Generation,
	}, nil)
}

func (c *Client) ClearViewport(ctx context.Context, workspaceID, surfaceID, clientID string, generation int) error {
	return c.Call(ctx, "mobile.terminal.viewport", map[string]any{
		"workspace_id":        workspaceID,
		"surface_id":          surfaceID,
		"client_id":           clientID,
		"viewport_generation": generation,
		"clear":               true,
	}, nil)
}

func (c *Client) CreateWorkspace(ctx context.Context, title, cwd string) (CreatedWorkspace, error) {
	params := map[string]any{"focus": false}
	if title != "" {
		params["title"] = title
	}
	if cwd != "" {
		params["cwd"] = cwd
	}
	var created CreatedWorkspace
	err := c.Call(ctx, "workspace.create", params, &created)
	return created, err
}

func (c *Client) CloseWorkspace(ctx context.Context, workspaceID string, force bool) error {
	return c.Call(ctx, "workspace.close", map[string]any{"workspace_id": workspaceID, "force": force}, nil)
}

func (c *Client) RenameWorkspace(ctx context.Context, workspaceID, title string) error {
	return c.Call(ctx, "workspace.rename", map[string]any{"workspace_id": workspaceID, "title": title}, nil)
}

func (c *Client) CreateTerminal(ctx context.Context, workspaceID string) (string, error) {
	var created struct {
		CreatedTerminalID string `json:"created_terminal_id"`
	}
	err := c.Call(ctx, "mobile.terminal.create", map[string]any{"workspace_id": workspaceID}, &created)
	return created.CreatedTerminalID, err
}

func (c *Client) Notifications(ctx context.Context) ([]Notification, error) {
	var list struct {
		Notifications []Notification `json:"notifications"`
	}
	err := c.Call(ctx, "notification.list", nil, &list)
	return list.Notifications, err
}

type Event struct {
	Type        string          `json:"type"`
	Seq         int64           `json:"seq"`
	Name        string          `json:"name"`
	Category    string          `json:"category"`
	WorkspaceID *string         `json:"workspace_id"`
	SurfaceID   *string         `json:"surface_id"`
	Payload     json.RawMessage `json:"payload"`
}

func (c *Client) Events(ctx context.Context, afterSeq *int64, categories []string, handle func(Event) error) error {
	params := map[string]any{"categories": categories, "include_heartbeats": true}
	if afterSeq != nil {
		params["after_seq"] = *afterSeq
	}
	return c.Stream(ctx, params, func(line json.RawMessage) error {
		if bytes.HasPrefix(line, []byte("ERROR:")) {
			_, err := decodeResponse(line)
			return err
		}
		var event Event
		if err := json.Unmarshal(line, &event); err != nil {
			return err
		}
		if event.Type == "" {
			_, err := decodeResponse(line)
			return err
		}
		return handle(event)
	})
}
