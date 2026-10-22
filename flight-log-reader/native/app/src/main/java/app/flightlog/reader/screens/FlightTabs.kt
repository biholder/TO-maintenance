package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.flightlog.reader.ui.TrackView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Analyzer
import app.flightlog.core.Batteries
import app.flightlog.core.Ch
import app.flightlog.core.EventKind
import app.flightlog.core.Export
import app.flightlog.core.LogReader
import app.flightlog.core.Severity
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.EvFilter
import app.flightlog.reader.TrackBy
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.map.MapLayer
import app.flightlog.reader.map.MapTiles
import app.flightlog.reader.clock
import app.flightlog.reader.durationText
import app.flightlog.reader.num
import app.flightlog.reader.signed
import app.flightlog.reader.ui.Chip
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.ModeBands
import app.flightlog.reader.ui.Segmented
import app.flightlog.reader.ui.Stat
import app.flightlog.reader.ui.StatusDot
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.TrackGeometry
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.ValueGrid
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.Radius
import app.flightlog.reader.ui.card
import app.flightlog.reader.ui.tk
import kotlin.math.roundToInt

// ───────────────────────── Сводка ─────────────────────────

@Composable
fun SummaryTab(vm: AppViewModel, p: LogReader.Parsed) {
    val a = p.analysis
    val log = p.log
    val s = a.summary
    val u = UnitFmt(vm.units)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Kicker("Диагностика")
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (a.issues.isEmpty()) {
                Row(Modifier.fillMaxWidth().hairline(tk.dv).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tk.ok, 8.dp)
                    T("Проблем не обнаружено", Type.body(15.sp, FontWeight.Medium), modifier = Modifier.padding(start = 10.dp))
                }
            }
            a.issues.forEach { i ->
                val crit = i.severity == Severity.CRITICAL
                val col = if (crit) tk.cr else tk.wr
                Column(
                    Modifier.fillMaxWidth().card(if (crit) tk.cr.copy(alpha = 0.10f) else tk.wr1)
                        .clickable { vm.jumpTo(i.start, i.channel) }.padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.hairline(col, radius = 6.dp).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            T(if (crit) "КРИТИЧНО" else "ПРЕДУПР.", Type.cond(12.sp, 1.sp), col)
                        }
                        T("${clock(i.start)}–${clock(i.end)}", Type.cond(14.sp), tk.mu, Modifier.padding(start = 10.dp))
                    }
                    T(i.title, Type.body(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    T(i.explanation, Type.body(14.sp), tk.tx, Modifier.padding(top = 4.dp))
                    if (i.channel != null) {
                        val label = log.series[i.channel]?.label ?: "График"
                        T("График: ${label.lowercase()} →", Type.body(14.sp, FontWeight.Medium), tk.act, Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (a.healthy.isNotEmpty()) {
                T("${a.healthy.joinToString(", ")} — в норме", Type.body(14.sp), tk.mu)
            }
        }

        Kicker("Сводка", Modifier.padding(top = 24.dp, bottom = 10.dp))
        val (dv, du) = u.dist(s.distanceM)
        val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
        val mins = (s.flightTime / 60).toInt()
        val secs = (s.flightTime % 60).roundToInt()
        ValueGrid(2, listOf<@Composable () -> Unit>(
            { Stat("Длительность", "$mins:${"%02d".format(secs)}", "мин") },
            { Stat("Дистанция", dv, du) },
            { Stat("Макс. высота", u.alt(s.maxAltM), u.altUnit) },
            { Stat("Макс. скорость", u.speed(s.maxSpeedMs), u.speedUnit) },
            { Stat("Израсходовано", num(s.usedMah, 0), "мА·ч") },
            { Stat("Мин. напряжение", num(s.minVoltage, 2), "В", if (lowV != null && (s.minVoltage ?: 99f) < lowV + 0.6f) tk.wr else tk.tx) },
        ))

        Kicker("Борт", Modifier.padding(top = 24.dp, bottom = 6.dp))
        listOf(
            "Контроллер" to log.vehicle.board,
            "Прошивка" to log.vehicle.firmware,
            "Тип" to log.vehicle.vehicleType,
            "Рама" to log.vehicle.frame,
            "GPS" to log.vehicle.gps,
            "Батарея" to listOfNotNull(
                Batteries.packs(log).size.takeIf { it > 1 }?.let { "$it АКБ" },
                log.params.firstOrNull { it.name == "BATT_CAPACITY" }?.value?.let { "${it.roundToInt()} мА·ч" },
            ).joinToString(" · "),
            "Файл" to "${log.fileName} · ${LogReader.sizeText(log.fileSize)} · ${log.messageCount} сообщ.",
        ).filter { it.second.isNotEmpty() }.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(vertical = 10.dp)) {
                T(k, Type.body(14.sp), tk.mu, Modifier.width(110.dp))
                T(v, Type.body(14.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            }
        }
    }
}

// ───────────────────────── Трек ─────────────────────────

@Composable
fun TrackTab(vm: AppViewModel, p: LogReader.Parsed) {
    val log = p.log
    val tr = log.track
    val u = UnitFmt(vm.units)
    if (tr.size < 2) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            T("В логе нет координат GPS с 3D-фиксом", Type.body(15.sp), tk.mu)
        }
        return
    }
    val bands = remember(log) { ModeBands.of(log) }
    val byMode = vm.trackBy == TrackBy.MODE
    // Значения для раскраски по выбранной метрике; для режима — номер режима.
    val colorValues = remember(p, vm.trackBy) {
        FloatArray(tr.size) { i ->
            when (vm.trackBy) {
                TrackBy.SPEED -> tr.speed[i]
                TrackBy.ALT -> log.series[Ch.ALT]?.valueAt(tr.time[i]) ?: tr.alt[i]
                TrackBy.BATTERY -> log.series[Ch.VOLT]?.valueAt(tr.time[i]) ?: 0f
                TrackBy.CURRENT -> log.series[Ch.CURR]?.valueAt(tr.time[i]) ?: 0f
                TrackBy.MODE -> bands.names.indexOf(log.modeAt(tr.time[i])).toFloat()
            }
        }
    }
    val lo = colorValues.minOrNull() ?: 0f
    val hi = (colorValues.maxOrNull() ?: 1f).let { if (it - lo < 1e-3f) lo + 1f else it }
    val ramp = tk.ramp.let { if (vm.trackBy == TrackBy.BATTERY) it.reversed() else it }
    val t = vm.tSec
    val cur = tr.indexAt(t)
    val warnTimes = remember(p) { p.analysis.issues.map { it.start } }
    val grid = tk.grid; val tx = tk.tx; val wr = tk.wr; val sf = tk.sf; val bg = tk.bg
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Цвет", Type.body(13.sp), tk.mu)
            TrackBy.entries.filter { by ->
                when (by) {
                    TrackBy.CURRENT -> Ch.CURR in log.series
                    TrackBy.BATTERY -> Ch.VOLT in log.series
                    TrackBy.MODE -> bands.names.isNotEmpty()
                    else -> true
                }
            }.forEach { by -> Chip(by.title, vm.trackBy == by, { vm.trackBy = by }) }
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Подложка", Type.body(13.sp), tk.mu)
            MapLayer.entries.forEach { l -> Chip(l.title, vm.mapLayer == l, { vm.selectMapLayer(l) }) }
        }
        val layer = vm.mapLayer
        val padPx = with(LocalDensity.current) { 18.dp.toPx() }
        val density = LocalDensity.current.density
        val dimTiles = tk.dark && layer != MapLayer.SATELLITE
        val viewState = remember(p) { androidx.compose.runtime.mutableStateOf<TrackView?>(null) }
        val mapSize = remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
        // При воспроизведении приближенная карта следует за бортом.
        LaunchedEffect(cur, vm.playing) {
            val v = viewState.value ?: return@LaunchedEffect
            val sz = mapSize.value
            if (!vm.playing || cur < 0 || sz.width == 0) return@LaunchedEffect
            val g = TrackGeometry(tr, sz.width.toFloat(), sz.height.toFloat(), padPx, v)
            val (x, y) = g.project(cur)
            val m = 0.2f
            if (x < sz.width * m || x > sz.width * (1 - m) || y < sz.height * m || y > sz.height * (1 - m)) viewState.value = g.centeredOn(cur)
        }
        val textMeasurer = rememberTextMeasurer()
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(1f).card(sf, tk.dv, Radius.L).onSizeChanged { mapSize.value = it }) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(p) {
                        detectTapGestures(
                            onDoubleTap = { viewState.value = null },
                            onTap = { pos ->
                                val g = TrackGeometry(tr, size.width.toFloat(), size.height.toFloat(), padPx, viewState.value)
                                var best = 0; var bd = Float.MAX_VALUE
                                for (i in 0 until tr.size) {
                                    val (x, y) = g.project(i)
                                    val d = (x - pos.x) * (x - pos.x) + (y - pos.y) * (y - pos.y)
                                    if (d < bd) { bd = d; best = i }
                                }
                                vm.seekSec(tr.time[best])
                            },
                        )
                    }
                    .pointerInput(p) {
                        // Два пальца — масштаб и сдвиг; одним пальцем карта двигается, только когда приближена,
                        // иначе жест прокручивает страницу.
                        val slop = viewConfiguration.touchSlop
                        val maxScale = 256.0 * density * (1 shl 21)
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var moved = 0f
                            var active = false
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || viewState.value != null) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (!active) {
                                        moved += pan.getDistance() + kotlin.math.abs(zoom - 1f) * 200f
                                        active = moved > slop || fingers >= 2
                                    }
                                    if (active && (zoom != 1f || pan != Offset.Zero)) {
                                        val c = event.calculateCentroid(useCurrent = true)
                                        val g = TrackGeometry(tr, size.width.toFloat(), size.height.toFloat(), padPx, viewState.value)
                                        viewState.value = g.transformed(zoom, c.x, c.y, pan.x, pan.y, maxScale)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                val g = TrackGeometry(tr, size.width, size.height, padPx, viewState.value)
                if (layer.url != null) {
                    drawTiles(g, layer, density)
                    if (dimTiles) drawRect(bg.copy(alpha = 0.35f))
                } else drawGrid(grid, 20.dp.toPx())
                val w = 3.dp.toPx()
                // Не больше ~3000 отрезков: длинные логи иначе тормозят при воспроизведении.
                val step = (tr.size / 3000).coerceAtLeast(1)
                // На карте — тёмная обводка, чтобы трек читался поверх снимка.
                if (layer.url != null) {
                    var prev = g.project(0)
                    for (i in step until tr.size step step) {
                        val pt = g.project(i)
                        drawLine(Color.Black.copy(alpha = if (i > cur) 0.2f else 0.55f), Offset(prev.first, prev.second),
                            Offset(pt.first, pt.second), w + 2.dp.toPx(), StrokeCap.Round)
                        prev = pt
                    }
                }
                var prev = g.project(0)
                for (i in step until tr.size step step) {
                    val pt = g.project(i)
                    val col = if (byMode) bands.color(colorValues[i].toInt().coerceAtLeast(0))
                    else ramp[((colorValues[i] - lo) / (hi - lo) * (ramp.size - 1)).roundToInt().coerceIn(0, ramp.size - 1)]
                    val alpha = if (i > cur) (if (layer.url != null) 0.45f else 0.25f) else 1f
                    drawLine(col.copy(alpha = alpha), Offset(prev.first, prev.second), Offset(pt.first, pt.second), w,
                        if (layer.url != null) StrokeCap.Round else StrokeCap.Square)
                    prev = pt
                }
                // Предупреждения — ромбы.
                for (wt in warnTimes) {
                    val i = tr.indexAt(wt).coerceAtLeast(0)
                    val (x, y) = g.project(i)
                    val r = 7.dp.toPx()
                    rotate(45f, Offset(x, y)) {
                        drawRect(bg, Offset(x - r / 2, y - r / 2), Size(r, r))
                        drawRect(wr, Offset(x - r / 2, y - r / 2), Size(r, r), style = Stroke(1.5.dp.toPx()))
                    }
                }
                // Дом.
                val (hx, hy) = g.project(0)
                val hs = 18.dp.toPx()
                drawRoundRect(tx, Offset(hx - hs / 2, hy - hs / 2), Size(hs, hs),
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()))
                val hLabel = textMeasurer.measure("H", Type.cond(12.sp).copy(color = bg))
                drawText(hLabel, topLeft = Offset(hx - hLabel.size.width / 2f, hy - hLabel.size.height / 2f))
                // Текущая позиция.
                if (cur >= 0) {
                    val (cx, cy) = g.project(cur)
                    if (layer.url != null) drawCircle(bg, 8.dp.toPx(), Offset(cx, cy))
                    drawCircle(tx, 2.5.dp.toPx(), Offset(cx, cy))
                    drawCircle(tx, 6.dp.toPx(), Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                }
            }
            if (viewState.value != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).card(bg.copy(alpha = 0.85f), tk.dv, Radius.S)
                    .clickable { viewState.value = null }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    T("Весь трек", Type.body(12.sp, FontWeight.Medium))
                }
            }
            val onMap = if (layer.url != null) Modifier.background(bg.copy(alpha = 0.75f), RoundedCornerShape(Radius.XS)).padding(horizontal = 5.dp, vertical = 1.dp) else Modifier
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                T("${"%.5f".format(tr.lat[0])}, ${"%.5f".format(tr.lon[0])}", Type.body(11.sp), tk.mu, onMap)
            }
            if (layer.url != null) {
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
                    T(layer.attribution, Type.body(10.sp), tk.mu, onMap)
                    if (MapTiles.offline.intValue > 0) T("Нет сети — подложка из кэша", Type.body(10.sp), tk.wr, Modifier.padding(top = 4.dp).then(onMap))
                }
            }
        }
        // Легенда.
        if (byMode) ModeLegend(bands, Modifier.padding(top = 10.dp))
        else Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val unitText = when (vm.trackBy) {
                TrackBy.SPEED -> u.speedUnit; TrackBy.ALT -> u.altUnit; TrackBy.BATTERY -> "В"; TrackBy.CURRENT -> "А"; TrackBy.MODE -> ""
            }
            val conv: (Float) -> String = when (vm.trackBy) {
                TrackBy.SPEED -> { v -> u.speed(v) }
                TrackBy.ALT -> { v -> u.alt(v) }
                TrackBy.BATTERY -> { v -> num(v, 1) }
                TrackBy.CURRENT, TrackBy.MODE -> { v -> num(v, 0) }
            }
            T(conv(lo), Type.body(12.sp), tk.mu)
            Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(6.dp).background(Brush.horizontalGradient(ramp), RoundedCornerShape(3.dp)))
            T("${conv(hi)} $unitText", Type.body(12.sp), tk.mu)
        }
        // Показания в текущий момент.
        val s = log.series
        val toHome = if (cur >= 0) Analyzer.haversine(tr.lat[0], tr.lon[0], tr.lat[cur], tr.lon[cur]) else null
        val (hd, hu) = u.dist(toHome)
        Box(Modifier.height(14.dp))
        ValueGrid(3, listOf<@Composable () -> Unit>(
            { Stat("Крен", signed(s[Ch.ROLL]?.valueAt(t), 1), "°", big = false) },
            { Stat("Тангаж", signed(s[Ch.PITCH]?.valueAt(t), 1), "°", big = false) },
            { Stat("Курс", num(s[Ch.YAW]?.valueAt(t), 0), "°", big = false) },
            { Stat("Верт. скор.", signed(s[Ch.CLIMB]?.valueAt(t)?.let { u.channel(it, "м/с") }, 1), u.speedUnit, big = false) },
            { Stat("До дома", hd, hu, big = false) },
            { Stat("HDOP", num(s[Ch.HDOP]?.valueAt(t), 2), "", big = false) },
        ))
    }
}

