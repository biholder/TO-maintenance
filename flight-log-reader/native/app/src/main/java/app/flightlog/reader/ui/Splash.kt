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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Фон заставки; тот же цвет у системной заставки Android 12+, чтобы не было вспышки. */
val SplashGray = Color(0xFF5A5D62)

/** Детали поверх белого корпуса: панели, рули, камера. */
private val Detail = Color(0xFF8C9096)
private val NavRed = Color(0xFFFF5A4E)
private val NavGreen = Color(0xFF5CE08A)
private val LedBlue = Color(0xFF6FB6FF)

/** Длительность заставки, мс. */
private const val DURATION = 3200

/** Строки данных, которые «вылетают» из полётного контроллера: поле лога и значение. */
private val FIELDS = listOf(
    "ATT.Roll  −2.4°", "GPS.NSats  17", "BAT.Volt  24.6 В", "VIBE.VibeZ  12.3", "XKF4.SV  0.21",
    "RCOU.C1  1540", "MAG.MagX  212", "CTUN.Alt  48.2 м", "BAT.Curr  31.5 А", "RSSI  92 %",
    "GPS.HDop  0.7", "MODE  LOITER", "ESC.RPM  8650", "BARO.Press  99840",
)

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun phase(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))
private fun smooth(x: Float) = x * x * (3 - 2 * x)
private fun easeOut(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)

