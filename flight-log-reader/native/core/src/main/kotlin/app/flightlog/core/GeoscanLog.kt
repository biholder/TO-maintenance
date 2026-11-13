package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Бортовой журнал автопилота Геоскан (101, 201, 401…): самоописывающий бинарный формат.
 *
 * Файл — поток записей `[время, мкс: 6 байт][контрольный байт][id][данные]`, у самой первой записи
 * заголовка нет. Запись с id 255 — описание сообщения: id, размер, имя (24 байта), единицы
 * или имена полей через запятую (16 байт) и до шести пар «тип, количество». Типы: 0 bool, 1 char,
 * 2 int8, 3 int16, 4 int32, 5 int64, 6 uint8, 7 uint16, 8 uint32, 9 uint64, 10 float, 11 double.
 *
 * В журнал 401 вклиниваются 14-байтные кадры другого устройства `55 AA … 33 CC` — даже внутри
 * записей; читатель их пропускает. При сбое ищет следующую запись с известным id и правдоподобным временем.
 */
class GeoscanLog(buffer: ByteBuffer) {
    class Field(val name: String, val type: Int, val offset: Int, val length: Int)

    class Message(val id: Int, val name: String, val size: Int, val units: String, val fields: List<Field>) {
        fun field(name: String) = fields.firstOrNull { it.name == name }
    }

    private val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val n = b.limit()
    /** Начало журнала (сигнатура описания описаний). */
    val start: Int = find(b)
    val messages = HashMap<Int, Message>()
    var records = 0L
        private set
    var skipped = 0L
        private set

    /** Запись: сообщение, время (мкс от включения), данные в [data] с позиции 0. */
    fun interface Visitor {
        fun record(msg: Message, timeUs: Long, data: ByteBuffer): Boolean
    }

