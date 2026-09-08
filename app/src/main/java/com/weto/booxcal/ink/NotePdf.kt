package com.weto.booxcal.ink

import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Las notas como PDF vectorial editable, en el formato de Boox Notes.
 *
 * Boox (NeoReader / Notes) no guarda la escritura en el contenido de la
 * página, sino como **anotaciones**: una por trazo, con nombre
 * `#ONYX-STROKE`, su apariencia (un camino tramo a tramo, cada tramo con el
 * grosor que le da la presión), un objeto `onyxpoints` con los puntos
 * (x, y, tamaño, presión, tiempo) y un JSON `onyxtag` con la punta. El
 * contenido de la página es solo la plantilla.
 *
 * **Escribir**: cada trazo va como una de esas anotaciones, con todas sus
 * marcas, para que Boox Notes reconozca el PDF como suyo y pueda seguir
 * editándolo. La página va en píxeles del dispositivo (1860 de ancho), como
 * las de Boox. Los textos convertidos van como texto de la página, y el
 * cuaderno entero además embebido en la información del documento.
 *
 * **Leer**: si hay anotaciones de trazo, son la escritura (lo que Boox haya
 * añadido, borrado o cambiado manda); los textos salen del cuaderno
 * embebido si es un PDF de esta app. Sin anotaciones, se usa el cuaderno
 * embebido; y si tampoco, se recorren los caminos y el texto del contenido,
 * ignorando lo que parece plantilla.
 */
object NotePdf {

    /** Ancho de página de Boox, en píxeles del panel. Las páginas se escriben así. */
    private const val BOOX_PAGE_WIDTH = 1860f

    /** Ancho lógico del lienzo de una nota importada: A4 a 150 ppp. */
    const val IMPORT_CANVAS_WIDTH = 1240f

    /** Sube cuando cambia lo que se saca de un PDF ajeno: lo ya bajado se vuelve a bajar. */
    const val IMPORT_VERSION = 4

    private const val INFO_KEY = "BooxCalNotebook"
    private const val PRODUCER = "NeoPdf"
    /** La página importada va como JPEG: una de texto pesa una fracción del PNG. */
    private const val BACKGROUND_JPEG_QUALITY = 0.85f
    private const val HIDDEN_TEXT_SIZE = 6f
    private const val HIDDEN_TEXT_LINES = 400
    private const val CREATOR = "BOOX"
    private const val STROKE_NAME = "#ONYX-STROKE"
    private const val MARKER_ALPHA = 110f / 255f
    private const val TEXT_LINE_FACTOR = 1.25f
    private const val CURVE_STEPS = 8
    private const val ONYX_MAX_PRESSURE = 4095f
    private const val ONYX_TOUCH_SIZE = 7445f

    /** Una raya que cubre este tanto del ancho (o alto) con este grosor es plantilla. */
    private const val RULE_SPAN = 0.7f
    private const val RULE_THICKNESS = 4f
    /** Un contorno que cubre casi toda la hoja en los dos sentidos es un marco. */
    private const val FRAME_SPAN = 0.85f
    /** Texto de la plantilla de Boox, por si el color no bastara para descartarlo. */
    private const val TEMPLATE_PREFIX = "Plantilla Boox"
    /** A partir de qué nivel un color cuenta como blanco (0..255). */
    private const val WHITE_FLOOR = 245
    /** Cuerpo con el que se coloca un cuadro de texto, en puntos. */
    private const val FREE_TEXT_SIZE_PT = 12f
    /** Ancho de la imagen de una página ajena (el de la pantalla de la tablet) y su alto máximo, en píxeles. */
    private const val IMAGE_PAGE_WIDTH = 1860f
    private const val MAX_PAGE_HEIGHT_PX = 9000f
    /** Dos tramos se encadenan si el segundo empieza donde acabó el primero. */
    private const val JOIN_EPSILON = 0.6f
    /** Separación mínima entre puntos importados, en píxeles del lienzo. */
    private const val MIN_POINT_GAP = 1.5f

    private val pdfDate = SimpleDateFormat("'D:'yyyyMMddHHmmss", Locale.US)

    // --- Escribir -----------------------------------------------------------

