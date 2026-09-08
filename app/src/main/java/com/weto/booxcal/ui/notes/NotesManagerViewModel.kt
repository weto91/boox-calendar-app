package com.weto.booxcal.ui.notes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import com.weto.booxcal.data.remote.google.GoogleAuthManager
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.NoteFolderRepository
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.NoteDriveSync
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una carpeta tal y como se pinta en el árbol: con su profundidad y sus cuentas. */
data class FolderNode(
    val folder: NoteFolderEntity,
    val depth: Int,
    val hasChildren: Boolean,
    val noteCount: Int,
)

/** Lo que se está viendo: todo, la raíz, o una carpeta. */
sealed class FolderScope {
    data object All : FolderScope()
    data object Root : FolderScope()
    data class Folder(val id: Long) : FolderScope()
}

private data class Filters(
    val scope: FolderScope = FolderScope.All,
    val expanded: Set<Long> = emptySet(),
    val tag: String? = null,
    val query: String = "",
    val message: String? = null,
    val importProgress: ImportProgress? = null,
)

/** Una importación en marcha: qué archivo, cuál de cuántos, qué se hace y cuánto va. */
data class ImportProgress(
    val fileName: String,
    val index: Int,
    val count: Int,
    val label: String,
    /** De 0 a 1, del archivo en curso. */
    val fraction: Float,
) {
    /** De 0 a 1, del lote entero. */
    val overall: Float get() = ((index - 1) + fraction) / count.coerceAtLeast(1)
}

data class NotesManagerState(
    val tree: List<FolderNode> = emptyList(),
    val folders: List<NoteFolderEntity> = emptyList(),
    val scope: FolderScope = FolderScope.All,
    val expanded: Set<Long> = emptySet(),
    val notes: List<InkNoteEntity> = emptyList(),
    val totalNotes: Int = 0,
    val rootCount: Int = 0,
    /** Todas las etiquetas en uso, con cuántas notas llevan cada una. */
    val tags: List<Pair<String, Int>> = emptyList(),
    val tag: String? = null,
    val query: String = "",
    /** Carpetas con notas importadas dentro (directas o en subcarpetas): se gestionan desde Drive. */
    val lockedFolders: Set<Long> = emptySet(),
    val syncing: Boolean = false,
    val driveConfigured: Boolean = false,
    val driveError: String? = null,
    val driveSyncAt: Long = 0,
    val message: String? = null,
    /** Importación de PDF en marcha; null si no hay ninguna. */
    val importProgress: ImportProgress? = null,
    val loading: Boolean = true,
) {
    val selectedFolderId: Long? get() = (scope as? FolderScope.Folder)?.id
    val selectedFolder: NoteFolderEntity? get() = selectedFolderId?.let { id -> folders.firstOrNull { it.id == id } }

    /** «NOTAS DEL DÍA / 2026-01-25» de la carpeta elegida; vacío en la raíz. */
    val selectedPath: List<NoteFolderEntity>
        get() {
            val byId = folders.associateBy { it.id }
            val chain = mutableListOf<NoteFolderEntity>()
            var cursor = selectedFolderId
            var guard = 0
            while (cursor != null && guard++ < 64) {
                val folder = byId[cursor] ?: break
                chain += folder
                cursor = folder.parentId
            }
            return chain.reversed()
        }

    fun pathOf(folderId: Long?): String {
        val byId = folders.associateBy { it.id }
        val chain = mutableListOf<String>()
        var cursor = folderId
        var guard = 0
        while (cursor != null && guard++ < 64) {
            val folder = byId[cursor] ?: break
            chain += folder.name
            cursor = folder.parentId
        }
        return chain.reversed().joinToString(" / ")
    }
}

