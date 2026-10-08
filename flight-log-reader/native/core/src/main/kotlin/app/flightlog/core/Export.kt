package app.flightlog.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Текстовые форматы экспорта: CSV, KML, GPX, .param. */
object Export {
    /** CSV со всеми каналами на общей сетке времени [rateHz] (последнее известное значение). */
    fun csv(log: FlightLog, rateHz: Float = 10f): String {
        val keys = log.series.keys.toList()
        val sb = StringBuilder()
        sb.append("time_s")
        if (log.track.size > 0) sb.append(",lat,lon")
        keys.forEach { k -> val s = log.series.getValue(k); sb.append(',').append(s.message).append(if (s.unit.isNotEmpty()) " (${s.unit})" else "") }
        sb.append('\n')
        val steps = (log.duration * rateHz).toInt()
        for (i in 0..steps) {
            val t = i / rateHz
            sb.append(num(t, 2))
            if (log.track.size > 0) {
                val j = log.track.indexAt(t)
                if (j >= 0 && log.track.time[j] <= t) sb.append(',').append(log.track.lat[j]).append(',').append(log.track.lon[j])
                else sb.append(",,")
            }
            for (k in keys) {
                sb.append(',')
                log.series.getValue(k).valueAt(t)?.let { sb.append(num(it, 3)) }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun kml(log: FlightLog): String {
        val tr = log.track
        val coords = (0 until tr.size).joinToString(" ") { "${tr.lon[it]},${tr.lat[it]},${num(tr.alt[it], 1)}" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>${xml(log.fileName)}</name>
    <Style id="track"><LineStyle><color>ffa68059</color><width>3</width></LineStyle></Style>
    <Placemark>
      <name>Трек</name>
      <styleUrl>#track</styleUrl>
      <LineString>
        <altitudeMode>absolute</altitudeMode>
        <coordinates>$coords</coordinates>
      </LineString>
    </Placemark>
  </Document>
</kml>
"""
    }

    fun gpx(log: FlightLog): String {
        val tr = log.track
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Flight Log Reader" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${xml(log.fileName)}</name>
    <trkseg>
""")
        for (i in 0 until tr.size) {
            sb.append("      <trkpt lat=\"${tr.lat[i]}\" lon=\"${tr.lon[i]}\"><ele>${num(tr.alt[i], 1)}</ele>")
            log.startUtcMillis?.let { sb.append("<time>").append(iso.format(Date(it + (tr.time[i] * 1000).toLong()))).append("</time>") }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Файл параметров в формате Mission Planner: NAME,VALUE. */
    fun param(log: FlightLog, onlyChanged: Boolean = false): String =
        log.params.filter { !onlyChanged || it.changed }
            .joinToString("\n", postfix = "\n") { "${it.name},${paramValue(it.value)}" }

    fun paramValue(v: Float): String {
        if (v == v.toLong().toFloat() && kotlin.math.abs(v) < 1e9) return v.toLong().toString()
        return java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    }

    private fun num(v: Float, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
