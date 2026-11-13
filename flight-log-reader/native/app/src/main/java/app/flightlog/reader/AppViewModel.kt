package app.flightlog.reader

import app.flightlog.reader.map.MapLayer
import app.flightlog.core.LogFields
import app.flightlog.reader.export.ParamMode
import app.flightlog.reader.export.ReportOptions
import app.flightlog.reader.map.MapTiles
import app.flightlog.reader.update.Release
import app.flightlog.reader.update.Updater
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

enum class Screen { LIBRARY, DECODE, FLIGHT, COMPARE, EXPORT, REPORT_CHARTS, REPORT_PARAMS, SETTINGS }
enum class FlightTab(val title: String) { SUMMARY("Сводка"), TRACK("Трек"), CHARTS("Графики"), EVENTS("События"), PARAMS("PARM") }
enum class TrackBy(val title: String) { SPEED("Скорость"), ALT("Высота"), BATTERY("Батарея"), CURRENT("Ток"), MODE("Режим") }
enum class EvFilter(val title: String) { ALL("Все"), WARN("Предупреждения"), MODES("Режимы") }

/** Что развёрнуто на весь экран: график канала, наложение или сравнение АКБ. */
sealed interface FullChart {
    data class Channel(val key: String) : FullChart
    data object Overlay : FullChart
    data object Batteries : FullChart
}

/** Графики отчёта по умолчанию. */
val DEFAULT_REPORT_CHARTS = listOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.CURR, Ch.VIBE_Z, Ch.SATS)

