package approvals

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type Kind string

const (
	KindPermission Kind = "permission"
	KindQuestion   Kind = "question"
	KindPlan       Kind = "plan"
)

const (
	feedKindPermission = "permissionRequest"
	feedKindQuestion   = "question"
	feedKindPlan       = "exitPlan"
	feedStatusPending  = "pending"
	maxDetailLength    = 8000
	lookupAttempts     = 5
	lookupDelay        = 200 * time.Millisecond
	sweepInterval      = 15 * time.Second
	callTimeout        = 15 * time.Second
)

var actionableHooks = map[string]bool{
	"PermissionRequest": true,
	"AskUserQuestion":   true,
	"ExitPlanMode":      true,
}

var permissionModes = map[string]bool{"once": true, "always": true, "deny": true}

var planModes = map[string]bool{"manual": true, "autoAccept": true, "deny": true}

const maxFeedbackLength = 4000

var ErrNotPending = errors.New("that request was already answered or timed out")

type Option struct {
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}

type Question struct {
	Header      string   `json:"header,omitempty"`
	Prompt      string   `json:"prompt"`
	MultiSelect bool     `json:"multi_select"`
	Options     []Option `json:"options"`
}

type Approval struct {
	ID          string     `json:"id"`
	Kind        Kind       `json:"kind"`
	Agent       string     `json:"agent,omitempty"`
	WorkspaceID string     `json:"workspace_id"`
	SurfaceID   string     `json:"surface_id"`
	Tool        string     `json:"tool,omitempty"`
	Detail      string     `json:"detail,omitempty"`
	Description string     `json:"description,omitempty"`
	Questions   []Question `json:"questions,omitempty"`
	Plan        string     `json:"plan,omitempty"`
	Cwd         string     `json:"cwd,omitempty"`
	CreatedAt   string     `json:"created_at,omitempty"`
}

type Answer struct {
	Mode     string   `json:"mode,omitempty"`
	Answers  []string `json:"answers,omitempty"`
	Feedback string   `json:"feedback,omitempty"`
}

type Source interface {
	Events(ctx context.Context, afterSeq *int64, categories []string, handle func(cmux.Event) error) error
	FeedItems(ctx context.Context) ([]cmux.FeedItem, error)
	ReplyPermission(ctx context.Context, requestID, mode string) error
	ReplyQuestion(ctx context.Context, requestID string, selections []string) error
	ReplyExitPlan(ctx context.Context, requestID, mode, feedback string) error
}

type Tracker struct {
	source Source
	logger *slog.Logger

	mu          sync.Mutex
	pending     []Approval
	subscribers map[chan struct{}]struct{}
}

func NewTracker(source Source, logger *slog.Logger) *Tracker {
	return &Tracker{source: source, logger: logger, subscribers: map[chan struct{}]struct{}{}}
}

