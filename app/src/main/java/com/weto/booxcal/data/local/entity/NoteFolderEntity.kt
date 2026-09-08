package com.weto.booxcal.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Carpeta del gestor de notas. `parentId` null es la raíz. Las de las notas
 * del día las crea la app sola («NOTAS DEL DÍA» y una por fecha); el resto,
 * el usuario.
 */
@Entity(
    tableName = "note_folders",
    indices = [Index("parentId")],
)
data class NoteFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val createdAt: Long,
) {
    companion object {
        const val DAY_NOTES_ROOT = "NOTAS DEL DÍA"
    }
}

/**
 * Borrados pendientes en Drive. La nota ya no existe cuando toca sincronizar,
 * así que el archivo que hay que mandar a la papelera se apunta aquí.
 */
@Entity(tableName = "drive_pending_deletes")
data class DrivePendingDeleteEntity(
    @PrimaryKey val fileId: String,
    val queuedAt: Long,
)
