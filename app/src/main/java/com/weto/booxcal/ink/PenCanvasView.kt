package com.weto.booxcal.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

enum class PenMode { AUTO, SDK, TOUCH }

/** Punta seleccionada. Cambia el grosor, el perfil y la opacidad del trazo. */
enum class PenTool { PENCIL, BALLPOINT, MARKER, ERASER, LASSO }

/**
 * Lo que la vista sabe sobre por qué escribe o no escribe.
 *
 * No es telemetría: es la única forma de distinguir "el SDK no se montó" de "el
 * SDK se montó y no manda nada" de "el lápiz ni siquiera llega a Android".
 */
data class PenDiagnostics(
    val helperCreated: Boolean = false,
    /** El SDK ha entregado al menos un trazo de verdad. */
    val sdkProven: Boolean = false,
    val motionEvents: Int = 0,
    /** Último tipo de herramienta visto por Android: dedo, lápiz o goma. */
    val lastToolType: String = "—",
    val limitRect: String = "—",
    val error: String? = null,
    val sdkVersion: String = "—",
    /** Métodos públicos de `TouchHelper`, para ver qué API trae el SDK instalado. */
    val sdkMethods: String = "—",
    /** Métodos opcionales que se intentaron y respondieron. */
    val sdkExtras: String = "—",
) {
    val engine: String
        get() = when {
            sdkProven -> "SDK de Onyx"
            helperCreated -> "SDK montado, sin respuesta · se dibuja por táctil"
            else -> "Táctil"
        }
}

/** Portapapeles de trazos, compartido por todos los lienzos de la app. */
object InkClipboard {
    var strokes: List<Stroke> = emptyList()
        private set
    var texts: List<InkText> = emptyList()
        private set

    fun put(strokes: List<Stroke>, texts: List<InkText> = emptyList()) {
        this.strokes = strokes
        this.texts = texts
    }

    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty()
}

/**
 * Lienzo de escritura a mano.
 *
 * ### Por qué va en capa de software
 *
 * Con aceleración por hardware, `invalidate(rect)` no existe: cada movimiento
 * del lápiz repinta la vista entera, y el controlador del panel e-ink, que solo
 * puede refrescar unas pocas veces por segundo, se salta casi todos los
 * fotogramas. Eso son los "puntos sueltos" que aparecen mientras se escribe y
 * el trazo completo que sale al levantar. En capa de software la invalidación
 * es por rectángulo, el panel refresca solo lo que ha cambiado y da tiempo a
 * verlo. Junto con el modo de refresco rápido mientras el lápiz está abajo, es
 * lo más cerca del trazo nativo que se puede llegar sin el SDK.
 *
 * ### El camino táctil no espera permiso
 *
 * `TouchHelper.create` puede devolver un objeto y luego no entregar un solo
 * punto. Así que el táctil dibuja **hasta que el SDK demuestre que funciona**, y
 * solo entonces se aparta.
 */
class PenCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Los tres ajustes solo se pasan al SDK cuando cambian de verdad. Compose
    // vuelve a ejecutar el bloque `update` de la vista en cada recomposición
    // del padre (cada guardado, cada cambio de estado de sincronización), y
    // sin la guarda eso era una ráfaga de `setStrokeWidth` en mitad de la
    // escritura.
    var strokeWidth: Float = 3f
        set(value) {
            if (field == value) return
            field = value
            runCatching { touchHelper?.setStrokeWidth(sdkStrokeWidth()) }
        }

    /**
     * Píxeles por milímetro de esta pantalla. El grosor se elige en
     * milímetros, que es lo que uno ve sobre el papel; el lienzo trabaja en
     * píxeles.
     */
    val pxPerMm: Float = SdkInk.mmToPx(context, 1f)

    /** Grosor elegido, en milímetros. Es lo que se guarda con cada trazo. */
    var strokeWidthMm: Float = 0.2f
        set(value) {
            if (field == value) return
            field = value
            strokeWidth = value * pxPerMm
        }

    /** Textura del lápiz: 0 o 1, las dos del carboncillo del SDK. Cambia el estilo del SDK. */
    var pencilTexture: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (tool == PenTool.PENCIL) applyToolToSdk()
        }

    /** Cuánto responde a la presión la punta elegida: 0 nada, 1 todo. En el boli cambia además el estilo del SDK. */
    var pressureSensitivity: Float = 0.5f
        set(value) {
            if (field == value) return
            field = value
            // La pluma del SDK lee su sensibilidad del dispositivo; a cero es otro estilo (línea fija).
            if (tool == PenTool.BALLPOINT) applyToolToSdk()
        }

    /** Grosor que se le pide al SDK: el mismo en píxeles que pinta el lienzo. */
    /**
     * El SDK pinta en píxeles de pantalla y la hoja se guarda en píxeles de
     * hoja: ampliada, el trazo en vivo tiene que ir tan gordo como se verá
     * luego al repintarlo a escala.
     */
    private fun sdkStrokeWidth(): Float = strokeWidth * zoom

    /**
     * Con goma o lazo se pausa el trazo rápido del SDK: es lo que hace el demo
     * en su botón de borrar. Pausado, el lápiz vuelve a llegar como toques
     * normales y los atiende el camino táctil, que sí sabe borrar y rodear.
     */
    var tool: PenTool = PenTool.PENCIL
        set(value) {
            if (field == value) return
            field = value
            applyToolToSdk()
        }

    var colorArgb: Int = Color.BLACK
        set(value) {
            if (field == value) return
            field = value
            runCatching { touchHelper?.callIfPresent("setStrokeColor", value) }
        }

    /** Se dispara al terminar cada trazo, borrado o limpieza. */
    var onDocumentChanged: (() -> Unit)? = null

    var onEngineResolved: ((Boolean) -> Unit)? = null

    var onDiagnostics: ((PenDiagnostics) -> Unit)? = null

    /** Cuántos trazos hay dentro del lazo. Sirve para enseñar las acciones. */
    var onSelectionChanged: ((Int) -> Unit)? = null

    var penMode: PenMode = PenMode.AUTO
        set(value) {
            if (field == value) return
            field = value
            if (value == PenMode.TOUCH) {
                closeHelper()
                report()
            } else {
                setupTouchHelper()
            }
        }

    private val strokes = mutableListOf<Stroke>()
    private val texts = mutableListOf<InkText>()
    private val livePoints = mutableListOf<InkPoint>()

    /**
     * Deshacer por instantáneas: cada cambio guarda cómo estaba el documento
     * antes. Sale más simple que deshacer "el último trazo" desde que hay
     * textos, borrados de varios trazos y conversiones. Las listas comparten
     * los objetos, así que cada instantánea son dos listas de referencias.
     */
    private data class Snapshot(val strokes: List<Stroke>, val texts: List<InkText>)

    private val history = mutableListOf<Snapshot>()
    private val future = mutableListOf<Snapshot>()

    /** Lazo en curso, y los trazos y textos que ha atrapado. */
    private val lassoPath = mutableListOf<InkPoint>()
    private val selection = mutableSetOf<Int>()
    private val textSelection = mutableSetOf<Int>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.SANS_SERIF
    }

    /** Letra de los textos transcritos. Cambiarla repinta los que haya. */
    var textTypeface: android.graphics.Typeface = android.graphics.Typeface.SANS_SERIF
        set(value) {
            if (field == value) return
            field = value
            textPaint.typeface = value
            if (texts.isNotEmpty()) {
                redrawBitmap()
                repaintUnderSdk()
            }
        }

    /** Arrastre de la selección: de dónde se cogió. */
    private var dragStart: InkPoint? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private var touchHelper: TouchHelper? = null
    private var penEnabled = true
    private var strokeStartedAt = 0L

    /**
     * Lo último que se le pidió al SDK: capturando (`true`) o en pausa. Es lo
     * que decide si un toque del lápiz lo atiende el camino táctil: durante
     * una pausa el SDK deja pasar el lápiz como toques normales, y si nadie
     * los pintara, lo escrito en ese medio segundo se perdería.
     */
    private var rawActive = false

    /** Hay una reactivación del SDK programada con retardo. */
    private var resumeScheduled = false

    /** El camino táctil tiene un trazo a medias; el SDK espera a que acabe. */
    private var touchStrokeActive = false
    private var resumeAfterTouch = false

    /** Se ha escrito o borrado algo desde la última carga del documento. */
    private var editedSinceLoad = false

    /**
     * Zona con trazos que el SDK pintó directamente en el panel y que la
     * vista todavía no ha vuelto a dibujar. Mientras el trazo rápido está
     * activo no hace falta (ya se ven), pero al pausarlo el panel se
     * refresca con lo último que dibujó la vista, y esos trazos
     * desaparecían hasta que algo la invalidaba (el lazo, por ejemplo).
     */
    private var sdkInkPending: Rect? = null

    /** El último trazo lo puso el camino táctil y aún podría ser un duplicado. */
    private var lastStrokeFromTouch = false

    /** La goma del botón del lápiz está pulsada en este gesto. */
    private var erasingByButton = false

    private var diagnostics = PenDiagnostics(sdkVersion = sdkVersion())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Para los pinceles del SDK, que dejan el Paint como quieren. */
    private val sdkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.DKGRAY
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    val usesOnyxPen: Boolean get() = diagnostics.sdkProven

    val hasSelection: Boolean get() = selection.isNotEmpty() || textSelection.isNotEmpty()

    private val selectionCount: Int get() = selection.size + textSelection.size

    private val toolDraws: Boolean
        get() = tool != PenTool.ERASER && tool != PenTool.LASSO

    /**
     * Si la punta va por el trazo rápido del SDK. Hoy, todas: dejar el
     * lápiz al camino táctil dejaba la app sin responder en el Note Air 5C,
     * y hasta entender por qué, el SDK pinta en vivo y la app repinta con el
     * pincel del SDK del mismo estilo cuando lo hay.
     */
    val toolFastPath: Boolean
        get() = true

    /** Lo que el SDK debería estar haciendo: capturar o dejar pasar. */
    private val rawWanted: Boolean
        get() = penEnabled && toolDraws && toolFastPath && hasWindowFocus()

    /** El SDK está capturando el lápiz en este momento. */
    private val sdkCapturing: Boolean
        get() = touchHelper != null && diagnostics.sdkProven && rawActive

    private val resumeRunnable = Runnable {
        resumeScheduled = false
        syncRawEnabled()
    }

    private fun applyRaw(enabled: Boolean) {
        val helper = touchHelper ?: return
        runCatching { helper.setRawDrawingEnabled(enabled) }
        rawActive = enabled
    }

    /**
     * Pausa el SDK ya, y olvida cualquier reactivación pendiente. Acto
     * seguido vuelca a pantalla lo que el SDK había pintado por su cuenta:
     * es el orden del demo (apagar, luego render).
     */
    private fun pauseRaw() {
        removeCallbacks(resumeRunnable)
        resumeScheduled = false
        resumeAfterTouch = false
        if (rawActive) applyRaw(false)
        flushSdkInk()
    }

    private fun flushSdkInk() {
        val pending = sdkInkPending ?: return
        sdkInkPending = null
        invalidatePage(pending.left, pending.top, pending.right, pending.bottom)
    }

    // --- La hoja ------------------------------------------------------------

    /**
     * El lienzo es una hoja tan ancha como la vista y con la proporción de un
     * A4: lo escrito no se encoge para caber, se hace scroll vertical con el
     * dedo. `panY` es cuánto se ha bajado. Todo lo que se guarda (trazos,
     * textos, lazo, selección) va en coordenadas de la hoja; la vista solo
     * enseña una ventana de ella.
     */
    private var panY = 0f

    val pageHeight: Float get() = width * pageAspect

    /** Alto de la hoja respecto al ancho: A4 salvo que el documento sea más largo (un PDF ajeno). */
    private var pageAspect = PAGE_ASPECT

    /** Imagen de fondo de la página (un PDF ajeno), ya cargada, y su nombre. */
    private var background: Bitmap? = null
    private var backgroundName: String? = null
    private var hiddenText = ""

    private var panning = false
    private var panStartY = 0f
    private var panStartOffset = 0f

    private fun clampPan() {
        val maxY = (pageHeight - height / zoom).coerceAtLeast(0f)
        panY = panY.coerceIn(0f, maxY)
        val maxX = (width - width / zoom).coerceAtLeast(0f)
        panX = panX.coerceIn(0f, maxX)
    }

    private fun setPan(value: Float) {
        val max = (pageHeight - height / zoom).coerceAtLeast(0f)
        val clamped = value.coerceIn(0f, max)
        if (clamped == panY) return
        panY = clamped
        invalidate()
    }

    // --- Zoom ------------------------------------------------------------------

    /**
     * Ampliar, para leer mejor o para escribir fino. La hoja es vectorial,
     * así que no pierde nada. Escribiendo ampliado, los puntos del lápiz (del
     * SDK y del camino táctil) se pasan a coordenadas de la hoja dividiendo
     * por el zoom, y el grosor que se le da al SDK va multiplicado, para que
     * el trazo en vivo mida lo mismo que el repintado. `panX` es cuánto se
     * ha desplazado a la derecha, en coordenadas de la hoja.
     */
    var zoomEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) setZoom(1f)
        }

    var zoom: Float = 1f
        private set
    private var panX = 0f

    /** Cambia el zoom manteniendo el centro de la vista en su sitio. */
    fun zoomBy(factor: Float) {
        if (!zoomEnabled) return
        setZoom(zoom * factor)
    }

    fun resetZoom() = setZoom(1f)

    private fun setZoom(target: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        // El punto de la hoja bajo el foco sigue bajo el foco tras el cambio.
        val pageX = panX + focusX / zoom
        val pageY = panY + focusY / zoom
        zoom = next
        panX = pageX - focusX / zoom
        panY = pageY - focusY / zoom
        clampPan()
        // El grosor del trazo en vivo del SDK depende del zoom.
        runCatching { touchHelper?.setStrokeWidth(sdkStrokeWidth()) }
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    /** Invalida un rectángulo dado en coordenadas de la hoja. */
    private fun invalidatePage(left: Int, top: Int, right: Int, bottom: Int) {
        @Suppress("DEPRECATION")
        invalidate(
            ((left - panX) * zoom).toInt() - 1,
            ((top - panY) * zoom).toInt() - 1,
            ((right - panX) * zoom).toInt() + 2,
            ((bottom - panY) * zoom).toInt() + 2,
        )
    }

    /** De un punto de la vista (píxeles de pantalla) a la hoja. */
    private fun pageX(viewX: Float): Float = panX + viewX / zoom
    private fun pageY(viewY: Float): Float = panY + viewY / zoom

    /**
     * El dedo desplaza la hoja. Mientras dura, el SDK se pausa: con el trazo
     * rápido activo la región está congelada y el desplazamiento no se vería.
     */
    private var panStartX = 0f
    private var panStartOffsetX = 0f

    private fun handleFingerPan(event: MotionEvent): Boolean {
        // Con zoom, dos dedos amplían o reducen; mientras dure el pellizco
        // no se desplaza.
        if (zoomEnabled) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // Al cambiar el número de dedos, el arrastre parte de donde queda el que sigue.
                panStartY = event.getY(0)
                panStartX = event.getX(0)
                panStartOffset = panY
                panStartOffsetX = panX
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pageHeight <= height && !zoomEnabled) return false
                panning = true
                panStartY = event.y
                panStartX = event.x
                panStartOffset = panY
                panStartOffsetX = panX
                parent?.requestDisallowInterceptTouchEvent(true)
                pauseRaw()
                EinkRefresh.fastMode(this)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panning) return false
                if (zoomEnabled && scaleDetector.isInProgress) return true
                if (zoomEnabled && zoom > 1f) {
                    panX = panStartOffsetX - (event.x - panStartX) / zoom
                    panY = panStartOffset - (event.y - panStartY) / zoom
                    clampPan()
                    invalidate()
                } else {
                    setPan(panStartOffset - (event.y - panStartY))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!panning) return false
                panning = false
                parent?.requestDisallowInterceptTouchEvent(false)
                EinkRefresh.readableMode(this)
                invalidate()
                resumeRawDelayed()
                return true
            }

            else -> return false
        }
    }

    private fun rememberSdkInk(stroke: Stroke) {
        val pad = (displayWidth(stroke) * 2.5f + 8f).toInt()
        val bounds = Rect(
            stroke.points.minOf { it.x }.toInt() - pad,
            stroke.points.minOf { it.y }.toInt() - pad,
            stroke.points.maxOf { it.x }.toInt() + pad,
            stroke.points.maxOf { it.y }.toInt() + pad,
        )
        sdkInkPending = sdkInkPending?.apply { union(bounds) } ?: bounds
    }

    /**
     * Pone el SDK como toca: en pausa si no debe capturar; capturando en
     * cuanto acabe el trazo táctil que haya a medias, o ya si no hay ninguno.
     */
    private fun syncRawEnabled() {
        if (!rawWanted) {
            pauseRaw()
            return
        }
        if (touchStrokeActive) {
            resumeAfterTouch = true
            return
        }
        if (!rawActive) applyRaw(true)
    }

    /**
     * Reactiva el SDK con retardo. Es lo que hace el demo de Onyx después de
     * cada repintado, cambio de herramienta o ventana emergente
     * (`DELAY_ENABLE_RAW_DRAWING_MILLS`, medio segundo en las pantallas de
     * color): al activar el trazo rápido la región se congela, y si se
     * activa antes de que el panel haya llegado a mostrar el repintado, ese
     * repintado no se ve nunca. Entre medias el lápiz sigue escribiendo por
     * el camino táctil.
     */
    private fun resumeRawDelayed(delayMs: Long = RESUME_DELAY_MS) {
        removeCallbacks(resumeRunnable)
        resumeScheduled = false
        if (!rawWanted) {
            pauseRaw()
            return
        }
        if (touchHelper == null) return
        resumeScheduled = true
        postDelayed(resumeRunnable, delayMs)
    }

    /**
     * El estilo nuevo solo entra al reactivar el trazo rápido: el demo, tras
     * cambiar de punta, apaga y enciende ("refresh ui"). Con goma o lazo se
     * queda en pausa y el lápiz vuelve al camino táctil, que sí sabe borrar
     * y rodear.
     */
    private fun applyToolToSdk() {
        val helper = touchHelper ?: return
        pauseRaw()
        if (toolDraws && toolFastPath) {
            runCatching {
                val style = currentStrokeStyle()
                SdkInk.applyStrokeParameters(style, pressureSensitivity)
                helper.setStrokeWidth(sdkStrokeWidth())
                helper.setStrokeStyle(style)
            }
            resumeRawDelayed()
        }
    }

    init {
        setBackgroundColor(Color.WHITE)
        // Capa de software: ver la nota de la clase.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // --- API pública --------------------------------------------------------

    private var sourceDocument: InkDocument? = null

    fun loadDocument(document: InkDocument) {
        sourceDocument = document
        history.clear()
        future.clear()
        editedSinceLoad = false
        lastStrokeFromTouch = false
        clearSelection()
        // La hoja toma la proporción del documento si es más largo que un
        // A4 (las páginas de OneNote no tienen fin), hasta un límite.
        pageAspect = if (document.canvasWidth > 0f && document.canvasHeight > 0f) {
            (document.canvasHeight / document.canvasWidth).coerceIn(PAGE_ASPECT, MAX_PAGE_ASPECT)
        } else {
            PAGE_ASPECT
        }
        hiddenText = document.hiddenText
        loadBackground(document.background)
        clampPan()
        applySourceDocument()
        redrawBitmap()
        repaintUnderSdk()
    }

    /** La imagen de fondo se decodifica fuera del hilo principal y se pinta al llegar. */
    private fun loadBackground(name: String?) {
        if (name == backgroundName && (name == null || background != null)) return
        background?.recycle()
        background = null
        backgroundName = name
        if (name == null) return
        Thread {
            val loaded = NoteStorage.loadImage(name)
            post {
                if (backgroundName != name) {
                    loaded?.recycle()
                    return@post
                }
                background = loaded
                redrawBitmap()
                repaintUnderSdk()
            }
        }.start()
    }

    private fun applySourceDocument() {
        val source = sourceDocument ?: return
        strokes.clear()
        texts.clear()
        // A la hoja de esta vista: un documento escrito en una vista más ancha
        // o más estrecha se escala en proporción (la hoja es siempre A4).
        val scaled =
            if (width > 0 && height > 0) source.scaledTo(width.toFloat(), pageHeight)
            else source
        strokes.addAll(scaled.strokes)
        texts.addAll(scaled.texts)
    }

    fun document(): InkDocument =
        InkDocument(
            canvasWidth = width.toFloat(),
            canvasHeight = pageHeight,
            strokes = strokes.toList(),
            texts = texts.toList(),
            background = backgroundName,
            hiddenText = hiddenText,
        )

    // --- Historial ----------------------------------------------------------

    private fun snapshot() = Snapshot(strokes.toList(), texts.toList())

    private fun restore(state: Snapshot) {
        strokes.clear()
        strokes.addAll(state.strokes)
        texts.clear()
        texts.addAll(state.texts)
    }

    /** Se llama antes de cada cambio: guarda el estado al que volvería deshacer. */
    private fun recordChange() {
        history += snapshot()
        if (history.size > MAX_HISTORY) history.removeAt(0)
        future.clear()
    }

    /** Solo lo que hay dentro del lazo, como documento aparte (para el OCR). */
    fun selectionDocument(): InkDocument =
        InkDocument(
            canvasWidth = width.toFloat(),
            canvasHeight = pageHeight,
            strokes = selectedStrokes(),
        )

    fun isEmpty(): Boolean = strokes.isEmpty() && texts.isEmpty()

    // --- Toda la nota a texto -----------------------------------------------

    /**
     * Un renglón escrito a mano: qué trazos lo forman, qué sitio ocupa y con
     * qué cuerpo de letra se convierte. El cuerpo no sale de este renglón
     * solo: los renglones de alto parecido comparten uno, para que las
     * frases de una misma nota no salgan cada una de un tamaño.
     */
    class TextLine internal constructor(
        val strokeIndices: List<Int>,
        val bounds: RectF,
        val sizePx: Float,
    )

    /**
     * Agrupa los trazos en renglones para convertirlos a texto de uno en uno,
     * cada cual en su sitio. Lo que no parece escritura (un trazo muy alto
     * o muy ancho: un recuadro, una flecha, un esquema) se deja fuera y se
     * queda como está.
     *
     * Es una heurística: los trazos se ordenan de arriba abajo y cada uno se
     * une al renglón en curso si cae dentro de su banda, con un margen de
     * medio renglón; si no, empieza otro.
     */
    /**
     * @param onlyTool si se da (nombre de [PenTool]), solo los trazos de esa
     *   punta son texto y no se adivina nada: lo demás es dibujo y se queda.
     *   Sin él, entran todos y se separa dibujo de escritura por la forma.
     */
    fun textLines(onlyTool: String? = null): List<TextLine> {
        val boxes = strokes.mapIndexedNotNull { index, stroke ->
            if (onlyTool != null && stroke.tool != onlyTool) return@mapIndexedNotNull null
            strokeBounds(stroke)?.let { index to it }
        }
        if (boxes.isEmpty()) return emptyList()
        val guessDrawings = onlyTool == null
        val heights = boxes.map { it.second.height() }.filter { it > 2f }.sorted()
        val median = (heights.getOrNull(heights.size / 2) ?: return emptyList())
            .coerceAtLeast(MIN_TEXT_UNIT_PX)

        // 1. Manchas: trazos que se tocan o casi. Una palabra es una mancha
        //    (las letras van pegadas, el punto de la i cae encima), y un
        //    dibujo es otra (sus trazos se cruzan). El margen va con la
        //    altura de cada trazo, no con la mediana de la nota: así la letra
        //    grande y la pequeña se agrupan cada una a su escala.
        val parent = IntArray(boxes.size) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val reach = boxes.map { (_, box) ->
            val m = box.height().coerceAtLeast(median * 0.5f) * BLOB_MARGIN + 4f
            RectF(box.left - m, box.top - m * 0.6f, box.right + m, box.bottom + m * 0.6f)
        }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (RectF.intersects(reach[i], reach[j])) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }
        class Blob(val strokeIndices: List<Int>, val boxes: List<RectF>, val bounds: RectF) {
            val n: Int get() = strokeIndices.size
            val h: Float get() = bounds.height().coerceAtLeast(1f)
            val w: Float get() = bounds.width()
        }
        val blobs = boxes.indices.groupBy { find(it) }.values.map { members ->
            val bounds = RectF(boxes[members.first()].second)
            members.forEach { bounds.union(boxes[it].second) }
            Blob(members.map { boxes[it].first }, members.map { boxes[it].second }, bounds)
        }

        // 2. Qué mancha es dibujo. Por forma, no por tamaño de letra:
        //    - anidación: un trazo que encierra a dos o más de tamaño
        //      apreciable (la cara con sus ojos, la casa con sus ventanas).
        //      Las letras van una al lado de otra; el punto de la i y la
        //      barra de la t son pequeños y no cuentan;
        //    - solape: trazos unos encima de otros (la suma de sus cajas
        //      supera con mucho la caja de la mancha). Las letras se solapan
        //      poco;
        //    - una figura grande y no muy ancha; algo compacto con muchos
        //      trazos encima; o una raya larga suelta.
        fun isDrawing(b: Blob): Boolean {
            val aspect = b.w / b.h
            val big = b.h > median * BIG_FIGURE_UNITS && aspect < 2.5f
            val compactHeavy = b.n >= 5 && aspect < 1.3f
            val loneLine = b.n <= 2 && b.w > median * LONE_LINE_UNITS
            val nested = b.boxes.any { outer ->
                b.boxes.count { inner ->
                    inner !== outer &&
                        inner.height() >= outer.height() * NESTED_MIN_FRACTION &&
                        inner.width() < outer.width() && inner.height() < outer.height() &&
                        outer.contains(inner.centerX(), inner.centerY())
                } >= 2
            }
            val overlap = b.boxes.sumOf { (it.width() * it.height()).toDouble() } /
                (b.w * b.h).coerceAtLeast(1f)
            val sketchy = b.n >= 3 && overlap > OVERLAP_SKETCH_RATIO
            return big || compactHeavy || loneLine || nested || sketchy
        }
        val textBlobs = blobs
            .filterNot { guessDrawings && isDrawing(it) }
            .sortedBy { it.bounds.centerY() }

        // 3. Renglones de manchas de texto: se alinean por el centro y con
        //    altura parecida, así la letra grande y la pequeña van a
        //    renglones distintos y cada uno se convierte a su tamaño.
        class Row(val members: MutableList<Blob>) {
            val center: Float get() = members.map { it.bounds.centerY() }.average().toFloat()
            val height: Float get() = members.map { it.h }.average().toFloat()
        }
        val rows = mutableListOf<Row>()
        textBlobs.forEach { blob ->
            val row = rows.lastOrNull()
            val fits = row != null &&
                abs(blob.bounds.centerY() - row.center) <= row.height * ROW_JOIN_FRACTION &&
                minOf(blob.h, row.height) / maxOf(blob.h, row.height) >= ROW_HEIGHT_SIMILARITY
            if (fits) row!!.members += blob else rows += Row(mutableListOf(blob))
        }

        // 4. Dentro del renglón, un hueco de varias letras parte el texto
        //    (columnas). Una letra suelta sin más compañía en su renglón se
        //    deja: puede ser una marca o un trazo de un dibujo, y perder una
        //    letra es más barato que perder un dibujo.
        val segments = mutableListOf<Pair<List<Int>, RectF>>()
        // Which row each segment came from: the reading order is row by row
        // and, inside a row, left to right. Sorting by the exact top of each
        // segment instead put "aplicación" (tall ascender) before "Esta es
        // una" on the same line, and the sentence came out shuffled.
        val rowOf = HashMap<Pair<List<Int>, RectF>, Int>()
        rows.forEachIndexed { rowIndex, row ->
            val sorted = row.members.sortedBy { it.bounds.left }
            var segment = mutableListOf<Blob>()
            var right = Float.NEGATIVE_INFINITY
            fun flush() {
                if (segment.isNotEmpty()) {
                    val bounds = RectF(segment.first().bounds)
                    segment.forEach { bounds.union(it.bounds) }
                    val strokesInSegment = segment.sumOf { it.n }
                    val wordy = !guessDrawings || segment.size >= 2 || strokesInSegment >= 2 ||
                        bounds.width() / bounds.height().coerceAtLeast(1f) >= 1.2f
                    if (wordy) {
                        val entry = segment.flatMap { it.strokeIndices } to bounds
                        segments += entry
                        rowOf[entry] = rowIndex
                    }
                }
                segment = mutableListOf()
                right = Float.NEGATIVE_INFINITY
            }
            sorted.forEach { blob ->
                if (segment.isNotEmpty() && blob.bounds.left - right > row.height * COLUMN_GAP_FRACTION) flush()
                segment += blob
                right = max(right, blob.bounds.right)
            }
            flush()
        }

        // 5. Cuerpo de letra: los renglones de alto parecido comparten uno
        //    (la mediana del grupo). Un renglón con una "g" o una "l" es más
        //    alto que el de al lado sin serlo su letra; con el cuerpo por
        //    renglón cada frase salía de un tamaño.
        val bySize = segments.sortedBy { it.second.height() }
        val sizeOf = HashMap<Pair<List<Int>, RectF>, Float>()
        var group = mutableListOf<Pair<List<Int>, RectF>>()
        fun closeGroup() {
            if (group.isEmpty()) return
            val hs = group.map { it.second.height() }.sorted()
            val size = (hs[hs.size / 2] * TEXT_HEIGHT_FACTOR).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
            group.forEach { sizeOf[it] = size }
            group = mutableListOf()
        }
        bySize.forEach { segment ->
            val smallest = group.firstOrNull()?.second?.height()
            if (smallest != null && segment.second.height() > smallest * SIZE_GROUP_RATIO) closeGroup()
            group += segment
        }
        closeGroup()

        return segments
            .sortedWith(compareBy({ rowOf[it] ?: 0 }, { it.second.left }))
            .map { (indices, bounds) -> TextLine(indices, bounds, sizeOf[indices to bounds] ?: bounds.height() * TEXT_HEIGHT_FACTOR) }
    }

    /** Solo los trazos de un renglón, como documento aparte (para el OCR). */
    fun lineDocument(line: TextLine): InkDocument =
        InkDocument(
            canvasWidth = width.toFloat(),
            canvasHeight = pageHeight,
            strokes = line.strokeIndices.mapNotNull { strokes.getOrNull(it) },
        )

    /**
     * Sustituye varios renglones por su texto, de una vez y con una sola
     * entrada en el historial: deshacer devuelve toda la escritura.
     */
    fun replaceWithTexts(conversions: List<Pair<TextLine, String>>) {
        val usable = conversions.filter { (line, text) -> text.isNotBlank() && line.strokeIndices.isNotEmpty() }
        if (usable.isEmpty()) return
        recordChange()
        val removed = usable.flatMap { it.first.strokeIndices }.toSet()
        val added = usable.map { (line, text) ->
            val first = strokes.getOrNull(line.strokeIndices.first())
            val lineCount = text.trim().split('\n').size
            InkText(
                text = text.trim(),
                x = line.bounds.left,
                y = line.bounds.top,
                sizePx = (line.sizePx / lineCount).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX),
                colorArgb = first?.colorArgb ?: Color.BLACK,
            )
        }
        val survivors = strokes.filterIndexed { index, _ -> index !in removed }
        strokes.clear()
        strokes.addAll(survivors)
        texts.addAll(added)
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    private fun strokeBounds(stroke: Stroke): RectF? {
        if (stroke.points.isEmpty()) return null
        return RectF(
            stroke.points.minOf { it.x },
            stroke.points.minOf { it.y },
            stroke.points.maxOf { it.x },
            stroke.points.maxOf { it.y },
        )
    }

    val canUndo: Boolean get() = history.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        future += snapshot()
        restore(previous)
        lastStrokeFromTouch = false
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        history += snapshot()
        restore(next)
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    fun clear() {
        if (isEmpty()) return
        recordChange()
        strokes.clear()
        texts.clear()
        sourceDocument = null
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    // --- Selección: acciones ------------------------------------------------

    private fun selectedStrokes(): List<Stroke> =
        selection.sorted().mapNotNull { strokes.getOrNull(it) }

    private fun selectedTexts(): List<InkText> =
        textSelection.sorted().mapNotNull { texts.getOrNull(it) }

    fun copySelection() {
        if (!hasSelection) return
        InkClipboard.put(selectedStrokes(), selectedTexts())
    }

    fun cutSelection() {
        if (!hasSelection) return
        InkClipboard.put(selectedStrokes(), selectedTexts())
        deleteSelection()
    }

    /**
     * Pega desplazado un poco hacia abajo y a la derecha, y deja lo pegado
     * seleccionado: así se puede arrastrar a su sitio sin volver a rodearlo.
     */
    fun paste() {
        if (InkClipboard.isEmpty) return
        recordChange()
        val offset = PASTE_OFFSET_PX
        val firstStroke = strokes.size
        val firstText = texts.size
        InkClipboard.strokes.forEach { stroke ->
            strokes += stroke.copy(
                points = stroke.points.map { it.copy(x = it.x + offset, y = it.y + offset) }
            )
        }
        InkClipboard.texts.forEach { text ->
            texts += text.copy(x = text.x + offset, y = text.y + offset)
        }
        editedSinceLoad = true
        selection.clear()
        selection += firstStroke until strokes.size
        textSelection.clear()
        textSelection += firstText until texts.size
        redrawBitmap()
        onSelectionChanged?.invoke(selectionCount)
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    /** Borra solo lo que hay dentro del lazo. */
    fun deleteSelection() {
        if (!hasSelection) return
        recordChange()
        val survivors = strokes.filterIndexed { index, _ -> index !in selection }
        strokes.clear()
        strokes.addAll(survivors)
        val survivingTexts = texts.filterIndexed { index, _ -> index !in textSelection }
        texts.clear()
        texts.addAll(survivingTexts)
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    /**
     * «A texto»: los trazos rodeados se sustituyen por el texto reconocido, en
     * el mismo sitio y con un cuerpo de letra a la altura de lo escrito. El
     * texto se queda seleccionado para poder arrastrarlo con el lápiz a donde
     * tenga que ir.
     */
    fun convertSelectionToText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || selection.isEmpty()) return
        val chosen = selectedStrokes()
        val points = chosen.flatMap { it.points }
        if (points.isEmpty()) return
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val height = points.maxOf { it.y } - top
        val lineCount = clean.split('\n').size
        val size = (height / lineCount * TEXT_HEIGHT_FACTOR).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)

        recordChange()
        val survivors = strokes.filterIndexed { index, _ -> index !in selection }
        strokes.clear()
        strokes.addAll(survivors)
        texts += InkText(
            text = clean,
            x = left,
            y = top,
            sizePx = size,
            colorArgb = chosen.first().colorArgb,
        )
        editedSinceLoad = true
        selection.clear()
        textSelection.clear()
        textSelection += texts.lastIndex
        lassoPath.clear()
        redrawBitmap()
        onSelectionChanged?.invoke(selectionCount)
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    fun clearSelection() {
        if (!hasSelection && lassoPath.isEmpty()) return
        selection.clear()
        textSelection.clear()
        lassoPath.clear()
        onSelectionChanged?.invoke(0)
        invalidate()
    }

    /**
     * Pausa la captura del lápiz. Imprescindible al abrir un diálogo o un campo
     * de texto encima: con el trazo activo, el SDK de Onyx sigue pintando sobre
     * la interfaz y se come los toques.
     */
    fun setPenEnabled(enabled: Boolean) {
        // Idempotente: se llama desde el `update` de Compose en cada
        // recomposición, y reprogramar la reanudación cada vez la aplazaría
        // sin fin.
        if (penEnabled == enabled) return
        penEnabled = enabled
        if (enabled) resumeRawDelayed() else pauseRaw()
    }

    fun release() {
        closeHelper()
        background?.recycle()
        background = null
        bitmap?.recycle()
        bitmap = null
        bitmapCanvas = null
    }

    // --- Ciclo de vida ------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureBitmap()
        // El documento suele llegar antes de que la vista tenga tamaño (el
        // efecto que lo carga corre antes del primer layout), y entonces se
        // guardó sin escalar. Se escala aquí, en la primera medida, siempre
        // que no se haya escrito nada encima. En cambios de tamaño posteriores
        // (aparece la barra del lazo, por ejemplo) los trazos se quedan donde
        // están: reescalarlos los movería bajo el lápiz.
        if ((oldw == 0 || oldh == 0 || oldw != w) && !editedSinceLoad) applySourceDocument()
        clampPan()
        redrawBitmap()
        // Al siguiente fotograma: aquí ya se sabe el tamaño, pero la vista puede
        // no estar colocada aún en la ventana, y el SDK quiere el rectángulo en
        // coordenadas de pantalla.
        post { setupTouchHelper() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Todo va en coordenadas de la hoja; la vista enseña desde `panY`
        // (y `panX`, ampliada, en las notas de solo lectura).
        canvas.save()
        canvas.scale(zoom, zoom)
        canvas.translate(-panX, -panY)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // El subrayador en curso (camino táctil) se pinta aquí, entero y de
        // una vez, encima del bitmap: pintarlo por tramos en el bitmap
        // duplicaba la tinta donde un tramo pisaba al anterior, y salían
        // puntos más oscuros a lo largo del trazo.
        if (tool == PenTool.MARKER && touchStrokeActive && !erasingByButton && livePoints.size > 1) {
            applyPaint(PenTool.MARKER, strokeWidth, colorArgb)
            canvas.drawPath(strokePath(livePoints), paint)
        }

        if (lassoPath.size > 1) {
            val path = Path()
            path.moveTo(lassoPath[0].x, lassoPath[0].y)
            lassoPath.drop(1).forEach { path.lineTo(it.x, it.y) }
            canvas.drawPath(path, lassoPaint)
        }
        if (hasSelection) {
            selectionBounds()?.let { canvas.drawRect(it, lassoPaint) }
        }
        // El final de la hoja, para que se vea que se acaba.
        canvas.drawLine(0f, pageHeight - 1f, width.toFloat(), pageHeight - 1f, lassoPaint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(positionWatcher)
        viewTreeObserver.addOnGlobalLayoutListener(positionWatcher)
    }

    override fun onDetachedFromWindow() {
        runCatching {
            viewTreeObserver.removeOnScrollChangedListener(positionWatcher)
            viewTreeObserver.removeOnGlobalLayoutListener(positionWatcher)
        }
        EinkRefresh.enableFingerTouch(context)
        release()
        super.onDetachedFromWindow()
    }

    private val positionWatcher = object :
        ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {
        override fun onScrollChanged() = syncLimitRect()
        override fun onGlobalLayout() = syncLimitRect()
    }

    private var appliedRect: Rect? = null

    private fun syncLimitRect() {
        if (touchHelper == null) return
        val rect = screenRect()
        if (rect.isEmpty || rect == appliedRect) return
        setupTouchHelper()
    }

    /**
     * El demo activa el trazo en `onResume` y lo apaga en `onPause`. Sin foco de
     * ventana —un diálogo encima, otra app— el SDK se comería los toques.
     *
     * Al recuperar el foco se reactiva con retardo (`POPUP_RESUME_PEN_TIME_MS`
     * en el demo): si no, la región se congela antes de que el panel borre el
     * diálogo que acaba de cerrarse, y se queda su fantasma encima del
     * cuaderno.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            resumeRawDelayed(POPUP_RESUME_DELAY_MS)
        } else {
            EinkRefresh.enableFingerTouch(context)
            pauseRaw()
        }
    }

    /**
     * Con el trazo rápido activo el SDK congela la pantalla en la región (así
     * mantiene su tinta). Para que se vea un repintado nuestro —deshacer,
     * borrar, cambiar de página— hay que apagarlo, repintar y encenderlo
     * pasado un momento, que es lo que hace el demo tras volcar su bitmap.
     */
    private fun repaintUnderSdk() {
        if (touchHelper == null || (!rawActive && !resumeScheduled)) {
            invalidate()
            return
        }
        // Si ya había una reactivación en marcha, se vuelve a contar desde
        // este repintado: es el último el que tiene que llegar al panel.
        pauseRaw()
        EinkRefresh.enablePost(this)
        invalidate()
        resumeRawDelayed()
    }

    // --- Camino Onyx --------------------------------------------------------

    /**
     * Monta el trazo rápido del SDK.
     *
     * Toda la configuración va **antes** de `openRawDrawing()`: el SDK congela
     * la región y el estilo al abrir. Se escribe cada paso al log con el mismo
     * TAG para poder seguirlo con `adb logcat -s PenCanvasView`.
     */
    private fun setupTouchHelper() {
        if (width <= 0 || height <= 0) return
        if (penMode == PenMode.TOUCH) return

        // En coordenadas de la vista, no de pantalla: es lo que hace el demo
        // oficial (`getLocalVisibleRect`). Con coordenadas de pantalla la región
        // caía fuera de sitio y el SDK nunca tenía nada que entregar.
        val limit = Rect()
        getLocalVisibleRect(limit)
        if (limit.isEmpty) return

        val existing = touchHelper
        if (existing != null) {
            // El demo vuelve a abrir el trazo en cada cambio de layout, sin
            // cerrarlo antes. Es la forma de que la región siga a la vista.
            runCatching {
                existing.setStrokeWidth(sdkStrokeWidth())
                    .setLimitRect(limit, ArrayList())
                    .openRawDrawing()
                existing.setStrokeStyle(currentStrokeStyle())
            }
            // Reopening leaves the SDK capturing the pen. That is wrong while
            // the pen is paused (a creation window on top, the keyboard just
            // resized the screen): the stylus then never reached the text
            // field and only a finger could place the caret. So the capture
            // is switched off explicitly and put back only if it is wanted,
            // now or by the delayed resume already on its way.
            runCatching { existing.setRawDrawingEnabled(false) }
            rawActive = false
            if (!resumeScheduled) syncRawEnabled()
            appliedRect = screenRect()
            diagnostics = diagnostics.copy(limitRect = limit.shortString())
            report()
            return
        }

        var failure: String? = null
        Log.i(TAG, "SDK: montando TouchHelper en $limit (${Build.MANUFACTURER} ${Build.MODEL}, ${Build.DISPLAY})")
        touchHelper = try {
            TouchHelper.create(this, rawInputCallback).apply {
                Log.i(TAG, "SDK: create OK")
                callIfPresent("debugLog", true)
                // El mismo orden que el demo oficial.
                val style = currentStrokeStyle()
                SdkInk.applyStrokeParameters(style, pressureSensitivity)
                setStrokeWidth(sdkStrokeWidth())
                    .setLimitRect(limit, ArrayList())
                    .openRawDrawing()
                setStrokeStyle(style)
                callIfPresent("setStrokeColor", colorArgb)
                // Al soltar el lápiz el SDK avisa (`onPenUpRefresh`) y la zona
                // se repinta desde la hoja, como hace Boox Notes.
                callIfPresent("setPenUpRefreshEnabled", true)
                callIfPresent("setPenUpRefreshTimeMs", PEN_UP_REFRESH_MS)
                Log.i(TAG, "SDK: openRawDrawing OK · estilo ${SdkInk.styleName(style)}")
                // El demo lo llama sobre la vista antes de volcar a pantalla
                // con el trazo rápido activo: permite que un repintado
                // nuestro llegue al panel aunque la región esté congelada.
                EinkRefresh.enablePost(this@PenCanvasView)
                val wanted = rawWanted
                setRawDrawingEnabled(wanted)
                rawActive = wanted
                setRawDrawingRenderEnabled(true)
                Log.i(TAG, "SDK: setRawDrawingEnabled($wanted) OK")
                applyOptionalSdkCalls(this)
            }
        } catch (t: Throwable) {
            failure = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.w(TAG, "SDK: fallo al montar; se escribe por el camino táctil", t)
            null
        }

        appliedRect = screenRect()
        diagnostics = diagnostics.copy(
            helperCreated = touchHelper != null,
            limitRect = limit.shortString(),
            error = failure,
        )
        Log.i(TAG, "Trazo: ${diagnostics.engine}")
        report()
        onEngineResolved?.invoke(touchHelper != null)
    }

    /**
     * Ajustes de la API que trae este SDK (vista en el diagnóstico del Note
     * Air 5C) y comprobación de que el trazo quedó de verdad activo.
     *
     * Con `setRawInputReaderEnable(true)` el SDK empezó a quedarse con los
     * toques del lápiz (dejaron de llegar a Android) sin entregar puntos. Así
     * que después de configurar se le pregunta con `isRawDrawingInputEnabled()`
     * y, si dice que no, se fuerza con `forceSetRawDrawingEnabled(true)`.
     * `setPostInputEvent(true)` pide que además reenvíe los eventos como
     * toques normales: es la red que mantiene vivo el camino táctil si el SDK
     * captura y calla.
     */
    private fun applyOptionalSdkCalls(helper: TouchHelper) {
        val methods = helper.javaClass.methods
            .filter { it.declaringClass != Any::class.java }
            .map { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
            .distinct()
            .sorted()
        val tried = mutableListOf<String>()

        fun attempt(name: String, vararg args: Any) {
            val ok = helper.callIfPresent(name, *args)
            if (ok != null) tried += "$name=${if (ok) "ok" else "error"}"
        }

        attempt("enableSideBtnErase", true)
        attempt("enableFingerTouch", false)
        attempt("setPenUpRefreshEnabled", false)

        var input = helper.queryFlag("isRawDrawingInputEnabled")
        if (input == false && rawWanted) {
            attempt("forceSetRawDrawingEnabled", true)
            input = helper.queryFlag("isRawDrawingInputEnabled")
            if (input == true) rawActive = true
        }
        val created = helper.queryFlag("isRawDrawingCreated")
        val render = helper.queryFlag("isRawDrawingRenderEnabled")
        tried += "created=$created · input=$input · render=$render"

        diagnostics = diagnostics.copy(
            sdkMethods = methods.joinToString(" · "),
            sdkExtras = tried.joinToString(" · "),
        )
        Log.i(TAG, "SDK: extras = $tried")
    }

    /** Invoca un método por nombre si existe. null = no existe; true/false = fue bien/mal. */
    private fun TouchHelper.callIfPresent(name: String, vararg args: Any): Boolean? {
        val method = javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.indices.all { i -> compatible(m.parameterTypes[i], args[i]) }
        } ?: return null
        val ok = runCatching { method.invoke(this, *args) }.isSuccess
        Log.i(TAG, "SDK: $name(${args.joinToString()}) → ${if (ok) "ok" else "error"}")
        return ok
    }

    private fun TouchHelper.queryFlag(name: String): Boolean? = runCatching {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.invoke(this) as? Boolean
    }.getOrNull()

    private fun compatible(type: Class<*>, arg: Any): Boolean = when (arg) {
        is Boolean -> type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java
        is Int -> type == Integer.TYPE || type == Integer::class.java
        is Float -> type == java.lang.Float.TYPE || type == java.lang.Float::class.java
        else -> type.isInstance(arg)
    }

    /** Estilo del SDK para la punta y sus ajustes (ver [SdkInk.styleFor]). */
    private fun currentStrokeStyle(): Int = SdkInk.styleFor(tool, pencilTexture, pressureSensitivity)

    private fun closeHelper() {
        removeCallbacks(resumeRunnable)
        resumeScheduled = false
        resumeAfterTouch = false
        touchStrokeActive = false
        runCatching {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        }
        touchHelper = null
        rawActive = false
        appliedRect = null
        diagnostics = diagnostics.copy(helperCreated = false, sdkProven = false)
        onEngineResolved?.invoke(false)
    }

    private fun screenRect(): Rect {
        val visible = Rect()
        if (getGlobalVisibleRect(visible) && !visible.isEmpty) return visible
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return Rect(origin[0], origin[1], origin[0] + width, origin[1] + height)
    }

    private fun Rect.shortString(): String = "$left,$top → $right,$bottom"

    private val rawInputCallback = object : RawInputCallback() {

        // El SDK llama desde su propio hilo de lectura. Todo lo que toque la
        // vista o el bitmap se manda al principal: `invalidate()` desde otro
        // hilo revienta la app en cuanto el SDK empiece a entregar.

        override fun onBeginRawDrawing(shortcutDrawing: Boolean, point: TouchPoint) {
            Log.d(TAG, "SDK: onBeginRawDrawing")
            post {
                proveSdk()
                strokeStartedAt = System.currentTimeMillis()
                livePoints.clear()
                EinkRefresh.disableFingerTouch(context)
            }
        }

        override fun onEndRawDrawing(shortcutDrawing: Boolean, point: TouchPoint) {
            post { EinkRefresh.enableFingerTouch(context) }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = Unit

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
            val points = touchPointList.points?.toList() ?: return
            Log.d(TAG, "SDK: lista de ${points.size} puntos")
            post {
                proveSdk()
                livePoints.clear()
                // Cada punto con su propio tiempo: la lista llega de golpe al
                // soltar, y con la hora de llegada todos saldrían simultáneos
                // (y el boli, que mira la velocidad, pintaría todo «rápido»).
                val base = points.firstOrNull()?.timestamp ?: 0L
                points.mapIndexedTo(livePoints) { index, point -> point.toInk(base, index) }
                commitLiveStroke(fromTouch = false)
            }
        }

        override fun onBeginRawErasing(shortcutErasing: Boolean, point: TouchPoint) {
            post { EinkRefresh.disableFingerTouch(context) }
        }

        override fun onEndRawErasing(shortcutErasing: Boolean, point: TouchPoint) {
            post { EinkRefresh.enableFingerTouch(context) }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = Unit

        /**
         * El SDK avisa un momento después de soltar el lápiz: la zona del
         * trazo se repinta desde la hoja (el mismo pincel del SDK) con el
         * modo de repintado de escritura, como `PartialRefreshRequest` del
         * demo. Así el trazo queda limpio y ya con su cara definitiva.
         */
        override fun onPenUpRefresh(refreshRect: RectF?) {
            val rect = refreshRect ?: return
            post {
                if (!rawActive) return@post
                EinkRefresh.handwritingRepaintMode(this@PenCanvasView)
                invalidatePage(
                    pageX(rect.left).toInt() - PEN_UP_PAD,
                    pageY(rect.top).toInt() - PEN_UP_PAD,
                    pageX(rect.right).toInt() + PEN_UP_PAD,
                    pageY(rect.bottom).toInt() + PEN_UP_PAD,
                )
                postDelayed({ EinkRefresh.readableMode(this@PenCanvasView) }, PEN_UP_MODE_RESET_MS)
            }
        }

        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {
            val points = touchPointList.points?.toList() ?: return
            post {
                proveSdk()
                eraseNear(points.map { it.toInk() })
            }
        }
    }

    /**
     * Primera entrega real del SDK. Hasta ese momento el camino táctil pintaba
     * por si acaso, y el primer trazo puede estar por duplicado: se quita la
     * copia táctil. Solo la primera vez: con el SDK ya probado, un trazo
     * táctil es uno hecho durante una pausa del SDK, y es tan válido como
     * cualquier otro.
     */
    private fun proveSdk() {
        if (diagnostics.sdkProven) return
        if (lastStrokeFromTouch && strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            // Y su entrada en el historial, que se grabó al guardarlo.
            history.removeLastOrNull()
            lastStrokeFromTouch = false
            redrawBitmap()
        }
        diagnostics = diagnostics.copy(sdkProven = true)
        report()
        onEngineResolved?.invoke(true)
    }

    /**
     * Los puntos del SDK vienen en coordenadas de la vista: el demo los pinta
     * tal cual sobre un bitmap del tamaño de la vista.
     */
    private fun TouchPoint.toInk(): InkPoint {
        val (tx, ty) = SdkInk.readTilt(this)
        return InkPoint(
            x = pageX(x),
            y = pageY(y),
            t = (System.currentTimeMillis() - strokeStartedAt).coerceAtLeast(0),
            pressure = normalizePressure(pressure),
            tilt = tiltFromDegrees(tx, ty),
            tiltX = tx,
            tiltY = ty,
        )
    }

    /**
     * Punto de una lista del SDK, con el tiempo relativo al primero de la
     * lista. Si el SDK no marca el tiempo, se supone su cadencia de muestreo.
     */
    private fun TouchPoint.toInk(baseTimestamp: Long, index: Int): InkPoint {
        val stamp = timestamp
        val t = if (stamp > 0L && baseTimestamp > 0L && stamp >= baseTimestamp) {
            stamp - baseTimestamp
        } else {
            index * SDK_SAMPLE_MS
        }
        val (tx, ty) = SdkInk.readTilt(this)
        return InkPoint(
            x = pageX(x),
            y = pageY(y),
            t = t,
            pressure = normalizePressure(pressure),
            tilt = tiltFromDegrees(tx, ty),
            tiltX = tx,
            tiltY = ty,
        )
    }

    /**
     * El SDK entrega la presión en bruto (hasta `getMaxTouchPressure()`,
     * miles); Android la da entre 0 y 1. Se guarda siempre entre 0 y 1 para
     * que el repintado no dependa de por dónde entró el trazo.
     */
    private fun normalizePressure(raw: Float): Float {
        val max = EinkRefresh.maxTouchPressure
        val scale = when {
            max > 1f -> max
            raw > 1f -> DEFAULT_MAX_PRESSURE
            else -> 1f
        }
        return (raw / scale).coerceIn(0f, 1f)
    }

    // --- Camino táctil ------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        // Solo escribe el lápiz. El dedo no pinta, no borra y no rodea: en un
        // lienzo que ocupa media pantalla, un roce con la mano era un trazo.
        // El dedo solo desplaza la hoja.
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) return handleFingerPan(event)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }
        if (sdkCapturing || !penEnabled) {
            // Un trazo táctil que empezó antes de que se apagara el lápiz (se
            // abrió un diálogo a mitad) tiene que cerrarse igualmente, o el
            // SDK se quedaría esperándolo para siempre.
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchStrokeActive = false
                livePoints.clear()
                resumeIfDeferred()
            }
            return super.onTouchEvent(event)
        }

        diagnostics = diagnostics.copy(
            motionEvents = diagnostics.motionEvents + 1,
            lastToolType = when (toolType) {
                MotionEvent.TOOL_TYPE_STYLUS -> "lápiz"
                MotionEvent.TOOL_TYPE_ERASER -> "goma del lápiz"
                MotionEvent.TOOL_TYPE_FINGER -> "dedo"
                else -> "otro ($toolType)"
            },
        )

        // El botón del lápiz, o darle la vuelta, es la goma: igual que en el
        // resto de la tablet. Se decide al bajar y se mantiene todo el gesto,
        // porque el botón puede soltarse a mitad de trazo.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            erasingByButton = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        }
        val activeTool = if (erasingByButton) PenTool.ERASER else tool

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStartedAt = System.currentTimeMillis()
                livePoints.clear()
                touchStrokeActive = true
                val down = event.toInk()
                parent?.requestDisallowInterceptTouchEvent(true)
                EinkRefresh.disableFingerTouch(context)

                if (activeTool == PenTool.LASSO) {
                    val box = selectionBounds()
                    if (box != null && box.contains(down.x, down.y)) {
                        // Coger la selección para arrastrarla.
                        dragStart = down
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        return true
                    }
                    lassoPath.clear()
                    selection.clear()
                    textSelection.clear()
                    onSelectionChanged?.invoke(0)
                    lassoPath += down
                } else {
                    livePoints += down
                    // Refresco rápido mientras se escribe: el panel deja de
                    // esperar a tener un fotograma "bonito".
                    if (activeTool != PenTool.ERASER) EinkRefresh.fastMode(this)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val drag = dragStart
                if (drag != null) {
                    val current = event.toInk()
                    dragOffsetX = current.x - drag.x
                    dragOffsetY = current.y - drag.y
                    invalidate()
                    return true
                }
                for (i in 0 until event.historySize) {
                    livePoints += InkPoint(
                        x = pageX(event.getHistoricalX(i)),
                        y = pageY(event.getHistoricalY(i)),
                        t = event.getHistoricalEventTime(i) - strokeStartedAt,
                        pressure = event.getHistoricalPressure(i).coerceIn(0f, 1f),
                        tilt = tiltFromRadians(event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i)),
                    )
                }
                livePoints += event.toInk()
                when (activeTool) {
                    PenTool.ERASER -> eraseNear(listOf(livePoints.last()))
                    PenTool.LASSO -> {
                        lassoPath += livePoints.last()
                        invalidate()
                    }
                    else -> drawLiveTail()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                EinkRefresh.enableFingerTouch(context)
                touchStrokeActive = false
                if (dragStart != null) {
                    finishDrag()
                    resumeIfDeferred()
                    return true
                }
                livePoints += event.toInk()
                when (activeTool) {
                    PenTool.ERASER -> livePoints.clear()
                    PenTool.LASSO -> {
                        lassoPath += livePoints.last()
                        livePoints.clear()
                        applyLasso()
                    }
                    else -> {
                        commitLiveStroke(fromTouch = true)
                        EinkRefresh.readableMode(this)
                    }
                }
                erasingByButton = false
                report()
                resumeIfDeferred()
            }

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun MotionEvent.toInk(): InkPoint = InkPoint(
        x = pageX(x),
        y = pageY(y),
        t = eventTime - strokeStartedAt,
        pressure = pressure.coerceIn(0f, 1f),
        tilt = tiltFromRadians(getAxisValue(MotionEvent.AXIS_TILT)),
    )

    /** El SDK quería volver mientras el táctil tenía un trazo a medias: ahora. */
    private fun resumeIfDeferred() {
        if (!resumeAfterTouch) return
        resumeAfterTouch = false
        resumeRawDelayed()
    }

    /**
     * Pinta solo el último segmento e invalida solo su rectángulo. En capa de
     * software eso es lo que refresca el panel: un trocito, no la vista entera.
     */
    private fun drawLiveTail() {
        if (livePoints.size < 2) return
        val canvas = bitmapCanvas ?: return
        applyPaint(tool, strokeWidth, colorArgb)
        val a = livePoints[livePoints.size - 2]
        val b = livePoints.last()
        when (tool) {
            PenTool.PENCIL -> drawPencilSegment(canvas, a, b, strokeWidth, pencilTexture, pressureSensitivity, colorArgb)

            PenTool.BALLPOINT -> {
                drawBallpointSegment(canvas, a, b, strokeWidth, pressureSensitivity, colorArgb)
                if (livePoints.size == 2) drawBallpointBlob(canvas, a, b, strokeWidth, colorArgb)
            }

            // El subrayador no va al bitmap hasta soltar (ver onDraw); aquí
            // solo se invalida el tramo nuevo para que se vea.
            PenTool.MARKER -> Unit

            else -> canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }

        val pad = (strokeWidth * 2.5f + 4f).toInt()
        invalidatePage(
            min(a.x, b.x).toInt() - pad,
            min(a.y, b.y).toInt() - pad,
            max(a.x, b.x).toInt() + pad,
            max(a.y, b.y).toInt() + pad,
        )
    }

    // --- Selección inteligente ----------------------------------------------

    private fun applyLasso() {
        selection.clear()
        textSelection.clear()
        if (lassoPath.size > 2) {
            strokes.forEachIndexed { index, stroke ->
                val inside = stroke.points.count { contains(lassoPath, it) }
                if (stroke.points.isNotEmpty() && inside * 2 >= stroke.points.size) {
                    selection += index
                }
            }
            // Un texto entra si el lazo atrapa su centro.
            texts.forEachIndexed { index, text ->
                val box = textBounds(text)
                if (contains(lassoPath, InkPoint(box.centerX(), box.centerY()))) {
                    textSelection += index
                }
            }
        }
        lassoPath.clear()
        onSelectionChanged?.invoke(selectionCount)
        invalidate()
    }

    /** Aplica el desplazamiento del arrastre a los trazos seleccionados. */
    private fun finishDrag() {
        dragStart = null
        if (!hasSelection || (dragOffsetX == 0f && dragOffsetY == 0f)) {
            invalidate()
            return
        }
        recordChange()
        val dx = dragOffsetX
        val dy = dragOffsetY
        selection.forEach { index ->
            val stroke = strokes.getOrNull(index) ?: return@forEach
            strokes[index] = stroke.copy(
                points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) }
            )
        }
        textSelection.forEach { index ->
            val text = texts.getOrNull(index) ?: return@forEach
            texts[index] = text.copy(x = text.x + dx, y = text.y + dy)
        }
        dragOffsetX = 0f
        dragOffsetY = 0f
        editedSinceLoad = true
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    private fun contains(polygon: List<InkPoint>, point: InkPoint): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun selectionBounds(): RectF? {
        val points = selectedStrokes().flatMap { it.points }
        val boxes = selectedTexts().map(::textBounds)
        if (points.isEmpty() && boxes.isEmpty()) return null
        val box = RectF()
        var first = true
        fun include(l: Float, t: Float, r: Float, b: Float) {
            if (first) {
                box.set(l, t, r, b)
                first = false
            } else {
                box.union(l, t, r, b)
            }
        }
        points.forEach { include(it.x, it.y, it.x, it.y) }
        boxes.forEach { include(it.left, it.top, it.right, it.bottom) }
        box.inset(-8f, -8f)
        box.offset(dragOffsetX, dragOffsetY)
        return box
    }

    /** Rectángulo que ocupa un texto en el lienzo, medido con su cuerpo de letra. */
    private fun textBounds(text: InkText): RectF {
        textPaint.textSize = text.sizePx
        val lineHeight = text.sizePx * TEXT_LINE_FACTOR
        val lines = text.lines
        val widest = lines.maxOf { textPaint.measureText(it) }
        return RectF(text.x, text.y, text.x + widest, text.y + lineHeight * lines.size)
    }

    private fun drawText(canvas: Canvas, text: InkText) {
        textPaint.textSize = text.sizePx
        textPaint.color = text.colorArgb
        val lineHeight = text.sizePx * TEXT_LINE_FACTOR
        // La línea base va un cuerpo por debajo del borde superior de cada
        // renglón (menos el pequeño hueco de descendentes): así el texto ocupa
        // de verdad el rectángulo que se le calcula.
        text.lines.forEachIndexed { index, line ->
            canvas.drawText(line, text.x, text.y + lineHeight * index + text.sizePx * 0.9f, textPaint)
        }
    }

    // --- Común --------------------------------------------------------------

    private fun commitLiveStroke(fromTouch: Boolean) {
        if (livePoints.isEmpty()) return
        val stroke = Stroke(
            points = livePoints.toList(),
            width = strokeWidth,
            colorArgb = colorArgb,
            tool = tool.name,
            mm = strokeWidthMm,
            texture = pencilTexture,
            sensitivity = pressureSensitivity,
            sdkStyle = if (fromTouch) -1 else currentStrokeStyle(),
        )
        recordChange()
        strokes += stroke
        livePoints.clear()
        lastStrokeFromTouch = fromTouch
        editedSinceLoad = true

        drawStroke(stroke)
        if (fromTouch) {
            invalidate()
        } else {
            // Lo pintó el SDK y ya está en el panel; repintar ahora solo haría
            // parpadear. Se anota para volcarlo cuando el SDK se pause. Si la
            // lista llega ya con el SDK en pausa, se vuelca al momento.
            rememberSdkInk(stroke)
            if (!rawActive) flushSdkInk()
        }
        onDocumentChanged?.invoke()
    }

    private fun eraseNear(points: List<InkPoint>, radius: Float = ERASER_RADIUS_PX) {
        if (strokes.isEmpty() || points.isEmpty()) return
        val reach = radius + strokeWidth
        val survivors = strokes.filterNot { stroke ->
            stroke.points.any { sp ->
                points.any { ep -> hypot(sp.x - ep.x, sp.y - ep.y) <= reach }
            }
        }
        if (survivors.size == strokes.size) return
        recordChange()
        strokes.clear()
        strokes.addAll(survivors)
        lastStrokeFromTouch = false
        editedSinceLoad = true
        clearSelection()
        redrawBitmap()
        repaintUnderSdk()
        onDocumentChanged?.invoke()
    }

    /** El bitmap es la hoja entera, no la ventana que se ve de ella. */
    private fun ensureBitmap() {
        if (width <= 0 || height <= 0) return
        val pageH = pageHeight.toInt().coerceAtLeast(1)
        val current = bitmap
        if (current != null && current.width == width && current.height == pageH) return
        current?.recycle()
        bitmap = Bitmap.createBitmap(width, pageH, Bitmap.Config.ARGB_8888).also {
            bitmapCanvas = Canvas(it)
        }
    }

    private fun redrawBitmap() {
        ensureBitmap()
        val canvas = bitmapCanvas ?: return
        canvas.drawColor(Color.WHITE, android.graphics.PorterDuff.Mode.SRC)
        background?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), pageHeight), null) }
        strokes.forEach(::drawStroke)
        texts.forEach { drawText(canvas, it) }
    }

    /**
     * De la hoja a la pantalla: la vista enseña desde `panY`, y está donde
     * esté en la ventana. El carboncillo del SDK alinea su grano con los
     * píxeles de pantalla, y con esta matriz el repintado cae sobre lo que
     * pintó en vivo.
     */
    private fun screenMatrix(): Matrix {
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        // Solo traslación, como `RendererUtils.getPointMatrix` del demo: el
        // pincel la usa para alinear el grano, no para mover la geometría.
        return Matrix().apply { postTranslate(origin[0] - panX * zoom, origin[1] - panY * zoom) }
    }

    /**
     * Grosor en píxeles con el que se pinta un trazo. Los de antes de los
     * milímetros guardaban el grosor del cono, y el subrayador y el boli
     * lo transformaban al pintar; se sigue haciendo con ellos.
     */
    private fun displayWidth(stroke: Stroke): Float {
        val strokeTool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
        if (!stroke.legacy) return stroke.width
        return when (strokeTool) {
            PenTool.MARKER -> markerWidth(stroke.width)
            PenTool.BALLPOINT -> stroke.width * BALLPOINT_WIDTH_FACTOR
            else -> stroke.width
        }
    }

    private fun applyPaint(tool: PenTool, width: Float, colorArgb: Int) {
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

    private fun drawStroke(stroke: Stroke) {
        val canvas = bitmapCanvas ?: return
        val points = stroke.points
        if (points.isEmpty()) return

        val strokeTool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
        val width = displayWidth(stroke)
        applyPaint(strokeTool, width, stroke.colorArgb)

        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }

        // Lo que pintó el SDK en vivo se repinta con su mismo pincel: así el
        // trazo no cambia de cara al repintar la región. El «pencil» del SDK
        // es una línea lisa de grosor fijo, y esa la pinta la app tal cual.
        if (stroke.sdkStyle >= 0) {
            if (SdkInk.draw(context, canvas, sdkPaint, stroke.sdkStyle, stroke.sensitivity, points, stroke.colorArgb, width, screenMatrix())) {
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
    private fun strokePath(points: List<InkPoint>): Path {
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

    private fun drawPencilSegment(
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

    private fun drawBallpointSegment(canvas: Canvas, a: InkPoint, b: InkPoint, base: Float, sensitivity: Float, colorArgb: Int) {
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
    private fun drawBallpointBlob(canvas: Canvas, first: InkPoint, second: InkPoint, base: Float, colorArgb: Int) {
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

    private fun report() {
        onDiagnostics?.invoke(diagnostics)
    }

    private fun sdkVersion(): String = runCatching {
        // El SDK no expone su versión; la clase sí dice dónde vive.
        TouchHelper::class.java.`package`?.name ?: "com.onyx.android.sdk.pen"
    }.getOrDefault("—")

    companion object {
        private const val TAG = "PenCanvasView"
        private const val ERASER_RADIUS_PX = 22f
        /** Recorrido del cono de grosor de los trazos de antes de los milímetros. */
        const val BASE_WIDTH_MIN = 1.5f
        const val BASE_WIDTH_MAX = 9f

        /**
         * Recorrido del subrayador de los trazos de antes: el grosor del cono
         * se estiraba entre estos dos anchos.
         */
        private const val MARKER_MIN_PX = 45f
        private const val MARKER_MAX_PX = 120f
        private const val MARKER_ALPHA = 110

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

        /** Cadencia de muestreo supuesta del digitalizador, si el SDK no marca el tiempo. */
        private const val SDK_SAMPLE_MS = 8L

        /** Si el SDK no dice su máximo y la presión viene en bruto, es de 12 bits. */
        private const val DEFAULT_MAX_PRESSURE = 4096f

        /** Inclinación de Android (radianes desde la vertical) a 0..1. */
        fun tiltFromRadians(radians: Float): Float {
            if (radians.isNaN()) return 0f
            return (radians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        }

        /** Inclinación desde los dos ángulos en grados que dan algunos digitalizadores. */
        fun tiltFromDegrees(tiltX: Float, tiltY: Float): Float {
            if (tiltX == 0f && tiltY == 0f) return 0f
            val ax = Math.toRadians(tiltX.coerceIn(-89f, 89f).toDouble())
            val ay = Math.toRadians(tiltY.coerceIn(-89f, 89f).toDouble())
            val theta = atan(sqrt(tan(ax) * tan(ax) + tan(ay) * tan(ay)))
            return (theta / (PI / 2)).toFloat().coerceIn(0f, 1f)
        }

        /** Cuánto espera el SDK tras soltar el lápiz para pedir el repintado de la zona, y su margen. */
        private const val PEN_UP_REFRESH_MS = 500
        private const val PEN_UP_PAD = 24
        private const val PEN_UP_MODE_RESET_MS = 250L
        private const val PASTE_OFFSET_PX = 40f
        private const val MAX_HISTORY = 100

        /** Alto de la hoja respecto a su ancho: un A4 (297/210). */
        const val PAGE_ASPECT = 1.41421f
        /** Hasta cuatro A4 de largo para las páginas de PDF ajenos. */
        const val MAX_PAGE_ASPECT = 4f * 1.41421f
        /** Recorrido del zoom de lectura. */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f

        // «Toda la nota a texto»: manchas, dibujos y renglones.
        private const val MIN_TEXT_UNIT_PX = 14f
        /** Cuánto "alcanza" un trazo a su alrededor, en fracción de su alto. */
        private const val BLOB_MARGIN = 0.35f
        /** Una mancha más alta que tantas letras (mediana) y no muy ancha es una figura. */
        private const val BIG_FIGURE_UNITS = 4f
        /** Un trazo o dos de más de tantas letras de ancho es una raya suelta. */
        private const val LONE_LINE_UNITS = 8f
        /** Una mancha entra en un renglón si su centro cae a menos de esto del suyo (fracción del alto). */
        private const val ROW_JOIN_FRACTION = 0.6f
        /** ...y su alto se parece al del renglón al menos así. */
        private const val ROW_HEIGHT_SIMILARITY = 0.45f
        /** Un hueco de tantos altos de renglón separa columnas. */
        private const val COLUMN_GAP_FRACTION = 2.5f
        /** Un trazo encerrado cuenta como anidado si mide al menos esto del que lo encierra. */
        private const val NESTED_MIN_FRACTION = 0.35f
        /** Suma de cajas de los trazos entre la caja de la mancha: por encima, es un dibujo. */
        private const val OVERLAP_SKETCH_RATIO = 1.4
        /** Renglones con alto dentro de este factor comparten cuerpo de letra. */
        private const val SIZE_GROUP_RATIO = 1.45f

        /** Cuerpo de letra respecto a la altura de cada renglón escrito a mano. */
        private const val TEXT_HEIGHT_FACTOR = 0.75f
        private const val TEXT_LINE_FACTOR = 1.25f
        private const val MIN_TEXT_PX = 24f
        private const val MAX_TEXT_PX = 140f

        /**
         * Cuánto esperar antes de reactivar el trazo rápido tras un repintado
         * o un cambio de punta. El demo de Onyx usa medio segundo en las
         * pantallas de color (`COLOR_DEVICE_PEN_RESUME_DELAY_TIME_MS`): el
         * panel Kaleido tarda más en asentar un repintado.
         */
        private const val RESUME_DELAY_MS = 500L

        /** Tras cerrar un diálogo o volver de otra app (`POPUP_RESUME_PEN_TIME_MS`). */
        private const val POPUP_RESUME_DELAY_MS = 500L
    }
}
