package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trazos manuscritos. El OCR no destruye la nota: `strokesJson` conserva
 * siempre los puntos originales y `recognizedText` es solo la propuesta.
 *
 * Desde la v3 la nota vive en una carpeta del gestor, lleva etiquetas y
 * puede tener su copia en Google Drive como PDF vectorial.
 */
@Entity(
    tableName = "ink_notes",
    indices = [Index("anchorDayMillis"), Index("folderId"), Index("driveFileId")],
)
data class InkNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.weto.booxcal.ink.StrokeCodec] define el formato. */
    val strokesJson: String,
    val recognizedText: String? = null,
    /** Día al que se ancla la nota (medianoche UTC), para el memo de calendario. */
    val anchorDayMillis: Long? = null,
    /** Título puesto a mano desde la lista de notas del día. Null si no tiene. */
    val title: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Carpeta del gestor. Null: la raíz. */
    val folderId: Long? = null,
    /** Etiquetas separadas por [TAG_SEPARATOR]. Ver [tagList]. */
    val tags: String = "",
    /** Archivo en Drive, si la nota tiene copia allí. */
    val driveFileId: String? = null,
    /** Carpeta de Drive donde está el archivo. */
    val driveParentId: String? = null,
    /** Nombre del archivo en Drive (con .pdf). */
    val driveName: String? = null,
    /** `modifiedTime` de Drive la última vez que se sincronizó. */
    val driveModifiedAt: Long? = null,
    /** `updatedAt` local que se subió (o bajó) la última vez. */
    val driveSyncedAt: Long? = null,
    /**
     * True si el archivo lo creó esta app (va en el árbol espejo y se puede
     * mover y borrar); false si vino de otra app y hay que dejarlo donde está.
     */
    val driveOwned: Boolean = false,
    /** Nombre o carpeta cambiados aquí y aún no llevados a Drive. */
    val driveMetaDirty: Boolean = false,
) {
    val tagList: List<String> get() = splitTags(tags)

    /**
     * Vino de otra app por Drive. Es de solo lectura: ni se escribe en ella,
     * ni se mueve, ni se renombra, ni se borra desde aquí; su PDF es de la
     * app que lo creó, que no se entera de lo que cambie esta. Solo se le
     * pueden poner etiquetas (aparte de «importado», que no se quita) y se
     * indexa su texto para poder buscarla.
     */
    val isImported: Boolean get() = driveFileId != null && !driveOwned

    /** Del cuaderno (carpeta, día, Drive o etiquetas); las pegadas a un evento o tarea, no. */
    val belongsToManager: Boolean
        get() = folderId != null || anchorDayMillis != null || driveFileId != null || tags.isNotEmpty()

    /** Texto reconocido para enseñar: sin las marcas de página. */
    val recognizedFlat: String? get() = recognizedText?.replace(PAGE_BREAK, '\n')

    /**
     * Página (desde 0) en la que está la primera coincidencia con [query].
     * El indexador separa las páginas con [PAGE_BREAK]; se cuentan las que
     * hay antes del hallazgo. Sin coincidencia, la primera.
     */
    fun pageOfMatch(query: String): Int {
        val text = recognizedText ?: return 0
        val clean = query.trim()
        if (clean.isEmpty()) return 0
        val at = text.indexOf(clean, ignoreCase = true)
        if (at < 0) return 0
        return text.substring(0, at).count { it == PAGE_BREAK }
    }

    /** Cambios de contenido que Drive aún no tiene. */
    val driveDirty: Boolean get() = driveFileId == null || (driveSyncedAt ?: 0L) < updatedAt

    companion object {
        const val TAG_SEPARATOR = "|"
        const val TAG_DAY_NOTE = "Nota del día"
        const val TAG_IMPORTED = "importado"

        /** Separa las páginas dentro de `recognizedText`: así la búsqueda sabe en cuál está lo hallado. */
        const val PAGE_BREAK = '\u000C'

        fun joinTags(tags: Collection<String>): String =
            tags.map { it.replace(TAG_SEPARATOR, " ").trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString(TAG_SEPARATOR)

        fun splitTags(raw: String): List<String> =
            raw.split(TAG_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
