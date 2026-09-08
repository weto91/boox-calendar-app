package com.weto.booxcal.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.onyx.android.sdk.data.PenConstant
import com.onyx.android.sdk.data.note.PenAttrs
import com.onyx.android.sdk.data.note.PenTexture
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TiltConfig
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenV2Wrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.NeoPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.PenRenderArgs
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.PenUtils as PenDraw
import com.onyx.android.sdk.pen.utils.PenUtils as PenParams

private const val TAG = "SdkInk"

/**
 * Los pinceles del SDK de Onyx, tal y como los usa el demo oficial
 * (`OnyxPenDemo`, `shape/...ScribbleShape.java`, onyxsdk-pen 1.5.4).
 *
 * Con el trazo rápido, el SDK pinta la línea en el panel por su cuenta con
 * el estilo elegido: carboncillo (el lápiz con grano de Boox Notes, en dos
 * texturas), pluma (grosor por presión, con sensibilidad), línea fija,
 * marcador o pincel. Al soltar, la app repinta el trazo en su hoja con el
 * pincel del SDK de ese mismo estilo: es la única manera de que lo que se
 * ve al escribir sea lo que queda cuando la región se repinta (goma, lazo,
 * una ventana). Cualquier pincel propio, por parecido que sea, cambia el
 * trazo de cara en ese momento.
 */
object SdkInk {

    /** Milímetros a píxeles como lo hace el SDK: con la densidad nominal de la pantalla. */
    fun mmToPx(context: Context, mm: Float): Float =
        mm * context.resources.displayMetrics.densityDpi / MM_PER_INCH

    /**
     * Estilo del SDK para cada punta. El lápiz es el carboncillo, con la
     * textura 1 o 2; el boli, la línea fija sin sensibilidad y la pluma con
     * ella; el subrayador, el marcador.
     */
    fun styleFor(tool: PenTool, texture: Int, sensitivity: Float): Int = when (tool) {
        PenTool.MARKER -> TouchHelper.STROKE_STYLE_MARKER
        PenTool.BALLPOINT ->
            if (sensitivity <= 0f) TouchHelper.STROKE_STYLE_PENCIL else TouchHelper.STROKE_STYLE_FOUNTAIN
        else -> if (texture >= 1) TouchHelper.STROKE_STYLE_CHARCOAL_V2 else TouchHelper.STROKE_STYLE_CHARCOAL
    }

    fun styleName(style: Int): String = when (style) {
        TouchHelper.STROKE_STYLE_PENCIL -> "línea fija"
        TouchHelper.STROKE_STYLE_FOUNTAIN -> "pluma"
        TouchHelper.STROKE_STYLE_MARKER -> "marcador"
        TouchHelper.STROKE_STYLE_NEO_BRUSH -> "pincel"
        TouchHelper.STROKE_STYLE_CHARCOAL -> "carboncillo 1"
        TouchHelper.STROKE_STYLE_CHARCOAL_V2 -> "carboncillo 2"
        else -> "estilo $style"
    }

    /**
     * Sensibilidad de la pluma: a 50 % la del sistema (la que usa Boox
     * Notes), a 100 % el doble, a 0 % la línea fija (otro estilo).
     */
    private fun fountainPressure(sensitivity: Float): Float =
        PenParams.KEPLER_DEFAULT_PRESSURE_SENSITIVITY * (sensitivity.coerceIn(0f, 1f) * 2f)

    /**
     * Los parámetros del estilo viven en el dispositivo y el SDK los lee al
     * pintar en vivo: aquí se le pone a la pluma su sensibilidad, igual que
     * hace el demo (`PenManager.applyStrokeParameters`).
     */
    fun applyStrokeParameters(style: Int, sensitivity: Float) {
        if (style != TouchHelper.STROKE_STYLE_FOUNTAIN) return
        runCatching {
            val device = Device.currentDevice()
            val current = device.getStrokeParameters(style)
            val next = FloatArray(maxOf(current?.size ?: 0, 2))
            current?.copyInto(next)
            next[0] = fountainPressure(sensitivity)
            next[1] = PenParams.DEFAULT_SMOOTH_LEVEL
            device.setStrokeParameters(style, next)
        }.onFailure { Log.w(TAG, "No se pudo fijar la sensibilidad de la pluma: ${it.message}") }
    }

