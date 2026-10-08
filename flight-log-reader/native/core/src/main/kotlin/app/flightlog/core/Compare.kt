package app.flightlog.core

/** Сравнение двух полётов. */
object Compare {
    /** [higherIsWorse] — для подсветки дельты: true, если рост значения — ухудшение. */
    data class Metric(val label: String, val a: Float?, val b: Float?, val unit: String, val digits: Int, val higherIsWorse: Boolean?) {
        val delta: Float? get() = if (a != null && b != null) a - b else null
        val aIsWorse: Boolean
            get() {
                val d = delta ?: return false
                return when (higherIsWorse) { true -> d > 0; false -> d < 0; null -> false }
            }
    }

    data class ParamDiff(val name: String, val a: Float?, val b: Float?)

    fun metrics(a: Summary, b: Summary): List<Metric> = listOf(
        Metric("Длительность", a.flightTime / 60, b.flightTime / 60, "мин", 1, null),
        Metric("Израсходовано", a.usedMah, b.usedMah, "мА·ч", 0, true),
        Metric("Мин. напряжение", a.minVoltage, b.minVoltage, "В", 2, false),
        Metric("Средний ток", a.avgCurrent, b.avgCurrent, "А", 1, true),
        Metric("Пик вибраций", a.maxVibe, b.maxVibe, "м/с²", 1, true),
        Metric("Клиппинг IMU0", a.clipCount?.toFloat(), b.clipCount?.toFloat(), "", 0, true),
    )

    fun paramDiffs(a: List<Param>, b: List<Param>): List<ParamDiff> {
        val ma = a.associateBy { it.name }
        val mb = b.associateBy { it.name }
        return (ma.keys + mb.keys).sorted().mapNotNull { k ->
            val va = ma[k]?.value
            val vb = mb[k]?.value
            if (va != null && vb != null && nearlyEqual(va, vb)) null else ParamDiff(k, va, vb)
        }
    }
}
