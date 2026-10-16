package com.gantoine.muxer.net

import com.gantoine.muxer.protocol.Approval
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

class ApprovalAlerts(
    private val alert: suspend (List<Approval>) -> Unit,
    private val dismiss: (String) -> Unit,
    private val outliveDropsForMs: Long = OUTLIVE_DROPS_FOR_MS,
) {
    private var shown = emptySet<String>()

    suspend fun follow(approvals: Flow<List<Approval>?>) {
        try {
            approvals.collectLatest { received ->
                if (received == null) {
                    delay(outliveDropsForMs)
                    settle(emptyList())
                } else {
                    settle(received)
                }
            }
        } finally {
            shown.forEach(dismiss)
            shown = emptySet()
        }
    }

    private suspend fun settle(approvals: List<Approval>) = withContext(NonCancellable) {
        val ids = approvals.map { it.id }.toSet()
        (shown - ids).forEach(dismiss)
        alert(approvals.filter { it.id !in shown })
        shown = ids
    }

    private companion object {
        const val OUTLIVE_DROPS_FOR_MS = 60_000L
    }
}
