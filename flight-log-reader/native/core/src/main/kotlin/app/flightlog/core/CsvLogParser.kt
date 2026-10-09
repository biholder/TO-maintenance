package app.flightlog.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Разбор полётного лога в CSV. Единого формата нет, поэтому столбцы
 * распознаются по названиям (см. [ALIASES]), а единицы — по подписи в скобках:
 * «height_above_takeoff(feet)», «speed [km/h]», «BAT.Volt (В)».
 *
 * Поддерживаются: экспорт PLOV, AirData / PhantomHelp / Litchi (DJI),
 * таблицы в стиле ArduPilot (GPS.Spd, BAT.Volt…) и произвольные таблицы.
 * Нераспознанные числовые столбцы становятся отдельными каналами «csv:…».
 */
class CsvLogParser(private val maxRateHz: Double = DataFlashParser.DEFAULT_RATE_HZ) {

    private enum class Role { TIME, DATETIME, LAT, LON, ALT, SPD, VOLT, CURR, MAH, REM, SATS, HDOP, ROLL, PITCH, YAW, CLIMB, VIBE_X, VIBE_Y, VIBE_Z, CLIP, MODE, MESSAGE, ARMED }

    private class Column(val index: Int, val header: String, val name: String, val unit: String) {
        var role: Role? = null
        var scale = 1.0
        var offset = 0.0
        var numeric = 0
        var text = 0
        val values = DoubleList()
    }