    fun write(notebook: InkNotebook, title: String?): ByteArray {
        val trimmed = notebook.trimmed()
        val doc = PDDocument()
        try {
            doc.documentInformation.apply {
                producer = PRODUCER
                creator = CREATOR
                author = CREATOR
                if (!title.isNullOrBlank()) this.title = title
                setCustomMetadataValue(INFO_KEY, StrokeCodec.encodeNotebook(trimmed))
            }
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("PieceInfo"), onyxPieceInfo(
                """{"app":"com.onyx.android.ksync","commitId":"${UUID.randomUUID().toString().replace("-", "")}","exported":true,"id":""}"""
            ))
            trimmed.pages.forEach { page -> writePage(doc, page) }
            if (doc.numberOfPages == 0) doc.addPage(PDPage(PDRectangle(BOOX_PAGE_WIDTH, BOOX_PAGE_WIDTH * PenCanvasView.PAGE_ASPECT)))
            val out = ByteArrayOutputStream()
            doc.save(out)
            return patchObjectNumbers(out.toByteArray())
        } finally {
            doc.close()
        }
    }

    private fun onyxPieceInfo(json: String): COSDictionary {
        val tag = COSDictionary()
        tag.setString(COSName.getPDFName("LastModified"), pdfDate.format(Date()))
        tag.setItem(COSName.getPDFName("Private"), COSString(json))
        val piece = COSDictionary()
        piece.setItem(COSName.getPDFName("onyxtag"), tag)
        return piece
    }

    private fun writePage(doc: PDDocument, page: InkDocument) {
        val canvasWidth = if (page.canvasWidth > 0f) page.canvasWidth else IMPORT_CANVAS_WIDTH
        val canvasHeight = if (page.canvasHeight > 0f) page.canvasHeight else canvasWidth * PenCanvasView.PAGE_ASPECT
        val scale = BOOX_PAGE_WIDTH / canvasWidth
        val pageHeight = canvasHeight * scale
        val pdPage = PDPage(PDRectangle(BOOX_PAGE_WIDTH, pageHeight))
        doc.addPage(pdPage)
        pdPage.cosObject.setItem(COSName.getPDFName("PieceInfo"), onyxPieceInfo("""{"id":"","producer":"NeoPdf","type":"page"}"""))
        pdPage.cosObject.setBoolean(COSName.getPDFName("ONYXAnnotsCloned"), true)
        pdPage.cosObject.setString(COSName.getPDFName("LastModified"), pdfDate.format(Date()))

        fun px(x: Float) = x * scale
        fun py(y: Float) = pageHeight - y * scale

        // Contenido de la página: fondo blanco, la imagen de fondo (una
        // página importada de otra app), los textos convertidos y, invisible,
        // el texto a máquina de la página importada, para que el PDF de
        // Drive siga siendo buscable y se vea como el original.
        PDPageContentStream(doc, pdPage).use { cs ->
            cs.setNonStrokingColor(1f, 1f, 1f)
            cs.addRect(0f, 0f, BOOX_PAGE_WIDTH, pageHeight)
            cs.fill()
            page.background?.let { name ->
                NoteStorage.loadImage(name)?.let { bitmap ->
                    try {
                        runCatching {
                            val image = JPEGFactory.createFromImage(doc, bitmap, BACKGROUND_JPEG_QUALITY)
                            cs.drawImage(image, 0f, 0f, BOOX_PAGE_WIDTH, pageHeight)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            if (page.hiddenText.isNotBlank()) {
                runCatching {
                    cs.setRenderingMode(RenderingMode.NEITHER)
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    page.hiddenText.lines().filter { it.isNotBlank() }.take(HIDDEN_TEXT_LINES).forEachIndexed { index, line ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, HIDDEN_TEXT_SIZE)
                        cs.newLineAtOffset(4f, (pageHeight - 8f - index * (HIDDEN_TEXT_SIZE + 1f)).coerceAtLeast(2f))
                        cs.showText(latin1(line))
                        cs.endText()
                    }
                    cs.setRenderingMode(RenderingMode.FILL)
                }
            }
            page.texts.forEach { text ->
                val size = max(text.sizePx * scale, 1f)
                val lineHeight = size * TEXT_LINE_FACTOR
                cs.setNonStrokingColor(
                    Color.red(text.colorArgb) / 255f,
                    Color.green(text.colorArgb) / 255f,
                    Color.blue(text.colorArgb) / 255f,
                )
                text.lines.forEachIndexed { index, line ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, size)
                    cs.newLineAtOffset(px(text.x), py(text.y + lineHeight * index + size * 0.9f))
                    cs.showText(latin1(line))
                    cs.endText()
                }
            }
        }

        val annotations = mutableListOf<PDAnnotation>()
        page.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            annotations += strokeAnnotation(doc, stroke, scale, pageHeight)
        }
        pdPage.annotations = annotations
    }

    /** Un trazo como anotación de Boox: apariencia tramo a tramo, puntos y etiqueta. */
    private fun strokeAnnotation(doc: PDDocument, stroke: Stroke, scale: Float, pageHeight: Float): PDAnnotation {
        val tool = runCatching { PenTool.valueOf(stroke.tool) }.getOrDefault(PenTool.PENCIL)
        val points = if (stroke.points.size == 1) {
            listOf(stroke.points[0], stroke.points[0].copy(x = stroke.points[0].x + 0.3f))
        } else {
            stroke.points
        }
        // Grosor de cada punto en píxeles de página, como lo pinta el lienzo
        // (los trazos de antes de los milímetros, como los pintaba entonces).
        val widths = points.map { point ->
            val pressure = point.pressure.coerceIn(0f, 1f)
            val tilt = point.tilt.coerceIn(0f, 1f)
            when {
                tool == PenTool.MARKER -> if (stroke.legacy) PenCanvasView.markerWidth(stroke.width) else stroke.width
                tool == PenTool.BALLPOINT && stroke.legacy -> stroke.width * 0.85f * (0.78f + 0.4f * pressure)
                tool == PenTool.BALLPOINT ->
                    stroke.width * (1f + (pressure - 0.5f) * 2f * stroke.sensitivity * 0.6f) * (1f - 0.45f * tilt)
                stroke.legacy -> stroke.width * (0.5f + pressure)
                else -> stroke.width * (0.75f + 0.4f * pressure) * (1f + 1.5f * tilt)
            } * scale
        }
        val baseWidth = stroke.width * scale
        val alpha = if (tool == PenTool.MARKER) MARKER_ALPHA else 1f
        val r = Color.red(stroke.colorArgb) / 255f
        val g = Color.green(stroke.colorArgb) / 255f
        val b = Color.blue(stroke.colorArgb) / 255f

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        points.forEachIndexed { i, p ->
            xs[i] = p.x * scale
            ys[i] = pageHeight - p.y * scale
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        val pad = (widths.maxOrNull() ?: baseWidth) / 2f + 1f
        val rect = PDRectangle(minX - pad, minY - pad, (maxX - minX) + pad * 2, (maxY - minY) + pad * 2)

        val annotation = PDAnnotation.createAnnotation(COSDictionary().apply {
            setName(COSName.TYPE, "Annot")
            setName(COSName.SUBTYPE, "Stamp")
        })
        val cos = annotation.cosObject
        cos.setName(COSName.NAME, STROKE_NAME)
        cos.setInt(COSName.F, 4)
        cos.setItem(COSName.C, rgbArray(r, g, b))
        cos.setItem(COSName.IC, rgbArray(r, g, b))
        cos.setFloat(COSName.CA, alpha)
        cos.setString(COSName.T, "")
        cos.setString(COSName.M, pdfDate.format(Date()))
        cos.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, baseWidth) })
        annotation.rectangle = rect

        // Apariencia: cada tramo con su grosor, cabos y uniones redondos.
        val appearance = PDAppearanceStream(doc)
        appearance.bBox = rect
        appearance.resources = PDResources().also { resources ->
            resources.put(COSName.getPDFName("GS"), PDExtendedGraphicsState().apply {
                strokingAlphaConstant = alpha
                nonStrokingAlphaConstant = alpha
                // El port Android no expone el cabo como entero: va al diccionario.
                cosObject.setInt(COSName.LC, 2)
                setBlendMode(com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL)
                alphaSourceFlag = false
            })
        }
        PDPageContentStream(doc, appearance).use { cs ->
            cs.setGraphicsStateParameters(appearance.resources.getExtGState(COSName.getPDFName("GS")))
            cs.setStrokingColor(r, g, b)
            cs.setLineJoinStyle(1)
            cs.setLineCapStyle(1)
            for (i in 1 until points.size) {
                cs.setLineWidth(max(widths[i], 0.3f))
                cs.moveTo(xs[i - 1], ys[i - 1])
                cs.lineTo(xs[i], ys[i])
                cs.stroke()
            }
        }
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }

        // Puntos: versión, tamaño, y por punto x, y (desde arriba), tamaño
        // del toque, presión (sobre 4095) y milisegundos desde el inicio.
        val payload = ByteBuffer.allocate(8 + points.size * 20).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(1)
        payload.putInt(points.size * 20)
        val t0 = points.first().t
        points.forEachIndexed { i, p ->
            payload.putFloat(xs[i])
            payload.putFloat(pageHeight - ys[i])
            payload.putFloat(ONYX_TOUCH_SIZE)
            payload.putFloat((p.pressure.coerceIn(0f, 1f) * ONYX_MAX_PRESSURE).coerceAtLeast(1f))
            payload.putFloat((p.t - t0).coerceAtLeast(0L).toFloat())
        }
        val pointsStream = PDStream(doc)
        pointsStream.createOutputStream(COSName.FLATE_DECODE).use { it.write(payload.array()) }
        cos.setItem(COSName.getPDFName("onyxpoints"), pointsStream)
        cos.setString(COSName.getPDFName("onyxpointsObjNum"), OBJNUM_PLACEHOLDER)

        val extra = """{\"originStrokeWidth\":$baseWidth,\"paintStyle\":1,\"shapeCreateArgs\":{\"alphaFactor\":${if (tool == PenTool.MARKER) MARKER_ALPHA else 1.0},\"displayScale\":1.0,\"dpi\":320.0,\"maxPressure\":4095.0,\"newBrushRatio\":0.0,\"pressure\":0.0,\"pressureSensitivity\":0.3,\"smoothLevel\":0.6,\"source\":0,\"tiltX\":0,\"tiltY\":0},\"type\":5}"""
        val tag = """{"blend_mode":"","custom_attr":"","extra_attr":"$extra","id":"${UUID.randomUUID()}","layer_id":"0","matrix":"[1.0,-0.0,-0.0,1.0,0.0,-0.0,0.0,0.0,1.0]","type":"NeoBrushStroke","version":"2"}"""
        cos.setString(COSName.getPDFName("onyxtag"), tag)
        return annotation
    }

    private fun rgbArray(r: Float, g: Float, b: Float): COSArray = COSArray().apply {
        add(COSFloat(r)); add(COSFloat(g)); add(COSFloat(b))
    }

    private const val OBJNUM_PLACEHOLDER = "000000"

    /**
     * `onyxpointsObjNum` lleva el número de objeto del flujo de puntos, y ese
     * número no se conoce hasta guardar. Se escribe un hueco del mismo largo
     * y se rellena sobre los bytes, que así no se mueve ningún desplazamiento.
     */
    private fun patchObjectNumbers(pdf: ByteArray): ByteArray {
        val text = String(pdf, Charsets.ISO_8859_1)
        val builder = StringBuilder(text)
        val regex = Regex("""/onyxpoints\s+(\d+)\s+0\s+R\s*/onyxpointsObjNum\s*\($OBJNUM_PLACEHOLDER\)""")
        regex.findAll(text).forEach { match ->
            val number = match.groupValues[1]
            if (number.length > OBJNUM_PLACEHOLDER.length) return@forEach
            val padded = number.padStart(OBJNUM_PLACEHOLDER.length, '0')
            val start = match.range.last - OBJNUM_PLACEHOLDER.length
            builder.setRange(start, start + OBJNUM_PLACEHOLDER.length, padded)
        }
        return builder.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** Helvetica estándar solo sabe WinAnsi: lo que no entre se sustituye. */
    private fun latin1(text: String): String =
        buildString(text.length) {
            text.forEach { c ->
                append(if (c.code in 32..255 || c == '€') c else '?')
            }
        }

    // --- Leer ---------------------------------------------------------------

    /** [onProgress] recibe (página leída, total), para enseñar cuánto falta en un PDF largo. */
    fun read(bytes: ByteArray, onProgress: ((Int, Int) -> Unit)? = null): InkNotebook {
        PDDocument.load(bytes).use { doc ->
            val embedded = doc.documentInformation.getCustomMetadataValue(INFO_KEY)
                ?.let { runCatching { StrokeCodec.decodeNotebook(it) }.getOrNull() }
            val hasStrokeAnnotations = (0 until doc.numberOfPages).any { index ->
                runCatching { doc.getPage(index).annotations.any { it.isStrokeAnnotation } }.getOrDefault(false)
            }
            // Sin anotaciones, el cuaderno embebido es la nota tal cual se
            // guardó. Con ellas mandan las anotaciones: Boox pudo cambiarlas.
            if (!hasStrokeAnnotations && embedded != null && !embedded.isEmpty) {
                return InkNotebook(embedded.pages.mapIndexed { index, page -> page.withRestoredBackground(doc, index) })
            }

            val info = doc.documentInformation
            val boox = info.producer?.contains("NeoPdf", ignoreCase = true) == true ||
                info.creator?.contains("BOOX", ignoreCase = true) == true
            // Un PDF ajeno que no es de Boox (OneNote, un escaneo, lo que
            // sea) no se puede pasar a trazos con garantías: su tinta suele ir
            // como formas rellenas o como imagen. Cada página se guarda como
            // imagen de fondo, tal cual se ve, y su texto a máquina se lleva
            // aparte para indexarlo; lo manuscrito lo reconoce el indexador
            // sobre la imagen.
            val foreign = embedded == null && !boox && !hasStrokeAnnotations
            val renderer = if (foreign) PDFRenderer(doc) else null
            val total = doc.numberOfPages
            val pages = (0 until total).map { index ->
                val page = doc.getPage(index)
                val read = (if (renderer != null) runCatching { readImagePage(doc, renderer, page, index) }.getOrNull() else null)
                    ?: readPage(doc, page, index, annotationsOnly = boox || hasStrokeAnnotations, embedded?.page(index))
                onProgress?.invoke(index + 1, total)
                read
            }
            return InkNotebook(pages.ifEmpty { listOf(InkDocument()) }).trimmed()
        }
    }

    private val PDAnnotation.isStrokeAnnotation: Boolean
        get() = subtype == "Ink" || cosObject.getNameAsString(COSName.NAME) == STROKE_NAME

    private fun readPage(
        doc: PDDocument,
        page: PDPage,
        index: Int,
        annotationsOnly: Boolean,
        embeddedPage: InkDocument?,
    ): InkDocument {
        val box = page.cropBox
        val scale = IMPORT_CANVAS_WIDTH / max(box.width, 1f)
        val canvasHeight = box.height * scale
        val engine = PathEngine(page, box, scale)
        runCatching { engine.run(annotationsOnly) }
        // Los textos: los de esta app van en el cuaderno embebido. En un PDF
        // ajeno se leen del contenido, también en los de Boox: el texto de la
        // plantilla («Plantilla Boox…») va en blanco y se descarta por el
        // color, y lo demás es texto escrito a máquina, que hay que indexar
        // para poder buscarlo. Los cuadros de texto que van como anotación
        // (FreeText) también cuentan.
        val texts = when {
            embeddedPage != null -> embeddedPage.texts.map { it.rescaled(embeddedPage, IMPORT_CANVAS_WIDTH) }
            else -> runCatching { TextCollector(box, scale).collect(doc, index + 1) }.getOrDefault(emptyList()) +
                runCatching { freeTexts(page, box, scale) }.getOrDefault(emptyList())
        }
        // Una página importada y luego editada en la app: su imagen de fondo
        // y su texto a máquina viajan en el cuaderno embebido.
        val restored = embeddedPage?.withRestoredBackground(doc, index)
        return InkDocument(
            IMPORT_CANVAS_WIDTH, canvasHeight, engine.strokes, texts,
            background = restored?.background,
            hiddenText = restored?.hiddenText.orEmpty(),
        )
    }

    /**
     * La imagen de fondo de una página del cuaderno embebido vive en un
     * archivo de este dispositivo. Si aquí no está (el PDF viene de otra
     * tablet, o de una reinstalación), se saca del propio PDF: es la imagen
     * que la app pintó en la página al escribirlo.
     */
    private fun InkDocument.withRestoredBackground(doc: PDDocument, index: Int): InkDocument {
        val name = background ?: return this
        if (NoteStorage.hasImage(name)) return this
        val restored = runCatching { extractPageImage(doc.getPage(index)) }.getOrNull()
        return copy(background = restored)
    }

    /** La primera imagen del contenido de la página, guardada como fondo. Null si no hay. */
    private fun extractPageImage(page: PDPage): String? {
        val resources = page.resources ?: return null
        for (name in resources.xObjectNames) {
            val xobject = runCatching { resources.getXObject(name) }.getOrNull()
            if (xobject is PDImageXObject) {
                val bitmap = runCatching { xobject.image }.getOrNull() ?: continue
                return try {
                    NoteStorage.saveImage(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return null
    }

    /**
     * Una página de un PDF ajeno como imagen de fondo, al ancho del lienzo
     * de importación, con su texto a máquina aparte para el índice. Null si
     * no hay dónde guardar la imagen.
     */
    private fun readImagePage(doc: PDDocument, renderer: PDFRenderer, page: PDPage, index: Int): InkDocument? {
        val box = page.cropBox
        val scale = IMPORT_CANVAS_WIDTH / max(box.width, 1f)
        // La imagen se guarda al ancho de la pantalla de la tablet, que da
        // margen para ampliar al leer; las páginas muy largas (OneNote no
        // tiene fin) se limitan. A 72 puntos por pulgada, el ancho en
        // píxeles es box.width × dpi / 72.
        val imageScale = IMAGE_PAGE_WIDTH / max(box.width, 1f)
        val dpi = min(72f * imageScale, MAX_PAGE_HEIGHT_PX * 72f / max(box.height, 1f))
        val bitmap = renderer.renderImageWithDPI(index, dpi)
        val name = try {
            NoteStorage.saveImage(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return null
        val typed = runCatching { TextCollector(box, scale).collect(doc, index + 1) }.getOrDefault(emptyList()) +
            runCatching { freeTexts(page, box, scale) }.getOrDefault(emptyList())
        // El lienzo de la nota sigue en su ancho de siempre; la imagen se
        // encaja en él al pintar, tenga los píxeles que tenga.
        return InkDocument(
            canvasWidth = box.width * scale,
            canvasHeight = box.height * scale,
            background = name,
            hiddenText = typed.joinToString("\n") { it.text }.trim(),
        )
    }

    /** Cuadros de texto de la página (anotaciones FreeText), con su contenido y su sitio). */
    private fun freeTexts(page: PDPage, box: PDRectangle, scale: Float): List<InkText> =
        page.annotations
            .filter { it.subtype == "FreeText" }
            .mapNotNull { annotation ->
                val text = annotation.contents?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rect = annotation.rectangle ?: return@mapNotNull null
                InkText(
                    text = text,
                    x = (rect.lowerLeftX - box.lowerLeftX) * scale,
                    y = (box.upperRightY - rect.upperRightY) * scale,
                    sizePx = FREE_TEXT_SIZE_PT * scale,
                )
            }

    private fun InkText.rescaled(from: InkDocument, toWidth: Float): InkText {
        if (from.canvasWidth <= 0f) return this
        val factor = toWidth / from.canvasWidth
        return copy(x = x * factor, y = y * factor, sizePx = sizePx * factor)
    }

    /**
     * Recorre el contenido de una página y sus anotaciones y convierte cada
     * camino en trazos. Las coordenadas llegan ya en el espacio de la página
     * (origen abajo a la izquierda); aquí se pasan al del lienzo (origen
     * arriba). Los tramos encadenados de una misma anotación (Boox pinta un
     * tramo por punto, cada uno con su grosor) se funden en un solo trazo con
     * la presión que corresponde a cada grosor.
     */
    private class PathEngine(
        page: PDPage,
        private val box: PDRectangle,
        private val scale: Float,
    ) : PDFGraphicsStreamEngine(page) {

        val strokes = mutableListOf<Stroke>()

        private val subpaths = mutableListOf<MutableList<InkPoint>>()
        private var current: MutableList<InkPoint>? = null
        private val last = PointF()

        /** Trazo en construcción: puntos con el grosor de cada tramo. */
        private val pendingPoints = mutableListOf<InkPoint>()
        private val pendingWidths = mutableListOf<Float>()
        private var pendingColor = Color.BLACK
        private var pendingMarker = false

        /** Mientras se recorre la plantilla de un PDF de Boox no se guarda nada. */
        private var discarding = false

        fun run(annotationsOnly: Boolean) {
            // El contenido se procesa siempre (es lo que deja el motor listo
            // para las anotaciones); en un PDF de Boox es la plantilla y se tira.
            discarding = annotationsOnly
            processPage(page)
            discarding = false
            flush()
            page.annotations.forEach { annotation ->
                runCatching { showAnnotation(annotation) }
                flush()
            }
        }

        private fun toInk(x: Float, y: Float) = InkPoint(
            x = (x - box.lowerLeftX) * scale,
            y = (box.upperRightY - y) * scale,
        )

        private fun start(x: Float, y: Float): MutableList<InkPoint> =
            mutableListOf(toInk(x, y)).also { subpaths += it; current = it }

        override fun moveTo(x: Float, y: Float) {
            start(x, y)
            last.set(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            (current ?: start(last.x, last.y)).add(toInk(x, y))
            last.set(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val path = current ?: start(last.x, last.y)
            val x0 = last.x
            val y0 = last.y
            for (step in 1..CURVE_STEPS) {
                val t = step / CURVE_STEPS.toFloat()
                val u = 1f - t
                val x = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
                val y = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
                path.add(toInk(x, y))
            }
            last.set(x3, y3)
        }

        override fun getCurrentPoint(): PointF = PointF(last.x, last.y)

        override fun closePath() {
            current?.let { if (it.size > 1) it.add(it.first()) }
        }

        override fun endPath() {
            subpaths.clear()
            current = null
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            moveTo(p0.x, p0.y)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            closePath()
        }

        override fun strokePath() {
            val widthPt = runCatching { transformWidth(graphicsState.lineWidth) }.getOrDefault(1f)
            val alpha = runCatching { graphicsState.alphaConstant }.getOrDefault(1.0).toFloat()
            emit(widthPt * scale, strokingRgb(), alpha)
            endPath()
        }

        /**
         * Los rellenos no se importan: en los PDF de apps de notas son la
         * plantilla (fondos, formas, márgenes), no lo escrito.
         */
        override fun fillPath(windingRule: Path.FillType) = endPath()

        override fun fillAndStrokePath(windingRule: Path.FillType) = strokePath()

        override fun clip(windingRule: Path.FillType) = Unit
        override fun drawImage(pdImage: PDImage) = Unit
        override fun shadingFill(shadingName: COSName) = Unit

        private fun strokingRgb(): Int = runCatching {
            0xFF000000.toInt() or graphicsState.strokingColor.toRGB()
        }.getOrDefault(Color.BLACK)

        private fun emit(widthPx: Float, colorArgb: Int, alpha: Float) {
            if (discarding) return
            val marker = alpha < 0.85f
            subpaths.forEach { points ->
                if (points.isEmpty() || isTemplateArtwork(points)) return@forEach
                val chained = pendingPoints.isNotEmpty() &&
                    pendingColor == colorArgb && pendingMarker == marker &&
                    pendingPoints.last().isNear(points.first())
                if (!chained) {
                    flush()
                    pendingColor = colorArgb
                    pendingMarker = marker
                    pendingPoints += points.first()
                    pendingWidths += widthPx
                }
                for (i in 1 until points.size) {
                    pendingPoints += points[i]
                    pendingWidths += widthPx
                }
            }
        }

        private fun InkPoint.isNear(other: InkPoint): Boolean =
            hypot(x - other.x, y - other.y) <= JOIN_EPSILON

        /** Cierra el trazo en construcción: el grosor de cada tramo pasa a ser presión. */
        private fun flush() {
            if (pendingPoints.isEmpty()) return
            thinOut()
            val maxWidth = pendingWidths.maxOrNull() ?: 1f
            val stroke = if (pendingMarker) {
                Stroke(
                    points = pendingPoints.map { it.copy(pressure = 1f) },
                    width = markerBase(maxWidth),
                    colorArgb = pendingColor,
                    tool = PenTool.MARKER.name,
                )
            } else {
                // El lápiz pinta entre la mitad y una vez y media del grosor
                // base: el máximo del trazo es presión 1, y el resto en proporción.
                val base = (maxWidth / 1.5f).coerceIn(0.4f, 40f)
                Stroke(
                    points = pendingPoints.mapIndexed { i, p ->
                        p.copy(pressure = (pendingWidths[i] / base - 0.5f).coerceIn(0f, 1f))
                    },
                    width = base,
                    colorArgb = pendingColor,
                    tool = PenTool.PENCIL.name,
                )
            }
            val points = if (stroke.points.size == 1) {
                listOf(stroke.points[0], stroke.points[0].copy(x = stroke.points[0].x + 0.5f))
            } else {
                stroke.points
            }
            strokes += stroke.copy(points = points)
            pendingPoints.clear()
            pendingWidths.clear()
        }

        /**
         * Boox muestrea cada dos milisegundos: la mayoría de los puntos están
         * a menos de un píxel del anterior y solo abultan. Se dejan los que
         * se separan lo bastante, y siempre el primero y el último.
         */
        private fun thinOut() {
            if (pendingPoints.size <= 2) return
            val keptPoints = mutableListOf(pendingPoints.first())
            val keptWidths = mutableListOf(pendingWidths.first())
            for (i in 1 until pendingPoints.size - 1) {
                val p = pendingPoints[i]
                val k = keptPoints.last()
                if (hypot(p.x - k.x, p.y - k.y) >= MIN_POINT_GAP) {
                    keptPoints += p
                    keptWidths += pendingWidths[i]
                }
            }
            keptPoints += pendingPoints.last()
            keptWidths += pendingWidths.last()
            pendingPoints.clear(); pendingPoints += keptPoints
            pendingWidths.clear(); pendingWidths += keptWidths
        }

        /**
         * Rayas de la plantilla y marcos de página: una línea recta de lado a
         * lado, o un rectángulo del tamaño de la hoja. Nadie escribe eso.
         */
        private fun isTemplateArtwork(points: List<InkPoint>): Boolean {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            points.forEach {
                if (it.x < minX) minX = it.x
                if (it.x > maxX) maxX = it.x
                if (it.y < minY) minY = it.y
                if (it.y > maxY) maxY = it.y
            }
            val width = maxX - minX
            val height = maxY - minY
            val pageWidth = box.width * scale
            val pageHeight = box.height * scale
            val rulerLine = width >= pageWidth * RULE_SPAN && height <= RULE_THICKNESS
            val marginLine = height >= pageHeight * RULE_SPAN && width <= RULE_THICKNESS
            val pageFrame = width >= pageWidth * FRAME_SPAN && height >= pageHeight * FRAME_SPAN
            return rulerLine || marginLine || pageFrame
        }

        /** El grosor base del subrayador que pinta con este ancho. */
        private fun markerBase(widthPx: Float): Float {
            val fraction = ((widthPx - 45f) / (120f - 45f)).coerceIn(0f, 1f)
            return PenCanvasView.BASE_WIDTH_MIN +
                fraction * (PenCanvasView.BASE_WIDTH_MAX - PenCanvasView.BASE_WIDTH_MIN)
        }
    }

    /** Texto de una página, línea a línea, con su sitio y su cuerpo. */
    private class TextCollector(
        private val box: PDRectangle,
        private val scale: Float,
    ) : PDFTextStripper() {

        private val found = mutableListOf<InkText>()

        fun collect(doc: PDDocument, pageNumber: Int): List<InkText> {
            startPage = pageNumber
            endPage = pageNumber
            sortByPosition = true
            getText(doc)
            return found.toList()
        }

        /**
         * El texto blanco sobre blanco no se ve: es la plantilla de Boox
         * («Plantilla Boox Note Air…»), que no hay que ni enseñar ni indexar.
         */
        override fun processTextPosition(text: TextPosition) {
            val white = runCatching {
                val rgb = graphicsState.nonStrokingColor.toRGB()
                (rgb shr 16 and 0xFF) >= WHITE_FLOOR && (rgb shr 8 and 0xFF) >= WHITE_FLOOR && (rgb and 0xFF) >= WHITE_FLOOR
            }.getOrDefault(false)
            if (white) return
            super.processTextPosition(text)
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val first = textPositions.firstOrNull() ?: return
            val clean = text.trim()
            if (clean.isEmpty() || clean.startsWith(TEMPLATE_PREFIX, ignoreCase = true)) return
            val size = max(first.fontSizeInPt, 4f)
            found += InkText(
                text = clean,
                x = (first.xDirAdj - box.lowerLeftX) * scale,
                y = max(first.yDirAdj - size, 0f) * scale,
                sizePx = min(size * scale, 200f),
            )
        }
    }
}
