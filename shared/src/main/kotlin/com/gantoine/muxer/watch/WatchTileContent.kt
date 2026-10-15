package com.gantoine.muxer.watch

data class WatchTileContent(val headline: String, val rows: List<String>, val footer: String?) {
    companion object {
        const val MAX_ROWS = 3

        fun of(summary: WatchSummary?, formatTime: (Long) -> String): WatchTileContent {
            if (summary == null) return WatchTileContent("Phone not reachable", emptyList(), null)
            val footer = "Updated ${formatTime(summary.updatedAt)}"
            val waiting = summary.approvals.size
            if (waiting > 0) {
                val rows = summary.approvals.take(MAX_ROWS).map { row(it.agent, it.workspace) }
                val headline = if (waiting == 1) "1 agent waiting" else "$waiting agents waiting"
                return WatchTileContent(headline, rows, footer)
            }
            return WatchTileContent(quietHeadline(summary), workingRows(summary), footer)
        }

        fun row(agent: String, workspace: String): String =
            listOf(agent, workspace).filter { it.isNotBlank() }.joinToString(" · ")

        private fun quietHeadline(summary: WatchSummary): String = when {
            summary.computers.isEmpty() -> "No computers paired"
            summary.computers.none { it.connected } -> "Not connected"
            else -> "All quiet"
        }

        private fun workingRows(summary: WatchSummary): List<String> = when (val working = summary.working.size) {
            0 -> emptyList()
            1 -> listOf("${row(summary.working.single().agent, summary.working.single().workspace)} is working")
            else -> listOf("$working agents working")
        }
    }
}
