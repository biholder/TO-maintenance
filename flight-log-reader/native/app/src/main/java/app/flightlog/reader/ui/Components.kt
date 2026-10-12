package app.flightlog.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val tk: Tokens
    @Composable get() = LocalTokens.current

/** Текст с цветом по умолчанию tx. */
@Composable
fun T(
    text: String,
    style: TextStyle = Type.body(),
    color: Color = tk.tx,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = tk.mu) =
    T(text.uppercase(), Type.kicker, color, modifier)

/** «+»-метки регистрации в углах рамки: 11×11, сдвиг −6 от угла, линии 1px цвета mk. */
fun Modifier.regMarks(color: Color): Modifier = drawWithContent {
    drawContent()
    val s = 11.dp.toPx()
    val o = 6.dp.toPx()
    val c = 5.dp.toPx()
    val w = 1.dp.toPx()
    for ((x, y) in listOf(-o to -o, size.width - s + o to -o, -o to size.height - s + o, size.width - s + o to size.height - s + o)) {
        drawLine(color, Offset(x + c, y), Offset(x + c, y + s), w)
        drawLine(color, Offset(x, y + c), Offset(x + s, y + c), w)
    }
}

/** Радиусы скругления интерфейса. */
object Radius {
    val L = 16.dp
    val M = 12.dp
    val S = 8.dp
    val XS = 6.dp
}

/** Рамка 1px со скруглёнными углами; содержимое обрезается по той же форме. */
fun Modifier.hairline(color: Color, width: Dp = 1.dp, radius: Dp = Radius.M): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).border(width, color, shape)
}

/** Скруглённая карточка: фон и, при необходимости, рамка. */
fun Modifier.card(bg: Color, border: Color? = null, radius: Dp = Radius.M): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(bg, shape).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
}

/** Нижняя граница 1px. */
fun Modifier.bottomRule(color: Color) = drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

fun Modifier.topRule(color: Color) = drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    marks: Boolean = false,
    cornerRadius: Dp = Radius.M,
    leading: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier
            .then(if (marks) Modifier.regMarks(tk.mk) else Modifier)
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (pressed) tk.act else tk.ac, shape)
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) Box(Modifier.width(10.dp))
        T(text.uppercase(), Type.cond(if (height >= 50.dp) 17.sp else 15.sp, 1.3.sp), tk.onac)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.height(height).hairline(tk.dv).background(if (pressed) tk.sf else Color.Transparent, RoundedCornerShape(Radius.M))
            .clickable(src, null, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) Box(Modifier.width(8.dp))
        T(text.uppercase(), Type.cond(15.sp, 0.8.sp), tk.tx)
    }
}

/** Квадратная кнопка-иконка 44×44. */
@Composable
fun IconBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    bordered: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.size(44.dp)
            .then(if (bordered) Modifier.hairline(tk.dv) else Modifier.clip(RoundedCornerShape(Radius.M)))
            .background(if (selected) tk.tx else Color.Transparent, RoundedCornerShape(Radius.M))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 34.dp).hairline(if (selected) tk.tx else tk.dv, radius = 17.dp)
            .background(if (selected) tk.tx else Color.Transparent, RoundedCornerShape(17.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, Type.body(14.sp, FontWeight.Medium), if (selected) tk.bg else tk.tx)
    }
}

/** Сегмент-контрол: равные ячейки в рамке 1px, выбранная — заливка tx. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(38.dp).hairline(tk.dv, radius = Radius.M).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Radius.S))
                    .background(if (i == selected) tk.tx else Color.Transparent, RoundedCornerShape(Radius.S))
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { T(o.uppercase(), Type.cond(13.sp, 1.sp), if (i == selected) tk.bg else tk.tx, maxLines = 1) }
        }
    }
}

/** Чекбокс: квадрат 1.5px mk; выбран — заливка ac и «✓». */
@Composable
fun CheckBox(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier.size(size)
            .then(if (checked) Modifier.background(tk.ac, RoundedCornerShape(Radius.XS)) else Modifier.border(1.5.dp, tk.mk, RoundedCornerShape(Radius.XS))),
        contentAlignment = Alignment.Center,
    ) { if (checked) T("✓", Type.body(14.sp, FontWeight.SemiBold), tk.onac) }
}

/** Радио: круг 1.5px; выбранный — кольцо ac и точка 10px. */
@Composable
fun Radio(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(20.dp).border(1.5.dp, if (selected) tk.ac else tk.mk, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(10.dp).background(tk.ac, CircleShape)) }
}

/** Сетка значений: отдельные скруглённые плитки, в ряду — одной высоты. */
@Composable
fun ValueGrid(columns: Int, cells: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f).fillMaxHeight().card(tk.sf, tk.dv)) { cell() }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Ячейка «подпись + крупное число». */
@Composable
fun Stat(label: String, value: String, unit: String = "", color: Color = tk.tx, big: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Kicker(label)
        Row(verticalAlignment = Alignment.Bottom) {
            T(value, Type.cond(if (big) 28.sp else 21.sp), color, maxLines = 1)
            if (unit.isNotEmpty()) T(" $unit", Type.body(13.sp), tk.mu, Modifier.padding(bottom = if (big) 4.dp else 2.dp))
        }
    }
}

/** Квадратный индикатор статуса 7px + текст. */
@Composable
fun StatusDot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).background(color, RoundedCornerShape(2.dp)))
