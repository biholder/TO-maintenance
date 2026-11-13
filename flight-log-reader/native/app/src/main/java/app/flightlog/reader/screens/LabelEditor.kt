package app.flightlog.reader.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flightlog.reader.AppViewModel
import app.flightlog.reader.data.LogEntry
import app.flightlog.reader.ui.Kicker
import app.flightlog.reader.ui.PrimaryButton
import app.flightlog.reader.ui.SecondaryButton
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.card
import app.flightlog.reader.ui.tk

/** Подписи полёта: название, борт, комментарий. Открывается удержанием строки в списке или заголовка полёта. */
@Composable
fun LabelEditor(vm: AppViewModel, e: LogEntry) {
    var title by remember(e.id) { mutableStateOf(e.displayName) }
    var tail by remember(e.id) { mutableStateOf(e.tail) }
    var note by remember(e.id) { mutableStateOf(e.note) }
    Box(
        Modifier.fillMaxSize().background(Color(0x73000000)).statusBarsPadding()
            .clickable(remember { MutableInteractionSource() }, null) { vm.editing = null },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().background(tk.bg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(tk.mk, RoundedCornerShape(2.dp)))
            }
            T("Подписи полёта", Type.cond(24.sp))
            T("Файл ${e.fileName} не меняется — подписи хранятся в библиотеке и попадают в отчёт",
                Type.body(12.sp), tk.mu, Modifier.padding(top = 2.dp))

            Field("Название", title, { title = it }, e.fileName, single = true)
            if (title != e.fileName) {
                T("Вернуть имя файла", Type.body(13.sp, FontWeight.Medium), tk.act,
                    Modifier.padding(top = 6.dp).clickable { title = e.fileName })
            }
            Field("Борт", tail, { tail = it }, "Бортовой номер или позывной, например 101-07", single = true,
                caps = KeyboardCapitalization.Characters)
            Field("Комментарий", note, { note = it.take(500) }, "Задание, погода, замечания по полёту…", single = false)

            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Отмена", { vm.editing = null }, Modifier.weight(1f))
                PrimaryButton("Сохранить", { vm.saveLabels(e, title, tail, note) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, hint: String, single: Boolean,
    caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    Kicker(label, Modifier.padding(top = 16.dp, bottom = 6.dp))
    Box(Modifier.fillMaxWidth().heightIn(min = if (single) 46.dp else 92.dp).card(tk.sf).padding(horizontal = 12.dp, vertical = 12.dp)) {
        if (value.isEmpty()) T(hint, Type.body(15.sp), tk.mu)
        BasicTextField(
            value, onChange, singleLine = single, maxLines = if (single) 1 else 6,
            textStyle = Type.body(15.sp).copy(color = tk.tx), cursorBrush = SolidColor(tk.ac),
            keyboardOptions = KeyboardOptions(capitalization = caps),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
