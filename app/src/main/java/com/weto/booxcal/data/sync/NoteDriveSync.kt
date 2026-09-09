package com.weto.booxcal.data.sync

import android.util.Log
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import com.weto.booxcal.data.remote.google.DriveException
import com.weto.booxcal.data.remote.google.GDriveFile
import com.weto.booxcal.data.remote.google.GoogleAuthManager
import com.weto.booxcal.data.remote.google.GoogleDriveClient
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.NoteFolderRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.ink.NotePdf
import com.weto.booxcal.ink.StrokeCodec
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.weto.booxcal.R
import com.weto.booxcal.di.Graph
import androidx.annotation.StringRes

private const val TAG = "NoteDriveSync"

/**
 * Las notas y Google Drive, en los dos sentidos, como PDF vectorial.
 *
 * Se trabaja dentro de la carpeta de Drive elegida en Ajustes:
 *
 *  - **Carpetas**: la estructura es la misma a los dos lados, pero solo la que
 *    tiene notas. Cada carpeta de Drive con algún PDF dentro (directo o en
 *    subcarpetas) existe aquí con su ruta (`Carpeta_drive/PERSONAL` es
 *    `Raíz/PERSONAL`); las vacías y las auxiliares que Boox deja junto a cada
 *    PDF (solo un HTML de propiedades) no se enseñan, y si alguna se coló
 *    aquí, se quita. Al subir, la carpeta de Drive se crea cuando hace falta
 *    para colocar un PDF, nunca vacía.
 *  - **Bajar**: cada PDF nuevo de esa carpeta (y de sus subcarpetas) se
 *    convierte en una nota, en la carpeta local de su misma ruta. Llevan la
 *    etiqueta «importado». Un PDF que cambia en Drive vuelve a bajar, salvo que la
 *    nota haya cambiado aquí más tarde: gana el cambio más reciente.
 *  - **Subir**: cada nota del gestor sin archivo se crea en Drive en la
 *    carpeta espejo de la suya (`<carpeta elegida>/NOTAS DEL DÍA/2026-01-25`).
 *    Una nota que cambia aquí sustituye el contenido de su PDF, y moverla de
 *    carpeta o renombrarla hace lo mismo con el PDF. Solo con las notas de
 *    esta app: las importadas son de solo lectura y su PDF no se toca nunca
 *    (la app que lo creó no se enteraría de los cambios).
 *  - **Borrar**: en los dos sentidos. Una nota de esta app borrada aquí manda
 *    su PDF a la papelera de Drive; un PDF borrado en Drive borra la nota de
 *    aquí, sea de quien sea.
 */
