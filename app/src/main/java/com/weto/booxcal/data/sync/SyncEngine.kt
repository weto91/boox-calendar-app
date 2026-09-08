package com.weto.booxcal.data.sync

import android.graphics.Color
import android.util.Log
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.LocalCollections
import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.EventEntity
import com.weto.booxcal.data.local.entity.SyncEntityType
import com.weto.booxcal.data.local.entity.SyncMapEntity
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.data.local.entity.TaskListEntity
import com.weto.booxcal.data.remote.BackendAuthException
import com.weto.booxcal.data.remote.RemoteEvent
import com.weto.booxcal.data.remote.RemoteTask
import com.weto.booxcal.data.remote.SyncBackend
import com.weto.booxcal.data.remote.SyncCursor
import com.weto.booxcal.data.remote.SyncCursorExpiredException
import com.weto.booxcal.data.remote.TimeWindow
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SyncEngine"

/** Ventana de la sincronización completa: un año atrás, dos hacia delante. */
private const val WINDOW_PAST_DAYS = 365L
private const val WINDOW_FUTURE_DAYS = 730L

data class SyncOutcome(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: List<String> = emptyList(),
    val purged: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    fun summary(): String = buildString {
        append("↑$pushed ↓$pulled")
        if (purged > 0) append(" · purgadas $purged")
        if (conflicts.isNotEmpty()) append(" · ${conflicts.size} conflicto(s)")
        error?.let { append(" · $it") }
    }

    operator fun plus(other: SyncOutcome) = SyncOutcome(
        pushed = pushed + other.pushed,
        pulled = pulled + other.pulled,
        conflicts = conflicts + other.conflicts,
        purged = purged + other.purged,
        error = error ?: other.error,
    )
}

