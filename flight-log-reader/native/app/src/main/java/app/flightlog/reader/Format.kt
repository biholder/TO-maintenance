package app.flightlog.reader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val RU = Locale("ru", "RU")

/** Числа в ru-RU: десятичная запятая, узкий пробел в тысячах. */
fun num(v: Float?, digits: Int = 1): String {
    if (v == null || v.isNaN()) return "—"
    val s = String.format(RU, "%,.${digits}f", v)
    return s.replace(' ', ' ')
}

fun num(v: Double?, digits: Int = 1): String = num(v?.toFloat(), digits)

/** mm:ss или h:mm:ss. */
fun clock(sec: Float): String {
    val s = sec.coerceAtLeast(0f).roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

/** «12 мин 40 с» / «1 ч 05 мин». */
fun durationText(sec: Float): String {
    val s = sec.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h ч ${"%02d".format(m)} мин"
        m > 0 -> "$m мин ${s % 60} с"
        else -> "$s с"
    }
}

fun dateText(utcMillis: Long?): String {
    if (utcMillis == null) return "—"
    return SimpleDateFormat("d MMM, HH:mm", RU).format(Date(utcMillis)).replace(".", "")
}

fun dateTimeFull(utcMillis: Long?): String {
    if (utcMillis == null) return "—"
    return SimpleDateFormat("d MMMM yyyy, HH:mm", RU).format(Date(utcMillis))
}

/** Система единиц. */
enum class Units { METRIC, IMPERIAL }

class UnitFmt(val units: Units) {
    val imperial get() = units == Units.IMPERIAL
    fun alt(m: Float?) = if (imperial) num(m?.times(3.28084f), 0) else num(m, 1)
    val altUnit get() = if (imperial) "ft" else "м"
    fun speed(ms: Float?) = if (imperial) num(ms?.times(2.23694f), 1) else num(ms, 1)
    val speedUnit get() = if (imperial) "mph" else "м/с"
    fun dist(m: Double?): Pair<String, String> {
        if (m == null) return "—" to ""
        return if (imperial) {
            val mi = m / 1609.344
            if (mi >= 0.1) num(mi, 2) to "mi" else num(m * 3.28084, 0) to "ft"
        } else {
            if (m >= 1000) num(m / 1000, 2) to "км" else num(m, 0) to "м"
        }
    }
    /** Конвертация значения канала по его единице. */
    fun channel(v: Float, unit: String): Float = when {
        !imperial -> v
        unit == "м" -> v * 3.28084f
        unit == "м/с" -> v * 2.23694f
        else -> v
    }
    fun channelUnit(unit: String): String = when {
        !imperial -> unit
        unit == "м" -> "ft"
        unit == "м/с" -> "mph"
        else -> unit
    }
}

fun signed(v: Float?, digits: Int): String {
    if (v == null) return "—"
    val s = num(abs(v), digits)
    return when {
        v > 0 && s.any { it in '1'..'9' } -> "+$s"
        v < 0 && s.any { it in '1'..'9' } -> "−$s"
        else -> s
    }
}
