package com.weto.booxcal.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The brushes of the app, in one place: the canvas paints with them while
 * writing, and the previews (the notebook list, a note attached to an event)
 * paint with the very same ones, so a stroke keeps its grain wherever it is
 * shown instead of turning into a flat line.
 *
 * One instance per canvas or preview: it owns two [Paint]s and the cache of
 * grain shaders.
 */
class StrokeRenderer(private val context: Context) {

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** For the SDK brushes, which leave the Paint as they please. */
    private val sdkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Paints a whole page scaled and offset into [canvas], as a preview does.
     * Points and widths are transformed, not the canvas: the grain then stays
     * at device resolution instead of shrinking with the page. Below
     * [FAITHFUL_MIN_SCALE] the strokes go flat and at least a pixel wide: a
     * thumbnail is for telling notes apart, and grain at that size vanishes.
     */
    fun drawDocument(canvas: Canvas, document: InkDocument, scale: Float, offsetX: Float, offsetY: Float) {
        val faithful = scale >= FAITHFUL_MIN_SCALE
        val identity = Matrix()
        document.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val width = displayWidth(stroke) * scale
            val moved = stroke.copy(
                points = stroke.points.map { it.copy(x = offsetX + it.x * scale, y = offsetY + it.y * scale) },
                width = width,
            )
            if (faithful) {
                drawStroke(canvas, moved, identity, widthOverride = width)
            } else {
                val strokeTool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
                applyPaint(strokeTool, max(width, 1f), stroke.colorArgb)
                canvas.drawPath(strokePath(moved.points), paint)
            }
        }
    }

    /**
     * Grosor en píxeles con el que se pinta un trazo. Los de antes de los
     * milímetros guardaban el grosor del cono, y el subrayador y el boli
     * lo transformaban al pintar; se sigue haciendo con ellos.
     */
    fun displayWidth(stroke: Stroke): Float {
        val strokeTool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
        if (!stroke.legacy) return stroke.width
        return when (strokeTool) {
            PenTool.MARKER -> markerWidth(stroke.width)
            PenTool.BALLPOINT -> stroke.width * BALLPOINT_WIDTH_FACTOR
            else -> stroke.width
        }
    }

    fun applyPaint(tool: PenTool, width: Float, colorArgb: Int) {
        paint.color = colorArgb
        paint.shader = null
        paint.colorFilter = null
        paint.xfermode = null
        paint.strokeWidth = width
        when (tool) {
            PenTool.MARKER -> {
                paint.alpha = MARKER_ALPHA
                paint.strokeCap = Paint.Cap.SQUARE
            }

            else -> {
                paint.alpha = 255
                paint.strokeCap = Paint.Cap.ROUND
            }
        }
    }

    /**
     * Paints one stroke exactly as the canvas does: with the SDK brush it was
     * drawn live with, or the app's pencil grain and ballpoint. [screenMatrix]
     * aligns the charcoal grain with the panel; identity anywhere off screen.
     * [widthOverride] replaces the stroke's display width (a scaled preview).
     */
    fun drawStroke(canvas: Canvas, stroke: Stroke, screenMatrix: Matrix, widthOverride: Float? = null) {
        val points = stroke.points
        if (points.isEmpty()) return

        val strokeTool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
        val width = widthOverride ?: displayWidth(stroke)
        applyPaint(strokeTool, width, stroke.colorArgb)

        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }

        // Lo que pintó el SDK en vivo se repinta con su mismo pincel: así el
        // trazo no cambia de cara al repintar la región. El «pencil» del SDK
        // es una línea lisa de grosor fijo, y esa la pinta la app tal cual.
        if (stroke.sdkStyle >= 0) {
            if (SdkInk.draw(context, canvas, sdkPaint, stroke.sdkStyle, stroke.sensitivity, points, stroke.colorArgb, width, screenMatrix)) {
                return
            }
            // El pincel del SDK falló: lo más parecido a lo que pintó en vivo.
            applyPaint(strokeTool, width, stroke.colorArgb)
            when (strokeTool) {
                PenTool.MARKER, PenTool.BALLPOINT -> canvas.drawPath(strokePath(points), paint)
                else -> drawPressured(canvas, points, width)
            }
            return
        }

        when (strokeTool) {
            PenTool.PENCIL ->
                if (stroke.legacy) drawPressured(canvas, points, width)
                else drawPencil(canvas, points, width, stroke.texture, stroke.sensitivity, stroke.colorArgb)
            PenTool.BALLPOINT -> drawBallpoint(canvas, points, width, stroke.sensitivity, stroke.colorArgb)
            else -> canvas.drawPath(strokePath(points), paint)
        }
    }

    /** Curva suave por los puntos: cada uno es el control hacia el punto medio del siguiente. */
    fun strokePath(points: List<InkPoint>): Path {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f
            val midY = (points[i].y + points[i + 1].y) / 2f
            path.quadTo(points[i].x, points[i].y, midX, midY)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    /**
     * El lápiz liso de antes, para los trazos guardados entonces: cada tramo
     * con su grosor, entre la mitad y una vez y media del elegido.
     */
    private fun drawPressured(canvas: Canvas, points: List<InkPoint>, base: Float) {
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            paint.strokeWidth = pressuredWidth(base, (a.pressure + b.pressure) / 2f)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private fun pressuredWidth(base: Float, pressure: Float): Float =
        base * (PRESSURE_MIN_FACTOR + (PRESSURE_MAX_FACTOR - PRESSURE_MIN_FACTOR) * pressure.coerceIn(0f, 1f))

    // --- Lápiz ----------------------------------------------------------------

    /**
     * Lápiz de mina: el trazo tiene grano, como el grafito sobre el papel.
     *
     * El grano es una trama de puntos del color del trazo sobre papel en
     * blanco, sin grises: en e-ink, mientras se escribe el panel va en modo
     * rápido de dos tonos, y un gris se ve negro o no se ve; si el trazo
     * llevara grises, cambiaría de cara al pasar el panel a su modo normal.
     * Con puntos de tinta o papel, lo que se ve al escribir es lo que
     * queda. La trama está fija sobre la hoja y cada tramo se pinta a
     * través de ella en modo «oscurecer», así donde dos tramos se pisan no
     * cambia nada.
     *
     * Apretando, los puntos se juntan (el trazo se oscurece y se alisa,
     * pero nunca del todo, que un lápiz no es un boli) y el trazo se
     * ensancha algo. Tumbando el lápiz se escribe con el costado de la
     * mina: el trazo se ensancha mucho y el grano se abre, que es como se
     * sombrea. Las tramas se generan del mismo ruido: un punto que es tinta
     * flojo sigue siendo tinta apretando, y el trazo crece sin saltos.
     */
    private fun drawPencil(canvas: Canvas, points: List<InkPoint>, base: Float, texture: Int, sensitivity: Float, colorArgb: Int) {
        for (i in 1 until points.size) {
            drawPencilSegment(canvas, points[i - 1], points[i], base, texture, sensitivity, colorArgb)
        }
    }

    fun drawPencilSegment(
        canvas: Canvas,
        a: InkPoint,
        b: InkPoint,
        base: Float,
        texture: Int,
        sensitivity: Float,
        colorArgb: Int,
    ) {
        val raw = if (a.pressure <= 0f && b.pressure <= 0f) 0.5f else ((a.pressure + b.pressure) / 2f).coerceIn(0f, 1f)
        // La sensibilidad estira o encoge la presión alrededor del medio: a
        // 0 % el trazo no cambia con ella; a 100 % responde el doble.
        val pressure = (0.5f + (raw - 0.5f) * sensitivity.coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f)
        val tilt = ((a.tilt + b.tilt) / 2f).coerceIn(0f, 1f)
        val width = base *
            (PENCIL_PRESSURE_MIN + (PENCIL_PRESSURE_MAX - PENCIL_PRESSURE_MIN) * pressure) *
            (1f + PENCIL_TILT_WIDENING * tilt)
        val floor = if (texture >= 1) PENCIL_DENSITY_MIN_DENSE else PENCIL_DENSITY_MIN_SOFT
        val density = (floor + (PENCIL_DENSITY_MAX - floor) * pressure) * (1f - PENCIL_TILT_OPENING * tilt)

        paint.shader = grainShader(colorArgb, density)
        paint.colorFilter = null
        paint.alpha = 255
        paint.xfermode = DARKEN
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width.coerceAtLeast(1f)
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        paint.shader = null
        paint.xfermode = null
    }

    private val grainShaders = HashMap<Long, BitmapShader>()

    /** El ruido del grano, uno por píxel de la trama, fijo: la misma nota se pinta igual cada vez. */
    private val grainNoise: FloatArray by lazy {
        val random = Random(GRAIN_SEED)
        FloatArray(GRAIN_SIZE * GRAIN_SIZE) { random.nextFloat() }
    }

    /** La trama para un color y una densidad (cuantizada); se crea una vez. */
    private fun grainShader(colorArgb: Int, density: Float): BitmapShader {
        val level = (density.coerceIn(0f, 1f) * GRAIN_LEVELS).roundToInt()
        val key = ((colorArgb.toLong() and 0xFFFFFFFFL) shl 8) or level.toLong()
        return grainShaders.getOrPut(key) {
            BitmapShader(makeGrain(colorArgb, level.toFloat() / GRAIN_LEVELS), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    /** Trama opaca: tinta donde el ruido queda por debajo de la densidad, papel en el resto. */
    private fun makeGrain(colorArgb: Int, density: Float): Bitmap {
        val ink = colorArgb or (0xFF shl 24)
        val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) { i -> if (grainNoise[i] < density) ink else Color.WHITE }
        return Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888)
    }

    // --- Bolígrafo ---------------------------------------------------------

    /**
     * Bolígrafo: una bola que rueda. Responde a la presión según la
     * sensibilidad elegida (de nada a mucho) y a la velocidad: en un tramo
     * rápido la bola reparte menos tinta y la línea sale más fina. Tumbado,
     * el boli se afina: la bola toca el papel de refilón. Al posarse deja una
     * gota, y donde dos tramos se solapan no se oscurece (modo «oscurecer»).
     * La tinta es siempre del color pleno, sin aclarar: en e-ink un gris se
     * ve negro mientras se escribe y gris después, y el trazo cambiaría.
     */
    private fun drawBallpoint(canvas: Canvas, points: List<InkPoint>, base: Float, sensitivity: Float, colorArgb: Int) {
        if (points.size >= 2) drawBallpointBlob(canvas, points[0], points[1], base, colorArgb)
        for (i in 1 until points.size) {
            drawBallpointSegment(canvas, points[i - 1], points[i], base, sensitivity, colorArgb)
        }
    }

    /** Grosor de un tramo, por presión, inclinación y velocidad. */
    private fun ballpointSegment(a: InkPoint, b: InkPoint, base: Float, sensitivity: Float): Float {
        // Sin presión (un digitalizador que no la da) se escribe como a media
        // presión, no como rozando.
        val pressure = if (a.pressure <= 0f && b.pressure <= 0f) 0.5f else ((a.pressure + b.pressure) / 2f).coerceIn(0f, 1f)
        val tilt = ((a.tilt + b.tilt) / 2f).coerceIn(0f, 1f)
        val speed = segmentSpeed(a, b)
        val fast = (speed / BALLPOINT_FAST_SPEED).coerceIn(0f, 1f)
        val width = base *
            (1f + (pressure - 0.5f) * 2f * sensitivity.coerceIn(0f, 1f) * BALLPOINT_PRESSURE_SPAN) *
            (1f - BALLPOINT_TILT_THINNING * tilt) *
            (1f - BALLPOINT_SPEED_THINNING * fast)
        return width.coerceAtLeast(1f)
    }

    fun drawBallpointSegment(canvas: Canvas, a: InkPoint, b: InkPoint, base: Float, sensitivity: Float, colorArgb: Int) {
        val width = ballpointSegment(a, b, base, sensitivity)
        paint.shader = null
        paint.colorFilter = null
        paint.color = colorArgb
        paint.alpha = 255
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.xfermode = DARKEN
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        paint.xfermode = null
    }

    /** La gota del arranque: solo si el boli se posó despacio. */
    fun drawBallpointBlob(canvas: Canvas, first: InkPoint, second: InkPoint, base: Float, colorArgb: Int) {
        if (segmentSpeed(first, second) >= BALLPOINT_BLOB_SPEED) return
        paint.shader = null
        paint.colorFilter = null
        paint.color = colorArgb
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.xfermode = DARKEN
        canvas.drawCircle(first.x, first.y, base * BALLPOINT_BLOB_FACTOR / 2f, paint)
        paint.xfermode = null
        paint.style = Paint.Style.STROKE
    }

    /** Píxeles por milisegundo entre dos puntos; con marcas de tiempo iguales, rápido. */
    private fun segmentSpeed(a: InkPoint, b: InkPoint): Float {
        val dt = (b.t - a.t).toFloat()
        val distance = hypot(b.x - a.x, b.y - a.y)
        return if (dt <= 0f) BALLPOINT_FAST_SPEED else distance / dt
    }


    companion object {
        /** Recorrido del cono de grosor de los trazos de antes de los milímetros. */
        const val BASE_WIDTH_MIN = 1.5f
        const val BASE_WIDTH_MAX = 9f

        /**
         * Recorrido del subrayador de los trazos de antes: el grosor del cono
         * se estiraba entre estos dos anchos.
         */
        private const val MARKER_MIN_PX = 45f
        private const val MARKER_MAX_PX = 120f
        const val MARKER_ALPHA = 110

        /** Ancho del subrayador para un grosor base del cono (trazos de antes). */
        fun markerWidth(base: Float): Float {
            val fraction = ((base - BASE_WIDTH_MIN) / (BASE_WIDTH_MAX - BASE_WIDTH_MIN)).coerceIn(0f, 1f)
            return MARKER_MIN_PX + fraction * (MARKER_MAX_PX - MARKER_MIN_PX)
        }

        /** Grosor del lápiz liso de antes según la presión: de la mitad a una vez y media. */
        private const val PRESSURE_MIN_FACTOR = 0.5f
        private const val PRESSURE_MAX_FACTOR = 1.5f

        /** El lápiz: más fino rozando, más grueso apretando. */
        private const val PENCIL_PRESSURE_MIN = 0.6f
        private const val PENCIL_PRESSURE_MAX = 1.3f
        /** Tumbado del todo, el trazo es dos veces y media más ancho. */
        private const val PENCIL_TILT_WIDENING = 1.5f
        /**
         * Densidad del grano (fracción de puntos con tinta): rozando, según
         * la textura; apretando sube hasta el máximo, que no llega a liso.
         * Tumbado, el grano se abre.
         */
        private const val PENCIL_DENSITY_MIN_SOFT = 0.22f
        private const val PENCIL_DENSITY_MIN_DENSE = 0.40f
        private const val PENCIL_DENSITY_MAX = 0.90f
        private const val PENCIL_TILT_OPENING = 0.4f
        /** La trama del grano: tamaño, semilla y niveles de densidad. */
        private const val GRAIN_SIZE = 64
        private const val GRAIN_SEED = 20260907L
        private const val GRAIN_LEVELS = 24

        /** El boli de antes era algo más fino que el grosor elegido. */
        private const val BALLPOINT_WIDTH_FACTOR = 0.85f
        /** Con sensibilidad al máximo, el boli va de 0,4 a 1,6 veces el grosor. */
        private const val BALLPOINT_PRESSURE_SPAN = 0.6f
        /** Tumbado del todo, el boli se queda en algo más de la mitad. */
        private const val BALLPOINT_TILT_THINNING = 0.45f
        /** Velocidad (px/ms) a partir de la cual el tramo cuenta como rápido del todo. */
        private const val BALLPOINT_FAST_SPEED = 1.4f
        /** Cuánto adelgaza un tramo rápido. */
        private const val BALLPOINT_SPEED_THINNING = 0.3f
        /** La gota del arranque: si el boli se posa más despacio que esto, y de este tamaño. */
        private const val BALLPOINT_BLOB_SPEED = 0.18f
        private const val BALLPOINT_BLOB_FACTOR = 1.5f
        private val DARKEN = PorterDuffXfermode(PorterDuff.Mode.DARKEN)

        /** Below this scale a preview is a thumbnail: grain would vanish, so strokes go flat. */
        const val FAITHFUL_MIN_SCALE = 0.25f
    }
}
