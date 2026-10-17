package com.gantoine.muxer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gantoine.muxer.model.AgentMessaging
import com.gantoine.muxer.model.AgentNames
import com.gantoine.muxer.notifications.LiveActivities
import com.gantoine.muxer.protocol.AgentMessage
import com.gantoine.muxer.protocol.Retry
import java.time.Instant

@Composable
fun RetryBanner(retry: Retry, modifier: Modifier = Modifier) {
    val now = rememberNow(retry.dueAt)
    val seconds = AgentMessaging.secondsUntil(retry, Instant.ofEpochMilli(now))
    val agent = AgentNames.displayOrDefault(retry.agent)
    val countdown = when {
        seconds == null || seconds == 0L -> "Retrying now"
        else -> "Retrying in ${LiveActivities.countdown(seconds)}"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$agent hit a temporary error", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$countdown · attempt ${retry.attempt}. cmux will type \"continue\" for you.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun AgentMessageReceipts(messages: List<AgentMessage>, waitingOnYou: Boolean, modifier: Modifier = Modifier) {
    val unread = AgentMessaging.unread(messages)
    if (unread.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        unread.forEach { message -> Receipt(message, waitingOnYou) }
    }
}

@Composable
private fun Receipt(message: AgentMessage, waitingOnYou: Boolean) {
    val failed = message.state == AgentMessage.FAILED
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(message.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    val icon = when (message.state) {
                        AgentMessage.QUEUED -> Icons.Filled.Schedule
                        AgentMessage.FAILED -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.DoneAll
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(AgentMessaging.statusLine(message, waitingOnYou), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
