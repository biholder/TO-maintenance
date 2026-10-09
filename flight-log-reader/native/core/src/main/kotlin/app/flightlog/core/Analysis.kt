package app.flightlog.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Severity { OK, WARN, CRITICAL }

/** Найденная проблема полёта. */
data class Issue(
    val severity: Severity,
    val title: String,
    val start: Float,
    val end: Float,
    val explanation: String,
    /** Канал, который стоит открыть на графиках. */
    val channel: String?,
)

data class Summary(
    val flightTime: Float,
    val distanceM: Double,
    val maxAltM: Float?,
    val maxSpeedMs: Float?,
    val usedMah: Float?,
    val minVoltage: Float?,
    val avgCurrent: Float?,
    val maxVibe: Float?,
    val clipCount: Int?,
)

class Analysis(val summary: Summary, val issues: List<Issue>, val healthy: List<String>) {
    val status: Severity
        get() = when {
            issues.any { it.severity == Severity.CRITICAL } -> Severity.CRITICAL
            issues.isNotEmpty() -> Severity.WARN
            else -> Severity.OK
        }

    /** Короткий текст статуса для списка полётов. */
    val statusText: String
        get() = issues.firstOrNull { it.severity == Severity.CRITICAL }?.title
            ?: if (issues.isEmpty()) "Норма" else "${issues.size} ${plural(issues.size, "предупр.", "предупр.", "предупр.")}"
}

private val DJI_SOURCES = setOf("OSD", "OSD-CRIT", "OSD-ACTION", "SERIOUS")

object Analyzer {
    const val VIBE_WARN = 30f
    const val VIBE_CRIT = 60f
    const val SATS_MIN = 10f
    const val HDOP_MAX = 1.5f

