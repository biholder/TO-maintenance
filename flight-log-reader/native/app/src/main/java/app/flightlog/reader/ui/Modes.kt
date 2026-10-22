package app.flightlog.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.flightlog.core.FlightLog

/** Цвета режимов полёта: различимы между собой и читаются на светлой и тёмной теме. */
private val MODE_COLORS = listOf(
    0xFF4C8DF6, 0xFF2FB37E, 0xFFE0A21B, 0xFFE0614A, 0xFF8E6CD9, 0xFF2BB3C9, 0xFFD45FA6, 0xFF8C9A3A,
).map { Color(it) }

/**
 * Режимы полёта на шкале времени: отрезки в долях 0..1 и цвет каждого режима.
 * Цвета выдаются по порядку появления режима в логе.
 */
class ModeBands(val segments: List<Segment>, val names: List<String>) {
    class Segment(val from: Float, val to: Float, val mode: Int)

    fun color(mode: Int): Color = MODE_COLORS[mode % MODE_COLORS.size]

    fun colorOf(name: String): Color = names.indexOf(name).let { if (it < 0) Color.Gray else color(it) }

    companion object {
        fun of(log: FlightLog): ModeBands {
            val names = ArrayList<String>()
            val segs = ArrayList<Segment>()
            val d = log.duration.coerceAtLeast(1e-3f)
            log.modes.forEachIndexed { i, m ->
                var k = names.indexOf(m.name)
                if (k < 0) { names += m.name; k = names.size - 1 }
                val end = log.modes.getOrNull(i + 1)?.time ?: log.duration
                if (end > m.time) segs += Segment((m.time / d).coerceIn(0f, 1f), (end / d).coerceIn(0f, 1f), k)
            }
            return ModeBands(segs, names)
        }
    }
}

/** Полупрозрачные полосы режимов во всю высоту области рисования. */
fun DrawScope.drawModeBands(bands: ModeBands?, alpha: Float = 0.10f) {
    if (bands == null) return
    for (s in bands.segments) {
        drawRect(bands.color(s.mode).copy(alpha = alpha), Offset(s.from * size.width, 0f), Size((s.to - s.from) * size.width, size.height))
    }
}

/** Полоса режимов высотой [h] от [top]. */
fun DrawScope.drawModeStrip(bands: ModeBands, top: Float, h: Float) {
    for (s in bands.segments) {
        drawRect(bands.color(s.mode), Offset(s.from * size.width, top), Size((s.to - s.from) * size.width, h))
    }
}
