package app.flightlog.core

/** Прореживание ряда для отрисовки: min/max в каждой из [buckets] корзин по времени. */
object Downsample {
    class Points(val time: FloatArray, val values: FloatArray)

    fun minMax(s: Series, buckets: Int, t0: Float = 0f, t1: Float = s.time.lastOrNull() ?: 0f): Points {
        if (s.size <= buckets * 2 || buckets <= 0) return Points(s.time, s.values)
        val outT = FloatArray(buckets * 2)
        val outV = FloatArray(buckets * 2)
        var n = 0
        val span = (t1 - t0).coerceAtLeast(1e-6f)
        var i = 0
        for (b in 0 until buckets) {
            val end = t0 + span * (b + 1) / buckets
            var minI = -1
            var maxI = -1
            while (i < s.size && s.time[i] <= end) {
                if (minI < 0 || s.values[i] < s.values[minI]) minI = i
                if (maxI < 0 || s.values[i] > s.values[maxI]) maxI = i
                i++
            }
            if (minI < 0) continue
            val (a, c) = if (minI <= maxI) minI to maxI else maxI to minI
            outT[n] = s.time[a]; outV[n] = s.values[a]; n++
            if (c != a) { outT[n] = s.time[c]; outV[n] = s.values[c]; n++ }
        }
        return Points(outT.copyOf(n), outV.copyOf(n))
    }
}
