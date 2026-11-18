package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Ch
import app.flightlog.core.LogReader
import app.flightlog.core.NavAccuracy
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.FullChart
import app.flightlog.reader.clock
import app.flightlog.reader.num
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.ModeBands
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.card
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.landscape
import app.flightlog.reader.ui.tk

/** «м» с медианой / 95% / максимумом. */
fun navStat(s: NavAccuracy.Stat?, digits: Int = 1, withMinMax: Boolean = false): String = s?.let {
    if (withMinMax) "${num(it.median, digits)} (95%: ${num(it.p95, digits)}; ${num(it.min, digits)}–${num(it.max, digits)})"
    else "${num(it.median, digits)} / ${num(it.p95, digits)} / ${num(it.max, digits)}"
} ?: "—"

fun navShare(v: Float?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"

/** Строки таблицы «земля / полёт»: показатель, на земле, в полёте. */
fun navRows(r: NavAccuracy.Report): List<Triple<String, String, String>> {
    val g = r.ground; val f = r.flight
    return buildList {
        add(Triple("Спутники, медиана (95%; мин–макс)", navStat(g?.sats, 0, true), navStat(f?.sats, 0, true)))
        if (g?.eph != null || f?.eph != null) add(Triple("eph, медиана / 95% / макс, м", navStat(g?.eph), navStat(f?.eph)))
        if (g?.epv != null || f?.epv != null) add(Triple("epv, медиана / 95% / макс, м", navStat(g?.epv), navStat(f?.epv)))
        add(Triple("HDOP, медиана / 95% / макс", navStat(g?.hdop, 2), navStat(f?.hdop, 2)))
        if (g?.vdop != null || f?.vdop != null) add(Triple("VDOP, медиана / 95% / макс", navStat(g?.vdop, 2), navStat(f?.vdop, 2)))
        if (g?.aided != null || f?.aided != null) add(Triple("Решение с поправками (SBAS/DGPS/RTK)", navShare(g?.aided), navShare(f?.aided)))
        if ((g?.noFix ?: 0f) > 0f || (f?.noFix ?: 0f) > 0f) add(Triple("Без 3D-решения", navShare(g?.noFix), navShare(f?.noFix)))
        r.innov?.let { add(Triple("Невязка EKF гориз., медиана / 95% / макс, м", "—", navStat(it))) }
        if (r.gnssBaroStd != null) add(Triple("ГНСС − баро, колебания короче минуты: СКО / 95%, м", "—",
            "${num(r.gnssBaroStd, 1)} / ${num(r.gnssBaro95, 1)}"))
        r.velInnovStd?.let { v -> add(Triple("Невязка скорости EKF, СКО N / E / D, м/с", "—", v.joinToString(" / ") { num(it, 2) })) }
        r.fusedShare?.let { add(Triple("ГНСС принят фильтром; макс. test ratio", "—", "${navShare(it)}; ${num(r.maxTestRatio, 2)}")) }
    }
}

@Composable
private fun ratingColor(r: NavAccuracy.Rating) = when (r) {
    NavAccuracy.Rating.NORMAL -> tk.ok
    NavAccuracy.Rating.FAIR -> tk.wr
    NavAccuracy.Rating.POOR -> tk.cr
    NavAccuracy.Rating.UNKNOWN -> tk.mu
}

/** Вкладка «Навигация»: точность навигации по самооценке приёмника, фильтру и барометру. */
@Composable
fun NavTab(vm: AppViewModel, p: LogReader.Parsed) {
    val log = p.log
    val r = remember(log) { NavAccuracy.analyze(log) }
    if (r == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            T("В логе нет данных ГНСС: число спутников, HDOP или оценка точности приёмника не записаны", Type.body(15.sp), tk.mu)
        }
        return
    }
    val bands = remember(log) { ModeBands.of(log) }
    val summary: @Composable () -> Unit = {
        // Вердикт.
        Column(Modifier.fillMaxWidth().card(ratingColor(r.rating).copy(alpha = 0.10f)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.hairline(ratingColor(r.rating), radius = 6.dp).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    T(r.rating.title.uppercase(), Type.cond(12.sp, 1.sp), ratingColor(r.rating))
                }
                T("Точность навигации", Type.body(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
            }
            T(r.ratingText, Type.body(14.sp), modifier = Modifier.padding(top = 8.dp))
        }
        // Таблица земля / полёт.
        Kicker("Земля и полёт", Modifier.padding(top = 20.dp, bottom = 6.dp))
        T(listOfNotNull(
            r.takeoff?.let { "Взлёт ${clock(it)}" }, r.landing?.let { "посадка ${clock(it)}" },
            if (r.ground == null) "до взлёта данных нет" else null,
        ).joinToString(" · "), Type.body(12.sp), tk.mu)
        Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 6.dp)) {
            T("ПОКАЗАТЕЛЬ", Type.kicker, tk.mu, Modifier.weight(1.5f))
            T("НА ЗЕМЛЕ", Type.kicker, tk.mu, Modifier.weight(1f))
            T("В ПОЛЁТЕ", Type.kicker, tk.mu, Modifier.weight(1f))
        }
        navRows(r).forEach { (name, g, f) ->
            Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(name, Type.body(13.sp), modifier = Modifier.weight(1.5f).padding(end = 8.dp))
                T(g, Type.cond(15.sp), tk.mu, Modifier.weight(1f))
                T(f, Type.cond(15.sp), modifier = Modifier.weight(1f))
            }
        }
        // Эпизоды.
        if (r.episodes.isNotEmpty()) {
            Kicker("Эпизоды ухудшения · ${r.episodes.size}", Modifier.padding(top = 20.dp, bottom = 6.dp))
            r.episodes.forEach { e ->
                Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().card(tk.wr1).clickable { vm.jumpTo(e.start, Ch.EPH) }.padding(12.dp)) {
                    T("${clock(e.start)}–${clock(e.end)}", Type.cond(15.sp))
                    T(listOfNotNull(
                        e.maxEph?.let { "eph до ${num(it, 1)} м" }, e.maxEpv?.let { "epv до ${num(it, 1)} м" },
                        e.maxInnov?.let { "невязка до ${num(it, 1)} м" }, e.minSats?.let { "спутников до $it" },
                        if (e.hdopMin != null && e.hdopMax != null) "HDOP ${num(e.hdopMin, 1)}–${num(e.hdopMax, 1)}" else null,
                        if (e.aidingLost) "пропадают поправки" else null,
                    ).joinToString(", "), Type.body(13.sp), modifier = Modifier.padding(top = 2.dp))
                    if (e.signalNotGeometry) T("Деградация сигналов, а не геометрии — типичная картина помехи", Type.body(13.sp, FontWeight.Medium), tk.wr)
                    if (e.messages.isNotEmpty()) T("В логе: " + e.messages.joinToString("; "), Type.body(12.sp), tk.mu)
                    T("График →", Type.body(13.sp, FontWeight.Medium), tk.act, Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    val details: @Composable () -> Unit = {
        // Графики.
        Kicker("Графики", Modifier.padding(bottom = 6.dp))
        T("Касание — перемотка, удержание — на весь экран", Type.body(12.sp), tk.mu)
        for (k in listOf(Ch.SATS, Ch.EPH, Ch.EPV, Ch.GNSS_INNOV, Ch.HDOP)) key(k) {
            val s = log.series[k] ?: return@key
            val widthPx = remember { mutableIntStateOf(400) }
            val data = remember(s, widthPx.intValue) { chartData(s, log.duration, (widthPx.intValue / 2).coerceAtLeast(50)) }
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().hairline(tk.dv).padding(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(s.label, Type.body(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    T(num(s.valueAt(vm.tSec), if (k == Ch.SATS) 0 else 1) + if (s.unit.isNotEmpty()) " ${s.unit}" else "", Type.cond(17.sp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().onSizeChanged { widthPx.intValue = it.width }) {
                    SeriesChart(data, vm.t, tk.ac, tk.ac1, tk.tx, null, tk.cr, { vm.t = it }, height = 64.dp, bands = bands,
                        onLongPress = { vm.fullChart = FullChart.Channel(k) })
                }
            }
        }
        if (r.ephBySats.size >= 2) {
            Kicker("Точность по числу спутников (полёт)", Modifier.padding(top = 20.dp, bottom = 6.dp))
            Bars(r.ephBySats)
        }
        if (r.climb.size > 5 || r.descent.size > 5) {
            Kicker("Спутники на взлёте и посадке", Modifier.padding(top = 20.dp, bottom = 6.dp))
            Scatter(r.climb, r.descent)
        }
        if (r.observations.isNotEmpty()) {
            Kicker("Наблюдения", Modifier.padding(top = 20.dp, bottom = 6.dp))
            r.observations.forEach { T("• $it", Type.body(13.sp), modifier = Modifier.padding(bottom = 6.dp)) }
        }
        if (r.paramNotes.isNotEmpty()) {
            Kicker("Настройки EKF и ГНСС", Modifier.padding(top = 14.dp, bottom = 6.dp))
            r.paramNotes.forEach { T("• $it", Type.body(13.sp), modifier = Modifier.padding(bottom = 6.dp)) }
        }
        References()
    }
    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) { summary() }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) { details() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            summary()
            Box(Modifier.height(20.dp))
            details()
        }
    }
}

/** Медиана eph по числу спутников — столбики. */
@Composable
private fun Bars(data: List<Pair<Int, Float>>) {
    val measurer = rememberTextMeasurer()
    val ac = tk.ac; val mu = tk.mu; val grid = tk.grid
    val maxV = data.maxOf { it.second }.coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val bottom = size.height - 18.dp.toPx()
        val top = 14.dp.toPx()
        val w = size.width / data.size
        drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1f)
        data.forEachIndexed { i, (n, v) ->
            val h = (bottom - top) * v / maxV
            drawRect(ac, Offset(i * w + w * 0.15f, bottom - h), Size(w * 0.7f, h))
            val lv = measurer.measure(num(v, 1), Type.body(10.sp).copy(color = mu))
            drawText(lv, topLeft = Offset(i * w + (w - lv.size.width) / 2, bottom - h - lv.size.height))
            val ln = measurer.measure("$n", Type.body(11.sp).copy(color = mu))
            drawText(ln, topLeft = Offset(i * w + (w - ln.size.width) / 2, bottom + 2.dp.toPx()))
        }
    }
    T("Ось X — спутников в решении, столбик — медиана eph, м", Type.body(11.sp), tk.mu)
}