    fun analyze(log: FlightLog): Analysis {
        val armed = log.armTime ?: 0f
        val disarmed = log.disarmTime ?: log.duration
        fun inFlight(t: Float) = t in armed..disarmed

        val issues = ArrayList<Issue>()
        val healthy = ArrayList<String>()
        val s = log.series

        // Вибрации.
        val vibeKeys = listOf(Ch.VIBE_X, Ch.VIBE_Y, Ch.VIBE_Z).filter { it in s }
        if (vibeKeys.isNotEmpty()) {
            val z = s.getValue(vibeKeys.last())
            val intervals = intervals(z.time, BooleanArray(z.size) { i ->
                inFlight(z.time[i]) && vibeKeys.any { k -> (s.getValue(k).valueAt(z.time[i]) ?: 0f) > VIBE_WARN }
            }, gap = 2f, minLen = 1f)
            for ((a, b) in intervals) {
                var peak = 0f
                var peakKey = vibeKeys.last()
                for (k in vibeKeys) {
                    val ser = s.getValue(k)
                    for (i in ser.indexAt(a)..ser.indexAt(b)) if (ser.values[i] > peak) { peak = ser.values[i]; peakKey = k }
                }
                val axis = peakKey.removePrefix("vibe")
                issues += Issue(
                    if (peak > VIBE_CRIT) Severity.CRITICAL else Severity.WARN,
                    "Повышенные вибрации по оси $axis",
                    a, b,
                    "Пик ${fmt1(peak)} м/с² при допустимых ${VIBE_WARN.toInt()}. Проверьте балансировку и затяжку " +
                        "винтов, крепление полётного контроллера и демпферы.",
                    peakKey,
                )
            }
            if (intervals.isEmpty()) healthy += "Вибрации"
        }
        s[Ch.CLIP]?.let { c ->
            val first = c.values.firstOrNull() ?: 0f
            val last = c.values.lastOrNull() ?: 0f
            val delta = (last - first).toInt()
            if (delta > 0) {
                val i0 = c.values.indexOfFirst { it > first }
                issues += Issue(Severity.WARN, "Клиппинг акселерометра", c.time[i0], c.time.last(),
                    "Счётчик клиппинга IMU0 вырос на $delta — датчик упирался в предел измерения. " +
                        "Обычно это следствие сильных вибраций.", Ch.CLIP)
            }
        }

        // GPS.
        val gpsErr = log.events.filter { it.source == "ERR" && it.detail.startsWith("Subsys 11 ") && !it.detail.endsWith("ECode 0") }
        val gpsFs = log.events.filter { it.source == "ERR" && it.detail.startsWith("Subsys 7 ") && !it.detail.endsWith("ECode 0") }
        if (gpsFs.isNotEmpty()) {
            issues += Issue(Severity.CRITICAL, "Потеря GPS", gpsFs.first().time, gpsFs.last().time + 1,
                "Сработал failsafe по GPS — борт потерял навигационное решение.", Ch.SATS)
        }
        val sats = s[Ch.SATS]
        val hdop = s[Ch.HDOP]
        if (sats != null) {
            val bad = BooleanArray(sats.size) { i ->
                inFlight(sats.time[i]) && (sats.values[i] < SATS_MIN || (hdop?.valueAt(sats.time[i]) ?: 0f) > HDOP_MAX)
            }
            val iv = intervals(sats.time, bad, gap = 3f, minLen = 2f)
            for ((a, b) in iv) {
                var minS = Float.MAX_VALUE
                for (i in sats.indexAt(a)..sats.indexAt(b)) minS = minOf(minS, sats.values[i])
                issues += Issue(Severity.WARN, "Деградация GPS", a, b,
                    "Число спутников падало до ${minS.toInt()}" +
                        (if (gpsErr.any { it.time in a - 2..b + 2 }) ", зафиксирован GPS glitch" else "") +
                        ". Проверьте помехи от бортовой электроники и экранирование приёмника.", Ch.SATS)
            }
            if (iv.isEmpty() && gpsFs.isEmpty()) healthy += "GPS"
        }

        // Батарея.
        val volt = s[Ch.VOLT]
        val lowV = log.params.firstOrNull { it.name == "BATT_LOW_VOLT" }?.value?.takeIf { it > 0 }
        val crtV = log.params.firstOrNull { it.name == "BATT_CRT_VOLT" }?.value?.takeIf { it > 0 }
        val battFs = log.events.filter { it.source == "ERR" && it.detail.startsWith("Subsys 6 ") && !it.detail.endsWith("ECode 0") }
        if (battFs.isNotEmpty()) {
            issues += Issue(Severity.CRITICAL, "Failsafe батареи", battFs.first().time, battFs.last().time + 1,
                "Сработал failsafe по батарее. Проверьте ёмкость АКБ и пороги BATT_LOW_VOLT / BATT_CRT_VOLT.", Ch.VOLT)
        }
        if (volt != null && lowV != null) {
            val bad = BooleanArray(volt.size) { inFlight(volt.time[it]) && volt.values[it] < lowV }
            val iv = intervals(volt.time, bad, gap = 2f, minLen = 1f)
            for ((a, b) in iv) {
                var minV = Float.MAX_VALUE
                for (i in volt.indexAt(a)..volt.indexAt(b)) minV = minOf(minV, volt.values[i])
                issues += Issue(if (crtV != null && minV < crtV) Severity.CRITICAL else Severity.WARN,
                    "Низкое напряжение", a, b,
                    "Напряжение опускалось до ${fmt2(minV)} В при пороге BATT_LOW_VOLT ${fmt2(lowV)} В. " +
                        "Возможна просадка под нагрузкой или изношенная АКБ.", Ch.VOLT)
            }
            if (iv.isEmpty() && battFs.isEmpty()) healthy += "Батарея"
        } else if (volt != null && battFs.isEmpty()) healthy += "Батарея"

        // Прочие ошибки ERR.
        val handled = setOf(6, 7, 11)
        val errs = log.events.filter { it.source == "ERR" && !it.detail.endsWith("ECode 0") }
            .filter { e -> e.detail.substringAfter("Subsys ").substringBefore(' ').toIntOrNull() !in handled }
        val bySub = errs.groupBy { it.detail.substringAfter("Subsys ").substringBefore(' ').toIntOrNull() ?: -1 }
        for ((sub, list) in bySub) {
            val critical = sub in setOf(5, 12, 16, 17, 25, 26, 29)
            issues += Issue(if (critical) Severity.CRITICAL else Severity.WARN,
                ArduPilot.ERR_SUBSYS[sub] ?: "Ошибка подсистемы $sub",
                list.first().time, list.last().time + 1,
                "Зафиксировано ошибок: ${list.size}. Смотрите журнал событий.", null)
        }
        if (log.format == LogFormat.DATAFLASH || log.format == LogFormat.DATAFLASH_TEXT) {
            if (bySub.keys.none { it in setOf(16, 17, 24) }) healthy += "EKF3"
            if (3 !in bySub.keys) healthy += "Компас"
            if (5 !in bySub.keys && 2 !in bySub.keys) healthy += "RC"
        }

        // DJI: флаги контроллера, аварийные действия и серьёзные предупреждения приложения.
        if (log.format == LogFormat.DJI) {
            val dji = log.events.filter { it.kind == EventKind.WARN && it.source in DJI_SOURCES }
            for ((title, list) in dji.groupBy { it.title }) {
                val first = list.first()
                issues += Issue(
                    if (first.source == "OSD-CRIT") Severity.CRITICAL else Severity.WARN, title,
                    first.time, list.last().time + 1,
                    when (first.source) {
                        "OSD-ACTION" -> "Контроллер DJI выполнил автоматическое действие. Проверьте журнал событий."
                        "SERIOUS" -> "Серьёзное предупреждение приложения DJI" + if (list.size > 1) " (${list.size} раз)." else "."
                        else -> first.detail
                    },
                    null,
                )
            }
            val titles = dji.map { it.title }.toSet()
            if ("Ошибка компаса" !in titles) healthy += "Компас"
            if (titles.none { it.startsWith("Блокировка") || it.startsWith("Недостаточно") }) healthy += "Моторы"
        }

        issues.sortWith(compareByDescending<Issue> { it.severity }.thenBy { it.start })
        return Analysis(summary(log), issues, healthy)
    }

