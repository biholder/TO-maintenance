package app.flightlog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Точность навигации на синтетическом полёте: земля, полёт с «полкой» спутников и эпизодом помехи. */
class NavAccuracyTest {
    private fun series(key: String, unit: String, f: (Float) -> Float): Series {
        val t = FloatArray(3000) { it / 10f } // 300 с, 10 Гц
        return Series(key, key, key, unit, t, FloatArray(t.size) { f(t[it]) })
    }

    private fun flight(): FlightLog {
        val air = { t: Float -> t in 30f..250f }
        val jam = { t: Float -> t in 150f..160f }
        val s = listOf(
            series(Ch.ALT, "м") { t -> if (air(t)) 80f else 0f },
            series(Ch.SATS, "") { t -> when { jam(t) -> 12f; air(t) -> if ((t * 10).toInt() % 4 == 0) 18f else 16f; else -> 24f } },
            series(Ch.EPH, "м") { t -> when { jam(t) -> 40f; air(t) -> if ((t * 10).toInt() % 4 == 0) 3.5f else 6f; else -> 3f } },
            series(Ch.EPV, "м") { t -> when { jam(t) -> 80f; air(t) -> 9f; else -> 4f } },
            series(Ch.HDOP, "") { t -> when { jam(t) -> 1.2f; air(t) -> 1.0f; else -> 0.6f } },
            series(Ch.FIX, "") { t -> if (jam(t)) 3f else 4f },
            series(Ch.GNSS_INNOV, "м") { t -> if (jam(t)) 18f else 2f },
            series(Ch.GPS_ALT, "м") { t -> 200f + (if (air(t)) 80f else 0f) + kotlin.math.sin(t) * 2f },
            series(Ch.BARO_ALT, "м") { t -> if (air(t)) 80f else 0f },
            series(Ch.GPS_JAM, "") { 0f },
        ).associateBy { it.key }
        return FlightLog("t.ulg", 0, LogFormat.ULOG, VehicleInfo(), null, 300f, s,
            Track(FloatArray(0), DoubleArray(0), DoubleArray(0), FloatArray(0), FloatArray(0)),
            listOf(LogEvent(155f, EventKind.WARN, "[commander] Compass needs calibration", "WARNING", "MSG")),
            emptyList(), listOf(Param("EKF2_GPS_CHECK", 245f, null), Param("EKF2_REQ_EPV", 5f, null)), null, null, 0)
    }

    @Test
    fun reportsGroundFlightEpisodesAndObservations() {
        val r = assertNotNull(NavAccuracy.analyze(flight()))
        assertEquals(30f, assertNotNull(r.takeoff), 0.2f)
        assertEquals(24f, r.ground!!.sats!!.median)
        assertEquals(3f, r.ground!!.eph!!.median)
        assertEquals(16f, r.flight!!.sats!!.median)
        assertEquals(6f, r.flight!!.eph!!.median)
        assertTrue(r.flight!!.aided!! in 0.9f..0.97f)
        // Высота ГНСС гуляет синусом ±2 м относительно барометра.
        assertTrue(r.gnssBaroStd!! in 1.2f..1.6f, "${r.gnssBaroStd}")

        val e = r.episodes.single()
        assertEquals(150f, e.start, 0.2f)
        assertEquals(40f, e.maxEph)
        assertTrue(e.signalNotGeometry)
        assertTrue(e.aidingLost)
        assertEquals(listOf("Compass needs calibration"), e.messages)

        assertEquals(NavAccuracy.Rating.POOR, r.rating)
        assertTrue(r.observations.any { "теряются в воздухе" in it })
        assertTrue(r.observations.any { "ровно 16 спутников" in it })
        assertTrue(r.observations.any { "числом спутников" in it })
        assertTrue(r.observations.any { "равны нулю" in it })
        assertTrue(r.paramNotes.any { "EKF2_REQ_EPV" in it && "отключена" in it })
        assertTrue(Analyzer.analyze(flight()).issues.any { it.title == "Низкая точность навигации" })
    }

    @Test
    fun noGnssNoReport() {
        val log = flight()
        val bare = FlightLog("t", 0, LogFormat.CSV, VehicleInfo(), null, 300f, mapOf(Ch.ALT to log.series.getValue(Ch.ALT)),
            log.track, emptyList(), emptyList(), emptyList(), null, null, 0)
        assertEquals(null, NavAccuracy.analyze(bare))
    }
}
