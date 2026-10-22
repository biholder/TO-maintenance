package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Analyzer
import app.flightlog.core.Batteries
import app.flightlog.core.Ch
import app.flightlog.core.Downsample
import app.flightlog.core.FlightLog
import app.flightlog.core.LogReader
import app.flightlog.core.Series
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.FullChart
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.clock
import app.flightlog.reader.num
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.ModeBands
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.card
import app.flightlog.reader.ui.Radius
import app.flightlog.reader.ui.tk

/** Линия полноэкранного графика. */
private class Line(val series: Series, val color: Color, val dashed: Boolean = false)

/** Что рисовать: заголовок, линии, общая ли шкала, порог. */
private class FullSpec(val title: String, val lines: List<Line>, val shared: Boolean, val threshold: Float?, val fill: Boolean)

@Composable
private fun spec(vm: AppViewModel, log: FlightLog, chart: FullChart): FullSpec? {
    val colors = listOf(tk.ac, tk.wr, tk.ok, tk.cr, Color(0xFF8E6CD9), Color(0xFF2BB3C9))
    val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
    return when (chart) {
        is FullChart.Channel -> {
            val s = log.series[chart.key] ?: return null
            val vibe = chart.key.startsWith("vibe")
            val threshold = when {
                vibe -> Analyzer.VIBE_WARN
                chart.key == Ch.VOLT -> lowV
                chart.key == Ch.EKF -> Analyzer.EKF_WARN
                chart.key == Ch.RSSI -> Analyzer.RSSI_WARN
                else -> null
            }
            FullSpec(s.label, listOf(Line(s, if (vibe) tk.wr else tk.ac)), true, threshold, true)
        }
        FullChart.Overlay -> {
            val ss = vm.overlay.mapNotNull { log.series[it] }
            if (ss.isEmpty()) return null
            val shared = ss.map { it.unit }.distinct().size == 1 && vm.overlayShared
            FullSpec("Наложение графиков", ss.mapIndexed { i, s -> Line(s, colors[i % colors.size]) }, shared, null, false)
        }
        FullChart.Batteries -> {
            val packs = Batteries.packs(log)
            val metrics = Batteries.metrics(log, packs)
            if (metrics.isEmpty()) return null
            val m = metrics[vm.batMetric.coerceIn(0, metrics.size - 1)]
            val lines = packs.mapIndexedNotNull { i, p ->
                log.series[Batteries.key(p, m)]?.let { Line(it, colors[i % colors.size], dashed = i % 2 == 1) }
            }
            FullSpec("Сравнение АКБ · ${m.title}", lines, true, if (m == Batteries.Metric.VOLT) lowV else null, false)
        }
    }
}

private fun digitsFor(unit: String) = when (unit) {
    "" -> 2
    "мкс", "мГс", "%", "мА·ч" -> 0
    else -> 1
}

/**
 * График на весь экран: оси с подписями, касание и перетаскивание — перемотка,
 * два пальца — масштаб и сдвиг по времени.
 */
