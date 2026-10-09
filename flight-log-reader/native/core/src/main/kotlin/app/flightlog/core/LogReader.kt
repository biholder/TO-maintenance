package app.flightlog.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
        LogFormat.DATAFLASH_TEXT -> listOf(
            "Чтение файла",
            "Разбор текстового лога: FMT и сообщения",
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
        if (head.isDataFlash()) return LogFormat.DATAFLASH
        if (TextLogParser.looksLikeText(head)) return LogFormat.DATAFLASH_TEXT
        if (ext == "tlog") return LogFormat.TLOG
        if (ext == "bin") return LogFormat.DATAFLASH
        if (head.size > 9 && ((head[8].toInt() and 0xFF) == 0xFE || (head[8].toInt() and 0xFF) == 0xFD)) return LogFormat.TLOG
        return null
    }

    private fun ByteArray.isDataFlash() = size >= 3 && this[0] == 0xA3.toByte() && this[1] == 0x95.toByte() && (this[2].toInt() and 0xFF) == 0x80

    fun head(file: File, n: Int = 64): ByteArray = file.inputStream().use { s ->
        val b = ByteArray(n)
        val r = s.read(b).coerceAtLeast(0)
        b.copyOf(r)
    }

    class Listener(val onStage: (index: Int, result: String?) -> Unit, val onProgress: (Float) -> Unit)

    /** Результат разбора: лог и анализ. */
    class Parsed(val log: FlightLog, val analysis: Analysis)

    /**
     * Разбор файла с диска. Бинарные форматы отображаются в память (mmap) —
     * файл не копируется в кучу, поэтому логи в сотни мегабайт не переполняют память.
     */
    fun read(file: File, displayName: String = file.name, listener: Listener? = null): Parsed {
        listener?.onStage(0, null)
        val format = detect(displayName, head(file)) ?: throw unknown()
        return when (format) {
            LogFormat.DATAFLASH_TEXT -> file.inputStream().buffered(1 shl 16).use { input ->
                parse(format, displayName, file.length(), listener, text = { p -> TextLogParser().parse(input, file.length(), p) })
            }
            else -> RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                parse(format, displayName, file.length(), listener, buffer = buf)
            }
        }
    }

    /** Разбор из памяти — для тестов и небольших файлов. */
    fun read(bytes: ByteArray, name: String, listener: Listener? = null): Parsed {
        val format = detect(name, bytes.copyOf(minOf(64, bytes.size))) ?: throw unknown()
        return if (format == LogFormat.DATAFLASH_TEXT)
            parse(format, name, bytes.size.toLong(), listener, text = { p -> TextLogParser().parse(bytes.inputStream(), bytes.size.toLong(), p) })
        else parse(format, name, bytes.size.toLong(), listener, buffer = ByteBuffer.wrap(bytes))
    }

    private fun unknown() = LogParseException(
        "Неизвестный формат файла. Поддерживаются ArduPilot .bin, текстовый .log (Mission Planner) и MAVLink .tlog",
    )

    private fun parse(
        format: LogFormat,
        name: String,
        size: Long,
        listener: Listener?,
        buffer: ByteBuffer? = null,
        text: ((ProgressListener) -> DataFlashParser.Result)? = null,
    ): Parsed {
        listener?.onStage(0, sizeText(size))
        listener?.onProgress(0.05f)
        listener?.onStage(1, null)
        val progress = ProgressListener { _, f -> listener?.onProgress(0.05f + 0.75f * f) }
        val log = when (format) {
            LogFormat.DATAFLASH, LogFormat.DATAFLASH_TEXT -> {
                val r = if (format == LogFormat.DATAFLASH) DataFlashParser().parse(buffer!!, progress) else text!!(progress)
                listener?.onStage(1, "${r.tables.size} типов · ${r.messageCount} сообщ.")
                listener?.onStage(2, null)
                DataFlashMapper.map(name, size, r, format)
            }
            LogFormat.TLOG -> {
                val l = TlogMapper.map(name, size, buffer!!, progress)
                listener?.onStage(1, "${l.messageCount} пакетов")
                listener?.onStage(2, null)
                l
            }
        }
        listener?.onProgress(0.85f)
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
