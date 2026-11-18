package app.flightlog.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Точность навигации по логу автопилота.
 *
 * Три уровня, как в методике испытаний антенной решётки:
 * 1) самооценка приёмника — спутники, тип решения, eph/epv, HDOP/VDOP, признаки помех;
 * 2) согласованность с другими датчиками — невязки EKF по позиции и скорости ГНСС,
 *    расхождение высоты ГНСС и барометра (быстрая составляющая);
 * 3) реальная ошибка — только с эталоном (PPK/RTK), по логу автопилота недоступна.
 *
 * Земля — до взлёта, полёт — от взлёта до посадки (по высоте выше 5 м от стартовой).
 */
object NavAccuracy {
    /** Медиана, 95-й перцентиль, минимум и максимум. */
    data class Stat(val median: Float, val p95: Float, val min: Float, val max: Float, val n: Int)

    data class Phase(
        val sats: Stat?,
        val eph: Stat?,
        val epv: Stat?,
        val hdop: Stat?,
        val vdop: Stat?,
        /** Доля времени с решением с поправками (SBAS/DGPS/RTK, тип ≥ 4). */
        val aided: Float?,
        /** Доля времени без 3D-решения. */
        val noFix: Float?,
    )

    /** Эпизод ухудшения навигации в полёте. */
    data class Episode(
        val start: Float, val end: Float,
        val maxEph: Float?, val maxEpv: Float?, val maxInnov: Float?,
        val minSats: Int?, val hdopMin: Float?, val hdopMax: Float?,
        /** Пропадали поправки (тип решения падал ниже 4). */
        val aidingLost: Boolean,
        /** HDOP остаётся низким при росте погрешности: портятся сигналы, а не геометрия. */
        val signalNotGeometry: Boolean,
        /** Предупреждения автопилота в это время. */
        val messages: List<String>,
    )

    /** Эталонные значения (медиана / 95%, м). */
    class Reference(val title: String, val sats: String, val hMed: String, val h95: String, val vMed: String, val v95: String)

    val REFERENCES = listOf(
        Reference("Открытое небо, без помех, GPS+ГЛОНАСС+Galileo+BeiDou", "20–30+", "1–2,5", "3–5", "2–4", "5–8"),
        Reference("Только GPS+ГЛОНАСС", "14–20", "2–3", "4–6", "3–5", "7–10"),
        Reference("GPS SPS, гарантия стандарта (только GPS)", "—", "—", "8–9", "—", "—"),
    )

    /** Норма открытого неба для оценки: медиана и 95% горизонтальной и вертикальной оценки. */
    const val NORM_H_MED = 2.5f
    const val NORM_H_95 = 5f
    const val NORM_V_MED = 4f
    const val NORM_V_95 = 8f
    /** Окно, медленнее которого расхождение ГНСС и барометра не считается шумом (погода, дрейф), с. */
    const val GNSS_BARO_WINDOW = 60f

    enum class Rating(val title: String) { NORMAL("Норма"), FAIR("Удовлетворительно"), POOR("Плохо"), UNKNOWN("Нет оценки") }

    class Report(
        val ground: Phase?,
        val flight: Phase?,
        val takeoff: Float?,
        val landing: Float?,
        /** Невязка EKF по горизонтальной позиции ГНСС в полёте, м. */
        val innov: Stat?,
        /** Высота ГНСС минус баро, колебания короче [GNSS_BARO_WINDOW]: СКО и 95% модуля, м. */
        val gnssBaroStd: Float?,
        val gnssBaro95: Float?,
        /** СКО невязки скорости ГНСС N/E/D, м/с. */
        val velInnovStd: List<Float>?,
        /** Доля времени, когда фильтр принимал ГНСС; максимум test ratio. */
        val fusedShare: Float?,
        val maxTestRatio: Float?,
        /** Признаки помех: есть ли поля и все ли нулевые. */
        val interferenceFields: List<String>,
        val interferenceAllZero: Boolean,
        val episodes: List<Episode>,
        /** Медиана eph в полёте по числу спутников. */
        val ephBySats: List<Pair<Int, Float>>,
        /** Спутники по высоте над стартом на взлёте и посадке. */
        val climb: List<Pair<Float, Float>>,
        val descent: List<Pair<Float, Float>>,
        val rating: Rating,
        val ratingText: String,
        val observations: List<String>,
        val paramNotes: List<String>,
    )

