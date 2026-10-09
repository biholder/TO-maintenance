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
        val r = DataFlashParser(wanted = null, maxRateHz = 0.0).parse(res("copter_small.bin"))
        for (name in listOf("ATT", "BAT", "CTUN", "ERR", "EV", "GPS", "MODE", "MSG", "PARM", "VIBE")) {
            assertEquals(exp("bin.count.$name").toInt(), r.tables.getValue(name).rows, name)
        }
        assertEquals(0L, r.badBytes)
    }

    @Test
    fun dataflashValuesMatchPymavlink() {
        val r = DataFlashParser(maxRateHz = 0.0).parse(res("copter_small.bin"))
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
        val r = DataFlashParser(wanted = null, maxRateHz = 0.0).parse(bytes)
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

        val log = TlogMapper.map("copter_small.tlog", 0, java.nio.ByteBuffer.wrap(res("copter_small.tlog")))
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
    fun textLogMatchesBinary() {
        val bin = LogReader.read(res("copter_small.bin"), "copter_small.bin")
        val txt = LogReader.read(res("copter_small.log"), "copter_small.log")
        assertEquals(LogFormat.DATAFLASH_TEXT, txt.log.format)
        assertEquals(bin.log.vehicle, txt.log.vehicle)
        assertEquals(bin.log.modes.map { it.name }, txt.log.modes.map { it.name })
        assertEquals(bin.log.params.size, txt.log.params.size)
        assertEquals(bin.log.track.size, txt.log.track.size)
        assertEquals(bin.log.track.lat[100], txt.log.track.lat[100], 1e-7)
        for ((k, s) in bin.log.series) {
            val t = txt.log.series.getValue(k)
            assertEquals(s.size, t.size, k)
            assertEquals(s.max, t.max, 1e-3f, k)
        }
        assertEquals(bin.analysis.issues.map { it.title }, txt.analysis.issues.map { it.title })
        assertEquals(bin.analysis.summary.distanceM, txt.analysis.summary.distanceM, 0.5)
    }

    @Test
    fun textLogKeepsCommasInsideMessages() {
        val text = """
            FMT, 128, 89, FMT, BBnNZ, Type,Length,Name,Format,Columns
            FMT, 130, 75, MSG, QZ, TimeUS,Message
            FMT, 131, 14, MODE, QMBB, TimeUS,Mode,ModeNum,Rsn
            MSG, 1000, ArduCopter V4.5.7 (abc)
            MSG, 2000, PreArm: Battery 1 low voltage, 21.0V
            MODE, 3000, 5, 5, 1
        """.trimIndent().toByteArray()
        val r = TextLogParser().parse(text.inputStream(), text.size.toLong())
        assertEquals("PreArm: Battery 1 low voltage,21.0V", r.tables.getValue("MSG").str("Message")[1])
        assertEquals(5.0, r.tables.getValue("MODE").num("Mode")[0])
    }

    @Test
    fun decimatesHighRateMessages() {
        val full = DataFlashParser(maxRateHz = 0.0).parse(res("copter_small.bin"))
        val dec = DataFlashParser(maxRateHz = 5.0).parse(res("copter_small.bin"))
        val ctun = full.tables.getValue("CTUN").rows
        assertTrue(dec.tables.getValue("CTUN").rows in (ctun / 2 - 5)..(ctun / 2 + 5), "${dec.tables.getValue("CTUN").rows}")
        // события и параметры не прореживаются
        assertEquals(full.tables.getValue("PARM").rows, dec.tables.getValue("PARM").rows)
        assertEquals(full.tables.getValue("MSG").rows, dec.tables.getValue("MSG").rows)
        // при 10 Гц поток 10 Гц сохраняется целиком, несмотря на джиттер меток времени
        assertEquals(ctun, DataFlashParser().parse(res("copter_small.bin")).tables.getValue("CTUN").rows)
    }

    @Test
    fun rejectsUnknownFiles() {
        assertFailsWith<LogParseException> { LogReader.read("hello world".toByteArray(), "notes.txt") }
        assertFailsWith<LogParseException> { LogReader.read(ByteArray(1000), "empty.bin") }
    }
}
