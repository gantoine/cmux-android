package com.gantoine.muxer.notifications

import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.model.ApprovalReply

private const val WAKE_MS = 30_000L

suspend fun MuxerApplication.answerApproval(target: ApprovalTarget, reply: ApprovalReply): Result<Unit> {
    notifier.dismissApproval(target.approvalId)
    if (store.find(target.macId) == null) {
        return Result.failure(IllegalStateException("This computer is not paired anymore."))
    }
    val connection = connections.wakeAndAwait(target.macId, WAKE_MS)
        ?: return Result.failure(IllegalStateException("Couldn't reach the computer."))
    return connection.reply(target.approvalId, reply)
}
