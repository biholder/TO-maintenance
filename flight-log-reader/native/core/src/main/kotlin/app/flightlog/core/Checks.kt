package app.flightlog.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Проверки по компасу, EKF, моторам, сопротивлению АКБ и радиоканалу.
 * Вызываются из [Analyzer.analyze]; каждая добавляет найденные проблемы в [issues]
 * и название подсистемы в [healthy], если всё в норме.
 */
internal class Checks(private val log: FlightLog, private val issues: MutableList<Issue>, private val healthy: MutableList<String>) {
    private val s = log.series
    private val armed = log.armTime ?: 0f
    private val disarmed = log.disarmTime ?: log.duration
    private val params = log.params.associate { it.name to it.value }

    private fun inFlight(t: Float) = t in armed..disarmed

    /** В логе есть ошибки ERR этих подсистем. */
    private fun hasErr(vararg subsys: Int) = log.events.any { e ->
        e.source == "ERR" && !e.detail.endsWith("ECode 0") && (e.detail.substringAfter("Subsys ").substringBefore(' ').toIntOrNull() ?: -1) in subsys
    }

    /** Индексы точек ряда за время полёта. */
    private fun flightIdx(ser: Series): List<Int> = (0 until ser.size).filter { inFlight(ser.time[it]) }

    fun runAll() {
        compass()
        ekf()
        motors()
        resistance()
        radio()
        events()
    }

    // ── Компас ──

    fun compass() {
        val mag = s[Ch.MAG] ?: return
        val idx = flightIdx(mag)
        if (idx.size < 20) return
        var ok = true

        // Модуль поля вне допустимого диапазона ArduPilot (COMPASS_… arming check: 185…875 мГс).
        val values = idx.map { mag.values[it] }
        val median = values.sorted()[values.size / 2]
        if (median < FIELD_MIN || median > FIELD_MAX) {
            ok = false
            issues += Issue(Severity.WARN, "Поле компаса вне нормы", armed, disarmed,
                "Модуль магнитного поля около ${median.toInt()} мГс при норме $FIELD_MIN…$FIELD_MAX. Компас не откалиброван, " +
                    "неверно задана ориентация или рядом сильный магнит / железо.", Ch.MAG)
        }

        // Помехи от силовой проводки: поле меняется вместе с током (или газом).
        val load = s[Ch.CURR]?.takeIf { it.max > 1f } ?: s[Ch.THR]
        if (load != null) {
            val x = FloatArray(idx.size) { load.valueAt(mag.time[idx[it]]) ?: 0f }
            val y = FloatArray(idx.size) { mag.values[idx[it]] }
            val fit = linearFit(x, y)
            val span = x.max() - x.min()
            if (fit != null && median > 1f && span > 0f) {
                val change = abs(fit.slope) * span / median
                if (fit.r > INTERFERENCE_R && change > INTERFERENCE_SHARE) {
                    ok = false
                    val what = if (load.key == Ch.CURR) "тока от ${x.min().toInt()} до ${x.max().toInt()} А" else "газа"
                    issues += Issue(Severity.WARN, "Помехи компасу от силовой проводки", armed, disarmed,
                        "Поле компаса меняется на ${(change * 100).toInt()}% при изменении $what " +
                            "(допустимо до ${(INTERFERENCE_SHARE * 100).toInt()}%). Отнесите компас дальше от проводов питания и ESC, " +
                            "скрутите силовые провода, выполните калибровку compassmot.", Ch.MAG)
                }
            }
        }

        // Несколько компасов показывают разное направление поля.
        var i = 1
        while (true) {
            val diff = s[Ch.magDiff(i)] ?: break
            val di = flightIdx(diff)
            if (di.size >= 20) {
                val mean = di.sumOf { diff.values[it].toDouble() }.toFloat() / di.size
                if (mean > COMPASS_ANGLE) {
                    ok = false
                    issues += Issue(Severity.WARN, "Компасы 1 и ${i + 1} не согласованы", armed, disarmed,
                        "Направление поля расходится в среднем на ${mean.toInt()}° (норма — до ${COMPASS_ANGLE.toInt()}°). " +
                            "Проверьте параметр ориентации COMPASS_ORIENT${if (i == 1) "2" else "${i + 1}"} и выполните калибровку компасов.",
                        Ch.magDiff(i))
                }
            }
            i++
        }

        // Повторные сбросы курса EKF в полёте — признак магнитных помех.
        val yawResets = log.events.count { it.source == "EV" && it.detail == "EV 62" && it.time > armed + 15f && inFlight(it.time) }
        if (yawResets >= 2) {
            ok = false
            issues += Issue(Severity.WARN, "Сбросы курса EKF", armed, disarmed,
                "EKF $yawResets ${plural(yawResets, "раз", "раза", "раз")} сбрасывал курс в полёте — компас расходится с гироскопами. " +
                    "Обычно это магнитные помехи или плохая калибровка.", Ch.MAG)
        }
        if (ok && !hasErr(3)) healthy += "Компас"
    }

