package com.gantoine.muxer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.net.ConnectionState
import com.gantoine.muxer.net.RelayConnection
import com.gantoine.muxer.net.RelayException
import com.gantoine.muxer.protocol.CreatedWorkspace
import com.gantoine.muxer.protocol.RelayJson
import com.gantoine.muxer.protocol.TerminalInfo
import com.gantoine.muxer.protocol.WorkspaceGroupInfo
import com.gantoine.muxer.protocol.WorkspaceInfo
import com.gantoine.muxer.terminal.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private sealed interface WorkspaceLine {
    val key: String

    data class Plain(val workspace: WorkspaceInfo) : WorkspaceLine {
        override val key get() = workspace.id
    }

    data class Header(val entry: WorkspaceEntry.Group, val collapsed: Boolean) : WorkspaceLine {
        override val key get() = entry.key
    }

    data class Anchor(val group: WorkspaceGroupInfo, val workspace: WorkspaceInfo) : WorkspaceLine {
        override val key get() = workspace.id
    }
}

private fun cards(entries: List<WorkspaceEntry>, collapsed: (WorkspaceGroupInfo) -> Boolean): List<List<WorkspaceLine>> {
    val cards = mutableListOf<MutableList<WorkspaceLine>>()
    entries.forEach { entry ->
        when (entry) {
            is WorkspaceEntry.Single -> {
                val open = cards.lastOrNull()?.takeIf { it.first() is WorkspaceLine.Plain }
                if (open != null) open += WorkspaceLine.Plain(entry.workspace) else cards += mutableListOf<WorkspaceLine>(WorkspaceLine.Plain(entry.workspace))
            }
            is WorkspaceEntry.Group -> {
                val folded = collapsed(entry.group)
                val card = mutableListOf<WorkspaceLine>(WorkspaceLine.Header(entry, folded))
                if (!folded) {
                    if (entry.showAnchor && entry.anchor != null) card += WorkspaceLine.Anchor(entry.group, entry.anchor)
                    entry.members.forEach { card += WorkspaceLine.Plain(it) }
                }
                cards += card
            }
        }
    }
    return cards
}

