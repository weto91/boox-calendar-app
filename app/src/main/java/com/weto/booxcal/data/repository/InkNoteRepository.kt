package com.weto.booxcal.data.repository

import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.DrivePendingDeleteEntity
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ink.InkNotebook
import com.weto.booxcal.ink.NoteStorage
import com.weto.booxcal.ink.StrokeCodec
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class InkNoteRepository(
    db: AppDatabase,
    private val folders: NoteFolderRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.inkNoteDao()
    private val pendingDeletes = db.drivePendingDeleteDao()

    /** Avisa de cada nota guardada: es lo que dispara la transcripción de fondo. */
    var onSaved: ((Long) -> Unit)? = null

    fun observeById(id: Long): Flow<InkNoteEntity?> = dao.observeById(id)

    fun observeAnchoredDays(): Flow<Set<Long>> =
        dao.observeAnchoredDays().map { it.toSet() }

    fun observeForDay(dayMillis: Long): Flow<List<InkNoteEntity>> = dao.observeForDay(dayMillis)

    fun observeAll(): Flow<List<InkNoteEntity>> = dao.observeAll()

    suspend fun getAll(): List<InkNoteEntity> = dao.getAll()

    suspend fun getById(id: Long): InkNoteEntity? = dao.getById(id)

    suspend fun getByDriveFileId(fileId: String): InkNoteEntity? = dao.getByDriveFileId(fileId)

    suspend fun search(query: String): List<InkNoteEntity> = dao.search(query)

    suspend fun load(id: Long): Pair<InkNoteEntity, InkDocument>? {
        val note = dao.getById(id) ?: return null
        return note to StrokeCodec.decode(note.strokesJson)
    }

    suspend fun loadNotebook(id: Long): Pair<InkNoteEntity, InkNotebook>? {
        val note = dao.getById(id) ?: return null
        return note to StrokeCodec.decodeNotebook(note.strokesJson)
    }

    suspend fun save(
        id: Long?,
        document: InkDocument,
        recognizedText: String?,
        anchorDayMillis: Long?,
    ): Long = saveNotebook(id, InkNotebook(listOf(document)), recognizedText, anchorDayMillis)

    /**
     * Guarda el cuaderno. Una nota anclada a un día va sola a la carpeta
     * «NOTAS DEL DÍA / fecha» y lleva la etiqueta «Nota del día», salvo que
     * ya la hubieran colocado a mano en otra carpeta.
     */
    suspend fun saveNotebook(
        id: Long?,
        notebook: InkNotebook,
        recognizedText: String?,
        anchorDayMillis: Long?,
    ): Long {
        val timestamp = now()
        val existing = id?.let { dao.getById(it) }
        // Una nota importada no se escribe: de ella solo se guarda el texto
        // reconocido, que sirve para buscarla y no toca el PDF de Drive.
        if (existing != null && existing.isImported) {
            if (recognizedText != existing.recognizedText) dao.setRecognizedText(existing.id, recognizedText)
            return existing.id
        }
        val payload = NoteStorage.persist(existing?.strokesJson, StrokeCodec.encodeNotebook(notebook))
        val savedId = if (existing == null) {
            val folderId = anchorDayMillis?.let { dayFolder(it) }
            val tags = if (anchorDayMillis != null) InkNoteEntity.joinTags(listOf(InkNoteEntity.TAG_DAY_NOTE)) else ""
            dao.insert(
                InkNoteEntity(
                    strokesJson = payload,
                    recognizedText = recognizedText,
                    anchorDayMillis = anchorDayMillis,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    folderId = folderId,
                    tags = tags,
                )
            )
        } else {
            val folderId = existing.folderId ?: anchorDayMillis?.let { dayFolder(it) }
            dao.update(
                existing.copy(
                    strokesJson = payload,
                    recognizedText = recognizedText,
                    anchorDayMillis = anchorDayMillis,
                    updatedAt = timestamp,
                    folderId = folderId,
                )
            )
            existing.id
        }
        onSaved?.invoke(savedId)
        return savedId
    }

    /** Una nota que llega de Drive: se guarda con su archivo, en la raíz y etiquetada «importado». */
    suspend fun importFromDrive(
        existingId: Long?,
        notebook: InkNotebook,
        title: String?,
        fileId: String,
        parentId: String?,
        name: String,
        modifiedAt: Long,
    ): Long {
        val timestamp = now()
        val existing = existingId?.let { dao.getById(it) }
        // Las imágenes de fondo de la versión anterior que ya no se usan.
        val keep = notebook.pages.mapNotNull { it.background }.toSet()
        NoteStorage.backgroundsOf(existing?.strokesJson).filter { it !in keep }.forEach { NoteStorage.deleteImage(it) }
        val payload = NoteStorage.persist(existing?.strokesJson, StrokeCodec.encodeNotebook(notebook))
        val savedId = if (existing == null) {
            dao.insert(
                InkNoteEntity(
                    strokesJson = payload,
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    tags = InkNoteEntity.joinTags(listOf(InkNoteEntity.TAG_IMPORTED)),
                    driveFileId = fileId,
                    driveParentId = parentId,
                    driveName = name,
                    driveModifiedAt = modifiedAt,
                    driveSyncedAt = timestamp,
                    driveOwned = false,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    strokesJson = payload,
                    updatedAt = timestamp,
                    driveParentId = parentId,
                    driveName = name,
                    driveModifiedAt = modifiedAt,
                    driveSyncedAt = timestamp,
                )
            )
            existing.id
        }
        onSaved?.invoke(savedId)
        return savedId
    }

    suspend fun setDrive(
        id: Long,
        fileId: String?,
        parentId: String?,
        name: String?,
        modifiedAt: Long?,
        syncedAt: Long?,
        owned: Boolean,
    ) = dao.setDrive(id, fileId, parentId, name, modifiedAt, syncedAt, owned)

    suspend fun setRecognizedText(id: Long, text: String?) = dao.setRecognizedText(id, text)

    /**
     * Título a mano. En blanco lo quita. No toca `updatedAt`: lo escrito no
     * cambia y no hay que volver a subir el PDF entero; solo queda pendiente
     * llevar el nombre a Drive.
     */
    suspend fun setTitle(id: Long, title: String?) {
        val existing = dao.getById(id) ?: return
        if (existing.isImported) return
        val clean = title?.trim()?.ifBlank { null }
        dao.update(existing.copy(title = clean, driveMetaDirty = existing.driveMetaDirty || existing.driveFileId != null))
    }

    /** Mover de carpeta. Igual que el título: solo queda pendiente mover el PDF en Drive. */
    suspend fun setFolder(id: Long, folderId: Long?) {
        val existing = dao.getById(id) ?: return
        if (existing.isImported) return
        dao.update(existing.copy(folderId = folderId, driveMetaDirty = existing.driveMetaDirty || existing.driveFileId != null))
    }

    /** Colocación que viene de Drive: nunca es un cambio local. */
    suspend fun placeFromDrive(id: Long, folderId: Long?) = dao.setFolder(id, folderId)

    suspend fun setDriveMetaDirty(id: Long, dirty: Boolean) = dao.setDriveMetaDirty(id, dirty)

    suspend fun resetImports() = dao.resetImports()

    /** Etiquetas. Una nota importada lleva siempre «importado», se ponga lo que se ponga. */
    suspend fun setTags(id: Long, tags: Collection<String>) {
        val existing = dao.getById(id) ?: return
        val kept = if (existing.isImported) listOf(InkNoteEntity.TAG_IMPORTED) + tags else tags
        dao.setTags(id, InkNoteEntity.joinTags(kept))
    }

    /**
     * Borra la nota. Si tiene archivo en Drive, se apunta para mandarlo a la
     * papelera en la siguiente pasada (de la papelera se puede recuperar).
     */
    suspend fun delete(id: Long) {
        val note = dao.getById(id)
        // Una nota importada solo se va si se va su PDF de Drive.
        if (note != null && note.isImported) return
        if (note?.driveFileId != null) {
            pendingDeletes.insert(DrivePendingDeleteEntity(note.driveFileId, now()))
        }
        NoteStorage.deleteNotebookFiles(note?.strokesJson)
        dao.delete(id)
    }

    /** Borrado que viene de Drive: la nota se va de aquí y en Drive no se toca nada. */
    suspend fun deleteLocalOnly(id: Long) {
        NoteStorage.deleteNotebookFiles(dao.getById(id)?.strokesJson)
        dao.delete(id)
    }

    suspend fun deleteOrphans(): Int = dao.deleteOrphans()

    /** Las notas del día de antes del gestor: a su carpeta y con su etiqueta. */
    suspend fun backfillDayNotes() {
        dao.getAll()
            .filter { it.anchorDayMillis != null && (it.folderId == null || !it.tagList.contains(InkNoteEntity.TAG_DAY_NOTE)) }
            .forEach { note ->
                val folderId = note.folderId ?: dayFolder(note.anchorDayMillis!!)
                val tags = if (note.tagList.contains(InkNoteEntity.TAG_DAY_NOTE)) note.tags
                else InkNoteEntity.joinTags(note.tagList + InkNoteEntity.TAG_DAY_NOTE)
                dao.update(note.copy(folderId = folderId, tags = tags))
            }
    }

    private suspend fun dayFolder(dayMillis: Long): Long? {
        val date = LocalDate.ofEpochDay(Math.floorDiv(dayMillis, MILLIS_PER_DAY))
        return folders.ensurePath(listOf(NoteFolderEntity.DAY_NOTES_ROOT, date.toString()))
    }
}
