package com.gantoine.muxer.notifications

import com.gantoine.muxer.MuxerApplication

data class ApprovalDecision(val mode: String? = null, val answer: String? = null)

private const val WAKE_MS = 30_000L
private const val CONNECT_TIMEOUT_MS = 8_000L

suspend fun MuxerApplication.answerApproval(target: ApprovalTarget, decision: ApprovalDecision): Result<Unit> {
    notifier.dismissApproval(target.approvalId)
    if (store.find(target.macId) == null) {
        return Result.failure(IllegalStateException("This computer is not paired anymore."))
    }
    connections.wake(WAKE_MS)
    val connection = connections.awaitConnected(target.macId, CONNECT_TIMEOUT_MS)
        ?: return Result.failure(IllegalStateException("Couldn't reach the computer."))
    return when {
        decision.mode != null -> connection.decide(target.approvalId, decision.mode)
        decision.answer != null -> connection.answer(target.approvalId, listOf(decision.answer))
        else -> Result.success(Unit)
    }
}
