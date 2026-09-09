package com.weto.booxcal.ui.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.repository.EventRepository
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.domain.usecase.buildDayBuckets
import com.weto.booxcal.ink.InkNotebook
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.util.monthGridWeeks
import com.weto.booxcal.util.startOfMonthGrid
import com.weto.booxcal.util.toDayMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import com.weto.booxcal.R
import androidx.annotation.StringRes

/**
 * Los cuatro accesos del módulo inferior, en el orden de la app nativa:
 * escribir, todos los recordatorios, lo escrito hoy y lo que reclama atención.
 *
 * Llevan aquí su icono y su color porque el rótulo ya vivía aquí, y separarlos
 * solo conseguiría que un día el color de "recordatorio" dejara de ser el mismo
 * en la pestaña y en el menú.
 */
enum class HomeModule(@StringRes val title: Int, val glyph: Glyph, val accent: Color) {
    NOTE(R.string.home_module_note, Glyph.TabMemo, Accent.Note),
    REMINDERS(R.string.common_reminders, Glyph.TabReminder, Accent.Reminder),
    TODAY_NOTES(R.string.home_module_day_notes, Glyph.TabNotebook, Accent.Agenda),
    DUE(R.string.home_module_due, Glyph.TabAgenda, Accent.Today),
}

/** Un día con contenido dentro de la agenda de los próximos días. */
data class AgendaDay(
    val date: LocalDate,
    val entries: List<EventWithCalendar>,
)

data class HomeUiState(
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val gridStart: LocalDate = LocalDate.now(),
    val gridWeeks: Int = 6,
    val buckets: Map<LocalDate, DayBucket> = emptyMap(),
    val module: HomeModule = HomeModule.NOTE,
    /** Todos los pendientes, con fecha o sin ella. */
    val allReminders: List<TaskWithList> = emptyList(),
    /** Completados recientes, para la sección plegable. */
    val completedReminders: List<TaskWithList> = emptyList(),
    /** Pendientes vencidos o que vencen hoy. */
    val dueReminders: List<TaskWithList> = emptyList(),
    /** Eventos de hoy, para la pestaña de lo que reclama atención. */
    val todayEvents: List<EventWithCalendar> = emptyList(),
    /** Notas manuscritas del día elegido, la más reciente primero. */
    val dayNotes: List<InkNoteEntity> = emptyList(),
    /** Recordatorios que vencen el día elegido (no los vencidos de antes), pendientes primero. */
    val dayTasks: List<TaskWithList> = emptyList(),
    val agenda: List<AgendaDay> = emptyList(),
    /** La nota que edita el lienzo rápido. Null: hoja en blanco. */
    val currentNote: InkNoteEntity? = null,
    /**
     * Cambia cada vez que el lienzo rápido tiene que cargar otra cosa: otro
     * día, otra nota o una hoja nueva. Es la clave del cuaderno en pantalla.
     */
    val sheet: Int = 0,
    val notes: List<InkNoteEntity> = emptyList(),
    val ocrLanguageTag: String = "es",
    val syncing: Boolean = false,
    val lastSyncError: String? = null,
    val lastSyncAt: Long = 0,
    /** Hay una cuenta de Google con permiso dado. Sin ella no sube nada. */
    val accountConnected: Boolean = false,
    val loaded: Boolean = false,
) {
    fun bucket(date: LocalDate): DayBucket = buckets[date] ?: DayBucket(date)

    /** Eventos del día elegido: primero las franjas, luego los de hora. */
    val dayEvents: List<EventWithCalendar>
        get() = bucket(selected).let { it.banners + it.timed }
}

private data class HomeNav(
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    val module: HomeModule = HomeModule.NOTE,
    /** Nota que edita el lienzo rápido. Null: la más reciente del día, si la hay. */
    val noteId: Long? = null,
    /** Hoja en blanco pedida a propósito: no se recae en la nota más reciente. */
    val blankNote: Boolean = false,
    /** Sube al cambiar de día, de nota o de hoja: recarga el lienzo. */
    val sheet: Int = 0,
) {
    fun turnTo(noteId: Long?, blank: Boolean, selected: LocalDate = this.selected, anchor: LocalDate = this.anchor) =
        copy(anchor = anchor, selected = selected, noteId = noteId, blankNote = blank, sheet = sheet + 1)
}

/** Días que abarca la pestaña de próximos. */
private const val AGENDA_DAYS = 14L