    // ── EKF ──

    fun ekf() {
        val e = s[Ch.EKF] ?: return
        val bad = BooleanArray(e.size) { inFlight(e.time[it]) && e.values[it] > EKF_WARN }
        val iv = Analyzer.intervals(e.time, bad, gap = 2f, minLen = 0.5f)
        val parts = listOf(Ch.EKF_VEL to "скорости", Ch.EKF_POS to "позиции", Ch.EKF_HGT to "высоте", Ch.EKF_MAG to "компасу")
        for ((a, b) in iv) {
            var peak = 0f
            var what = ""
            var key = Ch.EKF
            for ((k, name) in parts) {
                val ser = s[k] ?: continue
                for (j in ser.indexAt(a)..ser.indexAt(b)) if (ser.values[j] > peak) { peak = ser.values[j]; what = name; key = k }
            }
            if (peak == 0f) for (j in e.indexAt(a)..e.indexAt(b)) peak = maxOf(peak, e.values[j])
            issues += Issue(if (peak > 1f) Severity.CRITICAL else Severity.WARN,
                "Рассогласование EKF" + if (what.isNotEmpty()) " по $what" else "", a, b,
                "Отношение теста инноваций достигало ${fmt2(peak)} (порог failsafe FS_EKF_THRESH — ${fmt1(EKF_WARN)}; " +
                    "больше 1 — измерения отбрасываются). " + when (what) {
                    "компасу" -> "Вероятны магнитные помехи или плохая калибровка компаса."
                    "высоте" -> "Проверьте барометр: обдув от винтов, засветка, перепады давления."
                    "скорости", "позиции" -> "Вероятны скачки GPS или сильные вибрации."
                    else -> "Смотрите графики EKF."
                }, key)
        }
        if (iv.isEmpty() && !hasErr(16, 17, 24)) healthy += "EKF"
    }

    // ── Моторы ──

    fun motors() {
        if (log.vehicle.vehicleType.let { it.isNotEmpty() && it != "Copter" }) return
        val mots = Motors.motors(log).mapNotNull { m -> s[Motors.key(m)]?.let { m to it } }
        if (mots.size < 3) return
        val ref = mots.first().second
        val (lo, hi) = Motors.pwmRange(params, Motors.outputOf(ref))
        val range = hi - lo
        if (range <= 0f) return
        // Моменты, когда все моторы крутятся и борт в воздухе (средний выход выше 25% диапазона).
        val rows = flightIdx(ref).filter { r ->
            val t = ref.time[r]
            mots.sumOf { (_, ser) -> (ser.valueAt(t) ?: 0f).toDouble() } / mots.size > lo + 0.25 * range
        }
        if (rows.size < 30) return
        var ok = true

        val means = mots.map { (m, ser) -> m to rows.sumOf { (ser.valueAt(ref.time[it]) ?: 0f).toDouble() }.toFloat() / rows.size }
        val (mHi, vHi) = means.maxBy { it.second }
        val (mLo, vLo) = means.minBy { it.second }
        val spread = (vHi - vLo) / range
        if (spread > MOTOR_SPREAD) {
            ok = false
            val avg = means.sumOf { it.second.toDouble() }.toFloat() / means.size
            issues += Issue(Severity.WARN, "Разбаланс моторов", armed, disarmed,
                "${Motors.label(mHi)} в среднем на ${(vHi - avg).toInt()} мкс выше среднего, ${Motors.label(mLo).lowercase()} — " +
                    "на ${(avg - vLo).toInt()} мкс ниже (разброс ${(spread * 100).toInt()}% диапазона ШИМ, норма — до " +
                    "${(MOTOR_SPREAD * 100).toInt()}%). Возможны смещение центра масс, перекос мотора или луча, " +
                    "изношенный мотор / ESC или повреждённый винт.", Motors.key(mHi))
        }

        // Выход упирается в максимум — не хватает тяги или управления.
        val top = hi - MOTOR_SAT * range
        for ((m, ser) in mots) {
            val bad = BooleanArray(ser.size) { inFlight(ser.time[it]) && ser.values[it] >= top }
            val iv = Analyzer.intervals(ser.time, bad, gap = 1f, minLen = 0.5f)
            if (iv.isEmpty()) continue
            ok = false
            val total = iv.sumOf { (a, b) -> (b - a).toDouble() }.toFloat()
            issues += Issue(if (total > 5f) Severity.CRITICAL else Severity.WARN, "${Motors.label(m)} на пределе",
                iv.first().first, iv.last().second,
                "Выход мотора упирался в максимум ${hi.toInt()} мкс (${iv.size} ${plural(iv.size, "раз", "раза", "раз")}, " +
                    "всего ${fmt1(total)} с) — регулятору не хватает тяги. Перегруз, слабый мотор или винт, просевшая АКБ, " +
                    "либо мотор компенсирует отказ соседнего.", Motors.key(m))
        }
        if (ok) healthy += "Моторы"
    }

