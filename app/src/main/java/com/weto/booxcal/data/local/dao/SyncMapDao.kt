package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.weto.booxcal.data.local.entity.SyncMapEntity

@Dao
interface SyncMapDao {

    @Query(
        "SELECT * FROM sync_map " +
            "WHERE entityType = :entityType AND localId = :localId AND backendId = :backendId"
    )
    suspend fun forLocal(entityType: String, localId: Long, backendId: String): SyncMapEntity?

    @Query(
        "SELECT * FROM sync_map " +
            "WHERE backendId = :backendId AND entityType = :entityType AND remoteId = :remoteId"
    )
    suspend fun forRemote(backendId: String, entityType: String, remoteId: String): SyncMapEntity?

    @Query(
        "SELECT * FROM sync_map " +
            "WHERE backendId = :backendId AND entityType = :entityType AND remoteId IN (:remoteIds)"
    )
    suspend fun forRemoteIds(
        backendId: String,
        entityType: String,
        remoteIds: List<String>,
    ): List<SyncMapEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SyncMapEntity): Long

    @Query(
        "DELETE FROM sync_map " +
            "WHERE entityType = :entityType AND localId = :localId AND backendId = :backendId"
    )
    suspend fun deleteForLocal(entityType: String, localId: Long, backendId: String)

    @Query("DELETE FROM sync_map WHERE entityType = :entityType AND localId = :localId")
    suspend fun deleteAllForLocal(entityType: String, localId: Long)

    @Query("DELETE FROM sync_map WHERE backendId = :backendId")
    suspend fun deleteAllForBackend(backendId: String)

    /** Tras un reinicio de colección quedan entradas apuntando a filas que ya no existen. */
    @Query("DELETE FROM sync_map WHERE entityType = 'event' AND localId NOT IN (SELECT id FROM events)")
    suspend fun deleteOrphanEventEntries()

    @Query("DELETE FROM sync_map WHERE entityType = 'task' AND localId NOT IN (SELECT id FROM tasks)")
    suspend fun deleteOrphanTaskEntries()
}
