package app.flightlog.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Разбор DJI FlightRecord (.txt) — журналов DJI GO 4, DJI Fly, DJI Pilot.
 *
 * Структура и алгоритмы перенесены из dji-log-parser
 * (https://github.com/lvauvillier/dji-log-parser, MIT, © Luc Vauvillier):
 *
 *  - v1–6: записи открытым текстом;
 *  - v7–12: каждая запись обфусцирована XOR с ключом из CRC-64 (без внешних ключей);
 *  - v13+: поверх XOR — AES-256-CBC; ключи к каждому файлу выдаёт DJI по API
 *    (см. [DjiKeychains]), на устройстве их не получить.
 *
 * Запись: [тип u8][длина u8 (v≤12) или u16][данные: seed + payload][0xFF].
 */
class DjiLog(buffer: ByteBuffer) {
    private val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val size = b.limit()

    val version: Int
    val details: DjiDetails
    /** Версия и «департамент» (приложение) из второго вспомогательного блока v13+ — нужны для запроса ключей. */
    val auxVersion: Int
    val department: Int
    private val recordsStart: Int
    private val recordsEnd: Int

    init {
        if (size < 100) throw LogParseException("Файл слишком мал для DJI FlightRecord")
        val detailOffsetRaw = b.getLong(0)
        version = u8(10)
        if (version !in 1..20) throw LogParseException("Неизвестная версия DJI FlightRecord: $version")
        var aVer = 0
        var dep = 3
        var recStart: Long
        if (version < 13) {
            val detailOffset = if (version < 12) detailOffsetRaw else 100L
            if (detailOffset !in 0 until size) throw LogParseException("Повреждён заголовок DJI FlightRecord")
            details = DjiDetails.parse(bytes(detailOffset.toInt(), minOf(400, size - detailOffset.toInt())), version)
            recStart = when {
                version < 6 -> 12L
                version < 12 -> 100L
                else -> 100L + 436
            }
            recordsEnd = if (version < 12) detailOffsetRaw.toInt() else size
        } else {
            // Блок 0: зашифрованные XOR сведения о полёте; блок 1: версия и приложение.
            var p = 100
            require(u8(p) == 0) { "DJI: нет блока сведений" }
            val infoSize = u16(p + 1)
            val region = decodeXor(p + 3, infoSize, 0)
            val info = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN)
            val infoLen = info.getShort(1).toInt() and 0xFFFF
            details = DjiDetails.parse(region.copyOfRange(3, minOf(region.size, 3 + infoLen)), version)
            p += 3 + infoSize
            if (p < size && u8(p) == 1) {
                val vs = u16(p + 1)
                aVer = u16(p + 3)
                dep = u8(p + 5)
                p += 3 + vs
            }
            recStart = detailOffsetRaw
            if (recStart == 0L || recStart > size) recStart = p.toLong()
            recordsEnd = size
        }
        auxVersion = aVer
        department = if (dep in 1..8) dep else 3 // неизвестное → DJI Fly, как в эталоне
        recordsStart = recStart.toInt()
    }

    /** Сырая запись после снятия XOR/AES. [data] пусто, если расшифровать не удалось. */
    class Record(val type: Int, val data: ByteArray)

    /** Статистика последнего обхода: сколько записей шифровались AES и сколько не расшифровалось. */
    var aesTotal = 0
        private set
    var aesFailed = 0
        private set

    /**
     * Обход записей. [keychains] — ключи по цепочкам (новая цепочка начинается
     * после записи KeyStorageRecover); для v<13 не нужны.
     *
     * Цепочка для участка лога выбирается по первой зашифрованной записи: если
     * ожидаемая по порядку не расшифровывает её, пробуются остальные — сервер DJI
     * может вернуть цепочки не в том порядке или без пустых.
     * [maxAes] — остановиться после стольких AES-записей (для быстрой проверки ключей).
     */
    fun records(
        keychains: List<Map<Int, Pair<ByteArray, ByteArray>>>? = null,
        maxAes: Int = Int.MAX_VALUE,
        onRecord: (Record) -> Unit,
    ) {
        val all = keychains ?: emptyList()
        var segment = 0
        var chain: HashMap<Int, Pair<ByteArray, ByteArray>>? = null
        aesTotal = 0
        aesFailed = 0
        val lenSize = if (version <= 12) 1 else 2
        var pos = recordsStart
        val end = minOf(recordsEnd, size)
        while (pos + 1 + lenSize < end && aesTotal < maxAes) {
            val type = u8(pos)
            // Вставленные JPEG-миниатюры: FF D8 … FF D9.
            if (type == 0xFF && u8(pos + 1) == 0xD8) {
                var q = pos + 2
                while (q + 1 < end && !(u8(q) == 0xFF && u8(q + 1) == 0xD9)) q++
                pos = q + 2
                continue
            }
            val len = if (lenSize == 1) u8(pos + 1) else u16(pos + 1)
            val dataStart = pos + 1 + lenSize
            val regionEnd = dataStart + len
            if (len == 0 || regionEnd >= end || u8(regionEnd) != 0xFF) {
                pos++ // сбой — ищем следующую запись
                continue
            }
            val data = when {
                type == KEY_STORAGE_RECOVER -> bytes(dataStart, len)
                version <= 6 -> bytes(dataStart, len)
                version <= 12 -> decodeXor(dataStart, len, type).let { it.copyOf(maxOf(0, it.size - 1)) }
                else -> {
                    val feature = featurePoint(type, version)
                    val x = decodeXor(dataStart, len, type)
                    if (feature == PLAINTEXT || all.isEmpty() || len < 18) x.copyOf(maxOf(0, x.size - 1))
                    else {
                        val ct = x.copyOf(len - 2)
                        val c = chain ?: pickChain(all, segment, feature, ct).also { chain = it }
                        val key = c[feature]
                        aesTotal++
                        if (key == null) { aesFailed++; ByteArray(0) }
                        else {
                            c[feature] = ct.copyOfRange(ct.size - 16, ct.size) to key.second
                            decryptAes(ct, key.first, key.second).also { if (it.isEmpty()) aesFailed++ }
                        }
                    }
                }
            }
            if (type == KEY_STORAGE_RECOVER) { segment++; chain = null }
            onRecord(Record(type, data))
            pos = regionEnd + 1
        }
    }

    /** Цепочка ключей для участка: по порядку, а если не подходит — первая, что расшифровывает запись. */
    private fun pickChain(
        all: List<Map<Int, Pair<ByteArray, ByteArray>>>, segment: Int, feature: Int, ct: ByteArray,
    ): HashMap<Int, Pair<ByteArray, ByteArray>> {
        val order = (listOf(segment) + all.indices).distinct().filter { it in all.indices }
        for (i in order) {
            val k = all[i][feature] ?: continue
            if (decryptAes(ct, k.first, k.second).isNotEmpty()) return HashMap(all[i])
        }
        return HashMap(all.getOrNull(segment) ?: emptyMap())
    }

    /** Доля успешно расшифрованных AES-записей на первых [sample] записях (0…1). */
    fun keysFit(keychains: List<Map<Int, Pair<ByteArray, ByteArray>>>, sample: Int = 300): Float {
        records(keychains, maxAes = sample) {}
        return if (aesTotal == 0) 1f else (aesTotal - aesFailed).toFloat() / aesTotal
    }

    /**
     * Тело запроса ключей к DJI Open API (как KeychainsRequest в эталоне).
     * [department] / [requestVersion] — переопределение значений из лога.
     */
    fun keychainsRequestJson(department: Int = this.department, requestVersion: Int = auxVersion): String {
        val keychains = ArrayList<List<Map<String, Any?>>>()
        var cur = ArrayList<Map<String, Any?>>()
        if (version >= 13) records { r ->
            when (r.type) {
                KEY_STORAGE -> if (r.data.size >= 4) {
                    val bb = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
                    val fp = bb.getShort(0).toInt() and 0xFFFF
                    val n = minOf(bb.getShort(2).toInt() and 0xFFFF, r.data.size - 4)
                    // Неизвестный feature point эталон не разбирает как KeyStorage — пропускаем.
                    FEATURE_NAMES[fp]?.let { name ->
                        cur.add(linkedMapOf(
                            "featurePoint" to name,
                            "aesCiphertext" to Base64.getEncoder().encodeToString(r.data.copyOfRange(4, 4 + n)),
                        ))
                    }
                }
                KEY_STORAGE_RECOVER -> { keychains.add(cur); cur = ArrayList() }
            }
        }
        keychains.add(cur)
        return MiniJson.stringify(linkedMapOf("version" to requestVersion, "department" to department, "keychainsArray" to keychains))
    }

    /** Сводка для диагностики (без координат и данных полёта). */
    fun diagnostics(): String = "FlightRecord v$version, aux v$auxVersion, приложение (department) $department, " +
        "${details.productName.ifEmpty { "модель ?" }}, приложение ${details.appVersion}"

    private fun u8(p: Int) = b.get(p).toInt() and 0xFF
    private fun u16(p: Int) = b.getShort(p).toInt() and 0xFFFF
    private fun bytes(p: Int, n: Int) = ByteArray(n) { b.get(p + it) }

    /** Снимает XOR: первый байт области — seed, остальное — данные. */
    private fun decodeXor(p: Int, len: Int, type: Int): ByteArray {
        if (len <= 1) return ByteArray(0)
        val seed = u8(p)
        val key = xorKey(seed, type)
        return ByteArray(len - 1) { (b.get(p + 1 + it).toInt() xor key[it % 8].toInt()).toByte() }
    }

    companion object {
        const val OSD = 1
        const val HOME = 2
        const val CUSTOM = 5
        const val CENTER_BATTERY = 7
        const val SMART_BATTERY = 8
        const val APP_TIP = 9
        const val APP_WARN = 10
        const val RECOVER = 13
        const val SMART_BATTERY_GROUP = 22
        const val APP_SERIOUS_WARN = 24
        const val KEY_STORAGE_RECOVER = 50
        const val KEY_STORAGE = 56

        const val PLAINTEXT = 8

        val FEATURE_NAMES = mapOf(
            1 to "FR_Standardization_Feature_Base_1", 2 to "FR_Standardization_Feature_Vision_2",
            3 to "FR_Standardization_Feature_Waypoint_3", 4 to "FR_Standardization_Feature_Agriculture_4",
            5 to "FR_Standardization_Feature_AirLink_5", 6 to "FR_Standardization_Feature_AfterSales_6",
            7 to "FR_Standardization_Feature_DJIFlyCustom_7", 8 to "FR_Standardization_Feature_Plaintext_8",
            9 to "FR_Standardization_Feature_FlightHub_9", 10 to "FR_Standardization_Feature_Gimbal_10",
            11 to "FR_Standardization_Feature_RC_11", 12 to "FR_Standardization_Feature_Camera_12",
            13 to "FR_Standardization_Feature_Battery_13", 14 to "FR_Standardization_Feature_FlySafe_14",
            15 to "FR_Standardization_Feature_Security_15",
        )
        val FEATURE_IDS = FEATURE_NAMES.entries.associate { (k, v) -> v to k }

        /** Какой ключ (feature point) шифрует запись данного типа. */
        fun featurePoint(type: Int, version: Int): Int {
            val v13 = version == 13
            return when (type) {
                1, 2, 6, 13, 14, 15, 40, 58, 59, 63 -> 1
                3 -> if (v13) 1 else 10
                4, 11, 29, 33 -> if (v13) 1 else 11
                5, 9, 10, 20, 24, 30, 54 -> 7
                7, 8 -> if (v13) 1 else 13
                12, 16, 19, 26, 27 -> 6
                17, 18 -> 2
                21, 41, 43, 44, 45, 46, 47, 48 -> 4
                22 -> if (v13) 6 else 13
                25 -> if (v13) 1 else 12
                28, 51, 52 -> if (v13) 6 else 14
                31, 32, 34, 35, 36, 38, 39 -> 3
                49 -> 5
                53 -> if (v13) 6 else 9
                55 -> 15
                62 -> 11
                else -> PLAINTEXT
            }
        }

        // CRC-64/Jones (Redis, crate crc64): отражённый, без финального XOR.
        private val CRC_TABLE = LongArray(256) { i ->
            var c = i.toLong()
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor -0x6a536cd653b4364bL else c ushr 1 }
            c
        }

        internal fun crc64(init: Long, data: ByteArray): Long {
            var crc = init
            for (x in data) crc = CRC_TABLE[((crc xor x.toLong()) and 0xFF).toInt()] xor (crc ushr 8)
            return crc
        }

        internal fun xorKey(seed: Int, type: Int): ByteArray {
            val magic = 0x123456789ABCDEF0L * seed
            val m = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(magic).array()
            val k = crc64(((seed + type) and 0xFF).toLong(), m)
            return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(k).array()
        }

        private fun decryptAes(ct: ByteArray, iv: ByteArray, key: ByteArray): ByteArray = try {
            if (ct.isEmpty() || ct.size % 16 != 0) ByteArray(0)
            else Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(ct)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }

        /** Похоже ли начало файла на DJI FlightRecord. */
        fun looksLikeDji(head: ByteArray): Boolean {
            if (head.size < 16) return false
            val v = head[10].toInt() and 0xFF
            val off = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            return v in 1..20 && off >= 0 && (v >= 13 || off > 12)
        }
    }
}

