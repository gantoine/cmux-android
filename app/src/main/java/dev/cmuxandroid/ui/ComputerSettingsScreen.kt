package dev.cmuxandroid.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cmuxandroid.CmuxApplication
import dev.cmuxandroid.data.Mac
import dev.cmuxandroid.net.ConnectionState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerSettingsScreen(app: CmuxApplication, macId: String, onBack: () -> Unit, onRemoved: () -> Unit) {
    val macs by app.store.macs.collectAsState(initial = null)
    val mac = macs?.firstOrNull { it.id == macId }
    val connections by app.connections.connectionsFlow.collectAsState()
    val state by (connections[macId]?.state ?: flowOf(ConnectionState.Stopped)).collectAsState(initial = ConnectionState.Stopped)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.notificationPermissionChanged()
        if (granted) {
            scope.launch { app.store.setBackgroundNotifications(macId, true) }
        } else {
            scope.launch { snackbar.showSnackbar("Allow notifications for cmux in Android settings to get alerts.") }
        }
    }

    fun setBackground(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.notifier.canNotify()) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        scope.launch { app.store.setBackgroundNotifications(macId, enabled) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mac?.name?.let { "$it settings" } ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (mac == null) {
            if (macs != null) FullWidthMessage("This computer is not paired anymore.", Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
            ListItem(
                headlineContent = { Text("Notify me in the background") },
                supportingContent = {
                    Text("Keeps a connection to this computer open so agent and terminal alerts arrive while the app is closed. Uses some battery.")
                },
                trailingContent = { Switch(checked = mac.backgroundNotifications, onCheckedChange = ::setBackground) },
                colors = colors,
                modifier = Modifier.clickable { setBackground(!mac.backgroundNotifications) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Name") },
                supportingContent = { Text(mac.name) },
                colors = colors,
                modifier = Modifier.clickable { renaming = true },
            )
            ListItem(headlineContent = { Text("Address") }, supportingContent = { Text(mac.url) }, colors = colors)
            ListItem(headlineContent = { Text("Status") }, supportingContent = { Text(describe(state)) }, colors = colors)
            (state as? ConnectionState.Connected)?.welcome?.let { welcome ->
                ListItem(
                    headlineContent = { Text("Versions") },
                    supportingContent = { Text("cmux ${welcome.cmuxVersion} · relay ${welcome.relayVersion}") },
                    colors = colors,
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Remove this computer", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("This phone forgets the computer and stops connecting to it.") },
                colors = colors,
                modifier = Modifier.clickable { removing = true },
            )
        }
    }

    if (renaming && mac != null) RenameDialog(mac, onDismiss = { renaming = false }) { name ->
        renaming = false
        scope.launch { app.store.rename(mac.id, name) }
    }

    if (removing && mac != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${mac.name}?") },
            text = {
                Text("This phone forgets the computer. To also revoke its access on the computer, run `cmux-relay devices` and `cmux-relay revoke`.")
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    scope.launch {
                        app.store.remove(mac.id)
                        onRemoved()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameDialog(mac: Mac, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(mac.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename computer") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
