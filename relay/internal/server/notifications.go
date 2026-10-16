package server

import (
	"context"
	"errors"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

type markReadRequest struct {
	NotificationID string `json:"notification_id"`
	SurfaceID      string `json:"surface_id"`
	All            bool   `json:"all"`
}

func (s *Server) notifications(ctx context.Context) (map[string][]cmux.Notification, error) {
	list, err := s.cmux.Notifications(ctx)
	if list == nil {
		list = []cmux.Notification{}
	}
	return map[string][]cmux.Notification{"notifications": list}, err
}

func (s *Server) markRead(ctx context.Context, request markReadRequest) error {
	switch {
	case request.All:
		return s.cmux.MarkAllNotificationsRead(ctx)
	case request.NotificationID != "":
		return s.cmux.MarkNotificationRead(ctx, request.NotificationID)
	case request.SurfaceID != "":
		return s.markTerminalRead(ctx, request.SurfaceID)
	default:
		return &requestError{Code: "invalid_params", Message: "mark_notifications_read needs notification_id, surface_id or all."}
	}
}

func (s *Server) markTerminalRead(ctx context.Context, surfaceID string) error {
	list, err := s.cmux.Notifications(ctx)
	if err != nil {
		return err
	}
	var failures []error
	for _, notification := range list {
		if notification.IsRead || notification.SurfaceID != surfaceID {
			continue
		}
		if err := s.cmux.MarkNotificationRead(ctx, notification.ID); err != nil && cmux.ErrorCode(err) != "not_found" {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

func (s *session) markAttachedRead(ctx context.Context, surfaceID string) {
	callCtx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	if err := s.server.markTerminalRead(callCtx, surfaceID); err != nil && ctx.Err() == nil {
		s.server.logger.Warn("could not mark the terminal's notifications read", "error", err)
	}
}
