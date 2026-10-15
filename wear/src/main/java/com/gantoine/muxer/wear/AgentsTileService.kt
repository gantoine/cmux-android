package com.gantoine.muxer.wear

import android.content.ComponentName
import android.text.format.DateFormat
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.gantoine.muxer.watch.WatchTileContent
import com.google.common.util.concurrent.ListenableFuture
import java.util.Date

class AgentsTileService : TileService() {
    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> =
        SuspendToFutureAdapter.launchFuture {
            val content = WatchTileContent.of(PhoneLink(this@AgentsTileService).summary(), ::formatTime)
            Tile.Builder()
                .setTileTimeline(Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration, content)))
                .build()
        }

    private fun layout(device: DeviceParameters, content: WatchTileContent): LayoutElement {
        val open = openApp()
        return materialScope(this, device) {
            primaryLayout(
                titleSlot = { text((content.footer ?: getString(R.string.app_name)).layoutString, maxLines = 1) },
                mainSlot = { body(content) },
                bottomSlot = { textEdgeButton(onClick = open) { text("Open".layoutString) } },
                onClick = open,
            )
        }
    }

    private fun MaterialScope.body(content: WatchTileContent): LayoutElement {
        val column = Column.Builder()
            .setWidth(expand())
            .addContent(text(content.headline.layoutString, typography = Typography.TITLE_MEDIUM, maxLines = 2))
        content.rows.forEach { row ->
            column.addContent(text(row.layoutString, typography = Typography.BODY_SMALL, maxLines = 1))
        }
        return column.build()
    }

    private fun formatTime(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun openApp(): Clickable = clickable(launchAction(ComponentName(this, MainActivity::class.java)), "open")
}
