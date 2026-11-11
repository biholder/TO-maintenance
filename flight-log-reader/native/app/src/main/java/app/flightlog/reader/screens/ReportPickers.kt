package app.flightlog.reader.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.Export
import app.flightlog.core.LogFields
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.export.ParamMode
import app.flightlog.reader.ui.CheckBox
import app.flightlog.reader.ui.Chip
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.Segmented
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.card
import app.flightlog.reader.ui.tk

@Composable
private fun PickerHeader(vm: AppViewModel, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(onClick = { vm.back() }, bordered = false) { Icon(Icons.ArrowLeft, tk.tx) }
        Column(Modifier.padding(start = 6.dp).weight(1f)) {
            T(title, Type.cond(22.sp), maxLines = 1)
            T(sub, Type.body(13.sp), tk.mu, maxLines = 1)
        }
        PrimaryButton("Готово", { vm.back() }, height = 40.dp)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(46.dp).card(tk.sf).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Search, tk.mu, size = 18.dp)
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) T(hint, Type.body(15.sp), tk.mu)
            BasicTextField(value, onChange, singleLine = true, textStyle = Type.body(15.sp).copy(color = tk.tx),
                cursorBrush = SolidColor(tk.ac), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconBox(onClick = { onChange("") }, bordered = false) { Icon(Icons.X, tk.mu, size = 18.dp) }
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, sub: String?, onClick: () -> Unit, indent: Boolean = false, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).bottomRule(tk.dv)
        .padding(start = if (indent) 36.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CheckBox(checked, size = 20.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            T(title, Type.body(15.sp, FontWeight.Medium), maxLines = 1)
            if (!sub.isNullOrEmpty()) T(sub, Type.body(12.sp), tk.mu, maxLines = 1)
        }
        if (trailing != null) T(trailing, Type.body(14.sp, FontWeight.SemiBold), tk.act)
    }
}

/** Выбор графиков для отчёта: каналы PLOV и любые поля журнала ArduPilot. */
@Composable
fun ReportChartsScreen(vm: AppViewModel) {
    val p = vm.flight ?: return
    val e = vm.flightEntry ?: return
    val log = p.log
    val fieldsSupported = LogFields.supported(e.format)
    LaunchedEffect(e.id) { vm.loadFieldCatalog() }
    val q = vm.chartQuery.trim()
    val expanded = remember(e.id) { mutableStateListOf<String>() }
    fun labelOf(k: String) = log.series[k]?.label ?: LogFields.parse(k)?.label ?: k
    val channels = log.series.values.filter { q.isEmpty() || it.label.contains(q, true) || it.message.contains(q, true) }
    val catalog = vm.fieldCatalog

    Column(Modifier.fillMaxSize()) {
        PickerHeader(vm, "Графики в отчёте", "Выбрано: ${vm.reportCharts.size}")
        Column(Modifier.padding(horizontal = 16.dp)) {
            SearchField(vm.chartQuery, { vm.chartQuery = it }, if (fieldsSupported) "Поиск: BAT, Volt, RCOU…" else "Поиск канала")
            // Выбранные — в порядке добавления; касание убирает.
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                vm.reportCharts.forEach { k -> Chip("${labelOf(k)}  ×", true, { vm.toggleReportChart(k) }) }
                Chip("По умолчанию", false, { vm.resetReportCharts() })
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            item(key = "h-ch") { Kicker("Каналы PLOV · ${channels.size}", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)) }
            items(channels, key = { "ch:" + it.key }) { s ->
                CheckRow(s.key in vm.reportCharts, s.label, s.message, { vm.toggleReportChart(s.key) })
            }
            if (fieldsSupported) {
                item(key = "h-df") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)) {
                        Kicker("Все поля лога ArduPilot")
                        T(when {
                            vm.fieldCatalogError != null -> "Не удалось прочитать: ${vm.fieldCatalogError}"
                            catalog == null -> "Читаю список сообщений…"
                            else -> "${catalog.size} сообщений. Откройте сообщение и отметьте поля"
                        }, Type.body(12.sp), if (vm.fieldCatalogError != null) tk.cr else tk.mu)
                    }
                }
                catalog?.forEach { m ->
                    // Сообщение с экземплярами показывается отдельно для каждого: BAT[0], BAT[1].
                    val heads = if (m.instances.isEmpty()) listOf<Int?>(null) else m.instances
                    for (inst in heads) {
                        val head = m.name + (inst?.let { "[$it]" } ?: "")
                        val fields = m.fields.filter { f -> q.isEmpty() || head.contains(q, true) || f.contains(q, true) }
                        if (fields.isEmpty()) continue
                        val open = q.isNotEmpty() || head in expanded
                        val picked = fields.count { f -> LogFields.Ref(m.name, inst, f).key in vm.reportCharts }
                        item(key = "m:$head") {
                            Row(Modifier.fillMaxWidth().clickable { if (head in expanded) expanded.remove(head) else expanded.add(head) }
                                .bottomRule(tk.dv).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                T(head, Type.cond(16.sp, 0.3.sp), modifier = Modifier.weight(1f))
                                if (picked > 0) T("выбрано $picked · ", Type.body(12.sp), tk.act)
                                T("${fields.size} ${app.flightlog.core.plural(fields.size, "поле", "поля", "полей")}", Type.body(12.sp), tk.mu)
                                Icon(if (open) Icons.ChevronUp else Icons.ChevronDown, tk.mu, Modifier.padding(start = 6.dp), size = 18.dp)
                            }
                        }
                        if (open) items(fields, key = { f -> "f:$head.$f" }) { f ->
                            val key = LogFields.Ref(m.name, inst, f).key
                            CheckRow(key in vm.reportCharts, f, null, { vm.toggleReportChart(key) }, indent = true)
                        }
                    }
                }
            } else {
                item(key = "h-na") {
                    T("Для этого формата доступны каналы PLOV. Любые поля можно выбрать в логах ArduPilot .bin и .log.",
                        Type.body(12.sp), tk.mu, Modifier.padding(16.dp))
                }
            }
            item(key = "end") { Box(Modifier.height(16.dp)) }
        }
    }
}

