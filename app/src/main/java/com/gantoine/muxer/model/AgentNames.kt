package com.gantoine.muxer.model

object AgentNames {
    private val known = mapOf(
        "claude" to "Claude",
        "codex" to "Codex",
        "pi" to "Pi",
        "amp" to "Amp",
        "opencode" to "OpenCode",
        "gemini" to "Gemini",
    )

    fun display(source: String?): String? {
        val first = source?.split('-', '_', ' ', '.')?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return known[first.lowercase()] ?: first.replaceFirstChar { it.uppercase() }
    }

    fun displayOrDefault(source: String?): String = display(source) ?: "The agent"
}
