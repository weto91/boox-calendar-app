package com.weto.booxcal.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weto.booxcal.util.startOfMonthGrid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.util.format

/**
 * Etiquetas de la cabecera de días.
 *
 * Se parte de la forma corta ("lun.", "mié.") y no de la estrecha: en español la
 * estrecha da "M" tanto para martes como para miércoles, y con tres letras el
 * día se lee sin ambigüedad.
 */
fun weekdayLabels(
    weekStart: DayOfWeek,
    chars: Int = 3,
    locale: Locale = Locale.getDefault(),
): List<String> =
    (0..6).map { offset ->
        weekStart.plus(offset.toLong())
            .getDisplayName(JavaTextStyle.SHORT, locale)
            .replace(".", "")
            .take(chars)
            .replaceFirstChar { it.titlecase(locale) }
    }

fun monthTitle(date: LocalDate, locale: Locale = Locale.getDefault()): String {
    val month = date.month.getDisplayName(JavaTextStyle.FULL, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$month ${date.year}"
}

@Composable
fun EinkDatePickerDialog(
    initial: LocalDate,
    weekStart: DayOfWeek,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    var visibleMonth by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    var selected by remember { mutableStateOf(initial) }
    val locale = rememberLocale()

    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.picker_choose_date), modifier = Modifier.width(360.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EinkButton("‹", { visibleMonth = visibleMonth.minusMonths(1) }, minWidth = 52.dp)
            Text(
                text = monthTitle(visibleMonth, locale),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            EinkButton("›", { visibleMonth = visibleMonth.plusMonths(1) }, minWidth = 52.dp)
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            weekdayLabels(weekStart, locale = locale).forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Eink.Graphite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        val gridStart = visibleMonth.startOfMonthGrid(weekStart)
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dow ->
                    val day = gridStart.plusDays((week * 7 + dow).toLong())
                    val inMonth = day.month == visibleMonth.month
                    val isSelected = day == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .background(if (isSelected) Eink.Black else Eink.White)
                            .einkClickable { selected = day },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isSelected -> Eink.White
                                inMonth -> Eink.Black
                                else -> Eink.Slate
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton(stringResource(R.string.common_today), {
                selected = LocalDate.now()
                visibleMonth = selected.withDayOfMonth(1)
            })
            if (onClear != null) EinkButton(stringResource(R.string.common_no_date), onClear)
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(stringResource(R.string.common_ok), { onSelected(selected) }, emphasized = true)
        }
    }
}

@Composable
fun EinkTimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onSelected: (LocalTime) -> Unit,
) {
    var time by remember { mutableStateOf(initial.withSecond(0).withNano(0)) }

    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.picker_choose_time), modifier = Modifier.width(320.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stepper(
                value = "%02d".format(time.hour),
                onUp = { time = time.plusHours(1) },
                onDown = { time = time.minusHours(1) },
            )
            Text(
                ":",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Stepper(
                value = "%02d".format(time.minute),
                onUp = { time = time.plusMinutes(5) },
                onDown = { time = time.minusMinutes(5) },
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(stringResource(R.string.common_ok), { onSelected(time) }, emphasized = true)
        }
    }
}

@Composable
private fun Stepper(value: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EinkButton("+", onUp, minWidth = 72.dp)
        Box(
            Modifier
                .width(72.dp)
                .height(56.dp)
                .border(HairlineWidth, Eink.Black, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(value, style = MaterialTheme.typography.displaySmall)
        }
        EinkButton("−", onDown, minWidth = 72.dp)
    }
}

/** Cuadrado relleno o hueco, para marcar el tipo de entrada en la rejilla. */
@Composable
fun EntryMarker(filled: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .then(
                if (filled) Modifier.background(Eink.Black)
                else Modifier.border(HairlineWidth, Eink.Black, RectangleShape)
            )
    )
}

// --- Cuándo -------------------------------------------------------------------

/** El «cuándo» de un evento: los días, si es de todo el día y, si no, sus horas. */
data class EventWhen(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allDay: Boolean,
    val startTime: LocalTime,
    val endTime: LocalTime,
) {
    val sameDay: Boolean get() = startDate == endDate
}

private val whenClock: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** One line that says when: "Wed 8 Sep, 10:00 to 11:00", "Mon 8 Sep to Wed 10 Sep, all day". */
@Composable
fun EventWhen.label(): String {
    val day = rememberDateFormat(R.string.pattern_date_short)
    val locale = rememberLocale()
    val start = startDate.format(day, capitalize = true, locale = locale)
    return when {
        !sameDay -> stringResource(R.string.when_label_multi_day, start, endDate.format(day, capitalize = true, locale = locale))
        allDay -> stringResource(R.string.when_label_all_day, start)
        else -> stringResource(R.string.when_label_timed, start, startTime.format(whenClock), endTime.format(whenClock))
    }
}

