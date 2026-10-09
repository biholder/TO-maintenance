package app.flightlog.reader

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flightlog.core.Ch
import app.flightlog.core.LogFormat
import app.flightlog.core.LogReader
import app.flightlog.reader.data.LogEntry
import app.flightlog.reader.data.LogRepository
import app.flightlog.reader.export.ExportFormat
import app.flightlog.reader.export.Exporter
import app.flightlog.reader.export.PdfSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { LIBRARY, DECODE, FLIGHT, COMPARE, EXPORT }
enum class FlightTab(val title: String) { SUMMARY("Сводка"), TRACK("Трек"), CHARTS("Графики"), EVENTS("События"), PARAMS("PARM") }
enum class TrackBy(val title: String) { SPEED("Скорость"), ALT("Высота"), BATTERY("Батарея") }
enum class EvFilter(val title: String) { ALL("Все"), WARN("Предупреждения"), MODES("Режимы") }

class DecodeState(
    val fileName: String,
    val sizeBytes: Long,
    val source: String,
    val stages: List<String>,
    val current: Int = 0,
    val results: Map<Int, String> = emptyMap(),
    val progress: Float = 0f,
    val mbPerSec: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
    val entryId: String? = null,
    val formatTitle: String = "",
) {
    fun copy(
        current: Int = this.current, results: Map<Int, String> = this.results, progress: Float = this.progress,
        mbPerSec: Float = this.mbPerSec, done: Boolean = this.done, error: String? = this.error,
        entryId: String? = this.entryId, formatTitle: String = this.formatTitle, stages: List<String> = this.stages,
    ) = DecodeState(fileName, sizeBytes, source, stages, current, results, progress, mbPerSec, done, error, entryId, formatTitle)
}

