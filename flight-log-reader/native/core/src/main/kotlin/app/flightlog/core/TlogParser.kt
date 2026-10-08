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

    fun parse(bytes: ByteArray, progress: ProgressListener? = null): Result {
        val out = ArrayList<Packet>()
        var pos = 0
        var bad = 0L
        val n = bytes.size
        var lastReport = 0
        val tsBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 + 8 <= n) {
            val magic = bytes[pos + 8].toInt() and 0xFF
            val ok = when (magic) {
                0xFE -> tryV1(bytes, pos, tsBuf, out)
                0xFD -> tryV2(bytes, pos, tsBuf, out)
                else -> -1
            }
            if (ok < 0) { pos++; bad++; continue }
            pos = ok
            if (progress != null && pos - lastReport > 256 * 1024) {
                lastReport = pos
                progress.onProgress(0, pos.toFloat() / n)
            }
        }
        progress?.onProgress(0, 1f)
        if (out.isEmpty()) throw LogParseException("Не найдено ни одного пакета MAVLink")
        return Result(out, bad)
    }

    private fun plausibleTime(us: Long) = us in 946_684_800_000_000L..4_102_444_800_000_000L // 2000..2100

    private fun tryV1(b: ByteArray, pos: Int, ts: ByteBuffer, out: MutableList<Packet>): Int {
        val p = pos + 8
        if (p + 6 > b.size) return -1
        val len = b[p + 1].toInt() and 0xFF
        val end = p + 6 + len + 2
        if (end > b.size) return -1
        val time = ts.getLong(pos)
        if (!plausibleTime(time)) return -1
        val msgId = b[p + 5].toInt() and 0xFF
        val crcExtra = CRC_EXTRA[msgId] ?: return end.also { /* неизвестное сообщение — пропускаем */ }
        if (!checkCrc(b, p + 1, 5 + len, crcExtra, b, p + 6 + len)) return -1
        out += Packet(time, b[p + 3].toInt() and 0xFF, b[p + 4].toInt() and 0xFF, msgId, payload(b, p + 6, len, msgId))
        return end
    }

    private fun tryV2(b: ByteArray, pos: Int, ts: ByteBuffer, out: MutableList<Packet>): Int {
        val p = pos + 8
        if (p + 10 > b.size) return -1
        val len = b[p + 1].toInt() and 0xFF
        val incompat = b[p + 2].toInt() and 0xFF
        val sigLen = if (incompat and 0x01 != 0) 13 else 0
        val end = p + 10 + len + 2 + sigLen
        if (end > b.size) return -1
        val time = ts.getLong(pos)
        if (!plausibleTime(time)) return -1
        val msgId = (b[p + 7].toInt() and 0xFF) or ((b[p + 8].toInt() and 0xFF) shl 8) or ((b[p + 9].toInt() and 0xFF) shl 16)
        val crcExtra = CRC_EXTRA[msgId] ?: return end
        if (!checkCrc(b, p + 1, 9 + len, crcExtra, b, p + 10 + len)) return -1
        out += Packet(time, b[p + 5].toInt() and 0xFF, b[p + 6].toInt() and 0xFF, msgId, payload(b, p + 10, len, msgId))
        return end
    }

    private fun payload(b: ByteArray, at: Int, len: Int, msgId: Int): ByteBuffer {
        val full = maxOf(len, MIN_LEN[msgId] ?: len)
        val arr = ByteArray(full)
        System.arraycopy(b, at, arr, 0, len)
        return ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun checkCrc(b: ByteArray, from: Int, count: Int, extra: Int, crcArr: ByteArray, crcAt: Int): Boolean {
        var crc = 0xFFFF
        fun acc(x: Int) {
            var tmp = (x xor crc) and 0xFF
            tmp = (tmp xor (tmp shl 4)) and 0xFF
            crc = ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
        }
        for (i in from until from + count) acc(b[i].toInt() and 0xFF)
        acc(extra)
        val got = (crcArr[crcAt].toInt() and 0xFF) or ((crcArr[crcAt + 1].toInt() and 0xFF) shl 8)
        return got == crc
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
