package com.weto.booxcal.ui.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkClipboard
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ink.InkFonts
import com.weto.booxcal.ink.InkNotebook
import com.weto.booxcal.ink.InkRecognizer
import com.weto.booxcal.ink.ModelState
import com.weto.booxcal.ink.PenCanvasView
import com.weto.booxcal.ink.PenDiagnostics
import com.weto.booxcal.ink.PenMode
import com.weto.booxcal.ink.PenTool
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.EinkWindowDensity
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.MinTouchTarget
import com.weto.booxcal.ui.theme.einkClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.weto.booxcal.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ink.Stroke
import java.util.Locale

/** Mando a distancia del lienzo: la vista es imperativa, Compose no. */
@Stable
class InkCanvasController {
    internal var view: PenCanvasView? = null

    fun document(): InkDocument = view?.document() ?: InkDocument.EMPTY
    fun selectionDocument(): InkDocument = view?.selectionDocument() ?: InkDocument.EMPTY
    fun isEmpty(): Boolean = view?.isEmpty() ?: true
    fun canUndo(): Boolean = view?.canUndo ?: false
    fun canRedo(): Boolean = view?.canRedo ?: false
    fun undo() = view?.undo() ?: Unit
    fun redo() = view?.redo() ?: Unit
    fun clear() = view?.clear() ?: Unit
    fun copySelection() = view?.copySelection() ?: Unit
    fun cutSelection() = view?.cutSelection() ?: Unit
    fun paste() = view?.paste() ?: Unit
    fun deleteSelection() = view?.deleteSelection() ?: Unit
    fun convertSelectionToText(text: String) = view?.convertSelectionToText(text) ?: Unit
    fun clearSelection() = view?.clearSelection() ?: Unit
    fun textLines(onlyTool: String? = null): List<PenCanvasView.TextLine> = view?.textLines(onlyTool).orEmpty()
    fun lineDocument(line: PenCanvasView.TextLine): InkDocument = view?.lineDocument(line) ?: InkDocument.EMPTY
    fun replaceWithTexts(conversions: List<Pair<PenCanvasView.TextLine, String>>) =
        view?.replaceWithTexts(conversions) ?: Unit
    fun setPenEnabled(enabled: Boolean) = view?.setPenEnabled(enabled) ?: Unit
    fun zoomBy(factor: Float) = view?.zoomBy(factor) ?: Unit
    fun resetZoom() = view?.resetZoom() ?: Unit
}

/**
 * Punta, color y grosores. Un solo objeto para no encadenar callbacks.
 *
 * Cada punta tiene su grosor, en milímetros sobre el papel: el lápiz y el
 * boli entre 0,10 y 2,00, el subrayador entre 0,50 y 8,00, siempre de
 * 0,05 en 0,05. El subrayador recuerda además su propio color: uno subraya
 * en un color y escribe en negro, y cambiar de punta no debe cambiar el
 * color de la otra. Todo esto se guarda entre sesiones.
 */
data class InkTools(
    val tool: PenTool = PenTool.PENCIL,
    /** La última punta que escribía, para volver a ella desde la goma o el lazo. */
    val lastTip: PenTool = PenTool.PENCIL,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
    val markerColorArgb: Int = DEFAULT_MARKER_COLOR_ARGB,
    val pencilWidthMm: Float = PENCIL_DEFAULT_MM,
    val ballpointWidthMm: Float = BALLPOINT_DEFAULT_MM,
    val markerWidthMm: Float = MARKER_DEFAULT_MM,
    /** Textura del lápiz: 0 menos densa, 1 más densa. */
    val pencilTexture: Int = 0,
    /** Cuánto responden a la presión el boli y el lápiz, de 0 a 100 %. */
    val ballpointPressure: Int = BALLPOINT_DEFAULT_PRESSURE,
    val pencilPressure: Int = PENCIL_DEFAULT_PRESSURE,
) {
    val lastDrawing: PenTool
        get() = if (tool == PenTool.ERASER || tool == PenTool.LASSO) lastTip else tool

    /** Cambia de útil; si es una punta que escribe, se recuerda como la última. */
    fun withTool(next: PenTool): InkTools =
        if (next == PenTool.ERASER || next == PenTool.LASSO) copy(tool = next) else copy(tool = next, lastTip = next)

    /** Color con el que pinta la punta elegida. */
    val activeColorArgb: Int
        get() = if (lastDrawing == PenTool.MARKER) markerColorArgb else colorArgb

    /** Cambia el color de la punta elegida, y solo de esa. */
    fun withColor(argb: Int): InkTools =
        if (lastDrawing == PenTool.MARKER) copy(markerColorArgb = argb) else copy(colorArgb = argb)

    /** Grosor de la punta elegida, en milímetros. */
    val widthMm: Float
        get() = when (lastDrawing) {
            PenTool.BALLPOINT -> ballpointWidthMm
            PenTool.MARKER -> markerWidthMm
            else -> pencilWidthMm
        }

    /** Sensibilidad a la presión de la punta elegida (el subrayador no tiene). */
    val pressure: Int
        get() = if (lastDrawing == PenTool.BALLPOINT) ballpointPressure else pencilPressure

    fun withPressure(value: Int): InkTools {
        val clean = value.coerceIn(0, 100)
        return if (lastDrawing == PenTool.BALLPOINT) copy(ballpointPressure = clean) else copy(pencilPressure = clean)
    }

    /** Recorrido del cono para la punta elegida. */
    val widthRange: ClosedFloatingPointRange<Float>
        get() = if (lastDrawing == PenTool.MARKER) MARKER_MM_RANGE else PEN_MM_RANGE

    /** Grosor de la punta elegida, recortado a su recorrido y redondeado al paso. */
    fun withWidthMm(mm: Float): InkTools {
        val snapped = snap(mm, widthRange)
        return when (lastDrawing) {
            PenTool.BALLPOINT -> copy(ballpointWidthMm = snapped)
            PenTool.MARKER -> copy(markerWidthMm = snapped)
            else -> copy(pencilWidthMm = snapped)
        }
    }

    /** La punta elegida vuelve a lo de fábrica: grosor, color y lo suyo. */
    fun resetActive(): InkTools = when (lastDrawing) {
        PenTool.BALLPOINT -> copy(
            ballpointWidthMm = BALLPOINT_DEFAULT_MM,
            ballpointPressure = BALLPOINT_DEFAULT_PRESSURE,
            colorArgb = DEFAULT_COLOR_ARGB,
        )
        PenTool.MARKER -> copy(markerWidthMm = MARKER_DEFAULT_MM, markerColorArgb = DEFAULT_MARKER_COLOR_ARGB)
        else -> copy(
            pencilWidthMm = PENCIL_DEFAULT_MM,
            pencilTexture = 0,
            pencilPressure = PENCIL_DEFAULT_PRESSURE,
            colorArgb = DEFAULT_COLOR_ARGB,
        )
    }

    companion object {
        const val PENCIL_DEFAULT_MM = 0.20f
        const val BALLPOINT_DEFAULT_MM = 0.50f
        const val MARKER_DEFAULT_MM = 5.00f
        const val BALLPOINT_DEFAULT_PRESSURE = 50
        const val PENCIL_DEFAULT_PRESSURE = 50
        const val STEP_MM = 0.05f
        val PEN_MM_RANGE: ClosedFloatingPointRange<Float> = 0.10f..2.00f
        val MARKER_MM_RANGE: ClosedFloatingPointRange<Float> = 0.50f..8.00f
        val DEFAULT_COLOR_ARGB: Int = 0xFF000000.toInt()
        /** El subrayador sale en rosa (el magenta de la paleta). */
        val DEFAULT_MARKER_COLOR_ARGB: Int = 0xFFFF00FF.toInt()

        fun snap(mm: Float, range: ClosedFloatingPointRange<Float>): Float {
            val steps = (mm / STEP_MM).roundToInt()
            return (steps * STEP_MM).coerceIn(range.start, range.endInclusive).let { (it * 100f).roundToInt() / 100f }
        }
    }
}