/** Спутники по высоте над стартом: взлёт и посадка. */
@Composable
private fun Scatter(climb: List<Pair<Float, Float>>, descent: List<Pair<Float, Float>>) {
    val measurer = rememberTextMeasurer()
    val ac = tk.ac; val wr = tk.wr; val mu = tk.mu; val grid = tk.grid
    val all = climb + descent
    val maxH = all.maxOf { it.first }.coerceAtLeast(10f)
    val minS = all.minOf { it.second } - 1
    val maxS = all.maxOf { it.second } + 1
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val left = 26.dp.toPx(); val bottom = size.height - 16.dp.toPx()
        val w = size.width - left; val h = bottom
        fun x(a: Float) = left + (a.coerceAtLeast(0f) / maxH) * w
        fun y(s: Float) = bottom - (s - minS) / (maxS - minS) * h
        drawLine(grid, Offset(left, bottom), Offset(size.width, bottom), 1f)
        for (k in 0..3) {
            val sv = minS + (maxS - minS) * k / 3
            val l = measurer.measure(num(sv, 0), Type.body(10.sp).copy(color = mu))
            drawText(l, topLeft = Offset(0f, y(sv) - l.size.height / 2))
            drawLine(grid, Offset(left, y(sv)), Offset(size.width, y(sv)), 1f)
        }
        for ((a, s) in climb) drawCircle(ac, 2.dp.toPx(), Offset(x(a), y(s)))
        for ((a, s) in descent) drawCircle(wr, 2.dp.toPx(), Offset(x(a), y(s)))
        val lx = measurer.measure("высота над стартом, м: 0 … ${num(maxH, 0)}", Type.body(10.sp).copy(color = mu))
        drawText(lx, topLeft = Offset(size.width - lx.size.width, bottom + 2.dp.toPx()))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tk.ac, RoundedCornerShape(4.dp)))
        T("взлёт", Type.body(12.sp), tk.mu, Modifier.padding(start = 4.dp, end = 12.dp))
        Box(Modifier.size(8.dp).background(tk.wr, RoundedCornerShape(4.dp)))
        T("посадка", Type.body(12.sp), tk.mu, Modifier.padding(start = 4.dp))
    }
}