/** Тайлы подложки под треком; пока тайл грузится, показывается увеличенный родитель из памяти. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTiles(g: TrackGeometry, layer: MapLayer, density: Float) {
    MapTiles.revision.intValue // перерисовка, когда догрузится тайл
    val z = kotlin.math.log2(g.scale / (256.0 * density)).roundToInt().coerceIn(1, layer.maxZoom)
    val n = 1 shl z
    val x0 = kotlin.math.floor(g.worldLeft * n).toInt()
    val x1 = kotlin.math.floor((g.worldLeft + size.width / g.scale) * n).toInt()
    val y0 = kotlin.math.floor(g.worldTop * n).toInt().coerceAtLeast(0)
    val y1 = kotlin.math.floor((g.worldTop + size.height / g.scale) * n).toInt().coerceAtMost(n - 1)
    fun sx(wx: Double) = ((wx - g.worldLeft) * g.scale)
    fun sy(wy: Double) = ((wy - g.worldTop) * g.scale)
    for (ty in y0..y1) for (tx in x0..x1) {
        val wx = ((tx % n) + n) % n
        val left = kotlin.math.floor(sx(tx.toDouble() / n)).toInt()
        val top = kotlin.math.floor(sy(ty.toDouble() / n)).toInt()
        val right = kotlin.math.ceil(sx((tx + 1).toDouble() / n)).toInt()
        val bottom = kotlin.math.ceil(sy((ty + 1).toDouble() / n)).toInt()
        val dstOff = androidx.compose.ui.unit.IntOffset(left, top)
        val dstSize = IntSize(right - left, bottom - top)
        val img = MapTiles.get(layer, z, wx, ty)
        if (img != null) {
            drawImage(img, srcSize = IntSize(img.width, img.height), dstOffset = dstOff, dstSize = dstSize,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        } else {
            val parent = MapTiles.peek(layer, z - 1, wx / 2, ty / 2) ?: continue
            val half = parent.width / 2
            drawImage(parent, srcOffset = androidx.compose.ui.unit.IntOffset((wx % 2) * half, (ty % 2) * half),
                srcSize = IntSize(half, half), dstOffset = dstOff, dstSize = dstSize,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        }
    }
}



// ───────────────────────── Графики ─────────────────────────

private val CHANNELS = listOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.CURR, Ch.VIBE_Z, Ch.SATS, Ch.ROLL, Ch.PITCH, Ch.VIBE_X, Ch.VIBE_Y, Ch.HDOP, Ch.CLIMB,
    Ch.MAH, Ch.CLIP, Ch.THR, Ch.EKF, Ch.MAG, Ch.magDiff(1), Ch.RSSI)

/** Все каналы лога для наложения: сначала стандартные, затем остальные в порядке разбора. */
private fun overlayKeys(log: app.flightlog.core.FlightLog): List<String> =
    (CHANNELS.filter { it in log.series } + log.series.keys).distinct()

