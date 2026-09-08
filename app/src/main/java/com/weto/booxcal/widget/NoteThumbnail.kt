package com.weto.booxcal.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ink.NoteStorage
import kotlin.math.max

/**
 * Miniatura de una página de nota para los widgets, pintada a mano con el
 * `Canvas` de Android (en un widget no hay Compose): encaja lo escrito, o
 * la página entera si tiene imagen de fondo, en el tamaño pedido.
 */
object NoteThumbnail {

    fun render(document: InkDocument, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (document.isEmpty) return bitmap

        val bounds = contentBounds(document)
        val scale = minOf(width / max(bounds.width(), 1f), height / max(bounds.height(), 1f))
        val offsetX = (width - bounds.width() * scale) / 2f - bounds.left * scale
        val offsetY = (height - bounds.height() * scale) / 2f - bounds.top * scale

        document.background?.let { name ->
            NoteStorage.loadImage(name, 8)?.let { image ->
                val dst = RectF(offsetX, offsetY, offsetX + document.canvasWidth * scale, offsetY + document.canvasHeight * scale)
                canvas.drawBitmap(image, null, dst, null)
                image.recycle()
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        document.strokes.forEach { stroke ->
            val points = stroke.points
            if (points.isEmpty()) return@forEach
            val marker = stroke.tool == "MARKER"
            paint.color = if (marker) stroke.colorArgb else Color.BLACK
            paint.alpha = if (marker) 100 else 255
            paint.strokeWidth = max(stroke.width * scale, 1f)
            val path = Path()
            path.moveTo(offsetX + points[0].x * scale, offsetY + points[0].y * scale)
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                path.quadTo(offsetX + points[i].x * scale, offsetY + points[i].y * scale, offsetX + midX * scale, offsetY + midY * scale)
            }
            val last = points.last()
            path.lineTo(offsetX + last.x * scale, offsetY + last.y * scale)
            canvas.drawPath(path, paint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        document.texts.forEach { text ->
            val size = max(text.sizePx * scale, 1f)
            textPaint.textSize = size
            text.lines.forEachIndexed { index, line ->
                canvas.drawText(line, offsetX + text.x * scale, offsetY + text.y * scale + size * 1.25f * index + size * 0.9f, textPaint)
            }
        }
        return bitmap
    }

    /** Lo que ocupa lo escrito, con un margen; la página entera si hay imagen de fondo. */
    private fun contentBounds(document: InkDocument): RectF {
        if (document.background != null) {
            return RectF(0f, 0f, max(document.canvasWidth, 1f), max(document.canvasHeight, 1f))
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
            val width = text.lines.maxOf { it.length } * text.sizePx * 0.55f
            val height = text.lines.size * text.sizePx * 1.25f
            if (text.x < left) left = text.x
            if (text.y < top) top = text.y
            if (text.x + width > right) right = text.x + width
            if (text.y + height > bottom) bottom = text.y + height
        }
        if (left > right || top > bottom) return RectF(0f, 0f, max(document.canvasWidth, 1f), max(document.canvasHeight, 1f))
        val pad = max(right - left, bottom - top) * 0.08f + 4f
        return RectF(left - pad, top - pad, right + pad, bottom + pad)
    }
}
