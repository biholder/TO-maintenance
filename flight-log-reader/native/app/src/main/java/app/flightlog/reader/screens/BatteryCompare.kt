package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Batteries
import app.flightlog.core.FlightLog
import app.flightlog.core.plural
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.num
import app.flightlog.reader.signed
import app.flightlog.reader.ui.Chip
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk

/** Цвета АКБ на общем графике. */
@Composable
private fun packColors(): List<Color> = listOf(tk.ac, tk.wr, tk.ok, tk.cr, tk.mu)

private fun packColor(colors: List<Color>, i: Int) = colors[i % colors.size]

/** Карточка сравнения нескольких АКБ одного полёта: наложенные графики и сводка. */
@Composable
fun BatteryCompareCard(vm: AppViewModel, log: FlightLog, packs: List<Int>) {
    val metrics = remember(log) { Batteries.metrics(log, packs) }
    if (metrics.isEmpty()) return
    val m = metrics[vm.batMetric.coerceIn(0, metrics.size - 1)]
    val series = packs.mapNotNull { p -> log.series[Batteries.key(p, m)]?.let { p to it } }
    val colors = packColors()
    val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
    val threshold = if (m == Batteries.Metric.VOLT) lowV else null
    val widthPx = remember { mutableIntStateOf(400) }
    val lo = listOfNotNull(series.minOfOrNull { it.second.min }, threshold).minOrNull() ?: 0f
    val hi = listOfNotNull(series.maxOfOrNull { it.second.max }, threshold).maxOrNull() ?: 1f
    val data = remember(log, m, widthPx.intValue) {
        series.map { (p, s) -> p to chartData(s, log.duration, (widthPx.intValue / 2).coerceAtLeast(50), loHi = lo to hi) }
    }
    val t = vm.tSec
    val grid = tk.grid; val cursorColor = tk.tx; val cr = tk.cr

    Column(Modifier.fillMaxWidth().hairline(tk.dv).padding(12.dp)) {
        T("Сравнение АКБ", Type.body(15.sp, FontWeight.SemiBold))
        T("${packs.size} ${plural(packs.size, "аккумулятор", "аккумулятора", "аккумуляторов")} на борту · ${series.firstOrNull()?.second?.message?.substringBefore('[') ?: ""}",
            Type.body(12.sp), tk.mu)
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEachIndexed { i, mm -> Chip(mm.title, mm == m, { vm.batMetric = i }) }
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().onSizeChanged { widthPx.intValue = it.width }) {
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .pointerInput(Unit) { detectTapGestures { vm.t = (it.x / size.width).coerceIn(0f, 1f) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ -> vm.t = (change.position.x / size.width).coerceIn(0f, 1f) }
                    },
            ) {
                drawGrid(grid, 20.dp.toPx())
                // Первая АКБ — сплошной линией поверх остальных.
                data.asReversed().forEach { (p, d) ->
                    drawSeries(d, packColor(colors, packs.indexOf(p)), null, if (p == packs.first()) 1.8.dp.toPx() else 1.4.dp.toPx(),
                        dash = packs.indexOf(p) % 2 == 1)
                }
                val ref = data.firstOrNull()?.second
                if (threshold != null && ref != null) drawThreshold(ref, threshold, cr)
                val cx = vm.t * size.width
                drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            T("${num(lo, m.digits)} … ${num(hi, m.digits)} ${m.unit}", Type.body(12.sp), tk.mu, Modifier.weight(1f))
            if (threshold != null) T("— BATT_LOW_VOLT", Type.body(12.sp), tk.cr)
        }

        // Легенда со значениями в позиции курсора.
        val now = series.map { (p, s) -> p to s.valueAt(t) }
        Column(Modifier.padding(top = 8.dp)) {
            now.forEach { (p, v) ->
                val col = packColor(colors, packs.indexOf(p))
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendLine(col, packs.indexOf(p) % 2 == 1)
                    T(Batteries.label(p), Type.body(14.sp, FontWeight.Medium), modifier = Modifier.padding(start = 8.dp).weight(1f))
                    T(num(v, m.digits), Type.cond(20.sp), col)
                    T(" ${m.unit}", Type.body(12.sp), tk.mu)
                }
            }
            if (now.size == 2) {
                val a = now[0].second; val b = now[1].second
                if (a != null && b != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        T("Разница", Type.body(13.sp), tk.mu, Modifier.weight(1f))
                        T("${signed(b - a, m.digits)} ${m.unit}", Type.body(13.sp), tk.mu)
                    }
                }
            }
        }

        StatsTable(log, packs, colors)
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean) {
    Canvas(Modifier.width(22.dp).height(8.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(),
            pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 5f)) else null)
    }
}

/** Сводка за полёт: строки — показатели, столбцы — АКБ (и разница для двух). */
@Composable
private fun StatsTable(log: FlightLog, packs: List<Int>, colors: List<Color>) {
    val stats = remember(log) { Batteries.stats(log) }
    if (stats.isEmpty()) return
    val rows = listOf<Triple<String, Int, (Batteries.PackStats) -> Float?>>(
        Triple("Мин. напряжение, В", 2) { it.minVolt },
        Triple("Макс. ток, А", 1) { it.maxCurr },
        Triple("Средний ток, А", 1) { it.avgCurr },
        Triple("Израсходовано, мА·ч", 0) { it.usedMah },
        Triple("Макс. температура, °C", 0) { it.maxTemp },
        Triple("Заряд в конце, %", 0) { it.endRem },
    ).filter { r -> stats.any { r.third(it) != null } }
    val delta = stats.size == 2
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 6.dp)) {
            T("ЗА ПОЛЁТ", Type.kicker, tk.mu, Modifier.weight(1.8f))
            stats.forEach { s -> T(Batteries.label(s.pack).uppercase(), Type.kicker, packColor(colors, packs.indexOf(s.pack)), Modifier.weight(1f)) }
            if (delta) T("Δ", Type.kicker, tk.mu, Modifier.weight(0.9f))
        }
        rows.forEach { (label, digits, get) ->
            Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(label, Type.body(13.sp), modifier = Modifier.weight(1.8f))
                stats.forEach { s -> T(num(get(s), digits), Type.cond(16.sp), modifier = Modifier.weight(1f)) }
                if (delta) {
                    val a = get(stats[0]); val b = get(stats[1])
                    T(if (a != null && b != null) signed(b - a, digits) else "—", Type.cond(16.sp), tk.mu, Modifier.weight(0.9f))
                }
            }
        }
    }
}
