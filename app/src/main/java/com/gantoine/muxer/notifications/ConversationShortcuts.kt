package com.gantoine.muxer.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import com.gantoine.muxer.MainActivity
import com.gantoine.muxer.R
import com.gantoine.muxer.data.MacSnapshot
import com.gantoine.muxer.protocol.WorkspaceInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import java.util.concurrent.ConcurrentHashMap

class ConversationShortcuts(private val context: Context) {
    private val known = ConcurrentHashMap<String, WorkspaceShortcut>()
    private var lastPlan: List<WorkspaceShortcut>? = null
    private var lastWorkspaceIds: Set<String>? = null

    @OptIn(FlowPreview::class)
    suspend fun keepInSync(snapshots: Flow<Map<String, MacSnapshot>>) {
        snapshots.debounce(SYNC_DEBOUNCE_MS).collect { current ->
            runCatching { sync(current.values) }
                .onFailure { Log.w("cmux", "Could not update conversation shortcuts", it) }
        }
    }

    fun agentName(macId: String, workspaceId: String): String? = known[id(macId, workspaceId)]?.agentName

    fun isAgentWorkspace(macId: String, workspaceId: String): Boolean = known.containsKey(id(macId, workspaceId))

    fun icon(macId: String, workspaceId: String): IconCompat =
        known[id(macId, workspaceId)]?.let(::iconFor) ?: appIcon()

    fun requestPin(macId: String, macName: String, workspace: WorkspaceInfo): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val shortcut = ShortcutPlan.shortcut(macId, macName, workspace) ?: return false
        known[shortcut.id] = shortcut
        val built = build(shortcut, rank = 0)
        ShortcutManagerCompat.enableShortcuts(context, listOf(built))
        return ShortcutManagerCompat.requestPinShortcut(context, built, null)
    }

    private fun sync(snapshots: Collection<MacSnapshot>) {
        snapshots.forEach { snapshot ->
            snapshot.workspaces.forEach { workspace ->
                ShortcutPlan.shortcut(snapshot.macId, snapshot.macName, workspace)?.let { known[it.id] = it }
            }
        }
        val plan = ShortcutPlan.plan(snapshots, limit())
        if (plan != lastPlan) {
            val built = plan.mapIndexed { rank, shortcut -> build(shortcut, rank) }
            ShortcutManagerCompat.enableShortcuts(context, built)
            if (ShortcutManagerCompat.setDynamicShortcuts(context, built)) lastPlan = plan
        }
        val workspaceIds = snapshots.flatMapTo(mutableSetOf()) { snapshot -> snapshot.workspaces.map { id(snapshot.macId, it.id) } }
        if (workspaceIds != lastWorkspaceIds) {
            removeGone(snapshots)
            lastWorkspaceIds = workspaceIds
        }
    }

    private fun removeGone(snapshots: Collection<MacSnapshot>) {
        val existing = ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED,
        ).map { it.id }.distinct()
        val gone = ShortcutPlan.gone(existing, snapshots)
        if (gone.isEmpty()) return
        gone.forEach { known.remove(it) }
        ShortcutManagerCompat.removeLongLivedShortcuts(context, gone)
        ShortcutManagerCompat.disableShortcuts(context, gone, "This workspace is closed.")
    }

    private fun limit(): Int = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_SHORTCUTS

    private fun build(shortcut: WorkspaceShortcut, rank: Int): ShortcutInfoCompat {
        val agent = Person.Builder()
            .setName(shortcut.agentName ?: "Agent")
            .setKey("agent:${shortcut.macId}:${shortcut.workspaceId}")
            .build()
        return ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(shortcut.title)
            .setLongLabel("${shortcut.title} · ${shortcut.macName}")
            .setIcon(iconFor(shortcut))
            .setIntent(openIntent(shortcut))
            .setLongLived(true)
            .setPerson(agent)
            .setCategories(ShortcutPlan.categories(shortcut))
            .setRank(rank)
            .build()
    }

    private fun openIntent(shortcut: WorkspaceShortcut): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Notifier.ACTION_OPEN_TERMINAL
            putExtra(Notifier.EXTRA_MAC_ID, shortcut.macId)
            putExtra(Notifier.EXTRA_WORKSPACE_ID, shortcut.workspaceId)
            putExtra(Notifier.EXTRA_SURFACE_ID, shortcut.surfaceId)
        }

    private fun iconFor(shortcut: WorkspaceShortcut): IconCompat {
        val letter = shortcut.title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return appIcon()
        val background = runCatching { shortcut.color.toColorInt() }.getOrNull() ?: return appIcon()
        val size = (ADAPTIVE_ICON_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight(background)) Color.BLACK else Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * LETTER_SCALE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter.toString(), size / 2f, baseline, paint)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    private fun isLight(color: Int): Boolean =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > LIGHT_THRESHOLD

    private fun appIcon(): IconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher)

    companion object {
        private const val STATIC_SHORTCUTS = 1
        private const val SYNC_DEBOUNCE_MS = 1_000L
        private const val ADAPTIVE_ICON_DP = 108
        private const val LETTER_SCALE = 0.3f
        private const val LIGHT_THRESHOLD = 160

        fun id(macId: String, workspaceId: String): String = ShortcutPlan.id(macId, workspaceId)

        fun reportUsed(context: Context, macId: String, workspaceId: String) {
            runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id(macId, workspaceId)) }
        }
    }
}
