package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.weto.booxcal.data.local.entity.DrivePendingDeleteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteFolderDao {

    @Query("SELECT * FROM note_folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM note_folders")
    suspend fun getAll(): List<NoteFolderEntity>

    @Query("SELECT * FROM note_folders WHERE id = :id")
    suspend fun getById(id: Long): NoteFolderEntity?

    @Query(
        """
        SELECT * FROM note_folders
        WHERE ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """
    )
    suspend fun find(parentId: Long?, name: String): NoteFolderEntity?

    @Insert
    suspend fun insert(folder: NoteFolderEntity): Long

    @Query("UPDATE note_folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE note_folders SET parentId = :parentId WHERE id = :id")
    suspend fun move(id: Long, parentId: Long?)

    @Query("DELETE FROM note_folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DrivePendingDeleteDao {

    @Query("SELECT * FROM drive_pending_deletes")
    suspend fun getAll(): List<DrivePendingDeleteEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DrivePendingDeleteEntity)

    @Query("DELETE FROM drive_pending_deletes WHERE fileId = :fileId")
    suspend fun delete(fileId: String)
}
