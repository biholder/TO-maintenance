package app.flightlog.core

import kotlin.math.PI

/** Преобразует пакеты MAVLink в модель полёта. */
object TlogMapper {
    /** Пакеты с телеметрией, которые прореживаются до 10 Гц; события и параметры — нет. */
    private val DECIMATED = setOf(
        TlogParser.SYS_STATUS, TlogParser.GPS_RAW_INT, TlogParser.ATTITUDE, TlogParser.GLOBAL_POSITION_INT,
        TlogParser.VFR_HUD, TlogParser.BATTERY_STATUS, TlogParser.VIBRATION,
    )
    private const val MIN_GAP_US = 80_000L

    fun map(fileName: String, fileSize: Long, buffer: java.nio.ByteBuffer, progress: ProgressListener? = null): FlightLog {
        val parser = TlogParser()
        // Проход 1: борт — система, приславшая HEARTBEAT с реальным автопилотом (autopilot != 8 INVALID).
        var hbFound: TlogParser.Packet? = null
        parser.scan(buffer) { p ->
            if (p.msgId == TlogParser.HEARTBEAT && (p.payload.get(5).toInt() and 0xFF) != 8) { hbFound = p; false } else true
        }
        val hb = hbFound ?: throw LogParseException("В логе нет HEARTBEAT от борта")
        val sys = hb.sysId
        val comp = hb.compId
        var t0 = -1L
        var lastUs = 0L
        var ownCount = 0L
        val lastKept = HashMap<Int, Long>()
        fun ts(p: TlogParser.Packet) = ((p.timeUs - t0) / 1e6).toFloat()

        val mavType = hb.payload.get(4).toInt() and 0xFF
        val autopilotId = hb.payload.get(5).toInt() and 0xFF
        val vehicleType = when (mavType) {
            1, 16, 19, 20, 21, 22, 23, 24, 25 -> "Plane"
            10, 11 -> "Rover"
            12 -> "Sub"
            else -> "Copter"
        }

        val cols = HashMap<String, Pair<DoubleList, DoubleList>>()
        fun put(key: String, t: Float, v: Double) {
            val c = cols.getOrPut(key) { DoubleList() to DoubleList() }
            c.first.add(t.toDouble()); c.second.add(v)
        }
        val tT = DoubleList(); val tLat = DoubleList(); val tLon = DoubleList(); val tAlt = DoubleList(); val tSpd = DoubleList()
        var lastSpd = 0.0
        val events = ArrayList<LogEvent>()
        val modes = ArrayList<ModeChange>()
        val params = LinkedHashMap<String, Param>()
        var firmware = ""
        var frame = ""
        var armT: Float? = null
        var disarmT: Float? = null
        var wasArmed: Boolean? = null
        var lastMode = -1
        var startUtc: Long? = null

        // Проход 2: сборка каналов.
        parser.scan(buffer, progress) scan@{ p ->
            if (p.sysId != sys || p.compId != comp) return@scan true
            if (t0 < 0) t0 = p.timeUs
            ownCount++
            lastUs = p.timeUs
            if (p.msgId in DECIMATED) {
                val last = lastKept[p.msgId]
                if (last != null && p.timeUs >= last && p.timeUs - last < MIN_GAP_US) return@scan true
                lastKept[p.msgId] = p.timeUs
            }
            val b = p.payload
            val t = ts(p)
            when (p.msgId) {
                TlogParser.HEARTBEAT -> {
                    val custom = b.getInt(0).toLong() and 0xFFFFFFFFL
                    val armed = (b.get(6).toInt() and 0x80) != 0
                    if (custom.toInt() != lastMode) {
                        lastMode = custom.toInt()
                        val name = if (autopilotId == 3) ArduPilot.modeName(vehicleType, lastMode) else "MODE $lastMode"
                        modes += ModeChange(t, name)
                        events += LogEvent(t, EventKind.MODE, "Режим $name", "", "HEARTBEAT")
                    }
                    if (wasArmed != armed) {
                        if (wasArmed != null || armed) {
                            if (armed) { if (armT == null) armT = t } else disarmT = t
                            events += LogEvent(t, EventKind.INFO, if (armed) "Моторы взведены" else "Моторы разоружены", "", "HEARTBEAT")
                        }
                        wasArmed = armed
                    }
                }
                TlogParser.SYS_STATUS -> {
                    val mv = b.getShort(14).toInt() and 0xFFFF
                    val ca = b.getShort(16).toInt()
                    val rem = b.get(30).toInt()
                    if (mv != 0xFFFF && mv > 0) put(Ch.VOLT, t, mv / 1000.0)
                    if (ca >= 0) put(Ch.CURR, t, ca / 100.0)
                    if (rem >= 0) put(Ch.REM, t, rem.toDouble())
                }
                TlogParser.SYSTEM_TIME -> {
                    val unixUs = b.getLong(0)
                    if (startUtc == null && unixUs > 1_000_000_000_000_000L) startUtc = unixUs / 1000 - (t * 1000).toLong()
                }
                TlogParser.BATTERY_STATUS -> {
                    val consumed = b.getInt(0)
                    if (consumed >= 0) put(Ch.MAH, t, consumed.toDouble())
                }
                TlogParser.GPS_RAW_INT -> {
                    val fix = b.get(28).toInt() and 0xFF
                    val sats = b.get(29).toInt() and 0xFF
                    val eph = b.getShort(20).toInt() and 0xFFFF
                    val vel = b.getShort(24).toInt() and 0xFFFF
                    if (sats != 255) put(Ch.SATS, t, sats.toDouble())
                    if (eph != 0xFFFF) put(Ch.HDOP, t, eph / 100.0)
                    if (vel != 0xFFFF) { lastSpd = vel / 100.0; put(Ch.SPD, t, lastSpd) }
                }
                TlogParser.GLOBAL_POSITION_INT -> {
                    val lat = b.getInt(4) / 1e7
                    val lon = b.getInt(8) / 1e7
                    val rel = b.getInt(16) / 1000.0
                    put(Ch.ALT, t, rel)
                    put(Ch.CLIMB, t, -b.getShort(24) / 100.0)
                    if (lat != 0.0 || lon != 0.0) {
                        tT.add(t.toDouble()); tLat.add(lat); tLon.add(lon); tAlt.add(b.getInt(12) / 1000.0); tSpd.add(lastSpd)
                    }
                }
                TlogParser.ATTITUDE -> {
                    put(Ch.ROLL, t, b.getFloat(4) * 180 / PI)
                    put(Ch.PITCH, t, b.getFloat(8) * 180 / PI)
                    put(Ch.YAW, t, ((b.getFloat(12) * 180 / PI) + 360) % 360)
                }
                TlogParser.VIBRATION -> {
                    put(Ch.VIBE_X, t, b.getFloat(8).toDouble())
                    put(Ch.VIBE_Y, t, b.getFloat(12).toDouble())
                    put(Ch.VIBE_Z, t, b.getFloat(16).toDouble())
                    put(Ch.CLIP, t, (b.getInt(20).toLong() and 0xFFFFFFFFL).toDouble())
                }
                TlogParser.STATUSTEXT -> {
                    val sev = b.get(0).toInt() and 0xFF
                    val text = cstr(b, 1, 50)
                    if (firmware.isEmpty() && ArduPilot.vehicleTypeFrom(text) != null && text.contains(" V")) firmware = text.substringBefore(" (")
                    if (text.startsWith("Frame:")) frame = text.removePrefix("Frame:").trim()
                    events += LogEvent(t, if (sev <= 4) EventKind.WARN else EventKind.INFO, text, SEVERITY.getOrElse(sev) { "" }, "MSG")
                }
                TlogParser.PARAM_VALUE -> {
                    val name = cstr(b, 8, 16)
                    if (name.isNotEmpty()) params[name] = Param(name, b.getFloat(0), null)
                }
            }
            true
        }

        val meta = mapOf(
            Ch.ALT to Triple("Высота", "GLOBAL_POSITION_INT.relative_alt", "м"),
            Ch.SPD to Triple("Скорость", "GPS_RAW_INT.vel", "м/с"),
            Ch.VOLT to Triple("Напряжение", "SYS_STATUS.voltage_battery", "В"),
            Ch.CURR to Triple("Ток", "SYS_STATUS.current_battery", "А"),
            Ch.MAH to Triple("Израсходовано", "BATTERY_STATUS.current_consumed", "мА·ч"),
            Ch.REM to Triple("Заряд", "SYS_STATUS.battery_remaining", "%"),
            Ch.VIBE_X to Triple("Вибрации X", "VIBRATION.vibration_x", "м/с²"),
            Ch.VIBE_Y to Triple("Вибрации Y", "VIBRATION.vibration_y", "м/с²"),
            Ch.VIBE_Z to Triple("Вибрации Z", "VIBRATION.vibration_z", "м/с²"),
            Ch.CLIP to Triple("Клиппинг IMU0", "VIBRATION.clipping_0", ""),
            Ch.SATS to Triple("Спутники", "GPS_RAW_INT.satellites_visible", ""),
            Ch.HDOP to Triple("HDOP", "GPS_RAW_INT.eph", ""),
            Ch.ROLL to Triple("Крен", "ATTITUDE.roll", "°"),
            Ch.PITCH to Triple("Тангаж", "ATTITUDE.pitch", "°"),
            Ch.YAW to Triple("Курс", "ATTITUDE.yaw", "°"),
            Ch.CLIMB to Triple("Верт. скорость", "GLOBAL_POSITION_INT.vz", "м/с"),
        )
        val series = LinkedHashMap<String, Series>()
        for ((key, m) in meta) {
            val c = cols[key] ?: continue
            series[key] = Series(key, m.first, m.second, m.third, c.first.toFloatArray(), c.second.toFloatArray())
        }
        val duration = ((lastUs - t0) / 1e6).toFloat()
        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = LogFormat.TLOG,
            vehicle = VehicleInfo(
                autopilot = when (autopilotId) { 3 -> "ArduPilot"; 12 -> "PX4"; else -> "MAVLink" },
                vehicleType = vehicleType, firmware = firmware, frame = frame,
            ),
            startUtcMillis = startUtc ?: (t0 / 1000),
            duration = duration, series = series,
            track = Track(tT.toFloatArray(), tLat.toDoubleArray(), tLon.toDoubleArray(), tAlt.toFloatArray(), tSpd.toFloatArray()),
            events = events.sortedBy { it.time }, modes = modes, params = params.values.sortedBy { it.name },
            armTime = armT, disarmTime = disarmT, messageCount = ownCount,
        )
    }

    private val SEVERITY = listOf("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFO", "DEBUG")

    private fun cstr(b: java.nio.ByteBuffer, at: Int, len: Int): String {
        var end = 0
        while (end < len && at + end < b.capacity() && b.get(at + end) != 0.toByte()) end++
        val arr = ByteArray(end) { b.get(at + it) }
        return String(arr, Charsets.UTF_8).trim()
    }
}
