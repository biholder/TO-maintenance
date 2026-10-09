package app.flightlog.reader.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.flightlog.core.Ch
import app.flightlog.core.Downsample
import app.flightlog.core.EventKind
import app.flightlog.core.LogReader
import app.flightlog.core.Severity
import app.flightlog.reader.UnitFmt
import app.flightlog.reader.Units
import app.flightlog.reader.clock
import app.flightlog.reader.dateTimeFull
import app.flightlog.reader.durationText
import app.flightlog.reader.num
import app.flightlog.reader.ui.TrackGeometry
import java.io.OutputStream

/** PDF-отчёт A4 (595×842 pt) в стиле «чертёж». */
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private val TX = Color.parseColor("#1d1f20")
    private val MU = Color.parseColor("#5d5d60")
    private val DV = Color.parseColor("#d6d6d8")
    private val AC = Color.parseColor("#5980a6")
    private val WR = Color.parseColor("#8a5a00")
    private val CR = Color.parseColor("#a8352a")
    private val OK = Color.parseColor("#3d7a57")

    private class Ctx(val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f
        var n = 0
        fun newPage() {
            page?.let { doc.finishPage(it) }
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create())
            c = page!!.canvas
            y = M
        }
        fun ensure(h: Float) { if (y + h > H - M) newPage() }
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private fun line(color: Int, w: Float = 0.75f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = w; style = Paint.Style.STROKE
    }

    fun write(p: LogReader.Parsed, sections: Set<PdfSection>, units: Units, out: OutputStream) {
        val doc = PdfDocument()
        val x = Ctx(doc)
        val log = p.log
        val a = p.analysis
        val u = UnitFmt(units)
        x.newPage()
        // Заголовок.
        x.c!!.drawText("PLOV · ОТЧЁТ О ПОЛЁТЕ", M, x.y + 10, paint(MU, 9f, true).apply { letterSpacing = 0.16f })
        x.c!!.drawText(log.fileName, M, x.y + 36, paint(TX, 24f, true))
        x.c!!.drawText("${log.vehicle.autopilot} ${log.format.ext} · ${dateTimeFull(log.startUtcMillis)}", M, x.y + 54, paint(MU, 10f))
        x.y += 72
        x.c!!.drawLine(M, x.y, W - M, x.y, line(TX, 1f))
        x.y += 16

        if (PdfSection.SUMMARY in sections) {
            heading(x, "ДИАГНОСТИКА")
            if (a.issues.isEmpty()) text(x, "Проблем не обнаружено.", OK)
            a.issues.forEach { i ->
                x.ensure(40f)
                val col = if (i.severity == Severity.CRITICAL) CR else WR
                x.c!!.drawRect(M, x.y - 8, M + 6, x.y - 2, Paint().apply { color = col })
                x.c!!.drawText("${i.title} · ${clock(i.start)}–${clock(i.end)}", M + 12, x.y, paint(TX, 10.5f, true))
                x.y += 14
                wrap(x, i.explanation, MU, 9.5f, M + 12)
                x.y += 6
            }
            if (a.healthy.isNotEmpty()) text(x, a.healthy.joinToString(", ") + " — в норме", MU)
            x.y += 8
            heading(x, "СВОДКА")
            val s = a.summary
            val (dv, du) = u.dist(s.distanceM)
            grid(x, listOf(
                "Длительность" to durationText(s.flightTime),
                "Дистанция" to "$dv $du",
                "Макс. высота" to "${u.alt(s.maxAltM)} ${u.altUnit}",
                "Макс. скорость" to "${u.speed(s.maxSpeedMs)} ${u.speedUnit}",
                "Израсходовано" to "${num(s.usedMah, 0)} мА·ч",
                "Мин. напряжение" to "${num(s.minVoltage, 2)} В",
            ))
            heading(x, "БОРТ")
            kv(x, listOf(
                "Контроллер" to log.vehicle.board.ifEmpty { "—" },
                "Прошивка" to log.vehicle.firmware.ifEmpty { "—" },
                "Рама" to log.vehicle.frame.ifEmpty { log.vehicle.vehicleType.ifEmpty { "—" } },
                "GPS" to log.vehicle.gps.ifEmpty { "—" },
                "Файл" to "${log.fileName} · ${LogReader.sizeText(log.fileSize)}",
            ))
        }

        if (PdfSection.TRACK in sections && log.track.size > 1) {
            x.ensure(300f)
            heading(x, "КАРТА ТРЕКА")
            val box = 260f
            val left = M
            val top = x.y
            val c = x.c!!
            c.drawRect(left, top, W - M, top + box, line(DV))
            val g = TrackGeometry(log.track, W - 2 * M - 20, box - 20)
            val path = Path()
            for (i in 0 until log.track.size) {
                val (px, py) = g.project(i)
                if (i == 0) path.moveTo(left + 10 + px, top + 10 + py) else path.lineTo(left + 10 + px, top + 10 + py)
            }
            c.drawPath(path, line(AC, 1.6f))
            val (hx, hy) = g.project(0)
            c.drawRect(left + 10 + hx - 5, top + 10 + hy - 5, left + 10 + hx + 5, top + 10 + hy + 5, Paint().apply { color = TX })
            c.drawText("Масштаб: ${num(g.metersPerUnit * 100, 0)} м", left + 8, top + box - 8, paint(MU, 8.5f))
            x.y += box + 18
        }

        if (PdfSection.CHARTS in sections) {
            heading(x, "ГРАФИКИ КАНАЛОВ")
            for (key in listOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.CURR, Ch.VIBE_Z, Ch.SATS)) {
                val s = log.series[key] ?: continue
                x.ensure(96f)
                val c = x.c!!
                c.drawText("${s.label} · ${s.message}", M, x.y, paint(TX, 9.5f, true))
                c.drawText("${num(s.min, 1)} … ${num(s.max, 1)} ${s.unit}", W - M - 120, x.y, paint(MU, 8.5f))
                val top = x.y + 6
                val h = 64f
                val w = W - 2 * M
                c.drawRect(M, top, M + w, top + h, line(DV))
                val d = Downsample.minMax(s, 240, 0f, log.duration)
                val lo = s.min
                val span = (s.max - lo).takeIf { it > 1e-6f } ?: 1f
                val path = Path()
                for (i in d.time.indices) {
                    val px = M + d.time[i] / log.duration * w
                    val py = top + h - (d.values[i] - lo) / span * (h - 6) - 3
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                c.drawPath(path, line(if (key == Ch.VIBE_Z) WR else AC, 1f))
                if (key == Ch.VIBE_Z && s.max > 30f) {
                    val ty = top + h - (30f - lo) / span * (h - 6) - 3
                    c.drawLine(M, ty, M + w, ty, line(CR, 0.75f).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) })
                }
                x.y = top + h + 18
            }
        }

        if (PdfSection.EVENTS in sections) {
            heading(x, "ЖУРНАЛ СОБЫТИЙ")
            log.events.filter { it.kind != EventKind.INFO || it.source != "MSG" || it.time > (log.armTime ?: 0f) - 5 }
                .take(120).forEach { e ->
                    x.ensure(14f)
                    val col = when (e.kind) { EventKind.WARN -> WR; EventKind.MODE -> AC; EventKind.INFO -> MU }
                    x.c!!.drawText(clock(e.time), M, x.y, paint(MU, 9f))
                    x.c!!.drawRect(M + 42, x.y - 7, M + 48, x.y - 1, Paint().apply { color = col })
                    x.c!!.drawText(e.title.take(80), M + 56, x.y, paint(TX, 9f))
                    x.c!!.drawText(e.source, W - M - 30, x.y, paint(MU, 8f))
                    x.y += 13
                }
            x.y += 8
        }

        if (PdfSection.PARAMS in sections) {
            heading(x, "ИЗМЕНЁННЫЕ ПАРАМЕТРЫ")
            val ch = log.params.filter { it.changed }
            if (ch.isEmpty()) text(x, if (log.params.isEmpty()) "Параметры в логе не найдены." else "Все параметры по умолчанию.", MU)
            ch.forEach { pr ->
                x.ensure(14f)
                x.c!!.drawText(pr.name, M, x.y, paint(TX, 9f, true))
                x.c!!.drawText(app.flightlog.core.Export.paramValue(pr.value), M + 180, x.y, paint(AC, 9f))
                x.c!!.drawText("по умолч. ${pr.default?.let { app.flightlog.core.Export.paramValue(it) } ?: "—"}", M + 280, x.y, paint(WR, 9f))
                x.y += 13
            }
        }

        x.page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }

    private fun heading(x: Ctx, t: String) {
        x.ensure(40f)
        x.y += 6
        x.c!!.drawText(t, M, x.y, paint(MU, 9f, true).apply { letterSpacing = 0.16f })
        x.y += 14
    }

    private fun text(x: Ctx, t: String, color: Int) {
        x.ensure(14f)
        x.c!!.drawText(t, M, x.y, paint(color, 10f))
        x.y += 14
    }

    private fun wrap(x: Ctx, t: String, color: Int, size: Float, left: Float) {
        val p = paint(color, size)
        val maxW = W - M - left
        var line = ""
        for (word in t.split(' ')) {
            val cand = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(cand) > maxW && line.isNotEmpty()) {
                x.ensure(13f); x.c!!.drawText(line, left, x.y, p); x.y += 12.5f; line = word
            } else line = cand
        }
        if (line.isNotEmpty()) { x.ensure(13f); x.c!!.drawText(line, left, x.y, p); x.y += 12.5f }
    }

    private fun grid(x: Ctx, cells: List<Pair<String, String>>) {
        val cols = 3
        val cw = (W - 2 * M) / cols
        val ch = 44f
        cells.chunked(cols).forEach { row ->
            x.ensure(ch)
            row.forEachIndexed { i, (k, v) ->
                val l = M + i * cw
                x.c!!.drawRect(l, x.y, l + cw, x.y + ch, line(DV))
                x.c!!.drawText(k.uppercase(), l + 8, x.y + 14, paint(MU, 7.5f, true).apply { letterSpacing = 0.12f })
                x.c!!.drawText(v, l + 8, x.y + 34, paint(TX, 15f, true))
            }
            x.y += ch
        }
        x.y += 14
    }

    private fun kv(x: Ctx, rows: List<Pair<String, String>>) {
        rows.forEach { (k, v) ->
            x.ensure(16f)
            x.c!!.drawText(k, M, x.y, paint(MU, 9.5f))
            x.c!!.drawText(v, M + 120, x.y, paint(TX, 9.5f))
            x.c!!.drawLine(M, x.y + 5, W - M, x.y + 5, line(DV, 0.5f))
            x.y += 16
        }
        x.y += 10
    }
}
