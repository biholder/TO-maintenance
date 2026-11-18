package app.flightlog.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PX4 ULog: синтетический журнал по спецификации формата. */
class ULogTest {
    private class Writer {
        val out = ByteArrayOutputStream()

        init {
            out.write(byteArrayOf(0x55, 0x4C, 0x6F, 0x67, 0x01, 0x12, 0x35, 0x01))
            out.write(le(8) { putLong(1_000_000) })
        }

        private fun le(n: Int, f: ByteBuffer.() -> Unit): ByteArray =
            ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()

        fun msg(type: Char, body: ByteArray) {
            out.write(le(3) { putShort(body.size.toShort()); put(type.code.toByte()) })
            out.write(body)
        }

        fun format(s: String) = msg('F', s.toByteArray())

        private fun kv(key: String, value: ByteArray) = byteArrayOf(key.length.toByte()) + key.toByteArray() + value

        fun info(key: String, value: ByteArray) = msg('I', kv(key, value))
        fun param(name: String, v: Float) = msg('P', kv("float $name", le(4) { putFloat(v) }))
        fun default(name: String, v: Float) = msg('Q', byteArrayOf(1) + kv("float $name", le(4) { putFloat(v) }))
        fun add(id: Int, multi: Int, name: String) = msg('A', byteArrayOf(multi.toByte()) + le(2) { putShort(id.toShort()) } + name.toByteArray())
        fun data(id: Int, size: Int, f: ByteBuffer.() -> Unit) = msg('D', le(2) { putShort(id.toShort()) } + le(size, f))
        fun log(level: Char, us: Long, text: String) = msg('L', byteArrayOf(level.code.toByte()) + le(8) { putLong(us) } + text.toByteArray())
    }

