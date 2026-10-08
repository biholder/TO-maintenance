package app.flightlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisTest {
    private val parsed = LogReader.read(javaClass.getResourceAsStream("/copter_small.bin")!!.readBytes(), "copter_small.bin")

    @Test
    fun findsVibrationAndGpsIssues() {
        val a = parsed.analysis
        val vibe = a.issues.single { it.title.startsWith("Повышенные вибрации") }
        assertEquals(Severity.WARN, vibe.severity)
        assertTrue(vibe.start in 260f..266f, "start ${vibe.start}")
        assertTrue(vibe.end in 274f..280f, "end ${vibe.end}")
        assertEquals(Ch.VIBE_Z, vibe.channel)
        val gps = a.issues.single { it.title == "Деградация GPS" }
        assertTrue(gps.start in 339f..342f)
        assertTrue(gps.explanation.contains("GPS glitch"))
        assertTrue(a.issues.any { it.title == "Клиппинг акселерометра" })
        assertEquals(Severity.WARN, a.status)
        assertTrue("EKF3" in a.healthy)
        assertTrue("Батарея" in a.healthy)
    }

    @Test
    fun summaryIsPlausible() {
        val s = parsed.analysis.summary
        val log = parsed.log
        assertEquals(log.disarmTime!! - log.armTime!!, s.flightTime)
        assertTrue(s.distanceM in 1500.0..4000.0, "distance ${s.distanceM}")
        assertEquals(60f, s.maxAltM!!, 1f)
        assertTrue(s.usedMah!! in 3000f..3700f)
        assertTrue(s.minVoltage!! in 22f..24f)
        assertTrue(s.maxVibe!! > 40f)
    }

    @Test
    fun intervalsMergeGaps() {
        val t = FloatArray(20) { it.toFloat() }
        val bad = BooleanArray(20) { it in 2..5 || it in 7..8 || it == 15 }
        assertEquals(listOf(2f to 8f), Analyzer.intervals(t, bad, gap = 2f, minLen = 1f))
    }

    @Test
    fun pluralRu() {
        assertEquals("лог", plural(1, "лог", "лога", "логов"))
        assertEquals("лога", plural(3, "лог", "лога", "логов"))
        assertEquals("логов", plural(11, "лог", "лога", "логов"))
        assertEquals("логов", plural(25, "лог", "лога", "логов"))
    }
}
