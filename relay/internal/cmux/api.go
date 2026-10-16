package cmux

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
)

type Terminal struct {
	ID               string  `json:"id"`
	Title            string  `json:"title"`
	CurrentDirectory *string `json:"current_directory"`
	IsReady          bool    `json:"is_ready"`
	IsFocused        bool    `json:"is_focused"`
	AgentSource      *string `json:"agent_source"`
	AgentState       *string `json:"agent_state"`
	GitBranch        *string `json:"git_branch,omitempty"`
	GitDirty         *bool   `json:"git_dirty,omitempty"`
	ListeningPorts   []int   `json:"listening_ports,omitempty"`
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

type TerminalGit struct {
	SurfaceID      string  `json:"surface_id"`
	GitBranch      *string `json:"git_branch"`
	GitDirty       *bool   `json:"git_dirty"`
	ListeningPorts []int   `json:"listening_ports"`
}

type WorkspaceGroup struct {
	ID                 string   `json:"id"`
	Name               string   `json:"name"`
	AnchorWorkspaceID  *string  `json:"anchor_workspace_id"`
	MemberWorkspaceIDs []string `json:"member_workspace_ids"`
	IsCollapsed        bool     `json:"is_collapsed"`
	IsPinned           bool     `json:"is_pinned"`
}

type WorkspaceList struct {
	Workspaces []Workspace      `json:"workspaces"`
	Groups     []WorkspaceGroup `json:"groups"`
}

type Cursor struct {
	Row     int    `json:"row"`
	Column  int    `json:"column"`
	Visible bool   `json:"visible"`
	Style   string `json:"style"`
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
}

type Identity struct {
	Version string `json:"version"`
}

type CreatedWorkspace struct {
	WorkspaceID string `json:"workspace_id"`
	SurfaceID   string `json:"surface_id"`
}

type AgentSession struct {
	Kind      string
	SessionID string
	Cwd       string
}

type Viewport struct {
	ClientID   string
	Columns    int
	Rows       int
	Generation int
}

const (
	MethodAgentSession = "surface.resume.get"
	MethodTerminalGit  = "debug.terminals"
	MethodKeepAwake    = "caffeine.status"
	MethodSetKeepAwake = "caffeine.set"
	MethodHostStatus   = "mobile.host.status"
	MethodChatSend     = "mobile.chat.send"
)

type ChatImage struct {
	Data   string `json:"data_b64"`
	Format string `json:"format"`
}

func (c *Client) Methods(ctx context.Context) ([]string, error) {
	var capabilities struct {
		Methods []string `json:"methods"`
	}
	err := c.Call(ctx, "system.capabilities", nil, &capabilities)
	return capabilities.Methods, err
}

func (c *Client) Identify(ctx context.Context) (Identity, error) {
	var identity Identity
	err := c.Call(ctx, "system.identify", nil, &identity)
	return identity, err
}

func (c *Client) HostName(ctx context.Context) (string, error) {
	var status struct {
		MacDisplayName string `json:"mac_display_name"`
	}
	err := c.Call(ctx, MethodHostStatus, nil, &status)
	return strings.TrimSpace(status.MacDisplayName), err
}

func (c *Client) SendChat(ctx context.Context, sessionID, text string, images []ChatImage) error {
	return c.Call(ctx, MethodChatSend, map[string]any{
		"session_id":  sessionID,
		"text":        text,
		"attachments": images,
	}, nil)
}

func (c *Client) AgentSession(ctx context.Context, workspaceID, surfaceID string) (AgentSession, error) {
	var result struct {
		ResumeBinding *struct {
			Kind         string `json:"kind"`
			CheckpointID string `json:"checkpoint_id"`
			Cwd          string `json:"cwd"`
		} `json:"resume_binding"`
	}
	err := c.Call(ctx, MethodAgentSession, map[string]any{"workspace_id": workspaceID, "surface_id": surfaceID}, &result)
	if err != nil || result.ResumeBinding == nil {
		return AgentSession{}, err
	}
	binding := result.ResumeBinding
	return AgentSession{Kind: binding.Kind, SessionID: binding.CheckpointID, Cwd: binding.Cwd}, nil
}

func (c *Client) Workspaces(ctx context.Context) (WorkspaceList, error) {
	var list WorkspaceList
	err := c.Call(ctx, "mobile.workspace.list", nil, &list)
	if list.Workspaces == nil {
		list.Workspaces = []Workspace{}
	}
	if list.Groups == nil {
		list.Groups = []WorkspaceGroup{}
	}
	return list, err
}

func (c *Client) TerminalGit(ctx context.Context) ([]TerminalGit, error) {
	var dump struct {
		Terminals []TerminalGit `json:"terminals"`
	}
	err := c.Call(ctx, MethodTerminalGit, nil, &dump)
	return dump.Terminals, err
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

type FeedOption struct {
	ID          string `json:"id"`
	Label       string `json:"label"`
	Description string `json:"description"`
}

type FeedQuestion struct {
	ID          string       `json:"id"`
	Header      string       `json:"header"`
	Prompt      string       `json:"prompt"`
	MultiSelect bool         `json:"multi_select"`
	Options     []FeedOption `json:"options"`
}

type FeedItem struct {
	ID        string         `json:"id"`
	Kind      string         `json:"kind"`
	Status    string         `json:"status"`
	RequestID string         `json:"request_id"`
	Source    string         `json:"source"`
	Title     string         `json:"title"`
	ToolName  string         `json:"tool_name"`
	ToolInput string         `json:"tool_input"`
	Questions []FeedQuestion `json:"questions"`
	Plan      string         `json:"plan"`
	Cwd       string         `json:"cwd"`
	CreatedAt string         `json:"created_at"`
}

func (c *Client) FeedItems(ctx context.Context) ([]FeedItem, error) {
	var list struct {
		Items []FeedItem `json:"items"`
	}
	err := c.Call(ctx, "feed.list", nil, &list)
	return list.Items, err
}

func (c *Client) ReplyPermission(ctx context.Context, requestID, mode string) error {
	return c.Call(ctx, "feed.permission.reply", map[string]any{"request_id": requestID, "mode": mode}, nil)
}

func (c *Client) ReplyExitPlan(ctx context.Context, requestID, mode, feedback string) error {
	params := map[string]any{"request_id": requestID, "mode": mode}
	if feedback != "" {
		params["feedback"] = feedback
	}
	return c.Call(ctx, "feed.exit_plan.reply", params, nil)
}

func (c *Client) ReplyQuestion(ctx context.Context, requestID string, selections []string) error {
	return c.Call(ctx, "feed.question.reply", map[string]any{"request_id": requestID, "selections": selections}, nil)
}

type Event struct {
	Type        string          `json:"type"`
	Seq         int64           `json:"seq"`
	Name        string          `json:"name"`
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

type AgentMessage struct {
	ID                 string   `json:"id"`
	ThreadID           string   `json:"thread_id"`
	SenderName         string   `json:"sender_name"`
	RecipientSurfaceID string   `json:"recipient_surface_id"`
	Body               string   `json:"body"`
	State              string   `json:"state"`
	CreatedAt          float64  `json:"created_at"`
	DeliveredAt        *float64 `json:"delivered_at"`
	ReadAt             *float64 `json:"read_at"`
	FailureReason      string   `json:"failure_reason"`
}

func (c *Client) SendAgentMessage(ctx context.Context, target, from, body string) (AgentMessage, error) {
	var message AgentMessage
	err := c.Call(ctx, "agent.message.send", map[string]any{"target": target, "from": from, "body": body}, &message)
	return message, err
}

func (c *Client) AgentMessages(ctx context.Context, surfaceID string, limit int) ([]AgentMessage, error) {
	var list struct {
		Messages []AgentMessage `json:"messages"`
	}
	err := c.Call(ctx, "agent.message.list", map[string]any{"surface": surfaceID, "limit": limit}, &list)
	return list.Messages, err
}

func (c *Client) KeepAwake(ctx context.Context) (bool, error) {
	var status struct {
		Enabled bool `json:"enabled"`
	}
	err := c.Call(ctx, MethodKeepAwake, nil, &status)
	return status.Enabled, err
}

func (c *Client) SetKeepAwake(ctx context.Context, enabled bool) (bool, error) {
	var status struct {
		Enabled bool `json:"enabled"`
	}
	err := c.Call(ctx, MethodSetKeepAwake, map[string]any{"enabled": enabled}, &status)
	return status.Enabled, err
}
