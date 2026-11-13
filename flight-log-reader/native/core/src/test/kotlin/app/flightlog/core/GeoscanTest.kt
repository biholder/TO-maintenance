package app.flightlog.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Журнал Геоскан: синтетический файл того же устройства, что и бортовые логи 101/201/401. */
class GeoscanTest {
    /** Пишет журнал: описания, затем записи `[время 6 байт][контрольный байт][id][данные]`. */
    private class Writer(prefix: Int = 0) {
        val out = ByteArrayOutputStream()
        private var first = true
        private var frameEvery = 0
        private var frameCount = 0
        private var bytes = 0

        init { repeat(prefix) { out.write(0) } }

        fun framesEvery(n: Int) { frameEvery = n }

        private fun raw(a: ByteArray) {
            for (x in a) {
                out.write(x.toInt())
                bytes++
                // Чужой кадр 55 AA … 33 CC прямо посреди записи, как в журналах 401.
                if (frameEvery > 0 && bytes % frameEvery == 0) {
                    frameCount++
                    out.write(byteArrayOf(0x55, 0xAA.toByte(), frameCount.toByte(), 0, 1, 0x0F, 1, 2, 3, 4, 5, 6, 0x33, 0xCC.toByte()))
                }
            }
        }

        fun record(timeUs: Long, id: Int, payload: ByteArray) {
            if (!first) {
                val h = ByteArray(7)
                for (i in 0 until 6) h[i] = (timeUs shr (8 * i)).toByte()
                h[6] = 0x5A
                raw(h)
            }
            first = false
            raw(byteArrayOf(id.toByte()) + payload)
        }

        fun describe(timeUs: Long, id: Int, size: Int, name: String, units: String, types: List<Pair<Int, Int>>) {
            val p = ByteBuffer.allocate(54)
            p.put(id.toByte()).put(size.toByte())
            p.put(name.toByteArray().copyOf(24)).put(units.toByteArray().copyOf(16))
            for ((t, c) in types) p.put(t.toByte()).put(c.toByte())
            record(timeUs, 255, p.array())
        }

        fun msg(timeUs: Long, id: Int, size: Int, fill: (ByteBuffer) -> Unit) {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            fill(b)
            record(timeUs, id, b.array())
        }
    }

    private fun log(frames: Boolean, prefix: Int = 0): File {
        val w = Writer(prefix)
        // Описание описаний — первая запись файла, без заголовка.
        w.describe(0, 255, 61, "Descriptor", "", emptyList())
        var t = 1_000_000L
        w.describe(t++, 1, 12, "Orientation", "deg", listOf(10 to 3))
        w.describe(t++, 3, 4, "Altitude", "m", listOf(10 to 1))
        w.describe(t++, 9, 20, "NavPosition", "deg,deg,m", listOf(11 to 2, 10 to 1))
        w.describe(t++, 10, 9, "NavSatInfo", "status,gnss", listOf(6 to 9))
        w.describe(t++, 13, 12, "NavVelocity", "m/s", listOf(10 to 3))
        w.describe(t++, 14, 21, "ControlData", "e,a,t,r,mode", listOf(10 to 5, 6 to 1))
        w.describe(t++, 17, 4, "UavMode", "", listOf(4 to 1))
        w.describe(t++, 19, 9, "PowerStatus", "A,V,%", listOf(10 to 2, 6 to 1))
        w.describe(t++, 27, 10, "Motor", "n,C,V,A,RPM,e,r", listOf(6 to 1, 2 to 1, 7 to 3, 6 to 2))
        w.describe(t++, 6, 8, "TimeDelta", "us", listOf(9 to 1))
        w.describe(t++, 52, 36, "ParameterFloat", "", listOf(1 to 32, 10 to 1))
        if (frames) w.framesEvery(997)
        w.msg(t++, 52, 36) { it.put("Plane_takeoffDelay".toByteArray().copyOf(32)).putFloat(120f) }
        w.msg(t++, 6, 8) { it.putLong(1_432_982_820_000_000L) }
        w.msg(t++, 17, 4) { it.putInt(2) }
        for (k in 0 until 600) {
            val us = 2_000_000L + k * 100_000L
            val sec = k / 10f
            val flying = sec in 10f..50f
            w.msg(us, 1, 12) { it.putFloat(5f).putFloat(-3f).putFloat(-90f) }
            w.msg(us + 100, 3, 4) { it.putFloat(if (flying) 100f else 0f) }
            w.msg(us + 200, 14, 21) { it.putFloat(0f).putFloat(0f).putFloat(if (flying) 0.6f else 0f).putFloat(0f).putFloat(0f).put(0) }
            w.msg(us + 300, 19, 9) { it.putFloat(if (flying) 20f else 0.5f).putFloat(16.4f).put(0) }
            for (m in 0 until 2) w.msg(us + 400 + m, 27, 10) {
                it.put((64 + m).toByte()).put(30).putShort(16000).putShort(5000).putShort((if (flying) 2000 + m * 50 else 0).toShort()).put(0).put(0)
            }
            if (k % 2 == 0) {
                w.msg(us + 500, 9, 20) { it.putDouble(55.0 + k * 1e-5).putDouble(73.0).putFloat(150f) }
                w.msg(us + 600, 13, 12) { it.putFloat(3f).putFloat(4f).putFloat(-1f) }
            }
            if (k % 10 == 0) w.msg(us + 700, 10, 9) { it.put(3).put(0).put(0).put(11).put(12).put(0).put(0).put(0).put(0) }
            if (k == 100) w.msg(us + 800, 17, 4) { it.putInt(15) }
        }
        val f = Files.createTempFile("geoscan", ".bin").toFile()
        f.writeBytes(w.out.toByteArray())
        return f
    }

