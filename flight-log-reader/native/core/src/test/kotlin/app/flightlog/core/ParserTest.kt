package app.flightlog.core

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Сверка с эталонными значениями, посчитанными pymavlink (tools/make_sample_logs.py). */
class ParserTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()
    private val expected = Properties().apply { ParserTest::class.java.getResourceAsStream("/expected.properties")!!.use { load(it) } }
    private fun exp(key: String) = expected.getProperty(key).toDouble()

    @Test
    fun dataflashMessageCountsMatchPymavlink() {
        val r = DataFlashParser(wanted = null).parse(res("copter_small.bin"))
        for (name in listOf("ATT", "BAT", "CTUN", "ERR", "EV", "GPS", "MODE", "MSG", "PARM", "VIBE")) {
            assertEquals(exp("bin.count.$name").toInt(), r.tables.getValue(name).rows, name)
        }
        assertEquals(0L, r.badBytes)
    }

    @Test
    fun dataflashValuesMatchPymavlink() {
        val r = DataFlashParser().parse(res("copter_small.bin"))
        val gps = r.tables.getValue("GPS")
        assertEquals(exp("bin.gps.first.lat"), gps.num("Lat")[0], 1e-9)
        assertEquals(exp("bin.gps.first.lng"), gps.num("Lng")[0], 1e-9)
        assertEquals(exp("bin.gps.first.gwk"), gps.num("GWk")[0])
        assertEquals(exp("bin.gps.first.gms"), gps.num("GMS")[0])
        assertEquals(exp("bin.ctun.alt.max"), r.tables.getValue("CTUN").num("Alt").max(), 1e-5)
        assertEquals(exp("bin.bat.volt.min"), r.tables.getValue("BAT").num("Volt").min(), 1e-5)
        assertEquals(exp("bin.bat.currtot.last"), r.tables.getValue("BAT").num("CurrTot").last(), 1e-3)
        assertEquals(exp("bin.vibe.z.max"), r.tables.getValue("VIBE").num("VibeZ").max(), 1e-5)
    }

    @Test
    fun dataflashSurvivesCorruption() {
        val bytes = res("copter_small.bin")
        // портим кусок в середине файла
        for (i in 200_000 until 200_500) bytes[i] = (i * 31).toByte()
        val r = DataFlashParser(wanted = null).parse(bytes)
        assertTrue(r.badBytes > 0)
        val good = exp("bin.count.CTUN").toInt()
        assertTrue(r.tables.getValue("CTUN").rows in (good - 20) until good)
    }

    @Test
    fun dataflashMapsFlight() {
        val p = LogReader.read(res("copter_small.bin"), "copter_small.bin")
        val log = p.log
        assertEquals(LogFormat.DATAFLASH, log.format)
        assertEquals("Copter", log.vehicle.vehicleType)
        assertEquals("ArduCopter V4.5.7", log.vehicle.firmware)
        assertEquals("CubeOrange+", log.vehicle.board)
        assertEquals("QUAD/X", log.vehicle.frame)
        assertEquals("u-blox", log.vehicle.gps)
        assertEquals(listOf("STABILIZE", "LOITER", "AUTO", "RTL", "LAND"), log.modes.map { it.name })
        assertNotNull(log.armTime)
        assertNotNull(log.disarmTime)
        assertEquals(400f, log.duration, 0.5f)
        // старт: 2026-09-14 11:32:05 UTC
        assertTrue(kotlin.math.abs(log.startUtcMillis!! - 1789385525000L) < 1500, "start ${log.startUtcMillis}")
        val p0 = log.params.first { it.name == "ATC_RAT_RLL_P" }
        assertEquals(exp("bin.parm.ATC_RAT_RLL_P").toFloat(), p0.value, 1e-6f)
        assertTrue(p0.changed)
        assertTrue(log.params.first { it.name == "ATC_ANG_PIT_P" }.changed.not())
        for (k in listOf(Ch.ALT, Ch.SPD, Ch.VOLT, Ch.CURR, Ch.MAH, Ch.VIBE_Z, Ch.SATS, Ch.ROLL, Ch.PITCH, Ch.CLIMB)) {
            assertTrue(k in log.series, k)
        }
        assertEquals(exp("bin.count.GPS").toInt(), log.track.size)
    }

    @Test
    fun tlogMatchesPymavlink() {
        val r = TlogParser().parse(res("copter_small.tlog"))
        val own = r.packets.filter { it.sysId == 1 }
        fun count(id: Int) = own.count { it.msgId == id }
        assertEquals(exp("tlog.count.HEARTBEAT").toInt(), count(TlogParser.HEARTBEAT))
        assertEquals(exp("tlog.count.GLOBAL_POSITION_INT").toInt(), count(TlogParser.GLOBAL_POSITION_INT))
        assertEquals(exp("tlog.count.SYS_STATUS").toInt(), count(TlogParser.SYS_STATUS))
        assertEquals(exp("tlog.count.PARAM_VALUE").toInt(), count(TlogParser.PARAM_VALUE))
        assertEquals(exp("tlog.count.VIBRATION").toInt(), count(TlogParser.VIBRATION))
        assertEquals(0L, r.badBytes)

        val log = TlogMapper.map("copter_small.tlog", 0, r)
        assertEquals(exp("tlog.relalt.max").toFloat(), log.series.getValue(Ch.ALT).max, 1e-3f)
        assertEquals(exp("tlog.volt.min").toFloat(), log.series.getValue(Ch.VOLT).min, 1e-3f)
        assertEquals(exp("tlog.sats.min").toFloat(), log.series.getValue(Ch.SATS).min)
        assertEquals("ArduPilot", log.vehicle.autopilot)
        assertEquals("ArduCopter V4.5.7", log.vehicle.firmware)
        assertEquals(listOf("STABILIZE", "LOITER", "AUTO", "RTL", "LAND"), log.modes.map { it.name })
        assertNotNull(log.armTime)
        assertEquals(53, log.params.size)
    }

    @Test
    fun rejectsUnknownFiles() {
        assertFailsWith<LogParseException> { LogReader.read("hello world".toByteArray(), "notes.txt") }
        assertFailsWith<LogParseException> { LogReader.read(ByteArray(1000), "empty.bin") }
    }
}
