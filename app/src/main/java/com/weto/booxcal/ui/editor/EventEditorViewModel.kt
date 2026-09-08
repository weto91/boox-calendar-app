package com.weto.booxcal.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.EventEntity
import com.weto.booxcal.data.repository.EventRepository
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.util.resolveDate
import com.weto.booxcal.util.resolveDateTime
import com.weto.booxcal.util.roundToNextHalfHour
import com.weto.booxcal.util.toDayMillis
import com.weto.booxcal.util.toMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class ReminderOption(val minutes: Int?, val label: String)

val REMINDER_OPTIONS = listOf(
    ReminderOption(null, "Sin aviso"),
    ReminderOption(0, "A la hora"),
    ReminderOption(10, "10 min"),
    ReminderOption(30, "30 min"),
    ReminderOption(60, "1 hora"),
    ReminderOption(1440, "1 día"),
)

data class EventForm(
    val id: Long? = null,
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val calendarId: Long = 0,
    /** Nombre y color del calendario del evento, para la vista de detalle. */
    val calendarName: String? = null,
    val calendarColorArgb: Int? = null,
    /** Color propio del evento en Google, si lo tiene; manda sobre el del calendario. */
    val colorArgb: Int? = null,
    val reminderMinutes: Int? = null,
    val inkNoteId: Long? = null,
    val inkDocument: InkDocument = InkDocument.EMPTY,
    /** Solo lectura: la v1 no crea series, pero no debe romperlas. */
    val isRecurringInstance: Boolean = false,
    val calendars: List<CalendarEntity> = emptyList(),
    val strokeWidth: Float = 3f,
    val ocrLanguageTag: String = "es",
    val loading: Boolean = true,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = id == null
    val canSave: Boolean get() = calendarId != 0L && !loading
}

