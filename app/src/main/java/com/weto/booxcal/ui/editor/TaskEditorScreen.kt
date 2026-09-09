package com.weto.booxcal.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ui.ink.InkCaptureSheet
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkCheckbox
import com.weto.booxcal.ui.theme.EinkDatePickerDialog
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.Glyph
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

@Composable
fun TaskEditorScreen(
    taskId: Long?,
    initialDueDate: LocalDate?,
    weekStart: DayOfWeek,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TaskEditorViewModel = viewModel(
        key = "task-${taskId ?: 0}",
        factory = viewModelFactory {
            initializer { TaskEditorViewModel(taskId, initialDueDate) }
        },
    )
    val form by viewModel.form.collectAsStateWithLifecycle()

    var datePickerOpen by remember { mutableStateOf(false) }
    var inkOpen by remember { mutableStateOf(false) }
    var inkDocument by remember { mutableStateOf(InkDocument.EMPTY) }

    LaunchedEffect(form.inkDocument) { inkDocument = form.inkDocument }

    // La escritura ocupa la pantalla entera en lugar de abrir un diálogo: un
    // diálogo es otra ventana, y ahí el trazo rápido de Onyx no engancha.
    if (inkOpen) {
        InkCaptureSheet(
            title = stringResource(R.string.task_note_title),
            initial = inkDocument,
            languageTag = form.ocrLanguageTag,
            onCancel = { inkOpen = false },
            onConfirm = { document, acceptedText ->
                inkDocument = document
                if (!acceptedText.isNullOrBlank()) {
                    viewModel.update { it.copy(title = acceptedText) }
                }
                inkOpen = false
            },
            modifier = modifier,
        )
        return
    }
    LaunchedEffect(form.saved, form.deleted) { if (form.saved || form.deleted) onClose() }

    // Abrir un recordatorio es mirarlo, no editarlo. La edición está a un botón.
    var editing by remember { mutableStateOf(taskId == null) }
    if (!editing && !form.loading) {
        TaskDetail(
            form = form,
            ink = inkDocument,
            onClose = onClose,
            onEdit = { editing = true },
            onDelete = viewModel::delete,
            onToggleCompleted = { done ->
                viewModel.update { it.copy(completed = done) }
                viewModel.save(inkDocument)
            },
            modifier = modifier,
        )
        return
    }

    EditorScaffold(
        title = stringResource(if (form.isNew) R.string.task_new else R.string.task_edit),
        glyph = Glyph.Bell,
        accent = Accent.Reminder,
        onClose = onClose,
        onSave = { viewModel.save(inkDocument) },
        canSave = form.canSave,
        onDelete = if (form.isNew) null else viewModel::delete,
        loading = form.loading,
        error = form.error,
        modifier = modifier,
    ) {
        EditorCard(stringResource(R.string.common_title)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EinkTextField(
                    value = form.title,
                    onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                    placeholder = stringResource(R.string.task_title_placeholder),
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                EinkIconButton(
                    Glyph.Pencil, { inkOpen = true },
                    contentDescription = stringResource(R.string.common_write_by_hand),
                    accent = Accent.Note,
                )
            }
            if (!inkDocument.isEmpty) {
                EditorRow(
                    Glyph.Pencil, Accent.Note, stringResource(R.string.editor_ink_attached),
                    hint = stringResource(R.string.task_ink_hint),
                ) {
                    EinkButton(stringResource(R.string.editor_view_or_change), { inkOpen = true })
                }
            }
        }

        EditorCard(stringResource(R.string.task_due)) {
            EditorRow(
                Glyph.Today, Accent.Reminder, stringResource(R.string.common_date),
                hint = stringResource(R.string.task_date_hint),
            ) {
                EinkButton(form.dueDate?.label() ?: stringResource(R.string.common_no_date), { datePickerOpen = true })
                if (form.dueDate != null) {
                    EinkButton(stringResource(R.string.common_remove), { viewModel.update { it.copy(dueDate = null) } })
                }
            }
            if (form.dueDate == null) {
                EinkHint(stringResource(R.string.task_no_date_hint))
            }
        }

        EditorCard(stringResource(R.string.common_list)) {
            if (form.lists.isEmpty()) {
                EditorNotice(stringResource(R.string.task_no_lists))
            } else {
                ChoiceFlow {
                    form.lists.forEach { list ->
                        ColorChoice(
                            label = list.name,
                            color = EinkPalette.forArgb(list.colorArgb),
                            selected = form.taskListId == list.id,
                            onClick = { viewModel.update { it.copy(taskListId = list.id) } },
                        )
                    }
                }
            }
        }

        EditorCard(stringResource(R.string.task_status)) {
            EditorRow(
                Glyph.Check, Accent.Search,
                stringResource(if (form.completed) R.string.common_completed else R.string.common_pending),
                hint = stringResource(R.string.task_status_hint),
            ) {
                EinkCheckbox(
                    checked = form.completed,
                    onCheckedChange = { checked -> viewModel.update { it.copy(completed = checked) } },
                )
            }
        }

        EditorCard(stringResource(R.string.common_notes_field)) {
            EinkTextField(
                value = form.notes,
                onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                placeholder = stringResource(R.string.common_optional),
                singleLine = false,
                minLines = 6,
            )
        }
    }

    if (datePickerOpen) {
        EinkDatePickerDialog(
            initial = form.dueDate ?: LocalDate.now(),
            weekStart = weekStart,
            onDismiss = { datePickerOpen = false },
            onSelected = { date ->
                viewModel.update { it.copy(dueDate = date) }
                datePickerOpen = false
            },
            onClear = {
                viewModel.update { it.copy(dueDate = null) }
                datePickerOpen = false
            },
        )
    }
}
