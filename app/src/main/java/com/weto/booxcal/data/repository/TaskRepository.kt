package com.weto.booxcal.data.repository

import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.SyncEntityType
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class TaskRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val taskDao = db.taskDao()
    private val taskListDao = db.taskListDao()
    private val syncMapDao = db.syncMapDao()

    fun observeVisible(): Flow<List<TaskWithList>> = taskDao.observeVisible()

    fun observeDueInRange(from: LocalDate, toExclusive: LocalDate): Flow<List<TaskWithList>> =
        taskDao.observeDueInRange(
            from.toEpochDay() * MILLIS_PER_DAY,
            toExclusive.toEpochDay() * MILLIS_PER_DAY,
        )

    fun observeById(id: Long): Flow<TaskWithList?> = taskDao.observeById(id)

    suspend fun getById(id: Long): TaskEntity? = taskDao.getById(id)

    suspend fun defaultTaskListId(preferred: Long): Long? {
        if (preferred != 0L && taskListDao.getById(preferred) != null) return preferred
        return taskListDao.getDefault()?.id
    }

    suspend fun create(task: TaskEntity): Long {
        val sortIndex = taskDao.nextTopSortIndex(task.taskListId)
        return taskDao.insert(
            task.copy(
                id = 0,
                sortIndex = sortIndex,
                dirty = true,
                deleted = false,
                purged = false,
                updatedAt = now(),
            )
        )
    }

    suspend fun update(task: TaskEntity) {
        taskDao.update(task.copy(dirty = true, updatedAt = now()))
    }

    suspend fun setCompleted(id: Long, completed: Boolean) {
        val task = taskDao.getById(id) ?: return
        taskDao.update(
            task.copy(
                completedAt = if (completed) now() else null,
                // Al reabrir una tarea purgada hay que devolverla a la vista.
                purged = false,
                dirty = true,
                updatedAt = now(),
            )
        )
    }

    suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> taskDao.setSortIndex(id, index * 100L) }
    }

    suspend fun delete(id: Long) {
        val task = taskDao.getById(id) ?: return
        val backendId = taskListDao.getById(task.taskListId)?.backendId.orEmpty()
        val mapped = syncMapDao.forLocal(SyncEntityType.TASK, id, backendId)
        if (mapped == null) {
            taskDao.deleteRow(id)
            syncMapDao.deleteAllForLocal(SyncEntityType.TASK, id)
        } else {
            taskDao.markDeleted(id, now())
        }
    }

    /**
     * Aplica la política de retención (§9). Se llama en cada sincronización y
     * al arrancar la app; es idempotente.
     *
     * Si la retención pasa a "nunca purgar", lo ya purgado vuelve: la purga es
     * una vista, no una destrucción.
     */
    /**
     * Recalcula la purga desde cero. Se llama al cambiar la retención en
     * ajustes: subir el plazo tiene que devolver a la vista lo que se ocultó
     * con el plazo anterior.
     */
    suspend fun recomputeRetention(settings: AppSettings): Int {
        taskDao.unpurgeAll()
        return applyRetention(settings)
    }

    suspend fun applyRetention(settings: AppSettings): Int {
        if (!settings.purgeEnabled) return -taskDao.unpurgeAll()
        val retentionMillis = settings.retentionDays.toLong() * MILLIS_PER_DAY
        return taskDao.purgeExpired(retentionMillis, now())
    }
}