class NotesManagerViewModel(
    private val notes: InkNoteRepository = Graph.inkNoteRepository,
    private val folders: NoteFolderRepository = Graph.noteFolderRepository,
    private val settings: SettingsStore = Graph.settings,
    private val scheduler: SyncScheduler = Graph.syncScheduler,
    private val auth: GoogleAuthManager = Graph.googleAuth,
    private val driveSync: NoteDriveSync = Graph.noteDriveSync,
) : ViewModel() {

    private val filters = MutableStateFlow(Filters())

    val state: StateFlow<NotesManagerState> = combine(
        folders.observeAll(),
        notes.observeAll(),
        settings.settings,
        scheduler.observeNotesRunning(),
        filters,
    ) { allFolders, allNotes, prefs, syncing, f ->
        build(allFolders, allNotes, prefs, syncing, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesManagerState())

    private fun build(
        allFolders: List<NoteFolderEntity>,
        allNotes: List<InkNoteEntity>,
        prefs: AppSettings,
        syncing: Boolean,
        f: Filters,
    ): NotesManagerState {
        // Solo las notas del gestor: las que cuelgan de un evento o una tarea no.
        val managed = allNotes.filter { it.belongsToManager }
        val countByFolder = managed.groupingBy { it.folderId }.eachCount()
        val childrenOf = allFolders.groupBy { it.parentId }

        // El árbol, con las carpetas de fecha de más reciente a más antigua y
        // el resto por nombre.
        val tree = mutableListOf<FolderNode>()
        fun visit(parentId: Long?, depth: Int) {
            val children = childrenOf[parentId].orEmpty().sortedWith(
                compareBy<NoteFolderEntity> { !it.name.matches(DATE_NAME) }
                    .thenByDescending { if (it.name.matches(DATE_NAME)) it.name else "" }
                    .thenBy { it.name.lowercase() }
            )
            children.forEach { folder ->
                val kids = childrenOf[folder.id].orEmpty()
                tree += FolderNode(folder, depth, kids.isNotEmpty(), countByFolder[folder.id] ?: 0)
                if (folder.id in f.expanded) visit(folder.id, depth + 1)
            }
        }
        visit(null, 0)

        val inScope = when (val scope = f.scope) {
            FolderScope.All -> managed
            FolderScope.Root -> managed.filter { it.folderId == null }
            is FolderScope.Folder -> managed.filter { it.folderId == scope.id }
        }
        val query = f.query.trim().lowercase()
        val visible = inScope
            .filter { note -> f.tag == null || note.tagList.any { it.equals(f.tag, ignoreCase = true) } }
            .filter { note ->
                query.isEmpty() ||
                    note.title?.lowercase()?.contains(query) == true ||
                    note.recognizedText?.lowercase()?.contains(query) == true ||
                    note.tagList.any { it.lowercase().contains(query) }
            }
            .sortedByDescending { it.updatedAt }

        // Una carpeta con notas importadas, y todas las de encima, son de Drive.
        val byId = allFolders.associateBy { it.id }
        val locked = mutableSetOf<Long>()
        managed.filter { it.isImported }.forEach { note ->
            var cursor = note.folderId
            var guard = 0
            while (cursor != null && guard++ < 64 && locked.add(cursor)) cursor = byId[cursor]?.parentId
        }

        val tags = managed.flatMap { it.tagList }
            .groupingBy { it }.eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

        return NotesManagerState(
            tree = tree,
            folders = allFolders,
            scope = f.scope,
            expanded = f.expanded,
            notes = visible,
            totalNotes = managed.size,
            rootCount = countByFolder[null] ?: 0,
            tags = tags,
            tag = f.tag,
            query = f.query,
            lockedFolders = locked,
            syncing = syncing,
            driveConfigured = prefs.driveNotesFolderId != null && auth.isAuthorized,
            driveError = prefs.driveNotesError,
            driveSyncAt = prefs.driveNotesSyncAt,
            message = f.message,
            importProgress = f.importProgress,
            loading = false,
        )
    }

    // --- Navegación por el árbol -------------------------------------------

    fun select(scope: FolderScope) {
        filters.value = filters.value.copy(scope = scope)
        // Elegir una carpeta la abre, para ver lo que cuelga de ella.
        (scope as? FolderScope.Folder)?.let { expand(it.id, true) }
    }

    fun toggleExpanded(folderId: Long) {
        val expanded = filters.value.expanded
        expand(folderId, folderId !in expanded)
    }

    private fun expand(folderId: Long, open: Boolean) {
        val expanded = filters.value.expanded
        filters.value = filters.value.copy(expanded = if (open) expanded + folderId else expanded - folderId)
    }

    /**
     * Viene de la búsqueda: se va a la carpeta de la nota, con todo el camino
     * hasta ella desplegado y sin filtros que la escondan, y se devuelve la
     * nota para abrirla. Null si ya no existe.
     */
    suspend fun reveal(noteId: Long): InkNoteEntity? {
        val note = notes.getById(noteId) ?: return null
        val byId = folders.getAll().associateBy { it.id }
        val chain = mutableSetOf<Long>()
        var cursor = note.folderId
        var guard = 0
        while (cursor != null && guard++ < 64 && chain.add(cursor)) cursor = byId[cursor]?.parentId
        filters.value = filters.value.copy(
            scope = note.folderId?.let { FolderScope.Folder(it) } ?: FolderScope.Root,
            expanded = filters.value.expanded + chain,
            tag = null,
            query = "",
        )
        return note
    }

    fun setTag(tag: String?) {
        filters.value = filters.value.copy(tag = if (filters.value.tag == tag) null else tag)
    }

    fun setQuery(query: String) {
        filters.value = filters.value.copy(query = query)
    }

    // --- Carpetas -----------------------------------------------------------

    fun createFolder(name: String) {
        val parent = state.value.selectedFolderId
        viewModelScope.launch {
            val id = folders.create(parent, name) ?: return@launch
            if (parent != null) expand(parent, true)
            select(FolderScope.Folder(id))
        }
    }

    fun renameFolder(id: Long, name: String) {
        viewModelScope.launch {
            if (!folders.rename(id, name)) notify(IMPORTED_FOLDER)
        }
    }

    /** Borra la carpeta; sus notas y subcarpetas suben a la de encima. */
    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            val folder = folders.getById(id)
            if (!folders.delete(id)) {
                notify(IMPORTED_FOLDER)
                return@launch
            }
            select(folder?.parentId?.let { FolderScope.Folder(it) } ?: FolderScope.Root)
            notify("Carpeta borrada; lo que tenía subió a la de encima")
        }
    }

    fun moveFolder(id: Long, parentId: Long?) {
        viewModelScope.launch {
            if (!folders.move(id, parentId)) {
                notify(if (folders.holdsImported(id)) IMPORTED_FOLDER else "Una carpeta no puede ir dentro de sí misma")
            }
        }
    }

    // --- Notas --------------------------------------------------------------

    fun moveNote(id: Long, folderId: Long?) {
        viewModelScope.launch { notes.setFolder(id, folderId) }
    }

    fun setTags(id: Long, tags: List<String>) {
        viewModelScope.launch { notes.setTags(id, tags) }
    }

    fun renameNote(id: Long, title: String) {
        viewModelScope.launch { notes.setTitle(id, title) }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            notes.delete(id)
            scheduler.syncNotesSoon()
        }
    }

    fun syncNow() {
        if (!state.value.driveConfigured) {
            notify("Activa Google Drive para las notas en Ajustes")
            return
        }
        scheduler.syncNotesNow()
    }

    /**
     * PDF elegidos con el selector de archivos (el Drive del usuario, la
     * memoria de la tablet…): cada uno entra como nota importada en la
     * carpeta abierta, y se sube a la carpeta de la app en Drive.
     */
    fun importPdfs(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!state.value.driveConfigured) {
            notify("Activa Google Drive para las notas en Ajustes: los PDF importados se guardan ahí")
            return
        }
        val folderId = state.value.selectedFolderId
        val resolver = context.applicationContext.contentResolver
        if (filters.value.importProgress != null) {
            notify("Ya hay una importación en marcha")
            return
        }
        viewModelScope.launch {
            var done = 0
            var failure: String? = null
            uris.forEachIndexed { index, uri ->
                fun progress(name: String, label: String, fraction: Float) {
                    filters.value = filters.value.copy(
                        importProgress = ImportProgress(name, index + 1, uris.size, label, fraction.coerceIn(0f, 1f)),
                    )
                }
                progress("PDF", "Leyendo el archivo…", 0f)
                val (name, bytes) = withContext(Dispatchers.IO) {
                    runCatching {
                        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                        val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("No se pudo leer el archivo")
                        (display ?: "Importado.pdf") to data
                    }
                }.getOrElse { e ->
                    failure = e.message ?: "No se pudo leer el archivo"
                    return@forEachIndexed
                }
                driveSync.importPdf(bytes, name, folderId) { label, fraction -> progress(name, label, fraction) }
                    .onSuccess { done++ }
                    .onFailure { e -> failure = e.message ?: "No se pudo importar $name" }
            }
            filters.value = filters.value.copy(importProgress = null)
            notify(
                when {
                    done == uris.size && done == 1 -> "PDF importado"
                    done == uris.size -> "$done PDF importados"
                    done == 0 -> "No se pudo importar: $failure"
                    else -> "$done de ${uris.size} importados. Último fallo: $failure"
                }
            )
        }
    }

    fun notify(text: String) {
        filters.value = filters.value.copy(message = text)
    }

    fun clearMessage() {
        filters.value = filters.value.copy(message = null)
    }

    private companion object {
        val DATE_NAME = Regex("\\d{4}-\\d{2}-\\d{2}")
        const val IMPORTED_FOLDER = "Esta carpeta tiene notas importadas: se cambia desde Google Drive"
    }
}
