package com.gantoine.muxer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.protocol.MacNotification
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun NotificationsScreen(
    app: MuxerApplication,
    macId: String,
    onBack: () -> Unit,
    onOpenTerminal: (workspaceId: String, surfaceId: String) -> Unit,
) {
    val connections by app.connections.connectionsFlow.collectAsState()
    val connection = connections[macId]
    val state by (connection?.state ?: remember { MutableStateFlow(ConnectionState.Stopped) }).collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<MacNotification>?>(null) }

    suspend fun load() {
        val target = connection ?: return
        target.notifications()
            .onSuccess { notifications = it }
            .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't load the notifications.") }
    }

    LaunchedEffect(connection, state is ConnectionState.Connected) {
        if (state is ConnectionState.Connected) load()
    }

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = "Notifications",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (notifications.orEmpty().any { !it.isRead }) {
                        IconButton(onClick = {
                            scope.launch {
                                connection?.markNotificationsRead(all = true)
                                    ?.onSuccess { load() }
                                    ?.onFailure { snackbar.showSnackbar(it.message ?: "Couldn't mark them read.") }
                            }
                        }) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Mark all read")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = notifications
            when {
                list == null -> Column {
                    ConnectionBanner(state)
                    LoadingState("Fetching notifications…")
                }
                list.isEmpty() -> Column {
                    ConnectionBanner(state)
                    EmptyState(
                        icon = Icons.Outlined.NotificationsNone,
                        title = "No notifications",
                        body = "Notifications from cmux show up here until they're cleared on the computer.",
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "banner") { ConnectionBanner(state) }
                    itemsIndexed(list, key = { _, notification -> notification.id }) { index, notification ->
                        NotificationRow(
                            notification,
                            index,
                            list.size,
                            onOpen = {
                                val workspaceId = notification.workspaceId
                                val surfaceId = notification.surfaceId
                                if (workspaceId != null && surfaceId != null) onOpenTerminal(workspaceId, surfaceId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: MacNotification, index: Int, count: Int, onOpen: () -> Unit) {
    val unread = !notification.isRead
    val title = notification.title.ifBlank { notification.tabTitle.ifBlank { "cmux" } }
    val detail = listOf(notification.subtitle, notification.body).filter { it.isNotBlank() }.joinToString(" · ")
    val time = notification.createdAt
        ?.let { runCatching { Instant.parse(it).epochSecond.toDouble() }.getOrNull() }
        ?.let { WorkspaceListing.time(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(groupedShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (time != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notification.tabTitle.isNotBlank() && notification.tabTitle != title) {
                Text(
                    notification.tabTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (unread) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}
