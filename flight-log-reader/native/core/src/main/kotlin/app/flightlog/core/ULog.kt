package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбор журнала PX4 ULog (.ulg), версия 1: заголовок `ULog\x01\x12\x35`, затем сообщения
 * `[размер u16][тип u8][данные]`. Секция определений: форматы (F), сведения (I, M), параметры (P)
 * и их значения по умолчанию (Q). Секция данных: подписки (A), записи топиков (D), текстовые
 * сообщения (L, C), синхронизация (S), пропуски (O).
 *
 * Поля форматов раскладываются в плоский список скаляров: `q[0]`, `esc[1].esc_rpm`.
 * Строки char[N] и служебные `_padding` в каналы не попадают.
 */
class ULog(buffer: ByteBuffer) {
    /** Скалярное поле записи: смещение от начала данных записи. */
    class Field(val name: String, val type: Int, val offset: Int)

    class Format(val name: String, val size: Int, val fields: List<Field>) {
        private val byName = fields.associateBy { it.name }
        /** Сколько байт записи занимают поля: хвостовое выравнивание PX4 в журнал не пишет. */
        val used: Int = fields.maxOfOrNull { it.offset + SIZES[it.type] } ?: 0
        fun field(name: String) = byName[name]
    }

    class Subscription(val msgId: Int, val multiId: Int, val name: String, val format: Format)

    class LogMessage(val timeUs: Long, val level: Int, val text: String)

    private val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val n = b.limit()
    val version: Int
    /** Сведения о системе (I): ver_hw, ver_sw_release, sys_name… — строки и числа. */
    val info = LinkedHashMap<String, Any>()
    val params = LinkedHashMap<String, Float>()
    val defaults = HashMap<String, Float>()
    val messages = ArrayList<LogMessage>()
    val subscriptions = HashMap<Int, Subscription>()
    var dropouts = 0
        private set
    private val rawFormats = LinkedHashMap<String, String>()
    private val formats = HashMap<String, Format>()

    init {
        if (n < 16 || !isULog(b)) throw LogParseException("Это не журнал PX4 ULog")
        version = b.get(7).toInt() and 0xFF
    }

    fun interface Visitor {
        fun record(sub: Subscription, timeUs: Long, data: ByteBuffer, offset: Int): Boolean
    }

    /** Проход по всему журналу. [visitor] вызывается для каждой записи топика (D). */
    fun scan(progress: ProgressListener? = null, visitor: Visitor) {
        var p = 16
        var lastReport = 0
        while (p + 3 <= n) {
            val size = b.getShort(p).toInt() and 0xFFFF
            val type = b.get(p + 2).toInt().toChar()
            val body = p + 3
            val end = body + size
            if (end > n) break
            when (type) {
                'D' -> if (size >= 2) {
                    val sub = subscriptions[b.getShort(body).toInt() and 0xFFFF]
                    if (sub != null && size - 2 >= sub.format.used) {
                        val off = body + 2
                        if (!visitor.record(sub, b.getLong(off), b, off)) return
                    }
                }
                'A' -> if (size >= 3) {
                    val multi = b.get(body).toInt() and 0xFF
                    val id = b.getShort(body + 1).toInt() and 0xFFFF
                    val name = str(body + 3, size - 3)
                    format(name)?.let { subscriptions[id] = Subscription(id, multi, name, it) }
                }
                'R' -> if (size >= 2) subscriptions.remove(b.getShort(body).toInt() and 0xFFFF)
                'F' -> {
                    val s = str(body, size)
                    val colon = s.indexOf(':')
                    if (colon > 0) { rawFormats[s.substring(0, colon)] = s.substring(colon + 1); formats.clear() }
                }
                'I' -> keyValue(body, size)?.let { (k, v) -> info[k] = v }
                'M' -> if (size >= 2) keyValue(body + 1, size - 1)?.let { (k, v) ->
                    // Многочастные сведения (журнал загрузки и т. п.) склеиваются.
                    if (v is String) info[k] = (info[k] as? String ?: "") + v
                }
                'P' -> keyValue(body, size)?.let { (k, v) -> (v as? Number)?.let { params[k] = it.toFloat() } }
                'Q' -> if (size >= 2) {
                    val kinds = b.get(body).toInt()
                    // Бит 0 — значение по умолчанию системы, бит 1 — текущей конфигурации.
                    if (kinds and 1 != 0) keyValue(body + 1, size - 1)?.let { (k, v) -> (v as? Number)?.let { defaults[k] = it.toFloat() } }
                }
                'L' -> if (size >= 9) {
                    val lvl = (b.get(body).toInt() and 0xFF) - '0'.code
                    messages += LogMessage(b.getLong(body + 1), lvl, str(body + 9, size - 9).trim())
                }
                'C' -> if (size >= 11) {
                    val lvl = (b.get(body).toInt() and 0xFF) - '0'.code
                    messages += LogMessage(b.getLong(body + 3), lvl, str(body + 11, size - 11).trim())
                }
                'O' -> dropouts++
            }
            p = end
            if (progress != null && p - lastReport > 1 shl 20) { lastReport = p; progress.onProgress(0, p.toFloat() / n) }
        }
        progress?.onProgress(0, 1f)
    }

