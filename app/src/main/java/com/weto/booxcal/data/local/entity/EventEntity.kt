package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `startMillis` / `endMillis` siguen el convenio de [com.weto.booxcal.util]:
 * instante real si `allDay = false`, medianoche UTC del día si `allDay = true`.
 *
 * La identidad remota no está aquí: vive en `sync_map`, para que una misma
 * entidad local pueda existir en varios backends cuando entren CalDAV o
 * Microsoft. Ver README, sección "Identidad remota".
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEntity::class,
            parentColumns = ["id"],
            childColumns = ["calendarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("calendarId"),
        Index("startMillis"),
        Index("endMillis"),
        Index("dirty"),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val calendarId: Long,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    /** IANA time zone con la que se creó el evento. Solo informativa. */
    val timeZone: String? = null,
    /** Si es una instancia de una serie, el id remoto del evento maestro. */
    val recurringRemoteId: String? = null,
    /** Líneas RRULE/EXDATE tal cual, separadas por '\n'. Null si no se repite. */
    val recurrence: String? = null,
    /** Minutos antes del inicio para cada aviso, separados por comas. */
    val reminderMinutes: String? = null,
    val colorArgb: Int? = null,
    val inkNoteId: Long? = null,
    val updatedAt: Long,
    /** Hay cambios locales pendientes de subir. */
    val dirty: Boolean = false,
    /** Borrado lógico: la fila vive hasta que el backend confirme el borrado. */
    val deleted: Boolean = false,
)
