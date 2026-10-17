package server

import (
	"context"
	"encoding/base64"
	"fmt"

	"github.com/gantoine/muxer/relay/internal/cmux"
)

var errImagesUnsupported = unsupported("receive images")

var imageFormats = map[string]bool{"png": true, "jpeg": true, "jpg": true, "webp": true, "gif": true}

func validImages(images []cmux.ChatImage) error {
	if len(images) == 0 || len(images) > maxImages {
		return invalid(fmt.Sprintf("Send 1 to %d images at a time.", maxImages))
	}
	for _, image := range images {
		if !imageFormats[image.Format] {
			return invalid("Images must be PNG, JPEG, WebP or GIF.")
		}
		decoded, err := base64.StdEncoding.DecodeString(image.Data)
		if err != nil || len(decoded) == 0 {
			return invalid("An image wasn't valid base64 data.")
		}
		if len(decoded) > maxImageBytes {
			return invalid(fmt.Sprintf("Each image can be up to %d MB.", maxImageBytes>>20))
		}
	}
	return nil
}

func (s *session) sendImages(ctx context.Context, current *attachment, text string, images []cmux.ChatImage) error {
	return guard(s.server, cmux.MethodChatSend, errImagesUnsupported, func() error {
		sessionID, _, err := s.server.claudeSession(ctx, current.workspaceID, current.surfaceID)
		if err != nil {
			return err
		}
		if sessionID == "" {
			return &requestError{Code: "no_agent", Message: "Images can only go to Claude, and Claude isn't running in this terminal."}
		}
		return s.server.cmux.SendChat(ctx, sessionID, text, images)
	})
}
