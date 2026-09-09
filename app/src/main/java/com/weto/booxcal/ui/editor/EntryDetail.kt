package com.weto.booxcal.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.weto.booxcal.ink.InkDocument
import com.weto.booxcal.ui.ink.InkPreview
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkCard
import com.weto.booxcal.ui.theme.EinkCardHeader
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.EinkRadioCheck
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.util.format
import androidx.compose.runtime.remember

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun LocalDate.long(): String =
    format(rememberDateFormat(R.string.pattern_date_full), capitalize = true, locale = rememberLocale())

/** "sat 5 Sep": lower case and without the abbreviation dots, which look dirty. */
@Composable
private fun LocalDate.short(): String = format(rememberDateFormat(R.string.pattern_date_short)).replace(".", "")

/** Ancho máximo del contenido: a pantalla completa una línea de texto de lado a lado se lee mal. */
private val CONTENT_MAX_WIDTH = 820.dp

/**
 * Cuándo es un evento, en una línea.
 *
 * "Sábado 5 de septiembre de 2026, de 2:00 a 3:00". Si acaba otro día, se dice
 * el otro día; si es de día completo, se dice "todo el día".
 */
@Composable
fun EventForm.whenLabel(): String {
    val sameDay = startDate == endDate
    return when {
        allDay && sameDay -> stringResource(R.string.detail_when_all_day, startDate.long())
        allDay -> stringResource(R.string.detail_when_multi_all_day, startDate.short(), endDate.short())
        sameDay -> stringResource(R.string.detail_when_timed, startDate.long(), startTime.format(clock), endTime.format(clock))
        else -> stringResource(
            R.string.detail_when_multi_timed,
            startDate.short(), startTime.format(clock), endDate.short(), endTime.format(clock),
        )
    }
}

/** Lo que se lee al lado del bloque de fecha: la hora, o hasta cuándo dura. */
@Composable
private fun EventForm.whenPrimary(): String {
    val sameDay = startDate == endDate
    return when {
        allDay && sameDay -> stringResource(R.string.common_all_day)
        allDay -> stringResource(R.string.detail_primary_until_all_day, endDate.short())
        sameDay -> stringResource(R.string.detail_primary_timed, startTime.format(clock), endTime.format(clock))
        else -> stringResource(R.string.detail_primary_multi_timed, startTime.format(clock), endDate.short(), endTime.format(clock))
    }
}

/** "1 h 30 min", "45 min", "3 días". Nada si dura lo normal de un día completo. */
@Composable
private fun EventForm.durationLabel(): String? {
    if (allDay) {
        val days = ChronoUnit.DAYS.between(startDate, endDate) + 1
        return if (days > 1) stringResource(R.string.duration_days, days) else null
    }
    val minutes = Duration.between(
        LocalDateTime.of(startDate, startTime),
        LocalDateTime.of(endDate, endTime),
    ).toMinutes()
    if (minutes <= 0) return null
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> stringResource(R.string.duration_minutes, rest)
        rest == 0L -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, rest)
    }
}

/** "Hoy", "Mañana", "Dentro de 3 días"… o nada si queda lejos: la fecha ya está al lado. */
@Composable
private fun relativeDay(date: LocalDate, today: LocalDate): String? {
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        days == 0 -> stringResource(R.string.common_today)
        days == 1 -> stringResource(R.string.common_tomorrow)
        days == -1 -> stringResource(R.string.common_yesterday)
        days in 2..13 -> stringResource(R.string.relative_in_days, days)
        days in -13..-2 -> stringResource(R.string.relative_days_ago, -days)
        else -> null
    }
}

/** Lo que se dice de un vencimiento: si ya pasó, cuánto hace; si no, cuánto queda. */
@Composable
private fun dueLabel(due: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, due).toInt()
    return when {
        days == 0 -> stringResource(R.string.due_today)
        days == 1 -> stringResource(R.string.due_tomorrow)
        days == -1 -> stringResource(R.string.due_yesterday)
        days in 2..13 -> stringResource(R.string.due_in_days, days)
        days in -13..-2 -> stringResource(R.string.due_days_ago, -days)
        days > 0 -> stringResource(R.string.due_on, due.short())
        else -> stringResource(R.string.due_was_on, due.short())
    }
}

/**
 * Vista de un evento: lo que hay, y nada más.
 *
 * Sin "aviso: ninguno", sin campos vacíos. Editar es un botón aparte; abrir un
 * evento para mirarlo no debería ponerlo en peligro.
 */