    /**
     * Pinta un trazo con el pincel del SDK de su estilo. [screenMatrix] lleva
     * de la hoja a la pantalla: el carboncillo alinea su grano con los
     * píxeles del panel, y así el repintado cae exactamente sobre lo que el
     * SDK pintó en vivo. Devuelve false si el SDK no supo pintarlo.
     */
    fun draw(
        context: Context,
        canvas: Canvas,
        paint: Paint,
        style: Int,
        sensitivity: Float,
        points: List<InkPoint>,
        colorArgb: Int,
        widthPx: Float,
        screenMatrix: Matrix,
    ): Boolean {
        if (points.isEmpty()) return false
        val maxPressure = EinkRefresh.maxTouchPressure.takeIf { it > 1f } ?: DEFAULT_MAX_PRESSURE
        applyStrokePaint(paint, colorArgb, widthPx)
        val drawn = runCatching {
            when (style) {
                TouchHelper.STROKE_STYLE_PENCIL -> drawPlain(canvas, paint, points)
                TouchHelper.STROKE_STYLE_FOUNTAIN -> drawFountain(canvas, paint, points, widthPx, sensitivity)
                TouchHelper.STROKE_STYLE_MARKER ->
                    drawMarker(canvas, paint, touchPoints(points, maxPressure), widthPx, maxPressure)
                TouchHelper.STROKE_STYLE_NEO_BRUSH ->
                    drawNeoBrush(canvas, paint, touchPoints(points, maxPressure), widthPx, maxPressure)
                TouchHelper.STROKE_STYLE_CHARCOAL, TouchHelper.STROKE_STYLE_CHARCOAL_V2 ->
                    drawCharcoal(canvas, paint, style, touchPoints(points, maxPressure), colorArgb, widthPx, screenMatrix)
                else -> return false
            }
            true
        }.onFailure { Log.w(TAG, "El pincel del SDK (${styleName(style)}) falló", it) }
            .getOrDefault(false)
        paint.xfermode = null
        paint.style = Paint.Style.STROKE
        return drawn
    }

    /** El Paint como lo deja `Shape.applyStrokeStyle` del demo. */
    private fun applyStrokePaint(paint: Paint, colorArgb: Int, widthPx: Float) {
        paint.reset()
        paint.strokeWidth = widthPx
        paint.color = colorArgb
        paint.isAntiAlias = true
        paint.isDither = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = 4f
        paint.pathEffect = null
        paint.xfermode = null
    }

    // --- Cada estilo, como en el demo ---------------------------------------

    /** `NormalPencilShape`: la línea de grosor fijo. */
    private fun drawPlain(canvas: Canvas, paint: Paint, points: List<InkPoint>) {
        val path = Path()
        var prevX = points[0].x
        var prevY = points[0].y
        path.moveTo(prevX, prevY)
        points.forEach { point ->
            path.quadTo(prevX, prevY, point.x, point.y)
            prevX = point.x
            prevY = point.y
        }
        canvas.drawPath(path, paint)
    }

    /** `BrushScribbleShape`: la pluma, con la presión entre 0 y 1 y relleno. */
    private fun drawFountain(canvas: Canvas, paint: Paint, points: List<InkPoint>, widthPx: Float, sensitivity: Float) {
        if (points.size < 2) return
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        val normalized = points.map { point ->
            TouchPoint(point.x, point.y, point.pressure.coerceIn(0f, 1f), DEFAULT_SIZE, point.t)
                .also { applyTilt(it, point) }
        }
        val pen = createFountainPen(widthPx, sensitivity) ?: return
        val render = NeoPenRender(pen)
        try {
            render.onTouchPointList(normalized)
            render.render(canvas, paint)
        } finally {
            render.destroyPen()
        }
    }

    /**
     * `FountainShapes.INSTANCE.createNeoPenV2(...)` del demo. `FountainShapes`
     * es un singleton; se llama por reflexión porque, según cómo esté
     * compilado el SDK, desde Kotlin se ve como objeto o como clase con
     * campo `INSTANCE`, y las dos formas no compilan a la vez.
     */
    private fun createFountainPen(widthPx: Float, sensitivity: Float): NeoPen? = runCatching {
        val clazz = Class.forName("com.onyx.android.sdk.pen.utils.FountainShapes")
        val instance = clazz.getField("INSTANCE").get(null)
        val method = clazz.methods.first { it.name == "createNeoPenV2" && it.parameterTypes.size == 9 }
        method.invoke(
            instance,
            widthPx,
            NeoFountainPenWrapper.MIN_FOUNTAIN_PEN_WIDTH,
            1f, 1f, 1f, 1f,
            fountainPressure(sensitivity),
            true,
            PenParams.DEFAULT_SMOOTH_LEVEL,
        ) as? NeoPen
    }.onFailure { Log.w(TAG, "Sin pluma del SDK: ${it.message}") }.getOrNull()

    /** `MarkerScribbleShape`: el marcador, en modo oscurecer. */
    private fun drawMarker(canvas: Canvas, paint: Paint, points: List<TouchPoint>, widthPx: Float, maxPressure: Float) {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        val markerPoints = NeoMarkerPenWrapper.computeStrokePoints(points, widthPx, maxPressure)
        NeoMarkerPenWrapper.drawStroke(canvas, paint, markerPoints, widthPx, false)
    }

