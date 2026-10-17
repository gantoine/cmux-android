package com.gantoine.muxer.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.R
import com.gantoine.muxer.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NotificationsTileService : TileService() {
    private var listening: Job? = null

    private val app get() = application as MuxerApplication

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = app.scope.launch(Dispatchers.Main) {
            app.tileStatus.collect(::show)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!app.notifier.canNotify()) {
            openApp()
            return
        }
        app.scope.launch {
            val macs = app.store.current()
            val enabled = NotificationsTile.backgroundAfterToggle(macs)
            macs.forEach { app.store.setBackgroundNotifications(it.id, enabled) }
        }
    }

    private fun show(status: TileStatus) {
        val tile = qsTile ?: return
        tile.state = when (status.state) {
            TileState.Active -> Tile.STATE_ACTIVE
            TileState.Inactive -> Tile.STATE_INACTIVE
            TileState.Unavailable -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = status.subtitle
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Widgets.openApp(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, NotificationsTileService::class.java)) }
                .onFailure { Log.w("cmux", "Could not refresh the quick settings tile", it) }
        }
    }
}
