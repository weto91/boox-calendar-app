package com.weto.booxcal.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ui.editor.EventEditorViewModel
import com.weto.booxcal.ui.editor.EventForm
import com.weto.booxcal.ui.editor.REMINDER_OPTIONS
import com.weto.booxcal.ui.editor.TaskEditorViewModel
import com.weto.booxcal.ui.editor.TaskForm
import com.weto.booxcal.ui.ink.InkCaptureSheet
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDatePickerDialog
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkFieldRow
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkTabs
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.EinkWhenPickerDialog
import com.weto.booxcal.ui.theme.EventWhen
import com.weto.booxcal.ui.theme.label
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.einkClickable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.util.format

@Composable
private fun LocalDate.label(): String =
    format(rememberDateFormat(R.string.pattern_date_short_year), capitalize = true, locale = rememberLocale())


/**
 * Creación desde el botón "+", con las dos pestañas de la app nativa.
 *
 * Es una pantalla y no un diálogo, y no por gusto. Un `Dialog` de Compose abre
 * **otra ventana**: se comía la barra de estado de la tablet, no heredaba el
 * escalado de densidad —de ahí que se viera todo diminuto— y dentro de ella el
 * trazo rápido de Onyx no engancha, porque se ata a la ventana de la actividad.
 * Como pantalla no pasa nada de eso, y además hay sitio.
 *
 * Los formularios viven en un almacén de ViewModels propio, que muere con la
 * pantalla. Como ventana flotante no cuelga de ninguna entrada de navegación,
 * y sin esto sus ViewModels se quedaban en el de la actividad: al guardar un
 * recordatorio, el formulario se quedaba «guardado» para siempre, y la
 * siguiente vez que se abría la pestaña se cerraba sola al instante.
 */
@Composable
fun QuickCreateScreen(
    date: LocalDate,
    weekStart: DayOfWeek,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialReminder: Boolean = false,
    initialTitle: String = "",
    /** Se llama justo antes de cerrar cuando se ha guardado algo (no al cancelar). */
    onSaved: (() -> Unit)? = null,
    /** Handwritten note attached from the start; null for none. */
    initialInk: InkDocument? = null,
) {
    val owner = remember { QuickCreateStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        QuickCreateContent(date, weekStart, onClose, modifier, initialReminder, initialTitle, onSaved, initialInk)
    }
}