/** Lo guardado entre sesiones, como herramientas. */
fun AppSettings.toInkTools(): InkTools {
    val tool = runCatching { PenTool.valueOf(inkTool) }.getOrDefault(PenTool.PENCIL)
        .takeIf { it != PenTool.ERASER && it != PenTool.LASSO } ?: PenTool.PENCIL
    return InkTools(
        tool = tool,
        lastTip = tool,
        colorArgb = inkColorArgb,
        markerColorArgb = inkMarkerColorArgb,
        pencilWidthMm = InkTools.snap(inkPencilWidthMm, InkTools.PEN_MM_RANGE),
        ballpointWidthMm = InkTools.snap(inkBallpointWidthMm, InkTools.PEN_MM_RANGE),
        markerWidthMm = InkTools.snap(inkMarkerWidthMm, InkTools.MARKER_MM_RANGE),
        pencilTexture = inkPencilTexture.coerceIn(0, 1),
        ballpointPressure = inkBallpointPressure.coerceIn(0, 100),
        pencilPressure = inkPencilPressure.coerceIn(0, 100),
    )
}

/** Guarda las herramientas para la próxima vez. La goma y el lazo no se recuerdan: se vuelve a la punta. */
suspend fun SettingsStore.saveInkTools(tools: InkTools) = setInkTools(
    tool = tools.lastDrawing.name,
    colorArgb = tools.colorArgb,
    markerColorArgb = tools.markerColorArgb,
    pencilWidthMm = tools.pencilWidthMm,
    ballpointWidthMm = tools.ballpointWidthMm,
    markerWidthMm = tools.markerWidthMm,
    pencilTexture = tools.pencilTexture,
    ballpointPressure = tools.ballpointPressure,
    pencilPressure = tools.pencilPressure,
)

/** Un color de la paleta: su valor, su nombre y, en los cuatro «puros», las letras que llevan dentro. */
data class InkColor(val argb: Int, @StringRes val name: Int, val label: String? = null)

/** La paleta del panel de Boox, tal cual, en sus dos filas. */
val INK_COLORS: List<InkColor> = listOf(
    InkColor(0xFF000000.toInt(), R.string.color_black),
    InkColor(0xFF444444.toInt(), R.string.color_dark_grey),
    InkColor(0xFF808080.toInt(), R.string.color_grey),
    InkColor(0xFFBBBBBB.toInt(), R.string.color_light_grey),
    InkColor(0xFFFFFFFF.toInt(), R.string.color_white, "WT"),
    InkColor(0xFFFF6666.toInt(), R.string.color_red, "RD"),
    InkColor(0xFF33AA55.toInt(), R.string.color_green, "GN"),
    InkColor(0xFF000080.toInt(), R.string.color_blue, "BU"),
    InkColor(0xFF00FFFF.toInt(), R.string.color_cyan),
    InkColor(0xFFFF00FF.toInt(), R.string.color_pink),
    InkColor(0xFFFFA500.toInt(), R.string.color_orange),
    InkColor(0xFFFFFF00.toInt(), R.string.color_yellow),
    InkColor(0xFF008000.toInt(), R.string.color_dark_green),
    InkColor(0xFF8A4FBF.toInt(), R.string.color_purple),
    InkColor(0xFF2196F3.toInt(), R.string.color_light_blue),
    InkColor(0xFFFF4500.toInt(), R.string.color_red_orange),
)

