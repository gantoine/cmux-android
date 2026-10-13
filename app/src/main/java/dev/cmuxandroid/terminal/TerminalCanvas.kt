package dev.cmuxandroid.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cmuxandroid.protocol.CellStyle
import kotlin.math.abs

data class CellMetrics(val width: Float, val height: Float)

@Composable
fun rememberCellMetrics(measurer: TextMeasurer, fontSize: TextUnit): CellMetrics = remember(measurer, fontSize) {
    val sample = "M".repeat(SAMPLE_LENGTH)
    val layout = measurer.measure(sample, TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize))
    CellMetrics(width = layout.size.width.toFloat() / SAMPLE_LENGTH, height = layout.size.height.toFloat())
}

@Composable
fun TerminalCanvas(
    screen: TerminalScreen,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onScrollLines: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    val metrics = rememberCellMetrics(measurer, fontSize.sp)
    val colors = remember { ColorCache() }
    val density = LocalDensity.current
    val currentFontSize = rememberUpdatedState(fontSize)
    val currentMetrics = rememberUpdatedState(metrics)
    val widthDp = with(density) { (metrics.width * screen.columns).toDp() }
    val contentHeight = metrics.height * screen.rows
    val heightDp = with(density) { contentHeight.toDp() }

    BoxWithConstraints(
        modifier = modifier
            .background(colors[screen.background] ?: Color.Black)
            .clipToBounds(),
    ) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val hiddenRows = (contentHeight - viewportHeight).coerceAtLeast(0f)
        val cursorBottom = ((screen.cursor?.row ?: 0) + 2) * metrics.height
        val scrollY = (cursorBottom - viewportHeight).coerceIn(0f, hiddenRows).roundToInt()
        TerminalGestures(
            onFontSizeChange = onFontSizeChange,
            onScrollLines = onScrollLines,
            onTap = onTap,
            currentFontSize = currentFontSize,
            currentMetrics = currentMetrics,
            modifier = Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset { IntOffset(0, -scrollY) },
        ) {
            Canvas(modifier = Modifier.size(widthDp, heightDp)) {
                drawTerminal(screen, metrics, measurer, colors, fontSize.sp)
            }
        }
    }
}

@Composable
private fun TerminalGestures(
    onFontSizeChange: (Float) -> Unit,
    onScrollLines: (Int) -> Unit,
    onTap: () -> Unit,
    currentFontSize: State<Float>,
    currentMetrics: State<CellMetrics>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalTravel = 0f
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                onFontSizeChange((currentFontSize.value * zoom).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
                                event.changes.forEach { it.consume() }
                                moved = true
                            }
                        } else {
                            val change = event.changes.first()
                            val delta = change.positionChange()
                            if (abs(delta.y) > abs(delta.x)) {
                                verticalTravel += delta.y
                                val lineHeight = currentMetrics.value.height
                                val lines = (verticalTravel / lineHeight).toInt()
                                if (lines != 0) {
                                    onScrollLines(-lines)
                                    verticalTravel -= lines * lineHeight
                                    change.consume()
                                }
                            }
                            if (delta.getDistance() > 2f) moved = true
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved) onTap()
                }
            },
    ) {
        content()
    }
}

private fun DrawScope.drawTerminal(
    screen: TerminalScreen,
    metrics: CellMetrics,
    measurer: TextMeasurer,
    colors: ColorCache,
    fontSize: TextUnit,
) {
    val defaultBackground = colors[screen.background] ?: Color.Black
    val defaultForeground = colors[screen.foreground] ?: Color.White
    drawRect(defaultBackground, size = size)

    screen.lines.forEachIndexed { row, spans ->
        val top = row * metrics.height
        for (span in spans) {
            val style = screen.styles[span.style]
            var foreground = style?.let { colors[it.fg] } ?: defaultForeground
            var background = style?.let { colors[it.bg] } ?: defaultBackground
            if (style?.inverse == true) foreground = background.also { background = foreground }
            val left = span.column * metrics.width
            val width = span.width * metrics.width
            if (background != defaultBackground) {
                drawRect(background, topLeft = Offset(left, top), size = Size(width, metrics.height))
            }
            if (style?.invisible == true || span.text.isBlank()) continue
            if (style?.faint == true) foreground = foreground.copy(alpha = 0.6f)
            drawText(
                textMeasurer = measurer,
                text = span.text,
                topLeft = Offset(left, top),
                style = textStyle(style, foreground, fontSize),
                softWrap = false,
            )
        }
    }

    val cursor = screen.cursor ?: return
    if (!cursor.visible || cursor.row !in 0 until screen.rows) return
    val cursorColor = defaultForeground.copy(alpha = 0.7f)
    val left = cursor.column * metrics.width
    val top = cursor.row * metrics.height
    val stroke = 2.dp.toPx()
    when (cursor.shape) {
        "bar" -> drawRect(cursorColor, Offset(left, top), Size(stroke, metrics.height))
        "underline" -> drawRect(cursorColor, Offset(left, top + metrics.height - stroke), Size(metrics.width, stroke))
        "block_hollow" -> drawRect(
            cursorColor, Offset(left, top), Size(metrics.width, metrics.height),
            style = Stroke(stroke),
        )
        else -> drawRect(cursorColor, Offset(left, top), Size(metrics.width, metrics.height))
    }
}

private fun textStyle(style: CellStyle?, color: Color, fontSize: TextUnit): TextStyle {
    val decorations = buildList {
        if (style?.underline == true) add(TextDecoration.Underline)
        if (style?.strikethrough == true) add(TextDecoration.LineThrough)
    }
    return TextStyle(
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        fontWeight = if (style?.bold == true) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (style?.italic == true) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

class ColorCache {
    private val cache = HashMap<String, Color?>()

    operator fun get(hex: String): Color? = cache.getOrPut(hex) { parseHexColor(hex) }
}

fun parseHexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(((value and 0xFF) shl 24) or (value ushr 8))
        else -> null
    }
}

fun fittingGrid(widthPx: Int, heightPx: Int, metrics: CellMetrics): Pair<Int, Int> =
    (widthPx / metrics.width).toInt().coerceIn(20, 300) to (heightPx / metrics.height).toInt().coerceIn(5, 120)

const val MIN_FONT_SIZE = 6f
const val MAX_FONT_SIZE = 24f
private const val SAMPLE_LENGTH = 20