/**
 * Motor de sincronización, agnóstico de backend.
 *
 * Orden por colección: **primero subir, luego bajar**. Al revés, un cambio
 * local sin subir sería pisado por la versión del servidor antes de haber
 * tenido ocasión de viajar.
 *
 * Conflictos: gana el `updated` más reciente. Como se sube antes de bajar, al
 * llegar a la bajada solo quedan sucias las filas cuya subida falló; ahí sí se
 * compara marca de tiempo y se registra el conflicto.
 *
 * Una pasada cada vez. WorkManager lanza la periódica, la de «ahora» y la de
 * «en un momento» como trabajos distintos y puede ejecutarlos a la vez; dos
 * pasadas solapadas veían la misma fila nueva sin correspondencia y la
 * creaban las dos en el servidor. De ahí salían recordatorios repetidos.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val backends: List<SyncBackend>,
    private val settings: SettingsStore,
    private val taskRepository: TaskRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val calendarDao = db.calendarDao()
    private val taskListDao = db.taskListDao()
    private val eventDao = db.eventDao()
    private val taskDao = db.taskDao()
    private val syncMapDao = db.syncMapDao()

    private val running = Mutex()

    suspend fun syncAll(): SyncOutcome = running.withLock { syncAllLocked() }

    private suspend fun syncAllLocked(): SyncOutcome {
        var outcome = SyncOutcome()

        for (backend in backends) {
            if (!backend.isAuthorized()) continue
            outcome += runCatching { syncBackend(backend) }
                .getOrElse { t ->
                    Log.w(TAG, "Fallo sincronizando ${backend.id}", t)
                    SyncOutcome(error = t.message ?: t.javaClass.simpleName)
                }
        }

        val current = settings.settings.first()
        val purged = runCatching { taskRepository.applyRetention(current) }.getOrDefault(0)
        outcome += SyncOutcome(purged = maxOf(purged, 0))

        if (outcome.ok) {
            settings.recordSyncSuccess(now(), outcome.summary())
        } else {
            settings.recordSyncFailure(outcome.error!!)
        }
        return outcome
    }

    private suspend fun syncBackend(backend: SyncBackend): SyncOutcome {
        var outcome = SyncOutcome()
        reconcileCalendars(backend)
        reconcileTaskLists(backend)
        // Va aquí y no antes: hasta que las colecciones del backend existen no
        // hay adónde mudar lo que se creó sin cuenta, y va antes de la subida
        // para que se suba en esta misma pasada y no en la siguiente.
        adoptLocalEntries()

        val window = currentWindow()

        for (calendar in calendarDao.getAll().filter { it.backendId == backend.id }) {
            outcome += runCatching { syncCalendar(backend, calendar, window) }
                .getOrElse { t ->
                    if (t is BackendAuthException) throw t
                    Log.w(TAG, "Calendario ${calendar.name}: ${t.message}", t)
                    SyncOutcome(error = "${calendar.name}: ${t.message}")
                }
        }

        for (list in taskListDao.getAll().filter { it.backendId == backend.id }) {
            outcome += runCatching { syncTaskList(backend, list) }
                .getOrElse { t ->
                    if (t is BackendAuthException) throw t
                    Log.w(TAG, "Lista ${list.name}: ${t.message}", t)
                    SyncOutcome(error = "${list.name}: ${t.message}")
                }
        }
        return outcome
    }

    private fun currentWindow(): TimeWindow {
        val nowMillis = now()
        return TimeWindow(
            fromMillis = nowMillis - WINDOW_PAST_DAYS * MILLIS_PER_DAY,
            toMillis = nowMillis + WINDOW_FUTURE_DAYS * MILLIS_PER_DAY,
        )
    }

    // --- Adopción de lo creado sin cuenta -----------------------------------

    /**
     * Muda al calendario y a la lista de la cuenta todo lo que se creó antes de
     * haberla configurado.
     *
     * El modelo obliga a que cada evento cuelgue de un calendario, así que sin
     * cuenta se usa uno local que ningún backend reclama. Eso está bien para
     * poder apuntar cosas desde el primer arranque, y muy mal si se queda así:
     * el usuario ve "conectado a Google" y nada sube nunca.
     *
     * Se prefiere el calendario principal de la cuenta. Marcar las filas como
     * sucias basta para que la subida de esta misma pasada se las lleve.
     */
    private suspend fun adoptLocalEntries() {
        val calendars = calendarDao.getAll()
        val localCalendar = calendars.firstOrNull { it.backendId == LocalCollections.BACKEND_ID }
        val targetCalendar = calendars
            .filter { it.backendId != LocalCollections.BACKEND_ID && it.isWritable }
            .minByOrNull { if (it.isPrimary) 0 else 1 }

        if (localCalendar != null && targetCalendar != null) {
            val moved = eventDao.moveToCalendar(localCalendar.id, targetCalendar.id, now())
            if (moved > 0) Log.i(TAG, "$moved eventos adoptados por «${targetCalendar.name}»")
        }

        val lists = taskListDao.getAll()
        val localList = lists.firstOrNull { it.backendId == LocalCollections.BACKEND_ID }
        val targetList = lists.firstOrNull { it.backendId != LocalCollections.BACKEND_ID }

        if (localList != null && targetList != null) {
            val moved = taskDao.moveToList(localList.id, targetList.id, now())
            if (moved > 0) Log.i(TAG, "$moved tareas adoptadas por «${targetList.name}»")
        }
    }

    // --- Colecciones --------------------------------------------------------

    private suspend fun reconcileCalendars(backend: SyncBackend) {
        val remote = backend.listCalendars()
        if (remote.isEmpty()) {
            calendarDao.deleteAllForBackend(backend.id)
            return
        }
        for (collection in remote) {
            val existing = calendarDao.getByRemote(backend.id, collection.remoteId)
            if (existing == null) {
                calendarDao.insert(
                    CalendarEntity(
                        backendId = backend.id,
                        remoteId = collection.remoteId,
                        name = collection.name,
                        colorArgb = collection.colorArgb ?: Color.BLACK,
                        isPrimary = collection.isPrimary,
                        isWritable = collection.isWritable,
                    )
                )
            } else {
                // isVisible y syncToken son estado local: no se tocan aquí.
                calendarDao.update(
                    existing.copy(
                        name = collection.name,
                        colorArgb = collection.colorArgb ?: existing.colorArgb,
                        isPrimary = collection.isPrimary,
                        isWritable = collection.isWritable,
                    )
                )
            }
        }
        calendarDao.deleteMissing(backend.id, remote.map { it.remoteId })
        syncMapDao.deleteOrphanEventEntries()
    }

    private suspend fun reconcileTaskLists(backend: SyncBackend) {
        val remote = backend.listTaskLists()
        if (remote.isEmpty()) {
            taskListDao.deleteAllForBackend(backend.id)
            return
        }
        for (collection in remote) {
            val existing = taskListDao.getByRemote(backend.id, collection.remoteId)
            if (existing == null) {
                taskListDao.insert(
                    TaskListEntity(
                        backendId = backend.id,
                        remoteId = collection.remoteId,
                        name = collection.name,
                        colorArgb = collection.colorArgb ?: Color.BLACK,
                    )
                )
            } else {
                taskListDao.update(existing.copy(name = collection.name))
            }
        }
        taskListDao.deleteMissing(backend.id, remote.map { it.remoteId })
        syncMapDao.deleteOrphanTaskEntries()
    }

    // --- Eventos ------------------------------------------------------------

    private suspend fun syncCalendar(
        backend: SyncBackend,
        calendar: CalendarEntity,
        window: TimeWindow,
    ): SyncOutcome {
        val pushed = if (calendar.isWritable) pushEvents(backend, calendar) else 0
        val (pulled, conflicts) = pullEvents(backend, calendar, window)
        return SyncOutcome(pushed = pushed, pulled = pulled, conflicts = conflicts)
    }

    private suspend fun pushEvents(backend: SyncBackend, calendar: CalendarEntity): Int {
        var count = 0
        for (event in eventDao.getPending(calendar.id)) {
            val mapping = syncMapDao.forLocal(SyncEntityType.EVENT, event.id, backend.id)
            when {
                event.deleted -> {
                    mapping?.let { backend.deleteEvent(calendar.remoteId, it.remoteId) }
                    eventDao.deleteRow(event.id)
                    syncMapDao.deleteAllForLocal(SyncEntityType.EVENT, event.id)
                }

                mapping == null -> {
                    val created = backend.createEvent(calendar.remoteId, event.toRemote(null))
                    val remoteId = created.remoteId ?: continue
                    syncMapDao.upsert(
                        SyncMapEntity(
                            entityType = SyncEntityType.EVENT,
                            localId = event.id,
                            backendId = backend.id,
                            remoteId = remoteId,
                            etag = created.etag,
                            lastSyncedAt = now(),
                        )
                    )
                    eventDao.update(
                        event.copy(dirty = false, updatedAt = created.updatedAtMillis.orNow())
                    )
                }

                else -> {
                    val updated =
                        backend.updateEvent(calendar.remoteId, event.toRemote(mapping.remoteId))
                    syncMapDao.upsert(
                        mapping.copy(etag = updated.etag, lastSyncedAt = now())
                    )
                    eventDao.update(
                        event.copy(dirty = false, updatedAt = updated.updatedAtMillis.orNow())
                    )
                }
            }
            count++
        }
        return count
    }

    private suspend fun pullEvents(
        backend: SyncBackend,
        calendar: CalendarEntity,
        window: TimeWindow,
    ): Pair<Int, List<String>> {
        val cursor = calendar.syncToken?.let { SyncCursor(token = it) }
        val page = try {
            backend.pullEvents(calendar.remoteId, cursor, window)
        } catch (e: SyncCursorExpiredException) {
            Log.i(TAG, "Cursor caducado en ${calendar.name}; se reconstruye")
            eventDao.deleteCleanRows(calendar.id)
            syncMapDao.deleteOrphanEventEntries()
            calendarDao.setSyncState(calendar.id, null, now())
            backend.pullEvents(calendar.remoteId, null, window)
        }

        val conflicts = mutableListOf<String>()
        var applied = 0
        for (remote in page.items) {
            if (applyRemoteEvent(backend, calendar, remote, conflicts)) applied++
        }
        calendarDao.setSyncState(calendar.id, page.nextCursor?.token, now())
        return applied to conflicts
    }

    private suspend fun applyRemoteEvent(
        backend: SyncBackend,
        calendar: CalendarEntity,
        remote: RemoteEvent,
        conflicts: MutableList<String>,
    ): Boolean {
        val remoteId = remote.remoteId ?: return false
        val mapping = syncMapDao.forRemote(backend.id, SyncEntityType.EVENT, remoteId)
        val local = mapping?.let { eventDao.getById(it.localId) }

        if (remote.deleted) {
            if (local == null) return false
            if (local.dirty) {
                // Borrado allí, editado aquí. La edición local es lo único que
                // queda: se conserva y se volverá a crear en la próxima subida.
                conflicts += "«${local.title}» se borró en el servidor pero tiene cambios locales"
                syncMapDao.deleteAllForLocal(SyncEntityType.EVENT, local.id)
                return false
            }
            eventDao.deleteRow(local.id)
            syncMapDao.deleteAllForLocal(SyncEntityType.EVENT, local.id)
            return true
        }

        if (local == null) {
            // Si ya hay una fila local igual, pendiente de subir y sin
            // correspondencia, es esta misma: se creó en el servidor y la
            // respuesta no llegó a apuntarse (red cortada, proceso matado).
            // Se adopta en vez de duplicarla.
            val twin = eventDao.getPending(calendar.id).firstOrNull { candidate ->
                !candidate.deleted &&
                    candidate.title == remote.title &&
                    candidate.startMillis == remote.startMillis &&
                    candidate.endMillis == remote.endMillis &&
                    candidate.allDay == remote.allDay &&
                    syncMapDao.forLocal(SyncEntityType.EVENT, candidate.id, backend.id) == null
            }
            val id = twin?.id ?: eventDao.insert(remote.toEntity(calendar.id))
            if (twin != null) {
                eventDao.update(
                    remote.toEntity(calendar.id).copy(id = twin.id, inkNoteId = twin.inkNoteId, dirty = false, deleted = false)
                )
            }
            syncMapDao.upsert(
                SyncMapEntity(
                    entityType = SyncEntityType.EVENT,
                    localId = id,
                    backendId = backend.id,
                    remoteId = remoteId,
                    etag = remote.etag,
                    lastSyncedAt = now(),
                )
            )
            return true
        }

        if (local.dirty && local.updatedAt > remote.updatedAtMillis) {
            conflicts += "«${local.title}»: se conserva la versión local, más reciente"
            return false
        }

        eventDao.update(
            remote.toEntity(calendar.id).copy(
                id = local.id,
                // El trazo manuscrito es solo local: ningún backend lo conoce,
                // así que una bajada no puede hacerlo desaparecer.
                inkNoteId = local.inkNoteId,
                dirty = false,
                deleted = false,
            )
        )
        mapping?.let { syncMapDao.upsert(it.copy(etag = remote.etag, lastSyncedAt = now())) }
        return true
    }

    // --- Tareas -------------------------------------------------------------

    private suspend fun syncTaskList(backend: SyncBackend, list: TaskListEntity): SyncOutcome {
        val pushed = pushTasks(backend, list)
        val (pulled, conflicts) = pullTasks(backend, list)
        return SyncOutcome(pushed = pushed, pulled = pulled, conflicts = conflicts)
    }

    private suspend fun pushTasks(backend: SyncBackend, list: TaskListEntity): Int {
        var count = 0
        for (task in taskDao.getPending(list.id)) {
            val mapping = syncMapDao.forLocal(SyncEntityType.TASK, task.id, backend.id)
            when {
                task.deleted -> {
                    mapping?.let { backend.deleteTask(list.remoteId, it.remoteId) }
                    taskDao.deleteRow(task.id)
                    syncMapDao.deleteAllForLocal(SyncEntityType.TASK, task.id)
                }

                mapping == null -> {
                    val created = backend.createTask(list.remoteId, task.toRemote(null))
                    val remoteId = created.remoteId ?: continue
                    syncMapDao.upsert(
                        SyncMapEntity(
                            entityType = SyncEntityType.TASK,
                            localId = task.id,
                            backendId = backend.id,
                            remoteId = remoteId,
                            etag = created.etag,
                            lastSyncedAt = now(),
                        )
                    )
                    taskDao.update(
                        task.copy(dirty = false, updatedAt = created.updatedAtMillis.orNow())
                    )
                }

                else -> {
                    val updated = backend.updateTask(list.remoteId, task.toRemote(mapping.remoteId))
                    syncMapDao.upsert(mapping.copy(etag = updated.etag, lastSyncedAt = now()))
                    taskDao.update(
                        task.copy(dirty = false, updatedAt = updated.updatedAtMillis.orNow())
                    )
                }
            }
            count++
        }
        return count
    }

    private suspend fun pullTasks(
        backend: SyncBackend,
        list: TaskListEntity,
    ): Pair<Int, List<String>> {
        val cursor = list.lastSyncedAt?.let { SyncCursor(sinceMillis = it) }
        val page = try {
            backend.pullTasks(list.remoteId, cursor)
        } catch (e: SyncCursorExpiredException) {
            taskDao.deleteCleanRows(list.id)
            syncMapDao.deleteOrphanTaskEntries()
            backend.pullTasks(list.remoteId, null)
        }

        val conflicts = mutableListOf<String>()
        var applied = 0
        for (remote in page.items) {
            if (applyRemoteTask(backend, list, remote, conflicts)) applied++
        }
        page.nextCursor?.sinceMillis?.let { taskListDao.setLastSyncedAt(list.id, it) }
        return applied to conflicts
    }

    private suspend fun applyRemoteTask(
        backend: SyncBackend,
        list: TaskListEntity,
        remote: RemoteTask,
        conflicts: MutableList<String>,
    ): Boolean {
        val remoteId = remote.remoteId ?: return false
        val mapping = syncMapDao.forRemote(backend.id, SyncEntityType.TASK, remoteId)
        val local = mapping?.let { taskDao.getById(it.localId) }

        if (remote.deleted) {
            if (local == null) return false
            if (local.dirty) {
                conflicts += "«${local.title}» se borró en el servidor pero tiene cambios locales"
                syncMapDao.deleteAllForLocal(SyncEntityType.TASK, local.id)
                return false
            }
            taskDao.deleteRow(local.id)
            syncMapDao.deleteAllForLocal(SyncEntityType.TASK, local.id)
            return true
        }

        if (local == null) {
            // Igual que con los eventos: una tarea local pendiente, idéntica y
            // sin correspondencia es esta misma, no otra.
            val twin = taskDao.getPending(list.id).firstOrNull { candidate ->
                !candidate.deleted &&
                    candidate.title == remote.title &&
                    candidate.dueDayMillis == remote.dueDayMillis &&
                    candidate.notes.orEmpty() == remote.notes.orEmpty() &&
                    syncMapDao.forLocal(SyncEntityType.TASK, candidate.id, backend.id) == null
            }
            val id = twin?.id ?: taskDao.insert(remote.toEntity(list.id, sortIndex = remote.sortIndexHint()))
            if (twin != null) {
                taskDao.update(
                    remote.toEntity(list.id, sortIndex = twin.sortIndex).copy(
                        id = twin.id,
                        inkNoteId = twin.inkNoteId,
                        purged = twin.purged,
                        dirty = false,
                        deleted = false,
                    )
                )
            }
            syncMapDao.upsert(
                SyncMapEntity(
                    entityType = SyncEntityType.TASK,
                    localId = id,
                    backendId = backend.id,
                    remoteId = remoteId,
                    etag = remote.etag,
                    lastSyncedAt = now(),
                )
            )
            return true
        }

        if (local.dirty && local.updatedAt > remote.updatedAtMillis) {
            conflicts += "«${local.title}»: se conserva la versión local, más reciente"
            return false
        }

        taskDao.update(
            remote.toEntity(list.id, sortIndex = local.sortIndex).copy(
                id = local.id,
                inkNoteId = local.inkNoteId,
                // `purged` es una decisión local de presentación (§9); el
                // servidor no sabe nada de ella y no debe reiniciarla.
                purged = local.purged,
                dirty = false,
                deleted = false,
            )
        )
        mapping?.let { syncMapDao.upsert(it.copy(etag = remote.etag, lastSyncedAt = now())) }
        return true
    }

    // --- Mapeo local <-> neutro --------------------------------------------

    private fun Long.orNow(): Long = if (this > 0) this else now()

    private fun EventEntity.toRemote(remoteId: String?) = RemoteEvent(
        remoteId = remoteId,
        title = title,
        description = description,
        location = location,
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        timeZone = timeZone,
        recurrence = recurrence?.lines()?.filter { it.isNotBlank() }.orEmpty(),
        recurringRemoteId = recurringRemoteId,
        reminderMinutes = reminderMinutes
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty(),
        colorArgb = colorArgb,
        updatedAtMillis = updatedAt,
        deleted = deleted,
    )

    private fun RemoteEvent.toEntity(calendarId: Long) = EventEntity(
        calendarId = calendarId,
        title = title,
        description = description,
        location = location,
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        timeZone = timeZone,
        recurringRemoteId = recurringRemoteId,
        recurrence = recurrence.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        reminderMinutes = reminderMinutes.takeIf { it.isNotEmpty() }?.joinToString(","),
        colorArgb = colorArgb,
        updatedAt = updatedAtMillis.orNow(),
        dirty = false,
        deleted = false,
    )

    private fun TaskEntity.toRemote(remoteId: String?) = RemoteTask(
        remoteId = remoteId,
        title = title,
        notes = notes,
        dueDayMillis = dueDayMillis,
        completedAtMillis = completedAt,
        updatedAtMillis = updatedAt,
        deleted = deleted,
    )

    private fun RemoteTask.toEntity(taskListId: Long, sortIndex: Long) = TaskEntity(
        taskListId = taskListId,
        title = title,
        notes = notes,
        dueDayMillis = dueDayMillis,
        completedAt = completedAtMillis,
        sortIndex = sortIndex,
        remotePosition = position,
        updatedAt = updatedAtMillis.orNow(),
        dirty = false,
        deleted = false,
    )

    /**
     * Google numera las tareas con `position`, una cadena de dígitos ordenable
     * lexicográficamente. La usamos solo como orden inicial: a partir de ahí el
     * orden manual es local (§7; subirlo requeriría tasks.move, fuera de la v1).
     */
    private fun RemoteTask.sortIndexHint(): Long =
        position?.trimStart('0')?.take(15)?.toLongOrNull() ?: 0L
}