    fun stat(values: FloatArray): Stat? {
        val v = values.filter { !it.isNaN() && !it.isInfinite() }.sorted()
        if (v.isEmpty()) return null
        fun q(p: Float) = v[((v.size - 1) * p).toInt().coerceIn(0, v.size - 1)]
        return Stat(q(0.5f), q(0.95f), v.first(), v.last(), v.size)
    }

    private fun values(s: Series?, from: Float, to: Float): FloatArray {
        if (s == null || s.size == 0) return FloatArray(0)
        val a = s.indexAt(from).let { if (s.time[it] < from) it + 1 else it }
        val b = s.indexAt(to)
        if (b < a || s.time[b] > to) return FloatArray(0)
        return s.values.copyOfRange(a, b + 1)
    }

    private fun phase(log: FlightLog, from: Float, to: Float): Phase? {
        val s = log.series
        val fix = values(s[Ch.FIX], from, to)
        val p = Phase(
            stat(values(s[Ch.SATS], from, to)), stat(values(s[Ch.EPH], from, to)), stat(values(s[Ch.EPV], from, to)),
            stat(values(s[Ch.HDOP], from, to)), stat(values(s[Ch.VDOP], from, to)),
            fix.takeIf { it.isNotEmpty() }?.let { f -> f.count { it >= 4f }.toFloat() / f.size },
            fix.takeIf { it.isNotEmpty() }?.let { f -> f.count { it < 3f }.toFloat() / f.size },
        )
        return if (p.sats == null && p.eph == null && p.hdop == null) null else p
    }

    /** Ряд минус скользящее среднее за [window] с. */
    private fun highPass(t: FloatArray, x: FloatArray, window: Float): FloatArray {
        val out = FloatArray(x.size)
        var a = 0; var b = 0; var sum = 0.0
        for (k in x.indices) {
            while (b < x.size && t[b] <= t[k] + window / 2) { sum += x[b]; b++ }
            while (t[a] < t[k] - window / 2) { sum -= x[a]; a++ }
            out[k] = x[k] - (sum / (b - a)).toFloat()
        }
        return out
    }

    private fun std(v: FloatArray): Float? {
        if (v.size < 3) return null
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat()
    }

