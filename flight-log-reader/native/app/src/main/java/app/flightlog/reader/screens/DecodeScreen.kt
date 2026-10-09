package app.flightlog.reader.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.core.LogReader
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.num
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.regMarks
import app.flightlog.reader.ui.tk
import kotlin.math.roundToInt

@Composable
fun DecodeScreen(vm: AppViewModel) {
    val d = vm.decode ?: return
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 10.dp)) {
                IconBox(onClick = { vm.back() }) { Icon(Icons.X, tk.tx) }
            }
            Kicker("Расшифровка лога", Modifier.padding(top = 18.dp))
            T(d.fileName, Type.cond(28.sp), modifier = Modifier.padding(top = 4.dp), maxLines = 2)
            T("${if (d.sizeBytes > 0) LogReader.sizeText(d.sizeBytes) else "…"} · ${d.source}", Type.body(14.sp), tk.mu)

            // Блок прогресса с метками регистрации.
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().regMarks(tk.mk).hairline(tk.dv).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T("${(d.progress * 100).roundToInt()}", Type.cond(72.sp).copy(lineHeight = 72.sp))
                    T("%", Type.cond(28.sp), tk.mu, Modifier.padding(bottom = 10.dp, start = 2.dp))
                    Box(Modifier.weight(1f))
                    T("${num(d.mbPerSec, 1)} МБ/с", Type.cond(16.sp, 1.sp), tk.mu, Modifier.padding(bottom = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(tk.sf2)) {
                    Box(Modifier.fillMaxWidth(d.progress.coerceIn(0f, 1f)).height(6.dp).background(tk.ac))
                }
            }

            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                d.stages.forEachIndexed { i, title ->
                    val state = when {
                        d.done || i < d.current -> 2
                        i == d.current && d.error == null -> 1
                        else -> 0
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(18.dp).then(
                                when (state) {
                                    2 -> Modifier.background(tk.ac)
                                    1 -> Modifier.border(2.dp, tk.ac)
                                    else -> Modifier.border(1.5.dp, tk.mk)
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { if (state == 2) T("✓", Type.body(12.sp, FontWeight.SemiBold), tk.onac) }
                        Box(Modifier.width(12.dp))
                        T(title, Type.body(15.sp), if (state == 0) tk.mu else tk.tx, Modifier.weight(1f))
                        d.results[i]?.let { T(it, Type.body(13.sp), tk.mu, Modifier.padding(start = 8.dp), maxLines = 1) }
                    }
                }
            }

            d.djiKeyVersion?.let { v -> DjiKeyBlock(vm, v, d.djiBusy) }
            d.error?.let { err ->
                Column(Modifier.padding(top = 24.dp).fillMaxWidth().background(tk.wr1).padding(14.dp)) {
                    Kicker("Ошибка разбора", color = tk.cr)
                    T(err, Type.body(15.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (d.done) {
                Column(Modifier.padding(top = 24.dp).fillMaxWidth().background(tk.ac1).hairline(tk.ac).padding(14.dp)) {
                    Kicker("Формат определён", color = tk.act)
                    T(d.formatTitle, Type.body(15.sp, FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
                }
            }
            Box(Modifier.height(16.dp))
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            when {
                d.done -> PrimaryButton("Открыть полёт", { vm.openDecoded() }, Modifier.fillMaxWidth())
                d.djiKeyVersion != null -> SecondaryButton("Отмена", { vm.back() }, Modifier.fillMaxWidth())
                d.error != null -> SecondaryButton("Назад", { vm.back() }, Modifier.fillMaxWidth())
                else -> SecondaryButton("Отмена", { vm.back() }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Лог DJI v13+ зашифрован: ввод API-ключа DJI Open API и запрос ключей AES. */
@Composable
private fun DjiKeyBlock(vm: AppViewModel, version: Int, busy: Boolean) {
    var key by remember { mutableStateOf(vm.djiApiKey) }
    Column(Modifier.padding(top = 24.dp).fillMaxWidth().background(tk.ac1).hairline(tk.ac).padding(14.dp)) {
        Kicker("Нужен ключ DJI", color = tk.act)
        T("Лог DJI версии $version зашифрован. Ключи расшифровки к каждому файлу выдаёт только DJI — " +
            "по бесплатному API-ключу разработчика. Получить ключи нужно один раз: они сохранятся рядом с логом.",
            Type.body(14.sp), modifier = Modifier.padding(top = 6.dp))
        T("Где взять API-ключ: developer.dji.com → войти → Create App → тип «Open API» → активировать по письму → " +
            "в карточке приложения скопировать SDK key.",
            Type.body(13.sp), tk.mu, Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(46.dp).background(tk.bg).hairline(tk.dv).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart) {
            if (key.isEmpty()) T("API-ключ DJI (SDK key)", Type.body(15.sp), tk.mu)
            BasicTextField(
                key, { key = it }, singleLine = true,
                textStyle = Type.body(15.sp).copy(color = tk.tx), cursorBrush = SolidColor(tk.ac),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrimaryButton(if (busy) "Запрашиваю ключи…" else "Получить ключи и открыть", { vm.fetchDjiKeys(key) },
            Modifier.padding(top = 12.dp).fillMaxWidth(), height = 44.dp, enabled = !busy && key.isNotBlank(), marks = false)
        T("В DJI отправляются только зашифрованные ключи из лога, без координат и данных полёта.",
            Type.body(12.sp), tk.mu, Modifier.padding(top = 8.dp))
    }
}