@Composable
fun ChartsTab(vm: AppViewModel, p: LogReader.Parsed) {
    val log = p.log
    val u = UnitFmt(vm.units)
    // Стандартные каналы, затем дополнительные столбцы CSV.
    val available = CHANNELS.filter { it in log.series } + log.series.keys.filter { it.startsWith("csv:") }
    LaunchedEffect(p) {
        // Если стандартных каналов нет (например, в CSV), показать первые доступные.
        if (vm.channels.none { it in log.series }) vm.channels.addAll(available.take(4))
    }
    val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
    val packs = remember(log) { Batteries.packs(log) }
    val bands = remember(log) { ModeBands.of(log) }
    val overlayKeys = remember(log) { overlayKeys(log) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { k -> Chip(log.series.getValue(k).label, k in vm.channels, { vm.toggleChannel(k) }) }
        }
        val shown = vm.channels.filter { it in log.series }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bands.names.isNotEmpty()) item(key = "modes") { ModeLegend(bands) }
            item(key = "overlay") { OverlayCard(vm, log, overlayKeys, bands) }
            if (packs.size > 1) item(key = "battery-compare") { BatteryCompareCard(vm, log, packs, bands) }
            items(shown, key = { it }) { k ->
                val s = log.series.getValue(k)
                val vibe = k.startsWith("vibe")
                val threshold = when {
                    vibe -> Analyzer.VIBE_WARN
                    k == Ch.VOLT -> lowV
                    k == Ch.EKF -> Analyzer.EKF_WARN
                    k == Ch.RSSI -> Analyzer.RSSI_WARN
                    else -> null
                }
                val below = k == Ch.VOLT || k == Ch.RSSI
                val cur = s.valueAt(vm.tSec)
                val over = cur != null && threshold != null && (if (below) cur < threshold else cur > threshold)
                Column(Modifier.fillMaxWidth().hairline(tk.dv).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            T(s.label, Type.body(15.sp, FontWeight.SemiBold))
                            T(s.message, Type.body(12.sp), tk.mu)
                        }
                        T(cur?.let { num(u.channel(it, s.unit), if (k == Ch.SATS || k == Ch.CLIP) 0 else if (s.unit == "") 2 else 1) } ?: "—",
                            Type.cond(22.sp), if (over) tk.wr else tk.tx)
                        T(" ${u.channelUnit(s.unit)}", Type.body(12.sp), tk.mu, Modifier.padding(bottom = 3.dp))
                    }
                    val widthPx = remember { androidx.compose.runtime.mutableIntStateOf(400) }
                    val data = remember(s, log.duration, widthPx.intValue, vm.units) {
                        chartData(s, log.duration, (widthPx.intValue / 2).coerceAtLeast(50), { u.channel(it, s.unit) })
                    }
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().onSizeChanged { widthPx.intValue = it.width }) {
                        SeriesChart(
                            data, vm.t,
                            line = if (vibe) tk.wr else tk.ac, fill = if (vibe) tk.wr1 else tk.ac1,
                            cursorColor = tk.tx, threshold = threshold?.let { u.channel(it, s.unit) }, thresholdColor = tk.cr,
                            onSeek = { vm.t = it },
                            bands = bands,
                        )
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        T("мин ${num(u.channel(s.min, s.unit), 1)} · макс ${num(u.channel(s.max, s.unit), 1)}", Type.body(12.sp), tk.mu, Modifier.weight(1f))
                        T("${num(s.rateHz, 0)} Гц", Type.body(12.sp), tk.mu)
                    }
                }
            }
            item { Box(Modifier.height(8.dp)) }
        }
    }
}