/** The three tips: tool, icon and the resource of its name. */
val INK_TIPS: List<Triple<PenTool, Glyph, Int>> = listOf(
    Triple(PenTool.PENCIL, Glyph.Pencil, R.string.tip_pencil),
    Triple(PenTool.BALLPOINT, Glyph.Ballpoint, R.string.tip_ballpoint),
    Triple(PenTool.MARKER, Glyph.Marker, R.string.tip_marker),
)

private const val NOTICE_MILLIS = 2_500L
private const val MIN_LETTERS_PER_STROKE = 0.35f

/** Qué hacer con un texto reconocido a partir del lazo. */
enum class InkTextTarget { NOTE, EVENT, REMINDER }

/**
 * Estado del cuaderno, fuera del lienzo.
 *
 * Vive aparte para que la barra de herramientas y el lienzo puedan estar en
 * sitios distintos de la pantalla. En la pantalla principal las herramientas van
 * en la misma fila que las pestañas del módulo, y el lienzo debajo.
 *
 * Es `@Stable` a propósito: todo lo que lee Compose está en `mutableStateOf`.
 * Sin la anotación, cada recomposición del padre (cada guardado, cada cambio
 * del estado de sincronización) recomponía el lienzo y volvía a ejecutar su
 * bloque `update`, que hablaba con el SDK del lápiz en mitad de un trazo.
 */