@Composable
fun EventDetail(
    form: EventForm,
    ink: InkDocument,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    // El color del evento manda; si no tiene, el de su calendario; y si tampoco
    // (sin sincronizar aún), el de "evento" en el resto de la app.
    val accent = when {
        form.colorArgb != null -> EinkPalette.forArgb(form.colorArgb)
        form.calendarColorArgb != null -> EinkPalette.forArgb(form.calendarColorArgb)
        else -> Accent.Event
    }
    val chips = buildList {
        form.calendarName?.let { add(DetailChip(it, dot = accent)) }
        if (form.allDay) add(DetailChip(stringResource(R.string.common_all_day)))
        if (form.isRecurringInstance) add(DetailChip(stringResource(R.string.detail_repeats)))
    }

    DetailScaffold(
        kind = stringResource(R.string.common_event),
        glyph = Glyph.Today,
        accent = accent,
        title = form.title.ifBlank { stringResource(R.string.common_untitled) },
        chips = chips,
        onClose = onClose,
        onEdit = onEdit,
        onDelete = onDelete,
        modifier = modifier,
    ) {
        DetailCard(stringResource(R.string.common_when)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DateBlock(form.startDate)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        text = form.whenPrimary(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Eink.Black,
                    )
                    val secondary = listOfNotNull(
                        relativeDay(form.startDate, today),
                        form.durationLabel(),
                    ).joinToString(" · ")
                    Text(
                        text = secondary.ifEmpty { form.startDate.long() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Eink.Graphite,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        val hasDetails = form.calendarName != null || form.reminderMinutes != null ||
            form.location.isNotBlank() || form.isRecurringInstance
        if (hasDetails) {
            DetailCard(stringResource(R.string.detail_details)) {
                form.calendarName?.let {
                    InfoRow(Glyph.Today, accent, stringResource(R.string.common_calendar), it)
                }
                form.location.takeIf { it.isNotBlank() }?.let {
                    InfoRow(Glyph.Pin, Accent.Place, stringResource(R.string.common_place), it)
                }
                form.reminderMinutes?.let { minutes ->
                    val label = REMINDER_OPTIONS.firstOrNull { it.minutes == minutes }?.let { stringResource(it.label) }
                        ?: stringResource(R.string.duration_minutes, minutes)
                    InfoRow(
                        Glyph.Bell, Accent.Alarm, stringResource(R.string.common_alert),
                        if (minutes == 0) stringResource(R.string.detail_alert_at_time) else stringResource(R.string.detail_alert_before, label),
                        secondary = stringResource(R.string.detail_alert_hint),
                    )
                }
                if (form.isRecurringInstance) {
                    InfoRow(
                        Glyph.Undo, Accent.Time, stringResource(R.string.detail_repetition),
                        stringResource(R.string.detail_part_of_series),
                        secondary = stringResource(R.string.detail_series_hint),
                    )
                }
            }
        }

        form.description.takeIf { it.isNotBlank() }?.let {
            DetailCard(stringResource(R.string.common_description)) { BodyText(it) }
        }
        InkCard(ink)
    }
}

@Composable
fun TaskDetail(
    form: TaskForm,
    ink: InkDocument,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val list = form.lists.firstOrNull { it.id == form.taskListId }
    val overdue = !form.completed && form.dueDate?.isBefore(today) == true
    val chips = buildList {
        add(DetailChip(stringResource(if (form.completed) R.string.common_completed else R.string.common_pending), filled = form.completed))
        if (overdue) add(DetailChip(stringResource(R.string.detail_overdue), dot = Accent.Today))
        list?.let { add(DetailChip(it.name, dot = EinkPalette.forArgb(it.colorArgb))) }
    }

    DetailScaffold(
        kind = stringResource(R.string.common_reminder),
        glyph = Glyph.Bell,
        accent = Accent.Reminder,
        title = form.title.ifBlank { stringResource(R.string.common_untitled) },
        chips = chips,
        onClose = onClose,
        onEdit = onEdit,
        onDelete = onDelete,
        modifier = modifier,
        strike = form.completed,
    ) {
        DetailCard(stringResource(R.string.task_due)) {
            val due = form.dueDate
            if (due == null) {
                InfoRow(
                    Glyph.Today, Accent.Reminder, stringResource(R.string.common_date),
                    stringResource(R.string.detail_no_due_date),
                    secondary = stringResource(R.string.detail_no_due_hint),
                )
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DateBlock(due)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = if (form.completed) stringResource(R.string.common_completed) else dueLabel(due, today),
                            style = MaterialTheme.typography.titleLarge,
                            color = Eink.Black,
                        )
                        Text(
                            text = due.long(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Eink.Graphite,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        DetailCard(stringResource(R.string.task_status)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EinkRadioCheck(checked = form.completed, onCheckedChange = onToggleCompleted)
                Column(Modifier.weight(1f).padding(start = 6.dp, end = 12.dp)) {
                    Text(
                        text = stringResource(if (form.completed) R.string.detail_done else R.string.detail_to_do),
                        style = MaterialTheme.typography.titleMedium,
                        color = Eink.Black,
                    )
                    Text(
                        text = list?.let { stringResource(R.string.detail_list_named, it.name) }.orEmpty() +
                            stringResource(R.string.task_status_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Eink.Graphite,
                    )
                }
                EinkButton(
                    label = stringResource(if (form.completed) R.string.detail_reopen else R.string.detail_mark_done),
                    onClick = { onToggleCompleted(!form.completed) },
                    emphasized = !form.completed,
                )
            }
        }

        form.notes.takeIf { it.isNotBlank() }?.let {
            DetailCard(stringResource(R.string.common_notes_field)) { BodyText(it) }
        }
        InkCard(ink)
    }
}

// --- Piezas ------------------------------------------------------------------

/** Etiqueta de la cabecera: calendario o lista con su color, estado, "se repite". */
private class DetailChip(val label: String, val dot: Color? = null, val filled: Boolean = false)

/**
 * Armazón de las dos vistas: barra con el tipo y las acciones, cabecera con
 * baldosa de color, tipo y título, y debajo las tarjetas.
 */
@Composable
private fun DetailScaffold(
    kind: String,
    glyph: Glyph,
    accent: Color,
    title: String,
    chips: List<DetailChip>,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    strike: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onClose, contentDescription = stringResource(R.string.common_back))
            Text(
                text = kind,
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            EinkIconButton(Glyph.Trash, onDelete, contentDescription = stringResource(R.string.common_delete), accent = Accent.Today)
            EinkButton(label = stringResource(R.string.common_edit), onClick = onEdit, emphasized = true)
        }
        EinkDivider(color = Eink.Black)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Hero(kind, glyph, accent, title, chips, strike)
                content()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Barra de color a la izquierda, baldosa, tipo en pequeño y el título grande. */
@Composable
private fun Hero(
    kind: String,
    glyph: Glyph,
    accent: Color,
    title: String,
    chips: List<DetailChip>,
    strike: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(accent)
        )
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EinkTile(glyph, accent, size = 50.dp, glyphSize = 28.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        text = kind.uppercase(rememberLocale()),
                        style = MaterialTheme.typography.labelLarge,
                        color = Eink.Graphite,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Eink.Black,
                        textDecoration = if (strike) TextDecoration.LineThrough else null,
                    )
                }
            }
            if (chips.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { Chip(it) }
                }
            }
        }
    }
}

