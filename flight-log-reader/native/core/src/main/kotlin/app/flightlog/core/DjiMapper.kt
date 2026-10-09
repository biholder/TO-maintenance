package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.hypot

/** Преобразует записи DJI FlightRecord в модель полёта. */
object DjiMapper {
    /** Ключи AES не переданы, а лог v13+ зашифрован — нужен запрос к DJI. */
    class KeychainsRequired(val requestJson: String, val version: Int) :
        LogParseException("Лог DJI версии $version зашифрован: нужны ключи DJI (API-ключ DJI Open API)")

    /** Ключи из ответа DJI (или сохранённые рядом с логом): цепочки feature point → (iv, key). */
    fun parseKeychains(json: String): List<Map<Int, Pair<ByteArray, ByteArray>>> {
        val root = MiniJson.parse(json)
        val arr = (root as? Map<*, *>)?.get("data") ?: root
        return (arr as List<*>).map { chain ->
            (chain as List<*>).mapNotNull { e ->
                val m = e as Map<*, *>
                val fp = featureId(m["featurePoint"]) ?: return@mapNotNull null
                val iv = b64(m["aesIv"]) ?: return@mapNotNull null
                val key = b64(m["aesKey"]) ?: return@mapNotNull null
                fp to (iv to key)
            }.toMap()
        }
    }

    /** «FR_Standardization_Feature_Base_1», «…base_1» или число 1 → 1. */
    private fun featureId(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> DjiLog.FEATURE_IDS[v] ?: DjiLog.FEATURE_IDS.entries.firstOrNull { it.key.equals(v, true) }?.value
            ?: Regex("""_(\d+)$""").find(v)?.groupValues?.get(1)?.toInt() ?: v.toIntOrNull()
        else -> null
    }

