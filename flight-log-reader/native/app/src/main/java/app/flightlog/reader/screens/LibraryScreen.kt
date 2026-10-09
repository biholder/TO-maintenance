package app.flightlog.reader.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Severity
import app.flightlog.core.plural
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.Units
import app.flightlog.reader.data.LogEntry
import app.flightlog.reader.dateText
import app.flightlog.reader.durationText
import app.flightlog.reader.ui.CheckBox
import app.flightlog.reader.ui.Chip
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.StatusDot
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk
import app.flightlog.reader.ui.topRule

val SOURCES = listOf("ArduPilot", "PX4", "DJI", "Betaflight", "INAV")

@Composable
fun statusColor(s: Severity): Color = when (s) {
    Severity.OK -> tk.ok
    Severity.WARN -> tk.wr
    Severity.CRITICAL -> tk.cr
}

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val all = vm.entries
    val list = all.filter { vm.srcFilter == null || it.autopilot == vm.srcFilter }
    val total = all.sumOf { it.flightTime.toDouble() }.toFloat()
    val dark = tk.dark
    Column(Modifier.fillMaxSize()) {
        // Шапка.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                T("Полёты", Type.h1)
                T(
                    if (all.isEmpty()) "Логов пока нет"
                    else "${all.size} ${plural(all.size, "лог", "лога", "логов")} · ${durationText(total)} налёта",
                    Type.body(14.sp), tk.mu, Modifier.padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBox(onClick = { vm.units = if (vm.units == Units.METRIC) Units.IMPERIAL else Units.METRIC }) {
                    T(if (vm.units == Units.METRIC) "М" else "FT", Type.cond(15.sp, 1.sp))
                }
                IconBox(onClick = { vm.themeOverride = !dark }) { Icon(if (dark) Icons.Moon else Icons.Sun, tk.tx) }
                IconBox(onClick = { vm.compareMode = !vm.compareMode; if (!vm.compareMode) vm.picked.clear() }, selected = vm.compareMode) {
                    Icon(Icons.Columns, if (vm.compareMode) tk.bg else tk.tx)
                }
            }
        }

        // Чипы источника.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Все · ${all.size}", vm.srcFilter == null, { vm.srcFilter = null })
            SOURCES.forEach { s ->
                val n = all.count { it.autopilot == s }
                Chip(if (n > 0) "$s · $n" else s, vm.srcFilter == s, { vm.srcFilter = if (vm.srcFilter == s) null else s })
            }
        }
        Box(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth().topRule(tk.dv)) {
            if (list.isEmpty()) EmptyLibrary(vm, all.isEmpty())
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { e -> LogRow(vm, e) }
                item { Box(Modifier.height(12.dp)) }
            }
        }

        // Нижняя панель.
        Box(Modifier.fillMaxWidth().topRule(tk.dv).background(tk.bg).padding(16.dp)) {
            if (vm.compareMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Выберите 2 полёта", Type.body(15.sp, FontWeight.Medium))
                        T("${vm.picked.size} из 2", Type.body(13.sp), tk.mu)
                    }
                    PrimaryButton("Сравнить", { vm.startCompare() }, height = 52.dp, enabled = vm.picked.size == 2)
                }
            } else {
                PrimaryButton("Импортировать лог", { vm.sheet = true }, Modifier.fillMaxWidth(),
                    marks = false, cornerRadius = 12.dp,
                    leading = { Icon(Icons.Upload, tk.onac, size = 20.dp) })
            }
        }
    }
}

@Composable
private fun EmptyLibrary(vm: AppViewModel, nothing: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Drone, tk.mu, size = 40.dp)
        T(if (nothing) "Библиотека пуста" else "Нет логов этого источника", Type.h2, modifier = Modifier.padding(top = 14.dp))
        T(
            if (nothing) "Импортируйте .bin или .log (ArduPilot) либо .tlog (MAVLink) с телефона — или откройте демо-полёты, чтобы посмотреть возможности."
            else "Поддерживаются ArduPilot DataFlash и телеметрия MAVLink. PX4 ULog, DJI и Blackbox — в работе.",
            Type.body(14.sp), tk.mu, Modifier.padding(top = 6.dp),
        )
        if (nothing) SecondaryButton("Открыть демо-полёты", { vm.importDemo() }, Modifier.padding(top = 18.dp), height = 44.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(vm: AppViewModel, e: LogEntry) {
    val picked = e.id in vm.picked
    var menu by remember { mutableStateOf(false) }
    val u = UnitFmt(vm.units)
    Column(
        Modifier.fillMaxWidth()
            .background(if (picked) tk.ac1 else Color.Transparent)
            .combinedClickable(
                onClick = { if (vm.compareMode) vm.togglePick(e.id) else vm.openFlight(e) },
                onLongClick = { menu = !menu },
            )
            .bottomRule(tk.dv),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.compareMode) { CheckBox(picked); Box(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.hairline(tk.dv).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        T(".${e.format.ext}", Type.cond(12.sp, 0.5.sp), tk.act)
                    }
                    T(e.autopilot.ifEmpty { "—" }, Type.body(13.sp), tk.mu, Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                    T(dateText(e.startUtc ?: e.importedAt), Type.body(13.sp), tk.mu)
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    T(e.fileName, Type.body(16.5.sp, FontWeight.SemiBold), maxLines = 1)
                    T(listOf(e.board, e.firmware).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { e.vehicleType },
                        Type.body(13.5.sp), tk.mu, Modifier.padding(start = 8.dp, bottom = 1.dp).weight(1f), maxLines = 1)
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (dv, du) = u.dist(e.distanceM)
                    T("${durationText(e.flightTime)}   $dv $du", Type.body(13.5.sp), tk.tx, Modifier.weight(1f))
                    StatusDot(statusColor(e.status))
                    T(e.statusText, Type.body(13.5.sp, FontWeight.Medium), statusColor(e.status), Modifier.padding(start = 6.dp))
                }
            }
        }
        if (menu) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Открыть", { menu = false; vm.openFlight(e) }, Modifier.weight(1f), height = 40.dp)
                SecondaryButton("Удалить", { menu = false; vm.delete(e) }, Modifier.weight(1f), height = 40.dp,
                    leading = { Icon(Icons.Trash, tk.cr, size = 18.dp) })
            }
        }
    }
}