/** Выбор параметров для отчёта: изменённые, все или отмеченные вручную. */
@Composable
fun ReportParamsScreen(vm: AppViewModel) {
    val p = vm.flight ?: return
    val all = p.log.params
    val q = vm.reportParamQuery.trim()
    val mode = vm.reportParamMode
    val count = when (mode) {
        ParamMode.CHANGED -> all.count { it.changed }
        ParamMode.ALL -> all.size
        ParamMode.SELECTED -> all.count { it.name in vm.reportParamNames }
    }
    val list = all.filter { q.isEmpty() || it.name.contains(q, true) }
    Column(Modifier.fillMaxSize()) {
        PickerHeader(vm, "Параметры в отчёте", "В отчёт попадёт: $count из ${all.size}")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Segmented(ParamMode.entries.map { it.title }, mode.ordinal, { vm.chooseReportParamMode(ParamMode.entries[it]) })
            T(when (mode) {
                ParamMode.CHANGED -> "Параметры, которые отличаются от значений по умолчанию. Отметьте любой — перейдёте к выбору вручную."
                ParamMode.ALL -> "Все параметры из лога, в отчёте — в две колонки."
                ParamMode.SELECTED -> "Только отмеченные параметры. Выбор запоминается для следующих отчётов."
            }, Type.body(12.sp), tk.mu, Modifier.padding(top = 8.dp))
            SearchField(vm.reportParamQuery, { vm.reportParamQuery = it }, "Поиск: ATC_RAT, BATT, MOT…", Modifier.padding(top = 10.dp))
            if (mode == ParamMode.SELECTED) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (q.isNotEmpty()) Chip("Отметить найденные", false, {
                        list.filter { it.name !in vm.reportParamNames }.forEach { vm.toggleReportParam(it.name) }
                    })
                    Chip("Снять все", false, { vm.clearReportParams() })
                }
            }
        }
        if (all.isEmpty()) {
            T("Параметров в логе нет", Type.body(14.sp), tk.mu, Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(list, key = { it.name }) { pr ->
                val inReport = when (mode) {
                    ParamMode.CHANGED -> pr.changed
                    ParamMode.ALL -> true
                    ParamMode.SELECTED -> pr.name in vm.reportParamNames
                }
                CheckRow(inReport, pr.name, pr.default?.takeIf { pr.changed }?.let { "по умолч. ${Export.paramValue(it)}" },
                    {
                        if (mode != ParamMode.SELECTED) vm.selectParamsManually()
                        vm.toggleReportParam(pr.name)
                    },
                    trailing = Export.paramValue(pr.value))
            }
            item(key = "end") { Box(Modifier.height(16.dp)) }
        }
    }
}