    fun parse(input: InputStream, fileName: String, fileSize: Long, progress: ProgressListener? = null): FlightLog {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
        var headerLine = reader.readLine() ?: throw LogParseException("Пустой CSV-файл")
        headerLine = headerLine.removePrefix("﻿")
        // Некоторые выгрузки начинают с «sep=;».
        var delimiter: Char
        if (headerLine.startsWith("sep=")) {
            delimiter = headerLine.getOrElse(4) { ',' }
            headerLine = reader.readLine() ?: throw LogParseException("Пустой CSV-файл")
        } else delimiter = detectDelimiter(headerLine)
        val headers = split(headerLine, delimiter)
        if (headers.size < 2) throw LogParseException("Не удалось определить столбцы CSV")
        val cols = headers.mapIndexed { i, h -> Column(i, h.trim(), normalize(h), unitOf(h)) }
        assignRoles(cols)
        val decimalComma = delimiter == ';' || delimiter == '\t'

        val timeCol = cols.firstOrNull { it.role == Role.TIME }
        val dateCol = cols.firstOrNull { it.role == Role.DATETIME }
        val modeCol = cols.firstOrNull { it.role == Role.MODE }
        val msgCol = cols.firstOrNull { it.role == Role.MESSAGE }
        val armedCol = cols.firstOrNull { it.role == Role.ARMED }

        val times = DoubleList()
        val modes = ArrayList<ModeChange>()
        val events = ArrayList<LogEvent>()
        var firstEpochMs: Long? = null
        var t0: Double? = null
        var lastKept: Double? = null
        var lastMode: String? = null
        var armT: Float? = null
        var disarmT: Float? = null
        var row = 0L
        var bytes = headerLine.length + 1L
        var lastReport = 0L
        val minGap = if (maxRateHz > 0) 0.8 / maxRateHz else 0.0

        while (true) {
            val line = reader.readLine() ?: break
            bytes += line.length + 1
            if (line.isBlank()) continue
            val f = split(line, delimiter)
            row++
            // Время строки, с.
            var tAbs: Double? = null
            if (timeCol != null) {
                val raw = parseNum(f.getOrNull(timeCol.index), decimalComma)
                // Метка без единиц: по величине отличаем эпоху в мкс / мс от секунд.
                if (raw != null && row == 1L && timeCol.unit.isEmpty() && timeCol.scale == 1.0) {
                    timeCol.scale = when { raw > 1e14 -> 1e-6; raw > 1e11 -> 1e-3; else -> 1.0 }
                }
                tAbs = raw?.times(timeCol.scale)
            }
            if (dateCol != null) {
                val ms = parseDate(f.getOrNull(dateCol.index))
                if (ms != null) {
                    if (firstEpochMs == null) firstEpochMs = ms
                    if (tAbs == null) tAbs = ms / 1000.0
                }
            }
            if (tAbs == null) tAbs = (row - 1) / 10.0 // без столбца времени считаем 10 Гц
            if (t0 == null) t0 = tAbs
            val t = tAbs - t0
            if (t < 0) continue

            // События — без прореживания.
            val tf = t.toFloat()
            modeCol?.let { c ->
                val m = f.getOrNull(c.index)?.trim().orEmpty()
                if (m.isNotEmpty() && m != lastMode) {
                    lastMode = m
                    modes += ModeChange(tf, m)
                    events += LogEvent(tf, EventKind.MODE, "Режим $m", "", "CSV")
                }
            }
            msgCol?.let { c ->
                val m = f.getOrNull(c.index)?.trim().orEmpty()
                if (m.isNotEmpty() && events.lastOrNull()?.title != m) {
                    val warn = WARN_WORDS.any { m.contains(it, ignoreCase = true) }
                    events += LogEvent(tf, if (warn) EventKind.WARN else EventKind.INFO, m, "", "CSV")
                }
            }
            armedCol?.let { c ->
                val v = f.getOrNull(c.index)?.trim()?.lowercase()
                val on = v == "1" || v == "true" || v == "yes" || v == "armed"
                if (on) { if (armT == null) armT = tf; disarmT = null } else if (armT != null && disarmT == null) disarmT = tf
            }

            if (lastKept != null && t >= lastKept && t - lastKept < minGap) continue
            lastKept = t
            times.add(t)
            for (c in cols) {
                if (c.role == Role.TIME || c.role == Role.DATETIME || c.role == Role.MODE || c.role == Role.MESSAGE) continue
                val raw = f.getOrNull(c.index)
                val v = parseNum(raw, decimalComma)
                if (v != null) { c.numeric++; c.values.add(v * c.scale + c.offset) }
                else { if (!raw.isNullOrBlank()) c.text++; c.values.add(Double.NaN) }
            }
            if (progress != null && bytes - lastReport > 1 shl 20) {
                lastReport = bytes
                progress.onProgress(0, if (fileSize > 0) (bytes.toFloat() / fileSize).coerceAtMost(1f) else 0f)
            }
        }
        progress?.onProgress(0, 1f)
        if (times.size == 0) throw LogParseException("В CSV нет строк с данными")

        val time = times.toFloatArray()
        val series = LinkedHashMap<String, Series>()
        fun add(key: String, label: String, c: Column, unit: String) {
            val (tt, vv) = compact(time, c.values)
            if (tt.isNotEmpty()) series[key] = Series(key, label, c.header, unit, tt, vv)
        }
        for ((role, meta) in ROLE_SERIES) {
            val c = cols.firstOrNull { it.role == role } ?: continue
            add(meta.first, meta.second, c, meta.third)
        }
        // Остальные числовые столбцы — дополнительные каналы.
        var extra = 0
        for (c in cols) {
            if (c.role != null || c.numeric == 0 || c.text > c.numeric) continue
            if (extra >= MAX_EXTRA) break
            val (tt, vv) = compact(time, c.values)
            if (tt.size < 2 || vv.min() == vv.max()) continue
            series["csv:${c.header}"] = Series("csv:${c.header}", c.header.substringBefore('(').substringBefore('[').trim(), c.header, c.unit, tt, vv)
            extra++
        }

        val track = buildTrack(time, cols.firstOrNull { it.role == Role.LAT }, cols.firstOrNull { it.role == Role.LON },
            cols.firstOrNull { it.role == Role.ALT }, cols.firstOrNull { it.role == Role.SPD })
        val names = cols.map { it.name }
        val autopilot = when {
            names.any { it.startsWith("osd.") || it == "flycstate" || it.startsWith("flycstate") || it == "gimbal_heading" } -> "DJI"
            names.any { it.startsWith("ctun.") || it.startsWith("bat.") || it.startsWith("global_position_int") } -> "ArduPilot"
            else -> "CSV"
        }
        val startUtc = firstEpochMs ?: timeCol?.let { c -> t0?.takeIf { c.name.contains("unix") || it > 1e9 }?.let { (it * 1000).toLong() } }
        return FlightLog(
            fileName = fileName, fileSize = fileSize, format = LogFormat.CSV,
            vehicle = VehicleInfo(autopilot = autopilot, vehicleType = if (autopilot == "DJI") "Copter" else ""),
            startUtcMillis = startUtc, duration = time.last(), series = series, track = track,
            events = events.sortedBy { it.time }, modes = modes, params = emptyList(),
            armTime = armT, disarmTime = disarmT, messageCount = row,
        )
    }

