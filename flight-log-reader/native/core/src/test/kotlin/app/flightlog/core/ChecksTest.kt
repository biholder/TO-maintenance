package app.flightlog.core

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверки компаса, EKF, моторов, сопротивления АКБ и радиоканала на синтетическом полёте квадрокоптера. */
class ChecksTest {
    private fun flight(bad: Boolean, heli: Boolean = false): FlightLog {
        val f = Files.createTempFile("checks", ".bin").toFile()
        try {
            LargeLogTest.Writer(f).use { w ->
                w.define("PARM", "QNf", "TimeUS,Name,Value")
                w.define("EV", "QB", "TimeUS,Id")
                w.define("RCOU", "QHHHH", "TimeUS,C1,C2,C3,C4")
                w.define("BAT", "Qfff", "TimeUS,Volt,Curr,CurrTot")
                w.define("MAG", "QBfff", "TimeUS,I,MagX,MagY,MagZ")
                w.define("XKF4", "QBffff", "TimeUS,C,SV,SP,SH,SM")
                w.define("RSSI", "Qf", "TimeUS,RXRSSI")
                for (n in 1..4) w.write("PARM", 100_000L, "SERVO${n}_FUNCTION", 32 + n)
                w.write("PARM", 100_000L, "MOT_PWM_MIN", 1000)
                w.write("PARM", 100_000L, "MOT_PWM_MAX", 2000)
                if (heli) w.write("PARM", 100_000L, "H_RSC_MODE", 1)
                w.write("EV", 1_000_000L, 10)
                val r = if (bad) 0.12f else 0.02f
                var mah = 0f
                for (k in 0 until 600) {
                    val us = 1_000_000L + k * 100_000L
                    val t = k / 10f
                    val cur = 25f + 15f * sin(2 * PI * t / 4).toFloat()
                    mah += cur * 0.1f / 3.6f
                    w.write("BAT", us, 25f - t * 0.01f - r * cur, cur, mah)
                    val m = IntArray(4) { 1500 + ((cur - 25f) * 4).toInt() }
                    if (bad) {
                        m[2] += 160
                        if (t in 30f..33f) m[0] = 2000
                    }
                    w.write("RCOU", us, m[0], m[1], m[2], m[3])
                    val k0 = if (bad) 8f else 0.5f
                    w.write("MAG", us + 10, 0, 200f, 100f, 400f + k0 * cur)
                    if (bad) w.write("MAG", us + 20, 1, -100f, 200f, 400f) else w.write("MAG", us + 20, 1, 205f, 95f, 402f)
                    w.write("XKF4", us + 30, 0, 0.1f, 0.1f, 0.1f, if (bad && t in 20f..23f) 1.2f else 0.2f)
                    w.write("RSSI", us + 40, if (bad && t in 40f..43f) 0.1f else 0.9f)
                }
                w.write("EV", 61_000_000L, 11)
            }
            return LogReader.read(f).log
        } finally { f.delete() }
    }

    @Test
    fun findsProblems() {
        val log = flight(bad = true)
        assertEquals(listOf(1, 2, 3, 4), Motors.motors(log))
        assertTrue(Ch.magDiff(1) in log.series)
        val a = Analyzer.analyze(log)
        val titles = a.issues.map { it.title }
        for (t in listOf("Помехи компасу от силовой проводки", "Компасы 1 и 2 не согласованы", "Рассогласование EKF по компасу",
            "Разбаланс моторов", "Мотор 1 на пределе", "Просадка напряжения под нагрузкой", "Слабый сигнал управления")) {
            assertTrue(t in titles, "нет «$t» в $titles")
        }
        val ekf = a.issues.first { it.title.startsWith("Рассогласование EKF") }
        assertEquals(Severity.CRITICAL, ekf.severity)
        assertEquals(20.9f, ekf.start, 0.3f)
        val imb = a.issues.first { it.title == "Разбаланс моторов" }
        assertTrue("Мотор 3" in imb.explanation, imb.explanation)
        assertFalse("Компас" in a.healthy)
    }

    @Test
    fun healthyFlightHasNoWarnings() {
        val log = flight(bad = false)
        val a = Analyzer.analyze(log)
        assertEquals(emptyList(), a.issues.map { it.title })
        assertTrue(a.healthy.containsAll(listOf("Компас", "EKF", "Моторы", "Батарея")), a.healthy.toString())
        val res = Batteries.resistance(log.series.getValue(Ch.VOLT), log.series.getValue(Ch.CURR), 1f, 61f)!!
        assertEquals(0.02f, res.ohms, 0.004f)
    }

    @Test
    fun heliOutputsAreNotMotors() {
        val log = flight(bad = true, heli = true)
        assertEquals(emptyList(), Motors.motors(log))
        assertFalse(Analyzer.analyze(log).issues.any { it.title.contains("Мотор") })
    }

    @Test
    fun decodesErrorCodes() {
        assertEquals("GPS glitch — скачок координат", ArduPilot.errCodeText(11, 2))
        assertEquals("не удалось включить режим LOITER", ArduPilot.errCodeText(10, 5, "Copter"))
        assertEquals("восстановлено", ArduPilot.errCodeText(5, 0))
    }
}
