package app.flightlog.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CsvTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()

    @Test
    fun ownExportRoundTrip() {
        val bin = LogReader.read(res("copter_small.bin"), "copter_small.bin")
        val csv = Export.csv(bin.log).toByteArray()
        val p = LogReader.read(csv, "copter_small.csv")
        val log = p.log
        assertEquals(LogFormat.CSV, log.format)
        assertEquals("ArduPilot", log.vehicle.autopilot)
        assertEquals(bin.log.duration, log.duration, 0.11f)
        for (k in listOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.CURR, Ch.MAH, Ch.VIBE_Z, Ch.SATS, Ch.ROLL, Ch.CLIMB)) {
            assertTrue(k in log.series, k)
            assertEquals(bin.log.series.getValue(k).max, log.series.getValue(k).max, 0.01f, k)
        }
        assertTrue(log.track.size > 3000)
        val dBin = bin.analysis.summary.distanceM
        assertTrue(abs(p.analysis.summary.distanceM - dBin) / dBin < 0.03, "distance ${p.analysis.summary.distanceM} vs $dBin")
        assertTrue(p.analysis.issues.any { it.title.startsWith("Повышенные вибрации") })
    }

    @Test
    fun airDataStyleDjiCsv() {
        val sb = StringBuilder()
        sb.append("time(millisecond),datetime(utc),latitude,longitude,height_above_takeoff(feet),altitude(feet),speed(mph),")
        sb.append("satellites,voltage(v),battery_percent,compass_heading(degrees),pitch(degrees),roll(degrees),")
        sb.append("gimbal_pitch(degrees),isPhoto,flycState,message\n")
        for (i in 0 until 600) {
            val t = i * 100
            val sec = i / 10
            val date = "2026-09-14 11:%02d:%02d".format(32 + sec / 60, sec % 60)
            val mode = if (i < 100) "Motors Started" else if (i < 500) "P-GPS" else "Go Home"
            val msg = if (i == 300) "\"Low battery, returning home\"" else ""
            sb.append("$t,$date,${55.9 + i * 1e-5},37.5,${i * 0.5},${600 + i * 0.5},${10.0},")
            sb.append("${if (i in 200..230) 6 else 17},${16.0 - i * 0.002},${100 - i / 10},90,-5,1,")
            sb.append("${-30 - i % 10},0,$mode,$msg\n")
        }
        val log = LogReader.read(sb.toString().toByteArray(), "DJIFlightRecord.csv").log
        assertEquals("DJI", log.vehicle.autopilot)
        assertEquals(59.9f, log.duration, 0.01f)
        assertEquals(299.5f * 0.3048f, log.series.getValue(Ch.ALT).max, 0.01f) // футы → метры, а не altitude(feet)
        assertEquals(4.4704f, log.series.getValue(Ch.SPD).max, 1e-3f) // mph → м/с
        assertEquals(listOf("Motors Started", "P-GPS", "Go Home"), log.modes.map { it.name })
        val warn = log.events.single { it.kind == EventKind.WARN }
        assertEquals("Low battery, returning home", warn.title)
        assertEquals(30f, warn.time, 0.01f)
        assertNotNull(log.startUtcMillis)
        assertEquals(1789385520000L, log.startUtcMillis)
        assertTrue("csv:gimbal_pitch(degrees)" in log.series)
        assertTrue("csv:isPhoto" !in log.series, "постоянные столбцы не нужны")
        assertEquals(600, log.track.size)
        val a = Analyzer.analyze(log)
        assertTrue(a.issues.any { it.title == "Деградация GPS" })
    }

    @Test
    fun semicolonAndDecimalComma() {
        val text = "sep=;\nВремя (с);Широта;Долгота;Высота (м);Напряжение (В)\n" +
            (0 until 50).joinToString("\n") { "${it * 0.2};55,9;37,5;${it},5;22,${50 - it}" }
        val log = LogReader.read(text.toByteArray(), "table.csv").log
        assertEquals(9.8f, log.duration, 1e-3f)
        assertEquals(49.5f, log.series.getValue(Ch.ALT).max, 1e-3f)
        assertEquals(55.9, log.track.lat[0], 1e-9)
        assertEquals(22.1f, log.series.getValue(Ch.VOLT).min, 1e-3f)
    }

    @Test
    fun epochMillisTimestampAndDecimation() {
        val start = 1789385525000L
        val text = "timestamp,lat,lon,alt\n" + (0 until 1000).joinToString("\n") { "${start + it * 20},55.9,37.5,$it" } // 50 Гц
        val log = LogReader.read(text.toByteArray(), "px4.csv").log
        assertEquals(start, log.startUtcMillis)
        assertEquals(19.98f, log.duration, 0.05f)
        val n = log.series.getValue(Ch.ALT).size
        assertTrue(n in 190..260, "прорежено до ~10 Гц: $n")
    }

    @Test
    fun helpers() {
        assertEquals("height_above_takeoff", CsvLogParser.normalize("Height Above Takeoff (feet)"))
        assertEquals("feet", CsvLogParser.unitOf("Height Above Takeoff (feet)"))
        assertEquals("ctun.alt", CsvLogParser.normalize("CTUN.Alt (м)"))
        assertEquals("km/h", CsvLogParser.unitOf("speed [km/h]"))
        assertEquals(listOf("a", "b,c", "d\"e"), CsvLogParser.split("a,\"b,c\",\"d\"\"e\"", ','))
        assertEquals(';', CsvLogParser.detectDelimiter("a;b;c,d"))
    }
}