/** Понятный текст ошибки; нехватка памяти не роняет приложение, а показывается пользователю. */
fun errorText(e: Throwable): String = when (e) {
    is OutOfMemoryError -> "недостаточно памяти устройства для этого лога. Закройте другие приложения и попробуйте снова"
    else -> e.message ?: e.javaClass.simpleName
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = LogRepository(app)

    // Навигация.
    val stack = mutableStateListOf(Screen.LIBRARY)
    val screen: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (sheet) { sheet = false; return true }
        if (stack.size <= 1) {
            if (compareMode) { compareMode = false; picked.clear(); return true }
            return false
        }
        val top = stack.removeAt(stack.lastIndex)
        if (top == Screen.FLIGHT) { playing = false; playJob?.cancel() }
        if (top == Screen.DECODE) decodeJob?.cancel()
        return true
    }

    // Настройки.
    var themeOverride by mutableStateOf<Boolean?>(null)
    var units by mutableStateOf(Units.METRIC)

    // Библиотека.
    var entries by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var srcFilter by mutableStateOf<String?>(null)
    var compareMode by mutableStateOf(false)
    val picked = mutableStateListOf<String>()
    var sheet by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2000); toast = null }
    }

    init { reload() }

    fun reload() {
        viewModelScope.launch { entries = withContext(Dispatchers.IO) { repo.list() } }
    }

    fun togglePick(id: String) {
        if (id in picked) picked.remove(id) else {
            if (picked.size >= 2) picked.removeAt(0)
            picked.add(id)
        }
    }

    fun delete(e: LogEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(e) }
            picked.remove(e.id)
            reload()
            showToast("Лог ${e.fileName} удалён")
        }
    }

    // Импорт и расшифровка.
    var decode by mutableStateOf<DecodeState?>(null)
        private set
    private var decodeJob: Job? = null

    fun importUri(uri: Uri) = startImport("Память телефона") { repo.stage(uri) }

    fun importDemo() {
        sheet = false
        viewModelScope.launch {
            val assets = repo.demoAssets()
            val existing = entries.map { it.fileName }.toSet()
            val todo = assets.filter { it.substringAfterLast('/') !in existing }
            if (todo.isEmpty()) { showToast("Демо-полёты уже в библиотеке"); return@launch }
            withContext(Dispatchers.IO) { todo.forEach { a -> repo.stageAsset(a).let { (f, n) -> repo.import(f, n, null) } } }
            reload()
            showToast("Добавлено демо-полётов: ${todo.size}")
        }
    }

    private fun startImport(source: String, stage: () -> Pair<File, String>) {
        sheet = false
        decodeJob?.cancel()
        decode = DecodeState("…", 0, source, LogReader.stages(LogFormat.DATAFLASH))
        if (screen != Screen.DECODE) push(Screen.DECODE)
        decodeJob = viewModelScope.launch {
            try {
                val (file, name) = withContext(Dispatchers.IO) { stage() }
                val head = LogReader.head(file)
                val fmt = LogReader.detect(name, head)
                decode = DecodeState(name, file.length(), source, LogReader.stages(fmt ?: LogFormat.DATAFLASH))
                val started = System.nanoTime()
                val listener = LogReader.Listener(
                    onStage = { i, result ->
                        val d = decode ?: return@Listener
                        decode = if (result == null) d.copy(current = i) else d.copy(current = i, results = d.results + (i to result))
                    },
                    onProgress = { p ->
                        val d = decode ?: return@Listener
                        val sec = (System.nanoTime() - started) / 1e9f
                        decode = d.copy(progress = p, mbPerSec = if (sec > 0.05f) d.sizeBytes * p / 1048576f / sec else d.mbPerSec)
                    },
                )
                val (entry, parsed) = withContext(Dispatchers.Default) { repo.import(file, name, listener) }
                val d = decode!!
                decode = d.copy(
                    current = d.stages.size, progress = 1f, done = true, entryId = entry.id,
                    formatTitle = when (parsed.log.format) {
                        LogFormat.DATAFLASH -> "ArduPilot DataFlash · ${parsed.log.vehicle.firmware.ifEmpty { "прошивка не определена" }}"
                        LogFormat.DATAFLASH_TEXT -> "ArduPilot, текстовый лог · ${parsed.log.vehicle.firmware.ifEmpty { "прошивка не определена" }}"
                        LogFormat.TLOG -> "MAVLink telemetry · ${parsed.log.vehicle.autopilot}"
                    },
                )
                reload()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                decode = decode?.copy(error = errorText(e))
            }
        }
    }

    /** «ОТКРЫТЬ ПОЛЁТ» после расшифровки: заменяет экран расшифровки полётом. */
    fun openDecoded() {
        val id = decode?.entryId ?: return
        val e = repo.list().firstOrNull { it.id == id } ?: return
        stack.removeAt(stack.lastIndex)
        openFlight(e)
    }

    // Полёт.
    var flightEntry by mutableStateOf<LogEntry?>(null)
        private set
    var flight by mutableStateOf<LogReader.Parsed?>(null)
        private set
    var flightError by mutableStateOf<String?>(null)
        private set
    var tab by mutableStateOf(FlightTab.SUMMARY)
    var t by mutableStateOf(0f)
    var playing by mutableStateOf(false)
        private set
    var speed by mutableStateOf(10)
        private set
    val channels = mutableStateListOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.VIBE_Z)
    var evFilter by mutableStateOf(EvFilter.ALL)
    var paramQuery by mutableStateOf("")
    var onlyChanged by mutableStateOf(false)
    var trackBy by mutableStateOf(TrackBy.SPEED)
    private var playJob: Job? = null

    fun openFlight(e: LogEntry) {
        playJob?.cancel()
        playing = false
        flightEntry = e
        flightError = null
        tab = FlightTab.SUMMARY
        t = 0f
        flight = repo.cached(e.id)
        push(Screen.FLIGHT)
        if (flight == null) viewModelScope.launch {
            try {
                flight = withContext(Dispatchers.Default) { repo.open(e) }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                flightError = errorText(ex)
            }
        }
    }

    val duration: Float get() = flight?.log?.duration?.coerceAtLeast(1f) ?: 1f
    val tSec: Float get() = t * duration

    fun seekSec(sec: Float) { t = (sec / duration).coerceIn(0f, 1f) }

    fun togglePlay() {
        if (playing) { playing = false; playJob?.cancel(); return }
        if (t >= 1f) t = 0f
        playing = true
        playJob = viewModelScope.launch {
            while (isActive && playing) {
                delay(50)
                t = (t + speed * 0.05f / duration).coerceAtMost(1f)
                if (t >= 1f) { playing = false }
            }
        }
    }

    fun cycleSpeed() { speed = when (speed) { 10 -> 30; 30 -> 60; else -> 10 } }

    fun toggleChannel(key: String) { if (key in channels) channels.remove(key) else channels.add(key) }

    /** Переход из карточки проблемы к графику. */
    fun jumpTo(sec: Float, channel: String?) {
        seekSec(sec)
        if (channel != null && channel !in channels) channels.add(0, channel)
        tab = FlightTab.CHARTS
    }

    // Сравнение.
    var compareA by mutableStateOf<Pair<LogEntry, LogReader.Parsed>?>(null)
        private set
    var compareB by mutableStateOf<Pair<LogEntry, LogReader.Parsed>?>(null)
        private set
    var cmpCh by mutableStateOf(0)

    fun startCompare() {
        if (picked.size < 2) return
        val es = picked.mapNotNull { id -> entries.firstOrNull { it.id == id } }
        if (es.size < 2) return
        compareA = null; compareB = null
        push(Screen.COMPARE)
        viewModelScope.launch {
            try {
                val (a, b) = withContext(Dispatchers.Default) { repo.open(es[0]) to repo.open(es[1]) }
                compareA = es[0] to a
                compareB = es[1] to b
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                showToast("Не удалось открыть: ${errorText(ex)}")
                back()
            }
        }
    }

    // Экспорт.
    var exFmt by mutableStateOf(ExportFormat.PDF)
    val exSec = mutableStateListOf(*PdfSection.entries.toTypedArray())
    var exporting by mutableStateOf(false)
        private set

    fun toggleSection(s: PdfSection) { if (s in exSec) exSec.remove(s) else exSec.add(s) }

    fun exportFileName(): String {
        val base = flight?.log?.fileName?.substringBeforeLast('.') ?: "flight"
        return "$base.${exFmt.ext}"
    }

    fun export(share: Boolean, onShare: (File, String) -> Unit) {
        val p = flight ?: return
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val file = withContext(Dispatchers.Default) {
                    Exporter.write(ctx, p, exFmt, exSec.toSet(), exportFileName(), units)
                }
                if (share) onShare(file, exFmt.mime)
                else {
                    withContext(Dispatchers.IO) { Exporter.saveToDownloads(ctx, file, exFmt.mime) }
                    showToast("${file.name} сохранён в «Загрузки»")
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                showToast("Ошибка экспорта: ${errorText(ex)}")
            } finally {
                exporting = false
            }
        }
    }
}
