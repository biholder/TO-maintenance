package app.flightlog.reader.ui

import app.flightlog.core.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Проекция трека в прямоугольник w×h с отступом [pad] — веб-Меркатор, как у тайлов
 * карт, поэтому трек точно ложится на подложку. Мировые координаты — 0..1.
 */
class TrackGeometry(private val track: Track, val w: Float, val h: Float, val pad: Float = 0f) {
    /** Пикселей экрана на единицу мировой координаты. */
    val scale: Double
    /** Мировые координаты левого верхнего угла экрана. */
    val worldLeft: Double
    val worldTop: Double

    /** Сколько метров в одной единице экрана. */
    val metersPerUnit: Double

    init {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var latSum = 0.0
        for (i in 0 until track.size) {
            val x = worldX(track.lon[i]); val y = worldY(track.lat[i])
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            latSum += track.lat[i]
        }
        if (track.size == 0) { minX = 0.5; maxX = 0.5; minY = 0.5; maxY = 0.5 }
        // Не крупнее ~60 м на кадр: иначе висение в точке растягивается на весь экран.
        val spanX = (maxX - minX).coerceAtLeast(1.5e-6)
        val spanY = (maxY - minY).coerceAtLeast(1.5e-6)
        val iw = (w - 2 * pad).coerceAtLeast(1f)
        val ih = (h - 2 * pad).coerceAtLeast(1f)
        scale = minOf(iw / spanX, ih / spanY)
        worldLeft = (minX + maxX) / 2 - w / 2 / scale
        worldTop = (minY + maxY) / 2 - h / 2 / scale
        val lat = if (track.size > 0) latSum / track.size else 0.0
        metersPerUnit = EARTH_CIRCUMFERENCE * cos(Math.toRadians(lat)) / scale
    }

    fun project(lat: Double, lon: Double): Pair<Float, Float> =
        ((worldX(lon) - worldLeft) * scale).toFloat() to ((worldY(lat) - worldTop) * scale).toFloat()

    fun project(i: Int): Pair<Float, Float> = project(track.lat[i], track.lon[i])

    companion object {
        const val EARTH_CIRCUMFERENCE = 40_075_016.7

        fun worldX(lon: Double) = (lon + 180.0) / 360.0

        fun worldY(lat: Double): Double {
            val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
        }
    }
}
