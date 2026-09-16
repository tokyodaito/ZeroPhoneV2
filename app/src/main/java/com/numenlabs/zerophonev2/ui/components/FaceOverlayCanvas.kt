package com.numenlabs.zerophonev2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.numenlabs.zerophonev2.gate.FaceUiData
import kotlin.math.max

/** Overlay status: drives the color of the face "графы". */
enum class FaceOverlayStatus { ATTENTION, WARMING_UP, NO_FACE }

/**
 * Draws ML Kit face contours over the camera preview: a polyline + dots per
 * contour, plus the bounding box. Green = attention held (timer running),
 * yellow = face detected but not confirmed yet, red = no face / gate stopped.
 *
 * Coordinates: analysis points are rotated to upright, mirrored (front
 * camera) and center-crop-scaled — matching PreviewView's FILL_CENTER.
 */
@Composable
fun FaceOverlayCanvas(
    data: FaceUiData?,
    status: FaceOverlayStatus,
    modifier: Modifier = Modifier,
) {
    val color =
        when (status) {
            FaceOverlayStatus.ATTENTION -> Color(0xFF4CAF50)
            FaceOverlayStatus.WARMING_UP -> Color(0xFFFFC107)
            FaceOverlayStatus.NO_FACE -> Color(0xFFF44336)
        }
    Canvas(modifier = modifier) {
        val d = data ?: return@Canvas
        val swapped = d.rotationDegrees == 90 || d.rotationDegrees == 270
        val uprightW = (if (swapped) d.imageHeight else d.imageWidth).toFloat()
        val uprightH = (if (swapped) d.imageWidth else d.imageHeight).toFloat()
        val scale = max(size.width / uprightW, size.height / uprightH)
        val dx = (size.width - uprightW * scale) / 2f
        val dy = (size.height - uprightH * scale) / 2f

        fun toCanvas(
            x: Float,
            y: Float,
        ): Offset {
            // 1) Rotate the image point to upright space (clockwise).
            val (rx, ry) =
                when (d.rotationDegrees) {
                    90 -> (d.imageHeight - y) to x
                    180 -> (d.imageWidth - x) to (d.imageHeight - y)
                    270 -> y to (d.imageWidth - x)
                    else -> x to y
                }
            // 2) Mirror horizontally: the front preview is mirrored, analysis is not.
            val mx = uprightW - rx
            // 3) Center-crop scale onto the canvas.
            return Offset(mx * scale + dx, ry * scale + dy)
        }

        d.contours.forEach { contour ->
            if (contour.size < 2) return@forEach
            val path = Path()
            contour.forEachIndexed { index, point ->
                val o = toCanvas(point.x, point.y)
                if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color = color, style = Stroke(width = 3f))
            contour.forEach { point ->
                drawCircle(color = color, radius = 3f, center = toCanvas(point.x, point.y))
            }
        }

        d.boundingBox?.let { box ->
            val tl = toCanvas(box.left.toFloat(), box.top.toFloat())
            val br = toCanvas(box.right.toFloat(), box.bottom.toFloat())
            drawRect(
                color = color,
                topLeft = tl,
                size = Size(br.x - tl.x, br.y - tl.y),
                style = Stroke(width = 4f),
            )
        }
    }
}
