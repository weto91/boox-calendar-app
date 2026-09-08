package com.weto.booxcal.ink

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "NoteIndexer"

/**
 * Transcribe cada nota manuscrita entera, en segundo plano, y guarda el
 * texto para que la búsqueda la encuentre.
 *
 * Se dispara al guardar una nota y espera unos segundos de calma: mientras se
 * escribe no tiene sentido reconocer una frase a medias. Reconoce página a
 * página con el mismo modelo que usa «A texto», y suma los textos ya
 * convertidos, que no necesitan reconocimiento. El resultado sustituye la
 * transcripción anterior: es la nota completa, no un trozo. Las páginas van
 * separadas por [InkNoteEntity.PAGE_BREAK], para que al buscar se sepa en
 * cuál está la coincidencia.
 *
 * El modelo del idioma se descarga si hace falta (necesita red esa única
 * vez); sin él la nota se queda sin indexar y se intenta en el siguiente
 * guardado o al arrancar.
 *
 * Una página que es una imagen (un PDF ajeno) se pasa por el reconocedor de
 * texto en imagen, que lee lo impreso y lo manuscrito; su texto a máquina,
 * que viene aparte y exacto, se suma sin repetir las líneas que el
 * reconocedor ya dio.
 */
class NoteIndexer(
    private val repository: InkNoteRepository,
    private val recognizer: InkRecognizer,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val pending = mutableMapOf<Long, Job>()

    /** El reconocedor es uno: las notas pasan de una en una. */
    private val lock = Mutex()

    /** Texto en imagen (latino): impreso y manuscrito. */
    private val imageRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Reindexa la nota cuando lleve un rato sin cambios. */
    fun schedule(noteId: Long) {
        synchronized(pending) {
            pending[noteId]?.cancel()
            pending[noteId] = scope.launch {
                delay(SETTLE_MILLIS)
                synchronized(pending) { pending.remove(noteId) }
                runCatching { index(noteId) }
                    .onFailure { Log.w(TAG, "No se pudo indexar la nota $noteId", it) }
            }
        }
    }

    /** Al arrancar: las notas con tinta que aún no tienen transcripción. */
    fun indexMissing() {
        scope.launch {
            val notes = repository.observeAll().first()
                .filter { it.recognizedText.isNullOrBlank() }
            notes.forEach { note ->
                runCatching { index(note.id) }
                    .onFailure { Log.w(TAG, "No se pudo indexar la nota ${note.id}", it) }
            }
        }
    }

    suspend fun index(noteId: Long) = lock.withLock {
        val (note, notebook) = repository.loadNotebook(noteId) ?: return@withLock
        if (notebook.isEmpty) {
            if (note.recognizedText != null) repository.setRecognizedText(noteId, null)
            return@withLock
        }

        val tag = settings.settings.first().ocrLanguageTag
        val needsModel = notebook.pages.any { it.strokes.isNotEmpty() }
        val model = if (needsModel) recognizer.prepare(tag) else ModelState.Ready
        if (model !is ModelState.Ready) {
            Log.i(TAG, "Sin modelo de $tag: la nota $noteId se queda sin transcribir")
            return@withLock
        }

        val pages = notebook.pages.map { page ->
            val pieces = mutableListOf<String>()
            if (page.hiddenText.isNotBlank()) pieces += page.hiddenText
            page.texts.forEach { pieces += it.text }
            if (page.strokes.isNotEmpty()) {
                val inkOnly = InkDocument(page.canvasWidth, page.canvasHeight, page.strokes)
                recognizer.recognize(inkOnly).getOrNull()?.firstOrNull()?.let { pieces += it }
            }
            page.background?.let { name ->
                readImage(name)?.let { text -> pieces += withoutKnownLines(text, page.hiddenText) }
            }
            pieces.joinToString("\n").trim()
        }
        // Las páginas vacías del principio se quedan (mantienen el número de
        // página de las demás); las del final sobran.
        val text = pages.joinToString(InkNoteEntity.PAGE_BREAK.toString())
            .trimEnd()
            .ifBlank { null }
        if (text != note.recognizedText) repository.setRecognizedText(noteId, text)
    }

    /** Lo que el reconocedor de texto en imagen lee en la imagen de fondo de una página. */
    private suspend fun readImage(name: String): String? {
        val bitmap = NoteStorage.loadImage(name) ?: return null
        return try {
            imageRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim().ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la imagen $name", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Las líneas de [text] que no estén ya en [known], comparadas sin mayúsculas ni espacios de más. */
    private fun withoutKnownLines(text: String, known: String): String {
        val seen = known.lines().map { it.normalized() }.filter { it.isNotEmpty() }.toSet()
        return text.lines().filter { it.normalized().let { line -> line.isNotEmpty() && line !in seen } }.joinToString("\n")
    }

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private companion object {
        const val SETTLE_MILLIS = 4_000L
    }
}