/**
 * Заставка при запуске (3,2 с):
 * - 0–1,5 с: белый VTOL пролетает снизу вверх над серой «землёй» с сеткой и облаками, мигают навигационные огни;
 * - 1,2–2,1 с: прорисовывается схема полётного контроллера — плата, процессор, датчики, дорожки, разъёмы;
 * - 1,9–3,0 с: по дорожкам бегут импульсы, из платы вылетают строки данных лога; появляется надпись PLOV;
 * - 2,95–3,2 с: растворение в интерфейс. Затем [onDone].
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DURATION, easing = LinearEasing))
        onDone()
    }
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value * DURATION / 1000f // секунды
        val fadeOut = 1f - phase(t, 2.95f, 3.2f)
        drawRect(SplashGray.copy(alpha = fadeOut))
        val unit = minOf(size.width, size.height)
        val c = Offset(size.width / 2, size.height / 2)

        // 1. Пролёт VTOL.
        val fly = smooth(phase(t, 0f, 1.5f))
        val sceneAlpha = (1f - phase(t, 1.15f, 1.5f)) * fadeOut
        if (sceneAlpha > 0f) {
            drawGround(t, fly, unit, sceneAlpha)
            val y = size.height + unit * 0.4f - fly * (size.height + unit * 0.8f)
            val sway = 4f * sin(t * 3.2f)
            rotate(sway, Offset(c.x, y)) {
                // Тень на «земле» — со смещением, как от солнца сбоку.
                translate(unit * 0.07f, unit * 0.11f) {
                    scale(0.9f, Offset(c.x, y)) { drawVtol(Offset(c.x, y), unit * 0.62f, t, sceneAlpha, shadow = true) }
                }
                drawVtol(Offset(c.x, y), unit * 0.62f, t, sceneAlpha, shadow = false)
            }
        }

        // 2. Полётный контроллер.
        val fcAlpha = phase(t, 1.2f, 1.35f) * fadeOut
        if (fcAlpha > 0f) {
            val grow = 0.9f + 0.1f * smooth(phase(t, 1.2f, 1.7f))
            scale(grow, c) { drawController(c, unit * 0.4f, t, fcAlpha, measurer) }
        }

        // 3. Строки данных вылетают из платы, каждая со своей задержкой.
        val style = Type.cond(13.sp, 0.04.sp)
        // Разлёт по эллипсу, вытянутому вдоль длинной стороны экрана, — строки не уходят за край.
        val portrait = size.height >= size.width
        val kx = if (portrait) 0.45f else 1.3f
        val ky = if (portrait) 1.6f else 0.8f
        FIELDS.forEachIndexed { i, text ->
            val start = 1.9f + i * 0.035f
            val k = phase(t, start, start + 0.75f)
            if (k <= 0f || k >= 1f) return@forEachIndexed
            // По кругу, кроме сектора ±45° внизу — там появляется надпись.
            val angle = PI * 0.75 + i * (PI * 1.5 / (FIELDS.size - 1))
            // Соседние строки — на разном удалении, чтобы не наезжали друг на друга.
            val dist = unit * (0.24f + 0.2f * easeOut(k) + (i % 2) * 0.1f)
            val alpha = (if (k < 0.12f) k / 0.12f else 1f - phase(k, 0.6f, 1f)) * fadeOut
            val layout = measurer.measure(text, style.copy(color = Color.White.copy(alpha = alpha)))
            val p = Offset(c.x + (cos(angle) * dist * kx).toFloat(), c.y + (sin(angle) * dist * ky).toFloat())
            // Справа от платы строка начинается в точке разлёта, слева заканчивается в ней, сверху — по центру.
            val cosA = cos(angle).toFloat()
            val w = layout.size.width.toFloat()
            val x0 = when {
                cosA > 0.25f -> p.x
                cosA < -0.25f -> p.x - w
                else -> p.x - w / 2
            }
            drawCircle(Color.White.copy(alpha = alpha * 0.8f), unit * 0.006f, Offset(x0 - unit * 0.015f, p.y))
            drawText(layout, topLeft = Offset(x0, p.y - layout.size.height / 2f))
        }

        // 4. Надпись под платой.
        val title = smooth(phase(t, 2.25f, 2.7f)) * fadeOut
        if (title > 0f) {
            val word = measurer.measure("PLOV", Type.cond(30.sp, 0.3.sp).copy(color = Color.White.copy(alpha = title)))
            val sub = measurer.measure("АНАЛИЗ ПОЛЁТНЫХ ЛОГОВ", Type.cond(11.sp, 0.2.sp).copy(color = Color.White.copy(alpha = title * 0.7f)))
            val top = size.height * 0.84f - word.size.height + (1 - title) * unit * 0.03f
            drawText(word, topLeft = Offset(c.x - word.size.width / 2f, top))
            drawText(sub, topLeft = Offset(c.x - sub.size.width / 2f, top + word.size.height))
        }
    }
}

/** «Земля» под бортом: сетка и облака уходят вниз — видно движение вверх. Слева шкала высоты. */
private fun DrawScope.drawGround(t: Float, fly: Float, unit: Float, alpha: Float) {
    val step = unit * 0.14f
    val shift = (fly * size.height * 1.4f) % step
    val grid = Color.White.copy(alpha = 0.06f * alpha)
    var x = (size.width / 2) % step
    while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = shift - step
    while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    // Облака быстрее сетки — параллакс.
    val clouds = listOf(Triple(0.2f, 0.1f, 0.5f), Triple(0.78f, 0.55f, 0.38f), Triple(0.35f, 0.95f, 0.45f))
    for ((cx, cy, r) in clouds) {
        val py = ((cy * size.height + fly * size.height * 2.2f) % (size.height * 1.4f)) - size.height * 0.2f
        val w = unit * r
        drawOval(Color.White.copy(alpha = 0.07f * alpha), Offset(cx * size.width - w / 2, py - w * 0.18f), Size(w, w * 0.36f))
        drawOval(Color.White.copy(alpha = 0.05f * alpha), Offset(cx * size.width - w * 0.3f, py - w * 0.3f), Size(w * 0.55f, w * 0.34f))
    }
    // Шкала высоты: метки бегут вниз.
    val tick = Color.White.copy(alpha = 0.35f * alpha)
    val tickStep = unit * 0.07f
    var ty = (fly * size.height * 1.4f) % tickStep - tickStep
    var n = 0
    while (ty < size.height) {
        val long = n % 5 == 0
        drawLine(tick, Offset(unit * 0.04f, ty), Offset(unit * (if (long) 0.09f else 0.065f), ty), 1.5f)
        ty += tickStep; n++
    }
}

