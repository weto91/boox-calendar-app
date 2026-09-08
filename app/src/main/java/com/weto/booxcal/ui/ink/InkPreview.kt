package com.weto.booxcal.ui.ink

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ink.InkFonts
import com.weto.booxcal.ink.NoteStorage
import com.weto.booxcal.ink.PenCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.weto.booxcal.ui.theme.Eink
import kotlin.math.max

private class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)

/** Lo que ocupa la escritura, con un margen alrededor. */
private fun contentBounds(document: InkDocument): Bounds {
    // Con imagen de fondo, la página entera: es lo que hay que ver.
    if (document.background != null) {
        return Bounds(0f, 0f, max(document.canvasWidth, 1f), max(document.canvasHeight, 1f))
    }
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    document.strokes.forEach { stroke ->
        stroke.points.forEach {
            if (it.x < left) left = it.x
            if (it.y < top) top = it.y
            if (it.x > right) right = it.x
            if (it.y > bottom) bottom = it.y
        }
    }
    document.texts.forEach { text ->
        val lines = text.lines
        val width = lines.maxOf { it.length } * text.sizePx * 0.55f
        val height = lines.size * text.sizePx * 1.25f
        if (text.x < left) left = text.x
        if (text.y < top) top = text.y
        if (text.x + width > right) right = text.x + width
        if (text.y + height > bottom) bottom = text.y + height
    }
    if (left > right || top > bottom) return Bounds(0f, 0f, max(document.canvasWidth, 1f), max(document.canvasHeight, 1f))
    val pad = max(right - left, bottom - top) * 0.08f + 4f
    return Bounds(left - pad, top - pad, right - left + pad * 2, bottom - top + pad * 2)
}

/**
 * Miniatura de una nota manuscrita.
 *
 * Redibuja los trazos guardados en vez de guardar una imagen: una nota puede
 * repintarse a cualquier tamaño sin perder nitidez, y no hay un segundo formato
 * de datos que mantener sincronizado con el primero.
 */
@Composable
fun InkPreview(
    document: InkDocument,
    modifier: Modifier = Modifier,
    tint: Color = Eink.Black,
) {
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint.toArgb()
        typeface = InkFonts.typeface(context, settings.inkTextFont)
    }
    // La imagen de fondo, reducida (una miniatura no necesita más) y fuera del hilo principal.
    val background by produceState<Bitmap?>(initialValue = null, document.background) {
        value = document.background?.let { name -> withContext(Dispatchers.IO) { NoteStorage.loadImage(name, 8) } }
    }

    Canvas(modifier) {
        if (document.isEmpty) return@Canvas

        // Se encaja lo escrito, no la hoja entera: una línea arriba de una
        // hoja A4 saldría como un hilo en la miniatura.
        val content = contentBounds(document)
        val sourceWidth = max(content.width, 1f)
        val sourceHeight = max(content.height, 1f)
        // Encaje por el lado más restrictivo, sin deformar la escritura.
        val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
        val offsetX = (size.width - sourceWidth * scale) / 2f - content.left * scale
        val offsetY = (size.height - sourceHeight * scale) / 2f - content.top * scale

        background?.let { bitmap ->
            drawIntoCanvas { canvas ->
                val dst = RectF(offsetX, offsetY, offsetX + document.canvasWidth * scale, offsetY + document.canvasHeight * scale)
                canvas.nativeCanvas.drawBitmap(bitmap, null, dst, null)
            }
        }

        document.strokes.forEach { stroke ->
            val points = stroke.points
            if (points.isEmpty()) return@forEach

            val path = Path()
            path.moveTo(offsetX + points[0].x * scale, offsetY + points[0].y * scale)
            if (points.size == 1) {
                path.lineTo(offsetX + points[0].x * scale, offsetY + points[0].y * scale)
            }
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                path.quadraticTo(
                    offsetX + points[i].x * scale,
                    offsetY + points[i].y * scale,
                    offsetX + midX * scale,
                    offsetY + midY * scale,
                )
            }
            val last = points.last()
            path.lineTo(offsetX + last.x * scale, offsetY + last.y * scale)

            // El subrayador, en su color y translúcido, con extremos rectos:
            // ancho como es, pintado en negro tapaba la miniatura.
            val marker = stroke.tool == "MARKER"
            val width = when {
                marker && stroke.legacy -> PenCanvasView.markerWidth(stroke.width)
                else -> stroke.width
            }
            drawPath(
                path = path,
                color = if (marker) Color(stroke.colorArgb).copy(alpha = 0.4f) else tint,
                style = Stroke(
                    width = max(width * scale, 1f),
                    cap = if (marker) StrokeCap.Square else StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // Los textos convertidos, con el mismo encaje que los trazos.
        if (document.texts.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                document.texts.forEach { text ->
                    val size = max(text.sizePx * scale, 1f)
                    textPaint.textSize = size
                    val lineHeight = size * 1.25f
                    text.lines.forEachIndexed { index, line ->
                        canvas.nativeCanvas.drawText(
                            line,
                            offsetX + text.x * scale,
                            offsetY + text.y * scale + lineHeight * index + size * 0.9f,
                            textPaint,
                        )
                    }
                }
            }
        }
    }
}
