package com.weto.booxcal.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.EinkWhenPickerDialog
import com.weto.booxcal.ui.theme.EventWhen
import com.weto.booxcal.ui.theme.label
import com.weto.booxcal.ui.theme.Glyph
import java.time.DayOfWeek
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R

private enum class OpenPicker { NONE, WHEN }

private fun EventForm.toWhen() = EventWhen(startDate, endDate, allDay, startTime, endTime)

@Composable
fun EventEditorScreen(
    eventId: Long?,
    initialDate: LocalDate,
    weekStart: DayOfWeek,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: EventEditorViewModel = viewModel(
        key = "event-${eventId ?: 0}",
        factory = viewModelFactory {
            initializer { EventEditorViewModel(eventId, initialDate) }
        },
    )
    val form by viewModel.form.collectAsStateWithLifecycle()

    var picker by remember { mutableStateOf(OpenPicker.NONE) }
    var inkOpen by remember { mutableStateOf(false) }
    var inkDocument by remember { mutableStateOf(InkDocument.EMPTY) }

    // El documento del formulario llega tras la carga asíncrona.
    LaunchedEffect(form.inkDocument) { inkDocument = form.inkDocument }

    // La escritura ocupa la pantalla entera en lugar de abrir un diálogo: un
    // diálogo es otra ventana, y ahí el trazo rápido de Onyx no engancha.
    if (inkOpen) {
        InkCaptureSheet(
            title = stringResource(R.string.event_note_title),
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

    // Abrir un evento es mirarlo, no editarlo. La edición está a un botón.
    var editing by remember { mutableStateOf(eventId == null) }
    if (!editing && !form.loading) {
        EventDetail(
            form = form,
            ink = inkDocument,
            onClose = onClose,
            onEdit = { editing = true },
            onDelete = viewModel::delete,
            modifier = modifier,
        )
        return
    }

    EditorScaffold(
        title = stringResource(if (form.isNew) R.string.event_new else R.string.event_edit),
        glyph = Glyph.Today,
        accent = Accent.Event,
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
                    placeholder = stringResource(R.string.note_title_placeholder),
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
                    hint = stringResource(R.string.event_ink_hint),
                ) {
                    EinkButton(stringResource(R.string.editor_view_or_change), { inkOpen = true })
                }
            }
            if (form.isRecurringInstance) {
                EditorNotice(stringResource(R.string.event_series_notice))
            }
        }

        EditorCard(stringResource(R.string.common_when)) {
            // One button with the whole "when": days, all day or hours.
            EditorRow(Glyph.Today, Accent.Event, stringResource(R.string.common_when)) {
                EinkButton(form.toWhen().label(), { picker = OpenPicker.WHEN })
            }
        }

        EditorCard(stringResource(R.string.common_calendar)) {
            if (form.calendars.isEmpty()) {
                EditorNotice(stringResource(R.string.event_no_calendar))
            } else {
                ChoiceFlow {
                    form.calendars.forEach { calendar ->
                        ColorChoice(
                            label = calendar.name,
                            color = EinkPalette.forArgb(calendar.colorArgb),
                            selected = form.calendarId == calendar.id,
                            onClick = { viewModel.update { it.copy(calendarId = calendar.id) } },
                        )
                    }
                }
            }
        }

        EditorCard(stringResource(R.string.common_alert)) {
            ChoiceFlow {
                REMINDER_OPTIONS.forEach { option ->
                    EinkButton(
                        label = stringResource(option.label),
                        onClick = { viewModel.update { it.copy(reminderMinutes = option.minutes) } },
                        selected = form.reminderMinutes == option.minutes,
                    )
                }
            }
            EinkHint(stringResource(R.string.event_alert_hint))
        }

        EditorCard(stringResource(R.string.event_place_description)) {
            EinkTextField(
                value = form.location,
                onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                label = stringResource(R.string.common_place),
                placeholder = stringResource(R.string.common_optional),
            )
            EinkTextField(
                value = form.description,
                onValueChange = { value -> viewModel.update { it.copy(description = value) } },
                label = stringResource(R.string.common_description),
                placeholder = stringResource(R.string.common_optional),
                singleLine = false,
                minLines = 6,
            )
        }
    }

    when (picker) {
        OpenPicker.NONE -> Unit
        OpenPicker.WHEN -> EinkWhenPickerDialog(
            initial = form.toWhen(),
            weekStart = weekStart,
            onDismiss = { picker = OpenPicker.NONE },
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
                picker = OpenPicker.NONE
            },
        )
    }
}
