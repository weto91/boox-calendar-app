package com.weto.booxcal.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkSectionHeader
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.MILLIS_PER_DAY
import com.weto.booxcal.util.resolveDate
import com.weto.booxcal.util.resolveDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
private val stampWithTime: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault())

@Composable
fun SearchScreen(
    onOpenEvent: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    /** La nota y la página (desde 0) en la que está lo buscado. */
    onOpenNote: (InkNoteEntity, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = "Volver")
            EinkTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "Buscar eventos, tareas y notas",
                modifier = Modifier.weight(1f),
            )
        }
        EinkDivider(color = Eink.Border)

        when {
            state.query.length < MIN_QUERY_LENGTH -> Centered(
                "Escribe al menos $MIN_QUERY_LENGTH caracteres."
            )

            state.searching -> Centered("Buscando…")

            state.isEmpty -> Centered("Nada coincide con «${state.query}».")

            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.events.isNotEmpty()) {
                    item { EinkSectionHeader("Eventos (${state.events.size})") }
                    items(state.events, key = { "e-${it.event.id}" }) { row ->
                        EventResult(row, zone) { onOpenEvent(row.event.id) }
                        EinkDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
                if (state.tasks.isNotEmpty()) {
                    item { EinkSectionHeader("Tareas (${state.tasks.size})") }
                    items(state.tasks, key = { "t-${it.task.id}" }) { row ->
                        TaskResult(row) { onOpenTask(row.task.id) }
                        EinkDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
                if (state.notes.isNotEmpty()) {
                    item { EinkSectionHeader("Notas manuscritas (${state.notes.size})") }
                    items(state.notes, key = { "n-${it.id}" }) { note ->
                        NoteResult(note) { onOpenNote(note, note.pageOfMatch(state.query)) }
                        EinkDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EinkHint(message)
    }
}

@Composable
private fun EventResult(row: EventWithCalendar, zone: ZoneId, onClick: () -> Unit) {
    val event = row.event
    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(EinkPalette.forArgb(row.accentArgb))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title.ifBlank { "(sin título)" },
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (event.allDay) {
                    resolveDate(event.startMillis, true, zone).format(stamp)
                } else {
                    resolveDateTime(event.startMillis, false, zone).format(stampWithTime)
                } + "  ·  " + row.calendarName,
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskResult(row: TaskWithList, onClick: () -> Unit) {
    val task = row.task
    val completed = task.completedAt != null
    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(EinkPalette.forArgb(row.listColorArgb))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title.ifBlank { "(sin título)" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (completed) Eink.Slate else Eink.Black,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    task.dueDayMillis?.let {
                        add(LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY)).format(stamp))
                    }
                    add(row.listName)
                    if (task.purged) add("purgada")
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoteResult(note: InkNoteEntity, onOpen: () -> Unit) {
    val anchorDate = note.anchorDayMillis?.let {
        LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        note.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = note.recognizedFlat.orEmpty().ifBlank { "Nota manuscrita" },
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = anchorDate?.format(stamp)
                ?: if (note.belongsToManager) "Cuaderno" else "Adjunta a un evento o tarea",
            style = MaterialTheme.typography.bodySmall,
            color = Eink.Graphite,
        )
    }
}
