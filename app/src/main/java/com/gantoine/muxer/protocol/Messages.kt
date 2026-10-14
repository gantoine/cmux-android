package com.gantoine.muxer.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(ExperimentalSerializationApi::class)
val RelayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

@Serializable
data class Welcome(
    val macName: String,
    val relayVersion: String,
    val protocolVersion: Int,
    val cmuxVersion: String,
    val deviceId: String,
    val notificationSeq: Long = 0,
)

@Serializable
data class TerminalInfo(
    val id: String,
    val title: String = "",
    val currentDirectory: String? = null,
    val isFocused: Boolean = false,
    val agentSource: String? = null,
    val agentState: String? = null,
)

@Serializable
data class WorkspaceInfo(
    val id: String,
    val windowId: String? = null,
    val title: String = "",
    val description: String? = null,
    val customColor: String? = null,
    val currentDirectory: String? = null,
    val isSelected: Boolean = false,
    val isPinned: Boolean = false,
    val preview: String? = null,
    val previewAt: Double? = null,
    val lastActivityAt: Double? = null,
    val hasUnread: Boolean = false,
    val unreadCount: Int = 0,
    val terminals: List<TerminalInfo> = emptyList(),
)

@Serializable
data class WorkspacesMessage(val workspaces: List<WorkspaceInfo>)

@Serializable
data class CellStyle(
    val id: Int,
    val fg: String,
    val bg: String,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val inverse: Boolean = false,
    val invisible: Boolean = false,
)

@Serializable
data class Span(val column: Int, val width: Int, val style: Int, val text: String)

@Serializable
data class RowUpdate(val row: Int, val spans: List<Span>)

@Serializable
data class CursorInfo(val row: Int, val column: Int, val visible: Boolean, val shape: String = "block")

@Serializable
data class ScreenFrame(
    val workspaceId: String,
    val surfaceId: String,
    val full: Boolean,
    val columns: Int,
    val rows: Int,
    val foreground: String,
    val background: String,
    val altScreen: Boolean = false,
    val cursor: CursorInfo? = null,
    val styles: List<CellStyle> = emptyList(),
    val changed: List<RowUpdate> = emptyList(),
)

@Serializable
data class HistoryResponse(
    val styles: List<CellStyle> = emptyList(),
    val lines: List<List<Span>> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class DetachedMessage(val surfaceId: String, val reason: String = "")

@Serializable
data class RelayNotification(
    val seq: Long,
    val kind: String,
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val workspaceId: String? = null,
    val surfaceId: String? = null,
    val tabTitle: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class NotificationMessage(val notification: RelayNotification)

@Serializable
data class RequestError(val code: String, val message: String = "")

@Serializable
data class PairResponse(
    val token: String,
    val deviceId: String,
    val macName: String,
    val protocolVersion: Int,
)

@Serializable
data class ErrorResponse(val error: RequestError)

@Serializable
data class CreatedWorkspace(val workspaceId: String = "", val surfaceId: String = "")

@Serializable
data class ChatTool(val name: String, val summary: String? = null, val status: String = "running", val output: String? = null)

@Serializable
data class ChatEntry(val id: String, val kind: String, val text: String? = null, val tool: ChatTool? = null, val time: String? = null)

@Serializable
data class ChatSessionMessage(val surfaceId: String, val sessionId: String? = null)

@Serializable
data class ChatMessage(val surfaceId: String, val sessionId: String, val reset: Boolean = false, val entries: List<ChatEntry> = emptyList())