func (t *Tracker) Run(ctx context.Context) {
	go t.sweep(ctx)
	var afterSeq *int64
	backoff := time.Second
	for ctx.Err() == nil {
		err := t.source.Events(ctx, afterSeq, []string{"feed"}, func(event cmux.Event) error {
			backoff = time.Second
			if event.Type != "event" {
				return nil
			}
			seq := event.Seq
			afterSeq = &seq
			t.handle(ctx, event)
			return nil
		})
		if ctx.Err() != nil {
			return
		}
		t.logger.Warn("cmux feed stream ended; reconnecting", "error", err, "retry_in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 30*time.Second)
		t.reconcile(ctx)
	}
}

type feedPayload struct {
	HookEventName string `json:"hook_event_name"`
	RequestID     string `json:"_opencode_request_id"`
	Source        string `json:"_source"`
	WorkspaceID   string `json:"workspace_id"`
	SurfaceID     string `json:"surface_id"`
}

func (t *Tracker) handle(ctx context.Context, event cmux.Event) {
	var payload feedPayload
	_ = json.Unmarshal(event.Payload, &payload)
	if payload.WorkspaceID == "" && event.WorkspaceID != nil {
		payload.WorkspaceID = *event.WorkspaceID
	}
	if payload.SurfaceID == "" && event.SurfaceID != nil {
		payload.SurfaceID = *event.SurfaceID
	}
	switch event.Name {
	case "feed.item.received":
		if actionableHooks[payload.HookEventName] && payload.RequestID != "" {
			t.track(ctx, payload)
		} else if t.hasPendingOn(payload.SurfaceID) {
			t.reconcile(ctx)
		}
	case "feed.item.completed":
		t.remove(payload.RequestID)
	}
}

func (t *Tracker) track(ctx context.Context, payload feedPayload) {
	for attempt := 0; attempt < lookupAttempts; attempt++ {
		items, err := t.feedItems(ctx)
		if err != nil {
			t.logger.Warn("could not read the cmux feed", "error", err)
			return
		}
		for _, item := range items {
			if item.RequestID != payload.RequestID {
				continue
			}
			if item.Status != feedStatusPending {
				return
			}
			if approval, ok := approvalFrom(item, payload); ok {
				t.add(approval)
			}
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(lookupDelay):
		}
	}
}

func approvalFrom(item cmux.FeedItem, payload feedPayload) (Approval, bool) {
	approval := Approval{
		ID:          item.RequestID,
		Agent:       firstNonEmpty(item.Source, payload.Source),
		WorkspaceID: payload.WorkspaceID,
		SurfaceID:   payload.SurfaceID,
		Cwd:         item.Cwd,
		CreatedAt:   item.CreatedAt,
	}
	switch item.Kind {
	case feedKindPermission:
		approval.Kind = KindPermission
		approval.Tool = firstNonEmpty(item.ToolName, item.Title)
		approval.Detail, approval.Description = describeToolInput(item.ToolInput)
	case feedKindQuestion:
		approval.Kind = KindQuestion
		for _, question := range item.Questions {
			converted := Question{Header: question.Header, Prompt: question.Prompt, MultiSelect: question.MultiSelect}
			for _, option := range question.Options {
				converted.Options = append(converted.Options, Option{Label: option.Label, Description: option.Description})
			}
			approval.Questions = append(approval.Questions, converted)
		}
		if len(approval.Questions) == 0 {
			return Approval{}, false
		}
	case feedKindPlan:
		approval.Kind = KindPlan
		approval.Plan = planText(item.Plan)
	default:
		return Approval{}, false
	}
	return approval, true
}

func planText(raw string) string {
	var input struct {
		Plan string `json:"plan"`
	}
	if err := json.Unmarshal([]byte(raw), &input); err == nil && input.Plan != "" {
		return input.Plan
	}
	return raw
}

func describeToolInput(raw string) (detail, description string) {
	var input map[string]any
	if err := json.Unmarshal([]byte(raw), &input); err != nil {
		return truncate(raw), ""
	}
	description, _ = input["description"].(string)
	for _, key := range []string{"command", "file_path", "url", "pattern", "path", "query", "prompt"} {
		if value, ok := input[key].(string); ok && value != "" {
			return truncate(value), description
		}
	}
	delete(input, "description")
	if len(input) == 0 {
		return "", description
	}
	pretty, _ := json.MarshalIndent(input, "", "  ")
	return truncate(string(pretty)), description
}

func truncate(text string) string {
	if len(text) <= maxDetailLength {
		return text
	}
	return strings.ToValidUTF8(text[:maxDetailLength], "") + "…"
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func (t *Tracker) sweep(ctx context.Context) {
	ticker := time.NewTicker(sweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if len(t.Pending()) > 0 {
				t.reconcile(ctx)
			}
		}
	}
}

func (t *Tracker) reconcile(ctx context.Context) {
	if len(t.Pending()) == 0 {
		return
	}
	items, err := t.feedItems(ctx)
	if err != nil {
		t.logger.Warn("could not read the cmux feed", "error", err)
		return
	}
	stillPending := map[string]bool{}
	for _, item := range items {
		if item.Status == feedStatusPending && item.RequestID != "" {
			stillPending[item.RequestID] = true
		}
	}
	t.mu.Lock()
	kept := slices.DeleteFunc(slices.Clone(t.pending), func(approval Approval) bool { return !stillPending[approval.ID] })
	changed := len(kept) != len(t.pending)
	t.pending = kept
	t.mu.Unlock()
	if changed {
		t.notify()
	}
}

func (t *Tracker) feedItems(ctx context.Context) ([]cmux.FeedItem, error) {
	callCtx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	return t.source.FeedItems(callCtx)
}

func (t *Tracker) Answer(ctx context.Context, id string, answer Answer) error {
	approval, ok := t.find(id)
	if !ok {
		return ErrNotPending
	}
	var err error
	switch approval.Kind {
	case KindPermission:
		if !permissionModes[answer.Mode] {
			return errors.New("mode must be once, always or deny")
		}
		err = t.source.ReplyPermission(ctx, id, answer.Mode)
	case KindQuestion:
		if len(answer.Answers) != len(approval.Questions) {
			return errors.New("send one answer per question")
		}
		err = t.source.ReplyQuestion(ctx, id, answer.Answers)
	case KindPlan:
		if !planModes[answer.Mode] {
			return errors.New("mode must be manual, autoAccept or deny")
		}
		if answer.Mode == "autoAccept" && answer.Feedback != "" {
			return errors.New("feedback goes with manual or deny")
		}
		if len(answer.Feedback) > maxFeedbackLength {
			return errors.New("feedback must be at most 4000 bytes")
		}
		err = t.source.ReplyExitPlan(ctx, id, answer.Mode, answer.Feedback)
	}
	if err == nil {
		t.remove(id)
	}
	return err
}

func (t *Tracker) Pending() []Approval {
	t.mu.Lock()
	defer t.mu.Unlock()
	return slices.Clone(t.pending)
}

func (t *Tracker) Subscribe() (<-chan struct{}, func()) {
	t.mu.Lock()
	defer t.mu.Unlock()
	channel := make(chan struct{}, 1)
	t.subscribers[channel] = struct{}{}
	return channel, func() {
		t.mu.Lock()
		defer t.mu.Unlock()
		delete(t.subscribers, channel)
	}
}

func (t *Tracker) find(id string) (Approval, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	index := slices.IndexFunc(t.pending, func(approval Approval) bool { return approval.ID == id })
	if index < 0 {
		return Approval{}, false
	}
	return t.pending[index], true
}

func (t *Tracker) hasPendingOn(surfaceID string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return slices.ContainsFunc(t.pending, func(approval Approval) bool { return approval.SurfaceID == surfaceID })
}

func (t *Tracker) add(approval Approval) {
	t.mu.Lock()
	if slices.ContainsFunc(t.pending, func(existing Approval) bool { return existing.ID == approval.ID }) {
		t.mu.Unlock()
		return
	}
	t.pending = append(t.pending, approval)
	t.mu.Unlock()
	t.notify()
}

func (t *Tracker) remove(id string) {
	if id == "" {
		return
	}
	t.mu.Lock()
	before := len(t.pending)
	t.pending = slices.DeleteFunc(t.pending, func(approval Approval) bool { return approval.ID == id })
	changed := len(t.pending) != before
	t.mu.Unlock()
	if changed {
		t.notify()
	}
}

func (t *Tracker) notify() {
	t.mu.Lock()
	defer t.mu.Unlock()
	for subscriber := range t.subscribers {
		select {
		case subscriber <- struct{}{}:
		default:
		}
	}
}
