package dev.cmuxandroid.ui

import androidx.compose.animation.AnimatedVisibility
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.cmuxandroid.terminal.StickyModifiers
import dev.cmuxandroid.terminal.TerminalKeys
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
import androidx.compose.ui.platform.LocalDensity
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
import dev.cmuxandroid.terminal.TerminalBottomPadding
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
        FullWidthMessage("This computer is not paired anymore.")
        return
    }
    val state by connection.state.collectAsState()
    val screen by connection.screen.collectAsState()
    val workspaces by connection.workspaces.collectAsState()
    val chat by connection.chat.collectAsState()
    val terminal = workspaces?.firstOrNull { it.id == workspaceId }?.terminals?.firstOrNull { it.id == surfaceId }
    val workspace = workspaces?.firstOrNull { it.id == workspaceId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var fontSize by remember { mutableFloatStateOf(MacStore.DEFAULT_FONT_SIZE) }
    var fontSizeLoaded by remember { mutableStateOf(false) }
    var fitToPhone by remember { mutableStateOf(false) }
    var modifiers by remember { mutableStateOf(StickyModifiers()) }
    var composerOpen by remember { mutableStateOf(false) }
    var composerText by rememberSaveable { mutableStateOf("") }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) composerText = listOf(composerText.trimEnd(), spoken).filter { it.isNotEmpty() }.joinToString(" ")
    }
    val viewport = remember(surfaceId) { TerminalViewport() }
    var history by remember(surfaceId) { mutableStateOf<TerminalHistory?>(null) }
    var loadingHistory by remember(surfaceId) { mutableStateOf(false) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    var showChat by remember(surfaceId) { mutableStateOf(false) }
    LaunchedEffect(chat?.sessionId) { showChat = chat != null }

    LaunchedEffect(Unit) {
        val settings = app.store.settings.first()
        fontSize = settings.fontSize
        fitToPhone = settings.fitToPhone
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
                snackbar.showSnackbar("This terminal closed on the computer.")
                onBack()
            }
        }
    }

    fun perform(type: String, build: JsonObjectBuilder.() -> Unit = {}) {
        scope.launch {
            connection.request(type, buildJsonObject(build)).onFailure {
                snackbar.showSnackbar(it.message ?: "The computer did not accept that.")
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

    fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            dictation.launch(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No voice input is available on this phone.") }
        }
    }

    fun sendMessage() {
        val message = composerText.trim()
        if (message.isEmpty()) return
        if (!viewport.isLive) goLive()
        perform("paste") {
            put("text", message)
            put("submit", true)
        }
        composerText = ""
    }

    val sink = remember(connection) {
        object : TerminalInputSink {
            override fun text(text: String) {
                val input = TerminalKeys.withModifiers(text, modifiers)
                modifiers = StickyModifiers()
                if (!viewport.isLive) goLive()
                when (input) {
                    is TerminalKeys.Input.Key -> connection.send("key", buildJsonObject { put("key", input.name) })
                    is TerminalKeys.Input.Text -> connection.send("input", buildJsonObject { put("text", input.text) })
                }
            }

            override fun key(name: String) {
                val input = TerminalKeys.keyWithModifiers(name, modifiers)
                modifiers = StickyModifiers()
                if (!viewport.isLive) goLive()
                connection.send("key", buildJsonObject { put("key", input.name) })
            }
        }
    }

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = terminal?.title?.ifBlank { null } ?: workspace?.title ?: "Terminal",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (chat != null) {
                        IconButton(onClick = { showChat = !showChat }) {
                            if (showChat) {
                                Icon(Icons.Filled.Terminal, contentDescription = "Show the terminal")
                            } else {
                                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = "Show the chat")
                            }
                        }
                    }
                    if (!showChat || chat == null) {
                        IconToggleButton(checked = fitToPhone, onCheckedChange = {
                            fitToPhone = it
                            scope.launch { app.store.setFitToPhone(it) }
                        }) {
                            Icon(Icons.Filled.FitScreen, contentDescription = "Fit the terminal to this phone")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            AnimatedVisibility(visible = state !is ConnectionState.Connected) { ConnectionBanner(state) }
            val currentChat = chat
            if (showChat && currentChat != null) {
                ChatView(
                    chat = currentChat,
                    status = terminal?.let(WorkspaceListing::agentStatus),
                    draft = composerText,
                    onDraftChange = { composerText = it },
                    onSend = ::sendMessage,
                    onDictate = ::dictate,
                    onStop = { connection.send("key", buildJsonObject { put("key", "escape") }) },
                    onShowTerminal = { showChat = false },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
                ) {
                    val measurer = rememberTextMeasurer()
                    val metrics = rememberCellMetrics(measurer, fontSize.sp)
                    val bottomPadding = with(LocalDensity.current) { TerminalBottomPadding.roundToPx() }
                    val grid = fittingGrid(constraints.maxWidth, constraints.maxHeight - bottomPadding, metrics)
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
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                    if (composerOpen) {
                        Composer(
                            text = composerText,
                            onTextChange = { composerText = it },
                            onDictate = ::dictate,
                            onSend = ::sendMessage,
                        )
                    }
                    KeyBar(
                        modifiers = modifiers,
                        onModifiers = { modifiers = it },
                        onKey = sink::key,
                        onKeyboard = { inputView?.showKeyboard() },
                        composerOpen = composerOpen,
                        onComposer = { composerOpen = !composerOpen },
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
private fun KeyBar(
    modifiers: StickyModifiers,
    onModifiers: (StickyModifiers) -> Unit,
    onKey: (String) -> Unit,
    onKeyboard: () -> Unit,
    composerOpen: Boolean,
    onComposer: () -> Unit,
    onPaste: () -> Unit,
    inputHost: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        inputHost()
        IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, contentDescription = "Show the keyboard") }
        IconToggleButton(checked = composerOpen, onCheckedChange = { onComposer() }) {
            Icon(Icons.Filled.EditNote, contentDescription = if (composerOpen) "Hide the message box" else "Write a message")
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModifierButton("Ctrl", modifiers.ctrl) { onModifiers(modifiers.copy(ctrl = !modifiers.ctrl)) }
            ModifierButton("⌥", modifiers.alt) { onModifiers(modifiers.copy(alt = !modifiers.alt)) }
            ModifierButton("⇧", modifiers.shift) { onModifiers(modifiers.copy(shift = !modifiers.shift)) }
            KEYS.forEach { (label, name) -> KeyButton(label) { onKey(name) } }
            KeyButton("Paste", onClick = onPaste)
        }
    }
}

@Composable
private fun ModifierButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            KeyLabel(label)
        }
    } else {
        KeyButton(label, onClick)
    }
}

@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onDictate: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Message") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDictate) { Icon(Icons.Filled.Mic, contentDescription = "Dictate a message") }
        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send the message")
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
            KeyLabel(label)
        }
    }
}

@Composable
private fun KeyLabel(label: String) {
    val symbol = label.length == 1 && !label[0].isLetterOrDigit()
    Text(label, style = if (symbol) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
}