/** Конвертоплан-квадроплан сверху, носом вверх. [shadow] — та же форма полупрозрачной тенью. */
private fun DrawScope.drawVtol(center: Offset, span: Float, t: Float, alpha: Float, shadow: Boolean) {
    val body = if (shadow) Color.Black.copy(alpha = 0.18f * alpha) else Color.White.copy(alpha = alpha)
    val detail = Detail.copy(alpha = alpha)
    translate(center.x, center.y) {
        val s = span / 2f // полуразмах
        // Крыло с сужением к законцовкам и небольшими винглетами.
        val wing = Path().apply {
            moveTo(-s, -s * 0.06f); lineTo(-s * 0.12f, -s * 0.16f); lineTo(s * 0.12f, -s * 0.16f); lineTo(s, -s * 0.06f)
            lineTo(s, s * 0.03f); lineTo(s * 0.12f, s * 0.12f); lineTo(-s * 0.12f, s * 0.12f); lineTo(-s, s * 0.03f); close()
        }
        drawPath(wing, body)
        // Фюзеляж.
        drawRoundRect(body, Offset(-s * 0.085f, -s * 0.58f), Size(s * 0.17f, s * 1.2f), CornerRadius(s * 0.085f))
        // V-хвост.
        val tail = Path().apply {
            moveTo(0f, s * 0.46f); lineTo(-s * 0.32f, s * 0.68f); lineTo(-s * 0.28f, s * 0.75f); lineTo(0f, s * 0.6f)
            lineTo(s * 0.28f, s * 0.75f); lineTo(s * 0.32f, s * 0.68f); close()
        }
        drawPath(tail, body)
        // Балки.
        val boom = s * 0.5f
        for (side in listOf(-1f, 1f)) {
            drawLine(body, Offset(side * boom, -s * 0.52f), Offset(side * boom, s * 0.44f), s * 0.05f, StrokeCap.Round)
        }
        if (shadow) return@translate

        // Детали корпуса.
        val thin = s * 0.012f
        for (side in listOf(-1f, 1f)) {
            // Элероны и закрылки по задней кромке.
            drawLine(detail, Offset(side * s * 0.58f, s * 0.045f), Offset(side * s * 0.95f, s * 0.02f), thin)
            drawLine(detail, Offset(side * s * 0.58f, s * 0.045f), Offset(side * s * 0.58f, s * 0.085f), thin)
            drawLine(detail, Offset(side * s * 0.16f, s * 0.1f), Offset(side * s * 0.42f, s * 0.07f), thin)
            // Рули на V-хвосте.
            drawLine(detail, Offset(side * s * 0.1f, s * 0.6f), Offset(side * s * 0.27f, s * 0.7f), thin)
        }
        // Панель на фюзеляже, камера в носу, приёмник воздушного давления.
        drawLine(detail, Offset(0f, -s * 0.36f), Offset(0f, s * 0.38f), thin)
        drawLine(detail, Offset(-s * 0.06f, -s * 0.2f), Offset(s * 0.06f, -s * 0.2f), thin)
        drawCircle(detail, s * 0.05f, Offset(0f, -s * 0.47f))
        drawCircle(Color(0xFF2B2D30).copy(alpha = alpha), s * 0.025f, Offset(0f, -s * 0.47f))
        drawLine(Color.White.copy(alpha = alpha), Offset(0f, -s * 0.58f), Offset(0f, -s * 0.66f), thin * 1.2f, StrokeCap.Round)

        // Подъёмные винты: диск размытия, двухлопастной винт, мотор.
        for (side in listOf(-1f, 1f)) {
            val bx = side * boom
            for (by in listOf(-s * 0.52f, s * 0.44f)) {
                val pc = Offset(bx, by)
                drawCircle(Color.White.copy(alpha = alpha * 0.16f), s * 0.21f, pc)
                drawCircle(Color.White.copy(alpha = alpha * 0.55f), s * 0.21f, pc, style = Stroke(thin))
                rotate(t * 2000f * side + by, pc) {
                    for (blade in listOf(-1f, 1f)) {
                        drawOval(Color.White.copy(alpha = alpha),
                            Offset(bx + (if (blade < 0) -s * 0.2f else s * 0.02f), by - s * 0.018f), Size(s * 0.18f, s * 0.036f))
                    }
                }
                drawCircle(detail, s * 0.045f, pc)
                drawCircle(Color.White.copy(alpha = alpha), s * 0.02f, pc)
            }
        }
        // Толкающий винт: размытая полоса.
        drawOval(Color.White.copy(alpha = alpha * 0.45f), Offset(-s * 0.14f, s * 0.6f), Size(s * 0.28f, s * 0.05f))

        // Навигационные огни: красный — левое крыло, зелёный — правое, белый строб в хвосте.
        val glow = 0.6f + 0.4f * sin(t * 9f)
        drawCircle(NavRed.copy(alpha = alpha * 0.35f * glow), s * 0.07f, Offset(-s, -s * 0.015f))
        drawCircle(NavRed.copy(alpha = alpha), s * 0.025f, Offset(-s, -s * 0.015f))
        drawCircle(NavGreen.copy(alpha = alpha * 0.35f * glow), s * 0.07f, Offset(s, -s * 0.015f))
        drawCircle(NavGreen.copy(alpha = alpha), s * 0.025f, Offset(s, -s * 0.015f))
        if ((t * 2.5f) % 1f < 0.12f) {
            drawCircle(Color.White.copy(alpha = alpha * 0.5f), s * 0.1f, Offset(0f, s * 0.62f))
            drawCircle(Color.White.copy(alpha = alpha), s * 0.03f, Offset(0f, s * 0.62f))
        }
    }
}