    /** `NewBrushScribbleShape`: el pincel. */
    private fun drawNeoBrush(canvas: Canvas, paint: Paint, points: List<TouchPoint>, widthPx: Float, maxPressure: Float) {
        if (points.size < 2) return
        val brushPoints = NeoBrushPenWrapper.computeStrokePoints(points, widthPx, maxPressure)
        if (brushPoints == null || brushPoints.size < 2) return
        PenDraw.drawStrokeByPointSize(canvas, paint, brushPoints, false)
    }

    /** `CharcoalScribbleShape`: el carboncillo, textura 1 o 2, con la inclinación que tenga el dispositivo. */
    private fun drawCharcoal(
        canvas: Canvas,
        paint: Paint,
        style: Int,
        points: List<TouchPoint>,
        colorArgb: Int,
        widthPx: Float,
        screenMatrix: Matrix,
    ) {
        val v2 = style == TouchHelper.STROKE_STYLE_CHARCOAL_V2
        val texture = if (v2) PenTexture.CHARCOAL_SHAPE_V2 else PenTexture.CHARCOAL_SHAPE_V1
        val tilt = tiltConfig(style)
        val createArgs = ShapeCreateArgs()
            .setPenAttrs(PenAttrs().setTexture(texture))
            .setTiltConfig(tilt)
        val args = PenRenderArgs()
            .setCreateArgs(createArgs)
            .setCanvas(canvas)
            .setPenType(if (v2) NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL_V2 else NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL)
            .setColor(colorArgb)
            .setErase(false)
            .setTiltEnabled(tilt?.isTiltEnabled == true)
            .setPaint(paint)
            .setScreenMatrix(screenMatrix)
            .setStrokeWidth(widthPx)
            .setPoints(points)
        val normal = widthPx <= PenConstant.CHARCOAL_SHAPE_DRAW_NORMAL_SCALE_WIDTH_THRESHOLD
        if (v2) {
            if (normal) NeoCharcoalPenV2Wrapper.drawNormalStroke(args)
            else NeoCharcoalPenV2Wrapper.drawBigStroke(args.setRenderMatrix(screenMatrix))
        } else {
            if (normal) NeoCharcoalPenWrapper.drawNormalStroke(args)
            else NeoCharcoalPenWrapper.drawBigStroke(args.setRenderMatrix(screenMatrix))
        }
    }

    /** La inclinación del carboncillo la fija el dispositivo: `[activada, escala]`. */
    private fun tiltConfig(style: Int): TiltConfig? = runCatching {
        val parameters = Device.currentDevice().getStrokeParameters(style)
        if (parameters == null || parameters.size < 2) null
        else TiltConfig().setTiltEnabled(parameters[0] != 0f).setTiltScale(parameters[1])
    }.getOrNull()

    // --- Puntos ---------------------------------------------------------------

    /** Los puntos como los quiere el SDK: presión en bruto, con su máximo. */
    private fun touchPoints(points: List<InkPoint>, maxPressure: Float): List<TouchPoint> =
        points.map { point ->
            TouchPoint(point.x, point.y, point.pressure.coerceIn(0f, 1f) * maxPressure, DEFAULT_SIZE, point.t)
                .also { applyTilt(it, point) }
        }

    /**
     * La inclinación del punto, si el `TouchPoint` de esta versión la lleva
     * (`tiltX`/`tiltY`): se pone por reflexión para no depender del nombre.
     */
    private fun applyTilt(target: TouchPoint, point: InkPoint) {
        if (point.tiltX == 0f && point.tiltY == 0f) return
        TILT_X?.let { runCatching { it.setInt(target, point.tiltX.toInt()) } }
        TILT_Y?.let { runCatching { it.setInt(target, point.tiltY.toInt()) } }
    }

    /** `tiltX`/`tiltY` de un punto del SDK, en grados, o ceros si no los trae. */
    fun readTilt(point: TouchPoint): Pair<Float, Float> {
        val x = TILT_X?.let { runCatching { (it.get(point) as Number).toFloat() }.getOrNull() } ?: 0f
        val y = TILT_Y?.let { runCatching { (it.get(point) as Number).toFloat() }.getOrNull() } ?: 0f
        return x to y
    }

    private val TILT_X = runCatching { TouchPoint::class.java.getField("tiltX") }.getOrNull()
    private val TILT_Y = runCatching { TouchPoint::class.java.getField("tiltY") }.getOrNull()

    private const val MM_PER_INCH = 25.4f
    private const val DEFAULT_SIZE = 1f
    /** Si el SDK no dice su máximo, la presión es de 12 bits. */
    private const val DEFAULT_MAX_PRESSURE = 4096f
}
