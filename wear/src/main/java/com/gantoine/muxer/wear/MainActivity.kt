package com.gantoine.muxer.wear

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.gantoine.muxer.watch.WatchAnswerResult
import com.gantoine.muxer.watch.WatchApproval
import com.gantoine.muxer.watch.WatchProtocol
import com.gantoine.muxer.watch.WatchSummary
import com.gantoine.muxer.watch.WatchTileContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface PhoneState {
    data object Loading : PhoneState
    data class Ready(val summary: WatchSummary?) : PhoneState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = PhoneLink(this)
        setContent { MuxerWatchApp(link) }
    }
}

@Composable
private fun MuxerWatchApp(link: PhoneLink) {
    val states = remember(link) { link.summaries().map { PhoneState.Ready(it) } }
    val state by states.collectAsStateWithLifecycle(initialValue = PhoneState.Loading)
    val navController = rememberSwipeDismissableNavController()
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = "list") {
                composable("list") {
                    AgentsScreen(state, onOpen = { navController.navigate("approval/${Uri.encode(it.id)}") })
                }
                composable("approval/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val summary = (state as? PhoneState.Ready)?.summary
                    ApprovalScreen(
                        approval = summary?.approvals?.firstOrNull { it.id == id },
                        link = link,
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchList(content: TransformingLazyColumnScope.() -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, content = content)
    }
}

@Composable
private fun AgentsScreen(state: PhoneState, onOpen: (WatchApproval) -> Unit) {
    WatchList {
        when (state) {
            PhoneState.Loading -> item { Message("Loading") }
            is PhoneState.Ready -> {
                val summary = state.summary
                if (summary == null) {
                    item { ListHeader { Text("Phone not reachable") } }
                    item { Message("Open Muxer on your phone and keep it near your watch.") }
                } else {
                    agents(summary, onOpen)
                }
            }
        }
    }
}

private fun TransformingLazyColumnScope.agents(summary: WatchSummary, onOpen: (WatchApproval) -> Unit) {
    if (summary.approvals.isEmpty()) {
        item { ListHeader { Text(WatchTileContent.of(summary) { "" }.headline) } }
    } else {
        item { ListHeader { Text("Waiting for you") } }
        items(summary.approvals, key = { it.macId + it.id }) { approval ->
            Button(
                onClick = { onOpen(approval) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(approval.title, maxLines = 2) },
                secondaryLabel = { Text(approval.workspace, maxLines = 1) },
            )
        }
    }
    if (summary.working.isNotEmpty()) {
        item { ListHeader { Text("Working") } }
        items(summary.working) { agent -> Message(WatchTileContent.row(agent.agent, agent.workspace)) }
    }
}

@Composable
private fun ApprovalScreen(approval: WatchApproval?, link: PhoneLink, onDone: () -> Unit) {
    var shown by remember { mutableStateOf(approval) }
    if (approval != null && shown == null) shown = approval
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WatchAnswerResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(result) {
        if (result?.ok == true) {
            delay(CLOSE_AFTER_SUCCESS_MS)
            onDone()
        }
    }
    WatchList {
        val current = shown
        if (current == null) {
            item { Message("Already answered.") }
            return@WatchList
        }
        item { ListHeader { Text(current.title, textAlign = TextAlign.Center) } }
        if (current.summary.isNotBlank()) item { Message(current.summary) }
        val choices = WatchProtocol.choices(current)
        if (choices.isEmpty()) item { Message("Answer this one on your phone.") }
        if (result?.ok != true) {
            items(choices) { choice ->
                Button(
                    onClick = {
                        sending = true
                        result = null
                        scope.launch {
                            result = link.answer(WatchProtocol.answer(current, choice))
                            sending = false
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(choice.label) },
                )
            }
        }
        if (sending) item { Message("Sending…") }
        result?.let { item { Message(if (it.ok) "Sent" else it.message ?: "Your answer was not delivered.") } }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private const val CLOSE_AFTER_SUCCESS_MS = 1_200L