@Composable
private fun Chip(chip: DetailChip) {
    val background = if (chip.filled) Eink.Black else Eink.White
    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .border(HairlineWidth, if (chip.filled) Eink.Black else Eink.Border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chip.dot?.let { color ->
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (chip.filled) Eink.White else Eink.Black,
            maxLines = 1,
        )
    }
}

/** Tarjeta de sección: cabecera con el tema y las filas debajo. */
@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    EinkCard(Modifier.fillMaxWidth()) {
        EinkCardHeader(title)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Hoja de calendario: el día de la semana, el número grande y el mes. */
@Composable
private fun DateBlock(date: LocalDate) {
    val locale = rememberLocale()
    val weekdayShort = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    val monthShort = remember(locale) { DateTimeFormatter.ofPattern("MMM yyyy", locale) }
    Column(
        Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(HairlineWidth, Eink.Border, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.format(weekdayShort).replace(".", "").uppercase(locale),
            style = MaterialTheme.typography.labelSmall,
            color = Eink.Graphite,
        )
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = Eink.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = date.format(monthShort).replace(".", "").uppercase(locale),
            style = MaterialTheme.typography.labelSmall,
            color = Eink.Graphite,
        )
    }
}

/** Baldosa, rótulo pequeño encima y el valor debajo: una ficha, no un formulario. */
@Composable
private fun InfoRow(
    glyph: Glyph,
    accent: Color,
    label: String,
    value: String,
    secondary: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EinkTile(glyph, accent, size = 36.dp, glyphSize = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = label.uppercase(rememberLocale()),
                style = MaterialTheme.typography.labelSmall,
                color = Eink.Graphite,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Eink.Graphite,
                )
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Eink.Black,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/** La nota manuscrita, en su tarjeta y a buen tamaño. */
@Composable
private fun InkCard(ink: InkDocument) {
    if (ink.isEmpty) return
    DetailCard(stringResource(R.string.common_handwritten_note)) {
        InkPreview(
            document = ink,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(HairlineWidth, Eink.Border, RoundedCornerShape(8.dp))
                .padding(6.dp),
        )
    }
}
