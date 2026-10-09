package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбор телеметрии MAVLink (.tlog): поток записей
 * [8 байт метка времени UNIX, мкс, big-endian][пакет MAVLink v1 или v2].
 *
 * Декодируются только сообщения, нужные для анализа. Поля в пакете идут в
 * «проводном» порядке MAVLink (по убыванию размера типа); хвостовые нули v2
 * обрезаны — поэтому payload дополняется нулями до полной длины.
 */
class TlogParser {
    class Packet(val timeUs: Long, val sysId: Int, val compId: Int, val msgId: Int, val payload: ByteBuffer)

    class Result(val packets: List<Packet>, val badBytes: Long)

    /** Все пакеты списком — для небольших файлов и тестов. */
    fun parse(bytes: ByteArray, progress: ProgressListener? = null): Result {
        val out = ArrayList<Packet>()
        val bad = scan(ByteBuffer.wrap(bytes), progress) { out += it; true }
        if (out.isEmpty()) throw LogParseException("Не найдено ни одного пакета MAVLink")
        return Result(out, bad)
    }

    /**
     * Потоковый разбор: [onPacket] вызывается для каждого известного пакета,
     * false — остановить. Возвращает число пропущенных байт.
     */
    fun scan(buffer: ByteBuffer, progress: ProgressListener? = null, onPacket: (Packet) -> Boolean): Long {
        val b = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        var pos = 0
        var bad = 0L
        val n = b.limit()
        var lastReport = 0
        while (pos + 8 + 8 <= n) {
            val magic = b.get(pos + 8).toInt() and 0xFF
            val r = when (magic) {
                0xFE -> tryV1(b, pos)
                0xFD -> tryV2(b, pos)
                else -> null
            }
            if (r == null) { pos++; bad++; continue }
            pos = r.first
            val packet = r.second
            if (packet != null && !onPacket(packet)) break
            if (progress != null && pos - lastReport > 1 shl 20) {
                lastReport = pos
                progress.onProgress(0, pos.toFloat() / n)
            }
        }
        progress?.onProgress(0, 1f)
        return bad
    }

    private fun plausibleTime(us: Long) = us in 946_684_800_000_000L..4_102_444_800_000_000L // 2000..2100

    private fun u8(b: ByteBuffer, i: Int) = b.get(i).toInt() and 0xFF

    /** Возвращает (позиция после пакета, пакет или null для неизвестного сообщения) либо null при сбое. */
    private fun tryV1(b: ByteBuffer, pos: Int): Pair<Int, Packet?>? {
        val p = pos + 8
        if (p + 6 > b.limit()) return null
        val len = u8(b, p + 1)
        val end = p + 6 + len + 2
        if (end > b.limit()) return null
        val time = b.getLong(pos)
        if (!plausibleTime(time)) return null
        val msgId = u8(b, p + 5)
        val crcExtra = CRC_EXTRA[msgId] ?: return end to null
        if (!checkCrc(b, p + 1, 5 + len, crcExtra, p + 6 + len)) return null
        return end to Packet(time, u8(b, p + 3), u8(b, p + 4), msgId, payload(b, p + 6, len, msgId))
    }

    private fun tryV2(b: ByteBuffer, pos: Int): Pair<Int, Packet?>? {
        val p = pos + 8
        if (p + 10 > b.limit()) return null
        val len = u8(b, p + 1)
        val sigLen = if (u8(b, p + 2) and 0x01 != 0) 13 else 0
        val end = p + 10 + len + 2 + sigLen
        if (end > b.limit()) return null
        val time = b.getLong(pos)
        if (!plausibleTime(time)) return null
        val msgId = u8(b, p + 7) or (u8(b, p + 8) shl 8) or (u8(b, p + 9) shl 16)
        val crcExtra = CRC_EXTRA[msgId] ?: return end to null
        if (!checkCrc(b, p + 1, 9 + len, crcExtra, p + 10 + len)) return null
        return end to Packet(time, u8(b, p + 5), u8(b, p + 6), msgId, payload(b, p + 10, len, msgId))
    }

    private fun payload(b: ByteBuffer, at: Int, len: Int, msgId: Int): ByteBuffer {
        val full = maxOf(len, MIN_LEN[msgId] ?: len)
        val arr = ByteArray(full)
        for (i in 0 until len) arr[i] = b.get(at + i)
        return ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun checkCrc(b: ByteBuffer, from: Int, count: Int, extra: Int, crcAt: Int): Boolean {
        var crc = 0xFFFF
        fun acc(x: Int) {
            var tmp = (x xor crc) and 0xFF
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
        }
        for (i in from until from + count) acc(u8(b, i))
        acc(extra)
        return (u8(b, crcAt) or (u8(b, crcAt + 1) shl 8)) == crc
    }

    companion object {
        const val HEARTBEAT = 0
        const val SYS_STATUS = 1
        const val SYSTEM_TIME = 2
        const val PARAM_VALUE = 22
        const val GPS_RAW_INT = 24
        const val ATTITUDE = 30
        const val GLOBAL_POSITION_INT = 33
        const val VFR_HUD = 74
        const val BATTERY_STATUS = 147
        const val VIBRATION = 241
        const val STATUSTEXT = 253

        /** CRC_EXTRA из определений сообщений common.xml. */
        private val CRC_EXTRA = mapOf(
            HEARTBEAT to 50, SYS_STATUS to 124, SYSTEM_TIME to 137, PARAM_VALUE to 220,
            GPS_RAW_INT to 24, ATTITUDE to 39, GLOBAL_POSITION_INT to 104, VFR_HUD to 20,
            BATTERY_STATUS to 154, VIBRATION to 90, STATUSTEXT to 83,
        )
        /** Длина базовой части payload (без расширений). */
        private val MIN_LEN = mapOf(
            HEARTBEAT to 9, SYS_STATUS to 31, SYSTEM_TIME to 12, PARAM_VALUE to 25,
            GPS_RAW_INT to 30, ATTITUDE to 28, GLOBAL_POSITION_INT to 28, VFR_HUD to 20,
            BATTERY_STATUS to 36, VIBRATION to 32, STATUSTEXT to 51,
        )
    }
}