    // ── Внутреннее сопротивление АКБ ──

    fun resistance() {
        val packs = Batteries.packs(log)
        // (подпись, ключ напряжения, ключ тока)
        val sources = if (packs.size > 1) packs.map { p ->
            Triple(Batteries.label(p), Batteries.key(p, Batteries.Metric.VOLT), Batteries.key(p, Batteries.Metric.CURR))
        } else listOf(Triple("", Ch.VOLT, Ch.CURR))
        for ((name, vk, ck) in sources) {
            val v = s[vk] ?: continue
            val c = s[ck] ?: continue
            val est = Batteries.resistance(v, c, armed, disarmed) ?: continue
            if (est.sagShare <= SAG_SHARE) continue
            issues += Issue(Severity.WARN, "Просадка напряжения под нагрузкой" + if (name.isNotEmpty()) " · $name" else "", armed, disarmed,
                "Сопротивление цепи питания около ${(est.ohms * 1000).toInt()} мОм: при пиковом токе ${est.maxCurr.toInt()} А " +
                    "напряжение проседает на ${fmt1(est.sag)} В (${(est.sagShare * 100).toInt()}%, норма — до ${(SAG_SHARE * 100).toInt()}%). " +
                    "АКБ изношена или замёрзла, либо плохой контакт в разъёме или проводах.", vk)
        }
    }

    // ── Радиоканал ──

    fun radio() {
        val rssi = s[Ch.RSSI]?.takeIf { it.max > 0f } ?: return
        val weak = BooleanArray(rssi.size) { inFlight(rssi.time[it]) && rssi.values[it] < RSSI_WARN }
        val iv = Analyzer.intervals(rssi.time, weak, gap = 2f, minLen = 1f)
        for ((a, b) in iv) {
            var minV = Float.MAX_VALUE
            for (i in rssi.indexAt(a)..rssi.indexAt(b)) minV = minOf(minV, rssi.values[i])
            issues += Issue(if (minV <= 0f) Severity.CRITICAL else Severity.WARN, "Слабый сигнал управления", a, b,
                "Уровень сигнала приёмника падал до ${minV.toInt()}%" + (if (minV <= 0f) " — связь терялась" else "") +
                    ". Проверьте антенны приёмника и пульта, их ориентацию и дальность полёта.", Ch.RSSI)
        }
    }

    // ── События ──

    fun events() {
        val stops = log.events.filter { it.source == "EV" && it.detail == "EV 54" && inFlight(it.time) }
        if (stops.isNotEmpty()) issues += Issue(Severity.CRITICAL, "Аварийная остановка моторов", stops.first().time, stops.last().time + 1,
            "В полёте сработала аварийная остановка моторов (переключатель Motor E-Stop или команда GCS).", null)
        val rotor = log.events.filter { it.source == "EV" && it.detail == "EV 59" && inFlight(it.time) }
        if (rotor.isNotEmpty()) issues += Issue(Severity.CRITICAL, "Обороты ротора ниже критических", rotor.first().time, rotor.last().time + 1,
            "Обороты несущего ротора падали ниже H_RSC_CRITICAL. Проверьте настройки регулятора оборотов, АКБ и привод.", Ch.VOLT)
    }

    companion object {
        const val FIELD_MIN = 185
        const val FIELD_MAX = 875
        const val INTERFERENCE_R = 0.5f
        const val INTERFERENCE_SHARE = 0.3f
        const val COMPASS_ANGLE = 30f
        const val EKF_WARN = Analyzer.EKF_WARN
        const val MOTOR_SPREAD = 0.12f
        const val MOTOR_SAT = 0.02f
        const val SAG_SHARE = 0.15f
        const val RSSI_WARN = Analyzer.RSSI_WARN
    }
}

internal class LinearFit(val slope: Float, val r: Float)

/** Линейная регрессия y по x; r — модуль коэффициента корреляции. */
internal fun linearFit(x: FloatArray, y: FloatArray): LinearFit? {
    val n = x.size
    if (n < 3) return null
    val mx = x.average(); val my = y.average()
    var sxy = 0.0; var sxx = 0.0; var syy = 0.0
    for (i in 0 until n) {
        val dx = x[i] - mx; val dy = y[i] - my
        sxy += dx * dy; sxx += dx * dx; syy += dy * dy
    }
    if (sxx < 1e-9 || syy < 1e-9) return null
    return LinearFit((sxy / sxx).toFloat(), (abs(sxy) / sqrt(sxx * syy)).toFloat())
}
