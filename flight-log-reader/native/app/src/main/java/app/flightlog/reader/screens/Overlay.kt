package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.flightlog.core.Ch
import app.flightlog.core.FlightLog
import app.flightlog.core.Motors
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.FullChart
import app.flightlog.reader.MAX_OVERLAY
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.num
import app.flightlog.reader.ui.Chip
import app.flightlog.reader.ui.ModeBands
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.drawModeBands
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk

@Composable
private fun overlayColors(): List<Color> = listOf(tk.ac, tk.wr, tk.ok, tk.cr, Color(0xFF8E6CD9), Color(0xFF2BB3C9))

/** Знаков после запятой для единицы измерения. */
private fun digitsFor(unit: String) = when (unit) {
    "" -> 2
    "мкс", "мГс", "%", "мА·ч" -> 0
    else -> 1
}

/** Готовые наборы каналов, которые есть в логе. */
private fun presets(log: FlightLog): List<Pair<String, List<String>>> {
    val s = log.series
    fun has(vararg k: String) = k.filter { it in s }
    return listOfNotNull(
        Motors.motors(log).map { Motors.key(it) }.takeIf { it.size > 1 }?.let { "Моторы" to it },
        has(Ch.MAG, Ch.CURR).takeIf { it.size == 2 }?.let { "Компас и ток" to it },
        has(Ch.EKF_VEL, Ch.EKF_POS, Ch.EKF_HGT, Ch.EKF_MAG).takeIf { it.size > 1 }?.let { "EKF" to it },
        has(Ch.THR, Ch.VOLT, Ch.CURR).takeIf { it.size > 1 }?.let { "Газ и питание" to it },
        has(Ch.ALT, Ch.CLIMB, Ch.THR).takeIf { it.size > 1 }?.let { "Высота" to it },
        has(Ch.VIBE_X, Ch.VIBE_Y, Ch.VIBE_Z).takeIf { it.size > 1 }?.let { "Вибрации" to it },
        has(Ch.ROLL, Ch.PITCH).takeIf { it.size > 1 }?.let { "Крен и тангаж" to it },
    )
}

/** Наложение нескольких каналов на один график с общим курсором. */
@Composable
fun OverlayCard(vm: AppViewModel, log: FlightLog, keys: List<String>, bands: ModeBands?) {
    val u = UnitFmt(vm.units)
    val chosen = vm.overlay.filter { it in log.series }
    val series = chosen.map { log.series.getValue(it) }
    val colors = overlayColors()
    val presets = remember(log) { presets(log) }
    Column(Modifier.fillMaxWidth().hairline(tk.dv).padding(12.dp)) {
        T("Наложение графиков", Type.body(15.sp, FontWeight.SemiBold))
        T("До $MAX_OVERLAY каналов на одном графике — набором или по одному. Удержание графика — на весь экран", Type.body(12.sp), tk.mu)
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (title, ks) -> Chip(title, chosen == ks, { vm.setOverlay(if (chosen == ks) emptyList() else ks) }) }
            if (chosen.isNotEmpty()) Chip("Очистить", false, { vm.setOverlay(emptyList()) })
        }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.forEach { k -> Chip(log.series.getValue(k).label, k in chosen, { vm.toggleOverlay(k) }) }
        }
        if (series.isEmpty()) return@Column

        val units = series.map { it.unit }.distinct()
        val sameUnit = units.size == 1
        val shared = sameUnit && vm.overlayShared
        val widthPx = remember { mutableIntStateOf(400) }
        val loHi = if (shared) {
            u.channel(series.minOf { it.min }, units[0]) to u.channel(series.maxOf { it.max }, units[0])
        } else null
        val data = remember(chosen, shared, widthPx.intValue, vm.units, log) {
            series.map { s -> chartData(s, log.duration, (widthPx.intValue / 2).coerceAtLeast(50), { u.channel(it, s.unit) }, loHi) }
        }
        val grid = tk.grid; val cursorColor = tk.tx
        if (sameUnit && series.size > 1) {
            Row(Modifier.padding(top = 10.dp)) {
                Chip("Общая шкала", vm.overlayShared, { vm.overlayShared = !vm.overlayShared })
            }
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().onSizeChanged { widthPx.intValue = it.width }) {
            Canvas(
                Modifier.fillMaxWidth().height(170.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { vm.fullChart = FullChart.Overlay }) { vm.t = (it.x / size.width).coerceIn(0f, 1f) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ -> vm.t = (change.position.x / size.width).coerceIn(0f, 1f) }
                    },
            ) {
                drawModeBands(bands)
                drawGrid(grid, 20.dp.toPx())
                data.forEachIndexed { i, d -> drawSeries(d, colors[i % colors.size], null, 1.6.dp.toPx()) }
                val cx = vm.t * size.width
                drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
            }
        }
        if (loHi != null) {
            val unit = u.channelUnit(units[0])
            T("${num(loHi.first, digitsFor(units[0]))} … ${num(loHi.second, digitsFor(units[0]))} $unit",
                Type.body(12.sp), tk.mu, Modifier.padding(top = 4.dp))
        } else {
            T("У каждого канала своя шкала: от минимума до максимума", Type.body(12.sp), tk.mu, Modifier.padding(top = 4.dp))
        }
        Column(Modifier.padding(top = 8.dp)) {
            series.forEachIndexed { i, s ->
                val d = digitsFor(s.unit)
                val unit = u.channelUnit(s.unit)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(18.dp).height(3.dp).background(colors[i % colors.size], RoundedCornerShape(2.dp)))
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        T(s.label, Type.body(14.sp, FontWeight.Medium), maxLines = 1)
                        if (!shared) T("${num(u.channel(s.min, s.unit), d)} … ${num(u.channel(s.max, s.unit), d)} $unit",
                            Type.body(12.sp), tk.mu)
                    }
                    T(num(s.valueAt(vm.tSec)?.let { u.channel(it, s.unit) }, d), Type.cond(20.sp), colors[i % colors.size])
                    T(" $unit", Type.body(12.sp), tk.mu)
                }
            }
        }
    }
}

/** Легенда цветов режимов полёта. */
@Composable
fun ModeLegend(bands: ModeBands, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        T("Режимы", Type.body(13.sp), tk.mu)
        bands.names.forEachIndexed { i, name ->
            Box(Modifier.padding(start = 12.dp).size(10.dp).background(bands.color(i), RoundedCornerShape(2.dp)))
            T(name, Type.cond(13.sp, 0.5.sp), modifier = Modifier.padding(start = 5.dp))
        }
    }
}
