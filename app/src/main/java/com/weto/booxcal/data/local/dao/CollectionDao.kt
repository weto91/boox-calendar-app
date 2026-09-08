package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.TaskListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {

    @Query("SELECT * FROM calendars ORDER BY isPrimary DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE isVisible = 1")
    fun observeVisible(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars")
    suspend fun getAll(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun getById(id: Long): CalendarEntity?

    @Query("SELECT * FROM calendars WHERE backendId = :backendId AND remoteId = :remoteId")
    suspend fun getByRemote(backendId: String, remoteId: String): CalendarEntity?

    @Query(
        "SELECT * FROM calendars WHERE isWritable = 1 " +
            "ORDER BY isPrimary DESC, name COLLATE NOCASE LIMIT 1"
    )
    suspend fun getDefaultWritable(): CalendarEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(calendar: CalendarEntity): Long

    @Update
    suspend fun update(calendar: CalendarEntity)

    @Query("UPDATE calendars SET isVisible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE calendars SET syncToken = :token, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun setSyncState(id: Long, token: String?, syncedAt: Long)

    @Query("DELETE FROM calendars WHERE backendId = :backendId AND remoteId NOT IN (:keepRemoteIds)")
    suspend fun deleteMissing(backendId: String, keepRemoteIds: List<String>)

    @Query("DELETE FROM calendars WHERE backendId = :backendId")
    suspend fun deleteAllForBackend(backendId: String)
}

@Dao
interface TaskListDao {

    @Query("SELECT * FROM task_lists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists WHERE isVisible = 1")
    fun observeVisible(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists")
    suspend fun getAll(): List<TaskListEntity>

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun getById(id: Long): TaskListEntity?

    @Query("SELECT * FROM task_lists WHERE backendId = :backendId AND remoteId = :remoteId")
    suspend fun getByRemote(backendId: String, remoteId: String): TaskListEntity?

    @Query("SELECT * FROM task_lists ORDER BY id LIMIT 1")
    suspend fun getDefault(): TaskListEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: TaskListEntity): Long

    @Update
    suspend fun update(list: TaskListEntity)

    @Query("UPDATE task_lists SET isVisible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE task_lists SET lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun setLastSyncedAt(id: Long, syncedAt: Long)

    @Query("DELETE FROM task_lists WHERE backendId = :backendId AND remoteId NOT IN (:keepRemoteIds)")
    suspend fun deleteMissing(backendId: String, keepRemoteIds: List<String>)

    @Query("DELETE FROM task_lists WHERE backendId = :backendId")
    suspend fun deleteAllForBackend(backendId: String)
}