    /** Убирает пустые значения (NaN) из ряда. */
    private fun compact(time: FloatArray, values: DoubleList): Pair<FloatArray, FloatArray> {
        var n = 0
        for (i in 0 until values.size) if (!values[i].isNaN()) n++
        val t = FloatArray(n); val v = FloatArray(n)
        var j = 0
        for (i in 0 until values.size) if (!values[i].isNaN()) { t[j] = time[i]; v[j] = values[i].toFloat(); j++ }
        return t to v
    }

    private fun buildTrack(time: FloatArray, lat: Column?, lon: Column?, alt: Column?, spd: Column?): Track {
        if (lat == null || lon == null) return Track(FloatArray(0), DoubleArray(0), DoubleArray(0), FloatArray(0), FloatArray(0))
        val idx = (0 until lat.values.size).filter {
            val a = lat.values[it]; val b = lon.values[it]
            !a.isNaN() && !b.isNaN() && !(a == 0.0 && b == 0.0) && a in -90.0..90.0 && b in -180.0..180.0
        }
        return Track(
            FloatArray(idx.size) { time[idx[it]] },
            DoubleArray(idx.size) { lat.values[idx[it]] },
            DoubleArray(idx.size) { lon.values[idx[it]] },
            FloatArray(idx.size) { alt?.values?.get(idx[it])?.takeIf { v -> !v.isNaN() }?.toFloat() ?: 0f },
            FloatArray(idx.size) { spd?.values?.get(idx[it])?.takeIf { v -> !v.isNaN() }?.toFloat() ?: 0f },
        )
    }

    private fun assignRoles(cols: List<Column>) {
        for ((role, names) in ALIASES) {
            // Порядок псевдонимов задаёт приоритет: height_above_takeoff важнее altitude.
            val c = names.firstNotNullOfOrNull { n -> cols.firstOrNull { it.role == null && it.name == n } } ?: continue
            c.role = role
            applyUnits(c)
        }
    }

    private fun applyUnits(c: Column) {
        val u = c.unit.lowercase(Locale.ROOT).replace(" ", "")
        when (c.role) {
            Role.TIME -> c.scale = when {
                c.name == "timeus" || c.name.endsWith("_us") || u == "us" || u == "мкс" || u == "microseconds" -> 1e-6
                c.name == "time_boot_ms" || c.name.endsWith("_ms") || u == "ms" || u == "мс" || u.startsWith("millisecond") -> 1e-3
                else -> 1.0
            }
            Role.ALT -> c.scale = lengthScale(u, c.name)
            Role.SPD, Role.CLIMB -> c.scale = when {
                u == "mph" -> 0.44704
                u in setOf("km/h", "kmh", "kph", "км/ч") -> 1 / 3.6
                u in setOf("knots", "kn", "kt", "уз") -> 0.514444
                u in setOf("cm/s", "см/с") || c.name == "vel" -> 0.01
                u == "ft/s" -> 0.3048
                else -> 1.0
            }
            Role.VOLT -> c.scale = if (u == "mv" || u == "мв" || c.name == "voltage_battery") 0.001 else 1.0
            Role.CURR -> c.scale = when {
                u == "ma" || u == "ма" -> 0.001
                u == "ca" || c.name == "current_battery" -> 0.01
                else -> 1.0
            }
            Role.ROLL, Role.PITCH, Role.YAW -> c.scale = if (u == "rad" || u == "радианы") 180 / Math.PI else 1.0
            else -> Unit
        }
        if (c.role == Role.CLIMB && c.name in setOf("vz", "osd.zspeed", "zspeed")) c.scale = -c.scale // NED: вниз — плюс
    }

