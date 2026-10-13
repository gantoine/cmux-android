package dev.cmuxandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.data.MacStore
import dev.cmuxandroid.net.ConnectionState
import dev.cmuxandroid.net.RelayConnection
import dev.cmuxandroid.net.VisibleTerminal
import dev.cmuxandroid.terminal.TerminalCanvas
import dev.cmuxandroid.terminal.fittingGrid
import dev.cmuxandroid.terminal.rememberCellMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    var fontSize by remember { mutableFloatStateOf(MacStore.DEFAULT_FONT_SIZE) }
    var fontSizeLoaded by remember { mutableStateOf(false) }
    var fitToPhone by remember { mutableStateOf(false) }
    var liveTyping by remember { mutableStateOf(false) }
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

    fun perform(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        scope.launch {
            connection.request(type, buildJsonObject(build)).onFailure {
                snackbar.showSnackbar(it.message ?: "The Mac did not accept that.")
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
                    IconToggleButton(checked = liveTyping, onCheckedChange = { liveTyping = it }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = if (liveTyping) "Live typing on" else "Live typing off")
                    }
                    IconToggleButton(checked = fitToPhone, onCheckedChange = { fitToPhone = it }) {
                        Icon(Icons.Filled.FitScreen, contentDescription = "Fit the terminal to this phone")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
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
                if (current == null) {
                    FullWidthMessage(if (state is ConnectionState.Connected) "Loading the terminal…" else describe(state))
                } else {
                    TerminalCanvas(
                        screen = current,
                        fontSize = fontSize,
                        onFontSizeChange = { fontSize = it },
                        onScrollLines = { lines -> connection.send("scroll", buildJsonObject { put("lines", lines) }) },
                        onTap = {
                            focus.requestFocus()
                            keyboard?.show()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            InputBar(
                connection = connection,
                liveTyping = liveTyping,
                focus = focus,
                perform = ::perform,
            )
        }
    }
}

private const val FIT_SETTLE_MS = 300L

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
private fun InputBar(
    connection: RelayConnection,
    liveTyping: Boolean,
    focus: FocusRequester,
    perform: (String, kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun key(name: String) = perform("key") { put("key", name) }
    fun input(value: String) = perform("input") { put("text", value) }

    fun submit() {
        when {
            liveTyping || text.isEmpty() -> key("enter")
            else -> input(text + "\r")
        }
        text = ""
    }

    fun onTextChange(changed: String) {
        if (ctrl && changed.length > text.length) {
            val typed = changed.last().lowercaseChar()
            ctrl = false
            if (typed in 'a'..'z') {
                key("ctrl+$typed")
                return
            }
        }
        if (liveTyping) {
            val common = text.commonPrefixWith(changed).length
            repeat(text.length - common) { key("backspace") }
            changed.substring(common).takeIf { it.isNotEmpty() }?.let(::input)
        }
        text = changed
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton(if (ctrl) "Ctrl ●" else "Ctrl") { ctrl = !ctrl }
            KEYS.forEach { (label, name) -> KeyButton(label) { key(name) } }
            KeyButton("Paste") {
                clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { pasted ->
                    perform("paste") {
                        put("text", pasted)
                        put("submit", false)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = ::onTextChange,
                placeholder = { Text(if (liveTyping) "Keys go straight to the terminal" else "Type a command") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = ::submit) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    Box {
        FilledTonalButton(
            onClick = onClick,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
