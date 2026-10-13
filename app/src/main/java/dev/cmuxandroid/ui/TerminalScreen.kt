package dev.cmuxandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.data.MacStore
import dev.cmuxandroid.net.ConnectionState
import dev.cmuxandroid.net.VisibleTerminal
import dev.cmuxandroid.terminal.TerminalCanvas
import dev.cmuxandroid.terminal.TerminalContent
import dev.cmuxandroid.terminal.TerminalHistory
import dev.cmuxandroid.terminal.TerminalInputSink
import dev.cmuxandroid.terminal.TerminalInputView
import dev.cmuxandroid.terminal.TerminalViewport
import dev.cmuxandroid.terminal.fittingGrid
import dev.cmuxandroid.terminal.rememberCellMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(app: CmuxApplication, macId: String, workspaceId: String, surfaceId: String, onBack: () -> Unit) {
    val connections by app.connections.connectionsFlow.collectAsState()
    val connection = connections[macId]
    if (connection == null) {
        FullWidthMessage("This Mac is not paired anymore.")
        return
    }
    val state by connection.state.collectAsState()
    val screen by connection.screen.collectAsState()
    val workspaces by connection.workspaces.collectAsState()
    val terminal = workspaces?.firstOrNull { it.id == workspaceId }?.terminals?.firstOrNull { it.id == surfaceId }
    val workspace = workspaces?.firstOrNull { it.id == workspaceId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var fontSize by remember { mutableFloatStateOf(MacStore.DEFAULT_FONT_SIZE) }
    var fontSizeLoaded by remember { mutableStateOf(false) }
    var fitToPhone by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    val viewport = remember(surfaceId) { TerminalViewport() }
    var history by remember(surfaceId) { mutableStateOf<TerminalHistory?>(null) }
    var loadingHistory by remember(surfaceId) { mutableStateOf(false) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }

    LaunchedEffect(Unit) {
        fontSize = app.store.settings.first().fontSize
        fontSizeLoaded = true
    }
    LaunchedEffect(fontSize, fontSizeLoaded) {
        if (!fontSizeLoaded) return@LaunchedEffect
        delay(500)
        app.store.setFontSize(fontSize)
    }

    DisposableEffect(connection, workspaceId, surfaceId) {
        val attachment = connection.attach(workspaceId, surfaceId)
        val visible = VisibleTerminal(macId, surfaceId)
        app.connections.visibleTerminal.value = visible
        onDispose {
            connection.detach(attachment)
            if (app.connections.visibleTerminal.value === visible) app.connections.visibleTerminal.value = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { connection.resync() }
    LaunchedEffect(connection) {
        connection.detached.collect { detached ->
            if (detached.surfaceId == surfaceId) {
                snackbar.showSnackbar("This terminal closed on the Mac.")
                onBack()
            }
        }
    }

    fun perform(type: String, build: JsonObjectBuilder.() -> Unit = {}) {
        scope.launch {
            connection.request(type, buildJsonObject(build)).onFailure {
                snackbar.showSnackbar(it.message ?: "The Mac did not accept that.")
            }
        }
    }

    fun goLive() {
        viewport.returnToLive()
        viewport.selection = null
        history = null
    }

    fun loadHistory() {
        if (loadingHistory || history != null) return
        loadingHistory = true
        scope.launch {
            connection.history()
                .onSuccess { response ->
                    viewport.selection = null
                    history = TerminalHistory(response.styles.associateBy { it.id }, response.lines)
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not load earlier output.") }
            loadingHistory = false
        }
    }

    val sink = remember(connection) {
        object : TerminalInputSink {
            override fun text(text: String) {
                if (ctrl && text.length == 1 && text[0].isLetter()) {
                    ctrl = false
                    connection.send("key", buildJsonObject { put("key", "ctrl+${text[0].lowercaseChar()}") })
                    return
                }
                ctrl = false
                if (!viewport.isLive) goLive()
                connection.send("input", buildJsonObject { put("text", text) })
            }

            override fun key(name: String) {
                val withCtrl = if (ctrl && name in CTRL_COMBINABLE_KEYS) "ctrl+$name" else name
                ctrl = false
                if (!viewport.isLive) goLive()
                connection.send("key", buildJsonObject { put("key", withCtrl) })
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            terminal?.title?.ifBlank { null } ?: workspace?.title ?: "Terminal",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val subtitle = if (state is ConnectionState.Connected) {
                            listOfNotNull(workspace?.title, terminal?.agentState).joinToString(" · ")
                        } else {
                            describe(state)
                        }
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconToggleButton(checked = fitToPhone, onCheckedChange = { fitToPhone = it }) {
                        Icon(Icons.Filled.FitScreen, contentDescription = "Fit the terminal to this phone")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
            ) {
                val measurer = rememberTextMeasurer()
                val metrics = rememberCellMetrics(measurer, fontSize.sp)
                val grid = fittingGrid(constraints.maxWidth, constraints.maxHeight, metrics)
                LaunchedEffect(fitToPhone, grid) {
                    if (fitToPhone) delay(FIT_SETTLE_MS)
                    connection.setFit(if (fitToPhone) grid else null)
                }
                val current = screen
                LaunchedEffect(current?.altScreen) {
                    if (current?.altScreen == true) goLive()
                }
                if (current == null) {
                    FullWidthMessage(if (state is ConnectionState.Connected) "Loading the terminal…" else describe(state))
                } else {
                    TerminalCanvas(
                        content = TerminalContent(current, history),
                        viewport = viewport,
                        fontSize = fontSize,
                        onFontSizeChange = { fontSize = it },
                        onNeedHistory = ::loadHistory,
                        onReturnToLive = ::goLive,
                        onScrollProgram = { lines -> connection.send("scroll", buildJsonObject { put("lines", lines) }) },
                        onTap = { inputView?.showKeyboard() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val selection = viewport.selection
                    if (selection != null && !viewport.selecting) {
                        SelectionBar(
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                            onCopy = {
                                val text = TerminalContent(current, history).text(selection)
                                clipboard.setText(AnnotatedString(text))
                                viewport.selection = null
                                scope.launch { snackbar.showSnackbar("Copied ${text.lines().size} line(s).") }
                            },
                            onCancel = { viewport.selection = null },
                        )
                    }
                    if (!viewport.isLive) {
                        SmallFloatingActionButton(
                            onClick = ::goLive,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                                Text("Live")
                            }
                        }
                    }
                }
            }
            KeyBar(
                ctrl = ctrl,
                onCtrl = { ctrl = !ctrl },
                onKey = sink::key,
                onKeyboard = { inputView?.showKeyboard() },
                onPaste = {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { pasted ->
                        if (!viewport.isLive) goLive()
                        perform("paste") {
                            put("text", pasted)
                            put("submit", false)
                        }
                    }
                },
                inputHost = {
                    AndroidView(
                        factory = { context -> TerminalInputView(context).also { inputView = it } },
                        update = { view -> view.sink = sink },
                        modifier = Modifier.size(1.dp),
                    )
                },
            )
        }
    }
}

private const val FIT_SETTLE_MS = 300L

private val CTRL_COMBINABLE_KEYS = setOf(
    "up", "down", "left", "right", "home", "end", "pageup", "pagedown", "enter", "tab", "backspace", "delete",
)

private val KEYS = listOf(
    "Esc" to "escape",
    "Tab" to "tab",
    "↑" to "up",
    "↓" to "down",
    "←" to "left",
    "→" to "right",
    "^C" to "ctrl-c",
    "^D" to "ctrl-d",
    "⌫" to "backspace",
    "⏎" to "enter",
)

@Composable
private fun KeyBar(
    ctrl: Boolean,
    onCtrl: () -> Unit,
    onKey: (String) -> Unit,
    onKeyboard: () -> Unit,
    onPaste: () -> Unit,
    inputHost: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        inputHost()
        IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, contentDescription = "Show the keyboard") }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton(if (ctrl) "Ctrl ●" else "Ctrl", onClick = onCtrl)
            KEYS.forEach { (label, name) -> KeyButton(label) { onKey(name) } }
            KeyButton("Paste", onClick = onPaste)
        }
    }
}

@Composable
private fun SelectionBar(modifier: Modifier, onCopy: () -> Unit, onCancel: () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy) { Text("Copy") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    Box {
        FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
