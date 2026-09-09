package com.weto.booxcal.data.remote.google

import android.graphics.Color
import com.weto.booxcal.data.remote.BackendAuthException
import com.weto.booxcal.data.remote.BackendTransientException
import com.weto.booxcal.data.remote.RemoteCollection
import com.weto.booxcal.data.remote.RemoteEvent
import com.weto.booxcal.data.remote.RemotePage
import com.weto.booxcal.data.remote.RemoteTask
import com.weto.booxcal.data.remote.SyncBackend
import com.weto.booxcal.data.remote.SyncCursor
import com.weto.booxcal.data.remote.SyncCursorExpiredException
import com.weto.booxcal.data.remote.TimeWindow
import com.weto.booxcal.util.Rfc3339
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import retrofit2.HttpException
import java.io.IOException
import com.weto.booxcal.R
import com.weto.booxcal.di.Graph
import androidx.annotation.StringRes

private const val PAGE_SIZE = 250
private const val TASK_PAGE_SIZE = 100

class GoogleBackend(
    private val auth: GoogleAuthManager,
    private val calendarApi: GoogleCalendarApi,
    private val tasksApi: GoogleTasksApi,
) : SyncBackend {

    override val id: String = BACKEND_ID
    override val displayName: String = "Google"

    override suspend fun isAuthorized(): Boolean = auth.isConfigured && auth.isAuthorized

    override suspend fun accountLabel(): String? = auth.accountEmail

    // --- Colecciones --------------------------------------------------------

    override suspend fun listCalendars(): List<RemoteCollection> = runApi {
        val out = mutableListOf<RemoteCollection>()
        var pageToken: String? = null
        do {
            val page = calendarApi.calendarList(PAGE_SIZE, pageToken, false)
            page.items.asSequence()
                .filter { !it.deleted }
                .mapTo(out) { entry ->
                    RemoteCollection(
                        remoteId = entry.id,
                        name = entry.displayName,
                        colorArgb = parseHexColor(entry.backgroundColor),
                        isPrimary = entry.primary,
                        isWritable = entry.writable,
                    )
                }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    override suspend fun listTaskLists(): List<RemoteCollection> = runApi {
        val out = mutableListOf<RemoteCollection>()
        var pageToken: String? = null
        do {
            val page = tasksApi.taskLists(PAGE_SIZE, pageToken)
            page.items.mapTo(out) { list ->
                RemoteCollection(
                    remoteId = list.id,
                    name = list.title ?: list.id,
                    colorArgb = null,
                )
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    // --- Eventos ------------------------------------------------------------

    override suspend fun pullEvents(
        collectionId: String,
        cursor: SyncCursor?,
        window: TimeWindow,
    ): RemotePage<RemoteEvent> = runApi {
        val syncToken = cursor?.token
        val items = mutableListOf<RemoteEvent>()
        var pageToken: String? = null
        var nextSyncToken: String? = null

        do {
            // Con syncToken, Google rechaza timeMin/timeMax. Son dos peticiones
            // distintas, no una con parámetros opcionales.
            val page = if (syncToken != null) {
                calendarApi.events(
                    calendarId = collectionId,
                    timeMin = null,
                    timeMax = null,
                    syncToken = syncToken,
                    pageToken = pageToken,
                    singleEvents = true,
                    showDeleted = true,
                    maxResults = PAGE_SIZE,
                )
            } else {
                calendarApi.events(
                    calendarId = collectionId,
                    timeMin = Rfc3339.formatInstant(window.fromMillis),
                    timeMax = Rfc3339.formatInstant(window.toMillis),
                    syncToken = null,
                    pageToken = pageToken,
                    singleEvents = true,
                    showDeleted = true,
                    maxResults = PAGE_SIZE,
                )
            }
            page.items.forEach { g -> g.toRemoteEvent()?.let(items::add) }
            pageToken = page.nextPageToken
            if (page.nextSyncToken != null) nextSyncToken = page.nextSyncToken
        } while (pageToken != null)

        RemotePage(items, SyncCursor(token = nextSyncToken ?: syncToken))
    }

    override suspend fun createEvent(collectionId: String, event: RemoteEvent): RemoteEvent =
        runApi {
            calendarApi.createEvent(collectionId, eventBody(event)).toRemoteEvent()
                ?: throw BackendTransientException(text(R.string.backend_event_without_id))
        }

    override suspend fun updateEvent(collectionId: String, event: RemoteEvent): RemoteEvent =
        runApi {
            val remoteId = event.remoteId
                ?: throw IllegalArgumentException("updateEvent sin remoteId")
            calendarApi.patchEvent(collectionId, remoteId, eventBody(event)).toRemoteEvent()
                ?: throw BackendTransientException(text(R.string.backend_event_without_id))
        }

    override suspend fun deleteEvent(collectionId: String, remoteId: String) {
        try {
            calendarApi.deleteEvent(collectionId, remoteId)
        } catch (e: HttpException) {
            // 404/410 = ya no está. Objetivo cumplido.
            if (e.code() != 404 && e.code() != 410) throw e.toBackendException()
        } catch (e: IOException) {
            throw e.toBackendException()
        }
    }

    // --- Tareas -------------------------------------------------------------

    override suspend fun pullTasks(
        collectionId: String,
        cursor: SyncCursor?,
    ): RemotePage<RemoteTask> = runApi {
        // Google Tasks no tiene syncToken. El delta se hace con updatedMin, que
        // solo filtra por fecha de modificación: la lista completa la primera vez.
        val updatedMin = cursor?.sinceMillis?.let { Rfc3339.formatInstant(it) }
        val items = mutableListOf<RemoteTask>()
        var pageToken: String? = null
        // Se fija antes de pedir nada: si se tomara después, los cambios
        // ocurridos durante la propia descarga se perderían para siempre.
        val requestedAt = System.currentTimeMillis()

        do {
            val page = tasksApi.tasks(
                taskList = collectionId,
                updatedMin = updatedMin,
                pageToken = pageToken,
                maxResults = TASK_PAGE_SIZE,
                showCompleted = true,
                showDeleted = true,
                showHidden = true,
            )
            page.items.forEach { g -> g.toRemoteTask()?.let(items::add) }
            pageToken = page.nextPageToken
        } while (pageToken != null)

        RemotePage(items, SyncCursor(sinceMillis = requestedAt))
    }

    override suspend fun createTask(collectionId: String, task: RemoteTask): RemoteTask = runApi {
        tasksApi.createTask(collectionId, taskBody(task)).toRemoteTask()
            ?: throw BackendTransientException(text(R.string.backend_task_without_id))
    }

    override suspend fun updateTask(collectionId: String, task: RemoteTask): RemoteTask = runApi {
        val remoteId = task.remoteId ?: throw IllegalArgumentException("updateTask sin remoteId")
        tasksApi.patchTask(collectionId, remoteId, taskBody(task)).toRemoteTask()
            ?: throw BackendTransientException(text(R.string.backend_task_without_id))
    }

    override suspend fun deleteTask(collectionId: String, remoteId: String) {
        try {
            tasksApi.deleteTask(collectionId, remoteId)
        } catch (e: HttpException) {
            if (e.code() != 404 && e.code() != 410) throw e.toBackendException()
        } catch (e: IOException) {
            throw e.toBackendException()
        }
    }

    // --- Mapeo --------------------------------------------------------------

    private fun GEvent.toRemoteEvent(): RemoteEvent? {
        val remoteId = id ?: return null
        val cancelled = status == "cancelled"
        val allDay = start?.date != null

        // Un evento cancelado llega con id y poco más: ni fechas ni título.
        val parsedStart =
            if (allDay) Rfc3339.parseDateOrNull(start?.date)
            else Rfc3339.parseInstantOrNull(start?.dateTime)
        val parsedEnd =
            if (allDay) Rfc3339.parseDateOrNull(end?.date)
            else Rfc3339.parseInstantOrNull(end?.dateTime)

        val startMillis = parsedStart ?: 0L
        val endMillis = parsedEnd ?: startMillis

        if (!cancelled && startMillis == 0L) return null

        return RemoteEvent(
            remoteId = remoteId,
            etag = etag,
            title = summary.orEmpty(),
            description = description,
            location = location,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            timeZone = start?.timeZone,
            recurrence = recurrence,
            recurringRemoteId = recurringEventId,
            reminderMinutes = reminders
                ?.takeIf { !it.useDefault }
                ?.overrides
                ?.map { it.minutes }
                .orEmpty(),
            colorArgb = EVENT_COLORS[colorId],
            updatedAtMillis = Rfc3339.parseInstantOrNull(updated) ?: 0L,
            deleted = cancelled,
        )
    }

    private fun eventBody(event: RemoteEvent): JsonObject = buildJsonObject {
        put("summary", event.title)
        // null se envía explícitamente: es como se borra un campo en un PATCH.
        put("description", event.description)
        put("location", event.location)

        putJsonObject("start") { putEventTime(event.startMillis, event.allDay, event.timeZone) }
        putJsonObject("end") { putEventTime(event.endMillis, event.allDay, event.timeZone) }

        if (event.recurrence.isEmpty()) {
            put("recurrence", JsonNull)
        } else {
            putJsonArray("recurrence") { event.recurrence.forEach { add(it) } }
        }

        putJsonObject("reminders") {
            if (event.reminderMinutes.isEmpty()) {
                put("useDefault", true)
            } else {
                put("useDefault", false)
                putJsonArray("overrides") {
                    event.reminderMinutes.forEach { minutes ->
                        add(
                            buildJsonObject {
                                put("method", "popup")
                                put("minutes", minutes)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEventTime(
        millis: Long,
        allDay: Boolean,
        timeZone: String?,
    ) {
        if (allDay) {
            put("date", Rfc3339.formatDate(millis))
        } else {
            // Instante en UTC + zona aparte: inequívoco y sin depender de que
            // el formateador emita los segundos cuando son cero.
            put("dateTime", Rfc3339.formatInstant(millis))
            put("timeZone", timeZone ?: Rfc3339.timeZoneId())
        }
    }

    private fun GTask.toRemoteTask(): RemoteTask? {
        val remoteId = id ?: return null
        return RemoteTask(
            remoteId = remoteId,
            etag = etag,
            title = title.orEmpty(),
            notes = notes,
            dueDayMillis = Rfc3339.parseTaskDueOrNull(due),
            completedAtMillis = if (status == "completed") {
                Rfc3339.parseInstantOrNull(completed) ?: Rfc3339.parseInstantOrNull(updated)
            } else {
                null
            },
            position = position,
            parentRemoteId = parent,
            updatedAtMillis = Rfc3339.parseInstantOrNull(updated) ?: 0L,
            deleted = deleted,
        )
    }

    private fun taskBody(task: RemoteTask): JsonObject = buildJsonObject {
        put("title", task.title)
        put("notes", task.notes)
        put("due", task.dueDayMillis?.let { Rfc3339.formatTaskDue(it) })
        // Solo `status`. Google deriva `completed` de él; mandar los dos es la
        // vía rápida a un 400.
        put("status", if (task.completedAtMillis != null) "completed" else "needsAction")
        // `parent` y `position` no son editables por PATCH: requieren
        // tasks.move. El orden manual de la v1 es local. Ver README.
    }

    // --- Errores ------------------------------------------------------------

    private inline fun <T> runApi(block: () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            throw e.toBackendException()
        } catch (e: IOException) {
            throw e.toBackendException()
        }

    private fun HttpException.toBackendException(): Exception = when (code()) {
        401, 403 -> BackendAuthException(text(R.string.backend_credentials_rejected, code()), this)
        410 -> SyncCursorExpiredException(text(R.string.backend_cursor_expired))
        408, 429, in 500..599 -> BackendTransientException(text(R.string.backend_responded, code()), this)
        else -> BackendTransientException(text(R.string.backend_responded_message, code(), message()), this)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = Graph.appContext.getString(id, *args)

    private fun IOException.toBackendException(): Exception {
        // El interceptor envuelve los fallos de autorización en IOException
        // porque OkHttp no deja escapar otra cosa desde un interceptor.
        val root = cause
        return if (root is BackendAuthException) root
        else BackendTransientException(message ?: text(R.string.backend_network_error), this)
    }

    companion object {
        const val BACKEND_ID = "google"

        /**
         * Los once colores de evento de Google Calendar, por `colorId`. Son
         * fijos para todas las cuentas (endpoint `colors`, sección `event`),
         * así que no hace falta pedirlos. En el orden de la app de Google:
         * lavanda, salvia, uva, flamenco, plátano, mandarina, pavo real,
         * grafito, arándano, albahaca, tomate.
         */
        private val EVENT_COLORS: Map<String, Int> = mapOf(
            "1" to 0xFFA4BDFC.toInt(),
            "2" to 0xFF7AE7BF.toInt(),
            "3" to 0xFFDBADFF.toInt(),
            "4" to 0xFFFF887C.toInt(),
            "5" to 0xFFFBD75B.toInt(),
            "6" to 0xFFFFB878.toInt(),
            "7" to 0xFF46D6DB.toInt(),
            "8" to 0xFFE1E1E1.toInt(),
            "9" to 0xFF5484ED.toInt(),
            "10" to 0xFF51B749.toInt(),
            "11" to 0xFFDC2127.toInt(),
        )

        private fun parseHexColor(hex: String?): Int? =
            hex?.takeIf { it.startsWith("#") }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
    }
}
