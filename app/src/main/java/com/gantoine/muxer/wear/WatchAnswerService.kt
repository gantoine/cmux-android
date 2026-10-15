package com.gantoine.muxer.wear

import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.notifications.ApprovalDecision
import com.gantoine.muxer.notifications.ApprovalTarget
import com.gantoine.muxer.notifications.answerApproval
import com.gantoine.muxer.watch.WatchAnswer
import com.gantoine.muxer.watch.WatchAnswerResult
import com.gantoine.muxer.watch.WatchProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

class WatchAnswerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray> {
        if (path != WatchProtocol.ANSWER_PATH) return reply(WatchAnswerResult(ok = false, message = "The phone does not know this request."))
        val answer = WatchProtocol.decodeAnswer(request)
            ?: return reply(WatchAnswerResult(ok = false, message = "The phone could not read this answer. Update Muxer on both devices."))
        val app = application as MuxerApplication
        val response = TaskCompletionSource<ByteArray>()
        app.scope.launch {
            val result = runCatching { app.answerApproval(answer.target(), answer.decision()) }.getOrElse { Result.failure(it) }
            val reply = result.fold(
                onSuccess = { WatchAnswerResult(ok = true) },
                onFailure = { WatchAnswerResult(ok = false, message = it.message ?: "The computer did not accept it.") },
            )
            response.setResult(WatchProtocol.encodeResult(reply))
        }
        return response.task
    }

    private fun reply(result: WatchAnswerResult): Task<ByteArray> = Tasks.forResult(WatchProtocol.encodeResult(result))

    private fun WatchAnswer.target() = ApprovalTarget(macId, approvalId, workspaceId, surfaceId)

    private fun WatchAnswer.decision() = ApprovalDecision(mode = mode, answer = answer, plan = plan)
}
