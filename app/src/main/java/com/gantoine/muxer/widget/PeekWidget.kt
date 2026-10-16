package com.gantoine.muxer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.withTimeoutOrNull
import com.gantoine.muxer.net.RelayException
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.notifications.Notifier
import com.gantoine.muxer.protocol.WorkspaceInfo
import kotlinx.coroutines.flow.first

class PeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MuxerApplication
        val initial = Widgets.inputs(app).first()
        provideContent {
            val inputs by remember { Widgets.inputs(app) }.collectAsState(initial)
            val state = currentState<Preferences>()
            val choice = PeekChoice.from(state)
            MuxerWidgetTheme { PeekContent(inputs, choice, PeekOutput.from(state)) }
        }
    }

    companion object {
        const val LINES = 40
        private const val PEEK_TIMEOUT_MS = 8_000L

        suspend fun refreshAll(context: Context) {
            val app = context.applicationContext as MuxerApplication
            val manager = GlanceAppWidgetManager(context)
            val snapshots = app.snapshots.current()
            manager.getGlanceIds(PeekWidget::class.java).forEach { glanceId ->
                val choice = PeekChoice.from(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)) ?: return@forEach
                val workspace = snapshots[choice.macId]?.workspaces?.firstOrNull { it.id == choice.workspaceId } ?: return@forEach
                val surfaceId = WidgetData.agentSurface(workspace) ?: return@forEach
                val connection = app.connections.awaitConnected(choice.macId, PEEK_TIMEOUT_MS) ?: return@forEach
                val peeked = withTimeoutOrNull(PEEK_TIMEOUT_MS) { connection.peek(choice.workspaceId, surfaceId, LINES) } ?: return@forEach
                updateAppWidgetState(context, glanceId) { state ->
                    peeked.onSuccess { lines ->
                        state[PeekChoice.OUTPUT] = lines.joinToString("\n")
                        state[PeekChoice.PEEKED_AT] = System.currentTimeMillis()
                        state.remove(PeekChoice.ERROR)
                    }.onFailure { error ->
                        state[PeekChoice.ERROR] = if (error is RelayException && error.code == "unknown_type") UNSUPPORTED else error.message.orEmpty()
                    }
                }
            }
        }

        suspend fun choose(context: Context, appWidgetId: Int, macId: String, workspaceId: String) {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, glanceId) { state ->
                state[PeekChoice.MAC_ID] = macId
                state[PeekChoice.WORKSPACE_ID] = workspaceId
                state.remove(PeekChoice.OUTPUT)
                state.remove(PeekChoice.PEEKED_AT)
                state.remove(PeekChoice.ERROR)
            }
            PeekWidget().update(context, glanceId)
            refreshAll(context)
            PeekWidget().update(context, glanceId)
        }
    }
}

class PeekWidgetReceiver : MuxerWidgetReceiver() {
    override val glanceAppWidget = PeekWidget()
}

private const val UNSUPPORTED = "unsupported"

private data class PeekOutput(val text: String?, val peekedAt: Long?, val error: String?) {
    companion object {
        fun from(state: Preferences) = PeekOutput(state[PeekChoice.OUTPUT], state[PeekChoice.PEEKED_AT], state[PeekChoice.ERROR])
    }
}

private data class PeekChoice(val macId: String, val workspaceId: String) {
    companion object {
        val MAC_ID = stringPreferencesKey("mac_id")
        val WORKSPACE_ID = stringPreferencesKey("workspace_id")
        val OUTPUT = stringPreferencesKey("output")
        val PEEKED_AT = longPreferencesKey("peeked_at")
        val ERROR = stringPreferencesKey("error")

        fun from(state: Preferences): PeekChoice? {
            val macId = state[MAC_ID] ?: return null
            val workspaceId = state[WORKSPACE_ID] ?: return null
            return PeekChoice(macId, workspaceId)
        }
    }
}

private const val HEADER_DP = 52f
private const val LINE_DP = 15f
private const val HORIZONTAL_PADDING_DP = 28f
private const val CHAR_DP = 7f

@Composable
private fun PeekContent(inputs: WidgetInputs, choice: PeekChoice?, output: PeekOutput) {
    val snapshot = choice?.let { inputs.paired.firstOrNull { it.macId == choice.macId } }
    val workspace = snapshot?.workspaces?.firstOrNull { it.id == choice.workspaceId }
    WidgetSurface {
        when {
            choice == null -> PeekMessage("Tap to choose a workspace", configure())
            snapshot == null || workspace == null -> {
                WidgetHeader("Terminal", snapshot?.let { WidgetData.asOf(listOf(it), System.currentTimeMillis()) })
                PeekMessage("This workspace isn't on the computer anymore. Tap to choose another.", configure())
            }
            else -> PeekTerminal(snapshot, workspace, output)
        }
    }
}

@Composable
private fun PeekTerminal(snapshot: MacSnapshot, workspace: WorkspaceInfo, output: PeekOutput) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val stale = WidgetData.isStale(snapshot, now) || output.peekedAt?.let { now - it > WidgetData.STALE_AFTER_MS } ?: true
    val size = LocalSize.current
    val lineCount = WidgetData.previewLineCount(size.height.value, HEADER_DP, LINE_DP)
    val columnCount = WidgetData.previewColumnCount(size.width.value, HORIZONTAL_PADDING_DP, CHAR_DP)
    val lines = WidgetData.previewLines(output.text, lineCount, columnCount)
    val open = WidgetData.agentSurface(workspace)?.let { Notifier.openTerminal(context, snapshot.macId, workspace.id, it) }
        ?: Widgets.openApp(context)
    WidgetHeader(workspace.title.ifBlank { snapshot.macName }, WidgetData.asOf(listOf(snapshot), now))
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp).clickable(actionStartActivity(open))) {
        if (lines.isEmpty()) {
            Text(WidgetData.peekPlaceholder(output.text, output.error, snapshot.connected), style = secondaryText(12))
        } else {
            Text(
                lines.joinToString("\n"),
                maxLines = lineCount,
                style = TextStyle(
                    color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PeekMessage(text: String, intent: Intent) {
    Box(
        GlanceModifier.fillMaxSize().padding(16.dp).clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = secondaryText(14).copy(textAlign = TextAlign.Center))
    }
}

@Composable
private fun configure(): Intent {
    val context = LocalContext.current
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
    return Intent(context, PeekConfigActivity::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
