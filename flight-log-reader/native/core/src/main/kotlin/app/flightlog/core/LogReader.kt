package app.flightlog.core

import java.io.File

/** Точка входа: определяет формат и разбирает лог с отчётом по этапам. */
object LogReader {
    /** Этапы разбора для отображения прогресса. */
    fun stages(format: LogFormat): List<String> = when (format) {
        LogFormat.DATAFLASH -> listOf(
            "Чтение файла",
            "Разбор FMT и сообщений DataFlash",
            "Сборка каналов: CTUN, GPS, BAT, VIBE, ATT",
            "Журнал событий: MODE, EV, ERR, MSG",
            "Диагностика полёта",
        )
        LogFormat.TLOG -> listOf(
            "Чтение файла",
            "Разбор пакетов MAVLink v1/v2, проверка CRC",
            "Сборка каналов телеметрии",
            "Журнал событий: HEARTBEAT, STATUSTEXT",
            "Диагностика полёта",
        )
    }

    fun detect(name: String, head: ByteArray): LogFormat? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "bin" || ext == "log" && head.isDataFlash()) return LogFormat.DATAFLASH
        if (ext == "tlog") return LogFormat.TLOG
        if (head.isDataFlash()) return LogFormat.DATAFLASH
        if (head.size > 9 && ((head[8].toInt() and 0xFF) == 0xFE || (head[8].toInt() and 0xFF) == 0xFD)) return LogFormat.TLOG
        return null
    }

    private fun ByteArray.isDataFlash() = size >= 3 && this[0] == 0xA3.toByte() && this[1] == 0x95.toByte() && (this[2].toInt() and 0xFF) == 0x80

    class Listener(val onStage: (index: Int, result: String?) -> Unit, val onProgress: (Float) -> Unit)

    /** Результат разбора: лог, анализ и краткие итоги этапов. */
    class Parsed(val log: FlightLog, val analysis: Analysis)

    fun read(file: File, displayName: String = file.name, listener: Listener? = null): Parsed {
        listener?.onStage(0, null)
        val bytes = file.readBytes()
        return read(bytes, displayName, listener)
    }

    fun read(bytes: ByteArray, name: String, listener: Listener? = null): Parsed {
        val format = detect(name, bytes.copyOf(minOf(16, bytes.size)))
            ?: throw LogParseException("Неизвестный формат файла. Поддерживаются .bin (DataFlash) и .tlog (MAVLink)")
        listener?.onStage(0, sizeText(bytes.size.toLong()))
        listener?.onProgress(0.1f)
        listener?.onStage(1, null)
        val progress = ProgressListener { _, f -> listener?.onProgress(0.1f + 0.6f * f) }
        val log = when (format) {
            LogFormat.DATAFLASH -> {
                val r = DataFlashParser().parse(bytes, progress)
                listener?.onStage(1, "${r.tables.size} типов · ${r.messageCount} сообщ.")
                listener?.onStage(2, null)
                DataFlashMapper.map(name, bytes.size.toLong(), r)
            }
            LogFormat.TLOG -> {
                val r = TlogParser().parse(bytes, progress)
                listener?.onStage(1, "${r.packets.size} пакетов")
                listener?.onStage(2, null)
                TlogMapper.map(name, bytes.size.toLong(), r)
            }
        }
        listener?.onProgress(0.8f)
        listener?.onStage(2, "${log.series.size} каналов")
        listener?.onStage(3, null)
        listener?.onStage(3, "${log.events.size} событий")
        listener?.onStage(4, null)
        val analysis = Analyzer.analyze(log)
        listener?.onProgress(1f)
        listener?.onStage(4, analysis.statusText)
        return Parsed(log, analysis)
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1 shl 20 -> fmt1(bytes / 1048576f) + " МБ"
        bytes >= 1 shl 10 -> "${bytes / 1024} КБ"
        else -> "$bytes Б"
    }
}