    private fun lengthScale(u: String, name: String) = when {
        u in setOf("ft", "feet", "фут") -> 0.3048
        u in setOf("mm", "мм") || name == "relative_alt" -> 0.001
        u in setOf("cm", "см") -> 0.01
        else -> 1.0
    }

    companion object {
        private const val MAX_EXTRA = 40

        private val ROLE_SERIES: List<Pair<Role, Triple<String, String, String>>> = listOf(
            Role.ALT to Triple(Ch.ALT, "Высота", "м"),
            Role.SPD to Triple(Ch.SPD, "Скорость", "м/с"),
            Role.VOLT to Triple(Ch.VOLT, "Напряжение", "В"),
            Role.CURR to Triple(Ch.CURR, "Ток", "А"),
            Role.MAH to Triple(Ch.MAH, "Израсходовано", "мА·ч"),
            Role.REM to Triple(Ch.REM, "Заряд", "%"),
            Role.VIBE_X to Triple(Ch.VIBE_X, "Вибрации X", "м/с²"),
            Role.VIBE_Y to Triple(Ch.VIBE_Y, "Вибрации Y", "м/с²"),
            Role.VIBE_Z to Triple(Ch.VIBE_Z, "Вибрации Z", "м/с²"),
            Role.CLIP to Triple(Ch.CLIP, "Клиппинг IMU0", ""),
            Role.SATS to Triple(Ch.SATS, "Спутники", ""),
            Role.HDOP to Triple(Ch.HDOP, "HDOP", ""),
            Role.ROLL to Triple(Ch.ROLL, "Крен", "°"),
            Role.PITCH to Triple(Ch.PITCH, "Тангаж", "°"),
            Role.YAW to Triple(Ch.YAW, "Курс", "°"),
            Role.CLIMB to Triple(Ch.CLIMB, "Верт. скорость", "м/с"),
        )

        /** Нормализованные имена столбцов для каждой роли, по приоритету. */
        private val ALIASES: List<Pair<Role, List<String>>> = listOf(
            Role.TIME to listOf("time_s", "time", "timeus", "time(s)", "offsettime", "time_boot_ms", "timestamp", "elapsed_time", "t",
                "время", "время_с", "time_ms", "flytime"),
            Role.DATETIME to listOf("datetime", "datetime(utc)", "datetime_utc", "custom.updatetime", "date_time", "gmt_time", "utc_time", "дата_время"),
            Role.LAT to listOf("lat", "latitude", "osd.latitude", "gps.lat", "gps_lat", "широта"),
            Role.LON to listOf("lon", "lng", "longitude", "osd.longitude", "gps.lng", "gps.lon", "gps_lon", "долгота"),
            Role.ALT to listOf("ctun.alt", "height_above_takeoff", "osd.height", "relative_alt", "global_position_int.relative_alt",
                "rel_alt", "alt_rel", "height", "altitude", "alt", "высота"),
            Role.SPD to listOf("gps.spd", "speed", "groundspeed", "ground_speed", "osd.hspeed", "gps_raw_int.vel", "hspeed", "vel", "скорость"),
            Role.VOLT to listOf("bat.volt", "voltage", "volt", "battery_voltage", "battery.voltage", "sys_status.voltage_battery",
                "voltage_battery", "напряжение"),
            Role.CURR to listOf("bat.curr", "current", "curr", "battery.current", "sys_status.current_battery", "current_battery", "ток"),
            Role.MAH to listOf("bat.currtot", "currtot", "mah", "consumed", "current_consumed", "battery.usedcapacity",
                "battery_status.current_consumed"),
            Role.REM to listOf("bat.rempct", "battery_percent", "battery", "rempct", "battery.chargelevel", "battery_remaining",
                "sys_status.battery_remaining", "заряд"),
            Role.SATS to listOf("gps.nsats", "satellites", "nsats", "osd.gpsnum", "satellites_visible", "gps_raw_int.satellites_visible", "спутники"),
            Role.HDOP to listOf("gps.hdop", "hdop", "gps_raw_int.eph"),
            Role.ROLL to listOf("att.roll", "roll", "osd.roll", "attitude.roll"),
            Role.PITCH to listOf("att.pitch", "pitch", "osd.pitch", "attitude.pitch"),
            Role.YAW to listOf("att.yaw", "yaw", "compass_heading", "heading", "osd.yaw", "attitude.yaw", "курс"),
            Role.CLIMB to listOf("ctun.crt", "climb", "vertical_speed", "vspeed", "osd.zspeed", "zspeed", "vz", "vfr_hud.climb"),
            Role.VIBE_X to listOf("vibe.vibex", "vibex", "vibration_x", "vibration.vibration_x"),
            Role.VIBE_Y to listOf("vibe.vibey", "vibey", "vibration_y", "vibration.vibration_y"),
            Role.VIBE_Z to listOf("vibe.vibez", "vibez", "vibration_z", "vibration.vibration_z"),
            Role.CLIP to listOf("vibe.clip", "clip", "clipping_0", "vibration.clipping_0"),
            Role.MODE to listOf("mode", "flightmode", "flight_mode", "flycstate", "osd.flycstate", "режим"),
            Role.MESSAGE to listOf("message", "msg", "text", "app.tip", "app.warning", "сообщение"),
            Role.ARMED to listOf("armed", "isarmed", "is_armed", "osd.ismotoron", "ismotoron", "motor_on"),
        )

        private val WARN_WORDS = listOf("fail", "error", "warn", "low", "lost", "critical", "glitch", "ошибк", "предупр", "низк")

        /** «Height Above Takeoff (feet)» → «height_above_takeoff»; «CTUN.Alt (м)» → «ctun.alt». */
        internal fun normalize(h: String): String = h.trim().trim('"')
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]\s*$"""), "")
            .trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), "_")

        internal fun unitOf(h: String): String =
            Regex("""[(\[]([^)\]]*)[)\]]\s*$""").find(h.trim().trim('"'))?.groupValues?.get(1)?.trim() ?: ""

        internal fun detectDelimiter(header: String): Char =
            listOf(',', ';', '\t', '|').maxByOrNull { d -> header.count { it == d } } ?: ','

        /** Разбивка строки CSV с учётом кавычек. */
        internal fun split(line: String, d: Char): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var q = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> q = !q
                    c == d && !q -> { out += sb.toString(); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }

        internal fun parseNum(s: String?, decimalComma: Boolean): Double? {
            if (s == null) return null
            var t = s.trim()
            if (t.isEmpty()) return null
            if (decimalComma) t = t.replace(',', '.')
            t.toDoubleOrNull()?.let { return it }
            return when (t.lowercase(Locale.ROOT)) {
                "true", "yes" -> 1.0
                "false", "no" -> 0.0
                else -> null
            }
        }

        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss.SSS", "yyyy/MM/dd HH:mm:ss",
            "dd.MM.yyyy HH:mm:ss", "M/d/yyyy h:mm:ss.SS a", "M/d/yyyy h:mm:ss a", "M/d/yyyy HH:mm:ss",
        ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

        /** Дата-время → мс UTC; без зоны считается UTC. */
        internal fun parseDate(s: String?): Long? {
            val t = s?.trim()?.trim('"') ?: return null
            if (t.isEmpty()) return null
            runCatching { return Instant.parse(t).toEpochMilli() }
            runCatching { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }
            for (f in DATE_FORMATS) runCatching { return LocalDateTime.parse(t, f).toInstant(ZoneOffset.UTC).toEpochMilli() }
            return null
        }
    }
}
