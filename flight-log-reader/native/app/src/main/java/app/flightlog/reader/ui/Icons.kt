package app.flightlog.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Иконки Lucide (stroke-width 1.5), контуры из lucide.dev, лицензия ISC. */
object Icons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Sun = icon("sun", circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
        "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41")
    val Moon = icon("moon", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")
    val Columns = icon("columns", "M3 3h18v18H3Z", "M12 3v18")
    val Upload = icon("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12")
    val Download = icon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
    val ArrowLeft = icon("arrow-left", "m12 19-7-7 7-7", "M19 12H5")
    val X = icon("x", "M18 6 6 18", "m6 6 12 12")
    val Search = icon("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val Play = icon("play", "M6 3 20 12 6 21Z")
    val Pause = icon("pause", "M6 4h4v16H6Z", "M14 4h4v16h-4Z")
    val Share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val ChevronRight = icon("chevron-right", "m9 18 6-6-6-6")
    val Trash = icon("trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val Drone = icon("drone", circle(6f, 6f, 3f), circle(18f, 6f, 3f), circle(6f, 18f, 3f), circle(18f, 18f, 3f),
        "M8.5 8.5 10 10", "M15.5 8.5 14 10", "M8.5 15.5 10 14", "M15.5 15.5 14 14", "M10 10h4v4h-4Z")
}

@Composable
fun Icon(v: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Image(rememberVectorPainter(v), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}
