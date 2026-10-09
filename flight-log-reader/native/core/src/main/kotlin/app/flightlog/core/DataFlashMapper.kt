package app.flightlog.core

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
        val gpsTab = t["GPS"]?.let { g -> if (g.has("I")) filterInstance(g, "I", 0.0) else g }
        val bat = (t["BAT"] ?: t["BAT0"] ?: t["CURR"])?.let { b -> if (b.has("Inst")) filterInstance(b, "Inst", 0.0) else b }
        val batName = if (t["BAT"] != null) "BAT" else if (t["BAT0"] != null) "BAT0" else "CURR"
        val vibe = t["VIBE"]?.let { v -> if (v.has("IMU")) filterInstance(v, "IMU", 0.0) else v }
        val ctun = t["CTUN"]

        if (ctun != null && ctun.has("Alt")) add(Ch.ALT, "Высота", "CTUN", "Alt", "м", ctun)
        else t["POS"]?.let { add(Ch.ALT, "Высота", "POS", "RelHomeAlt", "м", it) }
        add(Ch.SPD, "Скорость", "GPS", "Spd", "м/с", gpsTab)
        add(Ch.VOLT, "Напряжение", batName, "Volt", "В", bat)
        add(Ch.CURR, "Ток", batName, "Curr", "А", bat)
        add(Ch.MAH, "Израсходовано", batName, "CurrTot", "мА·ч", bat)
        add(Ch.REM, "Заряд", batName, "RemPct", "%", bat)
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
                val title = if (c == 0) "$name: восстановлено" else "$name: ошибка $c"
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

        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = format,
            vehicle = VehicleInfo("ArduPilot", vehicleType, firmware, board, frame, gps),
            startUtcMillis = startUtc, duration = tEnd, series = series, track = track,
            events = events, modes = modes, params = params, armTime = armT, disarmTime = disarmT,
            messageCount = r.messageCount,
        )
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
