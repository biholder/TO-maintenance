package app.flightlog.core

import java.nio.ByteBuffer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Сверка с эталонным dji-log-parser-js (tools/verify_dji.mjs → dji_expected.json). */
class DjiTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()
    private val expected = MiniJson.parse(String(res("dji_expected.json"))) as Map<*, *>
    private val keychains = String(res("dji_v14.keychains.json"))

    private val modeNames = mapOf(
        "GPSAtti" to "P-GPS", "EngineStart" to "Engine Start", "AutoTakeoff" to "Auto Takeoff",
        "GoHome" to "Go Home", "AutoLanding" to "Auto Landing",
    )

    @Test
    fun crc64MatchesRedisVector() {
        assertEquals(0xe9c6d914c4b8d9caUL.toLong(), DjiLog.crc64(0, "123456789".toByteArray()))
    }

    private fun checkAgainstReference(name: String, exp: Map<*, *>, keys: String?) {
        val bytes = res(name)
        val dji = DjiLog(ByteBuffer.wrap(bytes))
        assertEquals((exp["version"] as Double).toInt(), dji.version)
        assertEquals(exp["aircraftName"], dji.details.aircraftName)
        assertEquals(exp["aircraftSn"], dji.details.aircraftSn)
        assertEquals(Instant.parse(exp["startTime"] as String).toEpochMilli(), dji.details.startTimeMs)
        assertEquals((exp["totalDistance"] as Double).toFloat(), dji.details.totalDistance)

        val counts = HashMap<Int, Int>()
        dji.records(keys?.let { DjiMapper.parseKeychains(it) }) { counts.merge(it.type, 1, Int::plus) }
        val ec = exp["counts"] as Map<*, *>
        assertEquals((ec["OSD"] as Double).toInt(), counts[DjiLog.OSD])
        assertEquals((ec["Custom"] as Double).toInt(), counts[DjiLog.CUSTOM])
        assertEquals((ec["CenterBattery"] as Double).toInt(), counts[DjiLog.CENTER_BATTERY])
        assertEquals((ec["Home"] as Double).toInt(), counts[DjiLog.HOME])

        val p = LogReader.read(bytes, name, djiKeychains = keys)
        val log = p.log
        assertEquals(LogFormat.DJI, log.format)
        assertEquals("DJI", log.vehicle.autopilot)
        assertEquals(exp["osdFirstLat"] as Double, log.track.lat[0], 1e-9)
        assertEquals(exp["osdFirstLon"] as Double, log.track.lon[0], 1e-9)
        assertEquals((exp["osdMaxAltitude"] as Double).toFloat(), log.series.getValue(Ch.ALT).max, 1e-4f)
        assertEquals((exp["osdMinGps"] as Double).toFloat(), log.series.getValue(Ch.SATS).min)
        assertEquals((exp["batteryMinVoltage"] as Double).toFloat(), log.series.getValue(Ch.VOLT).min, 1e-4f)
        // эталон выгружен как множество режимов без повторов
        assertEquals((exp["osdModes"] as List<*>).map { modeNames[it] }, log.modes.map { it.name }.distinct())
        assertEquals(Instant.parse(exp["customFirst"] as String).toEpochMilli(), log.startUtcMillis)
        val dur = (Instant.parse(exp["customLast"] as String).toEpochMilli() - log.startUtcMillis!!) / 1000f
        assertEquals(dur, log.duration, 0.01f)
        assertTrue(log.armTime != null && log.disarmTime != null)
        assertEquals(((exp["osdMotorUp"] as Double) / 10).toFloat(), log.disarmTime!! - log.armTime!!, 0.11f)

        val a = p.analysis
        val vib = a.issues.single { it.title == "Сильные вибрации" }
        assertEquals(60f, vib.start, 0.11f)
        assertTrue(a.issues.any { it.title == "Деградация GPS" })
        assertTrue(log.events.any { it.title == "Возврат из приложения" })
    }

    @Test
    fun v12XorMatchesReference() = checkAgainstReference("dji_v12.txt", expected["v12"] as Map<*, *>, null)

    @Test
    fun v14AesMatchesReference() {
        val exp = expected["v14"] as Map<*, *>
        checkAgainstReference("dji_v14.txt", exp, keychains)
        val log = LogReader.read(res("dji_v14.txt"), "dji_v14.txt", djiKeychains = keychains).log
        val msgs = log.events.filter { it.source in setOf("TIP", "WARN", "SERIOUS") }.map {
            mapOf("TIP" to "AppTip", "WARN" to "AppWarn", "SERIOUS" to "AppSeriousWarn")[it.source] + ":" + it.title
        }
        assertEquals(exp["messages"], msgs)
        val serious = LogReader.read(res("dji_v14.txt"), "dji_v14.txt", djiKeychains = keychains).analysis.issues
        assertTrue(serious.any { it.title == "Low battery. Returning to home" })
    }

    @Test
    fun v14WithoutKeysAsksForThemWithReferenceRequest() {
        val e = assertFailsWith<DjiMapper.KeychainsRequired> { LogReader.read(res("dji_v14.txt"), "dji_v14.txt") }
        assertEquals(14, e.version)
        val ours = MiniJson.parse(e.requestJson)
        val ref = (expected["v14"] as Map<*, *>)["keychainsRequest"]
        assertEquals(ref, ours)
    }

    @Test
    fun v14WithWrongKeysFailsClearly() {
        val wrong = keychains.replace(Regex("\"aesKey\": \"[^\"]+\""), "\"aesKey\": \"${"A".repeat(43)}=\"")
        val e = assertFailsWith<LogParseException> { LogReader.read(res("dji_v14.txt"), "dji_v14.txt", djiKeychains = wrong) }
        assertTrue(e.message!!.contains("ключи не подходят"), e.message)
    }

    @Test
    fun apiResponseParsing() {
        val body = """{"data":[[{"featurePoint":"FR_Standardization_Feature_Base_1","aesKey":"a2V5","aesIv":"aXY="}]],"result":{"code":0,"msg":"ok"}}"""
        val chains = DjiMapper.parseKeychains(DjiKeychains.parseResponse(body))
        assertEquals("key", String(chains[0].getValue(1).second))
        assertEquals("iv", String(chains[0].getValue(1).first))
        val err = assertFailsWith<DjiKeychains.ApiException> { DjiKeychains.parseResponse("""{"result":{"code":2,"msg":"Invalid key"}}""") }
        assertTrue(err.message!!.contains("Invalid key"))
    }

    @Test
    fun detectsByContentNotJustExtension() {
        assertEquals(LogFormat.DJI, LogReader.detect("DJIFlightRecord_2026-09-14.txt", res("dji_v12.txt").copyOf(64)))
        assertEquals(null, LogReader.detect("notes.txt", "hello, this is just a text file".toByteArray()))
    }
}
