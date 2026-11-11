package app.flightlog.reader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Фон заставки; тот же цвет у системной заставки Android 12+, чтобы не было вспышки. */
val SplashGray = Color(0xFF5A5D62)

/** Длительность заставки, мс. */
private const val DURATION = 2000

/** Поля журнала, которые «вылетают» из полётного контроллера. */
private val FIELDS = listOf(
    "ATT.Roll", "GPS.NSats", "BAT.Volt", "VIBE.VibeZ", "XKF4.SV", "RCOU.C1",
    "MAG.MagX", "CTUN.Alt", "BAT.Curr", "RSSI", "GPS.HDop", "MODE", "ERR", "PARM",
)

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun phase(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))
private fun smooth(x: Float) = x * x * (3 - 2 * x)

/**
 * Заставка при запуске: белый VTOL пролетает снизу вверх по серому фону, сменяется схемой
 * полётного контроллера, из которого разлетаются имена полей лога. Две секунды, затем [onDone].
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DURATION, easing = LinearEasing))
        onDone()
    }
    val measurer = rememberTextMeasurer()
    val style = Type.cond(13.sp, 0.04.sp)
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value * DURATION / 1000f // секунды
        // Последние 0,2 с — растворение в интерфейс приложения.
        val fadeOut = 1f - phase(t, 1.8f, 2.0f)
        drawRect(SplashGray.copy(alpha = fadeOut))
        val unit = minOf(size.width, size.height)
        val c = Offset(size.width / 2, size.height / 2)

        // 1. VTOL снизу вверх (0–1,05 с), к концу пролёта растворяется.
        val fly = phase(t, 0f, 1.05f)
        val droneAlpha = (1f - phase(t, 0.75f, 1.05f)) * fadeOut
        if (droneAlpha > 0f) {
            val y = size.height + unit * 0.35f - smooth(fly) * (size.height + unit * 0.7f)
            drawVtol(Offset(c.x, y), unit * 0.62f, t, Color.White.copy(alpha = droneAlpha))
        }

        // 2. Полётный контроллер проявляется (0,8–1,15 с).
        val fcIn = smooth(phase(t, 0.8f, 1.15f))
        val fcAlpha = fcIn * fadeOut
        if (fcAlpha > 0f) {
            scale(0.85f + 0.15f * fcIn, c) {
                drawController(c, unit * 0.36f, Color.White.copy(alpha = fcAlpha))
            }
        }

        // 3. Строки полей вылетают из контроллера (1,0–1,9 с), по одной с небольшой задержкой.
        FIELDS.forEachIndexed { i, text ->
            val start = 1.0f + i * 0.045f
            val k = phase(t, start, start + 0.6f)
            if (k <= 0f || k >= 1f) return@forEachIndexed
            val angle = -PI / 2 + i * (2 * PI / FIELDS.size) + 0.3
            val dist = unit * (0.16f + 0.42f * smooth(k))
            val alpha = (if (k < 0.15f) k / 0.15f else 1f - phase(k, 0.55f, 1f)) * fadeOut
            val layout = measurer.measure(text, style.copy(color = Color.White.copy(alpha = alpha)))
            val p = Offset(c.x + (cos(angle) * dist).toFloat(), c.y + (sin(angle) * dist).toFloat())
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

/** Конвертоплан-квадроплан сверху: фюзеляж, крыло, две балки с четырьмя подъёмными винтами, V-хвост, толкающий винт. */
private fun DrawScope.drawVtol(center: Offset, span: Float, t: Float, color: Color) {
    translate(center.x, center.y) {
        val s = span / 2f // полуразмах
        // Крыло с небольшой стреловидностью.
        val wing = Path().apply {
            moveTo(-s, s * 0.02f); lineTo(-s * 0.95f, -s * 0.1f)
            lineTo(s * 0.95f, -s * 0.1f); lineTo(s, s * 0.02f)
            lineTo(s * 0.12f, s * 0.12f); lineTo(-s * 0.12f, s * 0.12f); close()
        }
        drawPath(wing, color)
        // Фюзеляж.
        drawRoundRect(color, Offset(-s * 0.08f, -s * 0.55f), Size(s * 0.16f, s * 1.15f), CornerRadius(s * 0.08f))
        // V-хвост.
        val tail = Path().apply {
            moveTo(0f, s * 0.48f); lineTo(-s * 0.3f, s * 0.68f); lineTo(-s * 0.26f, s * 0.74f); lineTo(0f, s * 0.6f)
            lineTo(s * 0.26f, s * 0.74f); lineTo(s * 0.3f, s * 0.68f); close()
        }
        drawPath(tail, color)
        // Балки и подъёмные винты.
        val boom = s * 0.5f
        for (side in listOf(-1f, 1f)) {
            val bx = side * boom
            drawLine(color, Offset(bx, -s * 0.5f), Offset(bx, s * 0.42f), s * 0.05f, StrokeCap.Round)
            for (by in listOf(-s * 0.5f, s * 0.42f)) {
                drawCircle(color.copy(alpha = color.alpha * 0.25f), s * 0.2f, Offset(bx, by))
                drawCircle(color, s * 0.2f, Offset(bx, by), style = Stroke(s * 0.015f))
                // Вращающиеся лопасти.
                rotate(t * 2400f * side + by, Offset(bx, by)) {
                    drawLine(color, Offset(bx - s * 0.18f, by), Offset(bx + s * 0.18f, by), s * 0.03f, StrokeCap.Round)
                }
                drawCircle(color, s * 0.035f, Offset(bx, by))
            }
        }
        // Толкающий винт в хвосте фюзеляжа.
        drawLine(color, Offset(-s * 0.12f, s * 0.62f), Offset(s * 0.12f, s * 0.62f), s * 0.025f, StrokeCap.Round)
    }
}

