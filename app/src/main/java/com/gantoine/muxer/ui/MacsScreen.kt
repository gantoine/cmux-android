package com.gantoine.muxer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.data.Mac
import com.gantoine.muxer.net.ConnectionState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private sealed interface MacDialog {
    data class Actions(val mac: Mac) : MacDialog
    data class Remove(val mac: Mac) : MacDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacsScreen(app: MuxerApplication, onOpenMac: (Mac) -> Unit, onOpenSettings: (Mac) -> Unit, onAddMac: () -> Unit) {
    val macs by app.store.macs.collectAsState(initial = null)
    val connections by app.connections.connectionsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<MacDialog?>(null) }

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = "Computers",
            )
        },
        floatingActionButton = {
            if (!macs.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAddMac,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Pair a computer") },
                )
            }
        },
    ) { padding ->
        val list = macs
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize()) {
                GhostComputers(Modifier.padding(top = 8.dp))
                Button(
                    onClick = onAddMac,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 300.dp)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Icon(Icons.Filled.AddToQueue, contentDescription = null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Pair your first computer", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                }
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(list, key = { _, mac -> mac.id }) { index, mac ->
                    val state by (connections[mac.id]?.state ?: flowOf(ConnectionState.Stopped))
                        .collectAsState(initial = ConnectionState.Stopped)
                    val keepAwake by (connections[mac.id]?.keepAwake ?: flowOf(null)).collectAsState(initial = null)
                    MacRow(
                        mac = mac,
                        state = state,
                        keepAwake = keepAwake == true,
                        shape = groupedShape(index, list.size),
                        onOpen = { onOpenMac(mac) },
                        onLongPress = { dialog = MacDialog.Actions(mac) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        is MacDialog.Actions -> ActionSheet(
            onDismiss = { dialog = null },
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MacAvatar(connections[current.mac.id]?.state?.value ?: ConnectionState.Stopped, size = 44.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(current.mac.name, style = MaterialTheme.typography.titleMedium)
                        Text(current.mac.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        ) {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open workspaces") { dialog = null; onOpenMac(current.mac) }
            SheetAction(Icons.Outlined.Settings, "Settings") { dialog = null; onOpenSettings(current.mac) }
            SheetAction(Icons.Filled.Delete, "Remove from this phone", destructive = true) { dialog = MacDialog.Remove(current.mac) }
        }
        is MacDialog.Remove -> RemoveComputerDialog(current.mac, onDismiss = { dialog = null }) {
            scope.launch { app.store.remove(current.mac.id) }
            dialog = null
        }
    }
}

@Composable
private fun GhostComputers(modifier: Modifier = Modifier, rows: Int = 4) {
    val background = MaterialTheme.colorScheme.background
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val bone = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.15f),
                        0.45f to background.copy(alpha = 0.55f),
                        0.9f to background,
                    ),
                )
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(groupedShape(index, rows))
                    .background(container)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).background(bone, CircleShape))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.45f else 0.6f).height(16.dp).background(bone, CircleShape))
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(bone.copy(alpha = 0.7f), CircleShape))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MacRow(
    mac: Mac,
    state: ConnectionState,
    keepAwake: Boolean,
    shape: Shape,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacAvatar(state)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mac.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                describe(state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is ConnectionState.Connected) MaterialTheme.colorScheme.onSurfaceVariant else statusColor(state),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (keepAwake) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Outlined.Coffee,
                contentDescription = "Kept awake",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (mac.backgroundNotifications) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Outlined.NotificationsActive,
                contentDescription = "Notifies in the background",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun MacAvatar(state: ConnectionState, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LaptopMac,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size / 2),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.3f)
                .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                .padding(2.dp)
                .background(statusColor(state), CircleShape),
        )
    }
}
