package com.weto.booxcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.ui.theme.weekdayLabels
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val ENTRY_HEIGHT = 15.dp
private val DAY_NUMBER_HEIGHT = 22.dp

/**
 * Cuadrícula del mes. Ocupa todo el alto disponible y reparte las semanas a
 * partes iguales; cada celda decide cuántas entradas le caben.
 */
@Composable
fun MonthGrid(
    state: CalendarUiState,
    zone: ZoneId,
    onSelectDay: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(state.anchor)
    val weeks = ((state.rangeEnd.toEpochDay() - state.rangeStart.toEpochDay()) / 7).toInt()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Eink.White)
                .padding(vertical = 6.dp)
        ) {
            weekdayLabels(state.weekStart).forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Eink.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        EinkDivider(color = Eink.Black)

        repeat(weeks) { week ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                repeat(7) { dayOfWeek ->
                    val date = state.rangeStart.plusDays((week * 7 + dayOfWeek).toLong())
                    DayCell(
                        bucket = state.bucket(date),
                        zone = zone,
                        inCurrentMonth = YearMonth.from(date) == currentMonth,
                        isToday = date == today,
                        isSelected = date == state.selected,
                        lastColumn = dayOfWeek == 6,
                        lastRow = week == weeks - 1,
                        onClick = {
                            if (date == state.selected) onOpenDay(date) else onSelectDay(date)
                        },
                        onToggleTask = onToggleTask,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    bucket: DayBucket,
    zone: ZoneId,
    inCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    lastColumn: Boolean,
    lastRow: Boolean,
    onClick: () -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .drawBehind {
                val stroke = 1.dp.toPx()
                if (!lastColumn) {
                    drawLine(
                        Eink.Hairline,
                        Offset(size.width, 0f),
                        Offset(size.width, size.height),
                        stroke,
                    )
                }
                if (!lastRow) {
                    drawLine(
                        Eink.Hairline,
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                        stroke,
                    )
                }
                // El día seleccionado se marca con un recuadro grueso: en
                // monocromo, invertir la celda entera taparía su contenido.
                if (isSelected) {
                    val thick = 2.dp.toPx()
                    val radius = 8.dp.toPx()
                    drawRoundRect(
                        color = Eink.Black,
                        topLeft = Offset(thick / 2, thick / 2),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - thick,
                            size.height - thick,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = thick),
                    )
                }
            }
            .einkClickable(onClick = onClick)
            .padding(2.dp)
    ) {
        val capacity = ((maxHeight - DAY_NUMBER_HEIGHT) / ENTRY_HEIGHT).toInt().coerceAtLeast(0)

        Column(Modifier.fillMaxSize()) {
            DayNumber(
                bucket = bucket,
                inCurrentMonth = inCurrentMonth,
                isToday = isToday,
            )
            EntryStack(
                bucket = bucket,
                zone = zone,
                capacity = capacity,
                onToggleTask = onToggleTask,
            )
        }
    }
}

@Composable
private fun DayNumber(bucket: DayBucket, inCurrentMonth: Boolean, isToday: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .then(if (isToday) Modifier.background(Eink.Black) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = bucket.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isToday -> Eink.White
                    inCurrentMonth -> Eink.Black
                    else -> Eink.Slate
                },
            )
        }
        if (bucket.hasInkNote) {
            // Marca de nota manuscrita anclada al día.
            Text("✎", style = MaterialTheme.typography.labelSmall, color = Eink.Black)
        }
    }
}

@Composable
private fun ColumnScope.EntryStack(
    bucket: DayBucket,
    zone: ZoneId,
    capacity: Int,
    onToggleTask: (Long, Boolean) -> Unit,
) {
    if (capacity == 0) return

    // Orden de prioridad al recortar: franjas, luego horas, luego tareas.
    val banners = bucket.banners
    val timed = bucket.timed
    val tasks = bucket.tasks
    val total = banners.size + timed.size + tasks.size
    val overflow = total > capacity
    val visible = if (overflow) capacity - 1 else capacity

    var used = 0
    Column(Modifier.fillMaxWidth()) {
        for (row in banners) {
            if (used >= visible) break
            BannerEntry(row)
            used++
        }
        for (row in timed) {
            if (used >= visible) break
            TimedEntry(row, zone)
            used++
        }
        for (row in tasks) {
            if (used >= visible) break
            TaskEntry(row, onToggle = { checked -> onToggleTask(row.task.id, checked) })
            used++
        }
        if (overflow) {
            Text(
                text = "+${total - used}",
                style = MaterialTheme.typography.labelSmall,
                color = Eink.Graphite,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}