@Stable
class InkBoardState internal constructor(
    val controller: InkCanvasController,
    private val onNotebookChanged: (InkNotebook) -> Unit,
    initialPage: Int = 0,
) {
    var tools by mutableStateOf(InkTools())
    /** Ya se cargaron las herramientas guardadas: a partir de ahí la punta elegida manda. */
    internal var toolsLoaded = false
    var notebook by mutableStateOf(InkNotebook.EMPTY)
        internal set
    /** Se abre por [initialPage]; `adopt` la recorta al cuaderno real cuando llega. */
    var page by mutableIntStateOf(initialPage.coerceAtLeast(0))
        private set
    var revision by mutableIntStateOf(0)
        internal set
    var selectionCount by mutableIntStateOf(0)
        internal set
    var diagnostics by mutableStateOf(PenDiagnostics())
        internal set

    /** Aviso sobre el lienzo (progreso de la conversión, un fallo). Null: nada. */
    var notice by mutableStateOf<String?>(null)
        internal set

    /**
     * «Toda la nota a texto»: cada renglón escrito a mano se sustituye por su
     * texto, en su sitio y a su tamaño; lo que no parece escritura (dibujos,
     * esquemas) se queda como está. Una sola entrada de deshacer.
     */
    fun convertWholeNote(scope: CoroutineScope, languageTag: String, textTool: String) {
        scope.launch {
            val results = recognizeWholeNote(languageTag, textTool) ?: return@launch
            controller.replaceWithTexts(results)
            revision++
            if (results.isEmpty()) flashNotice(text(R.string.ink_no_text_recognized)) else notice = null
        }
    }

    /**
     * Toda la nota como un evento o un recordatorio: se reconoce lo escrito
     * igual que en «a texto», pero el trazo se queda como está y el texto,
     * renglón a renglón, va de título a la ventana de creación.
     */
    fun createFromWholeNote(
        scope: CoroutineScope,
        languageTag: String,
        textTool: String,
        target: InkTextTarget,
    ) {
        scope.launch {
            val attachment = attachmentFor(controller.document(), textTool)
            val results = recognizeWholeNote(languageTag, textTool, quiet = attachment != null) ?: emptyList()
            if (results.isEmpty() && attachment == null) {
                flashNotice(text(R.string.ink_no_text_recognized))
                return@launch
            }
            notice = null
            val title = results.joinToString(" ") { (_, line) -> line.trim() }.titleCased()
            if (attachment == null) {
                onText?.invoke(target, title, true, null)
            } else {
                pendingCreate = PendingCreate(target, title, attachment.ink, attachment.hasDrawing, attachment.hasHighlight)
            }
        }
    }

    /** What travels as a handwritten note, and why. Null when nothing does. */
    private class Attachment(val ink: InkDocument, val hasDrawing: Boolean, val hasHighlight: Boolean)

    /**
     * The strokes that become the handwritten attachment: what was drawn
     * with the tip that is not the text tip (a pencil sketch next to
     * ballpoint text), and the ballpoint text the highlighter went over,
     * without the highlight itself. With "any" as text tool there is no
     * drawing tip, so only the highlighted text is attached.
     */
    private fun attachmentFor(document: InkDocument, textTool: String): Attachment? {
        val drawingTip = when (textTool) {
            "pencil" -> PenTool.BALLPOINT.name
            "ballpoint" -> PenTool.PENCIL.name
            else -> null
        }
        val textTip = when (textTool) {
            "pencil" -> PenTool.PENCIL.name
            else -> PenTool.BALLPOINT.name
        }
        val drawing = document.strokes.filter { drawingTip != null && it.tool == drawingTip }
        val markers = document.strokes.filter { it.tool == PenTool.MARKER.name }
        val highlighted = document.strokes.filter { stroke ->
            stroke.tool == textTip && markers.any { marker -> touches(marker, stroke) }
        }
        if (drawing.isEmpty() && highlighted.isEmpty()) return null
        val kept = (drawing + highlighted).toSet()
        return Attachment(
            ink = InkDocument(
                canvasWidth = document.canvasWidth,
                canvasHeight = document.canvasHeight,
                strokes = document.strokes.filter { it in kept },
            ),
            hasDrawing = drawing.isNotEmpty(),
            hasHighlight = highlighted.isNotEmpty(),
        )
    }

    private fun touches(a: Stroke, b: Stroke): Boolean {
        if (a.points.isEmpty() || b.points.isEmpty()) return false
        val pad = maxOf(a.width, b.width) / 2f
        return a.points.minOf { it.x } - pad <= b.points.maxOf { it.x } &&
            b.points.minOf { it.x } - pad <= a.points.maxOf { it.x } &&
            a.points.minOf { it.y } - pad <= b.points.maxOf { it.y } &&
            b.points.minOf { it.y } - pad <= a.points.maxOf { it.y }
    }

    /**
     * Reconoce cada renglón escrito a mano. Null si no había nada que
     * reconocer o el reconocedor no está (el aviso ya queda puesto); la lista
     * vacía significa que nada de lo escrito resultó ser texto.
     */
    private suspend fun recognizeWholeNote(
        languageTag: String,
        textTool: String,
        /** Nothing written with the text tip is fine (there is a drawing to attach): no notice. */
        quiet: Boolean = false,
    ): List<Pair<PenCanvasView.TextLine, String>>? {
        // Con una punta elegida en Ajustes no se adivina nada: lo de esa
        // punta es texto, lo demás dibujo.
        val onlyTool = when (textTool) {
            "pencil" -> PenTool.PENCIL.name
            "ballpoint" -> PenTool.BALLPOINT.name
            else -> null
        }
        val guessing = onlyTool == null
        val lines = controller.textLines(onlyTool)
        if (lines.isEmpty()) {
            if (quiet) return emptyList()
            flashNotice(
                text(
                    when (textTool) {
                        "pencil" -> R.string.ink_nothing_pencil
                        "ballpoint" -> R.string.ink_nothing_ballpoint
                        else -> R.string.ink_nothing_to_convert
                    }
                )
            )
            return null
        }
        notice = text(R.string.ink_preparing_recognizer)
        val model = Graph.inkRecognizer.prepare(languageTag)
        if (model is ModelState.Unavailable) {
            flashNotice(model.reason)
            return null
        }
        val results = mutableListOf<Pair<PenCanvasView.TextLine, String>>()
        lines.forEachIndexed { index, line ->
            notice = text(R.string.ink_recognizing_n_of_m, index + 1, lines.size)
            val text = Graph.inkRecognizer.recognize(controller.lineDocument(line))
                .getOrNull()?.firstOrNull()
            // Sin letras ni números no era escritura: se deja el trazo. Y
            // pocas letras para muchos trazos tampoco: escribiendo, cada
            // trazo da al menos media letra; un dibujo de ocho trazos que
            // el reconocedor lee como "A" es un dibujo.
            val letters = text?.count { it.isLetterOrDigit() } ?: 0
            val enough = letters > 0 &&
                (!guessing || letters >= line.strokeIndices.size * MIN_LETTERS_PER_STROKE)
            if (text != null && enough) results += line to text
        }
        return results
    }

    private suspend fun flashNotice(text: String) {
        notice = text
        delay(NOTICE_MILLIS)
        if (notice == text) notice = null
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = Graph.appContext.getString(id, *args)

    /**
     * Sube solo cuando hay que volver a cargar el lienzo desde el cuaderno:
     * al cambiar de página o de cuaderno. NO en cada trazo. Antes el lienzo
     * recargaba el documento después de cada trazo guardado, y esa recarga
     * apagaba y encendía el trazo rápido de Onyx en mitad de la escritura:
     * de ahí los trazos que se perdían y el principio que no aparecía.
     */
    var loadToken by mutableIntStateOf(0)
        private set

    /** Quien monta el cuaderno decide qué hacer con el texto reconocido. */
    /**
     * Texto reconocido para usar: el destino, el texto y si salió de toda la
     * nota (true) o de una selección del lazo (false). Quien crea un evento
     * o un recordatorio con toda la nota puede querer borrarla después.
     */
    var onText: ((InkTextTarget, String, Boolean, InkDocument?) -> Unit)? = null

    /**
     * A creation waiting for the user's yes: the note has something that will
     * travel as a handwritten attachment (a pencil drawing, highlighted
     * ballpoint text), and that is said before it happens.
     */
    var pendingCreate by mutableStateOf<PendingCreate?>(null)
        private set

    fun confirmCreate() {
        val pending = pendingCreate ?: return
        pendingCreate = null
        onText?.invoke(pending.target, pending.title, true, pending.ink)
    }

    fun cancelCreate() {
        pendingCreate = null
    }

    val pageCount: Int get() = notebook.pageCount

    internal fun commitPage(): InkNotebook {
        val updated = notebook.withPage(page, controller.document())
        notebook = updated
        return updated
    }

    internal fun onCanvasChanged() {
        revision++
        onNotebookChanged(commitPage())
    }

    /**
     * Cuaderno que llega después de montar (la nota del día se lee de la base
     * de datos, y la primera composición la ve vacía). Solo se adopta si aún
     * no se ha escrito nada: después, lo que manda es el lienzo.
     */
    internal fun adopt(fresh: InkNotebook) {
        if (revision != 0 || fresh == notebook) return
        notebook = fresh
        clampPage()
        loadToken++
    }

    internal fun clampPage() {
        page = page.coerceIn(0, (notebook.pageCount - 1).coerceAtLeast(0))
    }

    fun goToPage(target: Int) {
        if (target < 0) return
        val committed = commitPage()
        val grown =
            if (target >= committed.pageCount) committed.withPage(target, committed.newPage())
            else committed
        notebook = grown
        page = target
        revision++
        loadToken++
        onNotebookChanged(grown)
    }
}

@Composable
fun rememberInkBoardState(
    notebook: InkNotebook,
    key: Any? = Unit,
    onNotebookChanged: (InkNotebook) -> Unit,
    initialPage: Int = 0,
): InkBoardState {
    val state = remember(key) {
        InkBoardState(InkCanvasController(), onNotebookChanged, initialPage).also {
            it.notebook = notebook
            it.clampPage()
        }
    }
    LaunchedEffect(notebook) { state.adopt(notebook) }
    // Las herramientas guardadas: la primera vez enteras (punta incluida);
    // después solo lo que cambió en el panel, sin pisar la goma o el lazo
    // que se tenga en la mano. Se espera a lo guardado de verdad, no al
    // valor de arranque, que dejaría la punta de fábrica.
    val settings by Graph.settings.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
    val saved = settings?.toInkTools()
    // Keyed on the state too: a fresh sheet (after turning the note into an
    // event, say) is a new state object that has to load the saved tools
    // again, or it would start with the factory pencil.
    LaunchedEffect(state, saved) {
        if (saved == null) return@LaunchedEffect
        state.tools = if (state.toolsLoaded) saved.copy(tool = state.tools.tool, lastTip = state.tools.lastTip) else saved
        state.toolsLoaded = true
    }
    return state
}

// --- Lienzo -----------------------------------------------------------------

@Composable
fun InkSurface(
    state: InkBoardState,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    /** Solo se mira: el lápiz no pinta (notas importadas de otra app). */
    readOnly: Boolean = false,
) {
    val settings by Graph.settings.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val penMode = when (settings.penMode) {
        "sdk" -> PenMode.SDK
        "touch" -> PenMode.TOUCH
        else -> PenMode.AUTO
    }
    val tools = state.tools

    Column(modifier.fillMaxWidth()) {
        // El menú del lazo aparece encima del lienzo solo cuando hay algo
        // rodeado. Es el menú contextual que pedía la selección inteligente.
        if (state.selectionCount > 0) {
            SelectionBar(state)
        }
        state.notice?.let { EinkHint(it, Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }

        AndroidView(
            factory = { context ->
                PenCanvasView(context).apply {
                    strokeWidthMm = tools.widthMm
                    pencilTexture = tools.pencilTexture
                    pressureSensitivity = tools.pressure / 100f
                    tool = tools.tool
                    colorArgb = tools.activeColorArgb
                    this.penMode = penMode
                    onDocumentChanged = { state.onCanvasChanged() }
                    onDiagnostics = { state.diagnostics = it }
                    onSelectionChanged = { state.selectionCount = it }
                    setPenEnabled(!InkGate.blocked && !readOnly)
                    state.controller.view = this
                }
            },
            update = { canvas ->
                // El estado del cuaderno se crea de nuevo al cambiar de día,
                // de nota o de hoja, pero la vista es la misma: el estado nuevo
                // tiene que quedarse con ella. Sin esto su mando a distancia
                // apuntaba a nada, el lazo mandaba un documento vacío al OCR y
                // deshacer, borrar o pegar no hacían nada.
                state.controller.view = canvas
                canvas.textTypeface = InkFonts.typeface(canvas.context, settings.inkTextFont)
                canvas.strokeWidthMm = tools.widthMm
                canvas.pencilTexture = tools.pencilTexture
                canvas.pressureSensitivity = tools.pressure / 100f
                canvas.tool = tools.tool
                canvas.colorArgb = tools.activeColorArgb
                canvas.penMode = penMode
                canvas.onDocumentChanged = { state.onCanvasChanged() }
                canvas.onDiagnostics = { state.diagnostics = it }
                canvas.onSelectionChanged = { state.selectionCount = it }
                // Con una ventana flotante encima (crear evento o recordatorio)
                // el lápiz espera: si no, el trazo rápido se comía los toques
                // sobre sus botones y los pintaba en la nota.
                canvas.setPenEnabled(!InkGate.blocked && !readOnly)
                // Ampliar para leer: solo cuando el lápiz no pinta.
                canvas.zoomEnabled = true
            },
            onRelease = { canvas ->
                state.controller.view = null
                canvas.release()
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier.weight(1f))
                .border(HairlineWidth, Eink.Border, RectangleShape),
        )
    }

    // Se carga al montar, al cambiar de página y al cambiar de cuaderno (el
    // estado es otro objeto); nunca por un trazo propio. La clave lleva el
    // estado: dos estados recién creados tienen el mismo contador a cero, y
    // solo con el contador el efecto no volvía a correr al cambiar de hoja.
    LaunchedEffect(state, state.loadToken) {
        state.controller.view?.loadDocument(state.notebook.page(state.page))
    }
}

/**
 * Acciones sobre lo rodeado con el lazo.
 *
 * Reconocer va primero porque es lo que hace útil el lazo en un calendario:
 * rodeas lo que has apuntado y sale un recordatorio con ese texto.
 */
@Composable
private fun SelectionBar(state: InkBoardState) {
    val ocr = rememberInkOcrController()
    val settings by Graph.settings.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    var target by remember { mutableStateOf<InkTextTarget?>(null) }

    fun recognizeFor(kind: InkTextTarget) {
        target = kind
        ocr.recognize(settings.ocrLanguageTag, state.controller.selectionDocument())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Eink.White)
            .border(HairlineWidth, Eink.Black, ControlCorner)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionAction(Glyph.Today, stringResource(R.string.ink_action_event), Accent.Event) { recognizeFor(InkTextTarget.EVENT) }
            SelectionAction(Glyph.Bell, stringResource(R.string.ink_action_remind), Accent.Reminder) { recognizeFor(InkTextTarget.REMINDER) }
            SelectionAction(Glyph.Note, stringResource(R.string.ink_action_to_text), Accent.Search) { recognizeFor(InkTextTarget.NOTE) }
            Spacer(Modifier.weight(1f))
            SelectionAction(Glyph.Copy, stringResource(R.string.ink_action_copy), null) { state.controller.copySelection() }
            SelectionAction(Glyph.Cut, stringResource(R.string.ink_action_cut), null) {
                state.controller.cutSelection()
                state.revision++
            }
            SelectionAction(Glyph.Trash, stringResource(R.string.common_delete), Accent.Today) {
                state.controller.deleteSelection()
                state.revision++
            }
            EinkIconButton(
                glyph = Glyph.Check,
                onClick = { state.controller.clearSelection() },
                contentDescription = stringResource(R.string.ink_clear_selection),
                box = 44.dp,
            )
        }

        when (val result = ocr.state) {
            OcrState.Idle -> EinkHint(stringResource(R.string.ink_selection_hint))
            OcrState.Preparing, OcrState.Running -> EinkHint(stringResource(R.string.ink_recognizing))
            is OcrState.Failed -> EinkHint(result.reason)
            is OcrState.Ready -> Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EinkHint(stringResource(R.string.ink_choose))
                result.candidates.take(3).forEach { candidate ->
                    EinkButton(candidate, {
                        val kind = target
                        ocr.reset()
                        if (kind == InkTextTarget.NOTE) {
                            // «A texto» sustituye lo rodeado por el texto, en su
                            // sitio, y lo deja seleccionado para colocarlo con
                            // el lápiz. Quien monta el cuaderno se entera igual.
                            state.controller.convertSelectionToText(candidate)
                            state.revision++
                            state.onText?.invoke(kind, candidate, false, null)
                        } else {
                            kind?.let { state.onText?.invoke(it, candidate.titleCased(), false, null) }
                            state.controller.clearSelection()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(glyph: Glyph, label: String, accent: Color?, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(ControlCorner)
            .einkClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EinkIconButton(glyph, onClick, contentDescription = label, accent = accent, box = 36.dp, size = 20.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Eink.Graphite)
    }
}

// --- Menú de la nota --------------------------------------------------------

/**
 * Botón de la hoja con renglones, con su desplegable: pasar toda la nota a
 * texto, o crear un evento o un recordatorio con lo que pone en ella.
 *
 * El menú es un `Popup` (otra ventana): reaplica la densidad de la app, como
 * los diálogos, y el lienzo ya sabe pausar y reanudar el trazo rápido cuando
 * pierde y recupera el foco de ventana.
 */
@Composable
private fun NoteActionsMenu(
    enabled: Boolean,
    glyphSize: Dp,
    box: Dp,
    /** Pasar la nota a texto la cambia: en una nota de solo lectura no se ofrece. */
    onConvert: (() -> Unit)?,
    onCreate: (InkTextTarget) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Box {
        EinkIconButton(
            glyph = Glyph.Note,
            onClick = { open = true },
            contentDescription = stringResource(R.string.ink_note_actions),
            enabled = enabled,
            selected = open,
            size = glyphSize,
            box = box,
        )
        // Una punta de flecha pequeña en la esquina: dice que despliega.
        EinkGlyph(
            glyph = Glyph.CaretDown,
            size = 9.dp,
            tint = if (open) Eink.White else if (enabled) Eink.Black else Eink.Slate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 5.dp),
        )

        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { box.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                EinkWindowDensity {
                    Column(
                        Modifier
                            .width(300.dp)
                            .clip(ControlCorner)
                            .background(Eink.White)
                            .border(2.dp, Eink.Black, ControlCorner)
                            .padding(vertical = 6.dp),
                    ) {
                        if (onConvert != null) {
                            MenuAction(
                                glyph = Glyph.Note,
                                accent = Accent.Search,
                                label = stringResource(R.string.ink_whole_note_to_text),
                                hint = stringResource(R.string.ink_whole_note_to_text_hint),
                                onClick = { open = false; onConvert() },
                            )
                            EinkDivider(Modifier.padding(horizontal = 12.dp))
                        }
                        MenuAction(
                            glyph = Glyph.Today,
                            accent = Accent.Event,
                            label = stringResource(R.string.ink_create_event),
                            hint = stringResource(R.string.ink_create_hint),
                            onClick = { open = false; onCreate(InkTextTarget.EVENT) },
                        )
                        EinkDivider(Modifier.padding(horizontal = 12.dp))
                        MenuAction(
                            glyph = Glyph.Bell,
                            accent = Accent.Reminder,
                            label = stringResource(R.string.ink_create_reminder),
                            hint = stringResource(R.string.ink_create_hint),
                            onClick = { open = false; onCreate(InkTextTarget.REMINDER) },
                        )
                    }
                }
            }
        }
    }
}

/** Fila del menú: baldosa de color, acción y una línea de explicación. */
@Composable
private fun MenuAction(
    glyph: Glyph,
    accent: Color,
    label: String,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkTile(glyph, accent, size = 36.dp, glyphSize = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Graphite,
            )
        }
    }
}

// --- Herramientas -----------------------------------------------------------

/**
 * Barra de escritura, alineada a la derecha: pluma, goma, lazo, deshacer,
 * rehacer, borrar y el paginado. La pluma abre sus opciones al tocarla estando
 * ya elegida.
 */
@Composable
fun InkToolbar(
    state: InkBoardState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showPages: Boolean = true,
    /** Botón de hoja en blanco: una nota nueva. Solo donde hay varias por día. */
    onNewSheet: (() -> Unit)? = null,
    /**
     * Nota de solo lectura (importada de otra app): sin pluma, goma, lazo,
     * deshacer ni borrar. Quedan las acciones que solo leen (crear un evento
     * o un recordatorio con su texto) y el paso de páginas.
     */
    readOnly: Boolean = false,
) {
    var options by remember { mutableStateOf(false) }
    // Which destructive action is waiting for a yes: a new sheet with
    // writing on the current one, or wiping the sheet.
    var confirm by remember { mutableStateOf<ToolbarConfirm?>(null) }
    val box = if (compact) 44.dp else MinTouchTarget
    val glyph = if (compact) 20.dp else 22.dp
    val drawing = state.tools.tool != PenTool.ERASER && state.tools.tool != PenTool.LASSO

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (readOnly) EinkHint(stringResource(R.string.ink_read_only), Modifier.padding(end = 8.dp))
        // Zoom is by pinching with two fingers; the sheet is vectorial and
        // loses nothing enlarged.
        if (!readOnly) Box {
            EinkIconButton(
                glyph = Glyph.Pen,
                onClick = {
                    if (drawing) options = true
                    else state.tools = state.tools.withTool(state.tools.lastDrawing)
                },
                contentDescription = stringResource(R.string.ink_pen),
                selected = drawing,
                accent = Color(state.tools.activeColorArgb),
                size = glyph,
                box = box,
            )
            // El panel de la pluma: una ventana encima de todo.
            if (options) {
                PenOptionsPanel(
                    tools = state.tools,
                    onTools = { updated ->
                        state.tools = updated
                        // Se recuerda entre sesiones: es el único sitio donde se elige.
                        Graph.applicationScope.launch { Graph.settings.saveInkTools(updated) }
                    },
                    onDismiss = { options = false },
                )
            }
        }
        if (!readOnly) EinkIconButton(
            glyph = Glyph.Eraser,
            onClick = { state.tools = state.tools.withTool(PenTool.ERASER) },
            contentDescription = stringResource(R.string.ink_eraser),
            selected = state.tools.tool == PenTool.ERASER,
            size = glyph,
            box = box,
        )
        if (!readOnly) EinkIconButton(
            glyph = Glyph.Lasso,
            onClick = {
                state.controller.clearSelection()
                state.tools = state.tools.withTool(PenTool.LASSO)
            },
            contentDescription = stringResource(R.string.ink_lasso),
            selected = state.tools.tool == PenTool.LASSO,
            size = glyph,
            box = box,
        )
        if (!readOnly) EinkIconButton(
            glyph = Glyph.Undo,
            onClick = { state.controller.undo(); state.revision++ },
            contentDescription = stringResource(R.string.ink_undo),
            enabled = state.revision.let { state.controller.canUndo() },
            size = glyph,
            box = box,
        )
        if (!readOnly) EinkIconButton(
            glyph = Glyph.Redo,
            onClick = { state.controller.redo(); state.revision++ },
            contentDescription = stringResource(R.string.ink_redo),
            enabled = state.revision.let { state.controller.canRedo() },
            size = glyph,
            box = box,
        )
        val scope = rememberCoroutineScope()
        val settings by Graph.settings.settings
            .collectAsStateWithLifecycle(initialValue = AppSettings())
        NoteActionsMenu(
            enabled = state.notice == null,
            glyphSize = glyph,
            box = box,
            onConvert = if (readOnly) null else {
                { state.convertWholeNote(scope, settings.ocrLanguageTag, settings.inkTextTool) }
            },
            onCreate = { target ->
                state.createFromWholeNote(scope, settings.ocrLanguageTag, settings.inkTextTool, target)
            },
        )
        if (onNewSheet != null && !readOnly) {
            EinkIconButton(
                glyph = Glyph.NewSheet,
                // With nothing written there is nothing to warn about.
                onClick = { if (state.controller.isEmpty()) onNewSheet() else confirm = ToolbarConfirm.NEW_SHEET },
                contentDescription = stringResource(R.string.common_new_sheet),
                size = glyph,
                box = box,
            )
        }
        if (!readOnly) EinkIconButton(
            glyph = Glyph.Trash,
            onClick = { if (!state.controller.isEmpty()) confirm = ToolbarConfirm.CLEAR },
            enabled = state.revision.let { !state.controller.isEmpty() },
            contentDescription = stringResource(if (onNewSheet != null) R.string.ink_clear_note else R.string.ink_clear_page),
            size = glyph,
            box = box,
        )
        if (!InkClipboard.isEmpty && !readOnly) {
            EinkIconButton(
                glyph = Glyph.Paste,
                onClick = { state.controller.paste(); state.revision++ },
                contentDescription = stringResource(R.string.ink_paste),
                size = glyph,
                box = box,
            )
        }

        if (showPages) {
            EinkIconButton(
                glyph = Glyph.ChevronLeft,
                onClick = { state.goToPage(state.page - 1) },
                contentDescription = stringResource(R.string.ink_previous_page),
                enabled = state.page > 0,
                size = glyph,
                box = box,
            )
            Text(
                text = "${state.page + 1}/${state.pageCount}",
                style = MaterialTheme.typography.labelLarge,
                color = Eink.Black,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            EinkIconButton(
                glyph = Glyph.ChevronRight,
                onClick = { state.goToPage(state.page + 1) },
                contentDescription =
                    stringResource(if (state.page == state.pageCount - 1) R.string.ink_new_page else R.string.ink_next_page),
                // En solo lectura no se añaden páginas: se pasa hasta la última.
                enabled = !readOnly || state.page < state.pageCount - 1,
                size = glyph,
                box = box,
            )
        }
    }

    confirm?.let { pending ->
        val wholeNote = onNewSheet != null
        val title = stringResource(
            when (pending) {
                ToolbarConfirm.NEW_SHEET -> R.string.ink_new_sheet_title
                ToolbarConfirm.CLEAR -> if (wholeNote) R.string.ink_clear_note_title else R.string.ink_clear_page_title
            }
        )
        val text = stringResource(
            when (pending) {
                ToolbarConfirm.NEW_SHEET -> R.string.ink_new_sheet_text
                ToolbarConfirm.CLEAR -> if (wholeNote) R.string.ink_clear_note_text else R.string.ink_clear_page_text
            }
        )
        EinkDialog(onDismiss = { confirm = null }, title = title, modifier = Modifier.width(420.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Eink.Black)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                EinkButton(stringResource(R.string.common_cancel), { confirm = null })
                EinkButton(
                    stringResource(if (pending == ToolbarConfirm.NEW_SHEET) R.string.common_new_sheet else R.string.common_delete),
                    {
                        confirm = null
                        when (pending) {
                            ToolbarConfirm.NEW_SHEET -> onNewSheet?.invoke()
                            ToolbarConfirm.CLEAR -> { state.controller.clear(); state.revision++ }
                        }
                    },
                    emphasized = true,
                )
            }
        }
    }

    state.pendingCreate?.let { pending ->
        EinkDialog(
            onDismiss = state::cancelCreate,
            title = stringResource(if (pending.target == InkTextTarget.EVENT) R.string.ink_create_event else R.string.ink_create_reminder),
            modifier = Modifier.width(440.dp),
        ) {
            val lines = buildList {
                if (pending.hasDrawing) add(stringResource(R.string.ink_create_confirm_drawing))
                if (pending.hasHighlight) add(stringResource(R.string.ink_create_confirm_highlight))
                if (pending.title.isNotBlank()) add(stringResource(R.string.ink_create_confirm_text))
            }
            Text(lines.joinToString(" "), style = MaterialTheme.typography.bodyLarge, color = Eink.Black)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                EinkButton(stringResource(R.string.common_cancel), state::cancelCreate)
                EinkButton(stringResource(R.string.common_continue), state::confirmCreate, emphasized = true)
            }
        }
    }
}