/** Схема полётного контроллера: плата, процессор с выводами, разъёмы по краям, стрелка «вперёд». */
private fun DrawScope.drawController(c: Offset, size: Float, color: Color) {
    val half = size / 2
    val stroke = size * 0.022f
    drawRoundRect(color, Offset(c.x - half, c.y - half), Size(size, size), CornerRadius(size * 0.08f), style = Stroke(stroke))
    // Крепёжные отверстия.
    for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) {
        drawCircle(color, size * 0.035f, Offset(c.x + dx * half * 0.8f, c.y + dy * half * 0.8f), style = Stroke(stroke * 0.8f))
    }
    // Процессор с выводами.
    val chip = size * 0.34f
    drawRect(color, Offset(c.x - chip / 2, c.y - chip / 2), Size(chip, chip), style = Stroke(stroke))
    for (k in 0 until 5) {
        val o = -chip / 2 + chip * (k + 0.5f) / 5
        val len = size * 0.05f
        drawLine(color, Offset(c.x + o, c.y - chip / 2), Offset(c.x + o, c.y - chip / 2 - len), stroke * 0.7f)
        drawLine(color, Offset(c.x + o, c.y + chip / 2), Offset(c.x + o, c.y + chip / 2 + len), stroke * 0.7f)
        drawLine(color, Offset(c.x - chip / 2, c.y + o), Offset(c.x - chip / 2 - len, c.y + o), stroke * 0.7f)
        drawLine(color, Offset(c.x + chip / 2, c.y + o), Offset(c.x + chip / 2 + len, c.y + o), stroke * 0.7f)
    }
    // Разъёмы по краям платы.
    for (k in 0 until 3) {
        val o = -size * 0.22f + k * size * 0.22f
        drawRect(color, Offset(c.x + o - size * 0.06f, c.y + half - size * 0.1f), Size(size * 0.12f, size * 0.06f), style = Stroke(stroke * 0.7f))
        drawRect(color, Offset(c.x - half + size * 0.04f, c.y + o - size * 0.04f), Size(size * 0.06f, size * 0.08f), style = Stroke(stroke * 0.7f))
        drawRect(color, Offset(c.x + half - size * 0.1f, c.y + o - size * 0.04f), Size(size * 0.06f, size * 0.08f), style = Stroke(stroke * 0.7f))
    }
    // Стрелка направления вперёд.
    val a = Path().apply {
        moveTo(c.x, c.y - half + size * 0.06f)
        lineTo(c.x - size * 0.06f, c.y - half + size * 0.15f)
        lineTo(c.x + size * 0.06f, c.y - half + size * 0.15f)
        close()
    }
    drawPath(a, color)
}