// ───────────────────────── События ─────────────────────────

@Composable
fun EventsTab(vm: AppViewModel, p: LogReader.Parsed) {
    val log = p.log
    val list = remember(p, vm.evFilter) {
        log.events.filter {
            when (vm.evFilter) {
                EvFilter.ALL -> true
                EvFilter.WARN -> it.kind == EventKind.WARN
                EvFilter.MODES -> it.kind == EventKind.MODE
            }
        }
    }
    val t = vm.tSec
    val current = list.indexOfLast { it.time <= t }
    val state = rememberLazyListState()
    LaunchedEffect(vm.playing, current) { if (vm.playing && current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(16.dp)) {
            Segmented(EvFilter.entries.map { it.title }, vm.evFilter.ordinal, { vm.evFilter = EvFilter.entries[it] })
        }
        if (list.isEmpty()) T("Событий нет", Type.body(15.sp), tk.mu, Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(list.size) { i ->
                val e = list[i]
                val col = when (e.kind) { EventKind.WARN -> tk.wr; EventKind.MODE -> tk.ac; EventKind.INFO -> tk.mu }
                Row(
                    Modifier.fillMaxWidth().background(if (i == current) tk.ac1 else Color.Transparent)
                        .clickable { vm.seekSec(e.time) }.bottomRule(tk.dv).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    T(clock(e.time), Type.cond(15.sp), tk.mu, Modifier.width(52.dp))
                    Box(Modifier.padding(top = 6.dp, end = 10.dp)) { StatusDot(col, 8.dp) }
                    Column(Modifier.weight(1f)) {
                        T(e.title, Type.body(15.sp, FontWeight.Medium))
                        if (e.detail.isNotEmpty()) T(e.detail, Type.body(13.sp), tk.mu)
                    }
                    T(e.source, Type.cond(12.sp, 0.5.sp), tk.mu, Modifier.padding(start = 8.dp, top = 2.dp))
                }
            }
        }
    }
}