class EventEditorViewModel(
    private val eventId: Long?,
    private val initialDate: LocalDate,
    private val eventRepository: EventRepository = Graph.eventRepository,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
    private val database: AppDatabase = Graph.database,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _form = MutableStateFlow(EventForm())
    val form: StateFlow<EventForm> = _form.asStateFlow()

    /** Entidad tal y como estaba en la base, para no perder lo que el formulario no muestra. */
    private var loadedEntity: EventEntity? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = settingsStore.settings.first()
        val allCalendars = database.calendarDao().getAll()
        val calendars = allCalendars.filter { it.isWritable }
        val existing = eventId?.let { eventRepository.getById(it) }
        loadedEntity = existing
        // El calendario se busca entre todos, no solo los escribibles: un
        // evento de un calendario de solo lectura también tiene nombre.
        val calendar = existing?.let { e -> allCalendars.firstOrNull { it.id == e.calendarId } }

        _form.value = if (existing == null) {
            val start = LocalTime.now().roundToNextHalfHour()
            EventForm(
                startDate = initialDate,
                startTime = start,
                endDate = initialDate,
                endTime = start.plusHours(1),
                calendarId = eventRepository.defaultCalendarId(settings.defaultCalendarId) ?: 0L,
                calendars = calendars,
                strokeWidth = settings.inkStrokeWidth,
                ocrLanguageTag = settings.ocrLanguageTag,
                loading = false,
            )
        } else {
            val startDateTime = resolveDateTime(existing.startMillis, existing.allDay, zone)
            val endDateTime = resolveDateTime(existing.endMillis, existing.allDay, zone)
            // El fin de un evento de día completo es exclusivo; al usuario se le
            // muestra el último día que ocupa.
            val endDate =
                if (existing.allDay) {
                    resolveDate(existing.endMillis, true, zone).minusDays(1)
                } else {
                    endDateTime.toLocalDate()
                }
            val ink = existing.inkNoteId?.let { inkNoteRepository.load(it)?.second }

            EventForm(
                id = existing.id,
                title = existing.title,
                description = existing.description.orEmpty(),
                location = existing.location.orEmpty(),
                allDay = existing.allDay,
                startDate = startDateTime.toLocalDate(),
                startTime = startDateTime.toLocalTime(),
                endDate = maxOf(endDate, startDateTime.toLocalDate()),
                endTime = endDateTime.toLocalTime(),
                calendarId = existing.calendarId,
                calendarName = calendar?.name,
                calendarColorArgb = calendar?.colorArgb,
                colorArgb = existing.colorArgb,
                reminderMinutes = existing.reminderMinutes
                    ?.split(',')
                    ?.firstNotNullOfOrNull { it.trim().toIntOrNull() },
                inkNoteId = existing.inkNoteId,
                inkDocument = ink ?: InkDocument.EMPTY,
                isRecurringInstance = existing.recurringRemoteId != null ||
                    existing.recurrence != null,
                calendars = calendars,
                strokeWidth = settings.inkStrokeWidth,
                ocrLanguageTag = settings.ocrLanguageTag,
                loading = false,
            )
        }
    }

    fun update(transform: (EventForm) -> EventForm) {
        _form.value = transform(_form.value).let(::normalize)
    }

    /** El fin nunca puede quedar antes del inicio: se arrastra en vez de fallar. */
    private fun normalize(form: EventForm): EventForm {
        if (form.allDay) {
            return if (form.endDate < form.startDate) form.copy(endDate = form.startDate) else form
        }
        val start = LocalDateTime.of(form.startDate, form.startTime)
        val end = LocalDateTime.of(form.endDate, form.endTime)
        if (!end.isAfter(start)) {
            val fixed = start.plusHours(1)
            return form.copy(endDate = fixed.toLocalDate(), endTime = fixed.toLocalTime())
        }
        return form
    }

    fun save(document: InkDocument) {
        val form = _form.value
        if (!form.canSave) return

        viewModelScope.launch {
            runCatching {
                val inkNoteId = persistInk(form, document)
                val entity = form.toEntity(inkNoteId)
                if (form.isNew) eventRepository.create(entity) else eventRepository.update(entity)
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
            eventRepository.delete(id)
            syncScheduler.syncNow()
            _form.value = _form.value.copy(deleted = true)
        }
    }

    private suspend fun persistInk(form: EventForm, document: InkDocument): Long? {
        val hasStrokes = !document.isEmpty
        if (!hasStrokes) {
            form.inkNoteId?.let { inkNoteRepository.delete(it) }
            return null
        }
        // El texto reconocido no se guarda aquí: lo que el usuario aceptó ya
        // está en el título, y el trazo es la fuente que sí hay que conservar.
        return inkNoteRepository.save(
            id = form.inkNoteId,
            document = document,
            recognizedText = null,
            anchorDayMillis = null,
        )
    }

    private fun EventForm.toEntity(inkNoteId: Long?): EventEntity {
        val startMillis: Long
        val endMillis: Long
        if (allDay) {
            startMillis = startDate.toDayMillis()
            // Fin exclusivo, igual que iCalendar y Google.
            endMillis = endDate.plusDays(1).toDayMillis()
        } else {
            startMillis = LocalDateTime.of(startDate, startTime).toMillis(zone)
            endMillis = LocalDateTime.of(endDate, endTime).toMillis(zone)
        }

        // Se parte de la entidad cargada, no de una nueva: `recurrence` y
        // `recurringRemoteId` no salen en el formulario y perderlos convertiría
        // una instancia de una serie en un evento suelto.
        val base = loadedEntity ?: EventEntity(
            calendarId = calendarId,
            title = "",
            startMillis = startMillis,
            endMillis = endMillis,
            updatedAt = 0,
        )

        return base.copy(
            id = id ?: 0,
            calendarId = calendarId,
            title = title.trim(),
            description = description.trim().takeIf { it.isNotEmpty() },
            location = location.trim().takeIf { it.isNotEmpty() },
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            timeZone = zone.id,
            reminderMinutes = reminderMinutes?.toString(),
            inkNoteId = inkNoteId,
            updatedAt = System.currentTimeMillis(),
            dirty = true,
            deleted = false,
        )
    }
}
