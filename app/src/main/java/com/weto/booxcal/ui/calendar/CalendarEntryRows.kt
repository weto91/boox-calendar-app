package com.weto.booxcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.resolveDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

fun formatEventTime(row: EventWithCalendar, zone: ZoneId): String {
    val event = row.event
    if (event.allDay) return ""
    return resolveDateTime(event.startMillis, false, zone).toLocalTime().format(hourFormatter)
}

/**
 * Franja de evento que ocupa el día entero o cruza varios. Se pinta invertida
 * para que se distinga de un evento con hora sin necesidad de color.
 */
@Composable
fun BannerEntry(
    row: EventWithCalendar,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            // Relleno con el color del calendario. El contraste con el texto
            // blanco lo garantiza la paleta: todos sus tonos son oscuros.
            .background(EinkPalette.forArgb(row.accentArgb))
            .then(if (onClick != null) Modifier.einkClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = row.event.title.ifBlank { "(sin título)" },
            style = style,
            color = Eink.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Evento con hora: marca sólida + hora + título. */
@Composable
fun TimedEntry(
    row: EventWithCalendar,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    showTime: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.einkClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(6.dp).background(EinkPalette.forArgb(row.accentArgb)))
        if (showTime) {
            Text(
                text = formatEventTime(row, zone),
                style = style,
                color = Eink.Graphite,
                maxLines = 1,
            )
        }
        Text(
            text = row.event.title.ifBlank { "(sin título)" },
            style = style,
            color = Eink.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Tarea dentro de una vista de calendario.
 *
 * La casilla hueca frente al cuadrado sólido del evento es la diferencia que
 * pide §6: en monocromo, la forma es lo único que separa una cosa de otra.
 */
@Composable
fun TaskEntry(
    row: TaskWithList,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    onToggle: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val completed = row.task.completedAt != null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.einkClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // La casilla crece con la letra: con texto mediano, un cuadradito de
        // 7 dp parecía una mota.
        val boxSize = if (style.fontSize.value >= 12f) 12.dp else 7.dp
        Box(
            Modifier
                .size(boxSize)
                .border(if (boxSize > 8.dp) 1.5.dp else HairlineWidth, EinkPalette.forArgb(row.listColorArgb), RectangleShape)
                .then(
                    if (completed) {
                        Modifier.drawBehind {
                            drawLine(
                                color = Eink.Black,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (onToggle != null) {
                        Modifier.einkClickable { onToggle(!completed) }
                    } else {
                        Modifier
                    }
                )
        )
        Text(
            text = row.task.title.ifBlank { "(sin título)" },
            style = style,
            color = if (completed) Eink.Slate else Eink.Black,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