/** Скорости воспроизведения полёта. */
val SPEEDS = listOf(1, 2, 5, 10, 30, 60)
const val MAX_OVERLAY = 10

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
    /** Лог DJI v13+ зашифрован: нужен API-ключ DJI для получения ключей AES (номер версии лога). */
    val djiKeyVersion: Int? = null,
    val djiBusy: Boolean = false,
    /** Текущая попытка получения ключей DJI. */
    val djiStatus: String? = null,
    /** Полученные ключи DJI, если с ними лог не открылся, — чтобы отправить разработчику. */
    val djiKeysForReport: String? = null,
) {
    fun copy(
        current: Int = this.current, results: Map<Int, String> = this.results, progress: Float = this.progress,
        mbPerSec: Float = this.mbPerSec, done: Boolean = this.done, error: String? = this.error,
        entryId: String? = this.entryId, formatTitle: String = this.formatTitle, stages: List<String> = this.stages,
        djiKeyVersion: Int? = this.djiKeyVersion, djiBusy: Boolean = this.djiBusy, djiStatus: String? = this.djiStatus,
        djiKeysForReport: String? = this.djiKeysForReport,
    ) = DecodeState(fileName, sizeBytes, source, stages, current, results, progress, mbPerSec, done, error, entryId, formatTitle,
        djiKeyVersion, djiBusy, djiStatus, djiKeysForReport)
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
        if (editing != null) { editing = null; return true }
        if (fullChart != null) { fullChart = null; return true }
        if (stack.size <= 1) {
            if (compareMode) { compareMode = false; picked.clear(); return true }
            return false
        }
        val top = stack.removeAt(stack.lastIndex)
        if (top == Screen.FLIGHT) { playing = false; playJob?.cancel() }
        if (top == Screen.DECODE) {
            decodeJob?.cancel()
            pending?.first?.delete()
            pending = null
        }
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

    /** Запись, подписи которой сейчас редактируются (окно поверх экрана). */
    var editing by mutableStateOf<LogEntry?>(null)

    fun saveLabels(e: LogEntry, title: String, tail: String, note: String) {
        // Название, совпадающее с именем файла, не храним — пустое и так показывает имя файла.
        val t = title.trim().takeIf { it != e.fileName }.orEmpty()
        val updated = e.copy(title = t, tail = tail.trim(), note = note.trim())
        editing = null
        if (flightEntry?.id == e.id) flightEntry = updated
        compareA?.let { (ce, p) -> if (ce.id == e.id) compareA = updated to p }
        compareB?.let { (ce, p) -> if (ce.id == e.id) compareB = updated to p }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.update(updated) }
            reload()
        }
    }

    fun delete(e: LogEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(e) }
            picked.remove(e.id)
            reload()
            showToast("Лог ${e.displayName} удалён")
        }
    }

    // Импорт и расшифровка.
    var decode by mutableStateOf<DecodeState?>(null)
        private set
    private var decodeJob: Job? = null

    fun importUri(uri: Uri) = startImport("Память телефона") { repo.stage(uri) }

    /**
     * Файлы из «Поделиться» / «Открыть с помощью». Один файл — обычный экран
     * расшифровки; несколько — импорт в фоне с итогом во всплывающем сообщении.
     */
    fun importShared(uris: List<Uri>) {
        if (uris.size == 1) {
            // Импорт поверх открытых экранов: возвращаемся к библиотеке, затем расшифровка.
            while (stack.size > 1) back()
            startImport("Получено через «Поделиться»") { repo.stage(uris[0]) }
            return
        }
        sheet = false
        showToast("Импорт файлов: ${uris.size}…")
        viewModelScope.launch {
            var ok = 0
            var needKeys = 0
            val failed = ArrayList<String>()
            for (u in uris) {
                val staged = runCatching { withContext(Dispatchers.IO) { repo.stage(u) } }.getOrNull()
                if (staged == null) { failed += u.lastPathSegment ?: "?"; continue }
                try {
                    withContext(Dispatchers.Default) { repo.import(staged.first, staged.second, null) }
                    ok++
                } catch (e: app.flightlog.core.DjiMapper.KeychainsRequired) {
                    staged.first.delete()
                    needKeys++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed += staged.second
                }
            }
            reload()
            showToast(buildString {
                append("Импортировано: $ok из ${uris.size}")
                if (needKeys > 0) append(". Зашифрованные логи DJI ($needKeys) откройте по одному — нужен ключ DJI")
                if (failed.isNotEmpty()) append(". Не удалось: ${failed.take(3).joinToString()}")
            })
        }
    }

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

    // Настройки: API-ключ DJI Open API для зашифрованных логов DJI v13+.
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /**
     * Заставка уже показана. ViewModel живёт, пока приложение не закрыто: при сворачивании
     * и повороте экрана заставка не повторяется, при новом запуске — показывается снова.
     */
    var splashShown by mutableStateOf(false)
    var djiApiKey by mutableStateOf(prefs.getString("dji_api_key", "") ?: "")
        private set

    /** Подложка трека; выбор запоминается. */
    /** Плеер внизу экрана полёта свёрнут в узкую полосу. */
    var playerCollapsed by mutableStateOf(prefs.getBoolean("player_collapsed", false))
        private set

    fun togglePlayer() {
        playerCollapsed = !playerCollapsed
        prefs.edit().putBoolean("player_collapsed", playerCollapsed).apply()
    }

    // Обновления приложения из релизов GitHub.
    val appVersion: String = Updater.currentVersion(app)
    /** Проверять обновления автоматически (при запуске и раз в сутки в фоне) и уведомлять. */
    var updateAuto by mutableStateOf(prefs.getBoolean("update_auto", true))
        private set
    /** Последний релиз на GitHub, если он новее установленной версии. */
    var updateAvailable by mutableStateOf<Release?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set
    /** Результат последней проверки или ошибка для экрана настроек. */
    var updateMessage by mutableStateOf<String?>(null)
        private set
    /** Доля скачивания 0..1; null — не скачивается. */
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    private var updateFile: File? = null
    private var downloadJob: Job? = null

    fun setUpdateAutoCheck(on: Boolean) {
        updateAuto = on
        prefs.edit().putBoolean("update_auto", on).apply()
        Updater.schedule(getApplication(), on)
    }

    fun checkUpdates(manual: Boolean = true) {
        if (updateChecking) return
        updateChecking = true
        if (manual) updateMessage = null
        viewModelScope.launch {
            try {
                val r = Updater.latest()
                prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
                if (Updater.compare(r.version, appVersion) > 0) {
                    updateAvailable = r
                    updateMessage = null
                    if (!manual) Updater.notifyOnce(getApplication(), r)
                } else {
                    updateAvailable = null
                    updateMessage = "Установлена последняя версия"
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                if (manual) updateMessage = "Не удалось проверить: ${errorText(ex)}"
            } finally {
                updateChecking = false
            }
        }
    }

    /** Скачивает новую версию и запускает установку. */
    fun downloadAndInstall() {
        val r = updateAvailable ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            updateMessage = "Разрешите PLOV устанавливать приложения и нажмите «Установить» ещё раз"
            Updater.openInstallPermission(ctx)
            return
        }
        updateFile?.takeIf { it.exists() && it.name.contains(r.version) }?.let { Updater.install(ctx, it); return }
        if (downloadJob?.isActive == true) return
        updateMessage = null
        updateProgress = 0f
        downloadJob = viewModelScope.launch {
            try {
                val f = Updater.download(ctx, r) { p -> updateProgress = p }
                updateFile = f
                updateProgress = null
                Updater.install(ctx, f)
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                updateProgress = null
                updateMessage = "Ошибка обновления: ${errorText(ex)}"
            }
        }
    }

    /** Переход к настройкам из уведомления или плашки «Доступна версия». */
    fun openUpdates() {
        if (screen != Screen.SETTINGS) push(Screen.SETTINGS)
        if (updateAvailable == null) checkUpdates()
    }

    var mapLayer by mutableStateOf(
        prefs.getString("map_layer", null)?.let { n -> MapLayer.entries.firstOrNull { it.name == n } } ?: MapLayer.SCHEME)
        private set

    fun selectMapLayer(layer: MapLayer) {
        mapLayer = layer
        prefs.edit().putString("map_layer", layer.name).apply()
    }

    init {
        MapTiles.init(app)
        Updater.schedule(app, updateAuto)
        // При запуске — не чаще раза в 6 часов, молча: о новой версии скажет плашка и уведомление.
        if (updateAuto && System.currentTimeMillis() - prefs.getLong("update_checked_at", 0) > 6 * 3600_000L) checkUpdates(manual = false)
    }

    /** Подготовленный файл, ожидающий ключей DJI. */
    private var pending: Pair<File, String>? = null

    private fun startImport(source: String, stage: () -> Pair<File, String>) {
        sheet = false
        decodeJob?.cancel()
        pending?.first?.delete()
        pending = null
        decode = DecodeState("…", 0, source, LogReader.stages(LogFormat.DATAFLASH))
        if (screen != Screen.DECODE) push(Screen.DECODE)
        decodeJob = viewModelScope.launch {
            val staged = try {
                withContext(Dispatchers.IO) { stage() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                decode = decode?.copy(error = errorText(e))
                return@launch
            }
            runImport(staged.first, staged.second, source, null)
        }
    }

    /** Ключи DJI: запрос к DJI Open API и повтор импорта с ними. */
    fun fetchDjiKeys(apiKey: String) {
        val (file, name) = pending ?: return
        val d = decode ?: return
        val key = apiKey.trim()
        if (key.isEmpty()) return
        prefs.edit().putString("dji_api_key", key).apply()
        djiApiKey = key
        decode = d.copy(djiBusy = true, error = null)
        decodeJob = viewModelScope.launch {
            val keys = try {
                withContext(Dispatchers.IO) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val log = app.flightlog.core.DjiLog(raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length()))
                        app.flightlog.core.DjiKeychains.fetchMatching(key, log) { n, label ->
                            decode = decode?.copy(djiStatus = "Запрос ключей, попытка $n: $label")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                decode = decode?.copy(djiBusy = false, djiStatus = null, error = errorText(e))
                return@launch
            }
            decode = decode?.copy(djiKeyVersion = null, djiBusy = false, djiStatus = null)
            runImport(file, name, d.source, keys)
        }
    }

    private suspend fun runImport(file: File, name: String, source: String, djiKeys: String?) {
        run {
            try {
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
                val (entry, parsed) = withContext(Dispatchers.Default) { repo.import(file, name, listener, djiKeys) }
                pending = null
                val d = decode!!
                decode = d.copy(
                    current = d.stages.size, progress = 1f, done = true, entryId = entry.id,
                    formatTitle = when (parsed.log.format) {
                        LogFormat.DATAFLASH -> "ArduPilot DataFlash · ${parsed.log.vehicle.firmware.ifEmpty { "прошивка не определена" }}"
                        LogFormat.DATAFLASH_TEXT -> "ArduPilot, текстовый лог · ${parsed.log.vehicle.firmware.ifEmpty { "прошивка не определена" }}"
                        LogFormat.TLOG -> "MAVLink telemetry · ${parsed.log.vehicle.autopilot}"
                        LogFormat.CSV -> "Таблица CSV · ${parsed.log.vehicle.autopilot} · ${parsed.log.series.size} каналов"
                        LogFormat.DJI -> "DJI FlightRecord · ${parsed.log.vehicle.frame.ifEmpty { parsed.log.vehicle.board }}"
                        LogFormat.GEOSCAN -> "Журнал Геоскан · ${parsed.log.vehicle.vehicleType.lowercase()}" +
                            parsed.log.vehicle.firmware.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
                    },
                )
                reload()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.flightlog.core.DjiMapper.KeychainsRequired) {
                // Файл сохраняется до получения ключей.
                pending = file to name
                decode = decode?.copy(djiKeyVersion = e.version)
            } catch (e: Throwable) {
                decode = decode?.copy(error = errorText(e), djiKeysForReport = djiKeys)
            }
        }
    }

    /** Файл с ключами DJI для отчёта разработчику (ключи подходят только к этому логу, данных полёта нет). */
    fun djiKeysReportFile(): File? {
        val d = decode ?: return null
        val keys = d.djiKeysForReport ?: return null
        val f = File(app.flightlog.reader.export.Exporter.dir(getApplication()), d.fileName.substringBeforeLast('.') + ".dji-keys.json")
        f.writeText(keys)
        return f
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
    /** Каналы на общем графике «Наложение». */
    val overlay = mutableStateListOf<String>()
    /** Общая шкала на графике наложения (если единицы совпадают). */
    var overlayShared by mutableStateOf(true)
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
        fullChart = null
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

    fun cycleSpeed() { speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size] }

    fun toggleOverlay(key: String) {
        if (key in overlay) overlay.remove(key) else if (overlay.size < MAX_OVERLAY) overlay.add(key)
    }

    fun setOverlay(keys: List<String>) {
        overlay.clear(); overlay.addAll(keys.take(MAX_OVERLAY))
    }

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
    /** Метрика в сравнении АКБ одного полёта. */
    var batMetric by mutableStateOf(0)
    /** График, развёрнутый на весь экран (долгое нажатие). */
    var fullChart by mutableStateOf<FullChart?>(null)

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

    // Состав PDF-отчёта: графики (каналы PLOV или любые поля лога) и параметры. Запоминается между полётами.
    val reportCharts = mutableStateListOf<String>().apply {
        addAll(prefs.getString("report_charts", null)?.split('\n')?.filter { it.isNotEmpty() } ?: DEFAULT_REPORT_CHARTS)
    }
    var reportParamMode by mutableStateOf(
        prefs.getString("report_param_mode", null)?.let { n -> ParamMode.entries.firstOrNull { it.name == n } } ?: ParamMode.CHANGED)
        private set
    val reportParamNames = mutableStateListOf<String>().apply {
        addAll(prefs.getString("report_params", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
    }
    var chartQuery by mutableStateOf("")
    var reportParamQuery by mutableStateOf("")

    private fun saveReport() {
        prefs.edit()
            .putString("report_charts", reportCharts.joinToString("\n"))
            .putString("report_param_mode", reportParamMode.name)
            .putString("report_params", reportParamNames.joinToString("\n"))
            .apply()
    }

    fun toggleReportChart(key: String) {
        if (key in reportCharts) reportCharts.remove(key) else reportCharts.add(key)
        saveReport()
    }

    fun resetReportCharts() {
        reportCharts.clear(); reportCharts.addAll(DEFAULT_REPORT_CHARTS); saveReport()
    }

    fun chooseReportParamMode(m: ParamMode) {
        // Переход к выбору вручную с пустым списком — начать с того, что было в отчёте.
        if (m == ParamMode.SELECTED && reportParamNames.isEmpty()) selectParamsManually() else reportParamMode = m
        saveReport()
    }

    /** Выбор вручную, начиная с параметров текущего режима (изменённых или всех). */
    fun selectParamsManually() {
        val params = flight?.log?.params.orEmpty()
        if (reportParamMode != ParamMode.SELECTED) {
            reportParamNames.clear()
            reportParamNames.addAll(params.filter { reportParamMode == ParamMode.ALL || it.changed }.map { it.name })
        }
        reportParamMode = ParamMode.SELECTED
        saveReport()
    }

    fun toggleReportParam(name: String) {
        if (name in reportParamNames) reportParamNames.remove(name) else reportParamNames.add(name)
        reportParamMode = ParamMode.SELECTED
        saveReport()
    }

    fun clearReportParams() { reportParamNames.clear(); saveReport() }

    /** Все поля лога ArduPilot для выбора графиков (читаются по требованию). */
    var fieldCatalog by mutableStateOf<List<LogFields.Message>?>(null)
        private set
    var fieldCatalogError by mutableStateOf<String?>(null)
        private set
    private var catalogFor: String? = null

    fun loadFieldCatalog() {
        val e = flightEntry ?: return
        if (!LogFields.supported(e.format) || catalogFor == e.id) return
        catalogFor = e.id
        fieldCatalog = null
        fieldCatalogError = null
        viewModelScope.launch {
            try {
                fieldCatalog = withContext(Dispatchers.IO) { LogFields.catalog(repo.fileOf(e), e.format) }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                fieldCatalogError = errorText(ex)
                catalogFor = null
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
        // Файл называется по подписи полёта, если она есть.
        val base = flightEntry?.title?.takeIf { it.isNotBlank() }?.replace(Regex("""[\\/:*?"<>|\n]+"""), "_")?.take(80)
            ?: flight?.log?.fileName?.substringBeforeLast('.') ?: "flight"
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
                    // Поля лога, выбранные для графиков отчёта, дочитываются из файла.
                    val fieldKeys = reportCharts.filter { LogFields.isField(it) }
                    val e = flightEntry
                    val fields = if (exFmt == ExportFormat.PDF && PdfSection.CHARTS in exSec && fieldKeys.isNotEmpty() &&
                        e != null && LogFields.supported(e.format)) {
                        LogFields.extract(repo.fileOf(e), e.format, fieldKeys, p.log.timeOriginUs)
                    } else emptyMap()
                    val opts = ReportOptions(reportCharts.toList(), fields, reportParamMode, reportParamNames.toSet(),
                        title = e?.title.orEmpty(), tail = e?.tail.orEmpty(), note = e?.note.orEmpty())
                    Exporter.write(ctx, p, exFmt, exSec.toSet(), exportFileName(), units, mapLayer, opts)
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