    private val scratch = ByteArray(300)
    private val view: ByteBuffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)

    private fun frameAt(p: Int) = p + 14 <= n && b.get(p) == 0x55.toByte() && b.get(p + 1) == 0xAA.toByte() &&
        b.get(p + 12) == 0x33.toByte() && b.get(p + 13) == 0xCC.toByte()

    /** Читает [count] байт с позиции [from], пропуская чужие кадры; возвращает позицию после них или -1. */
    private fun read(from: Int, count: Int, dst: ByteArray, at: Int = 0): Int {
        var p = from
        var k = 0
        while (k < count) {
            while (frameAt(p)) p += 14
            if (p >= n) return -1
            dst[at + k++] = b.get(p++)
        }
        return p
    }

    /**
     * Проход по всему журналу. [visitor] возвращает false — остановить.
     * [progress] получает долю прочитанного.
     */
    fun scan(progress: ProgressListener? = null, visitor: Visitor) {
        if (start < 0) throw LogParseException("Не найдено начало журнала Геоскан")
        messages[255] = DESCRIPTOR
        var p = start
        var time = 0L
        var lastTime = -1L
        var lastReport = 0
        val hdr = ByteArray(7)
        while (p < n) {
            val id = b.get(p).toInt() and 0xFF
            val msg = messages[id]
            if (msg == null) { p = resync(p + 1, lastTime) ?: break; time = timeBefore(p); continue }
            val after = read(p + 1, msg.size, scratch)
            if (after < 0) break
            if (id == 255) describe()
            else if (msg.fields.isNotEmpty()) {
                view.clear(); view.limit(msg.size)
                if (!visitor.record(msg, time, view)) return
            }
            records++
            lastTime = time
            // Заголовок следующей записи: время и контрольный байт.
            val next = read(after, 7, hdr)
            if (next < 0) break
            var t = 0L
            for (i in 5 downTo 0) t = (t shl 8) or (hdr[i].toLong() and 0xFF)
            if (lastTime > 0 && (t < lastTime - 2_000_000 || t > lastTime + 60_000_000)) {
                p = resync(after, lastTime) ?: break; time = timeBefore(p); continue
            }
            time = t
            p = next
            while (frameAt(p)) p += 14
            if (progress != null && p - lastReport > 1 shl 20) { lastReport = p; progress.onProgress(0, p.toFloat() / n) }
        }
        progress?.onProgress(0, 1f)
    }

    private fun timeBefore(p: Int): Long {
        var t = 0L
        for (i in 6 downTo 1) t = (t shl 8) or (b.get(p - 1 - i).toLong() and 0xFF)
        return t
    }

    /** Ищет запись с известным id и временем рядом с [lastTime]; возвращает позицию id. */
    private fun resync(from: Int, lastTime: Long): Int? {
        var q = maxOf(from, 7)
        while (q < n) {
            val id = b.get(q).toInt() and 0xFF
            if (id != 255 && messages.containsKey(id)) {
                val t = timeBefore(q)
                if (lastTime < 0 || t in (lastTime - 1_000_000)..(lastTime + 30_000_000)) { skipped += q - from; return q }
            }
            q++
        }
        return null
    }

    /** Разбирает описание сообщения из [scratch]. */
    private fun describe() {
        val id = scratch[0].toInt() and 0xFF
        val size = scratch[1].toInt() and 0xFF
        if (id == 255) return
        val name = cstr(scratch, 2, 24)
        if (!NAME.matches(name)) return
        if (messages[id]?.name?.let { it != name } == true) return
        val units = cstr(scratch, 26, 16)
        val types = ArrayList<Pair<Int, Int>>()
        for (k in 0 until 6) {
            val ty = scratch[42 + k * 2].toInt() and 0xFF
            val cnt = scratch[43 + k * 2].toInt() and 0xFF
            if (ty > 11 || cnt == 0) break
            types += ty to cnt
        }
        val fields = layout(name, units, types)
        val total = fields.lastOrNull()?.let { it.offset + it.length * SIZES[it.type] } ?: 0
        messages[id] = Message(id, name, size, units, if (total == size) fields else emptyList())
    }

    companion object {
        private val SIG = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x3D) + "Descriptor".toByteArray()
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{1,23}")
        /** Размер значения каждого типа, байт. */
        val SIZES = intArrayOf(1, 1, 1, 2, 4, 8, 1, 2, 4, 8, 4, 8)
        private val DESCRIPTOR = Message(255, "Descriptor", 54, "", emptyList())

        fun find(b: ByteBuffer, limit: Int = 1 shl 16): Int {
            val end = minOf(b.limit(), limit) - SIG.size
            outer@ for (i in 0..end) {
                for (k in SIG.indices) if (b.get(i + k) != SIG[k]) continue@outer
                return i
            }
            return -1
        }

        fun looksLike(head: ByteArray) = find(ByteBuffer.wrap(head), head.size) >= 0

        private fun cstr(a: ByteArray, at: Int, len: Int): String {
            var e = at
            while (e < at + len && a[e] != 0.toByte()) e++
            return String(a, at, e - at, Charsets.ISO_8859_1)
        }

        /** Известные сообщения с понятными именами полей. */
        private val NAMES = mapOf(
            "Orientation" to listOf("roll", "pitch", "yaw"),
            "NavPosition" to listOf("lat", "lon", "alt"),
            "NavVelocity" to listOf("vn", "ve", "vd"),
            "MagHeading" to listOf("x", "y", "z"),
            "TimeDelta" to listOf("us"),
        )

        /**
         * Поля сообщения. Имена: из словаря известных сообщений; value для одного значения; из строки единиц, если это имена
         * через запятую по числу значений («a,e,r,t,mode,p»); «a.xyz,t,g.xyz» раскрывается в ax, ay, az…;
         * иначе value / x, y, z / v1…vN. Строки char[N] — одно поле.
         */
        fun layout(name: String, units: String, types: List<Pair<Int, Int>>): List<Field> {
            val scalars = ArrayList<Pair<Int, Int>>() // тип, длина (для char[N] — N)
            for ((ty, cnt) in types) {
                if (ty == 1) scalars += ty to cnt else repeat(cnt) { scalars += ty to 1 }
            }
            val tokens = units.split(',').map { it.trim() }.flatMap { t ->
                val dot = t.indexOf('.')
                if (dot > 0 && t.length - dot - 1 > 1) t.substring(dot + 1).map { "${t.substring(0, dot)}$it" } else listOf(t)
            }
            val names = NAMES[name]?.takeIf { it.size == scalars.size } ?: when {
                // Одно значение: в строке — единица измерения, а не имя.
                scalars.size == 1 -> listOf("value")
                // Имена через запятую; иногда последнее значение без имени («e,a,t,r,mode» при шести значениях).
                tokens.distinct().size == tokens.size && tokens.all { it.isNotEmpty() } &&
                    (tokens.size == scalars.size || tokens.size == scalars.size - 1) ->
                    tokens + (tokens.size until scalars.size).map { "v${it + 1}" }
                name == "BatteryCells" -> scalars.indices.map { "cell${it + 1}" }
                scalars.size == 3 -> listOf("x", "y", "z")
                else -> scalars.indices.map { "v${it + 1}" }
            }
            var off = 0
            return scalars.mapIndexed { i, (ty, len) ->
                Field(names[i], ty, off, len).also { off += SIZES[ty] * len }
            }
        }

        /** Значение поля как число (char[N] — NaN). */
        fun value(data: ByteBuffer, f: Field): Double = when (f.type) {
            0, 6 -> (data.get(f.offset).toInt() and 0xFF).toDouble()
            2 -> data.get(f.offset).toDouble()
            3 -> data.getShort(f.offset).toDouble()
            4 -> data.getInt(f.offset).toDouble()
            5 -> data.getLong(f.offset).toDouble()
            7 -> (data.getShort(f.offset).toInt() and 0xFFFF).toDouble()
            8 -> (data.getInt(f.offset).toLong() and 0xFFFFFFFFL).toDouble()
            9 -> data.getLong(f.offset).toULong().toDouble()
            10 -> data.getFloat(f.offset).toDouble()
            11 -> data.getDouble(f.offset)
            else -> Double.NaN
        }

        fun string(data: ByteBuffer, f: Field): String {
            val a = ByteArray(f.length) { data.get(f.offset + it) }
            return cstr(a, 0, a.size)
        }
    }
}