@Composable
fun FullChartView(vm: AppViewModel, p: LogReader.Parsed, chart: FullChart, modifier: Modifier) {
    val log = p.log
    val sp = spec(vm, log, chart)
    if (sp == null) {
        // Канал пропал (например, очищено наложение) — закрыть.
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.fullChart = null }
        return
    }
    val u = UnitFmt(vm.units)
    val duration = log.duration.coerceAtLeast(1f)
    // Видимое окно времени, с.
    val window = remember(p, chart) { mutableStateOf(0f to duration) }
    val (from, to) = window.value
    val bands = remember(log) { ModeBands.of(log) }
    val widthPx = remember { mutableStateOf(800) }
    val t = vm.tSec

    // Точки в окне в единицах отображения.
    val pts = remember(sp.lines.map { it.series }, from, to, widthPx.value, vm.units) {
        sp.lines.map { l ->
            val d = Downsample.minMax(l.series, (widthPx.value / 2).coerceAtLeast(50), from, to)
            d.time to FloatArray(d.values.size) { u.channel(d.values[it], l.series.unit) }
        }
    }
    // Шкала: по видимым точкам; общая — одна на все линии.
    fun range(i: Int): Pair<Float, Float> {
        val idx = if (sp.shared) pts.indices.toList() else listOf(i)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (k in idx) {
            val (tt, vv) = pts[k]
            for (j in tt.indices) if (tt[j] in from..to) { lo = minOf(lo, vv[j]); hi = maxOf(hi, vv[j]) }
        }
        val th = sp.threshold?.let { u.channel(it, sp.lines[0].series.unit) }
        if (sp.shared && th != null) { lo = minOf(lo, th); hi = maxOf(hi, th) }
        if (lo > hi) { lo = 0f; hi = 1f }
        if (hi - lo < 1e-3f) { hi += 0.5f; lo -= 0.5f }
        val pad = (hi - lo) * 0.06f
        return (lo - pad) to (hi + pad)
    }
    val ranges = pts.indices.map { range(it) }

    val measurer = rememberTextMeasurer()
    val mu = tk.mu; val grid = tk.grid; val tx = tk.tx; val cr = tk.cr; val dv = tk.dv
    val unit0 = sp.lines.first().series.unit

    Column(modifier.fillMaxSize().background(tk.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(onClick = { vm.fullChart = null }, bordered = false) { Icon(Icons.X, tk.tx) }
            T(sp.title, Type.cond(20.sp), modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1)
            if (from > 0f || to < duration) {
                Box(Modifier.card(tk.bg, tk.dv, Radius.S).clickable { window.value = 0f to duration }
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    T("Весь полёт", Type.body(12.sp, FontWeight.Medium))
                }
            }
        }
        // Легенда со значениями в позиции курсора.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            sp.lines.forEach { l ->
                val s = l.series
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(16.dp).height(3.dp).background(l.color, RoundedCornerShape(2.dp)))
                    T(if (sp.lines.size > 1) s.label else "Значение", Type.body(13.sp), tk.mu, Modifier.padding(start = 6.dp))
                    T(" " + num(s.valueAt(t)?.let { u.channel(it, s.unit) }, digitsFor(s.unit)), Type.cond(18.sp), l.color)
                    T(" ${u.channelUnit(s.unit)}", Type.body(12.sp), tk.mu)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .onSizeChanged { widthPx.value = it.width }) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(p, chart) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val left = 48.dp.toPx()
                        fun seek(x: Float) {
                            val (a, b) = window.value
                            val w = (size.width - left).coerceAtLeast(1f)
                            vm.seekSec(a + ((x - left) / w).coerceIn(0f, 1f) * (b - a))
                        }
                        seek(down.position.x)
                        var multi = false
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2) {
                                multi = true
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid(useCurrent = true)
                                val (a, b) = window.value
                                val w = (size.width - left).coerceAtLeast(1f)
                                val frac = ((c.x - left) / w).coerceIn(0f, 1f)
                                val anchor = a + frac * (b - a)
                                val span = ((b - a) / zoom).coerceIn(minOf(2f, duration), duration)
                                var na = anchor - frac * span - pan.x / w * span
                                na = na.coerceIn(0f, duration - span)
                                window.value = na to na + span
                            } else if (!multi) {
                                ev.changes.firstOrNull { it.pressed }?.let { seek(it.position.x) }
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                },
            ) {
                val left = 48.dp.toPx()
                val bottom = 20.dp.toPx()
                val w = size.width - left
                val h = size.height - bottom
                val span = (to - from).coerceAtLeast(1e-3f)
                fun x(time: Float) = left + (time - from) / span * w
                val labelStyle = Type.body(11.sp).copy(color = mu)

                // Полосы режимов в окне.
                for (sg in bands.segments) {
                    val a = maxOf(sg.from * duration, from); val b = minOf(sg.to * duration, to)
                    if (b > a) drawRect(bands.color(sg.mode).copy(alpha = 0.10f), Offset(x(a), 0f), Size(x(b) - x(a), h))
                }
                // Сетка и подписи по вертикали (одна шкала).
                val (lo0, hi0) = ranges.first()
                for (k in 0..4) {
                    val y = h - h * k / 4f
                    drawLine(grid, Offset(left, y), Offset(size.width, y), 1f)
                    if (sp.shared || sp.lines.size == 1) {
                        val v = lo0 + (hi0 - lo0) * k / 4f
                        val txt = measurer.measure(num(v, digitsFor(unit0)), labelStyle)
                        drawText(txt, topLeft = Offset(left - txt.size.width - 6.dp.toPx(), (y - txt.size.height / 2f).coerceIn(0f, h - txt.size.height)))
                    }
                }
                // Время по горизонтали.
                for (k in 0..4) {
                    val tm = from + span * k / 4f
                    val xx = x(tm)
                    drawLine(grid, Offset(xx, 0f), Offset(xx, h), 1f)
                    val txt = measurer.measure(clock(tm), labelStyle)
                    drawText(txt, topLeft = Offset((xx - txt.size.width / 2f).coerceIn(left, size.width - txt.size.width), h + 4.dp.toPx()))
                }
                drawLine(dv, Offset(left, h), Offset(size.width, h), 1f)

                clipRect(left, 0f, size.width, h) {
                    sp.threshold?.let { th ->
                        val v = u.channel(th, unit0)
                        if (v in lo0..hi0) {
                            val y = h - (v - lo0) / (hi0 - lo0) * h
                            drawLine(cr, Offset(left, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                        }
                    }
                    sp.lines.forEachIndexed { i, l ->
                        val (tt, vv) = pts[i]
                        if (tt.isEmpty()) return@forEachIndexed
                        val (lo, hi) = ranges[i]
                        val path = Path()
                        for (j in tt.indices) {
                            val px = x(tt[j]); val py = h - (vv[j] - lo) / (hi - lo) * h
                            if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        if (sp.fill) {
                            val f = Path().apply { addPath(path); lineTo(x(tt.last()), h); lineTo(x(tt.first()), h); close() }
                            drawPath(f, l.color.copy(alpha = 0.12f))
                        }
                        drawPath(path, l.color, style = Stroke(1.8.dp.toPx(),
                            pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
                    }
                    if (t in from..to) drawLine(tx, Offset(x(t), 0f), Offset(x(t), h), 1.dp.toPx())
                }
            }
        }
        T(if (from > 0f || to < duration) "Двумя пальцами — масштаб и сдвиг по времени" else "Касание — перемотка · двумя пальцами — масштаб по времени",
            Type.body(11.sp), tk.mu, Modifier.padding(start = 16.dp, bottom = 4.dp))
    }
}
