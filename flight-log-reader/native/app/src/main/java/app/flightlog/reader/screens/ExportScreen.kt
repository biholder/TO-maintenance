package app.flightlog.reader.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.LogReader
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.export.ExportFormat
import app.flightlog.reader.export.Exporter
import app.flightlog.reader.export.PdfSection
import app.flightlog.reader.ui.CheckBox
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.Radio
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk
import app.flightlog.reader.ui.topRule
import java.io.File

@Composable
fun ExportScreen(vm: AppViewModel, share: (File, String) -> Unit) {
    val p = vm.flight ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(onClick = { vm.back() }, bordered = false) { Icon(Icons.ArrowLeft, tk.tx) }
            Column(Modifier.padding(start = 6.dp)) {
                T("Экспорт и отчёт", Type.cond(22.sp))
                T(p.log.fileName, Type.body(13.sp), tk.mu)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Kicker("Формат", Modifier.padding(bottom = 6.dp))
            ExportFormat.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.exFmt = f }.bottomRule(tk.dv).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Radio(vm.exFmt == f)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        T(f.title, Type.body(15.sp, FontWeight.SemiBold))
                        T(f.description, Type.body(13.sp), tk.mu)
                    }
                    T(".${f.ext}", Type.cond(14.sp), tk.act)
                }
            }
            if (vm.exFmt == ExportFormat.PDF) {
                Kicker("Разделы отчёта", Modifier.padding(top = 22.dp, bottom = 8.dp))
                Row {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PdfSection.entries.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable { vm.toggleSection(s) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(s in vm.exSec, size = 20.dp)
                                T(s.title, Type.body(15.sp), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    Thumbnail(vm.exSec.toSet())
                }
            }
            Column(Modifier.padding(top = 20.dp).fillMaxWidth().topRule(tk.dv).padding(top = 12.dp)) {
                Kicker("Файл")
                T(vm.exportFileName(), Type.body(15.sp, FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
                T("≈ ${LogReader.sizeText(Exporter.estimate(p, vm.exFmt, vm.exSec.toSet()))}", Type.body(13.sp), tk.mu)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ready = !vm.exporting && (vm.exFmt != ExportFormat.PDF || vm.exSec.isNotEmpty())
            SecondaryButton("Поделиться", { if (ready) vm.export(true, share) }, Modifier.weight(1f),
                leading = { Icon(Icons.Share, tk.tx, size = 18.dp) })
            PrimaryButton(if (vm.exporting) "Готовлю…" else "Сохранить", { vm.export(false, share) }, Modifier.weight(1f), enabled = ready,
                leading = { Icon(Icons.Download, tk.onac, size = 18.dp) })
        }
    }
}

/** Миниатюра A4, перестраивающаяся по выбранным разделам. */
@Composable
private fun Thumbnail(sections: Set<PdfSection>) {
    val tx = tk.tx; val mu = tk.mu; val ac = tk.ac; val dv = tk.dv; val sf = tk.sf
    Box(Modifier.width(118.dp).height(167.dp).hairline(dv).background(if (tk.dark) sf else Color.White)) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            var y = 0f
            val w = size.width
            drawRect(mu, Offset(0f, y), Size(w * 0.35f, 3f)); y += 7f
            drawRect(tx, Offset(0f, y), Size(w * 0.8f, 7f)); y += 14f
            drawLine(tx, Offset(0f, y), Offset(w, y), 1f); y += 6f
            for (s in PdfSection.entries) {
                if (s !in sections) continue
                if (y > size.height - 8f) break
                drawRect(mu, Offset(0f, y), Size(w * 0.3f, 2.5f)); y += 6f
                when (s) {
                    PdfSection.SUMMARY -> {
                        for (r in 0 until 2) for (c in 0 until 3) drawRect(dv, Offset(c * w / 3, y + r * 11f), Size(w / 3 - 2f, 9f))
                        y += 26f
                    }
                    PdfSection.TRACK -> {
                        drawRect(dv, Offset(0f, y), Size(w, 30f))
                        drawLine(ac, Offset(w * 0.2f, y + 22f), Offset(w * 0.5f, y + 8f), 1.5f)
                        drawLine(ac, Offset(w * 0.5f, y + 8f), Offset(w * 0.8f, y + 18f), 1.5f)
                        y += 36f
                    }
                    PdfSection.CHARTS -> {
                        for (i in 0 until 2) {
                            drawRect(dv, Offset(0f, y), Size(w, 12f))
                            drawLine(ac, Offset(0f, y + 9f), Offset(w * 0.4f, y + 4f), 1f)
                            drawLine(ac, Offset(w * 0.4f, y + 4f), Offset(w, y + 7f), 1f)
                            y += 16f
                        }
                    }
                    PdfSection.EVENTS, PdfSection.PARAMS -> {
                        for (i in 0 until 3) { drawRect(dv, Offset(0f, y), Size(w * (0.9f - i * 0.15f), 2.5f)); y += 5f }
                        y += 4f
                    }
                }
            }
        }
    }
}
