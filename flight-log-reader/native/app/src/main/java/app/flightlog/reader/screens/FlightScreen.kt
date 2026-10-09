package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Analyzer
import app.flightlog.core.Ch
import app.flightlog.core.EventKind
import app.flightlog.core.LogReader
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.FlightTab
import app.flightlog.reader.Screen
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.clock
import app.flightlog.reader.dateText
import app.flightlog.reader.num
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk
import app.flightlog.reader.ui.topRule

@Composable
fun FlightScreen(vm: AppViewModel) {
    val e = vm.flightEntry ?: return
    val p = vm.flight
    Column(Modifier.fillMaxSize()) {
        // Топбар.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(onClick = { vm.back() }, bordered = false) { Icon(Icons.ArrowLeft, tk.tx) }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                T(e.fileName, Type.cond(20.sp), maxLines = 1)
                T("${e.autopilot} ${e.format.ext} · ${dateText(e.startUtc ?: e.importedAt)}", Type.body(13.sp), tk.mu, maxLines = 1)
            }
            SecondaryButton("Отчёт", { vm.push(Screen.EXPORT) }, height = 40.dp)
        }
        if (p == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val err = vm.flightError
                T(if (err != null) "Не удалось открыть: $err" else "Открытие лога…", Type.body(15.sp), if (err != null) tk.cr else tk.mu,
                    Modifier.padding(24.dp))
            }
            return@Column
        }
        // Табы.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).bottomRule(tk.dv).padding(horizontal = 8.dp)) {
            FlightTab.entries.forEach { t ->
                val sel = vm.tab == t
                Column(Modifier.clickable { vm.tab = t }.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(t.title.uppercase(), Type.tab, if (sel) tk.tx else tk.mu)
                        if (t == FlightTab.SUMMARY && p.analysis.issues.isNotEmpty()) {
                            Box(Modifier.padding(start = 6.dp).background(statusColor(p.analysis.status)).padding(horizontal = 5.dp)) {
                                T("${p.analysis.issues.size}", Type.cond(12.sp), tk.bg)
                            }
                        }
                    }
                    Box(Modifier.height(2.dp).fillMaxWidth().background(if (sel) tk.ac else Color.Transparent))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.tab) {
                FlightTab.SUMMARY -> SummaryTab(vm, p)
                FlightTab.TRACK -> TrackTab(vm, p)
                FlightTab.CHARTS -> ChartsTab(vm, p)
                FlightTab.EVENTS -> EventsTab(vm, p)
                FlightTab.PARAMS -> ParamsTab(vm, p)
            }
        }
        Player(vm, p)
    }
}

/** Плеер: показания, скраббер с метками событий, play/pause, режим и скорость. */
@Composable
private fun Player(vm: AppViewModel, p: LogReader.Parsed) {
    val log = p.log
    val t = vm.tSec
    val u = UnitFmt(vm.units)
    val alt = log.series[Ch.ALT]?.valueAt(t)
    val spd = log.series[Ch.SPD]?.valueAt(t)
    val volt = log.series[Ch.VOLT]?.valueAt(t)
    val sats = log.series[Ch.SATS]?.valueAt(t)
    val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
    Column(Modifier.fillMaxWidth().background(tk.sf).topRule(tk.dv).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Reading("Выс", "${u.alt(alt)}", u.altUnit, Modifier.weight(1f))
            Reading("Скор", "${u.speed(spd)}", u.speedUnit, Modifier.weight(1f))
            Reading("Бат", num(volt, 1), "В", Modifier.weight(1f), warn = lowV != null && volt != null && volt < lowV)
            Reading("GPS", sats?.toInt()?.toString() ?: "—", "сп.", Modifier.weight(1f), warn = sats != null && sats < Analyzer.SATS_MIN)
        }
        Scrubber(vm, p)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(tk.ac).clickable { vm.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (vm.playing) Icons.Pause else Icons.Play, tk.onac, size = 20.dp)
            }
            T("${clock(t)} / ${clock(log.duration)}", Type.cond(17.sp), modifier = Modifier.padding(start = 12.dp).weight(1f))
            val mode = log.modeAt(t)
            val armed = log.isArmedAt(t) || log.armTime == null
            val modeText = if (armed) mode else "DISARMED"
            val warnMode = modeText.contains("RTL") || modeText.contains("Go Home")
            Box(Modifier.hairline(if (warnMode) tk.wr else tk.dv).padding(horizontal = 8.dp, vertical = 4.dp)) {
                T(modeText, Type.cond(14.sp, 1.sp), if (warnMode) tk.wr else tk.tx)
            }
            Box(Modifier.width(8.dp))
            Box(Modifier.height(32.dp).hairline(tk.dv).clickable { vm.cycleSpeed() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                T("${vm.speed}×", Type.cond(15.sp))
            }
        }
    }
}

@Composable
private fun Reading(label: String, value: String, unit: String, modifier: Modifier, warn: Boolean = false) {
    Column(modifier) {
        Kicker(label)
        Row(verticalAlignment = Alignment.Bottom) {
            T(value, Type.cond(21.sp), if (warn) tk.wr else tk.tx, maxLines = 1)
            T(" $unit", Type.body(12.sp), tk.mu, Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun Scrubber(vm: AppViewModel, p: LogReader.Parsed) {
    val ac = tk.ac; val sf2 = tk.sf2; val tx = tk.tx; val wr = tk.wr; val mu = tk.mu
    val marks = androidx.compose.runtime.remember(p) {
        p.log.events.filter { it.kind != EventKind.INFO }.map { (it.time / p.log.duration) to (it.kind == EventKind.WARN) }
    }
    Canvas(
        Modifier.fillMaxWidth().height(26.dp).padding(vertical = 0.dp)
            .pointerInput(p) { detectTapGestures { vm.t = (it.x / size.width).coerceIn(0f, 1f) } }
            .pointerInput(p) { detectDragGestures { change, _ -> vm.t = (change.position.x / size.width).coerceIn(0f, 1f) } },
    ) {
        val cy = size.height / 2
        val th = 4.dp.toPx()
        drawRect(sf2, Offset(0f, cy - th / 2), Size(size.width, th))
        drawRect(ac, Offset(0f, cy - th / 2), Size(size.width * vm.t, th))
        val mh = 18.dp.toPx()
        for ((x, warn) in marks) {
            drawRect(if (warn) wr else mu.copy(alpha = 0.6f), Offset(x * size.width - 1.dp.toPx(), cy - mh / 2), Size(2.dp.toPx(), mh))
        }
        val tw = 14.dp.toPx(); val tH = 16.dp.toPx()
        drawRect(tx, Offset((vm.t * size.width - tw / 2).coerceIn(0f, size.width - tw), cy - tH / 2), Size(tw, tH))
    }
}
