package com.weto.booxcal.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.repository.EventRepository
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.model.CalendarViewMode
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.domain.usecase.buildDayBuckets
import com.weto.booxcal.util.monthGridWeeks
import com.weto.booxcal.util.startOfMonthGrid
import com.weto.booxcal.util.startOfWeek
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

data class CalendarUiState(
    val mode: CalendarViewMode = CalendarViewMode.MONTH,
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val rangeStart: LocalDate = LocalDate.now(),
    val rangeEnd: LocalDate = LocalDate.now().plusDays(1),
    val buckets: Map<LocalDate, DayBucket> = emptyMap(),
    val showTasks: Boolean = true,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastSyncError: String? = null,
) {
    fun bucket(date: LocalDate): DayBucket = buckets[date] ?: DayBucket(date)
}

private data class NavState(
    val mode: CalendarViewMode = CalendarViewMode.MONTH,
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    initialMode: CalendarViewMode = CalendarViewMode.MONTH,
    initialDate: LocalDate = LocalDate.now(),
    private val eventRepository: EventRepository = Graph.eventRepository,
    private val taskRepository: TaskRepository = Graph.taskRepository,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val nav = MutableStateFlow(
        NavState(mode = initialMode, anchor = initialDate, selected = initialDate)
    )

    val uiState: StateFlow<CalendarUiState> =
        combine(nav, settingsStore.settings) { navState, settings -> navState to settings }
            .flatMapLatest { (navState, settings) ->
                val range = rangeFor(navState.mode, navState.anchor, settings.weekStart)

                combine(
                    eventRepository.observeRange(range.first, range.second, zone),
                    if (settings.showTasksInCalendar) {
                        taskRepository.observeDueInRange(range.first, range.second)
                    } else {
                        flowOf(emptyList())
                    },
                    inkNoteRepository.observeAnchoredDays(),
                    syncScheduler.observeRunning(),
                ) { events, tasks, inkDays, syncing ->
                    CalendarUiState(
                        mode = navState.mode,
                        anchor = navState.anchor,
                        selected = navState.selected,
                        weekStart = settings.weekStart,
                        rangeStart = range.first,
                        rangeEnd = range.second,
                        buckets = buildDayBuckets(
                            from = range.first,
                            toExclusive = range.second,
                            events = events,
                            tasks = tasks,
                            inkNoteDays = inkDays,
                            zone = zone,
                        ),
                        showTasks = settings.showTasksInCalendar,
                        syncing = syncing,
                        lastSyncAt = settings.lastSyncAt,
                        lastSyncError = settings.lastSyncError,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun setMode(mode: CalendarViewMode) {
        nav.value = nav.value.copy(mode = mode, anchor = nav.value.selected)
    }

    fun select(date: LocalDate) {
        nav.value = nav.value.copy(selected = date, anchor = date)
    }

    fun goToToday() {
        val today = LocalDate.now()
        nav.value = nav.value.copy(anchor = today, selected = today)
    }

    fun previous() = shift(-1)

    fun next() = shift(1)

    private fun shift(direction: Long) {
        val current = nav.value
        val anchor = when (current.mode) {
            CalendarViewMode.MONTH -> current.anchor.plusMonths(direction)
            CalendarViewMode.WEEK -> current.anchor.plusWeeks(direction)
            CalendarViewMode.DAY -> current.anchor.plusDays(direction)
        }
        // Al cambiar de página el día seleccionado se mueve con ella, para que
        // "crear evento" no aterrice en un mes que ya no se está viendo.
        val selected = when (current.mode) {
            CalendarViewMode.MONTH -> anchor.withDayOfMonth(
                minOf(current.selected.dayOfMonth, anchor.lengthOfMonth())
            )
            else -> anchor
        }
        nav.value = current.copy(anchor = anchor, selected = selected)
    }

    fun syncNow() = syncScheduler.syncNow()

    fun toggleTask(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            taskRepository.setCompleted(taskId, completed)
            syncScheduler.syncSoon()
        }
    }

    private fun rangeFor(
        mode: CalendarViewMode,
        anchor: LocalDate,
        weekStart: DayOfWeek,
    ): Pair<LocalDate, LocalDate> = when (mode) {
        CalendarViewMode.MONTH -> {
            val start = anchor.startOfMonthGrid(weekStart)
            start to start.plusWeeks(anchor.monthGridWeeks(weekStart).toLong())
        }

        CalendarViewMode.WEEK -> {
            val start = anchor.startOfWeek(weekStart)
            start to start.plusWeeks(1)
        }

        CalendarViewMode.DAY -> anchor to anchor.plusDays(1)
    }
}
