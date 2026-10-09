package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбор бинарного журнала ArduPilot DataFlash (.bin).
 *
 * Каждое сообщение: 0xA3 0x95 <type> <payload>. Структура payload задаётся
 * сообщениями FMT (type 128), которые идут в самом логе. При повреждении
 * данных парсер ищет следующий заголовок и продолжает.
 *
 * Работает поверх [ByteBuffer] — в том числе отображённого в память файла,
 * поэтому сам файл не занимает кучу. Частые сообщения прореживаются до
 * [maxRateHz], чтобы многочасовые логи помещались в память телефона.
 */
class DataFlashParser(
    /** Какие сообщения сохранять (null — все). */
    private val wanted: Set<String>? = DEFAULT_MESSAGES,
    /** Предельная частота хранения для сообщений с TimeUS (0 — без прореживания). */
    private val maxRateHz: Double = DEFAULT_RATE_HZ,
    /** Сообщения, которые хранятся полностью: события и параметры. */
    private val keepAll: Set<String> = KEEP_ALL,
) {
    class Result(val tables: Map<String, MessageTable>, val messageCount: Long, val badBytes: Long)

    private class Fmt(val type: Int, val length: Int, val name: String, val format: String, val columns: List<String>) {
        /** Смещение поля TimeUS в payload, если оно первое и типа Q/q. */
        val timeAt: Int = if (columns.firstOrNull() == "TimeUS" && format.firstOrNull() in setOf('Q', 'q')) 3 else -1
        var lastKeptUs = Long.MIN_VALUE
        /** Таблица; для нескольких экземпляров (GPS[0]/GPS[1], BAT…) прореживание ведётся по паре (тип, экземпляр). */
        val lastKeptByInstance = HashMap<Int, Long>()
        val instanceAt: Int = run {
            val i = columns.indexOfFirst { it == "I" || it == "Inst" || it == "Instance" || it == "IMU" || it == "C" }
            if (i <= 0 || format[i] !in setOf('B', 'b')) -1 else 3 + format.substring(0, i).sumOf { SIZES.getValue(it) }
        }
    }

    fun parse(bytes: ByteArray, progress: ProgressListener? = null): Result =
        parse(ByteBuffer.wrap(bytes), progress)

    fun parse(buffer: ByteBuffer, progress: ProgressListener? = null): Result {
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val fmts = arrayOfNulls<Fmt>(256)
        fmts[FMT_TYPE] = Fmt(FMT_TYPE, 89, "FMT", "BBnNZ", listOf("Type", "Length", "Name", "Format", "Columns"))
        val tables = HashMap<String, MessageTable>()
        // Порог чуть меньше периода, чтобы потоки ровно на maxRateHz с джиттером не терялись через раз.
        val minGapUs = if (maxRateHz > 0) (0.8e6 / maxRateHz).toLong() else 0L
        var pos = 0
        var count = 0L
        var bad = 0L
        var lastReport = 0
        val n = buf.limit()
        while (pos + 3 <= n) {
            if (buf.get(pos) != HEAD1 || buf.get(pos + 1) != HEAD2) {
                pos++; bad++; continue
            }
            val type = buf.get(pos + 2).toInt() and 0xFF
            val fmt = fmts[type]
            if (fmt == null || pos + fmt.length > n) {
                pos++; bad++; continue
            }
            // Проверка: за сообщением должен идти следующий заголовок (или конец файла).
            val next = pos + fmt.length
            if (next + 1 < n && (buf.get(next) != HEAD1 || buf.get(next + 1) != HEAD2)) {
                pos++; bad++; continue
            }
            if (type == FMT_TYPE) {
                val f = readFmt(buf, pos + 3)
                if (f != null) fmts[f.type] = f
            } else if (wanted == null || fmt.name in wanted) {
                if (keep(buf, pos, fmt, minGapUs)) {
                    val table = tables.getOrPut(fmt.name) { newTable(fmt) }
                    decode(buf, pos + 3, fmt, table)
                }
            }
            count++
            pos = next
            if (progress != null && pos - lastReport > 1 shl 20) {
                lastReport = pos
                progress.onProgress(0, pos.toFloat() / n)
            }
        }
        progress?.onProgress(0, 1f)
        if (count == 0L) throw LogParseException("Не найдено ни одного сообщения DataFlash")
        return Result(tables, count, bad)
    }

    /** Прореживание: не чаще одного сообщения за [minGapUs] на тип и экземпляр. */
    private fun keep(buf: ByteBuffer, pos: Int, fmt: Fmt, minGapUs: Long): Boolean {
        if (minGapUs <= 0 || fmt.timeAt < 0 || fmt.name in keepAll) return true
        val t = buf.getLong(pos + fmt.timeAt)
        if (fmt.instanceAt < 0) {
            if (fmt.lastKeptUs != Long.MIN_VALUE && t >= fmt.lastKeptUs && t - fmt.lastKeptUs < minGapUs) return false
            fmt.lastKeptUs = t
        } else {
            val inst = buf.get(pos + fmt.instanceAt).toInt() and 0xFF
            val last = fmt.lastKeptByInstance[inst]
            if (last != null && t >= last && t - last < minGapUs) return false
            fmt.lastKeptByInstance[inst] = t
        }
        return true
    }

    private fun readFmt(buf: ByteBuffer, at: Int): Fmt? {
        val type = buf.get(at).toInt() and 0xFF
        val length = buf.get(at + 1).toInt() and 0xFF
        val name = cstr(buf, at + 2, 4)
        val format = cstr(buf, at + 6, 16)
        val cols = cstr(buf, at + 22, 64).split(',').map { it.trim() }
        if (name.isEmpty() || format.any { it !in SIZES }) return null
        if (3 + format.sumOf { SIZES.getValue(it) } != length) return null
        return Fmt(type, length, name, format, cols)
    }

    private fun newTable(f: Fmt): MessageTable {
        val t = MessageTable(f.name, f.columns)
        f.format.forEachIndexed { i, c ->
            val col = f.columns.getOrElse(i) { "f$i" }
            if (c in TEXT) t.text[col] = ArrayList() else if (c != 'a') t.numeric[col] = DoubleList()
        }
        return t
    }

    private fun decode(buf: ByteBuffer, start: Int, f: Fmt, table: MessageTable) {
        var p = start
        f.format.forEachIndexed { i, c ->
            val col = f.columns.getOrElse(i) { "f$i" }
            when (c) {
                'n', 'N', 'Z' -> table.text[col]!!.add(cstr(buf, p, SIZES.getValue(c)))
                'a' -> Unit
                else -> table.numeric[col]!!.add(readNum(buf, p, c))
            }
            p += SIZES.getValue(c)
        }
        table.rows++
    }

    private fun readNum(buf: ByteBuffer, p: Int, c: Char): Double = when (c) {
        'b' -> buf.get(p).toDouble()
        'B', 'M' -> (buf.get(p).toInt() and 0xFF).toDouble()
        'h' -> buf.getShort(p).toDouble()
        'H' -> (buf.getShort(p).toInt() and 0xFFFF).toDouble()
        'i' -> buf.getInt(p).toDouble()
        'I' -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toDouble()
        'f' -> buf.getFloat(p).toDouble()
        'd' -> buf.getDouble(p)
        'q' -> buf.getLong(p).toDouble()
        'Q' -> buf.getLong(p).let { if (it >= 0) it.toDouble() else (it ushr 1).toDouble() * 2.0 }
        'c' -> buf.getShort(p) * 0.01
        'C' -> (buf.getShort(p).toInt() and 0xFFFF) * 0.01
        'e' -> buf.getInt(p) * 0.01
        'E' -> (buf.getInt(p).toLong() and 0xFFFFFFFFL) * 0.01
        'L' -> buf.getInt(p) * 1.0e-7
        'g' -> halfToFloat(buf.getShort(p).toInt()).toDouble()
        else -> Double.NaN
    }

    companion object {
        private const val HEAD1 = 0xA3.toByte()
        private const val HEAD2 = 0x95.toByte()
        private const val FMT_TYPE = 128
        private val TEXT = setOf('n', 'N', 'Z')
        internal val SIZES = mapOf(
            'a' to 64, 'b' to 1, 'B' to 1, 'g' to 2, 'h' to 2, 'H' to 2, 'i' to 4, 'I' to 4,
            'f' to 4, 'n' to 4, 'N' to 16, 'Z' to 64, 'c' to 2, 'C' to 2, 'e' to 4, 'E' to 4,
            'L' to 4, 'd' to 8, 'M' to 1, 'q' to 8, 'Q' to 8,
        )

        /** Сообщения, нужные для анализа полёта. */
        val DEFAULT_MESSAGES = setOf(
            "PARM", "MSG", "MODE", "EV", "ERR", "GPS", "CTUN", "BAT", "BAT0", "CURR", "VIBE",
            "ATT", "VER", "XKF4", "POS",
        )

        /** Сообщения без прореживания: события, режимы, параметры, текст. */
        val KEEP_ALL = setOf("PARM", "MSG", "MODE", "EV", "ERR", "VER")

        /** 10 Гц достаточно для графиков и диагностики и ограничивает память на длинных логах. */
        const val DEFAULT_RATE_HZ = 10.0

        private fun cstr(buf: ByteBuffer, at: Int, len: Int): String {
            var end = 0
            while (end < len && buf.get(at + end) != 0.toByte()) end++
            val arr = ByteArray(end)
            for (i in 0 until end) arr[i] = buf.get(at + i)
            return String(arr, Charsets.UTF_8).trim()
        }

        private fun halfToFloat(h: Int): Float {
            val bits = h and 0xFFFF
            val sign = if (bits and 0x8000 != 0) -1f else 1f
            val exp = (bits shr 10) and 0x1F
            val mant = bits and 0x3FF
            return sign * when (exp) {
                0 -> mant / 1024f * Math.pow(2.0, -14.0).toFloat()
                31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1 + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
        }
    }
}
