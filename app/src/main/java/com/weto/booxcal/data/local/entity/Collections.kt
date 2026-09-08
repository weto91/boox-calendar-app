package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un calendario remoto. La colección es el ancla del backend: todo evento
 * pertenece a un calendario, y el calendario sabe de qué backend viene. Por eso
 * `backendId` vive aquí y no en cada fila de `events`.
 */
@Entity(
    tableName = "calendars",
    indices = [Index(value = ["backendId", "remoteId"], unique = true)],
)
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backendId: String,
    val remoteId: String,
    val name: String,
    val colorArgb: Int,
    val isPrimary: Boolean = false,
    val isWritable: Boolean = true,
    val isVisible: Boolean = true,
    /** Cursor de sincronización incremental (syncToken en Google Calendar). */
    val syncToken: String? = null,
    val lastSyncedAt: Long? = null,
)

@Entity(
    tableName = "task_lists",
    indices = [Index(value = ["backendId", "remoteId"], unique = true)],
)
data class TaskListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backendId: String,
    val remoteId: String,
    val name: String,
    val colorArgb: Int,
    val isVisible: Boolean = true,
    /**
     * Retención por lista. Reservado (§5 del scope): la v1 usa siempre el valor
     * global de ajustes. Si algún día se activa, `null` significa "usa el global".
     */
    val retentionDays: Int? = null,
    /** Google Tasks no tiene syncToken: se sincroniza con `updatedMin`. */
    val lastSyncedAt: Long? = null,
)
