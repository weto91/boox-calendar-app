package com.weto.booxcal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.ink.InkNotebook
import com.weto.booxcal.ink.StrokeCodec
import com.weto.booxcal.ui.ink.InkTextTarget
import com.weto.booxcal.ui.ink.InkPreview
import com.weto.booxcal.ui.ink.InkSurface
import com.weto.booxcal.ui.ink.InkToolbar
import com.weto.booxcal.ui.ink.rememberInkBoardState
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkRadioCheck
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.EinkTileButton
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.MILLIS_PER_DAY
import com.weto.booxcal.util.resolveDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Módulo inferior.
 *
 * Cuatro accesos, en el mismo orden que la app nativa: escribir, ver todos los
 * recordatorios, ver lo escrito hoy y ver lo que vence. Cuando el elegido es el
 * de escribir, la fila continúa con las herramientas del lápiz en vez de con el
 * título: es la única forma de que quepan sin robarle alto al lienzo.
 */
@Composable
fun ColumnScope.ModuleCard(
    state: HomeUiState,
    zone: ZoneId,
    onSelectModule: (HomeModule) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenAll: (HomeModule) -> Unit,
    onOpenNote: (InkNoteEntity) -> Unit,
    onSaveDayNote: (InkNotebook) -> Unit,
    onNoteText: (String) -> Unit,
    onNewSheet: () -> Unit,
    onEditNote: (Long) -> Unit,
    onRenameNote: (Long, String) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onCreateFromText: (isEvent: Boolean, text: String, wholeNote: Boolean) -> Unit,
) {
    val notebook = remember(state.currentNote?.id, state.currentNote?.updatedAt) {
        StrokeCodec.decodeNotebook(state.currentNote?.strokesJson)
    }
    // La clave es la hoja (día + nota + "hoja nueva"), no el id de la nota:
    // guardar devuelve el cuaderno por el flujo y le pone id, y recargarlo
    // ahí saltaría a la página uno en mitad de una frase.
    val board = rememberInkBoardState(
        notebook,
        key = "${state.selected}-${state.sheet}",
        onNotebookChanged = onSaveDayNote,
    )
    board.onText = { target, text, wholeNote ->
        when (target) {
            InkTextTarget.NOTE -> onNoteText(text)
            InkTextTarget.EVENT -> onCreateFromText(true, text, wholeNote)
            InkTextTarget.REMINDER -> onCreateFromText(false, text, wholeNote)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeModule.entries.forEach { module ->
            EinkTileButton(
                glyph = module.glyph,
                accent = module.accent,
                label = stringResource(module.title),
                onClick = { onSelectModule(module) },
                selected = state.module == module,
                tileSize = 30.dp,
                showLabel = false,
            )
        }

        Spacer(Modifier.width(6.dp))

        // El título va siempre al lado de los iconos; con "Nota" elegida, las
        // herramientas se alinean a la derecha detrás de él.
        Text(
            text = stringResource(state.module.title),
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 6.dp),
        )
        Spacer(Modifier.weight(1f))

        if (state.module == HomeModule.NOTE) {
            InkToolbar(state = board, compact = true, onNewSheet = onNewSheet)
        } else {
            Row(
                Modifier
                    .clip(ControlCorner)
                    .einkClickable { onOpenAll(state.module) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.module_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = Eink.Graphite,
                )
                EinkGlyph(Glyph.ChevronRight, size = 16.dp, tint = Eink.Graphite)
            }
        }
    }
    EinkDivider(color = Eink.Border)

    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (state.module) {
            HomeModule.NOTE -> InkSurface(
                state = board,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )

            HomeModule.REMINDERS -> RemindersTab(state, onToggleTask, onOpenTask)
            HomeModule.TODAY_NOTES -> DayNotesTab(state, onOpenNote, onEditNote, onRenameNote, onDeleteNote)
            HomeModule.DUE -> DueTab(state, zone, onToggleTask, onOpenTask, onOpenEvent)
        }
    }
}

// --- Recordatorios ----------------------------------------------------------

