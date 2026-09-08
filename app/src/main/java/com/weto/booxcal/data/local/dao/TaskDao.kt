package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.weto.booxcal.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

data class TaskWithList(
    @Embedded val task: TaskEntity,
    val listName: String,
    val listColorArgb: Int,
)

@Dao
interface TaskDao {

    @Query(
        """
        SELECT tasks.*,
               task_lists.name AS listName,
               task_lists.colorArgb AS listColorArgb
        FROM tasks
        JOIN task_lists ON task_lists.id = tasks.taskListId
        WHERE tasks.deleted = 0
          AND tasks.purged = 0
          AND task_lists.isVisible = 1
        ORDER BY tasks.completedAt IS NOT NULL,
                 tasks.dueDayMillis IS NULL,
                 tasks.dueDayMillis ASC,
                 tasks.sortIndex ASC,
                 tasks.id ASC
        """
    )
    fun observeVisible(): Flow<List<TaskWithList>>

    /** Tareas con vencimiento dentro del rango, para pintarlas en el calendario. */
    @Query(
        """
        SELECT tasks.*,
               task_lists.name AS listName,
               task_lists.colorArgb AS listColorArgb
        FROM tasks
        JOIN task_lists ON task_lists.id = tasks.taskListId
        WHERE tasks.deleted = 0
          AND tasks.purged = 0
          AND task_lists.isVisible = 1
          AND tasks.dueDayMillis IS NOT NULL
          AND tasks.dueDayMillis >= :fromDayMillis
          AND tasks.dueDayMillis < :toDayMillis
        ORDER BY tasks.completedAt IS NOT NULL, tasks.sortIndex ASC, tasks.id ASC
        """
    )
    fun observeDueInRange(fromDayMillis: Long, toDayMillis: Long): Flow<List<TaskWithList>>

    @Query(
        """
        SELECT tasks.*,
               task_lists.name AS listName,
               task_lists.colorArgb AS listColorArgb
        FROM tasks
        JOIN task_lists ON task_lists.id = tasks.taskListId
        WHERE tasks.id = :id
        """
    )
    fun observeById(id: Long): Flow<TaskWithList?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    /** Incluye las purgadas: si el usuario busca algo, quiere encontrarlo. */
    @Query(
        """
        SELECT tasks.*,
               task_lists.name AS listName,
               task_lists.colorArgb AS listColorArgb
        FROM tasks
        JOIN task_lists ON task_lists.id = tasks.taskListId
        WHERE tasks.deleted = 0
          AND task_lists.isVisible = 1
          AND (tasks.title LIKE '%' || :query || '%'
               OR IFNULL(tasks.notes, '') LIKE '%' || :query || '%')
        ORDER BY tasks.completedAt IS NOT NULL, tasks.dueDayMillis IS NULL, tasks.dueDayMillis DESC
        LIMIT 100
        """
    )
    suspend fun search(query: String): List<TaskWithList>

    @Query("SELECT * FROM tasks WHERE taskListId = :listId AND (dirty = 1 OR deleted = 1)")
    suspend fun getPending(listId: Long): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE dirty = 1 OR deleted = 1")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT IFNULL(MIN(sortIndex), 0) - 1 FROM tasks WHERE taskListId = :listId")
    suspend fun nextTopSortIndex(listId: Long): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    /**
     * El orden manual es local (§7): Google Tasks solo lo acepta vía tasks.move,
     * fuera del alcance de la v1. Por eso no marca `dirty`: subir la tarea
     * entera por un reordenamiento sería tráfico sin efecto.
     */
    @Query("UPDATE tasks SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Long)

    /** Igual que [EventDao.moveToCalendar], para las tareas creadas sin cuenta. */
    @Query(
        "UPDATE tasks SET taskListId = :to, dirty = 1, updatedAt = :now " +
            "WHERE taskListId = :from AND deleted = 0"
    )
    suspend fun moveToList(from: Long, to: Long, now: Long): Int

    @Query("UPDATE tasks SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: Long, now: Long)

    @Query("UPDATE tasks SET dirty = 0 WHERE id = :id")
    suspend fun clearDirty(id: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /**
     * Purga por retención (§9 del scope).
     *
     *   purge_at = (due IS NULL ? completed : MAX(due, completed)) + retención
     *
     * El MAX es lo que evita que una tarea vencida hace meses y completada hoy
     * desaparezca en el acto. Marca `purged`, no borra: borrar haría que la
     * siguiente sincronización la bajara otra vez.
     */
    @Query(
        """
        UPDATE tasks SET purged = 1
        WHERE deleted = 0
          AND purged = 0
          AND completedAt IS NOT NULL
          AND (MAX(completedAt, IFNULL(dueDayMillis, completedAt)) + :retentionMillis) <= :nowMillis
        """
    )
    suspend fun purgeExpired(retentionMillis: Long, nowMillis: Long): Int

    /** Al cambiar la retención a un valor mayor, lo purgado vuelve a la vista. */
    @Query("UPDATE tasks SET purged = 0 WHERE purged = 1")
    suspend fun unpurgeAll(): Int

    @Query("DELETE FROM tasks WHERE taskListId = :listId AND dirty = 0 AND deleted = 0")
    suspend fun deleteCleanRows(listId: Long)
}
