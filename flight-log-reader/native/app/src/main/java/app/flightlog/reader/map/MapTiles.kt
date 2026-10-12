package app.flightlog.reader.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Подложка трека: источник растровых тайлов. */
enum class MapLayer(val title: String, val url: String?, val maxZoom: Int, val attribution: String) {
    NONE("Нет", null, 0, ""),
    SCHEME("Схема", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19, "© участники OpenStreetMap"),
    SATELLITE("Спутник", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19,
        "© Esri, Maxar, Earthstar Geographics"),
    TERRAIN("Рельеф", "https://tile.opentopomap.org/{z}/{x}/{y}.png", 17, "© OpenStreetMap, SRTM · OpenTopoMap (CC-BY-SA)"),
}

/**
 * Загрузка и кэш тайлов: в памяти (LRU) и на диске (cacheDir/tiles), так что
 * просмотренные места открываются без сети. Загруженный тайл увеличивает
 * [revision] — Compose перерисовывает карту.
 */
object MapTiles {
    private const val MAX_DISK_BYTES = 150L shl 20
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private const val USER_AGENT = "PLOV/0.3 (Android flight log viewer; https://github.com/biholder/TO-maintenance)"

    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val net = Semaphore(4)
    private val memory = object : LruCache<String, ImageBitmap>(96) {}
    private val loading = HashSet<String>()
    private val failed = HashMap<String, Long>()

    /** Счётчик загруженных тайлов: читается при рисовании, чтобы карта обновлялась. */
    val revision = mutableIntStateOf(0)
    /** Последние попытки загрузки завершились ошибкой (нет сети). */
    val offline = mutableIntStateOf(0)

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.cacheDir, "tiles").apply { mkdirs() }
        scope.launch { trim() }
    }

    private fun key(layer: MapLayer, z: Int, x: Int, y: Int) = "${layer.name}/$z/$x/$y"

    /** Тайл только из памяти, без загрузки. */
    fun peek(layer: MapLayer, z: Int, x: Int, y: Int): ImageBitmap? = if (z < 1) null else memory.get(key(layer, z, x, y))

    /** Тайл из памяти; если его нет — запускает загрузку и возвращает null. */
    fun get(layer: MapLayer, z: Int, x: Int, y: Int): ImageBitmap? {
        val k = key(layer, z, x, y)
        memory.get(k)?.let { return it }
        synchronized(loading) {
            if (k in loading) return null
            val f = failed[k]
            if (f != null && System.currentTimeMillis() - f < 30_000) return null
            loading += k
        }
        scope.launch {
            val bmp = try { load(layer, z, x, y)?.asImageBitmap() } catch (_: Throwable) { null }
            synchronized(loading) {
                loading -= k
                if (bmp == null) failed[k] = System.currentTimeMillis() else failed -= k
            }
            if (bmp != null) {
                memory.put(k, bmp)
                offline.intValue = 0
                revision.intValue++
            } else offline.intValue++
        }
        return null
    }

    /** Тайл для PDF: синхронно, из кэша или сети; null — недоступен. */
    fun bitmapBlocking(layer: MapLayer, z: Int, x: Int, y: Int): Bitmap? =
        try { runBlocking { load(layer, z, x, y) } } catch (_: Throwable) { null }

    private suspend fun load(layer: MapLayer, z: Int, x: Int, y: Int): Bitmap? {
        val file = dir?.let { File(it, "${layer.name}/$z/$x/$y") }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val url = layer.url?.replace("{z}", "$z")?.replace("{x}", "$x")?.replace("{y}", "$y") ?: return null
        val bytes = net.withPermit {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", USER_AGENT)
                if (c.responseCode != 200) return@withPermit null
                c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        if (bytes == null) {
            // Без сети — хотя бы устаревший тайл с диска.
            return file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (file != null) runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return bmp
    }

    /** Ограничение дискового кэша: удаляются самые старые тайлы. */
    private fun trim() {
        val root = dir ?: return
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }
}