/**
 * Un solo selector para el cuándo de un evento, en vez de fecha de inicio,
 * hora, fecha de fin y hora por separado.
 *
 * Se toca un día: es el comienzo (y el fin, de momento). Se toca otro más
 * adelante: es el fin, y el evento pasa a ser de todo el día porque abarca
 * varios. Con un solo día elegido se puede marcar «todo el día» o, si no,
 * poner la hora de empezar y la de acabar (± de cuarto en cuarto de hora, o
 * tocando la hora para elegirla con precisión). Tocar un día teniendo ya un
 * tramo empieza de nuevo.
 */
@Composable
fun EinkWhenPickerDialog(
    initial: EventWhen,
    weekStart: DayOfWeek,
    onDismiss: () -> Unit,
    onSelected: (EventWhen) -> Unit,
) {
    var visibleMonth by remember { mutableStateOf(initial.startDate.withDayOfMonth(1)) }
    var start by remember { mutableStateOf(initial.startDate) }
    var end by remember { mutableStateOf(initial.endDate) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var startTime by remember { mutableStateOf(initial.startTime.withSecond(0).withNano(0)) }
    var endTime by remember { mutableStateOf(initial.endTime.withSecond(0).withNano(0)) }
    // El primer toque siempre pone el comienzo; el segundo, si es posterior, el fin.
    var awaitingEnd by remember { mutableStateOf(false) }
    // 0: la hora de empezar; 1: la de acabar. Null: ninguna abierta.
    var timePicker by remember { mutableStateOf<Int?>(null) }
    val locale = rememberLocale()

    fun tap(day: LocalDate) {
        if (awaitingEnd && day.isAfter(start)) {
            end = day
            allDay = true
            awaitingEnd = false
        } else {
            start = day
            end = day
            awaitingEnd = true
        }
    }

    fun moveStart(time: LocalTime) {
        val shift = java.time.Duration.between(startTime, time)
        startTime = time
        endTime = endTime.plus(shift)
    }

    fun moveEnd(time: LocalTime) {
        endTime = if (time.isBefore(startTime)) startTime else time
    }

    val current = EventWhen(start, end, allDay || start != end, startTime, endTime)

    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.common_when), modifier = Modifier.width(400.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EinkButton("‹", { visibleMonth = visibleMonth.minusMonths(1) }, minWidth = 52.dp)
            Text(
                text = monthTitle(visibleMonth, locale),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            EinkButton("›", { visibleMonth = visibleMonth.plusMonths(1) }, minWidth = 52.dp)
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            weekdayLabels(weekStart, locale = locale).forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Eink.Graphite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        val gridStart = visibleMonth.startOfMonthGrid(weekStart)
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dow ->
                    val day = gridStart.plusDays((week * 7 + dow).toLong())
                    val inMonth = day.month == visibleMonth.month
                    val isEdge = day == start || day == end
                    val inside = day.isAfter(start) && day.isBefore(end)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .background(if (isEdge) Eink.Black else Eink.White)
                            // Los días de en medio del tramo van con marco: se ve el tramo entero.
                            .then(if (inside) Modifier.border(HairlineWidth, Eink.Black, RectangleShape) else Modifier)
                            .einkClickable { tap(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isEdge -> Eink.White
                                inMonth -> Eink.Black
                                else -> Eink.Slate
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        EinkDivider()
        Spacer(Modifier.height(6.dp))

        if (start != end) {
            EinkHint(stringResource(R.string.picker_when_hint_multi_day))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.common_all_day),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Eink.Black,
                    modifier = Modifier.weight(1f),
                )
                EinkCheckbox(checked = allDay, onCheckedChange = { allDay = it })
            }
            if (!allDay) {
                TimeRow(stringResource(R.string.picker_starts), startTime, onChange = ::moveStart, onOpen = { timePicker = 0 })
                TimeRow(stringResource(R.string.picker_ends), endTime, onChange = ::moveEnd, onOpen = { timePicker = 1 })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = current.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = Eink.Graphite,
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton(stringResource(R.string.common_today), {
                tap(LocalDate.now())
                visibleMonth = start.withDayOfMonth(1)
            })
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(stringResource(R.string.common_ok), { onSelected(current) }, emphasized = true)
        }
    }

    timePicker?.let { which ->
        EinkTimePickerDialog(
            initial = if (which == 0) startTime else endTime,
            onDismiss = { timePicker = null },
            onSelected = { time ->
                if (which == 0) moveStart(time) else moveEnd(time)
                timePicker = null
            },
        )
    }
}

/** «Empieza  [−] 09:00 [+]»: de cuarto en cuarto de hora; la hora se toca para elegirla. */
@Composable
private fun TimeRow(
    label: String,
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            modifier = Modifier.weight(1f),
        )
        EinkButton("−", { onChange(time.minusMinutes(15)) }, minWidth = 48.dp)
        EinkButton(time.format(whenClock), onOpen, minWidth = 84.dp)
        EinkButton("+", { onChange(time.plusMinutes(15)) }, minWidth = 48.dp)
    }
}
