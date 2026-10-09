package app.flightlog.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Разбор текстового журнала ArduPilot (.log, Mission Planner / mavlogdump):
 *
 *     FMT, 129, 23, PARM, QNff, TimeUS,Name,Value,Default
 *     PARM, 210000, ATC_RAT_RLL_P, 0.118, 0.135
 *
 * Значения уже в итоговых единицах (координаты в градусах и т. п.).
 * Файл читается построчно, частые сообщения прореживаются, как в [DataFlashParser].
 */
class TextLogParser(
    private val wanted: Set<String>? = DataFlashParser.DEFAULT_MESSAGES,
    private val maxRateHz: Double = DataFlashParser.DEFAULT_RATE_HZ,
    private val keepAll: Set<String> = DataFlashParser.KEEP_ALL,
) {
    private class Fmt(val name: String, val format: String, val columns: List<String>) {
        val hasTime = columns.firstOrNull() == "TimeUS"
        val instanceIdx = columns.indexOfFirst { it == "I" || it == "Inst" || it == "Instance" || it == "IMU" }
            .takeIf { it > 0 && format.getOrNull(it) in setOf('B', 'b') } ?: -1
        val lastKept = HashMap<String, Long>()
        val lastTextCol = format.indexOfLast { it in TEXT }
    }

    fun parse(input: InputStream, totalBytes: Long, progress: ProgressListener? = null): DataFlashParser.Result {
        val counting = CountingStream(input)
        val reader = BufferedReader(InputStreamReader(counting, Charsets.UTF_8), 1 shl 16)
        val fmts = HashMap<String, Fmt>()
        val tables = HashMap<String, MessageTable>()
        val minGapUs = if (maxRateHz > 0) (0.8e6 / maxRateHz).toLong() else 0L
        var count = 0L
        var bad = 0L
        var lastReport = 0L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val parts = line.split(',').map { it.trim() }
            val name = parts[0]
            if (name == "FMT") {
                // FMT, type, length, name, format, col1,col2,...
                if (parts.size >= 6) {
                    val f = Fmt(parts[3], parts[4], parts.drop(5).filter { it.isNotEmpty() })
                    if (f.format.isNotEmpty() && f.format.length == f.columns.size) fmts[f.name] = f else bad++
                } else bad++
                count++
                continue
            }
            val fmt = fmts[name]
            if (fmt == null) { bad++; continue }
            count++
            if (wanted != null && name !in wanted) continue
            val values = fit(parts.subList(1, parts.size), fmt) ?: run { bad++; null } ?: continue
            if (!keep(values, fmt, minGapUs)) continue
            val table = tables.getOrPut(name) { newTable(fmt) }
            var ok = true
            fmt.format.forEachIndexed { i, c ->
                val col = fmt.columns[i]
                when {
                    c in TEXT -> table.text[col]!!.add(values[i])
                    c == 'a' -> Unit
                    else -> table.numeric[col]!!.add(values[i].toDoubleOrNull() ?: Double.NaN.also { ok = false })
                }
            }
            table.rows++
            if (!ok) bad++
            if (progress != null && counting.count - lastReport > 1 shl 20) {
                lastReport = counting.count
                progress.onProgress(0, if (totalBytes > 0) counting.count.toFloat() / totalBytes else 0f)
            }
        }
        progress?.onProgress(0, 1f)
        if (tables.isEmpty()) throw LogParseException("В текстовом логе не найдено сообщений с описанием FMT")
        return DataFlashParser.Result(tables, count, bad)
    }

    /** Подгоняет число полей: запятые внутри текстового поля склеиваются обратно. */
    private fun fit(values: List<String>, fmt: Fmt): List<String>? {
        val n = fmt.format.length
        if (values.size == n) return values
        if (values.size < n || fmt.lastTextCol < 0) return null
        val extra = values.size - n
        val i = fmt.lastTextCol
        return values.subList(0, i) + values.subList(i, i + extra + 1).joinToString(",") + values.subList(i + extra + 1, values.size)
    }

    private fun keep(values: List<String>, fmt: Fmt, minGapUs: Long): Boolean {
        if (minGapUs <= 0 || !fmt.hasTime || fmt.name in keepAll) return true
        val t = values[0].toLongOrNull() ?: values[0].toDoubleOrNull()?.toLong() ?: return true
        val key = if (fmt.instanceIdx > 0) values[fmt.instanceIdx] else ""
        val last = fmt.lastKept[key]
        if (last != null && t >= last && t - last < minGapUs) return false
        fmt.lastKept[key] = t
        return true
    }

    private fun newTable(f: Fmt): MessageTable {
        val t = MessageTable(f.name, f.columns)
        f.format.forEachIndexed { i, c ->
            if (c in TEXT) t.text[f.columns[i]] = ArrayList() else if (c != 'a') t.numeric[f.columns[i]] = DoubleList()
        }
        return t
    }

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    companion object {
        private val TEXT = setOf('n', 'N', 'Z')

        fun looksLikeText(head: ByteArray): Boolean = String(head, Charsets.US_ASCII).trimStart().startsWith("FMT,")
    }
}