    @Test
    fun readsJournal() {
        for ((frames, prefix) in listOf(false to 0, true to 1300)) {
            val f = log(frames, prefix)
            try {
                assertEquals(LogFormat.GEOSCAN, LogReader.detect(f.name, LogReader.head(f, LogReader.HEAD_BYTES)))
                val p = LogReader.read(f)
                val log = p.log
                assertEquals(LogFormat.GEOSCAN, log.format)
                assertEquals("Геоскан", log.vehicle.autopilot)
                assertEquals("Мультикоптер", log.vehicle.vehicleType)
                assertEquals(5f, log.series.getValue(Ch.ROLL).values.last(), 1e-4f)
                assertEquals(270f, log.series.getValue(Ch.YAW).values.last(), 1e-3f)
                assertEquals(5f, log.series.getValue(Ch.SPD).values.first(), 1e-4f, "скорость — модуль vn, ve")
                assertEquals(1f, log.series.getValue(Ch.CLIMB).values.first(), 1e-4f, "набор высоты = −vd")
                assertEquals(60f, log.series.getValue(Ch.THR).max, 1e-3f)
                assertEquals(11f, log.series.getValue(Ch.SATS).max)
                assertTrue(log.track.size in 290..300, "трек ${log.track.size}")
                assertEquals(2050f, log.series.getValue("rpm2").max)
                assertEquals(5f, log.series.getValue("mcur1").max, 1e-4f)
                // Ток 20 А в течение 40 с ≈ 222 мА·ч.
                assertTrue(abs(log.series.getValue(Ch.MAH).max - 222f) < 10f, "mah ${log.series.getValue(Ch.MAH).max}")
                assertEquals(listOf("MODE 2", "MODE 15"), log.modes.map { it.name })
                assertEquals(120f, log.params.single { it.name == "Plane_takeoffDelay" }.value)
                val arm = assertNotNull(log.armTime)
                assertTrue(arm in 10f..12f, "взлёт $arm")
                // TimeDelta — смещение от эпохи GPS: 2025-06-03.
                val start = java.time.Instant.ofEpochMilli(assertNotNull(log.startUtcMillis)).toString()
                assertTrue(start.startsWith("2025-06-03"), start)

                val cat = LogFields.catalog(f, LogFormat.GEOSCAN)
                assertEquals(listOf(64, 65), cat.single { it.name == "Motor" }.instances)
                assertTrue("t" in cat.single { it.name == "ControlData" }.fields)
                val ex = LogFields.extract(f, LogFormat.GEOSCAN, listOf("df:Motor[65].RPM", "df:Altitude.value"), log.timeOriginUs)
                assertEquals(2050f, ex.getValue("df:Motor[65].RPM").max)
                assertEquals(log.series.getValue(Ch.ALT).time.first(), ex.getValue("df:Altitude.value").time.first(), 1e-3f)
            } finally { f.delete() }
        }
    }
}
