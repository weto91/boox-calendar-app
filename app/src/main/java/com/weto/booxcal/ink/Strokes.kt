package com.weto.booxcal.ink

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    /** Millis desde el inicio del trazo. ML Kit lo usa para reconocer mejor. */
    val t: Long = 0,
    val pressure: Float = 1f,
    /**
     * Inclinación del lápiz: 0 vertical, 1 tumbado del todo. El lápiz de
     * mina se ensancha al tumbarlo; el boli se afina. No se escribe si es
     * cero, que es lo normal y abultaría cada punto.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tilt: Float = 0f,
    /** Inclinación en grados por eje, como la da el digitalizador: el carboncillo del SDK la quiere así. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tiltX: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tiltY: Float = 0f,
)

/**
 * Un trazo.
 *
 * `colorArgb` y `tool` llevan valor por defecto para que las notas escritas
 * antes de que existieran las puntas se sigan leyendo: salen negras y a lápiz,
 * que es exactamente lo que eran.
 */
@Serializable
data class Stroke(
    val points: List<InkPoint>,
    /** Grosor base en píxeles del lienzo. */
    val width: Float = 3f,
    val colorArgb: Int = BLACK_ARGB,
    /** Nombre de [com.weto.booxcal.ink.PenTool]. Texto y no ordinal: el ordinal se rompe al reordenar el enum. */
    val tool: String = "PENCIL",
    /**
     * Grosor elegido, en milímetros. Cero en los trazos de antes de que el
     * grosor se eligiera en milímetros: esos se pintan como entonces (el
     * subrayador con su recorrido propio, el boli algo más fino que el
     * grosor, el lápiz liso), para que las notas viejas no cambien de cara.
     */
    val mm: Float = 0f,
    /** Textura del lápiz: 0 menos densa, 1 más densa. */
    val texture: Int = 0,
    /** Sensibilidad a la presión (lápiz o boli), de 0 a 1. */
    val sensitivity: Float = 0.5f,
    /**
     * Estilo del SDK de Onyx con el que se pintó en vivo (trazo rápido), o
     * -1 si lo pintó la app. Un trazo del SDK se repinta siempre con el
     * pincel del SDK de ese estilo, para que no cambie de cara.
     */
    val sdkStyle: Int = -1,
) {
    /** De antes de los grosores en milímetros. */
    val legacy: Boolean get() = mm <= 0f
}

private const val BLACK_ARGB: Int = 0xFF000000.toInt()

/**
 * Un texto colocado sobre el lienzo: lo que sale de reconocer un trozo escrito
 * a mano con «A texto». Ocupa el sitio de los trazos que sustituye y se mueve
 * con el lazo como cualquier otra cosa. `x`/`y` es su esquina superior
 * izquierda y `sizePx` el cuerpo de letra, en píxeles del lienzo.
 */
@Serializable
data class InkText(
    val text: String,
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val colorArgb: Int = BLACK_ARGB,
) {
    val lines: List<String> get() = text.split('\n')
}

/**
 * Documento de tinta. Guarda el tamaño del lienzo en el que se escribió: sin
 * él, al reabrir la nota en una vista de otro tamaño los trazos aparecerían
 * desplazados o recortados.
 *
 * `texts` lleva valor por defecto para que las notas guardadas antes de que
 * existieran los textos se sigan leyendo tal cual.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InkDocument(
    val canvasWidth: Float = 0f,
    val canvasHeight: Float = 0f,
    val strokes: List<Stroke> = emptyList(),
    val texts: List<InkText> = emptyList(),
    /**
     * Imagen de fondo de la página (nombre de fichero en [NoteStorage]): la
     * página tal cual de un PDF ajeno (OneNote, un escaneo…), que no se
     * puede convertir en trazos. Se enseña debajo de todo.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val background: String? = null,
    /**
     * Texto de la página que no se enseña pero sí se indexa: el texto a
     * máquina de un PDF ajeno con imagen de fondo (en la imagen ya se ve).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hiddenText: String = "",
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty() && background == null

    companion object {
        val EMPTY = InkDocument()
    }
}

/**
 * Cuaderno: varias páginas de tinta bajo una sola nota.
 *
 * Se guarda en el mismo campo que guardaba un documento suelto. El códec
 * distingue los dos formatos al leer, así que las notas de antes siguen
 * abriéndose — como un cuaderno de una página.
 */
@Serializable
data class InkNotebook(val pages: List<InkDocument> = listOf(InkDocument())) {

    val pageCount: Int get() = maxOf(pages.size, 1)

    val isEmpty: Boolean get() = pages.all { it.isEmpty }

    fun page(index: Int): InkDocument = pages.getOrElse(index) { InkDocument.EMPTY }

    fun withPage(index: Int, document: InkDocument): InkNotebook {
        val grown = pages.toMutableList()
        while (grown.size <= index) grown += InkDocument()
        grown[index] = document
        return copy(pages = grown)
    }

    /**
     * Quita las páginas vacías del final. Pasar de página crea una en blanco, y
     * sin esto un vistazo curioso al final del cuaderno lo dejaría lleno de
     * páginas que nadie escribió.
     */
    fun trimmed(): InkNotebook {
        val last = pages.indexOfLast { !it.isEmpty }
        return if (last < 0) InkNotebook(listOf(InkDocument())) else copy(pages = pages.take(last + 1))
    }

    companion object {
        val EMPTY = InkNotebook()
    }
}

object StrokeCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: InkDocument): String = json.encodeToString(InkDocument.serializer(), document)

    fun decode(payload: String?): InkDocument {
        if (payload.isNullOrBlank()) return InkDocument.EMPTY
        return decodeNotebook(payload).page(0)
    }

    fun encodeNotebook(notebook: InkNotebook): String =
        json.encodeToString(InkNotebook.serializer(), notebook.trimmed())

    fun decodeNotebook(payload: String?): InkNotebook {
        // Una nota grande vive en un fichero; la fila solo trae la referencia.
        val raw = NoteStorage.resolve(payload)
        if (raw.isNullOrBlank()) return InkNotebook.EMPTY
        return decodeRaw(raw)
    }

    private fun decodeRaw(payload: String): InkNotebook {
        if (payload.contains("\"pages\"")) {
            runCatching { json.decodeFromString(InkNotebook.serializer(), payload) }
                .getOrNull()
                ?.takeIf { it.pages.isNotEmpty() }
                ?.let { return it }
        }
        // Formato antiguo: un documento suelto.
        val single = runCatching { json.decodeFromString(InkDocument.serializer(), payload) }
            .getOrDefault(InkDocument.EMPTY)
        return InkNotebook(listOf(single))
    }
}

/** Escala un documento al tamaño del lienzo actual, manteniendo proporción. */
fun InkDocument.scaledTo(width: Float, height: Float): InkDocument {
    if (canvasWidth <= 0f || canvasHeight <= 0f || width <= 0f || height <= 0f) return this
    val factor = minOf(width / canvasWidth, height / canvasHeight)
    if (factor == 1f) return this
    return copy(
        canvasWidth = width,
        canvasHeight = height,
        strokes = strokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { it.copy(x = it.x * factor, y = it.y * factor) },
                width = stroke.width * factor,
            )
        },
        texts = texts.map { it.copy(x = it.x * factor, y = it.y * factor, sizePx = it.sizePx * factor) },
    )
}