/** A creation from the whole note that waits for a yes: see [InkBoardState.pendingCreate]. */
class PendingCreate(
    val target: InkTextTarget,
    val title: String,
    val ink: InkDocument,
    val hasDrawing: Boolean,
    val hasHighlight: Boolean,
)

/** The recogniser is poor at case: a title always starts with a capital. */
private fun String.titleCased(): String = replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** The two toolbar actions that ask before acting. */
private enum class ToolbarConfirm { NEW_SHEET, CLEAR }

/** Cuaderno con sus herramientas encima, para las pantallas que le dan todo el espacio. */
@Composable
fun InkBoard(
    notebook: InkNotebook,
    languageTag: String,
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    canvasHeight: Dp? = null,
    showPages: Boolean = true,
    onNotebookChanged: (InkNotebook) -> Unit,
    onUseText: (String) -> Unit = {},
    onCreateEntry: ((isEvent: Boolean, text: String, ink: InkDocument?) -> Unit)? = null,
    readOnly: Boolean = false,
    initialPage: Int = 0,
) {
    val state = rememberInkBoardState(notebook, key, onNotebookChanged, initialPage)
    state.onText = { target, text, _, ink ->
        when (target) {
            InkTextTarget.NOTE -> onUseText(text)
            InkTextTarget.EVENT -> onCreateEntry?.invoke(true, text, ink) ?: onUseText(text)
            InkTextTarget.REMINDER -> onCreateEntry?.invoke(false, text, ink) ?: onUseText(text)
        }
    }

    Column(modifier.fillMaxWidth()) {
        InkToolbar(state, showPages = showPages, readOnly = readOnly, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        InkSurface(
            state = state,
            height = canvasHeight,
            readOnly = readOnly,
            modifier = if (canvasHeight == null) Modifier.weight(1f) else Modifier,
        )
    }
}

/** Escritura a pantalla completa para adjuntar una nota a un evento o a una tarea. */
@Composable
fun InkCaptureSheet(
    title: String,
    initial: InkDocument,
    languageTag: String,
    onCancel: () -> Unit,
    onConfirm: (document: InkDocument, acceptedText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var notebook by remember { mutableStateOf(InkNotebook(listOf(initial))) }
    var acceptedText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(Eink.White)
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onCancel, contentDescription = stringResource(R.string.ink_discard))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            EinkIconButton(
                glyph = Glyph.Check,
                onClick = { onConfirm(notebook.page(0), acceptedText) },
                contentDescription = stringResource(R.string.ink_save_note),
                accent = Accent.Search,
            )
        }

        if (acceptedText != null) {
            EinkHint(stringResource(R.string.ink_recognized_title, acceptedText.orEmpty()), Modifier.padding(bottom = 4.dp))
        }
        EinkDivider()

        InkBoard(
            notebook = notebook,
            languageTag = languageTag,
            modifier = Modifier.weight(1f).padding(top = 6.dp),
            showPages = false,
            onNotebookChanged = { notebook = it },
            onUseText = { acceptedText = it },
        )
    }
}

