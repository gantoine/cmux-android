package com.gantoine.muxer.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gantoine.muxer.R
import com.gantoine.muxer.ui.BrandDarkColors
import com.gantoine.muxer.ui.BrandLightColors

private val fallbackColors = ColorProviders(light = BrandLightColors, dark = BrandDarkColors)

@Composable
fun MuxerWidgetTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallbackColors
    GlanceTheme(colors = colors, content = content)
}

@Composable
fun WidgetSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(bottom = 8.dp),
        content = content,
    )
}

@Composable
fun WidgetHeader(title: String, asOf: String?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.defaultWeight(),
        )
        asOf?.let { AsOf(it) }
        RefreshButton()
    }
}

@Composable
fun AsOf(text: String) {
    Text(text, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
}

@Composable
fun RefreshButton() {
    CircleIconButton(
        imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
        contentDescription = "Refresh",
        onClick = actionRunCallback<RefreshWidgetsAction>(),
        backgroundColor = null,
        contentColor = GlanceTheme.colors.onSurfaceVariant,
        modifier = GlanceModifier.size(40.dp),
    )
}

@Composable
fun secondaryText(size: Int = 12) = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size.sp)

class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Widgets.refreshNow(context)
    }
}
