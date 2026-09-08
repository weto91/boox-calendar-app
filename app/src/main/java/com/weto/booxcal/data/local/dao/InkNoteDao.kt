package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.weto.booxcal.data.local.entity.InkNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InkNoteDao {

    @Query("SELECT * FROM ink_notes WHERE id = :id")
    suspend fun getById(id: Long): InkNoteEntity?

    @Query("SELECT * FROM ink_notes WHERE id = :id")
    fun observeById(id: Long): Flow<InkNoteEntity?>

    @Query("SELECT * FROM ink_notes WHERE anchorDayMillis = :dayMillis ORDER BY updatedAt DESC")
    fun observeForDay(dayMillis: Long): Flow<List<InkNoteEntity>>

    @Query("SELECT DISTINCT anchorDayMillis FROM ink_notes WHERE anchorDayMillis IS NOT NULL")
    fun observeAnchoredDays(): Flow<List<Long>>

    @Query("SELECT * FROM ink_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<InkNoteEntity>>

    @Query("SELECT * FROM ink_notes")
    suspend fun getAll(): List<InkNoteEntity>

    @Query("SELECT * FROM ink_notes WHERE driveFileId = :fileId LIMIT 1")
    suspend fun getByDriveFileId(fileId: String): InkNoteEntity?

    @Query("SELECT * FROM ink_notes WHERE folderId = :folderId")
    suspend fun getInFolder(folderId: Long): List<InkNoteEntity>

    @Query("SELECT COUNT(*) FROM ink_notes WHERE folderId = :folderId")
    suspend fun countInFolder(folderId: Long): Int

    /** Notas importadas (PDF de otra app) en esas carpetas. */
    @Query("SELECT COUNT(*) FROM ink_notes WHERE folderId IN (:folderIds) AND driveFileId IS NOT NULL AND driveOwned = 0")
    suspend fun countImportedInFolders(folderIds: List<Long>): Int

    @Query(
        """
        SELECT * FROM ink_notes
        WHERE recognizedText LIKE '%' || :query || '%'
           OR title LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT 50
        """
    )
    suspend fun search(query: String): List<InkNoteEntity>

    @Query("UPDATE ink_notes SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String?)

    /** Transcripción de fondo. No toca `updatedAt`: no cambia lo escrito. */
    @Query("UPDATE ink_notes SET recognizedText = :text WHERE id = :id")
    suspend fun setRecognizedText(id: Long, text: String?)

    @Query("UPDATE ink_notes SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("UPDATE ink_notes SET folderId = :toFolderId WHERE folderId = :fromFolderId")
    suspend fun moveAll(fromFolderId: Long, toFolderId: Long?)

    @Query("UPDATE ink_notes SET tags = :tags WHERE id = :id")
    suspend fun setTags(id: Long, tags: String)

    @Query("UPDATE ink_notes SET driveMetaDirty = :dirty WHERE id = :id")
    suspend fun setDriveMetaDirty(id: Long, dirty: Boolean)

    /** Olvida cuándo se bajó cada PDF ajeno: en la siguiente pasada vuelven a bajar. */
    @Query("UPDATE ink_notes SET driveModifiedAt = NULL WHERE driveFileId IS NOT NULL AND driveOwned = 0")
    suspend fun resetImports()

    /** Las notas de esas carpetas tienen que moverse también en Drive. */
    @Query("UPDATE ink_notes SET driveMetaDirty = 1 WHERE driveFileId IS NOT NULL AND folderId IN (:folderIds)")
    suspend fun markDriveMetaDirtyInFolders(folderIds: List<Long>)

    @Query(
        """
        UPDATE ink_notes
        SET driveFileId = :fileId, driveParentId = :parentId, driveName = :name,
            driveModifiedAt = :modifiedAt, driveSyncedAt = :syncedAt, driveOwned = :owned
        WHERE id = :id
        """
    )
    suspend fun setDrive(
        id: Long,
        fileId: String?,
        parentId: String?,
        name: String?,
        modifiedAt: Long?,
        syncedAt: Long?,
        owned: Boolean,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: InkNoteEntity): Long

    @Update
    suspend fun update(note: InkNoteEntity)

    @Query("DELETE FROM ink_notes WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Notas sin dueño: ni ancladas a un día, ni en el gestor (carpeta o
     * Drive), ni referenciadas por evento o tarea.
     */
    @Query(
        """
        DELETE FROM ink_notes
        WHERE anchorDayMillis IS NULL
          AND folderId IS NULL
          AND driveFileId IS NULL
          AND tags = ''
          AND id NOT IN (SELECT inkNoteId FROM events WHERE inkNoteId IS NOT NULL)
          AND id NOT IN (SELECT inkNoteId FROM tasks WHERE inkNoteId IS NOT NULL)
        """
    )
    suspend fun deleteOrphans(): Int
}