/** Todos los pendientes, tengan fecha o no, y lo completado detrás. */
@Composable
private fun RemindersTab(
    state: HomeUiState,
    onToggle: (Long, Boolean) -> Unit,
    onOpen: (Long) -> Unit,
) {
    var completedExpanded by remember { mutableStateOf(false) }

    if (state.allReminders.isEmpty() && state.completedReminders.isEmpty()) {
        EmptyModule(stringResource(R.string.module_no_reminders))
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Con diez o más pendientes, las filas se ajustan para que quepan
        // nueve y media: la media fila de abajo dice, sin más, que hay que
        // desplazar para ver el resto. Con menos, cada fila mide lo suyo.
        val rowHeight: Dp? =
            if (state.allReminders.size >= REMINDER_ROWS_THRESHOLD) maxHeight / VISIBLE_REMINDER_ROWS else null
        val today = LocalDate.now()

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.allReminders, key = { "pending-${it.task.id}" }) { row ->
                ReminderRow(
                    row = row,
                    completed = false,
                    today = today,
                    onToggle = { onToggle(row.task.id, it) },
                    onClick = { onOpen(row.task.id) },
                    modifier = if (rowHeight != null) Modifier.height(rowHeight - HairlineWidth) else Modifier,
                    compact = rowHeight != null,
                )
                EinkDivider(color = Eink.Border)
            }

            completedSection(state, completedExpanded, { completedExpanded = it }, today, onToggle, onOpen)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.completedSection(
    state: HomeUiState,
    completedExpanded: Boolean,
    onExpand: (Boolean) -> Unit,
    today: LocalDate,
    onToggle: (Long, Boolean) -> Unit,
    onOpen: (Long) -> Unit,
) {
    if (state.completedReminders.isNotEmpty()) {
        item(key = "completed-header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .einkClickable { onExpand(!completedExpanded) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EinkGlyph(
                    glyph = if (completedExpanded) Glyph.CaretDown else Glyph.CaretRight,
                    size = 16.dp,
                    tint = Eink.Graphite,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.module_completed_count, state.completedReminders.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = Eink.Graphite,
                )
            }
        }

        if (completedExpanded) {
            items(state.completedReminders, key = { "done-${it.task.id}" }) { row ->
                ReminderRow(
                    row = row,
                    completed = true,
                    today = today,
                    onToggle = { onToggle(row.task.id, it) },
                    onClick = { onOpen(row.task.id) },
                )
                EinkDivider(color = Eink.Border)
            }
        }
    }
}

/** A partir de cuántos pendientes se ajusta el alto de fila, y cuántas filas se enseñan. */
private const val REMINDER_ROWS_THRESHOLD = 10
private const val VISIBLE_REMINDER_ROWS = 9.5f

@Composable
private fun ReminderRow(
    row: TaskWithList,
    completed: Boolean,
    today: LocalDate,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val task = row.task
    val due = task.dueDayMillis?.let { LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY)) }
    val overdue = !completed && due != null && due.isBefore(today)
    val dueToday = !completed && due == today
    val reminderDate = rememberDateFormat(R.string.pattern_dd_month_year)

    Row(
        modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkRadioCheck(checked = completed, onCheckedChange = onToggle)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(vertical = if (compact) 4.dp else 8.dp)) {
            Text(
                text = task.title.ifBlank { stringResource(R.string.common_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (completed) Eink.Slate else Eink.Black,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                // Con el alto de fila ajustado solo cabe una línea de título.
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                // Lo que vence hoy y lo ya vencido se marcan con una etiqueta
                // que salta a la vista; la fecha toma el mismo tono.
                if (overdue) DuePill(stringResource(R.string.module_overdue_pill), Accent.Today)
                if (dueToday) DuePill(stringResource(R.string.common_today), Eink.Black)
                Text(
                    // Sin fecha se dice explícitamente: un recordatorio sin
                    // fecha no sale en el calendario y conviene que se note.
                    // La fecha va siempre en negro y negrita, que es como se
                    // lee bien; solo lo vencido cambia al rojo y lo hecho al gris.
                    text = due?.format(reminderDate)?.replace(".", "") ?: stringResource(R.string.common_no_date),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (completed) null else FontWeight.SemiBold,
                    color = when {
                        completed -> Eink.Slate
                        overdue -> Accent.Today
                        else -> Eink.Black
                    },
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                )
            }
        }
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(EinkPalette.forArgb(row.listColorArgb))
        )
    }
}

/** Etiqueta rellena con texto blanco: «Vencido», «Hoy». */
@Composable
private fun DuePill(label: String, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Eink.White,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// --- Notas del día ----------------------------------------------------------

/**
 * Las notas del día marcado en el calendario. Cada una con su botón de editar
 * (la nota pasa al lienzo rápido) y de título; tocar la fila la abre a
 * pantalla completa.
 */
@Composable
private fun DayNotesTab(
    state: HomeUiState,
    onOpenNote: (InkNoteEntity) -> Unit,
    onEditNote: (Long) -> Unit,
    onRenameNote: (Long, String) -> Unit,
    onDeleteNote: (Long) -> Unit,
) {
    if (state.dayNotes.isEmpty()) {
        EmptyModule(stringResource(R.string.module_no_notes_today))
        return
    }
    var renaming by remember { mutableStateOf<InkNoteEntity?>(null) }
    var deleting by remember { mutableStateOf<InkNoteEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.dayNotes, key = { "day-note-${it.id}" }) { note ->
            NoteRow(
                note = note,
                current = note.id == state.currentNote?.id,
                onOpen = { onOpenNote(note) },
                onEdit = { onEditNote(note.id) },
                onRename = { renaming = note },
                onDelete = { deleting = note },
            )
            EinkDivider(Modifier.padding(horizontal = 14.dp))
        }
    }

    val noteAt = stringResource(R.string.note_at_time)
    deleting?.let { note ->
        ConfirmDeleteDialog(
            title = note.displayTitle(noteAt),
            onDismiss = { deleting = null },
            onConfirm = {
                onDeleteNote(note.id)
                deleting = null
            },
        )
    }

    renaming?.let { note ->
        RenameNoteDialog(
            initial = note.title.orEmpty(),
            onDismiss = { renaming = null },
            onConfirm = { title ->
                onRenameNote(note.id, title)
                renaming = null
            },
        )
    }
}

