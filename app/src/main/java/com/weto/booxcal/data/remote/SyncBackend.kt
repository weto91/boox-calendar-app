package com.weto.booxcal.data.remote

/**
 * Contrato genérico de sincronización.
 *
 * Nada de lo que aparece aquí puede ser específico de Google: es la condición
 * para que CalDAV (v2) y Microsoft (v3) entren después sin reescribir el motor.
 * Si algún concepto de un proveedor no cabe en estos tipos, se traduce dentro
 * del backend o se pierde — no se filtra hacia arriba.
 */
interface SyncBackend {

    /** Identificador estable, el que se guarda en `sync_map.backendId`. */
    val id: String

    val displayName: String

    suspend fun isAuthorized(): Boolean

    /** Etiqueta para mostrar en ajustes (correo de la cuenta, URL del servidor…). */
    suspend fun accountLabel(): String?

    suspend fun listCalendars(): List<RemoteCollection>

    suspend fun listTaskLists(): List<RemoteCollection>

    /**
     * @param cursor null para sincronización completa.
     * @param window ventana temporal de la sincronización completa; se ignora
     *   en las incrementales, donde manda el cursor.
     * @throws SyncCursorExpiredException si el cursor ya no vale y toca
     *   reconstruir la colección desde cero.
     */
    suspend fun pullEvents(
        collectionId: String,
        cursor: SyncCursor?,
        window: TimeWindow,
    ): RemotePage<RemoteEvent>

    suspend fun pullTasks(collectionId: String, cursor: SyncCursor?): RemotePage<RemoteTask>

    suspend fun createEvent(collectionId: String, event: RemoteEvent): RemoteEvent

    suspend fun updateEvent(collectionId: String, event: RemoteEvent): RemoteEvent

    suspend fun deleteEvent(collectionId: String, remoteId: String)

    suspend fun createTask(collectionId: String, task: RemoteTask): RemoteTask

    suspend fun updateTask(collectionId: String, task: RemoteTask): RemoteTask

    suspend fun deleteTask(collectionId: String, remoteId: String)
}

data class RemoteCollection(
    val remoteId: String,
    val name: String,
    val colorArgb: Int?,
    val isPrimary: Boolean = false,
    val isWritable: Boolean = true,
)

/**
 * Evento en términos neutros. `startMillis`/`endMillis` usan el mismo convenio
 * que la base local: instante real, o medianoche UTC del día si `allDay`.
 * `endMillis` es **exclusivo** para eventos de día completo, igual que en
 * iCalendar y en Google.
 */
data class RemoteEvent(
    val remoteId: String?,
    val etag: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val recurrence: List<String> = emptyList(),
    val recurringRemoteId: String? = null,
    val reminderMinutes: List<Int> = emptyList(),
    /** Color propio del evento, si lo tiene; si no, manda el del calendario. */
    val colorArgb: Int? = null,
    val updatedAtMillis: Long = 0,
    val deleted: Boolean = false,
)

data class RemoteTask(
    val remoteId: String?,
    val etag: String? = null,
    val title: String,
    val notes: String? = null,
    /** Medianoche UTC del día de vencimiento. Sin hora: ningún backend la respeta. */
    val dueDayMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val position: String? = null,
    val parentRemoteId: String? = null,
    val updatedAtMillis: Long = 0,
    val deleted: Boolean = false,
)

/**
 * Cursor incremental. Cada backend usa el campo que le sirve: Google Calendar
 * el `token` (syncToken), Google Tasks el `sinceMillis` (updatedMin), CalDAV
 * usará el `token` (sync-token de RFC 6578).
 */
data class SyncCursor(
    val token: String? = null,
    val sinceMillis: Long? = null,
) {
    val isEmpty: Boolean get() = token == null && sinceMillis == null
}

data class RemotePage<T>(
    val items: List<T>,
    val nextCursor: SyncCursor?,
)

data class TimeWindow(val fromMillis: Long, val toMillis: Long)

/** El cursor caducó (HTTP 410 en Google). Toca sincronización completa. */
class SyncCursorExpiredException(message: String) : Exception(message)

/** No hay credenciales válidas y no se pueden renovar sin el usuario. */
class BackendAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Error del servidor que puede tener sentido reintentar más tarde. */
class BackendTransientException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
