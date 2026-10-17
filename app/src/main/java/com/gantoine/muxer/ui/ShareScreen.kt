package com.gantoine.muxer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.share.ShareComputer
import com.gantoine.muxer.share.ShareRouting
import com.gantoine.muxer.share.ShareTarget
import kotlinx.coroutines.flow.combine

@Composable
fun ShareScreen(app: MuxerApplication, onBack: () -> Unit, onPick: (ShareTarget) -> Unit) {
    val computersFlow = remember(app) {
        combine(app.snapshots.snapshots, app.store.macs) { snapshots, macs ->
            ShareRouting.computers(snapshots.values, macs.map { it.id })
        }
    }
    val computers by computersFlow.collectAsState(initial = null)
    var choosing by remember { mutableStateOf<ShareTarget?>(null) }

    Scaffold(
        topBar = {
            CmuxTopBar(
                title = "Send to an agent",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val list = computers
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when {
                list == null -> Unit
                list.isEmpty() -> item(key = "empty") { NoAgents(onBack) }
                else -> list.forEach { computer -> computerSection(computer) { target -> if (target.needsTerminalChoice) choosing = target else onPick(target) } }
            }
        }
    }
    choosing?.let { target ->
        ActionSheet(onDismiss = { choosing = null }) {
            target.agentTerminals.forEach { terminal ->
                TerminalChoice(terminal) {
                    choosing = null
                    onPick(target.copy(surfaceId = terminal.id))
                }
            }
        }
    }
}

private fun LazyListScope.computerSection(computer: ShareComputer, onPick: (ShareTarget) -> Unit) {
    item(key = "computer:${computer.macId}") {
        SectionLabel(if (computer.connected) computer.macName else "${computer.macName} · offline")
    }
    itemsIndexed(computer.targets, key = { _, target -> "target:${target.macId}:${target.workspace.id}" }) { index, target ->
        ShareTargetRow(target, groupedShape(index, computer.targets.size), onClick = { onPick(target) })
    }
}


@Composable
private fun ShareTargetRow(target: ShareTarget, shape: Shape, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceTile(target.workspace, size = 40.dp, animated = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(target.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                WorkspaceListing.subtitle(target.workspace),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoAgents(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        EmptyState(
            icon = Icons.Filled.AutoAwesome,
            title = "No agents running",
            body = "Start a coding agent in a cmux workspace on your computer, then share again.",
            modifier = Modifier.fillMaxWidth(),
            action = { Button(onClick = onBack) { Text("Go back") } },
        )
    }
}

