package app.flightlog.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.flightlog.core.Analysis
import app.flightlog.core.FlightLog
import app.flightlog.core.LogFormat
import app.flightlog.core.LogReader
import app.flightlog.core.Severity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Запись библиотеки: метаданные полёта без самих каналов. */
data class LogEntry(
    val id: String,
    val fileName: String,
    val format: LogFormat,
    val size: Long,
    val importedAt: Long,
    val startUtc: Long?,
    val autopilot: String,
    val vehicleType: String,
    val board: String,
    val firmware: String,
    val flightTime: Float,
    val distanceM: Double,
    val status: Severity,
    val statusText: String,
    val issues: Int,
) {
    val sortTime: Long get() = startUtc ?: importedAt

    fun toJson() = JSONObject().apply {
        put("id", id); put("fileName", fileName); put("format", format.name); put("size", size)
        put("importedAt", importedAt); startUtc?.let { put("startUtc", it) }
        put("autopilot", autopilot); put("vehicleType", vehicleType); put("board", board); put("firmware", firmware)
        put("flightTime", flightTime.toDouble()); put("distanceM", distanceM)
        put("status", status.name); put("statusText", statusText); put("issues", issues)
    }

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            id = o.getString("id"), fileName = o.getString("fileName"),
            format = LogFormat.valueOf(o.getString("format")), size = o.getLong("size"),
            importedAt = o.getLong("importedAt"), startUtc = if (o.has("startUtc")) o.getLong("startUtc") else null,
            autopilot = o.optString("autopilot"), vehicleType = o.optString("vehicleType"),
            board = o.optString("board"), firmware = o.optString("firmware"),
            flightTime = o.optDouble("flightTime", 0.0).toFloat(), distanceM = o.optDouble("distanceM", 0.0),
            status = Severity.valueOf(o.optString("status", "OK")), statusText = o.optString("statusText"),
            issues = o.optInt("issues"),
        )

        fun of(id: String, size: Long, importedAt: Long, log: FlightLog, a: Analysis) = LogEntry(
            id = id, fileName = log.fileName, format = log.format, size = size, importedAt = importedAt,
            startUtc = log.startUtcMillis, autopilot = log.vehicle.autopilot, vehicleType = log.vehicle.vehicleType,
            board = log.vehicle.board, firmware = log.vehicle.firmware, flightTime = a.summary.flightTime,
            distanceM = a.summary.distanceM, status = a.status, statusText = a.statusText, issues = a.issues.size,
        )
    }
}

/**
 * Хранилище логов: копии файлов в filesDir/logs и индекс index.json.
 * Разобранные полёты кэшируются в памяти (последние несколько).
 */
class LogRepository(private val context: Context) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val cache = object : LinkedHashMap<String, LogReader.Parsed>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LogReader.Parsed>?) = size > 2
    }

    @Synchronized
    fun list(): List<LogEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { LogEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.sortTime }
    }

    @Synchronized
    private fun save(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }

    fun fileOf(e: LogEntry) = File(dir, "${e.id}.${e.format.ext}")

    /** Копирует выбранный файл во временный, возвращает его и имя. */
    fun stage(uri: Uri): Pair<File, String> {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "log.bin"
        val tmp = File(context.cacheDir, "import-${UUID.randomUUID()}")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp to name
    }

    fun stageAsset(path: String): Pair<File, String> {
        val tmp = File(context.cacheDir, "import-${UUID.randomUUID()}")
        context.assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp to path.substringAfterLast('/')
    }

    /** Разбирает подготовленный файл и добавляет в библиотеку. */
    fun import(staged: File, name: String, listener: LogReader.Listener?): Pair<LogEntry, LogReader.Parsed> {
        try {
            val parsed = LogReader.read(staged, name, listener)
            val entry = LogEntry.of(UUID.randomUUID().toString(), staged.length(), System.currentTimeMillis(), parsed.log, parsed.analysis)
            // Перенос, а не копия: большие логи не должны временно занимать место дважды.
            val dst = fileOf(entry)
            if (!staged.renameTo(dst)) staged.copyTo(dst, overwrite = true)
            synchronized(this) {
                save(list() + entry)
                cache[entry.id] = parsed
            }
            return entry to parsed
        } finally {
            staged.delete()
        }
    }

    fun open(e: LogEntry): LogReader.Parsed {
        synchronized(this) { cache[e.id]?.let { return it } }
        val p = LogReader.read(fileOf(e), e.fileName)
        synchronized(this) { cache[e.id] = p }
        return p
    }

    fun cached(id: String): LogReader.Parsed? = synchronized(this) { cache[id] }

    @Synchronized
    fun delete(e: LogEntry) {
        fileOf(e).delete()
        cache.remove(e.id)
        save(list().filter { it.id != e.id })
    }

    fun demoAssets(): List<String> = context.assets.list("demo")?.map { "demo/$it" }?.sorted() ?: emptyList()
}
