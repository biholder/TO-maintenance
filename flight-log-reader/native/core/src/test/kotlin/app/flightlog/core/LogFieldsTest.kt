package app.flightlog.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Список полей лога и чтение любых полей для отчёта. */
class LogFieldsTest {
    private fun res(n: String) = File(javaClass.classLoader.getResource(n)!!.toURI())

    @Test
    fun parsesKeys() {
        assertEquals(LogFields.Ref("BAT", 1, "Volt"), LogFields.parse("df:BAT[1].Volt"))
        assertEquals(LogFields.Ref("ATT", null, "Roll"), LogFields.parse("df:ATT.Roll"))
        assertEquals("df:BAT[1].Volt", LogFields.Ref("BAT", 1, "Volt").key)
        assertNull(LogFields.parse("alt"))
    }

    @Test
    fun catalogsAndExtractsOnLogTimeline() {
        for ((name, fmt) in listOf("copter_small.bin" to LogFormat.DATAFLASH, "copter_small.log" to LogFormat.DATAFLASH_TEXT)) {
            val f = res(name)
            val log = LogReader.read(f).log
            val cat = LogFields.catalog(f, fmt)
            val att = cat.first { it.name == "ATT" }
            assertTrue("Roll" in att.fields && "DesRoll" in att.fields, att.fields.toString())
            assertTrue(cat.none { it.name == "PARM" || it.name == "FMT" })
            val got = LogFields.extract(f, fmt, listOf("df:ATT.Roll", "df:CTUN.ThO", "df:NOPE.X"), log.timeOriginUs)
            assertEquals(setOf("df:ATT.Roll", "df:CTUN.ThO"), got.keys)
            val roll = got.getValue("df:ATT.Roll")
            val ref = log.series.getValue(Ch.ROLL)
            assertEquals("ATT.Roll", roll.label)
            assertEquals(ref.time.first(), roll.time.first(), 1e-3f)
            assertEquals(ref.size, roll.size)
            assertNotNull(ref.valueAt(30f))
            assertEquals(ref.valueAt(30f)!!, roll.valueAt(30f)!!, 1e-3f)
        }
    }
}
