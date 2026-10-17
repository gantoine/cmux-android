//go:build e2e

package e2e

import (
	"testing"
)

func notify(t *testing.T, p *phone, ws workspace, title string) string {
	t.Helper()
	mark := p.mark()
	cmuxCLI(t, "notify", "--surface", ws.SurfaceID, "--title", title, "--body", "from the e2e suite", "--desktop", "false")
	created := p.pushAfter(mark, "notification", "the notification "+title, func(push message) bool {
		n := object(push["notification"])
		return n["kind"] == "created" && n["title"] == title
	})
	notification := object(created["notification"])
	if notification["surface_id"] != ws.SurfaceID || notification["workspace_id"] != ws.ID {
		t.Fatalf("the notification points at the wrong terminal: %v", notification)
	}
	return text(notification["id"])
}

func isRead(t *testing.T, p *phone, id string) (read, listed bool) {
	t.Helper()
	listing := p.must("notifications", nil)
	n := find(list(listing["notifications"]), "id", id)
	return flag(n["is_read"]), n != nil
}

func dismissed(t *testing.T, p *phone, mark int, id string) {
	t.Helper()
	p.pushAfter(mark, "notification", "the phone alert for "+id+" to be dismissed", func(push message) bool {
		n := object(push["notification"])
		return n["kind"] == "dismissed" && n["id"] == id
	})
}

func TestNotificationsReachThePhoneAndClearOnBothSides(t *testing.T) {
	p := connect(t)
	ws := scratchWorkspace(t, p)

	first := notify(t, p, ws, uniqueName(t)+"-attach")
	if read, listed := isRead(t, p, first); !listed || read {
		t.Fatalf("the new notification should be listed unread (listed %v, read %v)", listed, read)
	}
	mark := p.mark()
	attach(t, p, ws, ws.SurfaceID)
	dismissed(t, p, mark, first)
	if read, _ := isRead(t, p, first); !read {
		t.Fatal("opening the terminal should mark its notification read on the Mac")
	}

	second := notify(t, p, ws, uniqueName(t)+"-mark")
	mark = p.mark()
	p.must("mark_notifications_read", map[string]any{"notification_id": second})
	dismissed(t, p, mark, second)

	third := notify(t, p, ws, uniqueName(t)+"-clear")
	mark = p.mark()
	cmuxCLI(t, "clear-notifications", "--workspace", ws.ID, "--surface", ws.SurfaceID)
	dismissed(t, p, mark, third)
}