// --- Reconocimiento ---------------------------------------------------------

sealed interface OcrState {
    data object Idle : OcrState
    data object Preparing : OcrState
    data object Running : OcrState
    data class Ready(val candidates: List<String>) : OcrState
    data class Failed(val reason: String) : OcrState
}

class InkOcrController(
    private val recognizer: InkRecognizer,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf<OcrState>(OcrState.Idle)
        private set

    fun recognize(languageTag: String, document: InkDocument) {
        if (document.isEmpty) {
            state = OcrState.Failed(Graph.appContext.getString(R.string.ink_nothing_written))
            return
        }
        scope.launch {
            state = OcrState.Preparing
            when (val model = recognizer.prepare(languageTag)) {
                is ModelState.Unavailable -> {
                    state = OcrState.Failed(model.reason)
                    return@launch
                }
                else -> Unit
            }
            state = OcrState.Running
            recognizer.recognize(document)
                .onSuccess { candidates ->
                    state = if (candidates.isEmpty()) {
                        OcrState.Failed(Graph.appContext.getString(R.string.ink_no_text_recognized))
                    } else {
                        OcrState.Ready(candidates)
                    }
                }
                .onFailure { state = OcrState.Failed(it.message ?: Graph.appContext.getString(R.string.ink_recognizer_failed)) }
        }
    }

    fun reset() {
        state = OcrState.Idle
    }
}

@Composable
fun rememberInkOcrController(): InkOcrController {
    val scope = rememberCoroutineScope()
    val controller = remember { InkOcrController(Graph.inkRecognizer, scope) }
    DisposableEffect(Unit) { onDispose { controller.reset() } }
    return controller
}
