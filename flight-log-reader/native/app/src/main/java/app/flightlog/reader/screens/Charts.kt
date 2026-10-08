package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.flightlog.core.Downsample
import app.flightlog.core.Series

/** Прореженный ряд в координатах 0..1 по времени и значению. */
class ChartData(val x: FloatArray, val y: FloatArray, val lo: Float, val hi: Float)

fun chartData(s: Series, duration: Float, buckets: Int, conv: (Float) -> Float = { it }, loHi: Pair<Float, Float>? = null): ChartData {
    val d = Downsample.minMax(s, buckets, 0f, duration)
    val v = FloatArray(d.values.size) { conv(d.values[it]) }
    var lo = loHi?.first ?: (v.minOrNull() ?: 0f)
    var hi = loHi?.second ?: (v.maxOrNull() ?: 1f)
    if (hi - lo < 1e-3f) { hi += 0.5f; lo -= 0.5f }
    val pad = (hi - lo) * 0.08f
    lo -= pad; hi += pad
    return ChartData(FloatArray(d.time.size) { d.time[it] / duration }, FloatArray(v.size) { (v[it] - lo) / (hi - lo) }, lo, hi)
}

fun DrawScope.drawSeries(d: ChartData, line: Color, fill: Color?, width: Float, dash: Boolean = false) {
    if (d.x.isEmpty()) return
    val p = Path()
    for (i in d.x.indices) {
        val px = d.x[i] * size.width
        val py = size.height - d.y[i] * size.height
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    if (fill != null) {
        val f = Path().apply {
            addPath(p)
            lineTo(d.x.last() * size.width, size.height)
            lineTo(d.x.first() * size.width, size.height)
            close()
        }
        drawPath(f, fill)
    }
    drawPath(p, line, style = Stroke(width, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
}

fun DrawScope.drawThreshold(d: ChartData, value: Float, color: Color) {
    if (value < d.lo || value > d.hi) return
    val y = size.height - (value - d.lo) / (d.hi - d.lo) * size.height
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
}

fun DrawScope.drawGrid(color: Color, step: Float) {
    var x = step
    while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = step
    while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

/** График канала с общим курсором; тап/перетаскивание — перемотка. */
@Composable
fun SeriesChart(
    data: ChartData,
    cursor: Float,
    line: Color,
    fill: Color,
    cursorColor: Color,
    threshold: Float?,
    thresholdColor: Color,
    onSeek: (Float) -> Unit,
    height: Dp = 84.dp,
) {
    Canvas(
        Modifier.fillMaxWidth().height(height)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        drawSeries(data, line, fill, 1.5.dp.toPx())
        threshold?.let { drawThreshold(data, it, thresholdColor) }
        val cx = cursor * size.width
        drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
    }
}

@Composable
fun rememberChart(s: Series, duration: Float, buckets: Int, conv: (Float) -> Float, key: Any): ChartData =
    remember(s, duration, buckets, key) { chartData(s, duration, buckets, conv) }
