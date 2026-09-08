package com.weto.booxcal.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.weto.booxcal.data.local.dao.CalendarDao
import com.weto.booxcal.data.local.dao.DrivePendingDeleteDao
import com.weto.booxcal.data.local.dao.EventDao
import com.weto.booxcal.data.local.dao.InkNoteDao
import com.weto.booxcal.data.local.dao.NoteFolderDao
import com.weto.booxcal.data.local.dao.SyncMapDao
import com.weto.booxcal.data.local.dao.TaskDao
import com.weto.booxcal.data.local.dao.TaskListDao
import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.DrivePendingDeleteEntity
import com.weto.booxcal.data.local.entity.EventEntity
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import com.weto.booxcal.data.local.entity.SyncMapEntity
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.data.local.entity.TaskListEntity
import com.weto.booxcal.ink.NoteStorage
import java.io.File

@Database(
    entities = [
        CalendarEntity::class,
        TaskListEntity::class,
        EventEntity::class,
        TaskEntity::class,
        InkNoteEntity::class,
        SyncMapEntity::class,
        NoteFolderEntity::class,
        DrivePendingDeleteEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun calendarDao(): CalendarDao
    abstract fun taskListDao(): TaskListDao
    abstract fun eventDao(): EventDao
    abstract fun taskDao(): TaskDao
    abstract fun inkNoteDao(): InkNoteDao
    abstract fun syncMapDao(): SyncMapDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun drivePendingDeleteDao(): DrivePendingDeleteDao

    companion object {
        /** v2: título de las notas manuscritas. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN title TEXT")
            }
        }

        /** v3: gestor de notas (carpetas, etiquetas) y copia en Drive. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        parentId INTEGER,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_folders_parentId ON note_folders(parentId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS drive_pending_deletes (
                        fileId TEXT PRIMARY KEY NOT NULL,
                        queuedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN folderId INTEGER")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveFileId TEXT")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveParentId TEXT")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveName TEXT")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveModifiedAt INTEGER")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveSyncedAt INTEGER")
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveOwned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ink_notes_folderId ON ink_notes(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ink_notes_driveFileId ON ink_notes(driveFileId)")
            }
        }

        /** v4: marca de nombre o carpeta pendientes de llevar a Drive. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ink_notes ADD COLUMN driveMetaDirty INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5: los cuadernos grandes pasan a fichero. Una fila de más de 2 MB
         * no se puede leer entera (límite del CursorWindow de Android) y
         * tumbaba la app al abrir; se lee a trozos con `substr`, que es lo
         * único que SQLite deja hacer con ella, se escribe el fichero y la
         * fila se queda con la referencia.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val folder = NoteStorage.dir ?: return
                val ids = mutableListOf<Long>()
                db.query("SELECT id FROM ink_notes WHERE length(strokesJson) > ${NoteStorage.INLINE_LIMIT}").use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getLong(0)
                }
                ids.forEach { id ->
                    val builder = StringBuilder()
                    var offset = 1
                    while (true) {
                        val chunk = db.query(
                            "SELECT substr(strokesJson, ?, ?) FROM ink_notes WHERE id = ?",
                            arrayOf(offset, CHUNK, id),
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        if (chunk.isNullOrEmpty()) break
                        builder.append(chunk)
                        if (chunk.length < CHUNK) break
                        offset += CHUNK
                    }
                    folder.mkdirs()
                    val name = "note-$id-migrated.json"
                    File(folder, name).writeText(builder.toString())
                    db.execSQL(
                        "UPDATE ink_notes SET strokesJson = ? WHERE id = ?",
                        arrayOf(NoteStorage.reference(name), id),
                    )
                }
            }
        }

        private const val CHUNK = 200_000

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "booxcal.db")
                // Sin fallbackToDestructiveMigration: los trazos manuscritos no
                // se pueden regenerar desde el servidor. Cada versión nueva
                // necesita su Migration explícita.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
