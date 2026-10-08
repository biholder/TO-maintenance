package app.flightlog.reader.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.flightlog.core.Export
import app.flightlog.core.LogReader
import app.flightlog.reader.Units
import java.io.File

enum class ExportFormat(val title: String, val description: String, val ext: String, val mime: String) {
    PDF("PDF-отчёт", "Сводка, трек, графики и события для заказчика или инженера", "pdf", "application/pdf"),
    CSV("CSV", "Все каналы на общей сетке 10 Гц — для Excel и Python", "csv", "text/csv"),
    KML("KML", "Трек для Google Earth", "kml", "application/vnd.google-earth.kml+xml"),
    GPX("GPX", "Трек для навигационных программ", "gpx", "application/gpx+xml"),
    PARAM("Параметры", "Файл параметров для Mission Planner / QGC", "param", "text/plain"),
}

enum class PdfSection(val title: String) {
    SUMMARY("Сводка и борт"), TRACK("Карта трека"), CHARTS("Графики каналов"),
    EVENTS("Журнал событий"), PARAMS("Изменённые параметры"),
}

object Exporter {
    fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun write(ctx: Context, p: LogReader.Parsed, fmt: ExportFormat, sections: Set<PdfSection>, name: String, units: Units): File {
        val out = File(dir(ctx), name)
        when (fmt) {
            ExportFormat.PDF -> out.outputStream().use { PdfReport.write(p, sections, units, it) }
            ExportFormat.CSV -> out.writeText(Export.csv(p.log))
            ExportFormat.KML -> out.writeText(Export.kml(p.log))
            ExportFormat.GPX -> out.writeText(Export.gpx(p.log))
            ExportFormat.PARAM -> out.writeText(Export.param(p.log))
        }
        return out
    }

    /** Грубая оценка размера файла до экспорта. */
    fun estimate(p: LogReader.Parsed, fmt: ExportFormat, sections: Set<PdfSection>): Long = when (fmt) {
        ExportFormat.PDF -> 40_000L + sections.size * 60_000L
        ExportFormat.CSV -> (p.log.duration * 10 * (p.log.series.size + 2) * 9).toLong()
        ExportFormat.KML -> p.log.track.size * 36L + 600
        ExportFormat.GPX -> p.log.track.size * 110L + 300
        ExportFormat.PARAM -> p.log.params.sumOf { it.name.length + 10L }
    }

    /** Копирует файл в общую папку «Загрузки». */
    fun saveToDownloads(ctx: Context, file: File, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в «Загрузках»")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // Android 8–9: папка загрузок приложения, без запроса разрешений.
            val dst = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), file.name)
            file.copyTo(dst, overwrite = true)
        }
    }
}
