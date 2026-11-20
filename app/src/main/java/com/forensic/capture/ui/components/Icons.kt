package com.forensic.capture.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 图标与交付原型的 SVG 保持同一套线性语言：
 * 24×24 视图框、1.8 描边、圆头圆角，路径数据直接复用原型，避免二次手绘走形。
 */
data class IconSpec(
    val paths: List<String> = emptyList(),
    val filled: Boolean = false,
    val viewBox: Float = 24f,
    val strokeWidth: Float = 1.8f
)

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

@Composable
fun LineIcon(
    spec: IconSpec,
    tint: Color,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / spec.viewBox
        scale(s, pivot = Offset.Zero) {
            spec.paths.forEach { d ->
                val p = parse(d)
                if (spec.filled) {
                    drawPath(p, color = tint)
                } else {
                    drawPath(
                        path = p,
                        color = tint,
                        style = Stroke(
                            width = spec.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

/** 原型中的小圆点 / 状态点 */
@Composable
fun Dot(color: Color, size: Dp = 5.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(color = color)
    }
}

object FCIcons {
    private fun line(vararg d: String, width: Float = 1.8f, box: Float = 24f) =
        IconSpec(paths = d.toList(), strokeWidth = width, viewBox = box)

    private fun fill(vararg d: String, box: Float = 24f) =
        IconSpec(paths = d.toList(), filled = true, viewBox = box)

    val ShieldCheck = line(
        "M12 3l7 3v5.4c0 4.2-2.9 7.6-7 9.3-4.1-1.7-7-5.1-7-9.3V6l7-3z",
        "M9 12l2 2 4-4"
    )
    val Shield = line("M12 3l7 3v5.4c0 4.2-2.9 7.6-7 9.3-4.1-1.7-7-5.1-7-9.3V6l7-3z")
    val BrandMark = line(
        "M12 2.6l7.2 3.1v5.7c0 4.5-3 8-7.2 9.9-4.2-1.9-7.2-5.4-7.2-9.9V5.7L12 2.6z",
        "M12 7.8a2.8 2.8 0 100 5.6 2.8 2.8 0 000-5.6z",
        "M12 13.4v3.1",
        width = 1.5f
    )
    val Video = fill(
        "M4 6.6A2.6 2.6 0 016.6 4h6.8A2.6 2.6 0 0116 6.6v10.8a2.6 2.6 0 01-2.6 2.6H6.6A2.6 2.6 0 014 17.4V6.6z",
        "M17.6 9.2l3.1-1.8c.5-.3 1.1.05 1.1.63v8c0 .58-.6.93-1.1.63l-3.1-1.8V9.2z"
    )
    val VideoLine = line("M3.5 6h11a2 2 0 012 2v8a2 2 0 01-2 2h-11a2 2 0 01-2-2V8a2 2 0 012-2z", "M16.5 10.5l4-2.5v8l-4-2.5", width = 1.7f)
    val Mic = line("M9 2.6h6v11H9z", "M5.4 11.4a6.6 6.6 0 0013.2 0", "M12 18v3.4", width = 1.7f)
    val MicRound = line("M9 3h6a3 3 0 013 3v5a3 3 0 01-3 3h-6a3 3 0 01-3-3V6a3 3 0 013-3z", "M6 11a6 6 0 0012 0M12 17v4", width = 1.8f)
    val Upload = line("M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5", "M4 15v4a1 1 0 001 1h14a1 1 0 001-1v-4")
    val Download = line("M12 4v11m0 0l-4-4m4 4l4-4", "M5 19h14")
    val Copy = line("M9 9h11v12H9z", "M15 9V6a2 2 0 00-2-2H6a2 2 0 00-2 2v10a2 2 0 002 2h2")
    val Retry = line("M20 11a8 8 0 10-2.3 5.7", "M20 5v6h-6")
    val Clock = line("M12 3.5a8.5 8.5 0 100 17 8.5 8.5 0 000-17z", "M12 7.5V12l3 2")
    val Warn = fill("M10.3 3.9L2.9 17a2 2 0 001.7 3h14.8a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z")
    val Chain = line(
        "M9.5 14.5l5-5",
        "M7.8 10.2L6.2 11.8a3.5 3.5 0 004.9 4.9l1.6-1.6",
        "M16.2 13.8l1.6-1.6a3.5 3.5 0 00-4.9-4.9l-1.6 1.6"
    )
    val Doc = line("M6 3h9l4 4v14a1 1 0 01-1 1H6a1 1 0 01-1-1V4a1 1 0 011-1z", "M9 12h6M9 16h4")
    val Folder = line("M4 7.5A2.5 2.5 0 016.5 5h3l2 2h6A2.5 2.5 0 0120 9.5v8A2.5 2.5 0 0117.5 20h-11A2.5 2.5 0 014 17.5v-10z", width = 1.7f)
    val Home = line("M4 10.4L12 4l8 6.4V20a1 1 0 01-1 1h-4v-6H9v6H5a1 1 0 01-1-1v-9.6z")
    val Ledger = line("M3.5 4h17v16h-17z", "M7.5 9h9M7.5 13h9M7.5 17h5")
    val Person = line("M12 4.6a3.6 3.6 0 100 7.2 3.6 3.6 0 000-7.2z", "M4.6 20c.5-3.4 3.6-5.4 7.4-5.4s6.9 2 7.4 5.4")
    val Back = line("M15 5l-7 7 7 7", width = 2f)
    val ChevronRight = line("M9 6l6 6-6 6", width = 2f)
    val Play = fill("M7 4.5v15l13-7.5z")
    val Share = line("M17.5 3a2.5 2.5 0 100 5 2.5 2.5 0 000-5z", "M6.5 9.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z", "M17.5 16a2.5 2.5 0 100 5 2.5 2.5 0 000-5z", "M8.8 10.8l6.4-3.9M8.8 13.2l6.4 3.9")
    val Key = line("M8 8a4 4 0 100 8 4 4 0 000-8z", "M12 12h9M18 12v3M15.5 12v2.2")
    val Cert = line("M6 3h9l4 4v14a1 1 0 01-1 1H6a1 1 0 01-1-1V4a1 1 0 011-1z", "M9 13l2 2 4-4")
    val Gear = line("M10.3 4.3c.43-1.76 2.93-1.76 3.36 0a1.72 1.72 0 002.57 1.07c1.54-.94 3.31.83 2.37 2.37a1.72 1.72 0 001.07 2.57c1.76.43 1.76 2.93 0 3.36a1.72 1.72 0 00-1.07 2.57c.94 1.54-.83 3.31-2.37 2.37a1.72 1.72 0 00-2.57 1.07c-.43 1.76-2.93 1.76-3.36 0a1.72 1.72 0 00-2.57-1.07c-1.54.94-3.31-.83-2.37-2.37a1.72 1.72 0 00-1.07-2.57c-1.76-.43-1.76-2.93 0-3.36a1.72 1.72 0 001.07-2.57c-.94-1.54.83-3.31 2.37-2.37.99.6 2.29.07 2.57-1.07z", "M12 12m-2.8 0a2.8 2.8 0 105.6 0 2.8 2.8 0 00-5.6 0", width = 1.6f)
    val Lock = line("M6 10h12v10H6z", "M8.5 10V7.5a3.5 3.5 0 017 0V10")
    val Wifi = line("M2 5.5a15 15 0 0120 0", "M5.5 9.5a10 10 0 0113 0", "M9 13.2a5 5 0 016 0", width = 1.8f)
    val WifiOff = line(
        "M2 2l20 20",
        "M8.5 16.5a5 5 0 016.6-.4",
        "M5 12.5a10 10 0 013-2.2",
        "M19 12.5a10 10 0 00-3.2-2.3",
        "M2.5 8.5a15 15 0 014-2.4",
        "M21.5 8.5a15 15 0 00-6.7-3"
    )
    val Fingerprint = line(
        "M12 4.2c-4.3 0-7.8 3.5-7.8 7.8v2.4M19.8 12c0-4.3-3.5-7.8-7.8-7.8",
        "M12 8a4 4 0 014 4v3.4M8 12a4 4 0 014-4",
        "M12 12v5.6M8.6 17.8V12"
    )
    val Grid = line("M4 7h16M4 12h16M4 17h16")
    val Image = line("M3 4h18v16H3z", "M3 15l5-4 4 3 3-2 6 4", "M8.5 9a1.4 1.4 0 100-2.8 1.4 1.4 0 000 2.8z")
    val Stop = fill("M7 7h10v10H7z")
    val Check = fill("M16.7 5.3a1 1 0 010 1.4l-8 8a1 1 0 01-1.4 0l-4-4a1 1 0 011.4-1.4L8 12.6l7.3-7.3a1 1 0 011.4 0z", box = 20f)
    val CheckLine = line("M5 12.5l4.5 4.5L19 7.5", width = 2f)
    val Close = line("M6 6l12 12M18 6L6 18", width = 2f)
    val Pause = fill("M8 5h3v14H8z", "M13 5h3v14h-3z")
    val SearchDocs = line("M4 4h11l5 5v11H4z", "M8 8h5M8 12h8M8 16h6")
    val Inbox = line(
        "M4 13l2.2-7.2A2 2 0 018.1 4h7.8a2 2 0 011.9 1.8L20 13",
        "M4 13h4l1.2 2.2h5.6L16 13h4v5.6A2.4 2.4 0 0117.6 21H6.4A2.4 2.4 0 014 18.6V13z"
    )
    val SignalOff = line("M2 2l20 20", "M8.5 16.5a5 5 0 016.6-.4")
}
