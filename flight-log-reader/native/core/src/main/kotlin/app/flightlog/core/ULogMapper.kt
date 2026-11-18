package app.flightlog.core

import java.nio.ByteBuffer
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Преобразует журнал PX4 ULog в модель полёта. */
object ULogMapper {
    /** Прореживание каналов до 10 Гц, как у остальных форматов. */
    private const val MIN_GAP_US = 80_000L

    /** Режимы PX4 (vehicle_status.nav_state) — в обозначениях, близких к QGroundControl. */
    private val NAV_STATES = mapOf(
        0 to "MANUAL", 1 to "ALTCTL", 2 to "POSCTL", 3 to "MISSION", 4 to "HOLD", 5 to "RTL",
        6 to "POSCTL_SLOW", 10 to "ACRO", 12 to "DESCEND", 13 to "TERMINATION", 14 to "OFFBOARD",
        15 to "STABILIZED", 17 to "TAKEOFF", 18 to "LAND", 19 to "FOLLOW_ME", 20 to "PRECLAND",
        21 to "ORBIT", 22 to "VTOL_TAKEOFF",
    )

    fun modeName(state: Int) = NAV_STATES[state] ?: if (state in 23..30) "EXTERNAL${state - 22}" else "MODE $state"

    private class Col { val t = DoubleList(); val v = DoubleList() }

