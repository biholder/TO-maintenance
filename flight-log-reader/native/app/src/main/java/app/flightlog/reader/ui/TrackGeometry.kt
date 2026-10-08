package app.flightlog.reader.ui

import app.flightlog.core.Track
import kotlin.math.cos

/** Проекция трека в прямоугольник w×h с сохранением пропорций (локальная равнопромежуточная). */
class TrackGeometry(private val track: Track, val w: Float, val h: Float) {
    private val lat0: Double
    private val lon0: Double
    private val kx: Double
    private val scale: Double
    private val offX: Double
    private val offY: Double

    /** Сколько метров в одной единице экрана. */
    val metersPerUnit: Double

    init {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (i in 0 until track.size) {
            minLat = minOf(minLat, track.lat[i]); maxLat = maxOf(maxLat, track.lat[i])
            minLon = minOf(minLon, track.lon[i]); maxLon = maxOf(maxLon, track.lon[i])
        }
        if (track.size == 0) { minLat = 0.0; maxLat = 0.0; minLon = 0.0; maxLon = 0.0 }
        lat0 = minLat
        lon0 = minLon
        kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-7)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-7)
        scale = minOf(w / spanX, h / spanY)
        offX = (w - spanX * scale) / 2
        offY = (h - spanY * scale) / 2
        metersPerUnit = 111_320.0 / scale
    }

    fun project(lat: Double, lon: Double): Pair<Float, Float> =
        (offX + (lon - lon0) * kx * scale).toFloat() to (h - offY - (lat - lat0) * scale).toFloat()

    fun project(i: Int): Pair<Float, Float> = project(track.lat[i], track.lon[i])
}