/** Часть пути [path] от начала до доли [k] — эффект прорисовки линии. */
private fun DrawScope.drawPartial(path: Path, k: Float, color: Color, width: Float) {
    if (k <= 0f) return
    if (k >= 1f) { drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round)); return }
    val pm = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    pm.getSegment(0f, pm.length * k, part, true)
    drawPath(part, color, style = Stroke(width, cap = StrokeCap.Round))
}

private fun DrawScope.label(m: TextMeasurer, text: String, center: Offset, size: Float, color: Color) {
    val l = m.measure(text, TextStyle(fontFamily = BarlowCondensed, fontSize = (size).sp, color = color))
    drawText(l, topLeft = Offset(center.x - l.size.width / 2f, center.y - l.size.height / 2f))
}

/**
 * Схема полётного контроллера, прорисовывается по частям: контур платы, процессор с выводами,
 * датчики (IMU, барометр), дорожки к разъёмам, подписи разъёмов, светодиоды и импульсы данных.
 */
private fun DrawScope.drawController(c: Offset, size: Float, t: Float, alpha: Float, m: TextMeasurer) {
    val white = Color.White.copy(alpha = alpha)
    val half = size / 2
    val stroke = size * 0.018f
    val left = c.x - half; val top = c.y - half

    // Контур платы.
    val board = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + size, top + size, CornerRadius(size * 0.07f))) }
    drawPartial(board, phase(t, 1.2f, 1.55f), white, stroke)
    val holes = phase(t, 1.45f, 1.6f)
    if (holes > 0f) for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) {
        drawCircle(white.copy(alpha = alpha * holes), size * 0.032f, Offset(c.x + dx * half * 0.82f, c.y + dy * half * 0.82f), style = Stroke(stroke * 0.7f))
    }

    // Процессор с выводами и маркировкой.
    val chipK = phase(t, 1.35f, 1.65f)
    val chip = size * 0.3f
    if (chipK > 0f) {
        val a = alpha * chipK
        drawRect(white.copy(alpha = a), Offset(c.x - chip / 2, c.y - chip / 2), Size(chip, chip), style = Stroke(stroke))
        drawCircle(white.copy(alpha = a), size * 0.012f, Offset(c.x - chip * 0.36f, c.y - chip * 0.36f))
        val pins = (7 * chipK).toInt()
        for (k in 0 until pins) {
            val o = -chip / 2 + chip * (k + 0.5f) / 7
            val len = size * 0.035f
            drawLine(white.copy(alpha = a), Offset(c.x + o, c.y - chip / 2), Offset(c.x + o, c.y - chip / 2 - len), stroke * 0.55f)
            drawLine(white.copy(alpha = a), Offset(c.x + o, c.y + chip / 2), Offset(c.x + o, c.y + chip / 2 + len), stroke * 0.55f)
            drawLine(white.copy(alpha = a), Offset(c.x - chip / 2, c.y + o), Offset(c.x - chip / 2 - len, c.y + o), stroke * 0.55f)
            drawLine(white.copy(alpha = a), Offset(c.x + chip / 2, c.y + o), Offset(c.x + chip / 2 + len, c.y + o), stroke * 0.55f)
        }
        label(m, "STM32H7", Offset(c.x, c.y), 8f, white.copy(alpha = a * 0.85f))
    }

    // Датчики: IMU и барометр.
    val sens = phase(t, 1.5f, 1.75f)
    if (sens > 0f) {
        val a = alpha * sens
        val imu = Offset(c.x - size * 0.27f, c.y - size * 0.25f)
        drawRect(white.copy(alpha = a), Offset(imu.x - size * 0.07f, imu.y - size * 0.07f), Size(size * 0.14f, size * 0.14f), style = Stroke(stroke * 0.8f))
        label(m, "IMU", imu, 7f, white.copy(alpha = a * 0.85f))
        val baro = Offset(c.x + size * 0.27f, c.y - size * 0.27f)
        drawRect(white.copy(alpha = a), Offset(baro.x - size * 0.08f, baro.y - size * 0.045f), Size(size * 0.16f, size * 0.09f), style = Stroke(stroke * 0.8f))
        label(m, "BARO", baro, 7f, white.copy(alpha = a * 0.85f))
    }

    // Дорожки от процессора к разъёмам; по ним потом бегут импульсы данных.
    val traces = listOf(
        listOf(Offset(c.x - chip * 0.2f, c.y - chip / 2), Offset(c.x - chip * 0.2f, top + size * 0.12f), Offset(c.x - size * 0.14f, top + size * 0.06f)),
        listOf(Offset(c.x + chip * 0.2f, c.y - chip / 2), Offset(c.x + chip * 0.2f, top + size * 0.12f), Offset(c.x + size * 0.14f, top + size * 0.06f)),
        listOf(Offset(c.x - chip / 2, c.y + chip * 0.15f), Offset(left + size * 0.16f, c.y + chip * 0.15f), Offset(left + size * 0.06f, c.y + size * 0.05f)),
        listOf(Offset(c.x + chip / 2, c.y + chip * 0.15f), Offset(left + size * 0.84f, c.y + chip * 0.15f), Offset(left + size * 0.94f, c.y + size * 0.05f)),
        listOf(Offset(c.x - chip * 0.25f, c.y + chip / 2), Offset(c.x - chip * 0.25f, top + size * 0.8f), Offset(c.x - size * 0.22f, top + size * 0.9f)),
        listOf(Offset(c.x, c.y + chip / 2), Offset(c.x, top + size * 0.9f)),
        listOf(Offset(c.x + chip * 0.25f, c.y + chip / 2), Offset(c.x + chip * 0.25f, top + size * 0.8f), Offset(c.x + size * 0.22f, top + size * 0.9f)),
    ).map { pts -> Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } } }
    val traceK = phase(t, 1.55f, 1.9f)
    traces.forEach { drawPartial(it, traceK, white.copy(alpha = alpha * 0.6f), stroke * 0.5f) }

    // Разъёмы с подписями.
    val conn = phase(t, 1.7f, 1.95f)
    if (conn > 0f) {
        val a = alpha * conn
        val pad = stroke * 0.7f
        fun connector(center: Offset, w: Float, h: Float, name: String, labelAt: Offset) {
            drawRect(white.copy(alpha = a), Offset(center.x - w / 2, center.y - h / 2), Size(w, h), style = Stroke(pad))
            label(m, name, labelAt, 7f, white.copy(alpha = a * 0.8f))
        }
        connector(Offset(c.x - size * 0.14f, top + size * 0.04f), size * 0.16f, size * 0.05f, "GPS", Offset(c.x - size * 0.14f, top - size * 0.05f))
        connector(Offset(c.x + size * 0.14f, top + size * 0.04f), size * 0.16f, size * 0.05f, "TELEM", Offset(c.x + size * 0.14f, top - size * 0.05f))
        connector(Offset(left + size * 0.04f, c.y + size * 0.05f), size * 0.05f, size * 0.16f, "RC", Offset(left - size * 0.06f, c.y + size * 0.05f))
        connector(Offset(left + size * 0.96f, c.y + size * 0.05f), size * 0.05f, size * 0.16f, "PWM", Offset(left + size * 1.08f, c.y + size * 0.05f))
        connector(Offset(c.x - size * 0.22f, top + size * 0.93f), size * 0.12f, size * 0.05f, "I2C", Offset(c.x - size * 0.22f, top + size * 1.04f))
        connector(Offset(c.x, top + size * 0.93f), size * 0.12f, size * 0.05f, "USB", Offset(c.x, top + size * 1.04f))
        connector(Offset(c.x + size * 0.22f, top + size * 0.93f), size * 0.12f, size * 0.05f, "CAN", Offset(c.x + size * 0.22f, top + size * 1.04f))
    }

    // Светодиоды состояния: зелёный «дышит», синий мигает.
    val leds = phase(t, 1.8f, 1.95f)
    if (leds > 0f) {
        val g = leds * (0.55f + 0.45f * sin(t * 8f))
        val ledPos = Offset(left + size * 0.82f, top + size * 0.62f)
        drawCircle(NavGreen.copy(alpha = alpha * g * 0.35f), size * 0.04f, ledPos)
        drawCircle(NavGreen.copy(alpha = alpha * leds), size * 0.016f, ledPos)
        val blue = if ((t * 3f) % 1f < 0.5f) 1f else 0.25f
        val bluePos = Offset(left + size * 0.82f, top + size * 0.7f)
        drawCircle(LedBlue.copy(alpha = alpha * leds * blue * 0.35f), size * 0.04f, bluePos)
        drawCircle(LedBlue.copy(alpha = alpha * leds * blue), size * 0.016f, bluePos)
    }

    // Стрелка «вперёд».
    if (chipK > 0f) {
        val a = Path().apply {
            moveTo(c.x, c.y - chip / 2 - size * 0.13f)
            lineTo(c.x - size * 0.035f, c.y - chip / 2 - size * 0.07f)
            lineTo(c.x + size * 0.035f, c.y - chip / 2 - size * 0.07f)
            close()
        }
        drawPath(a, white.copy(alpha = alpha * chipK * 0.8f))
    }

    // Импульсы данных бегут по дорожкам от процессора наружу.
    if (t > 1.9f) {
        traces.forEachIndexed { i, path ->
            val pm = PathMeasure().apply { setPath(path, false) }
            val k = ((t - 1.9f) * 1.6f + i * 0.17f) % 1f
            val pos = pm.getPosition(pm.length * k)
            drawCircle(Color.White.copy(alpha = alpha * 0.3f), size * 0.025f, pos)
            drawCircle(Color.White.copy(alpha = alpha), size * 0.01f, pos)
        }
    }
}
