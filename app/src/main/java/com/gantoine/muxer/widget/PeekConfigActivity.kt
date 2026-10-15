package com.gantoine.muxer.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.terminal.parseHexColor
import com.gantoine.muxer.ui.CmuxTheme
import com.gantoine.muxer.ui.CmuxTopBar
import com.gantoine.muxer.ui.EmptyState
import com.gantoine.muxer.ui.SectionLabel
import com.gantoine.muxer.ui.WorkspaceListing
import kotlinx.coroutines.launch

class PeekConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as MuxerApplication
        setContent {
            CmuxTheme {
                val inputs by remember { Widgets.inputs(app) }.collectAsState(initial = null)
                PeekPicker(inputs) { macId, workspaceId -> choose(appWidgetId, macId, workspaceId) }
            }
        }
    }

    private fun choose(appWidgetId: Int, macId: String, workspaceId: String) {
        lifecycleScope.launch {
            PeekWidget.choose(applicationContext, appWidgetId, macId, workspaceId)
            setResult(RESULT_OK, resultIntent(appWidgetId))
            finish()
        }
    }

    private fun resultIntent(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun PeekPicker(inputs: WidgetInputs?, onChoose: (macId: String, workspaceId: String) -> Unit) {
    Scaffold(topBar = { CmuxTopBar("Choose a workspace") }) { padding ->
        val snapshots = inputs?.paired.orEmpty().filter { it.workspaces.isNotEmpty() }
        if (inputs != null && snapshots.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Terminal,
                title = "No workspaces yet",
                body = "Open Muxer while your computer is connected, then add this widget again.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            snapshots.forEach { snapshot ->
                item(key = "computer:${snapshot.macId}") { SectionLabel(snapshot.macName) }
                items(snapshot.workspaces, key = { "${snapshot.macId}:${it.id}" }) { workspace ->
                    WorkspaceChoice(snapshot, workspace) { onChoose(snapshot.macId, workspace.id) }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceChoice(snapshot: MacSnapshot, workspace: WorkspaceInfo, onClick: () -> Unit) {
    val accent = parseHexColor(WorkspaceListing.color(workspace)) ?: MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        supportingContent = {
            Text(
                WorkspaceListing.subtitle(workspace).lines().lastOrNull { it.isNotBlank() } ?: snapshot.macName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Box(Modifier.size(12.dp).background(accent, CircleShape)) },
    ) {
        Text(workspace.title.ifBlank { "Workspace" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
