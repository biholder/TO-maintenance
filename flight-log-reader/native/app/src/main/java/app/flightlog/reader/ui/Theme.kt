package app.flightlog.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.flightlog.reader.R

/** Токены дизайн-системы Industry (см. design/README.md). */
@Immutable
data class Tokens(
    val dark: Boolean,
    val bg: Color, val sf: Color, val sf2: Color, val tx: Color, val mu: Color, val dv: Color,
    val ac: Color, val act: Color, val ac1: Color, val onac: Color,
    val wr: Color, val wr1: Color, val cr: Color, val ok: Color, val mk: Color, val grid: Color,
    val ramp: List<Color>,
)

val LightTokens = Tokens(
    dark = false,
    bg = Color(0xFFF2F2F3), sf = Color(0xFFE9E9EA), sf2 = Color(0xFFDFE0E2),
    tx = Color(0xFF1D1F20), mu = Color(0xFF5D5D60), dv = Color(0x291D1F20),
    ac = Color(0xFF5980A6), act = Color(0xFF416180), ac1 = Color(0xFFE3EDF7), onac = Color.White,
    wr = Color(0xFF8A5A00), wr1 = Color(0x1FB07400), cr = Color(0xFFA8352A), ok = Color(0xFF3D7A57),
    mk = Color(0x801D1F20), grid = Color(0x0F1D1F20),
    ramp = listOf(0xFF9EBBD8, 0xFF749DC4, 0xFF597EA3, 0xFF416180, 0xFF2C455D, 0xFF1D2D3D).map { Color(it) },
)

val DarkTokens = Tokens(
    dark = true,
    bg = Color(0xFF1B1C1E), sf = Color(0xFF232427), sf2 = Color(0xFF2C2D31),
    tx = Color(0xFFE7E7EA), mu = Color(0xFFA3A3A7), dv = Color(0x29E7E7EA),
    ac = Color(0xFF749DC4), act = Color(0xFF94BCE3), ac1 = Color(0x29749DC4), onac = Color(0xFF0F1720),
    wr = Color(0xFFE2B45C), wr1 = Color(0x24E2B45C), cr = Color(0xFFF08A7A), ok = Color(0xFF7CC59A),
    mk = Color(0x73E7E7EA), grid = Color(0x0DE7E7EA),
    ramp = listOf(0xFF416180, 0xFF597EA3, 0xFF749DC4, 0xFF94BCE3, 0xFFB5D9FD, 0xFFEEF6FF).map { Color(it) },
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)
val BarlowCondensed = FontFamily(Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold))

private const val TNUM = "tnum"

/** Типографика по спецификации. */
object Type {
    fun cond(size: TextUnit, spacing: TextUnit = 0.em) = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = size,
        letterSpacing = spacing, fontFeatureSettings = TNUM,
    )
    fun body(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = Barlow, fontWeight = weight, fontSize = size, lineHeight = size * 1.45f,
        fontFeatureSettings = TNUM,
    )
    val kicker = cond(12.sp, 0.16.em)
    val h1 = cond(36.sp, (-0.01).em).copy(lineHeight = 38.sp)
    val h2 = cond(22.sp)
    val button = cond(15.sp, 0.07.em)
    val tab = cond(15.sp, 0.08.em)
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokens provides if (dark) DarkTokens else LightTokens, content = content)
}
