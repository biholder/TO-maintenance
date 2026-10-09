package app.flightlog.core

/** Формат файла лога. */
enum class LogFormat(val ext: String, val source: String) {
    DATAFLASH("bin", "ArduPilot"),
    DATAFLASH_TEXT("log", "ArduPilot"),
    TLOG("tlog", "MAVLink"),
    CSV("csv", "CSV"),
    DJI("txt", "DJI"),
}

/** Временной ряд одного канала: время в секундах от начала лога. */
class Series(
    val key: String,
    val label: String,
    val message: String,
    val unit: String,
    val time: FloatArray,
    val values: FloatArray,
) {
    val size: Int get() = time.size
    val min: Float by lazy { values.minOrNull() ?: 0f }
    val max: Float by lazy { values.maxOrNull() ?: 0f }

    /** Значение в момент [t] (последнее известное, без интерполяции). */
    fun valueAt(t: Float): Float? {
        if (time.isEmpty() || t < time[0]) return null
        return values[indexAt(t)]
    }

    fun indexAt(t: Float): Int {
        var lo = 0
        var hi = time.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (time[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Средняя частота записи, Гц. */
    val rateHz: Float
        get() = if (size < 2) 0f else (size - 1) / (time.last() - time.first()).coerceAtLeast(1e-3f)
}

/** Трек: точки GPS с 3D-фиксом. */
class Track(
    val time: FloatArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val alt: FloatArray,
    val speed: FloatArray,
) {
    val size: Int get() = time.size
    fun indexAt(t: Float): Int {
        var lo = 0
        var hi = time.size - 1
        if (hi < 0) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (time[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }
}

enum class EventKind { INFO, MODE, WARN }

data class LogEvent(
    val time: Float,
    val kind: EventKind,
    val title: String,
    val detail: String,
    val source: String,
)

data class ModeChange(val time: Float, val name: String)

data class Param(val name: String, val value: Float, val default: Float?) {
    val changed: Boolean get() = default != null && !nearlyEqual(value, default)
}

data class VehicleInfo(
    val autopilot: String = "",
    val vehicleType: String = "",
    val firmware: String = "",
    val board: String = "",
    val frame: String = "",
    val gps: String = "",
)

/** Полностью разобранный полёт. */
class FlightLog(
    val fileName: String,
    val fileSize: Long,
    val format: LogFormat,
    val vehicle: VehicleInfo,
    /** UTC начала лога (мс), если известно из GPS. */
    val startUtcMillis: Long?,
    /** Длина шкалы времени, с. */
    val duration: Float,
    val series: Map<String, Series>,
    val track: Track,
    val events: List<LogEvent>,
    val modes: List<ModeChange>,
    val params: List<Param>,
    val armTime: Float?,
    val disarmTime: Float?,
    val messageCount: Long,
) {
    fun modeAt(t: Float): String = modes.lastOrNull { it.time <= t }?.name ?: "—"
    fun isArmedAt(t: Float): Boolean =
        armTime != null && t >= armTime && (disarmTime == null || t < disarmTime)
}

/** Стандартные ключи каналов — одинаковые для всех форматов. */
object Ch {
    const val ALT = "alt"
    const val SPD = "spd"
    const val VOLT = "volt"
    const val CURR = "curr"
    const val MAH = "mah"
    const val VIBE_X = "vibeX"
    const val VIBE_Y = "vibeY"
    const val VIBE_Z = "vibeZ"
    const val CLIP = "clip"
    const val SATS = "nsats"
    const val HDOP = "hdop"
    const val ROLL = "roll"
    const val PITCH = "pitch"
    const val YAW = "yaw"
    const val CLIMB = "climb"
    const val REM = "rem"
}

internal fun nearlyEqual(a: Float, b: Float): Boolean =
    kotlin.math.abs(a - b) <= 1e-5f * maxOf(1f, kotlin.math.abs(a), kotlin.math.abs(b))

/** Колбэк прогресса разбора: этап (индекс) и доля 0..1 общего прогресса. */
fun interface ProgressListener {
    fun onProgress(stage: Int, fraction: Float)
}

open class LogParseException(message: String) : Exception(message)
