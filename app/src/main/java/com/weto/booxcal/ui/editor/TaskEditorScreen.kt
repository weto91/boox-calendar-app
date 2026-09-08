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

private val dueLabel: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

private fun LocalDate.label(): String =
    format(dueLabel).replace(".", "").replaceFirstChar { it.titlecase(Locale.getDefault()) }

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
            title = "Nota del recordatorio",
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
        title = if (form.isNew) "Nuevo recordatorio" else "Editar recordatorio",
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
        EditorCard("Título") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EinkTextField(
                    value = form.title,
                    onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                    placeholder = "¿Qué hay que hacer?",
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
                    hint = "Se guarda con el recordatorio y se ve en su ficha.",
                ) {
                    EinkButton("Ver o cambiar", { inkOpen = true })
                }
            }
        }

        EditorCard("Vencimiento") {
            EditorRow(
                Glyph.Today, Accent.Reminder, "Fecha",
                hint = "Google Tasks solo guarda el día, nunca la hora.",
            ) {
                EinkButton(form.dueDate?.label() ?: "Sin fecha", { datePickerOpen = true })
                if (form.dueDate != null) {
                    EinkButton("Quitar", { viewModel.update { it.copy(dueDate = null) } })
                }
            }
            if (form.dueDate == null) {
                EinkHint(
                    "Sin fecha, el recordatorio vive en la lista de recordatorios " +
                        "pero no aparece en el calendario."
                )
            }
        }

        EditorCard("Lista") {
            if (form.lists.isEmpty()) {
                EditorNotice("No hay listas de tareas. Conecta una cuenta en Ajustes y sincroniza.")
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

        EditorCard("Estado") {
            EditorRow(
                Glyph.Check, Accent.Search,
                if (form.completed) "Completada" else "Pendiente",
                hint = "Al guardar, el cambio sube a Google en unos segundos.",
            ) {
                EinkCheckbox(
                    checked = form.completed,
                    onCheckedChange = { checked -> viewModel.update { it.copy(completed = checked) } },
                )
            }
        }

        EditorCard("Notas") {
            EinkTextField(
                value = form.notes,
                onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                placeholder = "Opcional",
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
