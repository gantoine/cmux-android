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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.data.Mac
import dev.cmuxandroid.net.ConnectionState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MacsScreen(app: CmuxApplication, onOpenMac: (Mac) -> Unit, onAddMac: () -> Unit) {
    val macs by app.store.macs.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<Mac?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Computers") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddMac,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Pair a computer") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val list = macs
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyMacs()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { mac ->
                        val connection by app.connections.connectionsFlow.collectAsState()
                        val state by (connection[mac.id]?.state ?: flowOf(ConnectionState.Stopped))
                            .collectAsState(initial = ConnectionState.Stopped)
                        ListItem(
                            modifier = Modifier.combinedClickable(onClick = { onOpenMac(mac) }, onLongClick = { removing = mac }),
                            headlineContent = { Text(mac.name) },
                            supportingContent = { Text(describe(state), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingContent = { StatusDot(state) },
                            trailingContent = {
                                if (mac.backgroundNotifications) {
                                    Icon(
                                        Icons.Filled.NotificationsActive,
                                        contentDescription = "Notifies in the background",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    }
                }
            }
        }
    }

    removing?.let { mac ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${mac.name}?") },
            text = {
                Text("This phone forgets the computer. To also revoke its access on the computer, run `cmux-relay devices` and `cmux-relay revoke`.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.store.remove(mac.id) }
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyMacs() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No computers yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            "On your computer, install the relay and run `cmux-relay pair`. Then scan the QR code with your camera or tap Pair a computer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        is ConnectionState.Connected -> Color(0xFFA6D189)
        ConnectionState.Connecting -> Color(0xFFE5C890)
        is ConnectionState.Disconnected, ConnectionState.Unauthorized -> Color(0xFFE78284)
        ConnectionState.Stopped -> Color(0xFF737994)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
    }
}

fun describe(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected · cmux ${state.welcome.cmuxVersion}"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Disconnected -> "Offline · ${state.reason}"
    ConnectionState.Unauthorized -> "Not paired anymore. Remove it, then pair again."
    ConnectionState.Stopped -> "Idle"
}

@Composable
fun FullWidthMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
