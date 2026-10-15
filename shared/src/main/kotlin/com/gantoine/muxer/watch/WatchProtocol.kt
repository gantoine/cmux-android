package com.gantoine.muxer.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WatchComputer(val macId: String, val name: String, val connected: Boolean)

@Serializable
data class WatchApproval(
    val id: String,
    val macId: String,
    val workspaceId: String,
    val surfaceId: String,
    val kind: String,
    val agent: String,
    val workspace: String,
    val title: String,
    val summary: String,
    val options: List<String> = emptyList(),
)

@Serializable
data class WatchAgent(val macId: String, val agent: String, val workspace: String)

@Serializable
data class WatchSummary(
    val computers: List<WatchComputer> = emptyList(),
    val approvals: List<WatchApproval> = emptyList(),
    val working: List<WatchAgent> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class WatchChoice(val label: String, val mode: String? = null, val answer: String? = null, val plan: Boolean = false)

@Serializable
data class WatchAnswer(
    val macId: String,
    val approvalId: String,
    val workspaceId: String,
    val surfaceId: String,
    val mode: String? = null,
    val answer: String? = null,
    val plan: Boolean = false,
)

@Serializable
data class WatchAnswerResult(val ok: Boolean, val message: String? = null)

object WatchProtocol {
    const val SUMMARY_PATH = "/muxer/summary"
    const val SUMMARY_KEY = "summary"
    const val ANSWER_PATH = "/muxer/answer"

    const val KIND_PERMISSION = "permission"
    const val KIND_PLAN = "plan"
    const val MODE_ONCE = "once"
    const val MODE_ALWAYS = "always"
    const val MODE_DENY = "deny"
    const val PLAN_MANUAL = "manual"
    const val PLAN_AUTO_ACCEPT = "autoAccept"
    const val PLAN_DENY = "deny"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun choices(approval: WatchApproval): List<WatchChoice> = when (approval.kind) {
        KIND_PERMISSION -> listOf(
            WatchChoice("Allow", mode = MODE_ONCE),
            WatchChoice("Always", mode = MODE_ALWAYS),
            WatchChoice("Deny", mode = MODE_DENY),
        )
        KIND_PLAN -> listOf(
            WatchChoice("Approve", mode = PLAN_MANUAL, plan = true),
            WatchChoice("Auto-edit", mode = PLAN_AUTO_ACCEPT, plan = true),
            WatchChoice("Reject", mode = PLAN_DENY, plan = true),
        )
        else -> approval.options.map { WatchChoice(it, answer = it) }
    }

    fun answer(approval: WatchApproval, choice: WatchChoice) = WatchAnswer(
        macId = approval.macId,
        approvalId = approval.id,
        workspaceId = approval.workspaceId,
        surfaceId = approval.surfaceId,
        mode = choice.mode,
        answer = choice.answer,
        plan = choice.plan,
    )

    fun encodeSummary(summary: WatchSummary): String = json.encodeToString(WatchSummary.serializer(), summary)

    fun decodeSummary(encoded: String?): WatchSummary? =
        encoded?.let { runCatching { json.decodeFromString(WatchSummary.serializer(), it) }.getOrNull() }

    fun encodeAnswer(answer: WatchAnswer): ByteArray = json.encodeToString(WatchAnswer.serializer(), answer).encodeToByteArray()

    fun decodeAnswer(bytes: ByteArray?): WatchAnswer? =
        bytes?.let { runCatching { json.decodeFromString(WatchAnswer.serializer(), it.decodeToString()) }.getOrNull() }

    fun encodeResult(result: WatchAnswerResult): ByteArray = json.encodeToString(WatchAnswerResult.serializer(), result).encodeToByteArray()

    fun decodeResult(bytes: ByteArray?): WatchAnswerResult =
        bytes?.let { runCatching { json.decodeFromString(WatchAnswerResult.serializer(), it.decodeToString()) }.getOrNull() }
            ?: WatchAnswerResult(ok = false, message = "Your phone sent an answer the watch could not read.")
}
