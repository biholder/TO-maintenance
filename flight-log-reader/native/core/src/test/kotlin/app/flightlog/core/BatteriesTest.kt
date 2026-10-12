package app.flightlog.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Две АКБ на борту: BAT с полем Instance, как в вертолётах и тяжёлых мультикоптерах. */
class BatteriesTest {
    private fun twoPackLog(secondCurrent: Float): FlightLog {
        val f = Files.createTempFile("bat2", ".bin").toFile()
        try {
            LargeLogTest.Writer(f).use { w ->
                w.define("EV", "QB", "TimeUS,Id")
                w.define("BAT", "QBfffffcfB", "TimeUS,Instance,Volt,VoltR,Curr,CurrTot,EnrgTot,Temp,Res,RemPct")
                w.write("EV", 1_000_000L, 10)
                val mah = floatArrayOf(0f, 0f)
                for (i in 0 until 600) {
                    val t = 1_000_000L + i * 100_000L
                    val cur = floatArrayOf(20f, secondCurrent)
                    for (p in 0..1) {
                        mah[p] += cur[p] * 0.1f / 3.6f
                        val v = 50f - mah[p] / 1000f - cur[p] * 0.02f
                        w.write("BAT", t + p * 10, p, v, v + 0.3f, cur[p], mah[p], 0f, 30 + p, 0.02f, 100 - mah[p] / 100f)
                    }
                }
                w.write("EV", 61_000_000L, 11)
            }
            return LogReader.read(f).log
        } finally { f.delete() }
    }

    @Test
    fun splitsPacksByInstance() {
        val log = twoPackLog(26f)
        assertEquals(listOf(0, 1), Batteries.packs(log))
        val v0 = log.series.getValue(Batteries.key(0, Batteries.Metric.VOLT))
        val c1 = log.series.getValue(Batteries.key(1, Batteries.Metric.CURR))
        assertTrue(c1.values.all { it == 26f }, "ток второй АКБ не смешан с первой")
        assertTrue(log.series.getValue(Ch.CURR).values.all { it == 20f }, "основной канал — первая АКБ")
        assertTrue(v0.size in 550..610)
        assertTrue(Batteries.key(0, Batteries.Metric.TEMP) in log.series)
        assertEquals("АКБ 2 · Ток", c1.label)

        val st = Batteries.stats(log)
        assertEquals(2, st.size)
        assertTrue(st[1].usedMah!! > st[0].usedMah!!)
        assertEquals(26f, st[1].avgCurr!!, 0.01f)

        val issue = Analyzer.analyze(log).issues.firstOrNull { it.title == "Разбаланс АКБ" }
        assertNotNull(issue)
    }

    @Test
    fun balancedPacksGiveNoWarning() {
        val log = twoPackLog(20.5f)
        assertEquals(2, Batteries.packs(log).size)
        assertNull(Batteries.imbalance(Batteries.stats(log)))
    }

    @Test
    fun singlePackHasNoPerPackChannels() {
        val log = LogReader.read(File(BatteriesTest::class.java.getResource("/copter_small.bin")!!.toURI())).log
        assertTrue(Batteries.packs(log).isEmpty())
    }
}
