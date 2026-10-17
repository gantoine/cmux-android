package server

import (
	"context"

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
		return s.cmux.MarkSurfaceNotificationsRead(ctx, request.SurfaceID)
	default:
		return invalid("mark_notifications_read needs notification_id, surface_id or all.")
	}
}

func (s *session) markAttachedRead(ctx context.Context, surfaceID string) {
	callCtx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	if err := s.server.cmux.MarkSurfaceNotificationsRead(callCtx, surfaceID); err != nil && ctx.Err() == nil {
		s.server.logger.Warn("could not mark the terminal's notifications read", "error", err)
	}
}
