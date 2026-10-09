package app.flightlog.core

import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Лог на сотни мегабайт должен разбираться при ограниченной куче (см. maxHeapSize
 * в build.gradle.kts): файл отображается в память, частые сообщения прореживаются.
 */
class LargeLogTest {
    private class Writer(file: File) : AutoCloseable {
        private val out = BufferedOutputStream(file.outputStream(), 1 shl 20)
        private val sizes = mapOf('Q' to 8, 'B' to 1, 'f' to 4, 'c' to 2, 'C' to 2, 'L' to 4, 'I' to 4, 'H' to 2, 'e' to 4, 'Z' to 64, 'N' to 16, 'n' to 4)
        private val fmts = HashMap<String, Pair<Int, String>>()
        private var next = 129
        private val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)

        init { fmt(128, "FMT", "BBnNZ", "Type,Length,Name,Format,Columns") }

        private fun fmt(type: Int, name: String, format: String, cols: String) {
            val len = 3 + format.sumOf { sizes.getValue(it) }
            fmts[name] = type to format
            buf.clear()
            buf.put(0xA3.toByte()).put(0x95.toByte()).put(128.toByte())
            buf.put(type.toByte()).put(len.toByte())
            str(name, 4); str(format, 16); str(cols, 64)
            out.write(buf.array(), 0, buf.position())
        }

        fun define(name: String, format: String, cols: String) = fmt(next++, name, format, cols)

        private fun str(s: String, n: Int) { val b = s.toByteArray(); for (i in 0 until n) buf.put(if (i < b.size) b[i] else 0) }

        fun write(name: String, vararg v: Number) {
            val (type, format) = fmts.getValue(name)
            buf.clear()
            buf.put(0xA3.toByte()).put(0x95.toByte()).put(type.toByte())
            format.forEachIndexed { i, c ->
                when (c) {
                    'Q' -> buf.putLong(v[i].toLong())
                    'B' -> buf.put(v[i].toInt().toByte())
                    'f' -> buf.putFloat(v[i].toFloat())
                    'c', 'C' -> buf.putShort((v[i].toDouble() * 100).toInt().toShort())
                    'L' -> buf.putInt((v[i].toDouble() * 1e7).toInt())
                    'I' -> buf.putInt(v[i].toInt())
                    'H' -> buf.putShort(v[i].toInt().toShort())
                    'e' -> buf.putInt((v[i].toDouble() * 100).toInt())
                    else -> error(c)
                }
            }
            out.write(buf.array(), 0, buf.position())
        }

        override fun close() = out.close()
    }

    @Test
    fun parsesLongLogWithinSmallHeap() {
        val file = File.createTempFile("big", ".bin")
        try {
            val hours = 2.5
            Writer(file).use { w ->
                w.define("IMU", "QBffffffIIfBBHH", "TimeUS,I,GyrX,GyrY,GyrZ,AccX,AccY,AccZ,EG,EA,T,GH,AH,GHz,AHz")
                w.define("ATT", "QccccCCCCB", "TimeUS,DesRoll,Roll,DesPitch,Pitch,DesYaw,Yaw,ErrRP,ErrYaw,AEKF")
                w.define("CTUN", "Qffffffefffhh".replace('h', 'f'), "TimeUS,ThI,ABst,ThO,ThH,DAlt,Alt,BAlt,DSAlt,SAlt,TAlt,DCRt,CRt")
                w.define("GPS", "QBBIHBcLLeffffB", "TimeUS,I,Status,GMS,GWk,NSats,HDop,Lat,Lng,Alt,Spd,GCrs,VZ,Yaw,U")
                val steps = (hours * 3600 * 400).toLong() // 400 Гц
                for (k in 0 until steps) {
                    val us = 10_000_000L + k * 2_500L
                    val t = k / 400.0
                    w.write("IMU", us, 0, 0.01, 0.02, 0.03, 0.1, 0.2, -9.8, 0, 0, 35.0, 1, 1, 400, 400)
                    if (k % 8 == 0L) { // 50 Гц
                        w.write("ATT", us + 1, 1.0, 1.1, 2.0, 2.1, 90.0, 90.0, 0.01, 0.01, 1)
                        w.write("CTUN", us + 2, 0.2, 0.0, 0.3, 0.2, 50.0, 50.0 + kotlin.math.sin(t), 50.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                    }
                    if (k % 80 == 0L) // 5 Гц
                        w.write("GPS", us + 3, 0, 6, (t * 1000).toLong(), 2436, 17, 0.7, 55.9 + t * 1e-6, 37.5, 200.0, 5.0, 90.0, 0.0, 0.0, 1)
                }
            }
            assertTrue(file.length() > 200L * 1024 * 1024, "size ${file.length()}")
            val p = LogReader.read(file)
            val log = p.log
            assertEquals(hours * 3600, log.duration.toDouble(), 1.0)
            // ATT 50 Гц → ~12,5 Гц после прореживания
            val att = log.series.getValue(Ch.ROLL)
            assertTrue(att.size in 100_000..120_000, "ATT rows ${att.size}")
            assertEquals((hours * 3600 * 5).toInt(), log.track.size, "GPS не прореживается ниже 10 Гц")
        } finally {
            file.delete()
        }
    }
}
