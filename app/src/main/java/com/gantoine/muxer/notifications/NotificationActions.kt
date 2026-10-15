package com.gantoine.muxer.notifications

import com.gantoine.muxer.protocol.ApprovalQuestion

object NotificationActions {
    private const val MAX_ACTIONS = 3
    private const val TYPED_ANSWER_ACTIONS = 1

    fun oneTapAnswers(question: ApprovalQuestion): List<String> {
        val labels = question.options.map { it.label }
        val fits = labels.size in 1..MAX_ACTIONS - TYPED_ANSWER_ACTIONS
        return if (!question.multiSelect && fits) labels else emptyList()
    }
}
