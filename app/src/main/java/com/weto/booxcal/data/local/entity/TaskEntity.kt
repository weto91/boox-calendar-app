package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskListEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskListId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("taskListId"),
        Index("dueDayMillis"),
        Index("dirty"),
        Index("purged"),
    ],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskListId: Long,
    val title: String,
    val notes: String? = null,
    /**
     * Medianoche UTC del día de vencimiento, o null. Google Tasks solo maneja
     * fecha, nunca hora: guardar un instante aquí sería mentir sobre el modelo.
     */
    val dueDayMillis: Long? = null,
    /** Instante real de completado, o null si sigue pendiente. */
    val completedAt: Long? = null,
    /** Orden manual dentro de la lista. Menor = más arriba. */
    val sortIndex: Long = 0,
    /** `position` de Google Tasks, para reconciliar el orden con el servidor. */
    val remotePosition: String? = null,
    val parentTaskId: Long? = null,
    val inkNoteId: Long? = null,
    val updatedAt: Long,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    /**
     * Purgada por retención (§9). No se borra la fila: si se borrara, la
     * siguiente sincronización volvería a bajarla desde Google y reaparecería.
     */
    val purged: Boolean = false,
)

val TaskEntity.isCompleted: Boolean get() = completedAt != null
