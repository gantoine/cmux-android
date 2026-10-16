package com.gantoine.muxer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.RelayException
import com.gantoine.muxer.protocol.TopSummary
import com.gantoine.muxer.protocol.TopWorkspace
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object Activity {
    const val REFRESH_MS = 3_000L

    fun bytes(count: Long, locale: Locale = Locale.getDefault()): String {
        val units = listOf("KB", "MB", "GB", "TB")
        var value = count / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100 || unit == 0) String.format(locale, "%.0f %s", value, units[unit])
        else String.format(locale, "%.1f %s", value, units[unit])
    }

    fun cpu(percent: Double, locale: Locale = Locale.getDefault()): String = when {
        percent <= 0.0 -> "0%"
        percent < 1.0 -> "<1%"
        else -> String.format(locale, "%.0f%%", percent)
    }

    fun usage(cpuPercent: Double, memoryBytes: Long): String = "${cpu(cpuPercent)} CPU · ${bytes(memoryBytes)}"
}

@Composable
fun ActivityScreen(app: MuxerApplication, macId: String, onBack: () -> Unit) {
    val connections by app.connections.connectionsFlow.collectAsState()
    val connection = connections[macId]
    val state by (connection?.state ?: remember { MutableStateFlow(ConnectionState.Stopped) }).collectAsState()
    val macs by app.store.macs.collectAsState(initial = emptyList())
    val macName = macs.firstOrNull { it.id == macId }?.name ?: "The computer"
    var summary by remember { mutableStateOf<TopSummary?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = state is ConnectionState.Connected

    LaunchedEffect(connection, connected, attempt) {
        val target = connection ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                target.top()
                    .onSuccess {
                        summary = it
                        failure = null
                    }
                    .onFailure { failure = it }
                if ((failure as? RelayException)?.code in setOf("unknown_type", "unsupported")) break
                delay(Activity.REFRESH_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = "Activity",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = summary
            val error = failure
            when {
                current == null && error is RelayException && error.code == "unknown_type" -> EmptyState(
                    icon = Icons.Outlined.Speed,
                    title = "Update the relay",
                    body = "$macName's relay is too old to report CPU and memory use. Update it to see them here.",
                )
                current == null && error is RelayException && error.code == "unsupported" -> EmptyState(
                    icon = Icons.Outlined.Speed,
                    title = "Update cmux",
                    body = error.message ?: "This version of cmux can't report CPU and memory use.",
                )
                current == null && error != null -> Column {
                    ConnectionBanner(state)
                    EmptyState(
                        icon = Icons.Outlined.Speed,
                        title = "Couldn't load activity",
                        body = error.message ?: "$macName didn't answer.",
                    ) {
                        Button(onClick = {
                            failure = null
                            attempt++
                        }) { Text("Try again") }
                    }
                }
                current == null -> Column {
                    ConnectionBanner(state)
                    LoadingState(if (connected) "Measuring…" else "Waiting for $macName…")
                }
                else -> ActivityList(current, state)
            }
        }
    }
}

@Composable
private fun ActivityList(summary: TopSummary, state: ConnectionState) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "banner") { ConnectionBanner(state) }
        item(key = "totals") {
            SectionLabel("cmux")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(groupedShape(0, 1))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(Activity.usage(summary.cpuPercent, summary.memoryBytes), style = MaterialTheme.typography.titleMedium)
                Text(
                    buildList {
                        add("${summary.processCount} processes in cmux terminals")
                        if (summary.appMemoryBytes > 0) add("cmux itself ${Activity.bytes(summary.appMemoryBytes)}")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (summary.agents.isNotEmpty()) {
            item(key = "agents-label") { SectionLabel("Agents") }
            itemsIndexed(summary.agents, key = { _, agent -> "agent-${agent.name}" }) { index, agent ->
                UsageRow(
                    title = agent.name,
                    usage = Activity.usage(agent.cpuPercent, agent.memoryBytes),
                    detail = if (agent.processCount == 1) "1 process" else "${agent.processCount} processes",
                    index = index,
                    count = summary.agents.size,
                )
            }
        }
        if (summary.workspaces.isNotEmpty()) {
            item(key = "workspaces-label") { SectionLabel("Workspaces") }
            itemsIndexed(summary.workspaces, key = { _, workspace -> "workspace-${workspace.id}" }) { index, workspace ->
                WorkspaceUsageRow(workspace, index, summary.workspaces.size)
            }
        }
        item(key = "footer") {
            Text(
                "Updates every ${Activity.REFRESH_MS / 1000} seconds while this page is open.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceUsageRow(workspace: TopWorkspace, index: Int, count: Int) {
    UsageRow(
        title = workspace.title.ifBlank { "Workspace" },
        usage = Activity.usage(workspace.cpuPercent, workspace.memoryBytes),
        detail = null,
        index = index,
        count = count,
    ) {
        workspace.processes.forEach { process ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    process.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    Activity.usage(process.cpuPercent, process.memoryBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageRow(
    title: String,
    usage: String,
    detail: String?,
    index: Int,
    count: Int,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(groupedShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(usage, style = MaterialTheme.typography.labelLarge)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        extra()
    }
}
