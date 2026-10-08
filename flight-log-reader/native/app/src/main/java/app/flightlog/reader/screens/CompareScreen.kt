package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Analyzer
import app.flightlog.core.Ch
import app.flightlog.core.Compare
import app.flightlog.core.Export
import app.flightlog.core.LogReader
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.data.LogEntry
import app.flightlog.reader.dateText
import app.flightlog.reader.durationText
import app.flightlog.reader.num
import app.flightlog.reader.signed
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.Segmented
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.regMarks
import app.flightlog.reader.ui.tk

private val CMP = listOf("Батарея" to Ch.VOLT, "Вибрации" to Ch.VIBE_Z, "Ток" to Ch.CURR, "Высота" to Ch.ALT)

@Composable
fun CompareScreen(vm: AppViewModel) {
    val a = vm.compareA
    val b = vm.compareB
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(onClick = { vm.back() }, bordered = false) { Icon(Icons.ArrowLeft, tk.tx) }
            T("Сравнение полётов", Type.cond(22.sp), modifier = Modifier.padding(start = 6.dp))
        }
        if (a == null || b == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("Открытие логов…", Type.body(15.sp), tk.mu) }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlightCard("A", a.first, tk.ac, false, Modifier.weight(1f))
                FlightCard("B", b.first, tk.mu, true, Modifier.weight(1f))
            }
            Box(Modifier.height(16.dp))
            Segmented(CMP.map { it.first }, vm.cmpCh, { vm.cmpCh = it })
            OverlayChart(a.second, b.second, CMP[vm.cmpCh].second)

            Kicker("Метрики", Modifier.padding(top = 22.dp, bottom = 6.dp))
            val metrics = remember(a, b) { Compare.metrics(a.second.analysis.summary, b.second.analysis.summary) }
            Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 8.dp)) {
                T("МЕТРИКА", Type.kicker, tk.mu, Modifier.weight(1.6f))
                T("A", Type.kicker, tk.mu, Modifier.weight(1f))
                T("B", Type.kicker, tk.mu, Modifier.weight(1f))
                T("Δ", Type.kicker, tk.mu, Modifier.weight(1f))
            }
            metrics.forEach { m ->
                Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.6f)) {
                        T(m.label, Type.body(14.sp))
                        if (m.unit.isNotEmpty()) T(m.unit, Type.body(12.sp), tk.mu)
                    }
                    T(num(m.a, m.digits), Type.cond(17.sp), modifier = Modifier.weight(1f))
                    T(num(m.b, m.digits), Type.cond(17.sp), tk.mu, Modifier.weight(1f))
                    T(signed(m.delta, m.digits), Type.cond(17.sp), if (m.aIsWorse) tk.wr else tk.tx, Modifier.weight(1f))
                }
            }

            val diffs = remember(a, b) { Compare.paramDiffs(a.second.log.params, b.second.log.params) }
            Kicker("Различия в параметрах · ${diffs.size}", Modifier.padding(top = 22.dp, bottom = 6.dp))
            if (diffs.isEmpty()) T("Параметры совпадают", Type.body(14.sp), tk.mu)
            diffs.forEach { d ->
                Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(d.name, Type.cond(15.sp, 0.3.sp), modifier = Modifier.weight(1f))
                    T(d.a?.let { Export.paramValue(it) } ?: "—", Type.body(14.sp, FontWeight.SemiBold), tk.act)
                    T("  →  ", Type.body(14.sp), tk.mu)
                    T(d.b?.let { Export.paramValue(it) } ?: "—", Type.body(14.sp, FontWeight.SemiBold), tk.mu)
                }
            }
        }
    }
}

@Composable
private fun FlightCard(tag: String, e: LogEntry, color: Color, dashed: Boolean, modifier: Modifier) {
    Column(modifier.hairline(tk.dv).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(tag, Type.cond(18.sp), color)
            Canvas(Modifier.padding(start = 8.dp).width(28.dp).height(8.dp)) {
                val y = size.height / 2
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y),
                    if (dashed) 1.3.dp.toPx() else 1.8.dp.toPx(),
                    pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null)
            }
        }
        T(e.fileName, Type.body(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        T(dateText(e.startUtc ?: e.importedAt), Type.body(13.sp), tk.mu)
        T(durationText(e.flightTime), Type.body(13.sp), tk.mu)
    }
}

/** Наложение каналов двух полётов; время — от взведения моторов. */
@Composable
private fun OverlayChart(a: LogReader.Parsed, b: LogReader.Parsed, key: String) {
    val sa = a.log.series[key]
    val sb = b.log.series[key]
    val ac = tk.ac; val mu = tk.mu; val cr = tk.cr; val grid = tk.grid
    val span = maxOf(a.log.duration - (a.log.armTime ?: 0f), b.log.duration - (b.log.armTime ?: 0f)).coerceAtLeast(1f)
    val threshold = when (key) {
        Ch.VIBE_Z -> Analyzer.VIBE_WARN
        Ch.VOLT -> a.log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
        else -> null
    }
    val lo = listOfNotNull(sa?.min, sb?.min, threshold).minOrNull() ?: 0f
    val hi = listOfNotNull(sa?.max, sb?.max, threshold).maxOrNull() ?: 1f
    val da = remember(a, key) { sa?.let { shifted(it, a.log.armTime ?: 0f, span, lo, hi) } }
    val db = remember(b, key) { sb?.let { shifted(it, b.log.armTime ?: 0f, span, lo, hi) } }
    Box(Modifier.padding(top = 16.dp).fillMaxWidth().regMarks(tk.mk).hairline(tk.dv).padding(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            drawGrid(grid, 20.dp.toPx())
            db?.let { drawSeries(it, mu, null, 1.3.dp.toPx(), dash = true) }
            da?.let { drawSeries(it, ac, null, 1.8.dp.toPx()) }
            val ref = da ?: db
            if (threshold != null && ref != null) drawThreshold(ref, threshold, cr)
        }
        if (sa == null && sb == null) T("Канал отсутствует в обоих логах", Type.body(13.sp), tk.mu)
    }
    Row(Modifier.padding(top = 6.dp)) {
        T("время от взведения, ${durationText(span)}", Type.body(12.sp), tk.mu, Modifier.weight(1f))
        T("${num(lo, 1)} … ${num(hi, 1)} ${sa?.unit ?: sb?.unit ?: ""}", Type.body(12.sp), tk.mu)
    }
}

private fun shifted(s: app.flightlog.core.Series, t0: Float, span: Float, lo: Float, hi: Float): ChartData {
    val d = chartData(s, s.time.lastOrNull() ?: 1f, 200, loHi = lo to hi)
    val dur = s.time.lastOrNull() ?: 1f
    val x = FloatArray(d.x.size) { ((d.x[it] * dur) - t0) / span }
    return ChartData(x, d.y, d.lo, d.hi)
}