    fun map(fileName: String, fileSize: Long, buffer: ByteBuffer, progress: ProgressListener? = null): FlightLog {
        val log = ULog(buffer)
        val cols = LinkedHashMap<String, Col>()
        val meta = HashMap<String, Triple<String, String, String>>()
        val lastKept = HashMap<Long, Long>()
        var t0 = -1L
        var lastUs = 0L
        fun sec(us: Long) = (us - t0) / 1e6
        fun put(key: String, us: Long, v: Double, label: String, src: String, unit: String) {
            if (v.isNaN() || v.isInfinite()) return
            val c = cols.getOrPut(key) { meta[key] = Triple(label, src, unit); Col() }
            c.t.add(sec(us)); c.v.add(v)
        }
        fun due(sub: ULog.Subscription, us: Long): Boolean {
            val k = sub.msgId.toLong() shl 8 or sub.multiId.toLong()
            val last = lastKept[k]
            if (last != null && us >= last && us - last < MIN_GAP_US) return false
            lastKept[k] = us
            return true
        }

        val tT = DoubleList(); val tLat = DoubleList(); val tLon = DoubleList(); val tAlt = DoubleList(); val tSpd = DoubleList()
        var lastSpd = 0.0
        val events = ArrayList<LogEvent>()
        val modes = ArrayList<ModeChange>()
        var armT: Float? = null
        var disarmT: Float? = null
        var armed: Boolean? = null
        var failsafe = false
        var navState = -1
        var vehicleType = 0
        var isVtol = false
        var utcOffsetMs: Long? = null
        val batteries = sortedSetOf<Int>()
        val thrust = DoubleArray(4)
        var records = 0L
        var gpsTopic: String? = null

        log.scan(progress) { sub, us, b, o ->
            records++
            if (us <= 0) return@scan true
            if (t0 < 0) t0 = us
            if (us > lastUs) lastUs = us
            val f = sub.format
            fun v(name: String): Double = f.field(name)?.let { ULog.value(b, o, it) } ?: Double.NaN
            when (sub.name) {
                "vehicle_attitude" -> if (due(sub, us)) {
                    val w = v("q[0]"); val x = v("q[1]"); val y = v("q[2]"); val z = v("q[3]")
                    val roll = Math.toDegrees(atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)))
                    val pitch = Math.toDegrees(asin((2 * (w * y - z * x)).coerceIn(-1.0, 1.0)))
                    val yaw = Math.toDegrees(atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)))
                    put(Ch.ROLL, us, roll, "Крен", "vehicle_attitude.q", "°")
                    put(Ch.PITCH, us, pitch, "Тангаж", "vehicle_attitude.q", "°")
                    put(Ch.YAW, us, (yaw + 360) % 360, "Курс", "vehicle_attitude.q", "°")
                }
                "vehicle_local_position" -> if (due(sub, us)) {
                    put(Ch.ALT, us, -v("z"), "Высота", "vehicle_local_position.z", "м")
                    lastSpd = hypot(v("vx"), v("vy"))
                    put(Ch.SPD, us, lastSpd, "Скорость", "vehicle_local_position.vx,vy", "м/с")
                    put(Ch.CLIMB, us, -v("vz"), "Верт. скорость", "vehicle_local_position.vz", "м/с")
                }
                "vehicle_global_position" -> if (due(sub, us)) {
                    val lat = v("lat"); val lon = v("lon")
                    val valid = f.field("lat_lon_valid")?.let { ULog.value(b, o, it) != 0.0 } ?: true
                    if (valid && (lat != 0.0 || lon != 0.0) && !lat.isNaN()) {
                        tT.add(sec(us)); tLat.add(lat); tLon.add(lon); tAlt.add(v("alt")); tSpd.add(lastSpd)
                    }
                }
                // Один источник GPS: vehicle_gps_position, а sensor_gps — только если его нет (данные те же).
                "vehicle_gps_position", "sensor_gps" -> if (sub.multiId == 0 && (gpsTopic ?: sub.name.also { gpsTopic = it }) == sub.name && due(sub, us)) {
                    put(Ch.SATS, us, v("satellites_used"), "Спутники", "${sub.name}.satellites_used", "")
                    put(Ch.HDOP, us, v("hdop"), "HDOP", "${sub.name}.hdop", "")
                    f.field("jamming_indicator")?.let { put("gps_jam", us, ULog.value(b, o, it), "Индикатор помех GNSS", "${sub.name}.jamming_indicator", "") }
                    val utc = v("time_utc_usec")
                    if (utcOffsetMs == null && utc > 1e15) utcOffsetMs = (utc / 1000).toLong() - (us - t0) / 1000
                }
                "battery_status" -> {
                    val connected = f.field("connected")?.let { ULog.value(b, o, it) != 0.0 } ?: true
                    if (connected && due(sub, us)) {
                        val p = sub.multiId
                        batteries += p
                        val volt = v("voltage_v"); val curr = v("current_a"); val mah = v("discharged_mah")
                        val rem = v("remaining") * 100; val temp = v("temperature")
                        if (p == 0) {
                            put(Ch.VOLT, us, volt, "Напряжение", "battery_status.voltage_v", "В")
                            put(Ch.CURR, us, curr, "Ток", "battery_status.current_a", "А")
                            put(Ch.MAH, us, mah, "Израсходовано", "battery_status.discharged_mah", "мА·ч")
                            if (rem >= 0) put(Ch.REM, us, rem, "Заряд", "battery_status.remaining", "%")
                        }
                        val name = Batteries.label(p)
                        put(Batteries.key(p, Batteries.Metric.VOLT), us, volt, "$name · Напряжение", "battery_status[$p]", "В")
                        put(Batteries.key(p, Batteries.Metric.CURR), us, curr, "$name · Ток", "battery_status[$p]", "А")
                        put(Batteries.key(p, Batteries.Metric.MAH), us, mah, "$name · Израсходовано", "battery_status[$p]", "мА·ч")
                        if (rem >= 0) put(Batteries.key(p, Batteries.Metric.REM), us, rem, "$name · Заряд", "battery_status[$p]", "%")
                        put(Batteries.key(p, Batteries.Metric.TEMP), us, temp, "$name · Температура", "battery_status[$p]", "°C")
                    }
                }
                "vehicle_imu_status" -> if (sub.multiId == 0) {
                    // СКО ускорения по осям из дисперсии — та же мера, что VIBE в ArduPilot.
                    val keys = listOf(Ch.VIBE_X, Ch.VIBE_Y, Ch.VIBE_Z)
                    for (i in 0 until 3) {
                        val varA = v("var_accel[$i]")
                        if (varA >= 0) put(keys[i], us, sqrt(varA), "Вибрации ${"XYZ"[i]}", "vehicle_imu_status.var_accel[$i]", "м/с²")
                    }
                    val clip = v("accel_clipping[0]") + v("accel_clipping[1]") + v("accel_clipping[2]")
                    put(Ch.CLIP, us, clip, "Клиппинг акселерометра", "vehicle_imu_status.accel_clipping", "")
                    put("vibe_metric", us, v("accel_vibration_metric"), "Метрика вибраций PX4", "vehicle_imu_status.accel_vibration_metric", "м/с²")
                }
                "estimator_status" -> if (sub.multiId == 0 && due(sub, us)) {
                    val parts = listOf(
                        Triple(Ch.EKF_VEL, "vel_test_ratio", "EKF: скорость"), Triple(Ch.EKF_POS, "pos_test_ratio", "EKF: позиция"),
                        Triple(Ch.EKF_HGT, "hgt_test_ratio", "EKF: высота"), Triple(Ch.EKF_MAG, "hdg_test_ratio", "EKF: курс"),
                    )
                    var max = 0.0
                    for ((key, field, label) in parts) {
                        val r = v(field)
                        if (!r.isNaN()) { put(key, us, r, label, "estimator_status.$field", ""); max = maxOf(max, r) }
                    }
                    put(Ch.EKF, us, max, "EKF: инновации", "estimator_status · макс.", "")
                }
                "vehicle_thrust_setpoint" -> if (sub.multiId < thrust.size) {
                    thrust[sub.multiId] = sqrt(v("xyz[0]").let { it * it } + v("xyz[1]").let { it * it } + v("xyz[2]").let { it * it })
                    if (sub.multiId == 0 && due(sub, us)) put(Ch.THR, us, (thrust.max() * 100).coerceIn(0.0, 100.0), "Газ", "vehicle_thrust_setpoint · модуль", "%")
                }
                "actuator_motors" -> if (due(sub, us)) {
                    for (i in 0 until 12) {
                        val c = v("control[$i]")
                        if (!c.isNaN()) put("mout${i + 1}", us, c * 100, "Мотор ${i + 1} · выход", "actuator_motors.control[$i]", "%")
                    }
                }
                "input_rc" -> if (due(sub, us)) {
                    val rssi = v("rssi")
                    val lq = v("link_quality")
                    if (rssi in 0.0..100.0) put(Ch.RSSI, us, rssi, "Сигнал RC", "input_rc.rssi", "%")
                    else if (lq in 0.0..100.0) put(Ch.RSSI, us, lq, "Сигнал RC", "input_rc.link_quality", "%")
                }
                "vehicle_magnetometer" -> if (sub.multiId == 0 && due(sub, us)) {
                    val x = v("magnetometer_ga[0]"); val y = v("magnetometer_ga[1]"); val z = v("magnetometer_ga[2]")
                    put(Ch.MAG, us, sqrt(x * x + y * y + z * z) * 1000, "Поле компаса", "vehicle_magnetometer", "мГс")
                }
                "airspeed_validated" -> if (due(sub, us)) {
                    val tas = v("true_airspeed_m_s").takeIf { !it.isNaN() } ?: v("calibrated_airspeed_m_s")
                    put(Ch.ASPD, us, tas, "Воздушная скорость", "airspeed_validated.true_airspeed_m_s", "м/с")
                }
                "internal_combustion_engine_status" -> if (due(sub, us)) {
                    put("ice_rpm", us, v("rpm_filtered"), "ДВС · обороты", "internal_combustion_engine_status.rpm_filtered", "об/мин")
                    put("ice_cht", us, v("cylinder_head_temperature"), "ДВС · температура головки", "internal_combustion_engine_status.cylinder_head_temperature", "°C")
                    put("ice_egt", us, v("exhaust_gas_temperature"), "ДВС · температура выхлопа", "internal_combustion_engine_status.exhaust_gas_temperature", "°C")
                    put("ice_thr", us, v("throttle_gas_pct"), "ДВС · дроссель", "internal_combustion_engine_status.throttle_gas_pct", "%")
                }
                "vehicle_status" -> {
                    val t = sec(us).toFloat()
                    val state = v("nav_state").toInt()
                    if (state != navState) {
                        navState = state
                        val name = modeName(state)
                        modes += ModeChange(t, name)
                        events += LogEvent(t, EventKind.MODE, "Режим $name", "nav_state $state", "MODE")
                    }
                    val a = v("arming_state").toInt() == 2
                    if (armed != a) {
                        if (armed != null || a) {
                            if (a) { if (armT == null) armT = t } else disarmT = t
                            events += LogEvent(t, EventKind.INFO, if (a) "Моторы взведены" else "Моторы разоружены", "arming_state", "EV")
                        }
                        armed = a
                    }
                    val fs = v("failsafe") != 0.0
                    if (fs != failsafe) {
                        failsafe = fs
                        if (fs) events += LogEvent(t, EventKind.WARN, "Failsafe", "vehicle_status.failsafe", "EV")
                    }
                    vehicleType = v("vehicle_type").toInt()
                    isVtol = v("is_vtol") != 0.0
                }
            }
            true
        }
        if (t0 < 0) throw LogParseException("В журнале ULog нет записей с данными")

        // Текстовые сообщения; повторяющиеся (одинаковые без учёта чисел) не чаще раза в 30 с.
        val lastSeen = HashMap<String, Long>()
        for (m in log.messages) {
            if (m.timeUs < t0) continue
            val key = m.text.replace(Regex("[0-9.\\-]+"), "#")
            val prev = lastSeen[key]
            if (prev != null && m.timeUs - prev < 30_000_000) continue
            lastSeen[key] = m.timeUs
            events += LogEvent(sec(m.timeUs).toFloat(), if (m.level <= 4) EventKind.WARN else EventKind.INFO,
                m.text, LEVELS.getOrElse(m.level) { "" }, "MSG")
        }
        events.sortBy { it.time }

        val series = LinkedHashMap<String, Series>()
        for ((k, c) in cols) {
            if (k.startsWith("bat") && batteries.size < 2) continue
            val (label, src, unit) = meta.getValue(k)
            series[k] = Series(k, label, src, unit, c.t.toFloatArray(), c.v.toFloatArray())
        }
        // Неиспользуемые выходы и пустые каналы (всё по нулям) не показываем.
        series.keys.filter { it.startsWith("mout") || it.startsWith("ice_") || it == "gps_jam" }.forEach { k ->
            series[k]?.let { if (it.min == 0f && it.max == 0f) series.remove(k) }
        }

        val info = log.info
        val release = (info["ver_sw_release"] as? Number)?.toLong()
        val firmware = release?.let { r ->
            val major = (r shr 24) and 0xFF; val minor = (r shr 16) and 0xFF; val patch = (r shr 8) and 0xFF
            val kind = when ((r and 0xFF).toInt()) { 255 -> ""; 192 -> "-rc"; 128 -> "-beta"; 64 -> "-alpha"; else -> "-dev" }
            if (major == 0L && minor == 0L) null else "PX4 v$major.$minor.$patch$kind"
        } ?: (info["ver_sw_branch"] as? String).orEmpty()
        val type = when {
            isVtol -> "VTOL"
            vehicleType == 1 -> "Copter"
            vehicleType == 2 -> "Plane"
            vehicleType == 3 -> "Rover"
            else -> ""
        }
        val params = log.params.map { (k, value) -> Param(k, value, log.defaults[k]) }.sortedBy { it.name }
        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = LogFormat.ULOG,
            vehicle = VehicleInfo(
                autopilot = "PX4",
                vehicleType = type,
                firmware = firmware,
                board = (info["ver_hw"] as? String).orEmpty(),
                frame = when (type) { "VTOL" -> "VTOL"; "Copter" -> "Мультикоптер"; "Plane" -> "Самолёт"; "Rover" -> "Наземный"; else -> "" } +
                    ((log.params["SYS_AUTOSTART"])?.let { " · SYS_AUTOSTART ${it.toInt()}" } ?: ""),
                gps = "",
            ),
            startUtcMillis = utcOffsetMs,
            duration = ((lastUs - t0) / 1e6).toFloat(),
            series = series,
            track = Track(tT.toFloatArray(), tLat.toDoubleArray(), tLon.toDoubleArray(), tAlt.toFloatArray(), tSpd.toFloatArray()),
            events = events, modes = modes, params = params,
            armTime = armT, disarmTime = disarmT, messageCount = records,
            timeOriginUs = t0.toDouble(),
        )
    }

    private val LEVELS = listOf("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFO", "DEBUG")
}