/** The note's title: the one given by hand, else the transcription, else the time ([noteAt] is "Note at %s"). */
private fun InkNoteEntity.displayTitle(noteAt: String): String =
    title?.takeIf { it.isNotBlank() }
        ?: recognizedFlat?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?: noteAt.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalTime().format(clock))

@Composable
private fun NoteRow(
    note: InkNoteEntity,
    current: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val document = remember(note.id, note.updatedAt) { StrokeCodec.decode(note.strokesJson) }

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onOpen)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkPreview(
            document = document,
            modifier = Modifier
                .size(width = 72.dp, height = 46.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    if (current) 2.dp else HairlineWidth,
                    if (current) Eink.Black else Eink.Hairline,
                    RoundedCornerShape(6.dp),
                )
                .padding(3.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = note.displayTitle(stringResource(R.string.note_at_time)),
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Instant.ofEpochMilli(note.updatedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalTime()
                    .format(clock),
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Slate,
            )
        }
        EinkIconButton(
            glyph = Glyph.Edit,
            onClick = onEdit,
            contentDescription = stringResource(R.string.module_edit_note),
            accent = Accent.Note,
            box = 44.dp,
            size = 22.dp,
        )
        EinkIconButton(
            glyph = Glyph.Title,
            onClick = onRename,
            contentDescription = stringResource(R.string.module_edit_title),
            box = 44.dp,
            size = 22.dp,
        )
        EinkIconButton(
            glyph = Glyph.Trash,
            onClick = onDelete,
            contentDescription = stringResource(R.string.module_delete_note),
            accent = Accent.Today,
            box = 44.dp,
            size = 22.dp,
        )
    }
}

/** Borrar no tiene vuelta atrás: siempre se pregunta. */
@Composable
private fun ConfirmDeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.module_delete_note), modifier = Modifier.width(420.dp)) {
        Text(
            text = stringResource(R.string.module_delete_note_text, title),
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(stringResource(R.string.common_delete), onConfirm, emphasized = true)
        }
    }
}

@Composable
private fun RenameNoteDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.note_title_dialog), modifier = Modifier.width(420.dp)) {
        EinkTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = stringResource(R.string.note_title_placeholder),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EinkIconButton(Glyph.Check, { onConfirm(title) }, contentDescription = stringResource(R.string.note_title_save), accent = Accent.Search)
        }
    }
}

// --- Vencidos y hoy ---------------------------------------------------------

/** Lo que reclama atención ahora: lo que venció y lo que toca hoy. */
@Composable
private fun DueTab(
    state: HomeUiState,
    zone: ZoneId,
    onToggle: (Long, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenEvent: (Long) -> Unit,
) {
    if (state.dueReminders.isEmpty() && state.todayEvents.isEmpty()) {
        EmptyModule(stringResource(R.string.module_nothing_due))
        return
    }

    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.dueReminders.isNotEmpty()) {
            item(key = "due-header") { SectionLabel(stringResource(R.string.common_reminders)) }
            items(state.dueReminders, key = { "due-${it.task.id}" }) { row ->
                ReminderRow(
                    row = row,
                    completed = false,
                    today = today,
                    onToggle = { onToggle(row.task.id, it) },
                    onClick = { onOpenTask(row.task.id) },
                )
                EinkDivider(color = Eink.Border)
            }
        }
        if (state.todayEvents.isNotEmpty()) {
            item(key = "events-header") { SectionLabel(stringResource(R.string.module_events_today)) }
            items(state.todayEvents, key = { "today-event-${it.event.id}" }) { row ->
                EventRow(row, zone) { onOpenEvent(row.event.id) }
                EinkDivider(Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Eink.Graphite,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EventRow(row: EventWithCalendar, zone: ZoneId, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .size(width = 3.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(EinkPalette.forArgb(row.accentArgb))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (row.event.allDay) {
                "—"
            } else {
                resolveDateTime(row.event.startMillis, false, zone).toLocalTime().format(clock)
            },
            style = MaterialTheme.typography.labelLarge,
            color = Eink.Graphite,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = row.event.title.ifBlank { stringResource(R.string.common_untitled) },
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyModule(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EinkHint(message)
    }
}
