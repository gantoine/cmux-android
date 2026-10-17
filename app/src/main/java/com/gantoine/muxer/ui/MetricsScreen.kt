package com.gantoine.muxer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.isUnavailable
import com.gantoine.muxer.protocol.TopSummary
import com.gantoine.muxer.protocol.TopWorkspace
import com.gantoine.muxer.protocol.WorkspaceInfo
import java.util.Locale
import kotlinx.coroutines.delay

object Metrics {
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
fun MetricsScreen(app: MuxerApplication, macId: String, onBack: () -> Unit) {
    val connection = rememberConnection(app, macId)
    val state by connection.collectOr(ConnectionState.Stopped) { it.state }
    val macName = rememberMacName(app, macId)
    val workspaces by connection.collectOr(null) { it.workspaces }
    var summary by remember { mutableStateOf<TopSummary?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = state is ConnectionState.Connected

    LaunchedEffect(connection, connected, attempt) {
        val target = connection?.takeIf { it.supports(REQUEST) } ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                target.top()
                    .onSuccess {
                        summary = it
                        failure = null
                    }
                    .onFailure { failure = it }
                if (failure?.isUnavailable == true) break
                delay(Metrics.REFRESH_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            CmuxTopBar(title = "Metrics", onBack = onBack)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            RemoteContent(
                value = summary,
                failure = failure,
                state = state,
                requestType = REQUEST,
                icon = Icons.Outlined.Speed,
                what = "report CPU and memory use",
                failedTitle = "Couldn't load metrics",
                loading = if (connected) "Measuring…" else "Waiting for $macName…",
                macName = macName,
                onRetry = {
                    failure = null
                    attempt++
                },
            ) { current ->
                MetricsList(current, state, workspaces.orEmpty().associateBy { it.id })
            }
        }
    }
}

private const val REQUEST = "top"

@Composable
private fun MetricsList(summary: TopSummary, state: ConnectionState, workspaces: Map<String, WorkspaceInfo>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "banner") { ConnectionBanner(state) }
        item(key = "totals") {
            SectionLabel("cmux")
            Column(
                Modifier.groupedRow(groupedShape(0, 1)),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(Metrics.usage(summary.cpuPercent, summary.memoryBytes), style = MaterialTheme.typography.titleMedium)
                Text(
                    buildList {
                        add("${summary.processCount} processes in cmux terminals")
                        if (summary.appMemoryBytes > 0) add("cmux itself ${Metrics.bytes(summary.appMemoryBytes)}")
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
                    usage = Metrics.usage(agent.cpuPercent, agent.memoryBytes),
                    detail = if (agent.processCount == 1) "1 process" else "${agent.processCount} processes",
                    index = index,
                    count = summary.agents.size,
                )
            }
        }
        if (summary.workspaces.isNotEmpty()) {
            item(key = "workspaces-label") { SectionLabel("Workspaces") }
            itemsIndexed(summary.workspaces, key = { _, workspace -> "workspace-${workspace.id}" }) { index, workspace ->
                WorkspaceUsageRow(workspace, workspaces[workspace.id], index, summary.workspaces.size)
            }
        }
    }
}

@Composable
private fun WorkspaceUsageRow(workspace: TopWorkspace, info: WorkspaceInfo?, index: Int, count: Int) {
    UsageRow(
        title = workspace.title.ifBlank { "Workspace" },
        usage = Metrics.usage(workspace.cpuPercent, workspace.memoryBytes),
        detail = null,
        index = index,
        count = count,
        leading = info?.let { { WorkspaceTile(it, size = 28.dp) } },
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
                    Metrics.usage(process.cpuPercent, process.memoryBytes),
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
    leading: (@Composable () -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Row(Modifier.groupedRow(groupedShape(index, count))) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        UsageDetails(title, usage, detail, extra)
    }
}

@Composable
private fun UsageDetails(title: String, usage: String, detail: String?, extra: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
