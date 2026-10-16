package com.gantoine.muxer.notifications

import com.gantoine.muxer.protocol.ApprovalQuestion

object NotificationActions {
    private const val MAX_ACTIONS = 3

    fun oneTapAnswers(question: ApprovalQuestion): List<String> {
        if (question.multiSelect) return emptyList()
        val labels = question.options.map { it.label }
        return if (labels.size <= MAX_ACTIONS) labels else labels.take(MAX_ACTIONS - 1)
    }

    fun offersTypedAnswer(question: ApprovalQuestion): Boolean = oneTapAnswers(question).size < MAX_ACTIONS
}
