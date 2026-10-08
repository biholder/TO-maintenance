package app.flightlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    private val log = LogReader.read(javaClass.getResourceAsStream("/copter_small.bin")!!.readBytes(), "copter_small.bin").log

    @Test
    fun csvHasHeaderAndRows() {
        val lines = Export.csv(log, rateHz = 1f).lines().filter { it.isNotEmpty() }
        assertTrue(lines[0].startsWith("time_s,lat,lon,CTUN.Alt (м)"))
        assertEquals(402, lines.size)
        val cols = lines[0].split(',').size
        assertTrue(lines.drop(1).all { it.split(',').size == cols })
    }

    @Test
    fun kmlAndGpxContainTrack() {
        val kml = Export.kml(log)
        assertTrue(kml.contains("<coordinates>37.51382"))
        val gpx = Export.gpx(log)
        assertEquals(log.track.size, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<time>2026-09-14T11:32:"))
    }

    @Test
    fun paramFile() {
        val p = Export.param(log)
        assertTrue(p.lines().contains("BATT_CAPACITY,16000"))
        assertTrue(p.lines().contains("ATC_RAT_RLL_P,0.118"))
        val changed = Export.param(log, onlyChanged = true).lines().filter { it.isNotEmpty() }
        assertTrue(changed.size in 20..45)
    }

    @Test
    fun compareDiffs() {
        val diffs = Compare.paramDiffs(log.params, log.params.map { if (it.name == "MOT_THST_HOVER") it.copy(value = 0.3f) else it })
        assertEquals(listOf("MOT_THST_HOVER"), diffs.map { it.name })
        val s = Analyzer.summary(log)
        val m = Compare.metrics(s, s.copy(maxVibe = 10f))
        assertTrue(m.first { it.label == "Пик вибраций" }.aIsWorse)
    }

    @Test
    fun downsampleKeepsExtremes() {
        val s = log.series.getValue(Ch.VIBE_Z)
        val d = Downsample.minMax(s, 100)
        assertTrue(d.time.size <= 200)
        assertEquals(s.max, d.values.max())
        assertEquals(s.min, d.values.min())
    }
}