// ───────────────────────── Параметры ─────────────────────────

@Composable
fun ParamsTab(vm: AppViewModel, p: LogReader.Parsed) {
    val all = p.log.params
    val changed = all.count { it.changed }
    val q = vm.paramQuery.trim()
    val list = all.filter { (!vm.onlyChanged || it.changed) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().height(46.dp).card(tk.sf).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Search, tk.mu, size = 18.dp)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (vm.paramQuery.isEmpty()) T("Поиск параметра", Type.body(15.sp), tk.mu)
                    BasicTextField(
                        vm.paramQuery, { vm.paramQuery = it }, singleLine = true,
                        textStyle = Type.body(15.sp).copy(color = tk.tx), cursorBrush = SolidColor(tk.ac),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                T(if (all.isEmpty()) "Параметров в логе нет" else "${all.size} параметров · $changed отличаются от умолчаний",
                    Type.body(13.sp), tk.mu, Modifier.weight(1f))
                Chip("Только изменённые", vm.onlyChanged, { vm.onlyChanged = !vm.onlyChanged })
            }
            if (all.isNotEmpty() && all.all { it.default == null }) {
                T("Значения по умолчанию в этом логе не записаны", Type.body(12.sp), tk.mu, Modifier.padding(top = 6.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.name }) { pr ->
                Row(Modifier.fillMaxWidth().bottomRule(tk.dv).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(pr.name, Type.cond(16.sp, 0.3.sp), modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        T(Export.paramValue(pr.value), Type.body(15.sp, FontWeight.SemiBold), tk.act)
                        if (pr.changed) T("по умолч. ${Export.paramValue(pr.default!!)}", Type.body(12.sp), tk.wr)
                    }
                }
            }
        }
    }
}
