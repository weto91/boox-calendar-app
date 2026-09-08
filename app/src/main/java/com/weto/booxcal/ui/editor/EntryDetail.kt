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

private val longDate: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.getDefault())
private val shortDate: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val weekdayShort: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val monthShort: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun LocalDate.long(): String =
    format(longDate).replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** "sáb 5 sept": en minúscula y sin los puntos de abreviatura, que ensucian. */
private fun LocalDate.short(): String = format(shortDate).replace(".", "")

/** Ancho máximo del contenido: a pantalla completa una línea de texto de lado a lado se lee mal. */
private val CONTENT_MAX_WIDTH = 820.dp

/**
 * Cuándo es un evento, en una línea.
 *
 * "Sábado 5 de septiembre de 2026, de 2:00 a 3:00". Si acaba otro día, se dice
 * el otro día; si es de día completo, se dice "todo el día".
 */
fun EventForm.whenLabel(): String {
    val sameDay = startDate == endDate
    return when {
        allDay && sameDay -> "${startDate.long()} · todo el día"
        allDay -> "Del ${startDate.short()} al ${endDate.short()} · todo el día"
        sameDay -> "${startDate.long()}, de ${startTime.format(clock)} a ${endTime.format(clock)}"
        else -> "${startDate.short()} ${startTime.format(clock)} → ${endDate.short()} ${endTime.format(clock)}"
    }
}

/** Lo que se lee al lado del bloque de fecha: la hora, o hasta cuándo dura. */
private fun EventForm.whenPrimary(): String {
    val sameDay = startDate == endDate
    return when {
        allDay && sameDay -> "Todo el día"
        allDay -> "Hasta el ${endDate.short()}, todo el día"
        sameDay -> "De ${startTime.format(clock)} a ${endTime.format(clock)}"
        else -> "Desde las ${startTime.format(clock)} hasta el ${endDate.short()} a las ${endTime.format(clock)}"
    }
}

/** "1 h 30 min", "45 min", "3 días". Nada si dura lo normal de un día completo. */
private fun EventForm.durationLabel(): String? {
    if (allDay) {
        val days = ChronoUnit.DAYS.between(startDate, endDate) + 1
        return if (days > 1) "$days días" else null
    }
    val minutes = Duration.between(
        LocalDateTime.of(startDate, startTime),
        LocalDateTime.of(endDate, endTime),
    ).toMinutes()
    if (minutes <= 0) return null
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** "Hoy", "Mañana", "Dentro de 3 días"… o nada si queda lejos: la fecha ya está al lado. */
private fun relativeDay(date: LocalDate, today: LocalDate): String? {
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        days == 0 -> "Hoy"
        days == 1 -> "Mañana"
        days == -1 -> "Ayer"
        days in 2..13 -> "Dentro de $days días"
        days in -13..-2 -> "Hace ${-days} días"
        else -> null
    }
}

/** Lo que se dice de un vencimiento: si ya pasó, cuánto hace; si no, cuánto queda. */
private fun dueLabel(due: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, due).toInt()
    return when {
        days == 0 -> "Vence hoy"
        days == 1 -> "Vence mañana"
        days == -1 -> "Venció ayer"
        days in 2..13 -> "Vence dentro de $days días"
        days in -13..-2 -> "Venció hace ${-days} días"
        days > 0 -> "Vence el ${due.short()}"
        else -> "Venció el ${due.short()}"
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
        if (form.allDay) add(DetailChip("Todo el día"))
        if (form.isRecurringInstance) add(DetailChip("Se repite"))
    }

    DetailScaffold(
        kind = "Evento",
        glyph = Glyph.Today,
        accent = accent,
        title = form.title.ifBlank { "(sin título)" },
        chips = chips,
        onClose = onClose,
        onEdit = onEdit,
        onDelete = onDelete,
        modifier = modifier,
    ) {
        DetailCard("Cuándo") {
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
            DetailCard("Detalles") {
                form.calendarName?.let {
                    InfoRow(Glyph.Today, accent, "Calendario", it)
                }
                form.location.takeIf { it.isNotBlank() }?.let {
                    InfoRow(Glyph.Pin, Accent.Place, "Lugar", it)
                }
                form.reminderMinutes?.let { minutes ->
                    val label = REMINDER_OPTIONS.firstOrNull { it.minutes == minutes }?.label
                        ?: "$minutes min"
                    InfoRow(
                        Glyph.Bell, Accent.Alarm, "Aviso",
                        if (minutes == 0) "A la hora del evento" else "$label antes",
                        secondary = "Lo entrega Google en los dispositivos de la cuenta.",
                    )
                }
                if (form.isRecurringInstance) {
                    InfoRow(
                        Glyph.Undo, Accent.Time, "Repetición",
                        "Forma parte de una serie",
                        secondary = "Los cambios afectan solo a esta aparición.",
                    )
                }
            }
        }

        form.description.takeIf { it.isNotBlank() }?.let {
            DetailCard("Descripción") { BodyText(it) }
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
        add(DetailChip(if (form.completed) "Completada" else "Pendiente", filled = form.completed))
        if (overdue) add(DetailChip("Vencida", dot = Accent.Today))
        list?.let { add(DetailChip(it.name, dot = EinkPalette.forArgb(it.colorArgb))) }
    }

    DetailScaffold(
        kind = "Recordatorio",
        glyph = Glyph.Bell,
        accent = Accent.Reminder,
        title = form.title.ifBlank { "(sin título)" },
        chips = chips,
        onClose = onClose,
        onEdit = onEdit,
        onDelete = onDelete,
        modifier = modifier,
        strike = form.completed,
    ) {
        DetailCard("Vencimiento") {
            val due = form.dueDate
            if (due == null) {
                InfoRow(
                    Glyph.Today, Accent.Reminder, "Fecha",
                    "Sin fecha de vencimiento",
                    secondary = "Sale en la lista de recordatorios, no en el calendario.",
                )
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DateBlock(due)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = if (form.completed) "Completada" else dueLabel(due, today),
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

        DetailCard("Estado") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EinkRadioCheck(checked = form.completed, onCheckedChange = onToggleCompleted)
                Column(Modifier.weight(1f).padding(start = 6.dp, end = 12.dp)) {
                    Text(
                        text = if (form.completed) "Hecha" else "Por hacer",
                        style = MaterialTheme.typography.titleMedium,
                        color = Eink.Black,
                    )
                    Text(
                        text = list?.let { "Lista «${it.name}». " }.orEmpty() +
                            "El cambio sube a Google en unos segundos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Eink.Graphite,
                    )
                }
                EinkButton(
                    label = if (form.completed) "Reabrir" else "Marcar hecha",
                    onClick = { onToggleCompleted(!form.completed) },
                    emphasized = !form.completed,
                )
            }
        }

        form.notes.takeIf { it.isNotBlank() }?.let {
            DetailCard("Notas") { BodyText(it) }
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
            EinkIconButton(Glyph.ChevronLeft, onClose, contentDescription = "Volver")
            Text(
                text = kind,
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            EinkIconButton(Glyph.Trash, onDelete, contentDescription = "Borrar", accent = Accent.Today)
            EinkButton(label = "Editar", onClick = onEdit, emphasized = true)
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
                        text = kind.uppercase(Locale.getDefault()),
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
    Column(
        Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(HairlineWidth, Eink.Border, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.format(weekdayShort).replace(".", "").uppercase(Locale.getDefault()),
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
            text = date.format(monthShort).replace(".", "").uppercase(Locale.getDefault()),
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
                text = label.uppercase(Locale.getDefault()),
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
    DetailCard("Nota manuscrita") {
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