    private fun file(): java.io.File {
        val w = Writer()
        w.format("vehicle_attitude:uint64_t timestamp;float[4] q;uint8_t quat_reset_counter;uint8_t[3] _padding0;")
        w.format("vehicle_local_position:uint64_t timestamp;float z;float vx;float vy;float vz;")
        w.format("vehicle_global_position:uint64_t timestamp;double lat;double lon;float alt;bool lat_lon_valid;uint8_t[3] _padding0;")
        w.format("vehicle_gps_position:uint64_t timestamp;uint64_t time_utc_usec;float hdop;uint8_t fix_type;uint8_t satellites_used;uint8_t[2] _padding0;")
        w.format("battery_status:uint64_t timestamp;float voltage_v;float current_a;float discharged_mah;float remaining;bool connected;uint8_t[3] _padding0;")
        w.format("vehicle_status:uint64_t timestamp;uint8_t arming_state;uint8_t nav_state;uint8_t vehicle_type;bool is_vtol;bool failsafe;uint8_t[3] _padding0;")
        w.format("esc_report:int32_t esc_rpm;float esc_current;")
        w.format("esc_status:uint64_t timestamp;uint8_t esc_count;uint8_t[3] _padding0;esc_report[2] esc;")
        w.info("char[10] ver_hw", "PX4_FMU_V5".toByteArray())
        w.info("int32_t ver_sw_release", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x010F02FF).array())
        w.param("MPC_XY_VEL_MAX", 12f)
        w.default("MPC_XY_VEL_MAX", 10f)
        w.param("BAT1_N_CELLS", 12f)
        w.default("BAT1_N_CELLS", 12f)
        w.add(0, 0, "vehicle_attitude"); w.add(1, 0, "vehicle_local_position"); w.add(2, 0, "vehicle_global_position")
        w.add(3, 0, "vehicle_gps_position"); w.add(4, 0, "battery_status"); w.add(5, 0, "vehicle_status"); w.add(6, 0, "esc_status")
        val t0 = 5_000_000L
        for (k in 0 until 600) {
            val us = t0 + k * 100_000L
            val sec = k / 10f
            val flying = sec in 5f..55f
            // Крен 30° — кватернион поворота вокруг X; хвостовое выравнивание в записи не пишется (как в PX4).
            val h = Math.toRadians(30.0) / 2
            w.data(0, 25) { putLong(us); putFloat(cos(h).toFloat()); putFloat(sin(h).toFloat()); putFloat(0f); putFloat(0f); put(0) }
            w.data(1, 24) { putLong(us); putFloat(if (flying) -50f else 0f); putFloat(3f); putFloat(4f); putFloat(-1f) }
            w.data(2, 29) { putLong(us); putDouble(55.0 + k * 1e-5); putDouble(37.0); putFloat(200f); put(1) }
            if (k % 2 == 0) w.data(3, 22) { putLong(us); putLong(1_763_366_400_000_000L + k * 100_000L); putFloat(0.8f); put(3); put(18) }
            w.data(4, 25) { putLong(us); putFloat(48f); putFloat(if (flying) 30f else 1f); putFloat(k * 2f); putFloat(0.9f); put(1) }
            w.data(5, 13) { putLong(us); put(if (flying) 2 else 1); put(if (sec < 30f) 2 else 3); put(1); put(0); put(0) }
            w.data(6, 28) { putLong(us); put(2); put(0); put(0); put(0); putInt(4000); putFloat(10f); putInt(4100); putFloat(11f) }
            if (k % 10 == 0) w.log('6', us, "[uavcan] status ok $k")
        }
        w.log('4', t0 + 40_000_000, "[health_and_arming_checks] Compass needs calibration")
        val f = Files.createTempFile("px4", ".ulg").toFile()
        f.writeBytes(w.out.toByteArray())
        return f
    }

    @Test
    fun readsULog() {
        val f = file()
        try {
            assertEquals(LogFormat.ULOG, LogReader.detect(f.name, LogReader.head(f, LogReader.HEAD_BYTES)))
            val p = LogReader.read(f)
            val log = p.log
            assertEquals("PX4", log.vehicle.autopilot)
            assertEquals("PX4 v1.15.2", log.vehicle.firmware)
            assertEquals("PX4_FMU_V5", log.vehicle.board)
            assertEquals("Copter", log.vehicle.vehicleType)
            assertEquals(30f, log.series.getValue(Ch.ROLL).values.first(), 0.01f)
            assertEquals(50f, log.series.getValue(Ch.ALT).max, 1e-3f)
            assertEquals(5f, log.series.getValue(Ch.SPD).values.first(), 1e-4f)
            assertEquals(1f, log.series.getValue(Ch.CLIMB).values.first(), 1e-4f)
            assertEquals(18f, log.series.getValue(Ch.SATS).max)
            assertEquals(90f, log.series.getValue(Ch.REM).max, 1e-3f)
            assertEquals(600, log.track.size)
            assertEquals(listOf("POSCTL", "MISSION"), log.modes.map { it.name })
            val arm = assertNotNull(log.armTime)
            assertEquals(5f, arm, 0.15f)
            assertEquals(55.1f, assertNotNull(log.disarmTime), 0.15f)
            assertTrue(java.time.Instant.ofEpochMilli(assertNotNull(log.startUtcMillis)).toString().startsWith("2025-11-17"))
            // Параметры и значения по умолчанию (Q).
            assertTrue(log.params.single { it.name == "MPC_XY_VEL_MAX" }.changed)
            assertTrue(!log.params.single { it.name == "BAT1_N_CELLS" }.changed)
            // Повторы «status ok N» раз в секунду схлопываются до раза в 30 с.
            val ok = log.events.count { it.title.startsWith("[uavcan] status ok") }
            assertEquals(2, ok)
            val a = p.analysis
            assertTrue(a.issues.any { it.title == "Предупреждения автопилота" && "Compass needs calibration" in it.explanation })
            assertTrue("Компас" !in a.healthy)

            // Любые поля: вложенный формат и экземпляры.
            val cat = LogFields.catalog(f, LogFormat.ULOG)
            val esc = cat.single { it.name == "esc_status" }
            assertTrue("esc[1].esc_rpm" in esc.fields, esc.fields.toString())
            val ex = LogFields.extract(f, LogFormat.ULOG, listOf("df:esc_status.esc[1].esc_rpm"), log.timeOriginUs)
            assertEquals(4100f, ex.getValue("df:esc_status.esc[1].esc_rpm").max)
        } finally { f.delete() }
    }
}
