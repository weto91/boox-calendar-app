package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncEntityType {
    const val EVENT = "event"
    const val TASK = "task"
}

/**
 * Correspondencia entre una entidad local y su identidad en un backend.
 *
 * Es la única fuente de verdad del `remote_id`: las filas de `events` y `tasks`
 * no lo llevan duplicado. La clave (entityType, localId, backendId) permite que
 * la misma tarea exista mañana en Google y en CalDAV a la vez sin tocar el
 * esquema.
 */
@Entity(
    tableName = "sync_map",
    indices = [
        Index(value = ["entityType", "localId", "backendId"], unique = true),
        Index(value = ["backendId", "entityType", "remoteId"], unique = true),
    ],
)
data class SyncMapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val localId: Long,
    val backendId: String,
    val remoteId: String,
    val etag: String? = null,
    val lastSyncedAt: Long = 0,
)