    fun analyze(log: FlightLog): Report? {
        val s = log.series
        if (s[Ch.SATS] == null && s[Ch.EPH] == null && s[Ch.HDOP] == null) return null

        // Взлёт и посадка: высота выше стартовой на 5 м.
        val alt = s[Ch.ALT]
        var takeoff: Float? = null
        var landing: Float? = null
        var cruise: Float? = null
        if (alt != null && alt.size > 10) {
            val base = values(alt, alt.time.first(), alt.time.first() + 5f).sorted().let { it[it.size / 2] }
            val up = (0 until alt.size).firstOrNull { alt.values[it] > base + 5f }
            val down = (alt.size - 1 downTo 0).firstOrNull { alt.values[it] > base + 5f }
            if (up != null && down != null && alt.time[down] - alt.time[up] > 30f) {
                takeoff = alt.time[up]; landing = alt.time[down]
                cruise = stat(values(alt, takeoff, landing))?.median?.minus(base)
            }
        }
        if (takeoff == null) { takeoff = log.armTime; landing = log.disarmTime ?: log.duration }
        val t0 = takeoff ?: 0f
        val t1 = landing ?: log.duration
        val ground = if (takeoff != null && takeoff > 5f) phase(log, 0f, takeoff) else null
        val flight = phase(log, t0, t1)

        // Уровень 2: согласованность с фильтром и барометром.
        val innovValues = values(s[Ch.GNSS_INNOV], t0, t1)
        val innov = stat(innovValues)
        var gnssBaroStd: Float? = null
        var gnssBaro95: Float? = null
        val ga = s[Ch.GPS_ALT]; val ba = s[Ch.BARO_ALT]
        if (ga != null && ba != null) {
            val idx = (0 until ga.size).filter { ga.time[it] in t0..t1 && ba.valueAt(ga.time[it]) != null }
            if (idx.size > 50) {
                val tt = FloatArray(idx.size) { ga.time[idx[it]] }
                val d = FloatArray(idx.size) { ga.values[idx[it]] - ba.valueAt(tt[it])!! }
                val hp = highPass(tt, d, GNSS_BARO_WINDOW)
                gnssBaroStd = std(hp)
                gnssBaro95 = stat(FloatArray(hp.size) { abs(hp[it]) })?.p95
            }
        }
        val velStd = listOf(Ch.GNSS_VINN_N, Ch.GNSS_VINN_E, Ch.GNSS_VINN_D).map { std(values(s[it], t0, t1)) }
            .takeIf { l -> l.all { it != null } }?.map { it!! }
        val fused = values(s[Ch.GNSS_FUSED], t0, t1).takeIf { it.isNotEmpty() }?.let { f -> f.count { it > 0.5f }.toFloat() / f.size }
        val maxTr = values(s[Ch.GNSS_TR], t0, t1).filter { !it.isNaN() }.maxOrNull()

        // Признаки помех.
        val interference = listOf(Ch.GPS_JAM, Ch.GPS_NOISE, Ch.GPS_AGC, Ch.GPS_JAM_STATE, Ch.GPS_SPOOF).filter { it in s }
        val interferenceZero = interference.isNotEmpty() && interference.all { k -> s.getValue(k).let { it.min == 0f && it.max == 0f } }

        // Эпизоды ухудшения в полёте.
        val ephS = s[Ch.EPH]
        val ephMed = flight?.eph?.median
        val episodes = ArrayList<Episode>()
        val ref = ephS ?: s[Ch.GNSS_INNOV]
        if (ref != null) {
            val innovS = s[Ch.GNSS_INNOV]
            val bad = BooleanArray(ref.size) { i ->
                val t = ref.time[i]
                if (t !in t0..t1) return@BooleanArray false
                val e = ephS?.valueAt(t)
                val inn = innovS?.valueAt(t)
                (e != null && ephMed != null && e > maxOf(10f, 2.5f * ephMed)) || (inn != null && inn > 10f)
            }
            // Близкие всплески (до минуты между ними) — один эпизод.
            for ((a, b) in Analyzer.intervals(ref.time, bad, gap = 60f, minLen = 1f)) {
                val w0 = a - 5f; val w1 = b + 5f
                val hd = values(s[Ch.HDOP], w0, w1)
                val fixV = values(s[Ch.FIX], w0, w1)
                val maxEph = values(ephS, a, b).maxOrNull()
                val hdMax = hd.maxOrNull()
                val hdMed = stat(values(s[Ch.HDOP], a, b))?.median
                episodes += Episode(
                    a, b, maxEph, values(s[Ch.EPV], a, b).maxOrNull(), values(innovS, a, b).maxOrNull(),
                    values(s[Ch.SATS], w0, w1).minOrNull()?.toInt(), hd.minOrNull(), hdMax,
                    aidingLost = (flight?.aided ?: 0f) > 0.3f && fixV.any { it < 4f },
                    signalNotGeometry = hdMed != null && hdMed < 2f && maxEph != null && ephMed != null && maxEph > 2.5f * ephMed,
                    messages = log.events.filter { it.kind == EventKind.WARN && it.time in (a - 30f)..(b + 30f) }
                        .map { it.title.replace(Regex("^\\[[^]]+]\\s*"), "") }.distinct().take(3),
                )
            }
        }

        // Точность по числу спутников (полёт).
        val satsS = s[Ch.SATS]
        val bySats = HashMap<Int, MutableList<Float>>()
        if (ephS != null && satsS != null) {
            for (i in 0 until ephS.size) {
                val t = ephS.time[i]
                if (t !in t0..t1) continue
                val n = satsS.valueAt(t)?.toInt() ?: continue
                bySats.getOrPut(n) { ArrayList() } += ephS.values[i]
            }
        }
        val ephBySats = bySats.filter { it.value.size >= 50 }.map { (n, l) -> n to l.sorted()[l.size / 2] }.sortedBy { it.first }

        // Спутники по высоте на вертикальных участках.
        val climb = ArrayList<Pair<Float, Float>>()
        val descent = ArrayList<Pair<Float, Float>>()
        if (alt != null && satsS != null && takeoff != null && landing != null && cruise != null) {
            val base = alt.valueAt(maxOf(0f, takeoff - 10f)) ?: 0f
            val top = base + cruise * 0.9f
            val climbEnd = (alt.indexAt(takeoff) until alt.size).firstOrNull { alt.values[it] >= top }?.let { alt.time[it] } ?: (takeoff + 60f)
            val descStart = (alt.indexAt(landing) downTo 0).firstOrNull { alt.values[it] >= top }?.let { alt.time[it] } ?: (landing - 60f)
            for (i in 0 until satsS.size) {
                val t = satsS.time[i]
                val h = (alt.valueAt(t) ?: continue) - base
                if (t in (takeoff - 15f)..minOf(climbEnd, takeoff + 180f)) climb += h to satsS.values[i]
                if (t in maxOf(descStart, landing - 180f)..(landing + 15f)) descent += h to satsS.values[i]
            }
        }

        // Оценка относительно нормы открытого неба.
        val fe = flight?.eph
        val fv = flight?.epv
        val (rating, ratingText) = if (fe == null) {
            Rating.UNKNOWN to "Приёмник не пишет оценку точности (eph/HAcc) — по логу видны только спутники и HDOP."
        } else {
            val k = maxOf(fe.median / NORM_H_MED, fe.p95 / NORM_H_95)
            val r = when { k <= 1f -> Rating.NORMAL; k <= 2f -> Rating.FAIR; else -> Rating.POOR }
            val times = if (k > 1.15f) " — примерно в ${fmt1(k)} раза хуже нормы открытого неба (1–2,5 / 3–5 м)" else " — в пределах нормы открытого неба (1–2,5 / 3–5 м)"
            r to "Горизонтальная оценка в полёте ${fmt1(fe.median)} / ${fmt1(fe.p95)} м (медиана / 95%)$times." +
                (fv?.let { " Вертикальная ${fmt1(it.median)} / ${fmt1(it.p95)} м (норма 2–4 / 5–8 м)." } ?: "")
        }

        val obs = ArrayList<String>()
        val gs = ground?.sats; val fs = flight?.sats
        if (gs != null && fs != null && gs.median - fs.median >= 4f) {
            val near = descent.filter { it.first < 10f }.map { it.second }.sorted()
            obs += "Спутники теряются в воздухе: на земле медиана ${gs.median.toInt()}, в полёте ${fs.median.toInt()}." +
                (if (near.size > 5) " На посадке у земли число возвращается к ${near[near.size / 2].toInt()}." else "") +
                " Возможны внешняя помеха в прямой видимости с высоты (у земли её закрывает рельеф) или подавление решёткой спутников с направления помехи."
        }
        satsS?.let { ss ->
            val fv2 = values(ss, t0, t1).map { it.toInt() }
            if (fv2.size > 100) {
                val (mode, cnt) = fv2.groupingBy { it }.eachCount().maxBy { it.value }
                val share = cnt.toFloat() / fv2.size
                if (share >= 0.5f && (gs == null || mode < gs.median - 2)) {
                    obs += "Почти весь полёт ровно $mode спутников (${(share * 100).toInt()}% времени) — похоже на ограничение режима приёмника " +
                        "или антенной решётки (например, по числу каналов в режиме защиты), а не на естественный разброс."
                }
            }
        }
        if (ephBySats.size >= 3) {
            val lo = ephBySats.first(); val hi = ephBySats.last()
            if (lo.second >= 1.5f * hi.second) {
                obs += "Точность определяется числом спутников: при ${lo.first} медиана eph ≈ ${fmt1(lo.second)} м, при ${hi.first} — ≈ ${fmt1(hi.second)} м." +
                    (ground?.eph?.let { " Вернув потерянные в воздухе спутники, можно ожидать точность ближе к наземной (${fmt1(it.median)} м)." } ?: "")
            }
        }
        for (e in episodes.sortedByDescending { it.maxEph ?: it.maxInnov ?: 0f }.take(3)) {
            obs += buildString {
                append("Эпизод ${clockText(e.start)}–${clockText(e.end)}:")
                e.maxEph?.let { append(" eph до ${fmt1(it)} м") }
                e.maxEpv?.let { append(", epv до ${fmt1(it)} м") }
                e.maxInnov?.let { append(", невязка фильтра до ${fmt1(it)} м") }
                e.minSats?.let { append(", спутников до $it") }
                if (e.hdopMin != null && e.hdopMax != null) append(", HDOP ${fmt1(e.hdopMin)}–${fmt1(e.hdopMax)}")
                if (e.aidingLost) append(", пропадают поправки")
                append(".")
                if (e.signalNotGeometry) append(" Деградация сигналов, а не геометрии — типичная картина помехи.")
                if (e.messages.isNotEmpty()) append(" Одновременно в логе: " + e.messages.joinToString("; ") { "«$it»" } + ".")
            }
        }
        if (interferenceZero) obs += "Признаки помех (индикатор подавления, шум, АРУ, состояния подавления и подмены) весь полёт равны нулю — " +
            "приёмник их не передаёт, РЭБ по ним не виден."
        else if (interference.isEmpty()) obs += "Лог не содержит признаков помех от приёмника (индикатор подавления, АРУ, C/N0)."
        s[Ch.GPS_JAM_STATE]?.let { js -> if (js.max >= 2f) obs += "Приёмник сообщал о подавлении сигнала (jamming_state ${js.max.toInt()})." }
        s[Ch.GPS_SPOOF]?.let { sp -> if (sp.max >= 2f) obs += "Приёмник сообщал о признаках подмены сигнала (spoofing_state ${sp.max.toInt()})." }
        if (fe != null && fv != null && fe.median > 0f) {
            val r = fv.median / fe.median
            if (r !in 1.0f..2.2f) obs += "Отношение вертикальной и горизонтальной оценки ${fmt1(r)} — необычно (норма ≈1,3–1,6)."
        }
        obs += "Это оценка точности, которую выдаёт сам приёмник, а не измеренная ошибка. Реальную ошибку даёт только эталон: " +
            "сырые измерения с постобработкой PPK или второй RTK-приёмник на борту."

        // Параметры EKF2 (PX4).
        val p = log.params.associate { it.name to it.value }
        val notes = ArrayList<String>()
        p["EKF2_GPS_CHECK"]?.toInt()?.let { mask ->
            fun share(key: String, limit: Float?) = limit?.let { lim -> values(s[key], t0, t1).takeIf { it.isNotEmpty() }?.let { v -> v.count { it > lim }.toFloat() / v.size } }
            for ((bit, key, param, what) in listOf(Quad(2, Ch.EPH, "EKF2_REQ_EPH", "горизонтальной"), Quad(3, Ch.EPV, "EKF2_REQ_EPV", "вертикальной"))) {
                val lim = p[param]
                val sh = share(key, lim) ?: continue
                val pct = (sh * 100).toInt()
                notes += if (mask and (1 shl bit) == 0) "Проверка $what погрешности отключена (EKF2_GPS_CHECK=$mask); при $param=${fmt1(lim!!)} м она бы не проходила $pct% полёта."
                else "Проверка $what погрешности включена ($param=${fmt1(lim!!)} м): превышение $pct% полёта."
            }
        }
        val hgtRef = p["EKF2_HGT_REF"]?.toInt()
        val gpsCtrl = p["EKF2_GPS_CTRL"]?.toInt()
        if (hgtRef == 0 && gpsCtrl != null && gpsCtrl and 2 == 0) {
            notes += "Высота ГНСС в фильтр не подаётся (EKF2_GPS_CTRL=$gpsCtrl), основная высота — барометр (EKF2_HGT_REF=0): сравнение ГНСС с барометром независимое."
        }
        if (p["GPS_1_CONFIG"]?.toInt() == 0) notes += "Штатный драйвер GPS PX4 отключён (GPS_1_CONFIG=0) — данные ГНСС приходят другим путём; сырые измерения и C/N0 нужно писать на стороне приёмника."

        return Report(ground, flight, takeoff, landing, innov, gnssBaroStd, gnssBaro95, velStd, fused, maxTr,
            interference, interferenceZero, episodes, ephBySats, climb, descent, rating, ratingText, obs, notes)
    }

    private data class Quad(val bit: Int, val key: String, val param: String, val what: String)

    private fun clockText(sec: Float): String {
        val m = (sec / 60).toInt(); val s = (sec % 60).toInt()
        return "%d:%02d".format(m, s)
    }
}
