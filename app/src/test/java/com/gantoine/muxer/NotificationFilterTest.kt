package com.gantoine.muxer

import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.notifications.NotificationFilter
import com.gantoine.muxer.notifications.NotificationFilter.Kind
import com.gantoine.muxer.protocol.RelayNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private fun notification(subtitle: String?, body: String) =
        RelayNotification(seq = 1, kind = "created", id = "n1", title = "Claude Code", subtitle = subtitle, body = body)

    private val waiting = notification("Waiting", "Claude is waiting for your input")
    private val waitingWithoutSubtitle = notification("", "Claude is waiting for your input")
    private val completed = notification("Completed in muxer", "I ran the command.")
    private val permission = notification("Permission", "Claude needs your permission to use Bash")

    @Test
    fun classifiesCmuxClaudeNotifications() {
        assertEquals(Kind.Waiting, NotificationFilter.kind(waiting))
        assertEquals(Kind.Waiting, NotificationFilter.kind(waitingWithoutSubtitle))
        assertEquals(Kind.Completed, NotificationFilter.kind(completed))
        assertEquals(Kind.Completed, NotificationFilter.kind(notification("Completed", "Done.")))
        assertEquals(Kind.Other, NotificationFilter.kind(permission))
        assertEquals(Kind.Other, NotificationFilter.kind(notification("Error", "Claude hit an error")))
    }

    @Test
    fun dropsWaitingAndHonoursTheCompletionSetting() {
        val mac = Mac(id = "m", name = "M4", url = "http://m", encryptedToken = "t")
        assertFalse(NotificationFilter.shouldShow(mac, waiting))
        assertFalse(NotificationFilter.shouldShow(mac, waitingWithoutSubtitle))
        assertTrue(NotificationFilter.shouldShow(mac, completed))
        assertTrue(NotificationFilter.shouldShow(mac, permission))
        assertFalse(NotificationFilter.shouldShow(mac.copy(completionNotifications = false), completed))
        assertTrue(NotificationFilter.shouldShow(mac.copy(completionNotifications = false), permission))
    }
}
