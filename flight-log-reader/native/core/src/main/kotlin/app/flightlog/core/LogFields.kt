package app.flightlog.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Любые числовые поля журнала ArduPilot (.bin и текстовый .log), а не только каналы,
 * которые PLOV разбирает для анализа: список сообщений и полей и чтение выбранных полей.
 *
 * Ключ поля: `df:MSG.Field` или `df:MSG[N].Field` для сообщений с номером экземпляра
 * (BAT[1].Volt, GPS[0].Spd, XKF4[1].SV).
 */
object LogFields {
    const val PREFIX = "df:"

    /** Сообщение журнала: экземпляры (пусто — один) и числовые поля. */
    class Message(val name: String, val instances: List<Int>, val fields: List<String>)

    /** Поле разобранного ключа. */
    data class Ref(val message: String, val instance: Int?, val field: String) {
        val label: String = message + (if (instance != null) "[$instance]" else "") + "." + field
        val key: String = PREFIX + label
    }

    fun supported(format: LogFormat) = format == LogFormat.DATAFLASH || format == LogFormat.DATAFLASH_TEXT

    fun isField(key: String) = key.startsWith(PREFIX)

    fun parse(key: String): Ref? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val dot = body.indexOf('.')
        if (dot <= 0) return null
        val head = body.substring(0, dot)
        val field = body.substring(dot + 1)
        val br = head.indexOf('[')
        return if (br < 0) Ref(head, null, field)
        else Ref(head.substring(0, br), head.substring(br + 1).removeSuffix("]").toIntOrNull() ?: return null, field)
    }

    /** Служебные сообщения без данных полёта. */
    private val SKIP = setOf("FMT", "FMTU", "UNIT", "MULT", "PARM", "MSG", "VER", "FILE", "ISBH", "ISBD")
    private val INSTANCE_COLS = listOf("Instance", "Inst", "I", "IMU", "C")

    private fun instanceColumn(tab: MessageTable): String? = INSTANCE_COLS.firstOrNull { tab.has(it) }

    /**
     * Все сообщения с TimeUS и их числовые поля. Файл читается целиком, но хранится
     * по одной записи раз в несколько секунд — памяти почти не нужно.
     */
    fun catalog(file: File, format: LogFormat): List<Message> {
        val r = read(file, format, null, CATALOG_RATE_HZ)
        return r.tables.values.filter { it.name !in SKIP && it.has("TimeUS") && it.rows > 0 }.map { tab ->
            val ic = instanceColumn(tab)
            val inst = ic?.let { c -> tab.num(c).map { it.toInt() }.distinct().sorted() }.orEmpty()
            Message(
                tab.name,
                if (inst.size > 1 || (inst.size == 1 && inst[0] != 0)) inst else emptyList(),
                tab.numeric.keys.filter { it != "TimeUS" && it != ic },
            )
        }.sortedBy { it.name }
    }

    /**
     * Ряды для выбранных ключей на шкале времени лога: [timeOriginUs] — ноль шкалы
     * ([FlightLog.timeOriginUs]); null — начало первого прочитанного сообщения.
     */
    fun extract(file: File, format: LogFormat, keys: Collection<String>, timeOriginUs: Double?): Map<String, Series> {
        val refs = keys.mapNotNull { parse(it) }
        if (refs.isEmpty()) return emptyMap()
        val r = read(file, format, refs.map { it.message }.toSet(), DataFlashParser.DEFAULT_RATE_HZ)
        val t0 = timeOriginUs ?: r.tables.values.mapNotNull { it.numeric["TimeUS"]?.takeIf { l -> l.size > 0 }?.get(0) }.minOrNull() ?: 0.0
        val out = LinkedHashMap<String, Series>()
        for (ref in refs) {
            val tab = r.tables[ref.message] ?: continue
            if (!tab.has(ref.field) || !tab.has("TimeUS")) continue
            val us = tab.num("TimeUS")
            val v = tab.num(ref.field)
            val ic = instanceColumn(tab)
            val rows = if (ref.instance != null && ic != null) {
                val inst = tab.num(ic)
                us.indices.filter { inst[it].toInt() == ref.instance }
            } else us.indices.toList()
            if (rows.isEmpty()) continue
            out[ref.key] = Series(ref.key, ref.label, ref.label, "",
                FloatArray(rows.size) { ((us[rows[it]] - t0) / 1e6).toFloat() },
                FloatArray(rows.size) { v[rows[it]].toFloat() })
        }
        return out
    }

    private const val CATALOG_RATE_HZ = 0.2

    private fun read(file: File, format: LogFormat, wanted: Set<String>?, rateHz: Double): DataFlashParser.Result = when (format) {
        LogFormat.DATAFLASH -> RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            DataFlashParser(wanted, rateHz, emptySet()).parse(buf)
        }
        LogFormat.DATAFLASH_TEXT -> file.inputStream().buffered(1 shl 16).use {
            TextLogParser(wanted, rateHz, emptySet()).parse(it, file.length())
        }
        else -> throw LogParseException("Поля лога доступны только для ArduPilot .bin и .log")
    }
}
