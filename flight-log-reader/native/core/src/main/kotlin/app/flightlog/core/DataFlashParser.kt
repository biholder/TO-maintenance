package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбор бинарного журнала ArduPilot DataFlash (.bin).
 *
 * Каждое сообщение: 0xA3 0x95 <type> <payload>. Структура payload задаётся
 * сообщениями FMT (type 128), которые идут в самом логе. При повреждении
 * данных парсер ищет следующий заголовок и продолжает.
 */
class DataFlashParser(
    /** Какие сообщения сохранять (null — все). */
    private val wanted: Set<String>? = DEFAULT_MESSAGES,
) {
    class Result(val tables: Map<String, MessageTable>, val messageCount: Int, val badBytes: Long)

    private class Fmt(val type: Int, val length: Int, val name: String, val format: String, val columns: List<String>)

    fun parse(bytes: ByteArray, progress: ProgressListener? = null): Result {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fmts = arrayOfNulls<Fmt>(256)
        fmts[FMT_TYPE] = Fmt(FMT_TYPE, 89, "FMT", "BBnNZ", listOf("Type", "Length", "Name", "Format", "Columns"))
        val tables = HashMap<String, MessageTable>()
        var pos = 0
        var count = 0
        var bad = 0L
        var lastReport = 0
        val n = bytes.size
        while (pos + 3 <= n) {
            if (bytes[pos] != HEAD1 || bytes[pos + 1] != HEAD2) {
                pos++; bad++; continue
            }
            val type = bytes[pos + 2].toInt() and 0xFF
            val fmt = fmts[type]
            if (fmt == null || pos + fmt.length > n) {
                pos++; bad++; continue
            }
            // Проверка: за сообщением должен идти следующий заголовок (или конец файла).
            val next = pos + fmt.length
            if (next + 1 < n && (bytes[next] != HEAD1 || bytes[next + 1] != HEAD2)) {
                pos++; bad++; continue
            }
            if (type == FMT_TYPE) {
                val f = readFmt(buf, pos + 3)
                if (f != null) fmts[f.type] = f
            } else if (wanted == null || fmt.name in wanted) {
                val table = tables.getOrPut(fmt.name) { newTable(fmt) }
                decode(buf, pos + 3, fmt, table)
            }
            count++
            pos = next
            if (progress != null && pos - lastReport > 256 * 1024) {
                lastReport = pos
                progress.onProgress(0, pos.toFloat() / n)
            }
        }
        progress?.onProgress(0, 1f)
        if (count == 0) throw LogParseException("Не найдено ни одного сообщения DataFlash")
        return Result(tables, count, bad)
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
                'n', 'N', 'Z' -> {
                    val len = SIZES.getValue(c)
                    table.text[col]!!.add(cstr(buf, p, len))
                }
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
        private val SIZES = mapOf(
            'a' to 64, 'b' to 1, 'B' to 1, 'g' to 2, 'h' to 2, 'H' to 2, 'i' to 4, 'I' to 4,
            'f' to 4, 'n' to 4, 'N' to 16, 'Z' to 64, 'c' to 2, 'C' to 2, 'e' to 4, 'E' to 4,
            'L' to 4, 'd' to 8, 'M' to 1, 'q' to 8, 'Q' to 8,
        )

        /** Сообщения, нужные для анализа полёта. */
        val DEFAULT_MESSAGES = setOf(
            "PARM", "MSG", "MODE", "EV", "ERR", "GPS", "CTUN", "BAT", "BAT0", "CURR", "VIBE",
            "ATT", "VER", "XKF4", "POS",
        )

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