/** Lo que se espera desde el último trazo antes de escribir en la base. */
private const val NOTE_SAVE_DEBOUNCE_MILLIS = 500L

/** Cuántas completadas se enseñan bajo el desplegable. */
private const val COMPLETED_SHOWN = 50

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val eventRepository: EventRepository = Graph.eventRepository,
    private val taskRepository: TaskRepository = Graph.taskRepository,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val nav = MutableStateFlow(HomeNav())

    val uiState: StateFlow<HomeUiState> =
        combine(nav, settingsStore.settings) { navState, settings -> navState to settings }
            .flatMapLatest { (navState, settings) ->
                val gridStart = navState.anchor.startOfMonthGrid(settings.weekStart)
                val weeks = navState.anchor.monthGridWeeks(settings.weekStart)
                val gridEnd = gridStart.plusWeeks(weeks.toLong())

                // El mes visible: alimenta la rejilla y los puntos de cada día.
                val month = combine(
                    eventRepository.observeRange(gridStart, gridEnd, zone),
                    if (settings.showTasksInCalendar) {
                        taskRepository.observeDueInRange(gridStart, gridEnd)
                    } else {
                        flowOf(emptyList())
                    },
                    inkNoteRepository.observeAnchoredDays(),
                ) { events, tasks, inkDays ->
                    buildDayBuckets(gridStart, gridEnd, events, tasks, inkDays, zone)
                }

                val today = LocalDate.now()
                // El módulo inferior: no depende del mes que se esté mirando.
                val module = combine(
                    taskRepository.observeVisible(),
                    inkNoteRepository.observeAll(),
                    eventRepository.observeRange(today, today.plusDays(AGENDA_DAYS), zone),
                ) { tasks, notes, upcoming ->
                    Triple(tasks, notes, upcoming)
                }

                combine(month, module, syncScheduler.observeRunning()) { buckets, mod, syncing ->
                    val (tasks, notes, upcoming) = mod
                    val selectedDayMillis = navState.selected.toDayMillis()
                    val dayNotes = notes
                        .filter { it.anchorDayMillis == selectedDayMillis }
                        .sortedByDescending { it.updatedAt }
                    val currentNote = when {
                        navState.blankNote -> null
                        navState.noteId != null -> notes.firstOrNull { it.id == navState.noteId }
                        else -> dayNotes.firstOrNull()
                    }

                    HomeUiState(
                        anchor = navState.anchor,
                        selected = navState.selected,
                        weekStart = settings.weekStart,
                        gridStart = gridStart,
                        gridWeeks = weeks,
                        buckets = buckets,
                        module = navState.module,
                        allReminders = pending(tasks),
                        completedReminders = completed(tasks),
                        dueReminders = due(tasks, today),
                        todayEvents = eventsOn(upcoming, today),
                        dayNotes = dayNotes,
                        dayTasks = tasksOn(tasks, selectedDayMillis),
                        agenda = buildAgenda(upcoming, today),
                        currentNote = currentNote,
                        sheet = navState.sheet,
                        notes = notes,
                        ocrLanguageTag = settings.ocrLanguageTag,
                        syncing = syncing,
                        lastSyncError = settings.lastSyncError,
                        lastSyncAt = settings.lastSyncAt,
                        accountConnected = Graph.googleAuth.isAuthorized,
                        loaded = true,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // --- Acciones -----------------------------------------------------------

    fun select(date: LocalDate) {
        // Elegir un día de otro mes arrastra la rejilla a ese mes: si no, el día
        // seleccionado quedaría fuera de la vista al instante.
        moveTo(anchor = date, selected = date)
    }

    fun previousMonth() {
        val anchor = nav.value.anchor.minusMonths(1)
        moveTo(anchor = anchor, selected = clampToMonth(nav.value.selected, anchor))
    }

    fun nextMonth() {
        val anchor = nav.value.anchor.plusMonths(1)
        moveTo(anchor = anchor, selected = clampToMonth(nav.value.selected, anchor))
    }

    fun goToToday() {
        val today = LocalDate.now()
        moveTo(anchor = today, selected = today)
    }

    /** Cambiar de día es cambiar de hoja: lo pendiente de la anterior se guarda ya. */
    private fun moveTo(anchor: LocalDate, selected: LocalDate) {
        leaveNote()
        nav.value = nav.value.turnTo(noteId = null, blank = false, selected = selected, anchor = anchor)
    }

    /** El botón de hoja en blanco: una nota nueva para el día elegido. */
    fun newSheet() {
        leaveNote()
        nav.value = nav.value.turnTo(noteId = null, blank = true).copy(module = HomeModule.NOTE)
    }

    /** «Editar» desde la lista: esa nota pasa al lienzo rápido. */
    fun editNote(id: Long) {
        leaveNote()
        nav.value = nav.value.turnTo(noteId = id, blank = false).copy(module = HomeModule.NOTE)
    }

    fun renameNote(id: Long, title: String) {
        viewModelScope.launch { inkNoteRepository.setTitle(id, title) }
    }

    fun deleteNote(id: Long) {
        flushPendingSave()
        viewModelScope.launch {
            noteMutex.withLock { inkNoteRepository.delete(id) }
            if (nav.value.noteId == id) nav.value = nav.value.turnTo(noteId = null, blank = true)
        }
    }

    fun setModule(module: HomeModule) {
        nav.value = nav.value.copy(module = module)
    }

    /**
     * La nota del lienzo se convirtió entera en un evento o un recordatorio
     * y ya se ha guardado: la nota sobra. Se borra del todo (también de
     * Drive, en la próxima subida) y la hoja se queda en blanco.
     */
    fun consumeCurrentNote() {
        flushPendingSave()
        val captured = nav.value
        viewModelScope.launch {
            noteMutex.withLock {
                val id = resolveNoteId(captured) ?: return@withLock
                inkNoteRepository.delete(id)
                noteModified = true
                if (nav.value.sheet == captured.sheet) nav.value = nav.value.turnTo(noteId = null, blank = true)
            }
            leaveNote()
        }
    }

    fun toggleTask(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            taskRepository.setCompleted(taskId, completed)
            syncScheduler.syncSoon()
        }
    }

    private var pendingNoteSave: Job? = null
    private var pendingNote: Pair<HomeNav, InkNotebook>? = null

    /** Un guardado cada vez: dos seguidos sobre una hoja nueva crearían dos notas. */
    private val noteMutex = Mutex()

    /**
     * Guarda la nota manuscrita que hay en el lienzo rápido.
     *
     * El lienzo avisa en cada trazo y aquí se espera medio segundo antes de
     * tocar la base: escribir una fila de Room por letra no aporta seguridad y
     * sí un parpadeo, porque el flujo vuelve y recompone el módulo entero.
     *
     * Se anota a qué hoja pertenece lo que llega: si entre medias se cambia
     * de día o de nota, lo pendiente se guarda donde tocaba, no donde se está.
     */
    fun saveDayNote(notebook: InkNotebook) {
        val captured = nav.value
        pendingNoteSave?.cancel()
        pendingNote = captured to notebook
        pendingNoteSave = viewModelScope.launch {
            delay(NOTE_SAVE_DEBOUNCE_MILLIS)
            pendingNote = null
            withContext(NonCancellable) { persistNote(captured, notebook) }
        }
    }

    /** Antes de cambiar de hoja: lo que esperaba su medio segundo se guarda ya. */
    private fun flushPendingSave() {
        val pending = pendingNote ?: return
        pendingNoteSave?.cancel()
        pendingNote = null
        viewModelScope.launch {
            withContext(NonCancellable) { persistNote(pending.first, pending.second) }
        }
    }

    /** La hoja del lienzo cambió desde la última subida a Drive. */
    private var noteModified = false

    /**
     * Se deja la hoja: al cambiar de día o de nota, al salir de la pantalla
     * o al dejar la app. Lo pendiente se guarda ya y, si la nota cambió, sube
     * a Drive en ese momento en vez de esperar al respiro de medio minuto.
     * El cerrojo garantiza que la subida va detrás del guardado.
     */
    fun leaveNote() {
        flushPendingSave()
        viewModelScope.launch {
            noteMutex.withLock {
                if (!noteModified) return@withLock
                noteModified = false
                syncScheduler.syncNotesNow()
            }
        }
    }

    /**
     * A qué nota va lo escrito: la que ya tenía la hoja; si la hoja no pidió
     * ir en blanco, la más reciente del día; y si no, ninguna (se crea).
     */
    private suspend fun resolveNoteId(target: HomeNav): Long? = when {
        target.noteId != null -> target.noteId
        target.blankNote -> null
        else -> inkNoteRepository.observeForDay(target.selected.toDayMillis()).first()
            .maxByOrNull { it.updatedAt }?.id
    }

    private suspend fun persistNote(captured: HomeNav, notebook: InkNotebook) = noteMutex.withLock {
        val live = nav.value
        // Si la hoja sigue siendo la misma se lee el estado vivo: el primer
        // guardado le puso id, y el segundo tiene que verlo para no crear otra.
        val sameSheet = live.sheet == captured.sheet
        val target = if (sameSheet) live else captured
        val dayMillis = target.selected.toDayMillis()
        val existingId = resolveNoteId(target)

        if (notebook.isEmpty) {
            if (existingId != null) {
                inkNoteRepository.delete(existingId)
                noteModified = true
                // Se sigue en una hoja en blanco, no se salta a otra nota del día.
                if (sameSheet) nav.value = nav.value.copy(noteId = null, blankNote = true)
            }
            return@withLock
        }

        val existing = existingId?.let { inkNoteRepository.loadNotebook(it)?.first }
        val id = inkNoteRepository.saveNotebook(
            id = existing?.id,
            notebook = notebook,
            recognizedText = existing?.recognizedText,
            anchorDayMillis = dayMillis,
        )
        noteModified = true
        if (sameSheet) nav.value = nav.value.copy(noteId = id, blankNote = false)
    }

    /** Transcripción de la nota en el lienzo, elegida desde el lazo. */
    fun setDayNoteText(text: String) {
        val captured = nav.value
        viewModelScope.launch {
            noteMutex.withLock {
                val id = resolveNoteId(captured) ?: return@withLock
                val (existing, loaded) = inkNoteRepository.loadNotebook(id) ?: return@withLock
                inkNoteRepository.saveNotebook(
                    id = existing.id,
                    notebook = loaded,
                    recognizedText = listOfNotNull(existing.recognizedText, text)
                        .joinToString("\n")
                        .ifBlank { null },
                    anchorDayMillis = existing.anchorDayMillis,
                )
            }
        }
    }

    fun syncNow() = syncScheduler.syncNow()

    private fun clampToMonth(selected: LocalDate, anchor: LocalDate): LocalDate =
        anchor.withDayOfMonth(minOf(selected.dayOfMonth, anchor.lengthOfMonth()))

    // --- Derivaciones -------------------------------------------------------

    /** Pendientes, primero los que tienen fecha más cercana; los sin fecha, al final. */
    private fun pending(tasks: List<TaskWithList>): List<TaskWithList> = tasks
        .filter { it.task.completedAt == null }
        .sortedWith(
            compareBy(
                { it.task.dueDayMillis ?: Long.MAX_VALUE },
                { it.task.sortIndex },
                { it.task.id },
            )
        )

    private fun completed(tasks: List<TaskWithList>): List<TaskWithList> = tasks
        .filter { it.task.completedAt != null }
        .sortedByDescending { it.task.completedAt }
        .take(COMPLETED_SHOWN)

    /**
     * Lo vencido y lo de hoy. Un recordatorio atrasado que no aparece en ninguna
     * pantalla es un recordatorio perdido, así que se acumulan.
     */
    private fun due(tasks: List<TaskWithList>, today: LocalDate): List<TaskWithList> {
        val limit = today.toDayMillis()
        return pending(tasks).filter { row ->
            val dueDay = row.task.dueDayMillis ?: return@filter false
            dueDay <= limit
        }
    }

    /** Los recordatorios de un día concreto: pendientes primero, luego los hechos. */
    private fun tasksOn(tasks: List<TaskWithList>, dayMillis: Long): List<TaskWithList> = tasks
        .filter { it.task.dueDayMillis == dayMillis }
        .sortedWith(
            compareBy(
                { it.task.completedAt != null },
                { it.task.sortIndex },
                { it.task.id },
            )
        )

    private fun eventsOn(
        upcoming: List<EventWithCalendar>,
        today: LocalDate,
    ): List<EventWithCalendar> =
        buildAgenda(upcoming, today).firstOrNull { it.date == today }?.entries.orEmpty()

    private fun buildAgenda(
        upcoming: List<EventWithCalendar>,
        today: LocalDate,
    ): List<AgendaDay> {
        val buckets = buildDayBuckets(
            from = today,
            toExclusive = today.plusDays(AGENDA_DAYS),
            events = upcoming,
            tasks = emptyList(),
            inkNoteDays = emptySet(),
            zone = zone,
        )
        return buckets.values
            .filter { it.banners.isNotEmpty() || it.timed.isNotEmpty() }
            .map { AgendaDay(it.date, it.banners + it.timed) }
    }
}