private sealed interface WorkspaceDialog {
    data object Create : WorkspaceDialog
    data class Actions(val workspace: WorkspaceInfo, val group: WorkspaceGroupInfo? = null) : WorkspaceDialog
    data class Rename(val workspace: WorkspaceInfo) : WorkspaceDialog
    data class Close(val workspace: WorkspaceInfo, val force: Boolean) : WorkspaceDialog
    data class PickTerminal(val workspace: WorkspaceInfo) : WorkspaceDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkspacesScreen(
    app: MuxerApplication,
    macId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
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
    var toggledGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canPinShortcuts = remember { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }

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
    val groups by (connection?.groups ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = macName,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Outlined.Notifications, contentDescription = "Notifications from $macName")
                    }
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
                    val narrowed = filter != WorkspaceFilter.All || query.isNotBlank()
                    val cards = remember(all, groups, filter, query, toggledGroups) {
                        cards(WorkspaceListing.entries(all, groups, filter, query)) { group ->
                            !narrowed && group.isCollapsed != (group.id in toggledGroups)
                        }
                    }
                    fun toggle(group: WorkspaceGroupInfo) {
                        toggledGroups = if (group.id in toggledGroups) toggledGroups - group.id else toggledGroups + group.id
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "banner") {
                            AnimatedVisibility(visible = state !is ConnectionState.Connected) { ConnectionBanner(state) }
                        }
                        item(key = "filters") { FilterRow(all, groups, filter, onSelect = { filter = it }) }
                        if (cards.isEmpty()) {
                            item(key = "empty") {
                                FullWidthMessage(
                                    if (query.isNotBlank()) "No workspaces match \"${query.trim()}\"." else "Nothing here right now.",
                                    Modifier.padding(top = 48.dp),
                                )
                            }
                        }
                        cards.forEachIndexed { cardIndex, card ->
                            itemsIndexed(card, key = { _, line -> line.key }) { index, line ->
                                val shape = groupedShape(index, card.size)
                                val modifier = Modifier.animateItem().then(
                                    if (index == 0 && cardIndex > 0) Modifier.padding(top = 12.dp) else Modifier,
                                )
                                when (line) {
                                    is WorkspaceLine.Plain -> WorkspaceRow(
                                        workspace = line.workspace,
                                        shape = shape,
                                        onOpen = { open(line.workspace) },
                                        onLongPress = { dialog = WorkspaceDialog.Actions(line.workspace) },
                                        modifier = modifier,
                                    )
                                    is WorkspaceLine.Header -> GroupHeaderRow(
                                        entry = line.entry,
                                        collapsed = line.collapsed,
                                        canCollapse = !narrowed,
                                        shape = shape,
                                        onToggle = { toggle(line.entry.group) },
                                        modifier = modifier,
                                    )
                                    is WorkspaceLine.Anchor -> GroupTerminalsRow(
                                        workspace = line.workspace,
                                        shape = shape,
                                        onOpen = { open(line.workspace) },
                                        onLongPress = { dialog = WorkspaceDialog.Actions(line.workspace, line.group) },
                                        modifier = modifier,
                                    )
                                }
                            }
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
            header = { WorkspaceHeader(current.workspace, current.group) },
        ) {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { dialog = null; open(current.workspace) }
            SheetAction(Icons.Filled.Add, "New terminal") {
                dialog = null
                run({ request("create_terminal", buildJsonObject { put("workspace_id", current.workspace.id) }) })
            }
            if (canPinShortcuts && current.workspace.terminals.isNotEmpty()) {
                SheetAction(Icons.AutoMirrored.Filled.AddToHomeScreen, "Add to Home screen") {
                    dialog = null
                    if (!app.shortcuts.requestPin(macId, macName, current.workspace)) {
                        scope.launch { snackbar.showSnackbar("Your home screen can't add this shortcut.") }
                    }
                }
            }
            if (current.group == null) {
                SheetAction(Icons.Filled.DriveFileRenameOutline, "Rename") { dialog = WorkspaceDialog.Rename(current.workspace) }
                SheetAction(Icons.Filled.Close, "Close workspace", destructive = true) {
                    dialog = WorkspaceDialog.Close(current.workspace, force = false)
                }
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
private fun FilterRow(all: List<WorkspaceInfo>, groups: List<WorkspaceGroupInfo>, selected: WorkspaceFilter, onSelect: (WorkspaceFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val counts = remember(all, groups) { WorkspaceFilter.entries.associateWith { WorkspaceListing.count(all, groups, it) } }
        WorkspaceFilter.entries.forEach { filter ->
            val count = counts.getValue(filter)
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
    val unread = WorkspaceListing.isUnread(workspace)
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
        WorkspaceTile(workspace, status = status)
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
                    modifier = Modifier.weight(1f),
                )
                when {
                    workspace.unreadCount > 0 -> Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text("${workspace.unreadCount}", modifier = Modifier.padding(horizontal = 2.dp)) }
                    workspace.hasUnread -> Box(
                        Modifier.padding(start = 12.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            SubtitleLine(
                WorkspaceListing.subtitle(workspace),
                WorkspaceListing.time(workspace.lastActivityAt ?: workspace.previewAt),
                highlightTime = unread,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SubtitleLine(subtitle: String, time: String?, highlightTime: Boolean = false, maxLines: Int = 1) {
    Row {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        if (time != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                time,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlightTime) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GroupHeaderRow(
    entry: WorkspaceEntry.Group,
    collapsed: Boolean,
    canCollapse: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = entry.members.size
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(background)
            .then(
                if (canCollapse) {
                    Modifier.clickable(onClickLabel = if (collapsed) "Show workspaces" else "Hide workspaces", onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(48.dp).semantics { contentDescription = if (count == 1) "1 workspace" else "$count workspaces" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Text(
                "$count",
                color = background,
                fontSize = with(LocalDensity.current) { 10.dp.toSp() },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        if (entry.group.isPinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            entry.group.name.ifBlank { entry.anchor?.title?.ifBlank { null } ?: "Group" },
            style = MaterialTheme.typography.titleMediumEmphasized,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canCollapse) {
            val rotation by animateFloatAsState(if (collapsed) 0f else 180f, tween(200), label = "chevron")
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTerminalsRow(
    workspace: WorkspaceInfo,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val unread = WorkspaceListing.isUnread(workspace)
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (WorkspaceListing.isAgent(workspace)) Icons.Filled.AutoAwesome else Icons.Filled.Terminal,
                contentDescription = WorkspaceListing.agentStatus(workspace)?.label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) {
            SubtitleLine(
                WorkspaceListing.subtitle(workspace),
                WorkspaceListing.time(workspace.lastActivityAt ?: workspace.previewAt),
                highlightTime = unread,
            )
        }
    }
}

@Composable
fun WorkspaceTile(
    workspace: WorkspaceInfo,
    size: Dp = 48.dp,
    status: AgentStatus? = null,
    color: Color? = null,
) {
    val accent = color ?: parseHexColor(WorkspaceListing.color(workspace)) ?: MaterialTheme.colorScheme.primary
    val corner = size * 0.3f
    val shown = rememberSettledStatus(status)
    val working by animateFloatAsState(if (shown is AgentStatus.Working) 1f else 0f, tween(600), label = "working")
    val waiting by animateFloatAsState(if (shown is AgentStatus.NeedsYou) 1f else 0f, tween(600), label = "waiting")
    val idle by animateFloatAsState(if (shown is AgentStatus.Idle) 1f else 0f, tween(600), label = "idle")
    val animating = working > 0f || waiting > 0f || idle > 0f
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { seconds = (it - start) / 1_000_000_000f }
    }
    val motion = remember(accent) { TileMotion(accent) }
    val agent = WorkspaceListing.isAgent(workspace)
    Box(
        Modifier
            .size(size)
            .then(if (status != null) Modifier.semantics { contentDescription = status.label } else Modifier)
            .drawBehind {
                if (waiting > 0f) motion.drawRipples(this, seconds, corner.toPx(), waiting)
                if (working > 0f) motion.drawHalo(this, seconds, corner.toPx(), working)
            }
            .graphicsLayer {
                val beat = if (waiting > 0f) motion.heartbeat(seconds) * waiting else 0f
                scaleX = 1f + beat
                scaleY = 1f + beat
            }
            .drawBehind {
                drawRoundRect(if (idle > 0f) motion.idleFill(seconds, idle) else accent, cornerRadius = CornerRadius(corner.toPx()))
                if (working > 0f) motion.drawAurora(this, seconds, corner.toPx(), working)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (agent) Icons.Filled.AutoAwesome else Icons.Filled.Terminal,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size / 2).graphicsLayer {
                val breathe = if (working > 0f) motion.breathe(seconds) * working else 0f
                val doze = if (idle > 0f) motion.doze(seconds) * idle else 0f
                scaleX = 1f + 0.05f * breathe + 0.05f * doze
                scaleY = 1f + 0.05f * breathe + 0.05f * doze
            },
        )
    }
}

@Composable
private fun rememberSettledStatus(status: AgentStatus?): AgentStatus? {
    var shown by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        if (shown is AgentStatus.Working && status !is AgentStatus.NeedsYou) delay(WORKING_HOLD_MILLIS)
        shown = status
    }
    return shown
}

private const val WORKING_HOLD_MILLIS = 8_000L

@Composable
private fun WorkspaceHeader(workspace: WorkspaceInfo, group: WorkspaceGroupInfo? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WorkspaceTile(workspace, size = 44.dp, color = group?.let { MaterialTheme.colorScheme.primaryContainer })
        Spacer(Modifier.width(16.dp))
        Column {
            Text(group?.let { WorkspaceListing.anchorTitle(it, workspace) } ?: workspace.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun TerminalGitLine(terminal: TerminalInfo) {
    val branch = WorkspaceListing.gitSummary(terminal)
    val ports = WorkspaceListing.portsSummary(terminal)
    if (branch == null && ports == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (branch != null) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = "Git branch", modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            listOfNotNull(branch, ports).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics {
                contentDescription = listOfNotNull(WorkspaceListing.gitDescription(terminal), ports).joinToString(", ")
            },
        )
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabeledTextField(
                value = query,
                onValueChange = onQueryChange,
                label = "Search workspaces",
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier.weight(1f),
            )
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "New workspace")
            }
        }
    }
}

@Composable
internal fun TerminalChoice(terminal: TerminalInfo, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(terminal.title.ifBlank { "Terminal" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val title = terminal.title.ifBlank { "Terminal" }
            val path = terminal.currentDirectory?.let(WorkspaceListing::shortPath)?.takeIf { it != title }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOfNotNull(terminal.agentState, path)
                    .takeIf { it.isNotEmpty() }
                    ?.let { Text(it.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TerminalGitLine(terminal)
            }
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
internal fun TextInputDialog(
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
                    LabeledTextField(
                        value = values[index],
                        onValueChange = { changed -> values = values.toMutableList().also { it[index] = changed } },
                        label = label,
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.map { it.trim() }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
