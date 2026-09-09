package com.weto.booxcal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.ink.StrokeCodec
import com.weto.booxcal.ui.ink.InkPreview
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkCardHeader
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkRadioCheck
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.resolveDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Filas por página. Los rótulos de grupo cuentan como fila: son más bajos, pero ocupan. */
private const val PAGE_SIZE = 5

/** Una fila de la tarjeta: rótulo de grupo o elemento. */
private sealed class DayItem {
    data class Header(val title: String, val count: Int) : DayItem()
    data class Event(val row: EventWithCalendar) : DayItem()
    data class Task(val row: TaskWithList) : DayItem()
    data class Note(val note: InkNoteEntity) : DayItem()
}

/**
 * Lo que tiene el día elegido en el calendario de al lado: sus eventos, los
 * recordatorios que vencen ese día y sus notas manuscritas, cada grupo bajo
 * su rótulo. Los recordatorios vencidos de días anteriores no salen aquí:
 * para eso está la pestaña «Vencidos y hoy» de abajo.
 *
 * Paginado, no desplazable: en e-ink desplazar una lista cuesta un refresco
 * continuo y deja fantasmas. Pasar página repinta una sola vez.
 */
@Composable
fun ColumnScope.DayItemsCard(
    events: List<EventWithCalendar>,
    tasks: List<TaskWithList>,
    notes: List<InkNoteEntity>,
    date: LocalDate,
    zone: ZoneId,
    onOpenEvent: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onOpenNote: (InkNoteEntity) -> Unit,
    onNewEvent: () -> Unit,
) {
    val eventsLabel = stringResource(R.string.common_events)
    val remindersLabel = stringResource(R.string.common_reminders)
    val notesLabel = stringResource(R.string.common_notes)
    val items = remember(events, tasks, notes, eventsLabel) {
        buildList {
            if (events.isNotEmpty()) {
                add(DayItem.Header(eventsLabel, events.size))
                events.forEach { add(DayItem.Event(it)) }
            }
            if (tasks.isNotEmpty()) {
                add(DayItem.Header(remindersLabel, tasks.size))
                tasks.forEach { add(DayItem.Task(it)) }
            }
            if (notes.isNotEmpty()) {
                add(DayItem.Header(notesLabel, notes.size))
                notes.forEach { add(DayItem.Note(it)) }
            }
        }
    }
    val total = events.size + tasks.size + notes.size
    val pageCount = if (items.isEmpty()) 1 else (items.size + PAGE_SIZE - 1) / PAGE_SIZE
    var page by remember(date, items.size) { mutableIntStateOf(0) }
    val current = page.coerceIn(0, pageCount - 1)

    EinkCardHeader(
        title = stringResource(R.string.day_items_title, date.format(rememberDateFormat(R.string.pattern_day_month)).replace(".", "")),
        trailing = {
            Text(
                text = if (total == 0) "" else "$total",
                style = MaterialTheme.typography.labelMedium,
                color = Eink.Graphite,
            )
        },
    )

    Column(Modifier.weight(1f).fillMaxWidth()) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.day_items_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Eink.Graphite,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.day_items_add_event),
                        style = MaterialTheme.typography.titleMedium,
                        color = Eink.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .einkClickable(onClick = onNewEvent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            val slice = items.drop(current * PAGE_SIZE).take(PAGE_SIZE)
            slice.forEachIndexed { index, item ->
                when (item) {
                    is DayItem.Header -> GroupLabel(item.title, item.count)
                    is DayItem.Event -> EventRow(item.row, zone) { onOpenEvent(item.row.event.id) }
                    is DayItem.Task -> TaskRow(
                        row = item.row,
                        onToggle = { onToggleTask(item.row.task.id, it) },
                        onClick = { onOpenTask(item.row.task.id) },
                    )
                    is DayItem.Note -> NoteRow(item.note, zone) { onOpenNote(item.note) }
                }
                val next = slice.getOrNull(index + 1)
                // Sin raya antes de un rótulo: el rótulo ya separa.
                if (next != null && next !is DayItem.Header && item !is DayItem.Header) {
                    EinkDivider(Modifier.padding(start = 14.dp, end = 14.dp))
                }
            }
        }
    }

    if (pageCount > 1) {
        EinkDivider(color = Eink.Border)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            PagerArrow(Glyph.ChevronLeft, enabled = current > 0) { page = current - 1 }
            Text(
                text = "${current + 1}/$pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = Eink.Graphite,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            PagerArrow(Glyph.ChevronRight, enabled = current < pageCount - 1) { page = current + 1 }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "1/1",
                style = MaterialTheme.typography.labelMedium,
                color = Eink.Slate,
            )
        }
    }
}

@Composable
private fun GroupLabel(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(rememberLocale()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Eink.Graphite,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = Eink.Slate,
        )
    }
}

@Composable
private fun PagerArrow(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .width(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .einkClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EinkGlyph(glyph, size = 18.dp, tint = if (enabled) Eink.Black else Eink.Slate)
    }
}

@Composable
private fun EventRow(row: EventWithCalendar, zone: ZoneId, onClick: () -> Unit) {
    val event = row.event
    val accent = EinkPalette.forArgb(row.accentArgb)

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Barra de acento. Lleva el color del calendario, pero su función real
        // es marcar dónde empieza cada evento: funciona igual sin color.
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.width(46.dp)) {
            if (event.allDay) {
                Text(
                    text = stringResource(R.string.day_items_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = Eink.Black,
                )
                Text(
                    text = stringResource(R.string.day_items_day),
                    style = MaterialTheme.typography.labelSmall,
                    color = Eink.Graphite,
                )
            } else {
                Text(
                    text = resolveDateTime(event.startMillis, false, zone).toLocalTime().format(clock),
                    style = MaterialTheme.typography.labelLarge,
                    color = Eink.Black,
                )
                Text(
                    text = resolveDateTime(event.endMillis, false, zone).toLocalTime().format(clock),
                    style = MaterialTheme.typography.labelSmall,
                    color = Eink.Graphite,
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        Text(
            text = event.title.ifBlank { stringResource(R.string.common_untitled) },
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Un recordatorio del día: su marca para completarlo, el título y la lista. */
@Composable
private fun TaskRow(row: TaskWithList, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val task = row.task
    val completed = task.completedAt != null

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkRadioCheck(checked = completed, onCheckedChange = onToggle)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title.ifBlank { stringResource(R.string.common_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (completed) Eink.Slate else Eink.Black,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.listName,
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Slate,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // El punto lleva el color de la lista de Google a la que pertenece.
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(EinkPalette.forArgb(row.listColorArgb))
        )
    }
}

/** Una nota del día: miniatura, título (o primera línea transcrita, o la hora) y hora. */
@Composable
private fun NoteRow(note: InkNoteEntity, zone: ZoneId, onClick: () -> Unit) {
    val document = remember(note.id, note.updatedAt) { StrokeCodec.decode(note.strokesJson) }
    val time = Instant.ofEpochMilli(note.updatedAt).atZone(zone).toLocalTime().format(clock)
    val title = note.title?.takeIf { it.isNotBlank() }
        ?: note.recognizedFlat?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?: stringResource(R.string.note_at_time, time)

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkPreview(
            document = document,
            modifier = Modifier
                .size(width = 56.dp, height = 36.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(HairlineWidth, Eink.Hairline, RoundedCornerShape(5.dp))
                .padding(2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = time,
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Slate,
            )
        }
    }
}