class NoteDriveSync(
    private val drive: GoogleDriveClient,
    private val auth: GoogleAuthManager,
    private val notes: InkNoteRepository,
    private val folders: NoteFolderRepository,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(
        val downloaded: Int = 0,
        val uploaded: Int = 0,
        val skipped: Boolean = false,
        val error: String? = null,
    )

    private val running = Mutex()

    suspend fun sync(): Outcome = running.withLock {
        val prefs = settings.settings.first()
        val rootId = prefs.driveNotesFolderId ?: return@withLock Outcome(skipped = true)
        if (!auth.isAuthorized) return@withLock Outcome(skipped = true)

        try {
            var downloaded = 0
            var uploaded = 0
            flushDeletes()
            // El lector de PDF ajenos cambió: lo ya bajado se vuelve a bajar
            // una vez, para que salga con lo que ahora se sabe leer.
            if (prefs.driveImportVersion < NotePdf.IMPORT_VERSION) {
                notes.resetImports()
                settings.setDriveImportVersion(NotePdf.IMPORT_VERSION)
            }
            val remote = walk(rootId)
            mirrorFolders(remote)
            downloaded += pull(remote)
            uploaded += push(rootId, remote)
            settings.recordDriveNotesSuccess(now())
            Outcome(downloaded = downloaded, uploaded = uploaded)
        } catch (e: DriveException) {
            val message = describe(e)
            Log.w(TAG, "Drive: $message", e)
            settings.recordDriveNotesFailure(message)
            Outcome(error = message)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Sincronización de notas fallida", e)
            settings.recordDriveNotesFailure(message)
            Outcome(error = message)
        }
    }

    private fun describe(e: DriveException): String = when {
        e.missingScope -> text(R.string.drive_missing_scope)
        // Con `drive.file` una carpeta que no creó la app no existe para ella:
        // pasa al reconectar la cuenta con una carpeta elegida a mano antes.
        e.code == 404 -> text(R.string.drive_folder_gone)
        else -> e.message ?: text(R.string.drive_responded, e.code)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = Graph.appContext.getString(id, *args)

    // --- Importar ---------------------------------------------------------------

    /**
     * Un PDF elegido a mano (con el selector de archivos: el Drive del
     * usuario, la memoria de la tablet, lo que sea) entra como nota en la
     * carpeta dada. Se sube antes a la carpeta espejo de Drive, para que sea
     * de la app y así la vea con `drive.file`, la sincronice y la tengan los
     * demás dispositivos. Y es de la app del todo: el archivo lo creó ella,
     * así que la nota se puede escribir encima, mover, renombrar y borrar
     * como cualquier otra; lleva la etiqueta «importado» solo como
     * recuerdo. Devuelve el id de la nota, o el motivo por el que no se pudo.
     */
    suspend fun importPdf(
        bytes: ByteArray,
        fileName: String,
        folderId: Long?,
        /** Qué se está haciendo y cuánto va (0 a 1), para la barra de progreso. */
        onProgress: (label: String, fraction: Float) -> Unit = { _, _ -> },
    ): Result<Long> = running.withLock {
        val prefs = settings.settings.first()
        val rootId = prefs.driveNotesFolderId
            ?: return@withLock Result.failure(IllegalStateException(text(R.string.notes_msg_enable_drive)))
        if (!auth.isAuthorized) return@withLock Result.failure(IllegalStateException(text(R.string.auth_no_account)))
        runCatching {
            // Leer el PDF es trabajo de CPU (pinta cada página): fuera del
            // hilo principal, o la app deja de responder con un PDF largo.
            onProgress(text(R.string.import_reading_pdf), 0.02f)
            val notebook = withContext(Dispatchers.Default) {
                NotePdf.read(bytes) { page, total ->
                    onProgress(text(R.string.import_converting_page, page, total), 0.05f + 0.45f * page / total.coerceAtLeast(1))
                }
            }
            val name = fileName.trim().ifEmpty { "Importado.pdf" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
            onProgress(text(R.string.import_finding_folder), 0.52f)
            val remote = walk(rootId)
            val parent = mirrorFolder(rootId, remote, folders.getAll().associateBy { it.id }, folderId)
            onProgress(text(R.string.import_uploading), 0.58f)
            val created = drive.createPdf(parent, uniqueName(remote, parent, name), bytes) { sent, total ->
                onProgress(text(R.string.import_uploading_percent, (100 * sent / total.coerceAtLeast(1)).toInt()), 0.58f + 0.37f * sent / total.coerceAtLeast(1))
            }
            onProgress(text(R.string.import_saving), 0.96f)
            val id = notes.importFromDrive(
                existingId = null,
                notebook = notebook,
                title = created.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                fileId = created.id,
                parentId = parent,
                name = created.name,
                modifiedAt = created.modifiedMillis,
            )
            notes.placeFromDrive(id, folderId)
            // Propia, no de otra app: el archivo de Drive es de esta app.
            notes.setDrive(id, created.id, parent, created.name, created.modifiedMillis, now(), owned = true)
            id
        }.recoverCatching { e ->
            throw if (e is DriveException) IllegalStateException(describe(e), e) else e
        }
    }

    // --- Papelera -----------------------------------------------------------

    private suspend fun flushDeletes() {
        val dao = db.drivePendingDeleteDao()
        dao.getAll().forEach { pending ->
            drive.trash(pending.fileId)
            dao.delete(pending.fileId)
        }
    }

    // --- Lo que hay en Drive ------------------------------------------------

    /** Un PDF de Drive con la ruta de carpetas (relativa a la elegida) en la que está. */
    private class RemotePdf(val file: GDriveFile, val parentId: String, val path: List<String>)

    private class RemoteTree(
        val pdfs: Map<String, RemotePdf>,
        /** Todas las carpetas de Drive por ruta relativa («NOTAS DEL DÍA/2026-01-25»). */
        val foldersByPath: MutableMap<String, String>,
        /** Rutas (en minúsculas) de las carpetas con algún PDF dentro, directo o en subcarpetas. */
        val livePaths: Set<String>,
        /** Nombres ya usados en cada carpeta de Drive, para no repetir. */
        val namesByParent: MutableMap<String, MutableSet<String>>,
    )

    private class RemoteFolder(val parentPath: String?, val path: String, var hasPdf: Boolean = false)

    private suspend fun walk(rootId: String): RemoteTree {
        val pdfs = mutableMapOf<String, RemotePdf>()
        val foldersByPath = mutableMapOf<String, String>("" to rootId)
        val namesByParent = mutableMapOf<String, MutableSet<String>>()
        val visitedFolders = mutableListOf<RemoteFolder>()
        val queue = ArrayDeque<Triple<String, List<String>, RemoteFolder>>()
        val root = RemoteFolder(null, "")
        queue += Triple(rootId, emptyList(), root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_FOLDERS) {
            val (folderId, path, folder) = queue.removeFirst()
            visitedFolders += folder
            val children = drive.listChildren(folderId)
            namesByParent[folderId] = children.mapTo(mutableSetOf()) { it.name.lowercase() }
            children.forEach { child ->
                when {
                    child.isFolder -> {
                        val childPath = path + child.name
                        val key = childPath.joinToString("/")
                        foldersByPath[key] = child.id
                        queue += Triple(child.id, childPath, RemoteFolder(folder.path, key))
                    }
                    child.isPdf -> {
                        pdfs[child.id] = RemotePdf(child, folderId, path)
                        folder.hasPdf = true
                    }
                }
            }
        }
        // Una carpeta cuenta si tiene algún PDF, aunque sea en una subcarpeta:
        // se sube de cada carpeta con PDF hasta la raíz.
        val byPath = visitedFolders.associateBy { it.path }
        val live = mutableSetOf<String>()
        visitedFolders.filter { it.hasPdf }.forEach { folder ->
            var cursor: RemoteFolder? = folder
            while (cursor != null && cursor.path.isNotEmpty() && live.add(cursor.path.lowercase())) {
                cursor = cursor.parentPath?.let { byPath[it] }
            }
        }
        return RemoteTree(pdfs, foldersByPath, live, namesByParent)
    }

    // --- Carpetas -------------------------------------------------------------

    /**
     * Aquí existen las carpetas de Drive que tienen alguna nota dentro, con su
     * misma ruta. Las vacías y las auxiliares de Boox (la carpeta con un HTML
     * que deja junto a cada PDF) ni se crean ni se enseñan; y si una pasada
     * anterior las trajo, o una carpeta se quedó sin notas en Drive, se quita
     * de aquí, siempre que aquí también esté vacía. Las carpetas de Drive se
     * crean al subir un PDF que las necesite, no antes.
     */
    private suspend fun mirrorFolders(remote: RemoteTree) {
        remote.foldersByPath.keys
            .filter { it.isNotEmpty() && it.lowercase() in remote.livePaths }
            .sortedBy { it.count { c -> c == '/' } }
            .forEach { path -> folders.ensurePath(path.split('/')) }
        pruneFolders(remote)
    }

    /**
     * Carpetas locales que Drive conoce pero sin ningún PDF dentro: sobran,
     * si aquí tampoco tienen nada. Se recorren de la más honda a la raíz,
     * para que una rama vacía caiga entera. Las que solo existen aquí (recién
     * creadas en la app, a la espera de notas) se quedan.
     */
    private suspend fun pruneFolders(remote: RemoteTree) {
        val known = remote.foldersByPath.keys.mapTo(mutableSetOf()) { it.lowercase() }
        val all = folders.getAll()
        val byId = all.associateBy { it.id }
        all.map { it to folders.pathOf(it.id, byId).joinToString("/") { f -> f.name }.lowercase() }
            .filter { (_, path) -> path in known && path !in remote.livePaths }
            .sortedByDescending { (_, path) -> path.count { c -> c == '/' } }
            .forEach { (folder, path) ->
                if (folders.deleteIfEmpty(folder.id)) Log.i(TAG, "Carpeta vacía fuera: $path")
            }
    }

    // --- Bajar --------------------------------------------------------------

    private suspend fun pull(remote: RemoteTree): Int {
        var count = 0
        // Con `drive.file` la app solo ve lo que ella misma creó: cualquier
        // PDF de la carpeta es suyo, también los importados antes de que se
        // marcaran como propios. Con Drive completo (sesión antigua) no se
        // sabe, y una nota de otra app se deja como está.
        val allOwned = !auth.hasFullDriveScope
        remote.pdfs.values.forEach { pdf ->
            var local = notes.getByDriveFileId(pdf.file.id)
            val folderId = if (pdf.path.isEmpty()) null else folders.ensurePath(pdf.path)

            if (local != null && !local.driveOwned && allOwned) {
                notes.setDrive(local.id, pdf.file.id, local.driveParentId, local.driveName, local.driveModifiedAt, local.driveSyncedAt, owned = true)
                local = notes.getById(local.id)
                Log.i(TAG, "Adoptada como propia: ${pdf.file.name}")
            }

            // La carpeta sigue a Drive: si el PDF está en otra carpeta que la
            // nota (lo movieron allí, o la nota quedó mal colocada), se
            // recoloca. Salvo que aquí haya un movimiento sin subir todavía:
            // ese manda, y lo aplica la subida de después.
            if (local != null && !local.driveMetaDirty && local.folderId != folderId) {
                notes.placeFromDrive(local.id, folderId)
            }
            if (local != null && !local.driveMetaDirty &&
                (local.driveParentId != pdf.parentId || local.driveName != pdf.file.name)
            ) {
                notes.setDrive(local.id, pdf.file.id, pdf.parentId, pdf.file.name, local.driveModifiedAt, local.driveSyncedAt, local.driveOwned)
            }

            val remoteChanged = local == null ||
                pdf.file.modifiedMillis > (local.driveModifiedAt ?: 0L) + MODIFIED_TOLERANCE_MS
            if (!remoteChanged) return@forEach
            // Cambió en los dos sitios: se queda el más reciente. Si el local
            // es más nuevo, la subida de después lo impone.
            if (local != null && local.driveDirty && local.updatedAt > pdf.file.modifiedMillis) return@forEach

            val bytes = drive.download(pdf.file.id)
            val notebook = runCatching { NotePdf.read(bytes) }
                .getOrElse { Log.w(TAG, "PDF ilegible: ${pdf.file.name}", it); return@forEach }
            val id = notes.importFromDrive(
                existingId = local?.id,
                notebook = notebook,
                title = local?.title ?: pdf.file.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                fileId = pdf.file.id,
                parentId = pdf.parentId,
                name = pdf.file.name,
                modifiedAt = pdf.file.modifiedMillis,
            )
            // Una nota nueva va a la carpeta local de la misma ruta que en Drive.
            if (local == null) notes.placeFromDrive(id, folderId)
            count++
        }

        // Archivos borrados en Drive (o en su papelera): la nota se va de
        // aquí también. Si el archivo sigue existiendo pero fuera de la
        // carpeta elegida, la nota se queda y se deja en paz.
        notes.getAll().filter { it.driveFileId != null && it.driveFileId !in remote.pdfs }.forEach { note ->
            val stillThere = drive.getFile(note.driveFileId!!)?.takeIf { !it.trashed }
            if (stillThere == null) {
                Log.i(TAG, "Borrada en Drive: ${note.driveName ?: note.id}")
                notes.deleteLocalOnly(note.id)
            }
        }
        return count
    }

    // --- Subir --------------------------------------------------------------

    private suspend fun push(rootId: String, remote: RemoteTree): Int {
        var count = 0
        val folderMap = folders.getAll().associateBy { it.id }
        notes.getAll().filter { it.belongsToManager && !it.isImported && (it.driveDirty || it.driveMetaDirty) }.forEach { note ->
            val notebook = StrokeCodec.decodeNotebook(note.strokesJson)
            if (notebook.isEmpty) return@forEach
            val syncedAt = note.updatedAt

            val fileId = note.driveFileId
            if (fileId == null) {
                val bytes = NotePdf.write(notebook, note.title)
                val parent = mirrorFolder(rootId, remote, folderMap, note.folderId)
                val name = uniqueName(remote, parent, fileNameFor(note))
                val created = drive.createPdf(parent, name, bytes)
                notes.setDrive(note.id, created.id, parent, name, created.modifiedMillis, syncedAt, owned = true)
                notes.setDriveMetaDirty(note.id, false)
                count++
                return@forEach
            }

            // Nombre y carpeta: la carpeta se mueve siempre (la estructura es
            // la misma a los dos lados); el nombre solo en los archivos de
            // esta app, que los de otra app se llaman como esa app quiso.
            if (note.driveMetaDirty) {
                val parent = mirrorFolder(rootId, remote, folderMap, note.folderId)
                val wanted = fileNameFor(note)
                val rename = note.driveOwned && (note.driveName?.let { !sameName(it, wanted) } ?: true)
                val move = parent != note.driveParentId
                if (rename || move) {
                    val name = if (rename) uniqueName(remote, parent, wanted) else note.driveName
                    drive.updateMetadata(
                        fileId,
                        name = if (rename) name else null,
                        addParent = if (move) parent else null,
                        removeParent = if (move) note.driveParentId else null,
                    )
                    notes.setDrive(note.id, fileId, parent, name, note.driveModifiedAt, note.driveSyncedAt, note.driveOwned)
                }
                notes.setDriveMetaDirty(note.id, false)
                count++
            }
            if (!note.driveDirty) return@forEach

            val bytes = NotePdf.write(notebook, note.title)
            val updated = try {
                drive.updatePdf(fileId, bytes)
            } catch (e: DriveException) {
                if (e.code != 404) throw e
                // El archivo desapareció entre la bajada y la subida: se crea otro.
                val parent = mirrorFolder(rootId, remote, folderMap, note.folderId)
                val name = uniqueName(remote, parent, fileNameFor(note))
                drive.createPdf(parent, name, bytes).also {
                    notes.setDrive(note.id, it.id, parent, name, it.modifiedMillis, syncedAt, owned = true)
                }
            }
            val current = notes.getById(note.id) ?: return@forEach
            notes.setDrive(
                note.id, updated.id, current.driveParentId ?: updated.parents.firstOrNull(),
                current.driveName ?: updated.name, updated.modifiedMillis, syncedAt, current.driveOwned,
            )
            notes.setDriveMetaDirty(note.id, false)
            count++
        }
        return count
    }

    /**
     * La carpeta de Drive que espeja la carpeta local (creándola si falta),
     * bajo la elegida. La raíz local es la carpeta elegida.
     */
    private suspend fun mirrorFolder(
        rootId: String,
        remote: RemoteTree,
        folderMap: Map<Long, NoteFolderEntity>,
        folderId: Long?,
    ): String {
        val path = folders.pathOf(folderId, folderMap).map { it.name }
        var parent = rootId
        val walked = mutableListOf<String>()
        path.forEach { name ->
            walked += name
            val key = walked.joinToString("/")
            parent = remote.foldersByPath[key] ?: drive.createFolder(parent, name).id.also {
                remote.foldersByPath[key] = it
                remote.namesByParent.getOrPut(parent) { mutableSetOf() }.add(name.lowercase())
            }
        }
        return parent
    }

    private fun fileNameFor(note: InkNoteEntity): String {
        val base = note.title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(note)
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(120)
        return "$clean.pdf"
    }

    private fun defaultTitle(note: InkNoteEntity): String {
        val day = note.anchorDayMillis?.let { LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY)) }
        return if (day != null) {
            "${text(R.string.drive_note_file_prefix)} ${day.format(DAY)} ${Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).format(TIME)}"
        } else {
            "${text(R.string.drive_note_file_prefix)} ${Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).format(STAMP)}"
        }
    }

    private fun sameName(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** «Nota.pdf», «Nota (2).pdf»… hasta dar con uno libre en esa carpeta. */
    private fun uniqueName(remote: RemoteTree, parent: String, wanted: String): String {
        val used = remote.namesByParent.getOrPut(parent) { mutableSetOf() }
        if (wanted.lowercase() !in used) {
            used += wanted.lowercase()
            return wanted
        }
        val stem = wanted.removeSuffix(".pdf")
        var n = 2
        while (true) {
            val candidate = "$stem ($n).pdf"
            if (candidate.lowercase() !in used) {
                used += candidate.lowercase()
                return candidate
            }
            n++
        }
    }

    private companion object {
        const val MAX_FOLDERS = 500
        /** Drive redondea los tiempos: un segundo de margen evita rebajar lo recién subido. */
        const val MODIFIED_TOLERANCE_MS = 1_500L
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")
    }
}
