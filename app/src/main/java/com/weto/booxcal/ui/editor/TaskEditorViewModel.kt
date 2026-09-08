package com.weto.booxcal.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.data.local.entity.TaskListEntity
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.util.MILLIS_PER_DAY
import com.weto.booxcal.util.toDayMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TaskForm(
    val id: Long? = null,
    val title: String = "",
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val completed: Boolean = false,
    val taskListId: Long = 0,
    val inkNoteId: Long? = null,
    val inkDocument: InkDocument = InkDocument.EMPTY,
    val lists: List<TaskListEntity> = emptyList(),
    val strokeWidth: Float = 3f,
    val ocrLanguageTag: String = "es",
    val loading: Boolean = true,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = id == null
    val canSave: Boolean get() = taskListId != 0L && !loading
}

class TaskEditorViewModel(
    private val taskId: Long?,
    private val initialDueDate: LocalDate?,
    private val taskRepository: TaskRepository = Graph.taskRepository,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
    private val database: AppDatabase = Graph.database,
) : ViewModel() {

    private val _form = MutableStateFlow(TaskForm())
    val form: StateFlow<TaskForm> = _form.asStateFlow()

    private var loadedEntity: TaskEntity? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = settingsStore.settings.first()
        val lists = database.taskListDao().getAll()
        val existing = taskId?.let { taskRepository.getById(it) }
        loadedEntity = existing

        _form.value = if (existing == null) {
            TaskForm(
                dueDate = initialDueDate,
                taskListId = taskRepository.defaultTaskListId(settings.defaultTaskListId) ?: 0L,
                lists = lists,
                strokeWidth = settings.inkStrokeWidth,
                ocrLanguageTag = settings.ocrLanguageTag,
                loading = false,
            )
        } else {
            val ink = existing.inkNoteId?.let { inkNoteRepository.load(it)?.second }
            TaskForm(
                id = existing.id,
                title = existing.title,
                notes = existing.notes.orEmpty(),
                dueDate = existing.dueDayMillis?.let {
                    LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))
                },
                completed = existing.completedAt != null,
                taskListId = existing.taskListId,
                inkNoteId = existing.inkNoteId,
                inkDocument = ink ?: InkDocument.EMPTY,
                lists = lists,
                strokeWidth = settings.inkStrokeWidth,
                ocrLanguageTag = settings.ocrLanguageTag,
                loading = false,
            )
        }
    }

    fun update(transform: (TaskForm) -> TaskForm) {
        _form.value = transform(_form.value)
    }

    fun save(document: InkDocument) {
        val form = _form.value
        if (!form.canSave) return

        viewModelScope.launch {
            runCatching {
                val inkNoteId = persistInk(form, document)
                val base = loadedEntity ?: TaskEntity(
                    taskListId = form.taskListId,
                    title = "",
                    updatedAt = 0,
                )
                val entity = base.copy(
                    id = form.id ?: 0,
                    taskListId = form.taskListId,
                    title = form.title.trim(),
                    notes = form.notes.trim().takeIf { it.isNotEmpty() },
                    dueDayMillis = form.dueDate?.toDayMillis(),
                    completedAt = when {
                        !form.completed -> null
                        base.completedAt != null -> base.completedAt
                        else -> System.currentTimeMillis()
                    },
                    // Reabrir o reeditar una tarea purgada la devuelve a la vista.
                    purged = false,
                    inkNoteId = inkNoteId,
                    deleted = false,
                )
                if (form.isNew) taskRepository.create(entity) else taskRepository.update(entity)
                syncScheduler.syncNow()
            }.onSuccess {
                _form.value = _form.value.copy(saved = true)
            }.onFailure { t ->
                _form.value = _form.value.copy(error = t.message ?: "No se pudo guardar")
            }
        }
    }

    fun delete() {
        val id = _form.value.id ?: return
        viewModelScope.launch {
            taskRepository.delete(id)
            syncScheduler.syncNow()
            _form.value = _form.value.copy(deleted = true)
        }
    }

    private suspend fun persistInk(form: TaskForm, document: InkDocument): Long? {
        if (document.isEmpty) {
            form.inkNoteId?.let { inkNoteRepository.delete(it) }
            return null
        }
        return inkNoteRepository.save(
            id = form.inkNoteId,
            document = document,
            recognizedText = null,
            anchorDayMillis = null,
        )
    }
}
