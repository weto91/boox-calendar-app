package com.weto.booxcal.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.usecase.TaskGrouping
import com.weto.booxcal.domain.usecase.TaskSection
import com.weto.booxcal.domain.usecase.buildTaskSections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TasksUiState(
    val sections: List<TaskSection> = emptyList(),
    val grouping: TaskGrouping = TaskGrouping.BY_DATE,
    val retentionDays: Int = AppSettings().retentionDays,
    val syncing: Boolean = false,
    val pendingCount: Int = 0,
)

class TasksViewModel(
    private val taskRepository: TaskRepository = Graph.taskRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
) : ViewModel() {

    private val grouping = MutableStateFlow(TaskGrouping.BY_DATE)

    val uiState: StateFlow<TasksUiState> = combine(
        taskRepository.observeVisible(),
        grouping,
        settingsStore.settings,
        syncScheduler.observeRunning(),
    ) { tasks, group, settings, syncing ->
        TasksUiState(
            sections = buildTaskSections(tasks, LocalDate.now(), group),
            grouping = group,
            retentionDays = settings.retentionDays,
            syncing = syncing,
            pendingCount = tasks.count { it.task.completedAt == null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun setGrouping(value: TaskGrouping) {
        grouping.value = value
    }

    fun toggle(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            taskRepository.setCompleted(taskId, completed)
            syncScheduler.syncSoon()
        }
    }

    fun delete(taskId: Long) {
        viewModelScope.launch {
            taskRepository.delete(taskId)
            syncScheduler.syncSoon()
        }
    }

    /**
     * Reordena dentro de una sección reasignando índices consecutivos. Es más
     * barato que llevar una lista enlazada y sobrevive a que dos tareas
     * bajadas del servidor compartan el mismo índice inicial.
     */
    fun move(section: List<TaskWithList>, fromIndex: Int, delta: Int) {
        val toIndex = (fromIndex + delta).coerceIn(0, section.lastIndex)
        if (toIndex == fromIndex) return
        val reordered = section.map { it.task.id }.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        viewModelScope.launch { taskRepository.reorder(reordered) }
    }

    fun syncNow() = syncScheduler.syncNow()
}
