package app.flightlog.reader.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.Units
import app.flightlog.reader.update.Updater
import app.flightlog.reader.ui.CheckBox
import app.flightlog.reader.ui.Icon
import app.flightlog.reader.ui.IconBox
import app.flightlog.reader.ui.Icons
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.bottomRule
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk

/** Настройки: обновления приложения из GitHub, единицы, тема. */
@Composable
fun SettingsScreen(vm: AppViewModel, requestNotifications: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(onClick = { vm.back() }, bordered = false) { Icon(Icons.ArrowLeft, tk.tx) }
            T("Настройки", Type.cond(22.sp), modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp)) {
            Kicker("Обновления", Modifier.padding(bottom = 8.dp))
            Column(Modifier.fillMaxWidth().hairline(tk.dv).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("PLOV ${vm.appVersion}", Type.body(16.sp, FontWeight.SemiBold))
                        T("Обновления из github.com/${Updater.REPO}", Type.body(12.sp), tk.mu)
                    }
                    SecondaryButton(if (vm.updateChecking) "Проверяю…" else "Проверить", { vm.checkUpdates() }, height = 40.dp)
                }
                val r = vm.updateAvailable
                if (r != null) {
                    Column(Modifier.padding(top = 14.dp).fillMaxWidth().background(tk.ac1, RoundedCornerShape(10.dp)).padding(12.dp)) {
                        T("Доступна версия ${r.version}", Type.body(15.sp, FontWeight.SemiBold), tk.act)
                        T(listOfNotNull(r.publishedAt.takeIf { it.isNotEmpty() }, if (r.apkSize > 0) "%.1f МБ".format(r.apkSize / 1048576f) else null)
                            .joinToString(" · "), Type.body(12.sp), tk.mu)
                        // Описание релиза — раздел CHANGELOG в Markdown; показываем как простой текст.
                        val notes = r.notes.substringBefore("\n---").replace("`", "").replace("**", "").trim()
                        if (notes.isNotEmpty()) T(notes.take(1500), Type.body(13.sp), modifier = Modifier.padding(top = 8.dp))
                        val p = vm.updateProgress
                        if (p != null) {
                            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(tk.sf2)) {
                                Box(Modifier.fillMaxWidth(p).height(6.dp).background(tk.ac, RoundedCornerShape(3.dp)))
                            }
                            T("Скачивание… ${(p * 100).toInt()}%", Type.body(12.sp), tk.mu, Modifier.padding(top = 4.dp))
                        } else {
                            PrimaryButton("Скачать и установить", { vm.downloadAndInstall() }, Modifier.padding(top = 12.dp).fillMaxWidth(),
                                height = 44.dp, leading = { Icon(Icons.Download, tk.onac, size = 18.dp) })
                        }
                        T("Перед установкой PLOV проверит, что файл подписан тем же ключом, что и установленное приложение. " +
                            "Данные и библиотека полётов сохранятся.", Type.body(11.sp), tk.mu, Modifier.padding(top = 8.dp))
                    }
                }
                vm.updateMessage?.let { msg ->
                    T(msg, Type.body(13.sp), if (msg.startsWith("Не удалось") || msg.startsWith("Ошибка")) tk.cr else tk.mu, Modifier.padding(top = 10.dp))
                }
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().clickable {
                    val on = !vm.updateAuto
                    vm.setUpdateAutoCheck(on)
                    if (on) requestNotifications()
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(vm.updateAuto, size = 20.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        T("Проверять автоматически", Type.body(14.sp, FontWeight.Medium))
                        T("При запуске и раз в сутки в фоне; о новой версии придёт уведомление", Type.body(12.sp), tk.mu)
                    }
                }
                T("Все версии и список изменений →", Type.body(13.sp, FontWeight.Medium), tk.act,
                    Modifier.padding(top = 8.dp).clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
            }

            Kicker("Отображение", Modifier.padding(top = 24.dp, bottom = 6.dp))
            SettingRow("Единицы", if (vm.units == Units.METRIC) "Метрические: м, м/с, км" else "Имперские: ft, mph, mi") {
                vm.units = if (vm.units == Units.METRIC) Units.IMPERIAL else Units.METRIC
            }
            SettingRow("Тема", when (vm.themeOverride) { null -> "Как в системе"; false -> "Светлая"; true -> "Тёмная" }) {
                vm.themeOverride = when (vm.themeOverride) { null -> false; false -> true; true -> null }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).bottomRule(tk.dv).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, Type.body(15.sp, FontWeight.Medium))
            T(value, Type.body(13.sp), tk.mu)
        }
        Icon(Icons.ChevronRight, tk.mu, size = 20.dp)
    }
}
