package app.flightlog.reader.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.ValueGrid
import app.flightlog.reader.ui.hairline
import app.flightlog.reader.ui.tk

private val FORMATS = listOf(
    Triple(".bin", "DataFlash", "ArduPilot"),
    Triple(".log", "Текстовый", "ArduPilot / Mission Planner"),
    Triple(".tlog", "MAVLink", "ArduPilot / PX4"),
    Triple(".csv", "Таблица", "DJI (AirData), PLOV и др."),
    Triple(".ulg", "ULog", "PX4 · скоро"),
    Triple(".txt", "FlightRecord", "DJI · скоро"),
    Triple(".bbl", "Blackbox", "Betaflight / INAV · скоро"),
)

@Composable
fun ImportSheet(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x73000000))
            .clickable(remember { MutableInteractionSource() }, null) { vm.sheet = false },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(tk.bg)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(tk.mk))
            }
            T("Импорт лога", Type.cond(24.sp))
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceRow("FS", "Файл с телефона или облака", "Память, Google Диск, Яндекс Диск, Telegram") {
                    picker.launch(arrayOf("*/*"))
                }
                SourceRow("USB", "Полётный контроллер по USB-OTG", "ArduPilot / PX4 — загрузка с SD через MAVLink FTP · скоро", enabled = false) {}
                SourceRow("DEMO", "Демо-полёты", "Два синтетических полёта ArduCopter для знакомства с приложением") {
                    vm.importDemo()
                }
            }
            Kicker("Поддерживаемые форматы", Modifier.padding(top = 20.dp, bottom = 8.dp))
            ValueGrid(3, FORMATS.map { (ext, name, src) ->
                @Composable {
                    Column(Modifier.padding(10.dp).alpha(if (src.endsWith("скоро")) 0.5f else 1f)) {
                        T(ext, Type.cond(16.sp), tk.act)
                        T(name, Type.body(13.sp, FontWeight.Medium))
                        T(src, Type.body(12.sp), tk.mu, maxLines = 2)
                    }
                }
            })
        }
    }
}

@Composable
private fun SourceRow(tag: String, title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hairline(tk.dv).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).hairline(tk.ac), contentAlignment = Alignment.Center) {
            T(tag, Type.cond(if (tag.length > 3) 11.sp else 13.sp, 0.5.sp), tk.act)
        }
        Box(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(title, Type.body(15.sp, FontWeight.SemiBold))
            T(sub, Type.body(13.sp), tk.mu)
        }
    }
}
