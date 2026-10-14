package com.gantoine.cmux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gantoine.cmux.CmuxApplication
import com.gantoine.cmux.net.ConnectionState
import com.gantoine.cmux.net.RelayConnection
import com.gantoine.cmux.net.RelayException
import com.gantoine.cmux.protocol.CreatedWorkspace
import com.gantoine.cmux.protocol.RelayJson
import com.gantoine.cmux.protocol.TerminalInfo
import com.gantoine.cmux.protocol.WorkspaceInfo
import com.gantoine.cmux.terminal.parseHexColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private sealed interface WorkspaceDialog {
    data object Create : WorkspaceDialog
    data class Actions(val workspace: WorkspaceInfo) : WorkspaceDialog
    data class Rename(val workspace: WorkspaceInfo) : WorkspaceDialog
    data class Close(val workspace: WorkspaceInfo, val force: Boolean) : WorkspaceDialog
    data class PickTerminal(val workspace: WorkspaceInfo) : WorkspaceDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkspacesScreen(
    app: CmuxApplication,
    macId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (workspaceId: String, surfaceId: String) -> Unit,
) {
    val connections by app.connections.connectionsFlow.collectAsState()
    val macs by app.store.macs.collectAsState(initial = emptyList())
    val connection = connections[macId]
    val macName = macs.firstOrNull { it.id == macId }?.name ?: "Computer"
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<WorkspaceDialog?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WorkspaceFilter.All) }
    val scope = rememberCoroutineScope()

    fun run(action: suspend RelayConnection.() -> Result<*>, onSuccess: () -> Unit = {}) {
        val target = connection ?: return
        scope.launch {
            target.action()
                .onSuccess { onSuccess() }
                .onFailure { failure ->
                    val closing = dialog as? WorkspaceDialog.Close
                    if (failure is RelayException && failure.code == "confirmation_required" && closing != null) {
                        dialog = closing.copy(force = true)
                    } else {
                        snackbar.showSnackbar(failure.message ?: "That did not work.")
                    }
                }
        }
    }

    fun open(workspace: WorkspaceInfo) {
        when (workspace.terminals.size) {
            0 -> run({
                request("create_terminal", buildJsonObject { put("workspace_id", workspace.id) }).onSuccess { data ->
                    data?.jsonObject?.get("surface_id")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { onOpenTerminal(workspace.id, it) }
                }
            })
            1 -> onOpenTerminal(workspace.id, workspace.terminals.first().id)
            else -> dialog = WorkspaceDialog.PickTerminal(workspace)
        }
    }

    val state by (connection?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.Stopped) }).collectAsState()
    val workspaces by (connection?.workspaces ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = macName,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings for $macName")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val all = workspaces
            when {
                connection == null -> EmptyState(
                    icon = Icons.Filled.Terminal,
                    title = "Not paired anymore",
                    body = "This phone no longer has access to $macName. Remove it and pair again.",
                )
                state == ConnectionState.Unauthorized && all == null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = "Not paired anymore",
                    body = "$macName no longer accepts this phone. Remove it from this phone, then pair it again.",
                ) {
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
                all == null -> Column {
                    ConnectionBanner(state)
                    LoadingState(if (state is ConnectionState.Connected) "Fetching workspaces…" else "Waiting for $macName…")
                }
                all.isEmpty() -> Column {
                    ConnectionBanner(state)
                    EmptyState(
                        icon = Icons.Filled.Terminal,
                        title = "No workspaces yet",
                        body = "Create one here and it opens in cmux on $macName.",
                    ) {
                        Button(onClick = { dialog = WorkspaceDialog.Create }) {
                            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New workspace")
                        }
                    }
                }
                else -> {
                    val list = all.filter { WorkspaceListing.passes(it, filter) && WorkspaceListing.matches(it, query) }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "banner") {
                            AnimatedVisibility(visible = state !is ConnectionState.Connected) { ConnectionBanner(state) }
                        }
                        item(key = "filters") { FilterRow(all, filter, onSelect = { filter = it }) }
                        if (list.isEmpty()) {
                            item(key = "empty") {
                                FullWidthMessage(
                                    if (query.isNotBlank()) "No workspaces match \"${query.trim()}\"." else "Nothing here right now.",
                                    Modifier.padding(top = 48.dp),
                                )
                            }
                        }
                        itemsIndexed(list, key = { _, workspace -> workspace.id }) { index, workspace ->
                            WorkspaceRow(
                                workspace = workspace,
                                shape = groupedShape(index, list.size),
                                onOpen = { open(workspace) },
                                onLongPress = { dialog = WorkspaceDialog.Actions(workspace) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            if (connection != null && all != null) {
                SearchDock(
                    query = query,
                    onQueryChange = { query = it },
                    onCreate = { dialog = WorkspaceDialog.Create },
                    modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
                )
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        WorkspaceDialog.Create -> TextInputDialog(
            title = "New workspace",
            fields = listOf("Name" to "", "Folder on the computer (optional)" to ""),
            confirm = "Create",
            onDismiss = { dialog = null },
        ) { (title, cwd) ->
            dialog = null
            run({
                request("create_workspace", buildJsonObject {
                    put("title", title)
                    put("cwd", cwd)
                }).onSuccess { data ->
                    val created = data?.let { RelayJson.decodeFromJsonElement(CreatedWorkspace.serializer(), it) }
                    if (created != null && created.surfaceId.isNotEmpty()) onOpenTerminal(created.workspaceId, created.surfaceId)
                }
            })
        }
        is WorkspaceDialog.Actions -> ActionSheet(
            onDismiss = { dialog = null },
            header = { WorkspaceHeader(current.workspace) },
        ) {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { dialog = null; open(current.workspace) }
            SheetAction(Icons.Filled.Add, "New terminal") {
                dialog = null
                run({ request("create_terminal", buildJsonObject { put("workspace_id", current.workspace.id) }) })
            }
            SheetAction(Icons.Filled.DriveFileRenameOutline, "Rename") { dialog = WorkspaceDialog.Rename(current.workspace) }
            SheetAction(Icons.Filled.Close, "Close workspace", destructive = true) {
                dialog = WorkspaceDialog.Close(current.workspace, force = false)
            }
        }
        is WorkspaceDialog.Rename -> TextInputDialog(
            title = "Rename workspace",
            fields = listOf("Name" to current.workspace.title),
            confirm = "Rename",
            onDismiss = { dialog = null },
        ) { (title) ->
            dialog = null
            run({
                request("rename_workspace", buildJsonObject {
                    put("workspace_id", current.workspace.id)
                    put("title", title)
                })
            })
        }
        is WorkspaceDialog.Close -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Close ${current.workspace.title.ifBlank { "workspace" }}?") },
            text = {
                Text(
                    if (current.force) {
                        "Something is still running in it. Closing stops it on your computer."
                    } else {
                        "This closes the workspace and its terminals on your computer."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    run({
                        request("close_workspace", buildJsonObject {
                            put("workspace_id", current.workspace.id)
                            put("force", current.force)
                        })
                    }, onSuccess = { dialog = null })
                }) { Text(if (current.force) "Close anyway" else "Close") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is WorkspaceDialog.PickTerminal -> ActionSheet(onDismiss = { dialog = null }) {
            current.workspace.terminals.forEach { terminal ->
                TerminalChoice(terminal) {
                    dialog = null
                    onOpenTerminal(current.workspace.id, terminal.id)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(all: List<WorkspaceInfo>, selected: WorkspaceFilter, onSelect: (WorkspaceFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceFilter.entries.forEach { filter ->
            val count = WorkspaceListing.count(all, filter)
            if (filter == WorkspaceFilter.All || count > 0 || filter == selected) {
                val active = filter == selected
                FilterChip(
                    selected = active,
                    onClick = { onSelect(filter) },
                    label = { Text("${filter.label} · $count") },
                    leadingIcon = if (active) {
                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkspaceRow(
    workspace: WorkspaceInfo,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val status = WorkspaceListing.agentStatus(workspace)
    val unread = workspace.unreadCount > 0 || workspace.hasUnread
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceTile(workspace)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (workspace.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    workspace.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                WorkspaceListing.subtitle(workspace),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Spacer(Modifier.height(2.dp))
                StatusPill(status)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkspaceListing.time(workspace.lastActivityAt ?: workspace.previewAt)?.let { time ->
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                workspace.unreadCount > 0 -> Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text("${workspace.unreadCount}", modifier = Modifier.padding(horizontal = 2.dp)) }
                workspace.hasUnread -> Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

@Composable
private fun StatusPill(status: AgentStatus) {
    val (container, content) = when (status) {
        is AgentStatus.NeedsYou -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        is AgentStatus.Working -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        is AgentStatus.Other -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(status.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun WorkspaceTile(workspace: WorkspaceInfo, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val accent = parseHexColor(WorkspaceListing.color(workspace)) ?: MaterialTheme.colorScheme.primary
    val brush = Brush.linearGradient(listOf(lerp(accent, Color.White, 0.22f), accent, lerp(accent, Color.Black, 0.28f)))
    Box(
        Modifier.size(size).background(brush, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (WorkspaceListing.isAgent(workspace)) Icons.Filled.AutoAwesome else Icons.Filled.Terminal,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
private fun WorkspaceHeader(workspace: WorkspaceInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WorkspaceTile(workspace, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(workspace.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                workspace.currentDirectory?.let(WorkspaceListing::shortPath) ?: WorkspaceListing.subtitle(workspace),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchDock(query: String, onQueryChange: (String) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    1f to MaterialTheme.colorScheme.background,
                ),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "Search workspaces",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            }
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "New workspace")
            }
        }
    }
}

@Composable
private fun TerminalChoice(terminal: TerminalInfo, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(terminal.title.ifBlank { "Terminal" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val title = terminal.title.ifBlank { "Terminal" }
            val path = terminal.currentDirectory?.let(WorkspaceListing::shortPath)?.takeIf { it != title }
            listOfNotNull(terminal.agentState, path)
                .takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Icon(
                if (terminal.agentState != null || terminal.agentSource != null) Icons.Filled.AutoAwesome else Icons.Filled.Terminal,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    fields: List<Pair<String, String>>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var values by remember { mutableStateOf(fields.map { it.second }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.forEachIndexed { index, (label, _) ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { changed -> values = values.toMutableList().also { it[index] = changed } },
                        label = { Text(label) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.map { it.trim() }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
