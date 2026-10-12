package app.flightlog.core

import kotlin.math.abs

/**
 * Несколько аккумуляторов на борту (BAT[0], BAT[1]… в ArduPilot, BATTERY_STATUS.id в MAVLink).
 *
 * Каналы каждой АКБ лежат в [FlightLog.series] под ключами `bat<N>:<метрика>`,
 * где N — номер экземпляра с нуля. Они заводятся, только если АКБ больше одной;
 * стандартные [Ch.VOLT], [Ch.CURR]… по-прежнему описывают первую.
 */
object Batteries {
    enum class Metric(val id: String, val title: String, val unit: String, val digits: Int) {
        VOLT("volt", "Напряжение", "В", 2),
        CURR("curr", "Ток", "А", 1),
        MAH("mah", "Израсходовано", "мА·ч", 0),
        REM("rem", "Заряд", "%", 0),
        TEMP("temp", "Температура", "°C", 0),
        RES("res", "Сопротивление", "Ом", 3),
    }

    fun key(pack: Int, m: Metric) = "bat$pack:${m.id}"

    fun label(pack: Int) = "АКБ ${pack + 1}"

    /** Номера АКБ, для которых в логе есть каналы. */
    fun packs(log: FlightLog): List<Int> =
        log.series.keys.mapNotNull { k -> if (k.startsWith("bat") && ':' in k) k.substring(3, k.indexOf(':')).toIntOrNull() else null }
            .distinct().sorted()

    /** Метрики, которые есть хотя бы у одной АКБ. */
    fun metrics(log: FlightLog, packs: List<Int> = packs(log)): List<Metric> =
        Metric.entries.filter { m -> packs.any { key(it, m) in log.series } }

    data class PackStats(
        val pack: Int,
        val minVolt: Float?,
        val maxVolt: Float?,
        val maxCurr: Float?,
        val avgCurr: Float?,
        val usedMah: Float?,
        val maxTemp: Float?,
        val endRem: Float?,
    )

    /** Сводка по каждой АКБ за время полёта (между взведением и разоружением). */
    fun stats(log: FlightLog): List<PackStats> {
        val armed = log.armTime ?: 0f
        val disarmed = log.disarmTime ?: log.duration
        fun inFlight(s: Series): List<Int> = (0 until s.size).filter { s.time[it] in armed..disarmed }.ifEmpty { (0 until s.size).toList() }
        return packs(log).map { p ->
            fun s(m: Metric) = log.series[key(p, m)]
            val volt = s(Metric.VOLT)?.let { v -> inFlight(v).map { v.values[it] } }
            val curr = s(Metric.CURR)?.let { c -> inFlight(c).map { c.values[it] } }
            PackStats(
                pack = p,
                minVolt = volt?.minOrNull(),
                maxVolt = volt?.maxOrNull(),
                maxCurr = curr?.maxOrNull(),
                avgCurr = curr?.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                usedMah = s(Metric.MAH)?.let { it.max - (it.values.firstOrNull() ?: 0f) },
                maxTemp = s(Metric.TEMP)?.let { t -> inFlight(t).maxOfOrNull { t.values[it] } },
                endRem = s(Metric.REM)?.values?.lastOrNull(),
            )
        }
    }

    /** Порог разбаланса: доля от среднего значения. */
    const val IMBALANCE_SHARE = 0.15f
    /** Минимальный ток, при котором имеет смысл сравнивать токи АКБ, А. */
    const val IMBALANCE_MIN_CURR = 3f

    /**
     * Разбаланс АКБ: одна заметно больше другой отдаёт ток или израсходовала ёмкость.
     * Возвращает текст пояснения или null.
     */
    fun imbalance(stats: List<PackStats>): String? {
        if (stats.size < 2) return null
        val parts = ArrayList<String>()
        fun spread(values: List<Float>): Float? {
            if (values.size < 2) return null
            val mean = values.average().toFloat()
            if (abs(mean) < 1e-3f) return null
            return (values.max() - values.min()) / abs(mean)
        }
        val mah = stats.mapNotNull { it.usedMah }
        spread(mah)?.takeIf { it > IMBALANCE_SHARE && mah.max() > 200f }?.let {
            parts += "израсходовано ${mah.joinToString(" / ") { v -> v.toInt().toString() }} мА·ч (разница ${(it * 100).toInt()}%)"
        }
        val curr = stats.mapNotNull { it.avgCurr }
        spread(curr)?.takeIf { it > IMBALANCE_SHARE && curr.max() > IMBALANCE_MIN_CURR }?.let {
            parts += "средний ток ${curr.joinToString(" / ") { v -> fmt1(v) }} А"
        }
        val minV = stats.mapNotNull { it.minVolt }
        if (minV.size >= 2 && minV.max() - minV.min() > maxOf(0.5f, minV.max() * 0.03f)) {
            parts += "минимальное напряжение ${minV.joinToString(" / ") { v -> fmt2(v) }} В"
        }
        if (parts.isEmpty()) return null
        return "АКБ разряжаются неравномерно: " + parts.joinToString("; ") +
            ". Проверьте разъёмы и провода питания, состояние и баланс элементов, одинаковость АКБ."
    }
}