    private fun str(at: Int, len: Int): String {
        val a = ByteArray(len) { b.get(at + it) }
        var e = 0
        while (e < len && a[e] != 0.toByte()) e++
        return String(a, 0, e, Charsets.UTF_8)
    }

    /** Ключ «тип имя» и значение (I, M, P, Q). */
    private fun keyValue(at: Int, size: Int): Pair<String, Any>? {
        if (size < 1) return null
        val klen = b.get(at).toInt() and 0xFF
        if (1 + klen > size) return null
        val key = str(at + 1, klen)
        val sp = key.lastIndexOf(' ')
        if (sp < 0) return null
        val type = key.substring(0, sp)
        val name = key.substring(sp + 1)
        val v = at + 1 + klen
        val vlen = size - 1 - klen
        val value: Any = when {
            type.startsWith("char") -> str(v, vlen)
            else -> {
                val t = PRIMITIVES[type] ?: return name to ""
                if (vlen < SIZES[t]) return null
                read(b, v, t)
            }
        }
        return name to value
    }

    /** Формат с разложенными полями; вложенные форматы раскрываются. */
    fun format(name: String): Format? {
        formats[name]?.let { return it }
        val raw = rawFormats[name] ?: return null
        val fields = ArrayList<Field>()
        val size = layout(raw, "", 0, fields, 0) ?: return null
        return Format(name, size, fields).also { formats[name] = it }
    }

    private fun layout(raw: String, prefix: String, base: Int, out: MutableList<Field>, depth: Int): Int? {
        if (depth > 8) return null
        var off = base
        for (decl in raw.split(';')) {
            val d = decl.trim()
            if (d.isEmpty()) continue
            val sp = d.lastIndexOf(' ')
            if (sp < 0) return null
            var type = d.substring(0, sp).trim()
            val name = d.substring(sp + 1).trim()
            var count = 1
            val br = type.indexOf('[')
            if (br >= 0) {
                count = type.substring(br + 1, type.indexOf(']')).toIntOrNull() ?: return null
                type = type.substring(0, br)
            }
            val prim = PRIMITIVES[type]
            if (prim != null) {
                val sz = SIZES[prim]
                if (type != "char" && !name.startsWith("_padding")) {
                    for (i in 0 until count) out += Field(prefix + name + if (br >= 0) "[$i]" else "", prim, off + i * sz)
                }
                off += sz * count
            } else {
                val nested = rawFormats[type] ?: return null
                for (i in 0 until count) {
                    val p = prefix + name + (if (br >= 0) "[$i]" else "") + "."
                    off = layout(nested, p, off, out, depth + 1) ?: return null
                }
            }
        }
        return off
    }

    companion object {
        private val MAGIC = byteArrayOf(0x55, 0x4C, 0x6F, 0x67, 0x01, 0x12, 0x35)

        fun isULog(b: ByteBuffer) = b.limit() >= 8 && MAGIC.indices.all { b.get(it) == MAGIC[it] }
        fun looksLike(head: ByteArray) = head.size >= 8 && MAGIC.indices.all { head[it] == MAGIC[it] }

        /** Коды типов: 0 bool, 1 char, 2 int8 … 9 uint64, 10 float, 11 double (как у [GeoscanLog]). */
        private val PRIMITIVES = mapOf(
            "bool" to 0, "char" to 1, "int8_t" to 2, "int16_t" to 3, "int32_t" to 4, "int64_t" to 5,
            "uint8_t" to 6, "uint16_t" to 7, "uint32_t" to 8, "uint64_t" to 9, "float" to 10, "double" to 11,
        )
        internal val SIZES = intArrayOf(1, 1, 1, 2, 4, 8, 1, 2, 4, 8, 4, 8)

        fun read(b: ByteBuffer, at: Int, type: Int): Number = when (type) {
            0, 6 -> b.get(at).toInt() and 0xFF
            1, 2 -> b.get(at).toInt()
            3 -> b.getShort(at).toInt()
            4 -> b.getInt(at)
            5 -> b.getLong(at)
            7 -> b.getShort(at).toInt() and 0xFFFF
            8 -> b.getInt(at).toLong() and 0xFFFFFFFFL
            9 -> b.getLong(at)
            10 -> b.getFloat(at)
            11 -> b.getDouble(at)
            else -> 0
        }

        fun value(b: ByteBuffer, base: Int, f: Field): Double = when (f.type) {
            0, 6 -> (b.get(base + f.offset).toInt() and 0xFF).toDouble()
            1, 2 -> b.get(base + f.offset).toDouble()
            3 -> b.getShort(base + f.offset).toDouble()
            4 -> b.getInt(base + f.offset).toDouble()
            5 -> b.getLong(base + f.offset).toDouble()
            7 -> (b.getShort(base + f.offset).toInt() and 0xFFFF).toDouble()
            8 -> (b.getInt(base + f.offset).toLong() and 0xFFFFFFFFL).toDouble()
            9 -> b.getLong(base + f.offset).toULong().toDouble()
            10 -> b.getFloat(base + f.offset).toDouble()
            11 -> b.getDouble(base + f.offset)
            else -> Double.NaN
        }
    }
}
