package app.flightlog.core

import kotlin.math.acos
import kotlin.math.sqrt

/** Преобразует таблицы DataFlash в модель полёта. */
object DataFlashMapper {
    private const val GPS_EPOCH_MS = 315964800000L // 1980-01-06T00:00:00Z
    private const val LEAP_SECONDS = 18

    fun map(fileName: String, fileSize: Long, r: DataFlashParser.Result, format: LogFormat = LogFormat.DATAFLASH): FlightLog {
        val t = r.tables
        val t0 = t.values.mapNotNull { tab -> tab.numeric["TimeUS"]?.takeIf { it.size > 0 }?.get(0) }
            .minOrNull() ?: 0.0
        fun time(tab: MessageTable): FloatArray {
            val us = tab.num("TimeUS")
            return FloatArray(us.size) { ((us[it] - t0) / 1e6).toFloat() }
        }

        val tEnd = t.values.mapNotNull { tab ->
            tab.numeric["TimeUS"]?.takeIf { it.size > 0 }?.let { it[it.size - 1] }
        }.maxOrNull()?.let { ((it - t0) / 1e6).toFloat() } ?: 0f

        // Сведения о борте из текстовых сообщений.
        val msgs = t["MSG"]
        var vehicleType = ""
        var firmware = ""
        var board = ""
        var frame = ""
        var gps = ""
        msgs?.str("Message")?.forEach { m ->
            ArduPilot.vehicleTypeFrom(m)?.let {
                if (firmware.isEmpty() && m.contains(" V")) {
                    vehicleType = it
                    firmware = m.substringBefore(" (").trim()
                }
            }
            when {
                m.startsWith("Frame:") -> frame = m.removePrefix("Frame:").trim()
                gps.isEmpty() && m.startsWith("GPS 1: detected") ->
                    gps = m.removePrefix("GPS 1: detected").substringBefore(" at").trim()
                board.isEmpty() && BOARD.containsMatchIn(m) -> board = m.substringBefore(' ')
            }
        }
        t["VER"]?.let { v ->
            if (firmware.isEmpty() && v.has("FWS")) firmware = v.str("FWS").firstOrNull().orEmpty()
        }

        val series = LinkedHashMap<String, Series>()
        fun add(key: String, label: String, msg: String, col: String, unit: String, tab: MessageTable?,
                transform: ((Float) -> Float)? = null) {
            if (tab == null || !tab.has(col) || tab.rows == 0) return
            val v = tab.float(col)
            if (transform != null) for (i in v.indices) v[i] = transform(v[i])
            series[key] = Series(key, label, "$msg.$col", unit, time(tab), v)
        }
        val gpsTab = t["GPS"]?.let { firstInstance(it) }
        val batName = if (t["BAT"] != null) "BAT" else if (t["BAT0"] != null) "BAT0" else "CURR"
        val batAll = t[batName]
        val bat = batAll?.let { firstInstance(it) }
        val vibe = t["VIBE"]?.let { firstInstance(it) }
        val ctun = t["CTUN"]

        if (ctun != null && ctun.has("Alt")) add(Ch.ALT, "Высота", "CTUN", "Alt", "м", ctun)
        else t["POS"]?.let { add(Ch.ALT, "Высота", "POS", "RelHomeAlt", "м", it) }
        add(Ch.SPD, "Скорость", "GPS", "Spd", "м/с", gpsTab)
        add(Ch.VOLT, "Напряжение", batName, "Volt", "В", bat)
        add(Ch.CURR, "Ток", batName, "Curr", "А", bat)
        add(Ch.MAH, "Израсходовано", batName, "CurrTot", "мА·ч", bat)
        add(Ch.REM, "Заряд", batName, "RemPct", "%", bat)
        // Несколько АКБ: отдельные каналы на каждую.
        val packs = batAll?.let { b -> instanceColumn(b)?.let { c -> b.num(c).map { it.toInt() }.distinct().sorted() } }.orEmpty()
        if (packs.size > 1) {
            val col = instanceColumn(batAll!!)!!
            val extra = t["BATA"]
            for (p in packs) {
                val tab = filterInstance(batAll, col, p.toDouble())
                val name = Batteries.label(p)
                fun addBat(m: Batteries.Metric, src: String, column: String, from: MessageTable?) =
                    add(Batteries.key(p, m), "$name · ${m.title}", "$src[$p]", column, m.unit, from)
                addBat(Batteries.Metric.VOLT, batName, "Volt", tab)
                addBat(Batteries.Metric.CURR, batName, "Curr", tab)
                addBat(Batteries.Metric.MAH, batName, "CurrTot", tab)
                addBat(Batteries.Metric.REM, batName, "RemPct", tab)
                addBat(Batteries.Metric.TEMP, batName, "Temp", tab)
                addBat(Batteries.Metric.RES, batName, "Res", tab)
                // Без датчика тока ArduPilot пишет нули — такие каналы не показываем.
                Batteries.Metric.entries.forEach { m ->
                    val k = Batteries.key(p, m)
                    if (m != Batteries.Metric.VOLT && series[k]?.let { it.min == 0f && it.max == 0f } == true) series.remove(k)
                }
                if (Batteries.key(p, Batteries.Metric.MAH) !in series && extra != null) {
                    extra.let { e -> instanceColumn(e)?.let { c -> filterInstance(e, c, p.toDouble()) } }
                        ?.let { addBat(Batteries.Metric.MAH, "BATA", "TotmAh", it) }
                }
            }
            // Основные каналы описывают первую АКБ — так и подписываем.
            for (k in listOf(Ch.VOLT, Ch.CURR, Ch.MAH, Ch.REM)) series[k]?.let { s ->
                series[k] = Series(s.key, "${s.label} · ${Batteries.label(packs.first())}", s.message, s.unit, s.time, s.values)
            }
        }
        add(Ch.VIBE_X, "Вибрации X", "VIBE", "VibeX", "м/с²", vibe)
        add(Ch.VIBE_Y, "Вибрации Y", "VIBE", "VibeY", "м/с²", vibe)
        add(Ch.VIBE_Z, "Вибрации Z", "VIBE", "VibeZ", "м/с²", vibe)
        add(Ch.CLIP, "Клиппинг IMU0", "VIBE", if (vibe?.has("Clip") == true) "Clip" else "Clip0", "", vibe)
        add(Ch.SATS, "Спутники", "GPS", "NSats", "", gpsTab)
        add(Ch.HDOP, "HDOP", "GPS", "HDop", "", gpsTab)
        add(Ch.ROLL, "Крен", "ATT", "Roll", "°", t["ATT"])
        add(Ch.PITCH, "Тангаж", "ATT", "Pitch", "°", t["ATT"])
        add(Ch.YAW, "Курс", "ATT", "Yaw", "°", t["ATT"])
        if (ctun != null && ctun.has("CRt")) add(Ch.CLIMB, "Верт. скорость", "CTUN", "CRt", "м/с", ctun) { it / 100f }
        else gpsTab?.let { g -> if (g.has("VZ")) add(Ch.CLIMB, "Верт. скорость", "GPS", "VZ", "м/с", g) { -it } }

        if (ctun != null && ctun.has("ThO")) add(Ch.THR, "Газ", "CTUN", "ThO", "%", ctun) { it * 100f }
        t["RSSI"]?.let { r ->
            add(Ch.RSSI, "Сигнал RC", "RSSI", "RXRSSI", "%", r)
            // ArduPilot пишет 0…1; старые прошивки — 0…255.
            series[Ch.RSSI]?.let { s ->
                val k = if (s.max <= 1.001f) 100f else 100f / 255f
                series[Ch.RSSI] = Series(s.key, s.label, s.message, s.unit, s.time, FloatArray(s.size) { s.values[it] * k })
            }
        }
        addEkf(t["XKF4"], series, ::time)
        addCompasses(t, series, ::time)

        val track = buildTrack(gpsTab, ::time)
        val startUtc = gpsTab?.let { g ->
            if (!g.has("GWk") || !g.has("GMS")) return@let null
            val wk = g.num("GWk"); val ms = g.num("GMS"); val us = g.num("TimeUS")
            val i = wk.indices.firstOrNull { wk[it] > 0 } ?: return@let null
            GPS_EPOCH_MS + wk[i].toLong() * 604_800_000L + ms[i].toLong() - LEAP_SECONDS * 1000L -
                ((us[i] - t0) / 1000.0).toLong()
        }

        // События.
        val events = ArrayList<LogEvent>()
        val modes = ArrayList<ModeChange>()
        t["MODE"]?.let { m ->
            val tt = time(m)
            val num = m.num(if (m.has("Mode")) "Mode" else "ModeNum")
            for (i in tt.indices) {
                val name = ArduPilot.modeName(vehicleType, num[i].toInt())
                modes += ModeChange(tt[i], name)
                events += LogEvent(tt[i], EventKind.MODE, "Режим $name", modeReason(m, i), "MODE")
            }
        }
        var armT: Float? = null
        var disarmT: Float? = null
        t["EV"]?.let { e ->
            val tt = time(e); val id = e.num("Id")
            for (i in tt.indices) {
                val code = id[i].toInt()
                if (code == 10 && armT == null) armT = tt[i]
                if (code == 11) disarmT = tt[i]
                events += LogEvent(tt[i], EventKind.INFO, ArduPilot.EV_NAMES[code] ?: "Событие $code", "EV $code", "EV")
            }
        }
        t["ERR"]?.let { e ->
            val tt = time(e); val sub = e.num("Subsys"); val code = e.num("ECode")
            for (i in tt.indices) {
                val s = sub[i].toInt(); val c = code[i].toInt()
                val name = ArduPilot.ERR_SUBSYS[s] ?: "Подсистема $s"
                val title = "$name: ${ArduPilot.errCodeText(s, c, vehicleType)}"
                events += LogEvent(tt[i], if (c == 0) EventKind.INFO else EventKind.WARN, title, "Subsys $s · ECode $c", "ERR")
            }
        }
        msgs?.let { m ->
            val tt = time(m); val text = m.str("Message")
            for (i in tt.indices) {
                val s = text[i]
                // Служебные сообщения загрузки пропускаем, если они до взведения и не предупреждения.
                val warn = WARN_WORDS.any { s.contains(it, ignoreCase = true) }
                events += LogEvent(tt[i], if (warn) EventKind.WARN else EventKind.INFO, s, "", "MSG")
            }
        }
        events.sortBy { it.time }

        val params = t["PARM"]?.let { p ->
            val names = p.str("Name"); val vals = p.num("Value")
            val defs = if (p.has("Default")) p.num("Default") else null
            val map = LinkedHashMap<String, Param>()
            for (i in names.indices) {
                val d = defs?.get(i)?.takeIf { !it.isNaN() }?.toFloat()
                map[names[i]] = Param(names[i], vals[i].toFloat(), d)
            }
            map.values.sortedBy { it.name }
        } ?: emptyList()

        // Выходы на моторы: какие выходы — моторы, известно из параметров.
        t["RCOU"]?.let { rc ->
            val pm = params.associate { it.name to it.value }
            val heli = Motors.isHeli(pm, frame, firmware)
            for ((out, motor) in Motors.outputs(pm, frame, vehicleType, heli)) {
                val col = listOf("C$out", "Ch$out").firstOrNull { rc.has(it) } ?: continue
                add(Motors.key(motor), Motors.label(motor), "RCOU", col, "мкс", rc)
            }
        }

        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = format,
            vehicle = VehicleInfo("ArduPilot", vehicleType, firmware, board, frame, gps),
            startUtcMillis = startUtc, duration = tEnd, series = series, track = track,
            events = events, modes = modes, params = params, armTime = armT, disarmTime = disarmT,
            messageCount = r.messageCount,
        )
    }

    /** Отношения тестов инноваций EKF3 (XKF4) первого ядра; больше 1 — измерения отвергаются. */
    private fun addEkf(x: MessageTable?, series: MutableMap<String, Series>, time: (MessageTable) -> FloatArray) {
        if (x == null || x.rows == 0) return
        val tab = if (x.has("C")) filterInstance(x, "C", x.num("C").minOrNull() ?: 0.0) else x
        val tt = time(tab)
        val parts = listOf(
            Triple(Ch.EKF_VEL, "SV", "EKF: скорость"), Triple(Ch.EKF_POS, "SP", "EKF: позиция"),
            Triple(Ch.EKF_HGT, "SH", "EKF: высота"), Triple(Ch.EKF_MAG, "SM", "EKF: компас"),
        ).filter { tab.has(it.second) }
        if (parts.isEmpty()) return
        val all = parts.map { tab.float(it.second) }
        parts.forEachIndexed { i, (key, col, label) -> series[key] = Series(key, label, "XKF4.$col", "", tt, all[i]) }
        val max = FloatArray(tt.size) { r -> all.maxOf { it[r] } }
        series[Ch.EKF] = Series(Ch.EKF, "EKF: инновации", "XKF4 · макс.", "", tt, max)
    }

    /** Модуль поля каждого компаса и угол расхождения с первым. */
    private fun addCompasses(t: Map<String, MessageTable>, series: MutableMap<String, Series>, time: (MessageTable) -> FloatArray) {
        val tabs = ArrayList<MessageTable>()
        t["MAG"]?.let { m ->
            val col = instanceColumn(m)
            if (col == null) tabs += m else m.num(col).distinct().sorted().forEach { tabs += filterInstance(m, col, it) }
        }
        t["MAG2"]?.let { tabs += it }
        t["MAG3"]?.let { tabs += it }
        val vec = tabs.filter { it.rows > 0 && it.has("MagX") && it.has("MagY") && it.has("MagZ") }
            .map { Triple(it.float("MagX"), it.float("MagY"), it.float("MagZ")) to time(it) }
        vec.forEachIndexed { i, (v, tt) ->
            val (x, y, z) = v
            val mod = FloatArray(tt.size) { r -> sqrt(x[r] * x[r] + y[r] * y[r] + z[r] * z[r]) }
            val key = Ch.mag(i)
            series[key] = Series(key, if (i == 0) "Поле компаса" else "Поле компаса ${i + 1}", "MAG[$i]", "мГс", tt, mod)
        }
        if (vec.size < 2) return
        val (v0, t0) = vec[0]
        val ref = Series("", "", "", "", t0, FloatArray(t0.size))
        for (i in 1 until vec.size) {
            val (v, tt) = vec[i]
            val ang = FloatArray(tt.size) { r ->
                val j = ref.indexAt(tt[r])
                val dot = v.first[r] * v0.first[j] + v.second[r] * v0.second[j] + v.third[r] * v0.third[j]
                val n = sqrt(v.first[r] * v.first[r] + v.second[r] * v.second[r] + v.third[r] * v.third[r]) *
                    sqrt(v0.first[j] * v0.first[j] + v0.second[j] * v0.second[j] + v0.third[j] * v0.third[j])
                if (n < 1e-3f) 0f else Math.toDegrees(acos((dot / n).coerceIn(-1f, 1f)).toDouble()).toFloat()
            }
            val key = Ch.magDiff(i)
            series[key] = Series(key, "Расхождение компасов 1 и ${i + 1}", "MAG[0]∠MAG[$i]", "°", tt, ang)
        }
    }

    private fun modeReason(m: MessageTable, i: Int): String {
        if (!m.has("Rsn")) return ""
        return when (m.numAt("Rsn", i).toInt()) {
            1 -> "Переключатель RC"; 2 -> "Команда GCS"; 3 -> "Failsafe радио"; 4 -> "Failsafe батареи"
            5 -> "Failsafe GCS"; 6 -> "Failsafe EKF"; 7 -> "Failsafe GPS"; 9 -> "Геозона"
            10 -> "Миссия завершена"; 12 -> "Завершение посадки"; 13 -> "Терраин"
            else -> ""
        }
    }

    /** Имя столбца с номером экземпляра (GPS.I, BAT.Inst / BAT.Instance, VIBE.IMU). */
    private fun instanceColumn(tab: MessageTable): String? =
        listOf("Instance", "Inst", "I", "IMU").firstOrNull { tab.has(it) }

    /** Только первый экземпляр сообщения (наименьший номер). */
    private fun firstInstance(tab: MessageTable): MessageTable {
        val col = instanceColumn(tab) ?: return tab
        val first = tab.num(col).minOrNull() ?: return tab
        return filterInstance(tab, col, first)
    }

    private fun filterInstance(tab: MessageTable, col: String, inst: Double): MessageTable {
        val keep = tab.num(col)
        if (keep.all { it == inst }) return tab
        val out = MessageTable(tab.name, tab.columns)
        val rows = keep.indices.filter { keep[it] == inst }
        tab.numeric.forEach { (k, v) -> val l = DoubleList(rows.size + 1); rows.forEach { l.add(v[it]) }; out.numeric[k] = l }
        tab.text.forEach { (k, v) -> out.text[k] = rows.map { v[it] }.toMutableList() }
        out.rows = rows.size
        return out
    }

    private fun buildTrack(g: MessageTable?, time: (MessageTable) -> FloatArray): Track {
        if (g == null || !g.has("Lat") || !g.has("Lng")) return Track(FloatArray(0), DoubleArray(0), DoubleArray(0), FloatArray(0), FloatArray(0))
        val tt = time(g)
        val lat = g.num("Lat"); val lon = g.num("Lng")
        val alt = if (g.has("Alt")) g.num("Alt") else DoubleArray(lat.size)
        val spd = if (g.has("Spd")) g.num("Spd") else DoubleArray(lat.size)
        val status = if (g.has("Status")) g.num("Status") else null
        val idx = lat.indices.filter { (status == null || status[it] >= 3) && !(lat[it] == 0.0 && lon[it] == 0.0) }
        return Track(
            FloatArray(idx.size) { tt[idx[it]] },
            DoubleArray(idx.size) { lat[idx[it]] },
            DoubleArray(idx.size) { lon[idx[it]] },
            FloatArray(idx.size) { alt[idx[it]].toFloat() },
            FloatArray(idx.size) { spd[idx[it]].toFloat() },
        )
    }

    private val BOARD = Regex("""^(Cube|Pixhawk|Matek|Kakute|SpeedyBee|Durandal|Holybro|mRo|CUAV|Pixracer|fmuv|MambaF|Omnibus|Navigator|Here|Aero|Sky|Hitec|NxtPX|BeastF|JHEMCU|Flywoo|Pix32|KakuteH|CubeOrange)""", RegexOption.IGNORE_CASE)
    private val WARN_WORDS = listOf("fail", "error", "err:", "bad", "lost", "glitch", "crash", "emergency",
        "PreArm", "low", "critical", "vibration", "potential thrust loss", "EKF variance", "yaw reset")
}