/** Сведения о полёте из заголовка DJI-лога. */
data class DjiDetails(
    val startTimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val totalDistance: Float,
    val totalTime: Float,
    val maxHeight: Float,
    val takeOffAltitude: Float,
    val productType: Int,
    val aircraftName: String,
    val aircraftSn: String,
    val appPlatform: Int,
    val appVersion: String,
) {
    val productName: String get() = PRODUCTS[productType] ?: if (productType == 0) "" else "DJI ($productType)"

    companion object {
        fun parse(raw: ByteArray, version: Int): DjiDetails {
            val d = ByteBuffer.wrap(raw.copyOf(maxOf(raw.size, 400))).order(ByteOrder.LITTLE_ENDIAN)
            fun str(at: Int, n: Int): String {
                var e = 0
                while (e < n && d.get(at + e) != 0.toByte()) e++
                return String(ByteArray(e) { d.get(at + it) }, Charsets.UTF_8).trim()
            }
            val old = version <= 5
            return DjiDetails(
                startTimeMs = d.getLong(91),
                longitude = d.getDouble(99),
                latitude = d.getDouble(107),
                totalDistance = d.getFloat(115),
                totalTime = d.getInt(119) / 1000f,
                maxHeight = d.getFloat(123),
                takeOffAltitude = d.getFloat(if (old) 352 else 267),
                productType = d.get(if (old) 277 else 271).toInt() and 0xFF,
                aircraftName = str(if (old) 278 else 280, if (old) 24 else 32),
                aircraftSn = str(if (old) 267 else 312, if (old) 10 else 16),
                appPlatform = d.get(if (old) 348 else 376).toInt() and 0xFF,
                appVersion = (if (old) 349 else 377).let { "${d.get(it).toInt() and 0xFF}.${d.get(it + 1).toInt() and 0xFF}.${d.get(it + 2).toInt() and 0xFF}" },
            )
        }

        val PRODUCTS = mapOf(
            1 to "Inspire 1", 2 to "Phantom 3 Standard", 3 to "Phantom 3 Advanced", 4 to "Phantom 3 Pro", 5 to "Osmo",
            6 to "Matrice 100", 7 to "Phantom 4", 8 to "LB2", 9 to "Inspire 1 Pro", 10 to "A3", 11 to "Matrice 600",
            12 to "Phantom 3 4K", 13 to "Mavic Pro", 14 to "Zenmuse XT", 15 to "Inspire 1 RAW", 16 to "A2",
            17 to "Inspire 2", 18 to "Osmo Pro", 19 to "Osmo Raw", 20 to "Osmo+", 21 to "Mavic", 22 to "Osmo Mobile",
            23 to "Orange CV600", 24 to "Phantom 4 Pro", 25 to "N3", 26 to "Spark", 27 to "Matrice 600 Pro",
            28 to "Phantom 4 Advanced", 29 to "Phantom 3 SE", 30 to "AG405", 31 to "Matrice 200", 33 to "Matrice 210",
            34 to "Matrice 210 RTK", 38 to "Mavic Air", 42 to "Mavic 2", 44 to "Phantom 4 Pro V2", 46 to "Phantom 4 RTK",
            57 to "Phantom 4 Multispectral", 58 to "Mavic 2 Enterprise", 59 to "Mavic Mini", 60 to "Matrice 200 V2",
            61 to "Matrice 210 V2", 62 to "Matrice 210 RTK V2", 67 to "Mavic Air 2", 70 to "Matrice 300 RTK",
            73 to "FPV", 75 to "Air 2S", 76 to "Mini 2", 77 to "Mavic 3", 96 to "Mini SE", 103 to "Mini 3 Pro",
            111 to "Mavic 3 Pro", 113 to "Mini 2 SE", 116 to "Matrice 30", 118 to "Mavic 3 Enterprise", 121 to "Avata",
            126 to "Mini 4 Pro", 152 to "Avata 2", 170 to "Matrice 350 RTK",
        )
    }
}
