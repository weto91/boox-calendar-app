package com.weto.booxcal.data.repository

import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carpetas del gestor de notas: un árbol con la raíz en `parentId = null`.
 */
class NoteFolderRepository(
    db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val folders = db.noteFolderDao()
    private val notes = db.inkNoteDao()

    /** Crear la misma ruta desde dos sitios a la vez daría dos carpetas iguales. */
    private val creating = Mutex()

    fun observeAll(): Flow<List<NoteFolderEntity>> = folders.observeAll()

    suspend fun getAll(): List<NoteFolderEntity> = folders.getAll()

    suspend fun getById(id: Long): NoteFolderEntity? = folders.getById(id)

    /** La carpeta de esa ruta, creando lo que falte: `["NOTAS DEL DÍA", "2026-01-25"]`. */
    suspend fun ensurePath(path: List<String>): Long? = creating.withLock {
        var parent: Long? = null
        for (name in path) {
            val clean = name.trim()
            if (clean.isEmpty()) continue
            val existing = folders.find(parent, clean)
            parent = existing?.id ?: folders.insert(
                NoteFolderEntity(parentId = parent, name = clean, createdAt = now())
            )
        }
        parent
    }

    suspend fun create(parentId: Long?, name: String): Long? {
        val clean = name.trim().ifEmpty { return null }
        return creating.withLock {
            folders.find(parentId, clean)?.id
                ?: folders.insert(NoteFolderEntity(parentId = parentId, name = clean, createdAt = now()))
        }
    }

    /**
     * Una carpeta con notas importadas (PDF de otra app) dentro, directas o
     * en subcarpetas, es de Drive: cambiarla aquí sería mover esos PDF, y
     * esos no se tocan. No se renombra, ni se mueve, ni se borra.
     */
    suspend fun holdsImported(id: Long): Boolean = notes.countImportedInFolders(subtree(id)) > 0

    /** Renombrar cambia la ruta de todo lo que cuelga: sus notas se mueven en Drive. */
    suspend fun rename(id: Long, name: String): Boolean {
        val clean = name.trim().ifEmpty { return false }
        if (holdsImported(id)) return false
        folders.rename(id, clean)
        notes.markDriveMetaDirtyInFolders(subtree(id))
        return true
    }

    /** Mueve una carpeta; nunca dentro de sí misma ni de una hija suya. */
    suspend fun move(id: Long, parentId: Long?): Boolean {
        if (parentId != null && (parentId == id || isDescendant(parentId, id))) return false
        if (holdsImported(id)) return false
        folders.move(id, parentId)
        notes.markDriveMetaDirtyInFolders(subtree(id))
        return true
    }

    /**
     * Borra la carpeta: sus notas y subcarpetas suben a la carpeta padre. No
     * se borra ninguna nota al borrar una carpeta.
     */
    suspend fun delete(id: Long): Boolean {
        val folder = folders.getById(id) ?: return false
        if (holdsImported(id)) return false
        val affected = subtree(id)
        val all = folders.getAll()
        all.filter { it.parentId == id }.forEach { folders.move(it.id, folder.parentId) }
        notes.moveAll(id, folder.parentId)
        folders.delete(id)
        notes.markDriveMetaDirtyInFolders(affected + listOfNotNull(folder.parentId))
        return true
    }

    /**
     * Borra la carpeta solo si no tiene nada: ni notas ni subcarpetas. No
     * marca nada para Drive, que no hay nada que mover. Devuelve si se borró.
     */
    suspend fun deleteIfEmpty(id: Long): Boolean {
        if (notes.countInFolder(id) > 0) return false
        if (folders.getAll().any { it.parentId == id }) return false
        folders.delete(id)
        return true
    }

    /** La carpeta y todas las que cuelgan de ella. */
    private suspend fun subtree(id: Long): List<Long> {
        val childrenOf = folders.getAll().groupBy { it.parentId }
        val out = mutableListOf<Long>()
        val queue = ArrayDeque<Long>()
        queue += id
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            out += current
            childrenOf[current].orEmpty().forEach { queue += it.id }
        }
        return out
    }

    /** Ruta legible de una carpeta: «NOTAS DEL DÍA / 2026-01-25». */
    suspend fun pathOf(id: Long?): List<NoteFolderEntity> {
        val byId = folders.getAll().associateBy { it.id }
        return pathOf(id, byId)
    }

    fun pathOf(id: Long?, byId: Map<Long, NoteFolderEntity>): List<NoteFolderEntity> {
        val chain = mutableListOf<NoteFolderEntity>()
        var cursor = id
        var guard = 0
        while (cursor != null && guard++ < 64) {
            val folder = byId[cursor] ?: break
            chain += folder
            cursor = folder.parentId
        }
        return chain.reversed()
    }

    private suspend fun isDescendant(candidate: Long, ancestor: Long): Boolean {
        val byId = folders.getAll().associateBy { it.id }
        var cursor: Long? = candidate
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (cursor == ancestor) return true
            cursor = byId[cursor]?.parentId
        }
        return false
    }
}
