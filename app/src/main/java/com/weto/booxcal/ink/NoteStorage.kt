package com.weto.booxcal.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/**
 * Dónde viven los trazos de una nota grande.
 *
 * Android no deja leer de SQLite una fila de más de 2 MB (el límite del
 * `CursorWindow`), y una nota de Boox con miles de puntos pasa de eso con
 * holgura: al leerla, la app se caía nada más abrir. Los cuadernos que se
 * acercan al límite se guardan en un fichero y la fila guarda solo una
 * referencia `@file:nombre`; el códec la resuelve al leer, así que el resto
 * de la app no distingue unos de otros.
 */
object NoteStorage {

    private const val PREFIX = "@file:"

    /**
     * Por encima de esto el cuaderno va a fichero. Bajo a propósito: las
     * listas (el cuaderno, la búsqueda, la sincronización) cargan las filas
     * enteras, y con miles de notas cada kilobyte de más en la fila se paga
     * en memoria. Una nota escrita a mano normal pasa de aquí y vive en su
     * fichero, que solo se lee al abrirla o al pintar su miniatura.
     */
    const val INLINE_LIMIT = 32_000

    @Volatile
    var dir: File? = null

    fun isReference(payload: String?): Boolean = payload?.startsWith(PREFIX) == true

    /** El JSON del cuaderno, esté en la fila o en su fichero. */
    fun resolve(payload: String?): String? {
        if (payload == null || !payload.startsWith(PREFIX)) return payload
        val file = fileFor(payload) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * Lo que hay que guardar en la fila para este cuaderno: el JSON tal cual
     * si es pequeño, o la referencia a su fichero. Reutiliza el fichero de la
     * versión anterior si lo había, y lo borra si la nota vuelve a ser pequeña.
     */
    fun persist(previous: String?, payload: String): String {
        val folder = dir
        if (payload.length <= INLINE_LIMIT || folder == null) {
            if (isReference(previous)) fileFor(previous)?.delete()
            return payload
        }
        val name = if (isReference(previous)) previous!!.removePrefix(PREFIX) else "note-${UUID.randomUUID()}.json"
        folder.mkdirs()
        val file = File(folder, name)
        val temp = File(folder, "$name.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
        return PREFIX + name
    }

    fun delete(payload: String?) {
        if (isReference(payload)) fileFor(payload)?.delete()
    }

    /**
     * Borra todo lo que cuelga de un cuaderno guardado: su fichero, si lo
     * tiene, y las imágenes de fondo de sus páginas, salvo las de [keep]
     * (las que el cuaderno nuevo sigue usando).
     */
    fun deleteNotebookFiles(payload: String?, keep: Set<String> = emptySet()) {
        backgroundsOf(payload).filter { it !in keep }.forEach { deleteImage(it) }
        delete(payload)
    }

    /** Nombres de las imágenes de fondo de un cuaderno guardado. */
    fun backgroundsOf(payload: String?): Set<String> {
        if (payload.isNullOrBlank()) return emptySet()
        return runCatching { StrokeCodec.decodeNotebook(payload).pages.mapNotNull { it.background }.toSet() }
            .getOrDefault(emptySet())
    }

    // --- Imágenes de fondo ---------------------------------------------------

    /** Guarda una imagen de fondo y devuelve su nombre; null si no hay carpeta. */
    fun saveImage(bitmap: Bitmap): String? {
        val folder = dir ?: return null
        folder.mkdirs()
        val name = "bg-${UUID.randomUUID()}.png"
        val file = File(folder, name)
        return runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name
        }.getOrElse { file.delete(); null }
    }

    /** Carga una imagen de fondo, reducida [sampleSize] veces (potencia de dos). */
    fun loadImage(name: String, sampleSize: Int = 1): Bitmap? {
        val file = imageFile(name)?.takeIf { it.exists() } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
    }

    fun deleteImage(name: String) {
        imageFile(name)?.delete()
    }

    /** La imagen de fondo existe en este dispositivo (en otro habrá que sacarla del PDF). */
    fun hasImage(name: String): Boolean = imageFile(name)?.exists() == true

    private fun imageFile(name: String): File? {
        val folder = dir ?: return null
        if (name.isEmpty() || name.contains('/')) return null
        return File(folder, name)
    }

    fun reference(name: String): String = PREFIX + name

    private fun fileFor(payload: String?): File? {
        val folder = dir ?: return null
        val name = payload?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return null
        return File(folder, name)
    }
}