    fun summary(log: FlightLog): Summary {
        val armed = log.armTime ?: 0f
        val disarmed = log.disarmTime ?: log.duration
        val s = log.series
        fun inFlight(ser: Series, i: Int) = ser.time[i] in armed..disarmed
        fun maxIn(key: String): Float? = s[key]?.let { ser -> (0 until ser.size).filter { inFlight(ser, it) }.maxOfOrNull { ser.values[it] } }
        fun minIn(key: String): Float? = s[key]?.let { ser -> (0 until ser.size).filter { inFlight(ser, it) }.minOfOrNull { ser.values[it] } }
        val mah = s[Ch.MAH]?.let { it.max - (it.values.firstOrNull() ?: 0f) }
        val curr = s[Ch.CURR]?.let { ser ->
            val idx = (0 until ser.size).filter { inFlight(ser, it) }
            if (idx.isEmpty()) null else idx.map { ser.values[it] }.average().toFloat()
        }
        val clip = s[Ch.CLIP]?.let { (it.max - (it.values.firstOrNull() ?: 0f)).toInt() }
        val vibe = listOf(Ch.VIBE_X, Ch.VIBE_Y, Ch.VIBE_Z).mapNotNull { maxIn(it) }.maxOrNull()
        return Summary(
            flightTime = disarmed - armed,
            distanceM = distance(log.track),
            maxAltM = maxIn(Ch.ALT),
            maxSpeedMs = maxIn(Ch.SPD),
            usedMah = mah,
            minVoltage = minIn(Ch.VOLT),
            avgCurrent = curr,
            maxVibe = vibe,
            clipCount = clip,
        )
    }

    fun distance(tr: Track): Double {
        var d = 0.0
        for (i in 1 until tr.size) {
            val step = haversine(tr.lat[i - 1], tr.lon[i - 1], tr.lat[i], tr.lon[i])
            if (step < 200) d += step // отбрасываем скачки координат
        }
        return d
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Склеивает отметки «плохо» в интервалы. */
    internal fun intervals(time: FloatArray, bad: BooleanArray, gap: Float, minLen: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var start = -1f
        var end = -1f
        for (i in time.indices) {
            if (!bad[i]) continue
            if (start < 0) { start = time[i]; end = time[i] }
            else if (time[i] - end <= gap) end = time[i]
            else { if (end - start >= minLen) out += start to end; start = time[i]; end = time[i] }
        }
        if (start >= 0 && end - start >= minLen) out += start to end
        return out
    }
}

internal fun fmt1(v: Float) = String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',')
internal fun fmt2(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',')

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
