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
            title = "Nota del evento",
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
        title = if (form.isNew) "Nuevo evento" else "Editar evento",
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
        EditorCard("Título") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EinkTextField(
                    value = form.title,
                    onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                    placeholder = "Sin título",
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                EinkIconButton(
                    Glyph.Pencil, { inkOpen = true },
                    contentDescription = "Escribir a mano",
                    accent = Accent.Note,
                )
            }
            if (!inkDocument.isEmpty) {
                EditorRow(
                    Glyph.Pencil, Accent.Note, "Nota manuscrita adjunta",
                    hint = "Se guarda con el evento y se ve en su ficha.",
                ) {
                    EinkButton("Ver o cambiar", { inkOpen = true })
                }
            }
            if (form.isRecurringInstance) {
                EditorNotice(
                    "Este evento pertenece a una serie. Los cambios afectan solo a esta " +
                        "aparición; la repetición no se edita desde aquí."
                )
            }
        }

        EditorCard("Cuándo") {
            // Un solo botón con el cuándo entero: días, todo el día u horas.
            // Se elige todo en la misma ventana.
            EditorRow(Glyph.Today, Accent.Event, "Cuándo") {
                EinkButton(form.toWhen().label(), { picker = OpenPicker.WHEN })
            }
        }

        EditorCard("Calendario") {
            if (form.calendars.isEmpty()) {
                EditorNotice(
                    "No hay ningún calendario con permiso de escritura. " +
                        "Conecta una cuenta en Ajustes y sincroniza."
                )
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

        EditorCard("Aviso") {
            ChoiceFlow {
                REMINDER_OPTIONS.forEach { option ->
                    EinkButton(
                        label = option.label,
                        onClick = { viewModel.update { it.copy(reminderMinutes = option.minutes) } },
                        selected = form.reminderMinutes == option.minutes,
                    )
                }
            }
            EinkHint(
                "El aviso lo entrega Google en los dispositivos de la cuenta. " +
                    "La app no programa notificaciones locales en el Boox."
            )
        }

        EditorCard("Lugar y descripción") {
            EinkTextField(
                value = form.location,
                onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                label = "Lugar",
                placeholder = "Opcional",
            )
            EinkTextField(
                value = form.description,
                onValueChange = { value -> viewModel.update { it.copy(description = value) } },
                label = "Descripción",
                placeholder = "Opcional",
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
