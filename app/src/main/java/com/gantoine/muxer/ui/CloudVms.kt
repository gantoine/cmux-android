package com.gantoine.muxer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.net.RelayException
import com.gantoine.muxer.protocol.CloudVm
import com.gantoine.muxer.protocol.CloudVmsResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class HostedVm(val vm: CloudVm, val macId: String)

enum class VmPhase { Running, Asleep, Changing, Attention }

object CloudVms {
    const val REFRESH_MS = 15_000L
    private val noCloudCodes = setOf("cloud_disabled", "unsupported", "unknown_type")

    fun phase(status: String): VmPhase = when (status.lowercase()) {
        "running", "ready", "standby" -> VmPhase.Running
        "paused", "stopped", "suspended" -> VmPhase.Asleep
        "creating", "starting", "pending", "resuming", "pausing" -> VmPhase.Changing
        else -> VmPhase.Attention
    }

    fun statusLabel(status: String): String = status.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun megabytes(mb: Long): String = Metrics.bytes(mb * 1024 * 1024)

    fun summary(vm: CloudVm): String = buildList {
        add(statusLabel(vm.status))
        val stats = vm.stats ?: return@buildList
        stats.cpuPercent?.let { add("${Metrics.cpu(it)} CPU") }
        val used = stats.memoryUsedMb
        val total = stats.memoryTotalMb
        if (used != null && total != null && total > 0) add("${Metrics.cpu(used * 100.0 / total)} RAM")
    }.joinToString(" · ")

    fun details(vm: CloudVm, macName: String?): List<Pair<String, String>> = buildList {
        add("Status" to statusLabel(vm.status))
        if (vm.image.isNotBlank()) add("Image" to listOf(vm.image, vm.kind).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        vm.stats?.let { stats ->
            val cpu = listOfNotNull(stats.cpus?.let { if (it == 1) "1 CPU" else "$it CPUs" }, stats.cpuPercent?.let { "${Metrics.cpu(it)} used" })
            if (cpu.isNotEmpty()) add("Processor" to cpu.joinToString(" · "))
            usage(stats.memoryUsedMb, stats.memoryTotalMb)?.let { add("RAM" to it) }
            usage(stats.diskUsedMb, stats.diskTotalMb)?.let { add("Disk" to it) }
        }
        vm.createdAt?.let(::date)?.let { add("Created" to it) }
        if (macName != null) add("Via" to macName)
    }

    private fun usage(used: Long?, total: Long?): String? = when {
        used != null && total != null -> "${megabytes(used)} of ${megabytes(total)}"
        total != null -> megabytes(total)
        used != null -> "${megabytes(used)} used"
        else -> null
    }

    private fun date(iso: String): String? = runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
    }.getOrNull()

    fun answers(previous: Map<String, CloudVmsResponse>, macId: String, result: Result<CloudVmsResponse>): Map<String, CloudVmsResponse> =
        result.fold(
            onSuccess = { previous + (macId to it) },
            onFailure = { if ((it as? RelayException)?.code in noCloudCodes) previous - macId else previous },
        )

    fun merge(answers: Map<String, CloudVmsResponse>, macOrder: List<String>): List<HostedVm>? {
        val answered = macOrder.filter { it in answers }
        if (answered.isEmpty()) return null
        return answered.flatMap { macId -> answers.getValue(macId).vms.map { HostedVm(it, macId) } }.distinctBy { it.vm.id }
    }
}

@Stable
class CloudVmsModel {
    var vms by mutableStateOf<List<HostedVm>?>(null)
    var busy by mutableStateOf(emptySet<String>())

    fun replace(vm: CloudVm) {
        vms = vms?.map { hosted ->
            when {
                hosted.vm.id != vm.id -> hosted
                CloudVms.phase(vm.status) == VmPhase.Running -> hosted.copy(vm = vm.copy(stats = hosted.vm.stats))
                else -> hosted.copy(vm = vm)
            }
        }
    }
}

@Composable
fun rememberCloudVms(app: MuxerApplication, macOrder: List<String>): CloudVmsModel {
    val model = remember { CloudVmsModel() }
    val connectedIds by app.connections.connectedMacIds.collectAsState(initial = emptySet())
    val connections by app.connections.connectionsFlow.collectAsState()
    val currentConnections by rememberUpdatedState(connections)
    val currentOrder by rememberUpdatedState(macOrder)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(connectedIds) {
        if (connectedIds.isEmpty()) {
            model.vms = null
            return@LaunchedEffect
        }
        var answers = emptyMap<String, CloudVmsResponse>()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val results = coroutineScope {
                    connectedIds.mapNotNull { id -> currentConnections[id]?.let { id to it } }
                        .map { (id, connection) -> async { id to connection.cloudVms() } }
                        .awaitAll()
                }
                answers = answers.filterKeys { it in connectedIds }
                results.forEach { (id, result) -> answers = CloudVms.answers(answers, id, result) }
                model.vms = CloudVms.merge(answers, currentOrder)
                delay(CloudVms.REFRESH_MS)
            }
        }
    }
    return model
}

@Composable
fun vmPhaseColor(phase: VmPhase): Color = when (phase) {
    VmPhase.Running -> BrandGreen
    VmPhase.Changing -> MaterialTheme.colorScheme.tertiary
    VmPhase.Asleep -> MaterialTheme.colorScheme.outline
    VmPhase.Attention -> MaterialTheme.colorScheme.error
}

@Composable
fun VmAvatar(vm: CloudVm, size: Dp = 52.dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(size / 2),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.3f)
                .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                .padding(2.dp)
                .background(vmPhaseColor(CloudVms.phase(vm.status)), CircleShape),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VmRow(
    vm: CloudVm,
    busy: Boolean,
    shape: Shape,
    onOpen: () -> Unit,
    onSetPaused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = CloudVms.phase(vm.status)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmAvatar(vm)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(vm.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                CloudVms.summary(vm),
                style = MaterialTheme.typography.bodyMedium,
                color = if (phase == VmPhase.Attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            busy || phase == VmPhase.Changing -> LoadingIndicator(Modifier.size(40.dp))
            phase == VmPhase.Running -> FilledTonalIconButton(onClick = { onSetPaused(true) }) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause ${vm.name}")
            }
            phase == VmPhase.Asleep -> FilledTonalIconButton(onClick = { onSetPaused(false) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume ${vm.name}")
            }
        }
    }
}

@Composable
fun VmSheet(vm: CloudVm, macName: String?, busy: Boolean, onDismiss: () -> Unit, onSetPaused: (Boolean) -> Unit) {
    val phase = CloudVms.phase(vm.status)
    ActionSheet(
        onDismiss = onDismiss,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VmAvatar(vm, size = 44.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(vm.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        CloudVms.statusLabel(vm.status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudVms.details(vm, macName).drop(1).forEach { (label, value) ->
                Row {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(132.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            busy -> Unit
            phase == VmPhase.Running -> SheetAction(Icons.Filled.Pause, "Pause") { onSetPaused(true) }
            phase == VmPhase.Asleep -> SheetAction(Icons.Filled.PlayArrow, "Resume") { onSetPaused(false) }
        }
    }
}