    private fun b64(v: Any?): ByteArray? {
        val s = (v as? String)?.trim() ?: return null
        return runCatching { Base64.getMimeDecoder().decode(s) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
    }

    private val MODES = mapOf(
        0 to "Manual", 1 to "ATTI", 2 to "ATTI Course Lock", 3 to "ATTI Hover", 4 to "Hover", 5 to "GPS Brake",
        6 to "P-GPS", 7 to "Course Lock", 8 to "Home Lock", 9 to "Point of Interest", 10 to "Assisted Takeoff",
        11 to "Auto Takeoff", 12 to "Auto Landing", 13 to "ATTI Landing", 14 to "Waypoint", 15 to "Go Home",
        16 to "Click Go", 17 to "Virtual Stick", 18 to "Wristband", 19 to "Cine", 23 to "ATTI Limited", 24 to "Draw",
        25 to "Follow Me", 26 to "ActiveTrack", 27 to "TapFly", 28 to "Pano", 29 to "Farming", 30 to "FPV",
        31 to "Sport", 32 to "Novice", 33 to "Confirm Landing", 35 to "Terrain Follow", 36 to "Advanced Go Home",
        37 to "Advanced Landing", 38 to "Tripod", 39 to "Track Headlock", 41 to "Engine Start", 43 to "Gentle",
    )

    private val ACTIONS = mapOf(
        1 to "Возврат: предупреждение о заряде", 2 to "Посадка: предупреждение о заряде",
        3 to "Умный возврат по заряду", 4 to "Умная посадка по заряду", 5 to "Посадка: низкое напряжение",
        6 to "Возврат: низкое напряжение", 7 to "Посадка: критически низкое напряжение",
        8 to "Возврат по кнопке пульта", 11 to "Автопосадка с пульта", 12 to "Возврат из приложения",
        13 to "Автопосадка из приложения", 15 to "Возврат: потеря связи", 19 to "Посадка: препятствие снизу",
        28 to "Принудительная посадка по батарее", 29 to "Защитный возврат", 30 to "Посадка: блокировка мотора",
        32 to "Посадка: неоригинальная батарея", 33 to "Посадка: препятствие на пути домой", 34 to "Возврат: ошибка IMU",
    )

    /** Флаги OSD → (заголовок, пояснение, критично). */
    private val FLAGS = listOf(
        Triple(35, 0x40, Triple("Сильные вибрации", "Контроллер DJI зафиксировал вибрации. Проверьте пропеллеры и их крепление.", false)),
        Triple(35, 0x80, Triple("Акселерометр вне диапазона", "Перегрузка датчика — удар, рывок или сильные вибрации.", true)),
        Triple(35, 0x08, Triple("Блокировка мотора", "Мотор не набирал обороты. Проверьте моторы и пропеллеры.", true)),
        Triple(35, 0x10, Triple("Недостаточно тяги", "Тяги не хватало — перегруз, ветер или износ моторов.", true)),
        Triple(35, 0x20, Triple("Отказ барометра в полёте", "Высота могла определяться неверно.", true)),
        Triple(34, 0x01, Triple("Ошибка компаса", "Откалибруйте компас вдали от металла и источников помех.", false)),
    )

    fun map(fileName: String, fileSize: Long, log: DjiLog, keychainsJson: String?, progress: ProgressListener? = null): FlightLog {
        if (log.version >= 13 && keychainsJson == null) throw KeychainsRequired(log.keychainsRequestJson(), log.version)
        val keychains = keychainsJson?.let { parseKeychains(it) }

        val cols = HashMap<String, Pair<DoubleList, DoubleList>>()
        fun put(key: String, t: Float, v: Double) {
            val c = cols.getOrPut(key) { DoubleList() to DoubleList() }
            c.first.add(t.toDouble()); c.second.add(v)
        }
        val tT = DoubleList(); val tLat = DoubleList(); val tLon = DoubleList(); val tAlt = DoubleList(); val tSpd = DoubleList()
        val events = ArrayList<LogEvent>()
        val modes = ArrayList<ModeChange>()
        var firstTs: Long? = null
        var lastTs: Long? = null
        var osdCount = 0
        var osdTotal = 0
        var t = 0f
        var lastMode = -1
        var lastAction = 0
        var motor = false
        var armT: Float? = null
        var disarmT: Float? = null
        var homeAlt = log.details.takeOffAltitude.takeIf { it.isFinite() } ?: 0f
        var firstRemaining: Int? = null
        var batteryIndex: Int? = null
        val flagState = BooleanArray(FLAGS.size)
        var records = 0L

        log.records(keychains) records@{ r ->
            records++
            val d = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
            when (r.type) {
                DjiLog.CUSTOM -> if (r.data.size >= 18) {
                    val ts = d.getLong(10)
                    if (ts in 1_262_304_000_000L..4_102_444_800_000L) { // 2010…2100
                        if (firstTs == null) firstTs = ts
                        lastTs = maxOf(ts, lastTs ?: ts)
                    }
                }
                DjiLog.OSD -> {
                    osdTotal++
                    if (!DjiLog.plausibleOsd(r.data)) return@records
                    t = if (firstTs != null) ((lastTs!! - firstTs!!) / 1000f).coerceAtLeast(t) else osdCount / 10f
                    osdCount++
                    val lon = Math.toDegrees(d.getDouble(0))
                    val lat = Math.toDegrees(d.getDouble(8))
                    val height = d.getShort(16) / 10.0
                    val vx = d.getShort(18) / 10.0
                    val vy = d.getShort(20) / 10.0
                    val vz = d.getShort(22) / 10.0
                    val spd = hypot(vx, vy)
                    put(Ch.ALT, t, height)
                    put(Ch.SPD, t, spd)
                    put(Ch.CLIMB, t, -vz) // NED: ось Z вниз
                    put(Ch.PITCH, t, d.getShort(24) / 10.0)
                    put(Ch.ROLL, t, d.getShort(26) / 10.0)
                    put(Ch.YAW, t, ((d.getShort(28) / 10.0) + 360) % 360)
                    put(Ch.SATS, t, (d.get(36).toInt() and 0xFF).toDouble())
                    put(Ch.REM, t, (d.get(40).toInt() and 0xFF).toDouble())
                    if ((lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        tT.add(t.toDouble()); tLat.add(lat); tLon.add(lon); tAlt.add(homeAlt + height); tSpd.add(spd)
                    }
                    val mode = d.get(30).toInt() and 0x7F
                    if (mode != lastMode) {
                        lastMode = mode
                        val name = MODES[mode] ?: "Mode $mode"
                        modes += ModeChange(t, name)
                        events += LogEvent(t, EventKind.MODE, "Режим $name", "", "OSD")
                    }
                    val m = (d.get(32).toInt() and 0x08) != 0
                    if (m != motor) {
                        motor = m
                        if (m) { if (armT == null) armT = t; disarmT = null } else disarmT = t
                        events += LogEvent(t, EventKind.INFO, if (m) "Моторы запущены" else "Моторы остановлены", "", "OSD")
                    }
                    val action = d.get(37).toInt() and 0xFF
                    if (action != lastAction) {
                        lastAction = action
                        if (action != 0) {
                            val title = ACTIONS[action] ?: "Действие контроллера $action"
                            val warn = action in setOf(1, 2, 3, 4, 5, 6, 7, 15, 19, 28, 29, 30, 32, 33, 34)
                            events += LogEvent(t, if (warn) EventKind.WARN else EventKind.INFO, title, "flight action $action", "OSD-ACTION")
                        }
                    }
                    FLAGS.forEachIndexed { i, (byte, mask, info) ->
                        val on = (d.get(byte).toInt() and mask) != 0
                        if (on && !flagState[i]) {
                            events += LogEvent(t, EventKind.WARN, info.first, info.second, if (info.third) "OSD-CRIT" else "OSD")
                        }
                        flagState[i] = on
                    }
                    if (progress != null && osdCount % 2000 == 0) progress.onProgress(0, 0.5f)
                }
                DjiLog.HOME -> if (r.data.size >= 20) {
                    val a = d.getFloat(16) / 10f
                    if (a.isFinite() && a != 0f) homeAlt = a
                }
                DjiLog.CENTER_BATTERY -> if (r.data.size >= 16) {
                    val v = (d.getShort(1).toInt() and 0xFFFF) / 1000.0
                    val remaining = d.getShort(3).toInt() and 0xFFFF
                    if (v > 0) put(Ch.VOLT, t, v)
                    put(Ch.CURR, t, kotlin.math.abs(d.getShort(14) / 1000.0))
                    if (firstRemaining == null && remaining > 0) firstRemaining = remaining
                    firstRemaining?.let { put(Ch.MAH, t, (it - remaining).coerceAtLeast(0).toDouble()) }
                }
                DjiLog.SMART_BATTERY -> if (r.data.size >= 27 && Ch.VOLT !in cols) {
                    val v = (d.getShort(24).toInt() and 0xFFFF) / 1000.0
                    if (v > 0) put("smartVolt", t, v)
                }
                DjiLog.SMART_BATTERY_GROUP -> if (r.data.size >= 31 && r.data[0].toInt() == 2) {
                    val idx = r.data[1].toInt() and 0xFF
                    if (batteryIndex == null) batteryIndex = idx
                    if (idx == batteryIndex) {
                        put(Ch.VOLT, t, d.getInt(2) / 1000.0)
                        put(Ch.CURR, t, kotlin.math.abs(d.getInt(6) / 1000.0))
                        val full = d.getInt(10).toLong() and 0xFFFFFFFFL
                        val rem = d.getInt(14).toLong() and 0xFFFFFFFFL
                        if (firstRemaining == null && rem > 0) firstRemaining = rem.toInt()
                        firstRemaining?.let { put(Ch.MAH, t, (it - rem).coerceAtLeast(0).toDouble()) }
                    }
                }
                DjiLog.APP_TIP, DjiLog.APP_WARN, DjiLog.APP_SERIOUS_WARN -> {
                    val text = cstr(r.data)
                    if (text.isNotEmpty()) events += LogEvent(
                        t, if (r.type == DjiLog.APP_TIP) EventKind.INFO else EventKind.WARN, text, "",
                        when (r.type) { DjiLog.APP_TIP -> "TIP"; DjiLog.APP_WARN -> "WARN"; else -> "SERIOUS" },
                    )
                }
            }
        }
        progress?.onProgress(0, 1f)
        // Неверные ключи AES: большинство записей не расшифровывается (редкие «успехи» — мусор).
        val aesBad = log.version >= 13 && log.aesTotal > 0 && log.aesFailed * 2 > log.aesTotal
        if (aesBad || osdCount == 0 || osdCount < osdTotal / 2) throw LogParseException(
            if (log.version >= 13) "Не удалось расшифровать записи DJI: ключи не подходят к этому логу. " +
                "Расшифровано ${log.aesTotal - log.aesFailed} из ${log.aesTotal} записей, OSD: $osdCount из $osdTotal, " +
                "цепочка IV: ${log.ivMode ?: "—"}, доп. типы: ${log.learnedFeatures.entries.joinToString { "${it.key}→${it.value}" }.ifEmpty { "—" }}. " +
                "${log.ivReport}. ${log.diagnostics()}"
            else "В логе DJI нет записей OSD",
        )
        // SmartBattery — только если других источников напряжения нет.
        cols.remove("smartVolt")?.let { if (Ch.VOLT !in cols) cols[Ch.VOLT] = it }

        val meta = mapOf(
            Ch.ALT to Triple("Высота", "OSD.height", "м"),
            Ch.SPD to Triple("Скорость", "OSD.hSpeed", "м/с"),
            Ch.CLIMB to Triple("Верт. скорость", "OSD.zSpeed", "м/с"),
            Ch.PITCH to Triple("Тангаж", "OSD.pitch", "°"),
            Ch.ROLL to Triple("Крен", "OSD.roll", "°"),
            Ch.YAW to Triple("Курс", "OSD.yaw", "°"),
            Ch.SATS to Triple("Спутники", "OSD.gpsNum", ""),
            Ch.REM to Triple("Заряд", "OSD.battery", "%"),
            Ch.VOLT to Triple("Напряжение", "Battery.voltage", "В"),
            Ch.CURR to Triple("Ток", "Battery.current", "А"),
            Ch.MAH to Triple("Израсходовано", "Battery.used", "мА·ч"),
        )
        val series = LinkedHashMap<String, Series>()
        for ((key, mm) in meta) {
            val c = cols[key] ?: continue
            series[key] = Series(key, mm.first, mm.second, mm.third, c.first.toFloatArray(), c.second.toFloatArray())
        }
        val det = log.details
        val start = firstTs ?: det.startTimeMs.takeIf { it > 1_262_304_000_000L }
        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = LogFormat.DJI,
            vehicle = VehicleInfo(
                autopilot = "DJI", vehicleType = "Copter",
                firmware = listOf("FlightRecord v${log.version}", "приложение ${det.appVersion}",
                    det.aircraftSn.takeIf { it.isNotEmpty() }?.let { "SN $it" }).filterNotNull().joinToString(" · "),
                board = det.aircraftName.ifEmpty { det.productName },
                frame = det.productName,
            ),
            startUtcMillis = start, duration = t, series = series,
            track = Track(tT.toFloatArray(), tLat.toDoubleArray(), tLon.toDoubleArray(), tAlt.toFloatArray(), tSpd.toFloatArray()),
            events = events.sortedBy { it.time }, modes = modes, params = emptyList(),
            armTime = armT, disarmTime = disarmT, messageCount = records,
        )
    }

    private fun cstr(b: ByteArray): String {
        var e = 0
        while (e < b.size && b[e] != 0.toByte()) e++
        return String(b, 0, e, Charsets.UTF_8).trim()
    }
}
