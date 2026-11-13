package app.flightlog.core

import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.sqrt

/** Преобразует журнал Геоскан в модель полёта. */
object GeoscanMapper {
    private const val GPS_EPOCH_MS = 315964800000L // 1980-01-06T00:00:00Z
    /** Прореживание каналов, как у остальных форматов. */
    private const val MIN_GAP_US = 80_000L
    /** Сырой акселерометр: 2048 единиц на g (диапазон ±16 g). */
    private const val ACCEL_SCALE = 9.80665f / 2048f
    private const val ACCEL_CLIP = 32000

    private class Col { val t = DoubleList(); val v = DoubleList() }

    fun map(fileName: String, fileSize: Long, buffer: ByteBuffer, progress: ProgressListener? = null): FlightLog {
        val log = GeoscanLog(buffer)
        val cols = LinkedHashMap<String, Col>()
        val meta = HashMap<String, Triple<String, String, String>>() // ключ → подпись, источник, единицы
        val lastKept = HashMap<String, Long>()
        var t0 = -1L
        var lastUs = 0L
        fun sec(us: Long) = ((us - t0) / 1e6)
        fun put(key: String, us: Long, v: Double, label: String, src: String, unit: String) {
            val c = cols.getOrPut(key) { meta[key] = Triple(label, src, unit); Col() }
            c.t.add(sec(us)); c.v.add(v)
        }
        /** Не чаще 10 Гц на сообщение (и экземпляр). */
        fun due(key: String, us: Long): Boolean {
            val last = lastKept[key]
            if (last != null && us >= last && us - last < MIN_GAP_US) return false
            lastKept[key] = us
            return true
        }

        val tT = DoubleList(); val tLat = DoubleList(); val tLon = DoubleList(); val tAlt = DoubleList(); val tSpd = DoubleList()
        var lastSpd = 0.0
        var fix = 3
        val events = ArrayList<LogEvent>()
        val modes = ArrayList<ModeChange>()
        val params = LinkedHashMap<String, Param>()
        val versions = ArrayList<String>()
        var timeDeltaUs: Long? = null
        var leap = 18
        var parachute = 0
        var hasPowerStatus = false
        // Вибрации: СКО ускорения за окно 0,1 с по каждой оси, как VIBE в ArduPilot.
        val acc = DoubleArray(6)
        var accN = 0
        var accStart = -1L
        var clips = 0
        // Ток и израсходованная ёмкость.
        var mah = 0.0
        var lastCurrUs = -1L

        log.scan(progress) { msg, us, d ->
            if (us > 0) { if (t0 < 0) t0 = us; lastUs = maxOf(lastUs, us) }
            if (t0 < 0) return@scan true
            val f = msg.fields
            fun v(i: Int) = GeoscanLog.value(d, f[i])
            when (msg.name) {
                "Orientation" -> if (due(msg.name, us)) {
                    put(Ch.ROLL, us, v(0), "Крен", "Orientation.roll", "°")
                    put(Ch.PITCH, us, v(1), "Тангаж", "Orientation.pitch", "°")
                    put(Ch.YAW, us, (v(2) + 360) % 360, "Курс", "Orientation.yaw", "°")
                }
                "Altitude" -> if (due(msg.name, us)) put(Ch.ALT, us, v(0), "Высота", "Altitude", "м")
                "Airspeed", "AirSpeed" -> if (due(msg.name, us)) put(AIRSPEED, us, v(0), "Воздушная скорость", "${msg.name}", "м/с")
                "Distance" -> if (due(msg.name, us)) put(DISTANCE, us, v(0), "Расстояние до дома", "Distance", "м")
                "NavVelocity" -> if (due(msg.name, us)) {
                    lastSpd = hypot(v(0), v(1))
                    put(Ch.SPD, us, lastSpd, "Скорость", "NavVelocity", "м/с")
                    put(Ch.CLIMB, us, -v(2), "Верт. скорость", "NavVelocity.vd", "м/с")
                }
                "NavPosition" -> {
                    val lat = v(0); val lon = v(1)
                    if (fix >= 3 && (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        tT.add(sec(us)); tLat.add(lat); tLon.add(lon); tAlt.add(v(2)); tSpd.add(lastSpd)
                    }
                }
                "NavSatInfo" -> {
                    fix = v(0).toInt()
                    put(Ch.SATS, us, v(3), "Спутники", "NavSatInfo", "")
                }
                "NavInformation" -> {
                    fix = v(6).toInt()
                    put(Ch.HDOP, us, v(0), "HDOP", "NavInformation.dop", "")
                    put(Ch.SATS, us, v(3), "Спутники", "NavInformation", "")
                }
                "NavPrecision" -> put(Ch.HDOP, us, v(0), "HDOP", "NavPrecision.dop", "")
                "PowerStatus", "AuxPowerStatus", "IntPowerStatus" -> {
                    // Основная АКБ: PowerStatus (201), AuxPowerStatus (401); IntPowerStatus — бортовое питание 101.
                    val main = msg.name != "IntPowerStatus" || !hasPowerStatus
                    if (msg.name != "IntPowerStatus") hasPowerStatus = true
                    if (main) {
                        val a = v(0); val volt = v(1)
                        if (lastCurrUs >= 0 && us > lastCurrUs && us - lastCurrUs < 5_000_000) mah += a * (us - lastCurrUs) / 3.6e6
                        lastCurrUs = us
                        if (due("power", us)) {
                            put(Ch.VOLT, us, volt, "Напряжение", "${msg.name}.V", "В")
                            put(Ch.CURR, us, a, "Ток", "${msg.name}.A", "А")
                            put(Ch.MAH, us, mah, "Израсходовано", "${msg.name}.A · интеграл", "мА·ч")
                            if (v(2) > 0) put(Ch.REM, us, v(2), "Заряд", "${msg.name}.%", "%")
                        }
                    }
                }
                "BatPowerStatus" -> if (v(2) > 0) put(Ch.REM, us, v(2), "Заряд", "BatPowerStatus.%", "%")
                "BatteryCells" -> if (due(msg.name, us)) f.forEachIndexed { i, fl ->
                    val mv = GeoscanLog.value(d, fl)
                    if (mv in 1.0..60000.0) put("cell${i + 1}", us, mv / 1000, "Элемент ${i + 1}", "BatteryCells.${fl.name}", "В")
                }
                "BatteryTemp" -> if (due(msg.name, us)) f.forEachIndexed { i, fl ->
                    put("battemp${i + 1}", us, GeoscanLog.value(d, fl), "Температура АКБ ${i + 1}", "BatteryTemp.${fl.name}", "°C")
                }
                "ControlData" -> if (due(msg.name, us)) {
                    msg.field("t")?.let { put(Ch.THR, us, GeoscanLog.value(d, it) * 100, "Газ", "ControlData.t", "%") }
                    for ((name, label) in listOf("e" to "Руль высоты", "a" to "Элероны", "r" to "Руль направления")) {
                        msg.field(name)?.let { put("ctl_$name", us, GeoscanLog.value(d, it), label, "ControlData.$name", "") }
                    }
                }
                "MagHeading" -> if (due(msg.name, us)) put(Ch.MAG, us, sqrt(v(0) * v(0) + v(1) * v(1) + v(2) * v(2)) * 1000, "Поле компаса", "MagHeading", "мГс")
                "DynamicPressure" -> if (due(msg.name, us)) put("pdyn", us, v(0), "Динамическое давление", "DynamicPressure", "Па")
                "StaticPressure", "IntStaticPressure" -> if (due(msg.name, us)) put("pstat", us, v(0), "Статическое давление", msg.name, "Па")
                "RawAccelGyroData", "MpuAccelGyroData" -> {
                    if (accStart < 0) accStart = us
                    for (i in 0 until 3) {
                        val raw = v(i)
                        if (raw >= ACCEL_CLIP || raw <= -ACCEL_CLIP) clips++
                        val a = raw * ACCEL_SCALE
                        acc[i] += a; acc[i + 3] += a * a
                    }
                    accN++
                    if (us - accStart >= 100_000 && accN >= 3) {
                        val keys = listOf(Ch.VIBE_X, Ch.VIBE_Y, Ch.VIBE_Z)
                        for (i in 0 until 3) {
                            val mean = acc[i] / accN
                            val sd = sqrt((acc[i + 3] / accN - mean * mean).coerceAtLeast(0.0))
                            put(keys[i], us, sd, "Вибрации ${"XYZ"[i]}", "${msg.name} · СКО за 0,1 с", "м/с²")
                        }
                        put(Ch.CLIP, us, clips.toDouble(), "Клиппинг акселерометра", msg.name, "")
                        acc.fill(0.0); accN = 0; accStart = us
                    }
                }
                "Motor", "CanMotor" -> {
                    val num = msg.field("n")?.let { GeoscanLog.value(d, it).toInt() } ?: 0
                    if (due("${msg.name}$num", us)) {
                        // 401: CAN-адреса 64… → моторы 1…
                        val m = if (num >= 64) num - 63 else num + 1
                        msg.field("RPM")?.let { put("rpm$m", us, GeoscanLog.value(d, it), "Мотор $m · обороты", "${msg.name}[$num].RPM", "об/мин") }
                        msg.field("r")?.takeIf { msg.name == "CanMotor" }?.let { put("rpm$m", us, GeoscanLog.value(d, it), "Мотор $m · обороты", "${msg.name}[$num].r", "об/мин") }
                        msg.field("A")?.let {
                            val a = GeoscanLog.value(d, it)
                            put("mcur$m", us, if (msg.name == "Motor") a / 1000 else a, "Мотор $m · ток", "${msg.name}[$num].A", "А")
                        }
                        msg.field("C")?.let { put("mtemp$m", us, GeoscanLog.value(d, it), "Мотор $m · температура", "${msg.name}[$num].C", "°C") }
                        msg.field("T")?.let { put("mtemp$m", us, GeoscanLog.value(d, it), "Мотор $m · температура", "${msg.name}[$num].T", "°C") }
                    }
                }
                "UavMode" -> {
                    val mode = v(0).toInt()
                    val name = modeName(mode)
                    if (modes.lastOrNull()?.name != name) {
                        modes += ModeChange(sec(us).toFloat(), name)
                        events += LogEvent(sec(us).toFloat(), EventKind.MODE, "Режим $name", "UavMode $mode", "MODE")
                    }
                }
                "EventLog" -> {
                    val code = v(0).toInt()
                    events += LogEvent(sec(us).toFloat(), EventKind.INFO, "Событие $code", "EventLog $code", "EV")
                }
                "ParachuteState" -> {
                    val s = v(0).toInt()
                    if (s != parachute) {
                        events += LogEvent(sec(us).toFloat(), if (s != 0) EventKind.WARN else EventKind.INFO,
                            if (s != 0) "Парашют выпущен" else "Парашют: состояние $s", "ParachuteState $s", "EV")
                        parachute = s
                    }
                }
                "TimeDelta" -> if (timeDeltaUs == null) timeDeltaUs = v(0).toLong()
                "LeapSecond" -> leap = v(0).toInt()
                "Version" -> {
                    val hw = GeoscanLog.string(d, f[0])
                    val ver = if (v(1) == 0.0 && v(2) == 0.0) "" else "${v(1).toInt()}.${v(2).toInt()} · "
                    if (versions.size < 20) versions += "$hw ${ver}сборка ${v(5).toInt()}"
                }
                else -> if (msg.name.startsWith("Parameter") && f.size == 2 && f[0].type == 1) {
                    val name = GeoscanLog.string(d, f[0])
                    if (name.isNotEmpty()) params[name] = Param(name, v(1).toFloat(), null)
                }
            }
            true
        }
        if (t0 < 0) throw LogParseException("В журнале Геоскан нет записей с данными")

        val series = LinkedHashMap<String, Series>()
        for ((k, c) in cols) {
            val (label, src, unit) = meta.getValue(k)
            series[k] = Series(k, label, src, unit, c.t.toFloatArray(), c.v.toFloatArray())
        }
        // Ток без датчика (101: только бортовое напряжение) — нули не показываем.
        for (k in listOf(Ch.CURR, Ch.MAH)) series[k]?.let { if (it.min == 0f && it.max == 0f) series.remove(k) }
        events.sortBy { it.time }

        // Полёт: газ или обороты моторов выше нуля.
        val activity = series[Ch.THR]?.takeIf { it.max > 5f }?.let { s -> s to 5f }
            ?: series.keys.firstOrNull { it.startsWith("rpm") }?.let { series.getValue(it) to 100f }
        val armT = activity?.let { (s, th) -> (0 until s.size).firstOrNull { s.values[it] > th }?.let { s.time[it] } }
        val disarmT = activity?.let { (s, th) -> (s.size - 1 downTo 0).firstOrNull { s.values[it] > th }?.let { s.time[it] } }
        if (armT != null) events += LogEvent(armT, EventKind.INFO, "Моторы запущены", "газ / обороты", "EV")
        if (disarmT != null && disarmT > (armT ?: 0f)) events += LogEvent(disarmT, EventKind.INFO, "Моторы остановлены", "газ / обороты", "EV")
        events.sortBy { it.time }

        val copter = series.keys.any { it.startsWith("rpm") } && AIRSPEED !in series
        val autopilot = versions.firstOrNull { it.startsWith("Autopilot") }?.removePrefix("Autopilot")?.trim().orEmpty()
        val board = versions.firstOrNull { it.startsWith("CBoard") }?.substringBefore(' ').orEmpty()
        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = LogFormat.GEOSCAN,
            vehicle = VehicleInfo(
                autopilot = "Геоскан",
                vehicleType = if (copter) "Мультикоптер" else "Самолёт",
                firmware = autopilot.ifEmpty { "" },
                board = board,
                frame = if (copter) "Геоскан, мультикоптер" else "Геоскан, самолёт",
                gps = versions.firstOrNull { it.startsWith("Ublox") }?.substringBefore(' ').orEmpty(),
            ),
            startUtcMillis = timeDeltaUs?.let { GPS_EPOCH_MS + (it + t0) / 1000 - leap * 1000L },
            duration = ((lastUs - t0) / 1e6).toFloat(),
            series = series,
            track = Track(tT.toFloatArray(), tLat.toDoubleArray(), tLon.toDoubleArray(), tAlt.toFloatArray(), tSpd.toFloatArray()),
            events = events, modes = modes, params = params.values.sortedBy { it.name },
            armTime = armT, disarmTime = disarmT, messageCount = log.records,
            timeOriginUs = t0.toDouble(),
        )
    }

    const val AIRSPEED = "aspd"
    const val DISTANCE = "dist"

    /** Номера режимов UavMode; названий в журнале нет. */
    fun modeName(mode: Int) = "MODE $mode"
}
