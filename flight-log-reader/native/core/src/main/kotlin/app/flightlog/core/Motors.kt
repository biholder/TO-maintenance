package app.flightlog.core

/**
 * Выходы на моторы мультикоптера (RCOU в DataFlash, SERVO_OUTPUT_RAW в MAVLink).
 *
 * Каналы лежат в [FlightLog.series] под ключами `mot<N>`, N — номер мотора с единицы,
 * значения — ШИМ, мкс.
 */
object Motors {
    fun key(motor: Int) = "mot$motor"

    fun label(motor: Int) = "Мотор $motor"

    /** Номера моторов, для которых в логе есть каналы. */
    fun motors(log: FlightLog): List<Int> =
        log.series.keys.mapNotNull { k -> if (k.startsWith("mot")) k.substring(3).toIntOrNull() else null }.sorted()

    /** Вертолёт: выходы — сервоприводы автомата перекоса, сравнивать их как моторы нельзя. */
    fun isHeli(params: Map<String, Float>, frame: String, firmware: String): Boolean =
        params.keys.any { it.startsWith("H_RSC_") || it.startsWith("H_SW_") } ||
            frame.contains("HELI", ignoreCase = true) || firmware.contains("Heli", ignoreCase = true)

    /**
     * Номер выхода (с единицы) → номер мотора. По параметрам SERVOn_FUNCTION (33…40 — моторы 1…8,
     * 82…85 — 9…12); если параметров нет — по типу рамы из сообщения «Frame:».
     */
    fun outputs(params: Map<String, Float>, frame: String, vehicleType: String, heli: Boolean): Map<Int, Int> {
        if (heli) return emptyMap()
        val out = sortedMapOf<Int, Int>()
        for (n in 1..32) {
            val f = params["SERVO${n}_FUNCTION"]?.toInt() ?: continue
            when (f) {
                in 33..40 -> out[n] = f - 32
                in 82..85 -> out[n] = f - 73
            }
        }
        if (out.isNotEmpty() || params.keys.any { it.startsWith("SERVO") && it.endsWith("_FUNCTION") }) return out
        if (vehicleType != "Copter" && vehicleType != "") return out
        val count = frameMotors(frame) ?: return out
        return (1..count).associateWith { it }
    }

    private fun frameMotors(frame: String): Int? {
        val f = frame.uppercase()
        return when {
            "HELI" in f -> null
            "DODECA" in f -> 12
            "DECA" in f -> 10
            "OCTA" in f -> 8
            "HEXA" in f || "Y6" in f -> 6
            "QUAD" in f -> 4
            "TRI" in f -> 3
            else -> null
        }
    }

    /** Рабочий диапазон ШИМ мотора: MOT_PWM_MIN/MAX, иначе SERVOn_MIN/MAX выхода, иначе 1000…2000. */
    fun pwmRange(params: Map<String, Float>, output: Int?): Pair<Float, Float> {
        val lo = params["MOT_PWM_MIN"]?.takeIf { it > 0 } ?: output?.let { params["SERVO${it}_MIN"] }?.takeIf { it > 0 } ?: 1000f
        val hi = params["MOT_PWM_MAX"]?.takeIf { it > lo } ?: output?.let { params["SERVO${it}_MAX"] }?.takeIf { it > lo } ?: 2000f
        return lo to hi
    }

    /** Номер выхода из подписи канала («RCOU.C3», «SERVO_OUTPUT_RAW.servo3_raw» → 3). */
    fun outputOf(s: Series): Int? = OUTPUT_NUM.find(s.message)?.groupValues?.get(1)?.toIntOrNull()

    private val OUTPUT_NUM = Regex("""\.\D*(\d+)""")
}