private class QuickCreateStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
private fun QuickCreateContent(
    date: LocalDate,
    weekStart: DayOfWeek,
    onClose: () -> Unit,
    modifier: Modifier,
    initialReminder: Boolean,
    initialTitle: String,
    onSaved: (() -> Unit)?,
    initialInk: InkDocument?,
) {
    var tab by remember { mutableIntStateOf(if (initialReminder) 1 else 0) }
    val dismissSaved: () -> Unit = { onSaved?.invoke(); onClose() }
    // El trazo se guarda aquí y no dentro de cada pestaña: al cambiar de
    // pestaña la otra sale de la composición y se llevaría la nota con ella.
    var eventInk by remember { mutableStateOf(initialInk ?: InkDocument.EMPTY) }
    var reminderInk by remember { mutableStateOf(initialInk ?: InkDocument.EMPTY) }

    Column(
        modifier
            .fillMaxSize()
            .background(Eink.White)
            .padding(12.dp)
    ) {
        EinkTabs(
            tabs = listOf(
                Triple(Glyph.Today, Accent.Event, stringResource(R.string.common_event)),
                Triple(Glyph.Bell, Accent.Reminder, stringResource(R.string.common_reminder)),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
        )

        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            EventTab(
                date = date,
                weekStart = weekStart,
                initialTitle = initialTitle,
                ink = eventInk,
                onInk = { eventInk = it },
                onDismiss = onClose,
                onSaved = dismissSaved,
                modifier = Modifier.weight(1f),
            )
        } else {
            ReminderTab(
                date = date,
                weekStart = weekStart,
                initialTitle = initialTitle,
                ink = reminderInk,
                onInk = { reminderInk = it },
                onDismiss = onClose,
                onSaved = dismissSaved,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --- Evento -----------------------------------------------------------------

@Composable
private fun EventTab(
    date: LocalDate,
    weekStart: DayOfWeek,
    initialTitle: String,
    ink: InkDocument,
    onInk: (InkDocument) -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: EventEditorViewModel = viewModel(
        key = "quick-event",
        factory = viewModelFactory { initializer { EventEditorViewModel(null, date) } },
    )
    val form by viewModel.form.collectAsStateWithLifecycle()

    var picker by remember { mutableStateOf<EventPicker?>(null) }

    if (picker == EventPicker.INK) {
        InkCaptureSheet(
            title = stringResource(R.string.event_note_title),
            initial = ink,
            languageTag = form.ocrLanguageTag,
            onCancel = { picker = null },
            onConfirm = { document, acceptedText ->
                onInk(document)
                // Si aún no hay título y el reconocedor propuso uno, se aprovecha.
                if (acceptedText != null && form.title.isBlank()) {
                    viewModel.update { it.copy(title = acceptedText) }
                }
                picker = null
            },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(form.saved) { if (form.saved) onSaved() }
    LaunchedEffect(form.loading) {
        if (!form.loading && initialTitle.isNotBlank() && form.title.isBlank()) {
            viewModel.update { it.copy(title = initialTitle) }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            EinkTextField(
                value = form.title,
                onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                placeholder = stringResource(R.string.quick_event_title_placeholder),
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            // Un solo campo con el cuándo entero: días, todo el día u horas.
            EinkFieldRow(
                glyph = Glyph.Today,
                accent = Accent.Event,
                label = stringResource(R.string.common_when),
                value = EventWhen(form.startDate, form.endDate, form.allDay, form.startTime, form.endTime).label(),
                onClick = { picker = EventPicker.WHEN },
            )

            EinkDivider(Modifier.padding(vertical = 6.dp))

            EinkFieldRow(
                glyph = Glyph.ListCheck,
                accent = Accent.List,
                label = stringResource(R.string.common_calendar),
                value = form.calendars.firstOrNull { it.id == form.calendarId }?.name ?: "—",
                onClick = { picker = EventPicker.CALENDAR },
            )
            EinkFieldRow(
                glyph = Glyph.Bell,
                accent = Accent.Alarm,
                label = stringResource(R.string.common_alert),
                value = stringResource(
                    REMINDER_OPTIONS.firstOrNull { it.minutes == form.reminderMinutes }?.label ?: R.string.alert_none
                ),
                onClick = { picker = EventPicker.REMINDER },
            )
            EinkFieldRow(
                glyph = Glyph.Pencil,
                accent = Accent.Note,
                label = stringResource(R.string.common_handwritten_note),
                value = stringResource(if (ink.isEmpty) R.string.quick_ink_add else R.string.quick_ink_written),
                onClick = { picker = EventPicker.INK },
            )

            Spacer(Modifier.height(8.dp))

            EinkTextField(
                value = form.location,
                onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                label = stringResource(R.string.common_place),
                placeholder = stringResource(R.string.common_optional),
            )
            Spacer(Modifier.height(8.dp))
            EinkTextField(
                value = form.description,
                onValueChange = { value -> viewModel.update { it.copy(description = value) } },
                label = stringResource(R.string.common_description),
                placeholder = stringResource(R.string.common_optional),
                singleLine = false,
                minLines = 6,
            )
        }

        SaveBar(
            error = form.error,
            blocked = !form.canSave && !form.loading,
            canSave = form.canSave,
            onCancel = onDismiss,
            onSave = { viewModel.save(ink) },
        )
    }

    when (picker) {
        EventPicker.WHEN -> EinkWhenPickerDialog(
            initial = EventWhen(form.startDate, form.endDate, form.allDay, form.startTime, form.endTime),
            weekStart = weekStart,
            onDismiss = { picker = null },
            onSelected = { chosen ->
                viewModel.update {
                    it.copy(
                        startDate = chosen.startDate,
                        endDate = chosen.endDate,
                        allDay = chosen.allDay,
                        startTime = chosen.startTime,
                        endTime = chosen.endTime,
                    )
                }
                picker = null
            },
        )

        EventPicker.CALENDAR -> ChoiceDialog(
            title = stringResource(R.string.common_calendar),
            options = form.calendars.map { it.id to it.name },
            selected = form.calendarId,
            onDismiss = { picker = null },
            onPick = { id ->
                viewModel.update { it.copy(calendarId = id) }
                picker = null
            },
        )

        EventPicker.REMINDER -> ChoiceDialog(
            title = stringResource(R.string.common_alert),
            options = REMINDER_OPTIONS.map { it.minutes to stringResource(it.label) },
            selected = form.reminderMinutes,
            onDismiss = { picker = null },
            onPick = { minutes ->
                viewModel.update { it.copy(reminderMinutes = minutes) }
                picker = null
            },
        )

        // La hoja de escritura se pinta arriba, antes del formulario.
        EventPicker.INK -> Unit

        null -> Unit
    }
}

private enum class EventPicker { WHEN, CALENDAR, REMINDER, INK }

// --- Recordatorio -----------------------------------------------------------

@Composable
private fun ReminderTab(
    date: LocalDate,
    weekStart: DayOfWeek,
    initialTitle: String,
    ink: InkDocument,
    onInk: (InkDocument) -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TaskEditorViewModel = viewModel(
        key = "quick-task",
        factory = viewModelFactory { initializer { TaskEditorViewModel(null, date) } },
    )
    val form by viewModel.form.collectAsStateWithLifecycle()

    var picker by remember { mutableStateOf<ReminderPicker?>(null) }

    if (picker == ReminderPicker.INK) {
        InkCaptureSheet(
            title = stringResource(R.string.task_note_title),
            initial = ink,
            languageTag = form.ocrLanguageTag,
            onCancel = { picker = null },
            onConfirm = { document, acceptedText ->
                onInk(document)
                // Si aún no hay título y el reconocedor propuso uno, se aprovecha.
                if (acceptedText != null && form.title.isBlank()) {
                    viewModel.update { it.copy(title = acceptedText) }
                }
                picker = null
            },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(form.saved) { if (form.saved) onSaved() }
    LaunchedEffect(form.loading) {
        if (!form.loading && initialTitle.isNotBlank() && form.title.isBlank()) {
            viewModel.update { it.copy(title = initialTitle) }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            EinkTextField(
                value = form.title,
                onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                placeholder = stringResource(R.string.quick_reminder_placeholder),
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            EinkFieldRow(
                glyph = Glyph.Today,
                accent = Accent.Reminder,
                label = stringResource(R.string.common_date),
                value = form.dueDate?.label() ?: stringResource(R.string.common_no_date),
                onClick = { picker = ReminderPicker.DUE_DATE },
            )
            EinkFieldRow(
                glyph = Glyph.ListCheck,
                accent = Accent.List,
                label = stringResource(R.string.common_list),
                value = form.lists.firstOrNull { it.id == form.taskListId }?.name ?: "—",
                onClick = { picker = ReminderPicker.LIST },
            )
            EinkFieldRow(
                glyph = Glyph.Pencil,
                accent = Accent.Note,
                label = stringResource(R.string.common_handwritten_note),
                value = stringResource(if (ink.isEmpty) R.string.quick_ink_add else R.string.quick_ink_written),
                onClick = { picker = ReminderPicker.INK },
            )

            Spacer(Modifier.height(8.dp))

            EinkTextField(
                value = form.notes,
                onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                label = stringResource(R.string.quick_note_field),
                placeholder = stringResource(R.string.common_optional),
                singleLine = false,
                minLines = 8,
            )

            if (form.dueDate == null) {
                Spacer(Modifier.height(8.dp))
                EinkHint(stringResource(R.string.task_no_date_hint))
            }
        }

        SaveBar(
            error = form.error,
            blocked = !form.canSave && !form.loading,
            canSave = form.canSave,
            onCancel = onDismiss,
            onSave = { viewModel.save(ink) },
        )
    }

    when (picker) {
        ReminderPicker.DUE_DATE -> EinkDatePickerDialog(
            initial = form.dueDate ?: date,
            weekStart = weekStart,
            onDismiss = { picker = null },
            onSelected = { picked ->
                viewModel.update { it.copy(dueDate = picked) }
                picker = null
            },
            onClear = {
                viewModel.update { it.copy(dueDate = null) }
                picker = null
            },
        )

        ReminderPicker.LIST -> ChoiceDialog(
            title = stringResource(R.string.common_list),
            options = form.lists.map { it.id to it.name },
            selected = form.taskListId,
            onDismiss = { picker = null },
            onPick = { id ->
                viewModel.update { it.copy(taskListId = id) }
                picker = null
            },
        )

        // La hoja de escritura se pinta arriba, antes del formulario.
        ReminderPicker.INK -> Unit

        null -> Unit
    }
}

private enum class ReminderPicker { DUE_DATE, LIST, INK }

// --- Piezas comunes ---------------------------------------------------------

@Composable
private fun ColumnScope.SaveBar(
    error: String?,
    blocked: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    EinkDivider(Modifier.padding(top = 8.dp))

    if (error != null) {
        EinkHint(error, Modifier.padding(top = 6.dp))
    } else if (blocked) {
        // Solo puede pasar si la colección local aún no está creada; se dice en
        // vez de dejar el botón apagado sin explicación.
        EinkHint(stringResource(R.string.quick_no_collection), Modifier.padding(top = 6.dp))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.weight(1f))
        EinkIconButton(
            glyph = Glyph.ChevronLeft,
            onClick = onCancel,
            contentDescription = stringResource(R.string.common_cancel),
        )
        EinkIconButton(
            glyph = Glyph.Check,
            onClick = onSave,
            contentDescription = stringResource(R.string.common_save),
            enabled = canSave,
            accent = Accent.Search,
        )
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    EinkDialog(onDismiss = onDismiss, title = title, modifier = Modifier.width(360.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (options.isEmpty()) {
                EinkHint(stringResource(R.string.quick_no_options))
            }
            // Una lista con su marca, no una pila de botones del ancho de la
            // pantalla: lo que se elige es un nombre, y un botón enorme
            // alrededor de un nombre no añade nada y llena la pantalla de cajas.
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ControlCorner)
                        .einkClickable { onPick(value) }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(30.dp)) {
                        if (isSelected) {
                            EinkGlyph(Glyph.Check, size = 20.dp, tint = Eink.Black)
                        }
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Eink.Black,
                    )
                }
                EinkDivider()
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Spacer(Modifier.weight(1f))
                EinkIconButton(Glyph.ChevronLeft, onDismiss, contentDescription = stringResource(R.string.common_close))
            }
        }
    }
}

