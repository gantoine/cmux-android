package dev.cmuxandroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.net.ConnectionState
import dev.cmuxandroid.net.RelayConnection
import dev.cmuxandroid.net.RelayException
import dev.cmuxandroid.protocol.CreatedWorkspace
import dev.cmuxandroid.protocol.RelayJson
import dev.cmuxandroid.protocol.TerminalInfo
import dev.cmuxandroid.protocol.WorkspaceInfo
import dev.cmuxandroid.terminal.parseHexColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private sealed interface WorkspaceDialog {
    data object Create : WorkspaceDialog
    data class Rename(val workspace: WorkspaceInfo) : WorkspaceDialog
    data class Close(val workspace: WorkspaceInfo, val force: Boolean) : WorkspaceDialog
    data class PickTerminal(val workspace: WorkspaceInfo) : WorkspaceDialog
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var query by remember { mutableStateOf("") }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(macName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings for $macName")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = WorkspaceDialog.Create }) {
                Icon(Icons.Filled.Add, contentDescription = "New workspace")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (connection == null) {
                FullWidthMessage("This computer is not paired anymore.")
                return@Column
            }
            val state by connection.state.collectAsState()
            val workspaces by connection.workspaces.collectAsState()
            if (state !is ConnectionState.Connected) {
                FullWidthMessage(describe(state))
            }
            val all = workspaces ?: return@Column
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search workspaces") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = all.filter { WorkspaceListing.matches(it, query) }
            if (list.isEmpty() && query.isNotBlank()) {
                FullWidthMessage("No workspaces match \"${query.trim()}\".")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { workspace ->
                    WorkspaceRow(
                        workspace = workspace,
                        onOpen = { open(workspace) },
                        onRename = { dialog = WorkspaceDialog.Rename(workspace) },
                        onNewTerminal = {
                            run({ request("create_terminal", buildJsonObject { put("workspace_id", workspace.id) }) })
                        },
                        onClose = { dialog = WorkspaceDialog.Close(workspace, force = false) },
                    )
                    HorizontalDivider()
                }
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
        is WorkspaceDialog.PickTerminal -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(current.workspace.title.ifBlank { "Terminals" }) },
            text = {
                Column {
                    current.workspace.terminals.forEach { terminal ->
                        TerminalChoice(terminal) {
                            dialog = null
                            onOpenTerminal(current.workspace.id, terminal.id)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(
    workspace: WorkspaceInfo,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onNewTerminal: () -> Unit,
    onClose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = parseHexColor(WorkspaceListing.color(workspace)) ?: MaterialTheme.colorScheme.surfaceVariant
            Box(Modifier.size(42.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (WorkspaceListing.isAgent(workspace)) Icons.Filled.SmartToy else Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (workspace.isPinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Pinned", Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        workspace.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val detail = workspace.preview ?: workspace.currentDirectory?.let(::shortPath)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val agentStates = workspace.terminals.mapNotNull { it.agentState }.distinct()
                if (agentStates.isNotEmpty() || workspace.terminals.size > 1) {
                    Text(
                        (listOf("${workspace.terminals.size} terminals").filter { workspace.terminals.size > 1 } + agentStates)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkspaceListing.time(workspace.lastActivityAt ?: workspace.previewAt)?.let { time ->
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (workspace.unreadCount > 0) {
                    Box(
                        Modifier.background(Color(0xFF8CAAEE), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${workspace.unreadCount}", color = Color(0xFF1E1F29), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("New terminal") }, onClick = { menu = false; onNewTerminal() })
            DropdownMenuItem(text = { Text("Close workspace") }, onClick = { menu = false; onClose() })
        }
    }
}

@Composable
private fun TerminalChoice(terminal: TerminalInfo, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Icon(Icons.Filled.Terminal, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(terminal.title.ifBlank { "Terminal" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(terminal.agentState, terminal.currentDirectory?.let(::shortPath)).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { index, (label, _) ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { changed -> values = values.toMutableList().also { it[index] = changed } },
                        label = { Text(label) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.map { it.trim() }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shortPath(path: String): String {
    val home = Regex("^/Users/[^/]+").find(path)?.value ?: return path
    return "~" + path.removePrefix(home)
}
