package com.weto.booxcal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.di.Graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

const val MIN_QUERY_LENGTH = 2

data class SearchUiState(
    val query: String = "",
    val events: List<EventWithCalendar> = emptyList(),
    val tasks: List<TaskWithList> = emptyList(),
    val notes: List<InkNoteEntity> = emptyList(),
    val searching: Boolean = false,
) {
    val isEmpty: Boolean get() = events.isEmpty() && tasks.isEmpty() && notes.isEmpty()
}

private data class SearchResults(
    val events: List<EventWithCalendar> = emptyList(),
    val tasks: List<TaskWithList> = emptyList(),
    val notes: List<InkNoteEntity> = emptyList(),
    val searching: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val database: AppDatabase = Graph.database,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * Los resultados van con retardo, el campo de texto no: se combinan dos
     * flujos en vez de uno. Si el texto viniera del flujo con `debounce`, cada
     * letra tardaría un cuarto de segundo en aparecer.
     */
    private val results: kotlinx.coroutines.flow.Flow<SearchResults> = query
        .debounce(250)
        .flatMapLatest { text ->
            flow {
                val trimmed = text.trim()
                if (trimmed.length < MIN_QUERY_LENGTH) {
                    emit(SearchResults())
                    return@flow
                }
                emit(SearchResults(searching = true))
                // `LIKE` trata '%' y '_' como comodines: se quitan para que
                // buscar "50%" no acabe devolviendo la base entera.
                val escaped = trimmed.replace("%", "").replace("_", "")
                emit(
                    SearchResults(
                        events = database.eventDao().search(escaped),
                        tasks = database.taskDao().search(escaped),
                        notes = inkNoteRepository.search(escaped),
                        searching = false,
                    )
                )
            }
        }

    val uiState: StateFlow<SearchUiState> =
        combine(query, results) { text, found ->
            SearchUiState(
                query = text,
                events = found.events,
                tasks = found.tasks,
                notes = found.notes,
                searching = found.searching,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
    }
}