/** Эталонные значения и методика объективной оценки — сворачиваемый блок. */
@Composable
private fun References() {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Kicker("Эталонные значения и методика", Modifier.weight(1f))
        T(if (open) "Скрыть" else "Показать", Type.body(13.sp, FontWeight.Medium), tk.act)
    }
    if (!open) return
    Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 6.dp)) {
        T("УСЛОВИЯ", Type.kicker, tk.mu, Modifier.weight(1.6f))
        T("СПУТН.", Type.kicker, tk.mu, Modifier.weight(0.7f))
        T("ГОРИЗ.", Type.kicker, tk.mu, Modifier.weight(0.9f))
        T("ВЕРТ.", Type.kicker, tk.mu, Modifier.weight(0.9f))
    }
    NavAccuracy.REFERENCES.forEach { ref ->
        Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 8.dp)) {
            T(ref.title, Type.body(12.sp), modifier = Modifier.weight(1.6f).padding(end = 6.dp))
            T(ref.sats, Type.cond(14.sp), modifier = Modifier.weight(0.7f))
            T("${ref.hMed} / ${ref.h95}", Type.cond(14.sp), modifier = Modifier.weight(0.9f))
            T("${ref.vMed} / ${ref.v95}", Type.cond(14.sp), modifier = Modifier.weight(0.9f))
        }
    }
    T("Медиана / 95%, м. Антенная решётка без помех — «хорошо», если спутников не меньше, чем с обычной антенной, " +
        "а точность хуже не более чем в ~1,5 раза. Под помехой главное — не абсолютная точность, а деградация относительно " +
        "собственной базы без помех и непрерывность решения (без срывов и скачков).", Type.body(12.sp), tk.mu, Modifier.padding(top = 8.dp))
    Kicker("Объективная оценка", Modifier.padding(top = 14.dp, bottom = 4.dp))
    listOf(
        "База без помех: то же место и приёмник; решётка без подавления или эталонная антенна.",
        "Одновременные прогоны: две антенны на двух приёмниках в одно время.",
        "Реальная ошибка: в статике — относительно точки с известными координатами; в полёте — относительно RTK/PPK.",
        "Писать в лог: C/N0 по спутникам, отслеживаемые и используемые спутники, HDOP/VDOP, тип решения, АРУ и индикатор помех.",
        "Статика не меньше 30–60 мин; повторы в то же звёздное время.",
    ).forEachIndexed { i, s -> T("${i + 1}. $s", Type.body(12.sp), tk.mu, Modifier.padding(bottom = 3.dp)) }
}
