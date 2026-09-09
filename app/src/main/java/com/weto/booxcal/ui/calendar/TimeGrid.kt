package com.weto.booxcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.resolveDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberLocale

private val HOUR_HEIGHT = 54.dp
private val GUTTER_WIDTH = 50.dp
private const val MINUTES_PER_DAY = 24 * 60

/** Hora a la que se abre la rejilla: la madrugada no interesa a nadie. */
private const val FIRST_VISIBLE_HOUR = 7

/** Evento con hora ya colocado: minuto de inicio y fin, y carril si hay solapes. */
data class PositionedEvent(
    val row: EventWithCalendar,
    val startMinute: Int,
    val endMinute: Int,
    val lane: Int,
    val laneCount: Int,
)

/**
 * Reparte en carriles los eventos que se solapan.
 *
 * Se agrupan primero en racimos de eventos encadenados por solape, y el número
 * de carriles se calcula por racimo: así dos eventos de la mañana no estrechan
 * a los de la tarde.
 */
fun layoutTimedEvents(
    rows: List<EventWithCalendar>,
    date: LocalDate,
    zone: ZoneId,
): List<PositionedEvent> {
    data class Item(val row: EventWithCalendar, val start: Int, val end: Int)

    val items = rows.mapNotNull { row ->
        val event = row.event
        val start = resolveDateTime(event.startMillis, event.allDay, zone)
        val end = resolveDateTime(event.endMillis, event.allDay, zone)
        val startMinute =
            if (start.toLocalDate() < date) 0
            else start.toLocalTime().hour * 60 + start.toLocalTime().minute
        val endMinute =
            if (end.toLocalDate() > date) MINUTES_PER_DAY
            else end.toLocalTime().hour * 60 + end.toLocalTime().minute
        // Un evento sin duración sigue necesitando alto para poder tocarse.
        Item(row, startMinute.coerceIn(0, MINUTES_PER_DAY), max(endMinute, startMinute + 20))
    }.sortedWith(compareBy({ it.start }, { it.end }))

    val result = mutableListOf<PositionedEvent>()
    var cluster = mutableListOf<Item>()
    var clusterEnd = Int.MIN_VALUE

    fun flushCluster() {
        if (cluster.isEmpty()) return
        val laneEnds = mutableListOf<Int>()
        val assignment = cluster.map { item ->
            var lane = laneEnds.indexOfFirst { it <= item.start }
            if (lane == -1) {
                laneEnds.add(item.end)
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = item.end
            }
            item to lane
        }
        val laneCount = laneEnds.size
        assignment.mapTo(result) { (item, lane) ->
            PositionedEvent(item.row, item.start, item.end, lane, laneCount)
        }
        cluster = mutableListOf()
        clusterEnd = Int.MIN_VALUE
    }

    for (item in items) {
        if (cluster.isNotEmpty() && item.start >= clusterEnd) flushCluster()
        cluster.add(item)
        clusterEnd = max(clusterEnd, item.end)
    }
    flushCluster()
    return result
}

/**
 * Rejilla horaria compartida por la vista de semana (siete columnas) y la de
 * día (una). Las franjas de día completo y las tareas viven en la banda
 * superior, fuera de la rejilla, porque no tienen hora que respetar.
 */
@Composable
fun TimeGrid(
    days: List<DayBucket>,
    zone: ZoneId,
    selected: LocalDate,
    showDayHeaders: Boolean,
    onSelectDay: (LocalDate) -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val locale = rememberLocale()
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Se abre a primera hora de la mañana, no a medianoche.
    LaunchedEffect(Unit) {
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * FIRST_VISIBLE_HOUR).roundToPx() })
    }

    Column(modifier.fillMaxSize()) {
        if (showDayHeaders) {
            Row(Modifier.fillMaxWidth().background(Eink.White)) {
                Box(Modifier.width(GUTTER_WIDTH))
                days.forEach { bucket ->
                    val isToday = bucket.date == today
                    val isSelected = bucket.date == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .einkClickable { onSelectDay(bucket.date) }
                            .then(if (isSelected) Modifier.background(Eink.Black) else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = bucket.date.dayOfWeek
                                .getDisplayName(JavaTextStyle.SHORT, locale)
                                .take(2)
                                .uppercase(locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Eink.White else Eink.Graphite,
                        )
                        Text(
                            text = bucket.date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                isSelected -> Eink.White
                                isToday -> Eink.Black
                                else -> Eink.Black
                            },
                            textAlign = TextAlign.Center,
                        )
                        if (isToday && !isSelected) {
                            Box(Modifier.width(18.dp).height(2.dp).background(Eink.Black))
                        }
                    }
                }
            }
            EinkDivider(color = Eink.Black)
        }

        // Banda de día completo y recordatorios: mide lo que mide su
        // contenido (antes tenía un alto fijo que dejaba un hueco enorme
        // debajo), con las mismas columnas que la rejilla de horas.
        val hasBanner = days.any { it.banners.isNotEmpty() || it.tasks.isNotEmpty() }
        if (hasBanner) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .heightIn(min = 40.dp, max = 180.dp)
            ) {
                Box(
                    Modifier
                        .width(GUTTER_WIDTH)
                        .fillMaxHeight()
                        .gutterEdge()
                        .padding(end = 6.dp, top = 6.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        text = stringResource(R.string.timegrid_all_day),
                        style = MaterialTheme.typography.labelSmall,
                        color = Eink.Graphite,
                        textAlign = TextAlign.End,
                    )
                }
                days.forEach { bucket ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .columnEdge()
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        bucket.banners.forEach { row ->
                            BannerEntry(
                                row,
                                style = MaterialTheme.typography.labelMedium,
                                onClick = { onOpenEvent(row.event.id) },
                            )
                        }
                        bucket.tasks.forEach { row ->
                            TaskEntry(
                                row,
                                style = MaterialTheme.typography.labelMedium,
                                onToggle = { checked -> onToggleTask(row.task.id, checked) },
                                onClick = { onOpenTask(row.task.id) },
                            )
                        }
                    }
                }
            }
            EinkDivider(color = Eink.Border)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
        ) {
            Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
                HourGutter()
                days.forEach { bucket ->
                    DayColumn(
                        bucket = bucket,
                        zone = zone,
                        isToday = bucket.date == today,
                        onOpenEvent = onOpenEvent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Línea derecha del margen de horas, la misma en la banda y en la rejilla. */
private fun Modifier.gutterEdge(): Modifier = drawBehind {
    drawLine(Eink.Border, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
}

/** Línea derecha de una columna de día. */
private fun Modifier.columnEdge(): Modifier = drawBehind {
    drawLine(Eink.Hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
}

@Composable
private fun HourGutter() {
    Column(Modifier.width(GUTTER_WIDTH)) {
        repeat(24) { hour ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HOUR_HEIGHT)
                    .gutterEdge()
                    .padding(end = 6.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                if (hour > 0) {
                    // La hora va centrada sobre su línea, como en cualquier agenda.
                    Text(
                        text = "%d:00".format(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = Eink.Graphite,
                        modifier = Modifier.offset(y = (-7).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    bucket: DayBucket,
    zone: ZoneId,
    isToday: Boolean,
    onOpenEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = remember(bucket) { layoutTimedEvents(bucket.timed, bucket.date, zone) }
    // La hora actual se lee al componer: la rejilla se recompone al entrar y
    // al cambiar de día, que es lo que hace falta para una marca orientativa.
    val nowMinute = if (isToday) LocalTime.now().let { it.hour * 60 + it.minute } else -1

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .drawBehind {
                val stroke = 1.dp.toPx()
                val hourPx = size.height / 24f
                for (hour in 1 until 24) {
                    drawLine(
                        Eink.Hairline,
                        Offset(0f, hour * hourPx),
                        Offset(size.width, hour * hourPx),
                        stroke,
                    )
                }
                drawLine(
                    Eink.Hairline,
                    Offset(size.width, 0f),
                    Offset(size.width, size.height),
                    stroke,
                )
                // La hora actual: una línea negra con su punto, solo en hoy.
                if (nowMinute >= 0) {
                    val y = size.height * nowMinute / MINUTES_PER_DAY
                    drawLine(Eink.Black, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                    drawCircle(Eink.Black, radius = 4.dp.toPx(), center = Offset(0f, y))
                }
            }
    ) {
        val columnWidth = maxWidth
        positioned.forEach { item ->
            val laneWidth = columnWidth / item.laneCount
            Box(
                Modifier
                    .offset(
                        x = laneWidth * item.lane,
                        y = HOUR_HEIGHT * (item.startMinute / 60f),
                    )
                    .width(laneWidth)
                    .height(HOUR_HEIGHT * ((item.endMinute - item.startMinute) / 60f))
                    .padding(end = 2.dp, bottom = 1.dp)
                    .border(HairlineWidth, Eink.Black, RectangleShape)
                    .background(Eink.White)
                    .einkClickable { onOpenEvent(item.row.event.id) }
                    .padding(horizontal = 3.dp, vertical = 2.dp)
            ) {
                Column {
                    Text(
                        text = item.row.event.title.ifBlank { stringResource(R.string.common_untitled) },
                        style = MaterialTheme.typography.labelMedium,
                        color = Eink.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val duration = item.endMinute - item.startMinute
                    if (duration >= 45) {
                        Text(
                            text = formatEventTime(item.row, zone),
                            style = MaterialTheme.typography.labelSmall,
                            color = Eink.Graphite,
                        )
                    }
                }
            }
        }
    }
}

/** Minutos desde medianoche, útil para pruebas de la colocación. */
internal fun minuteOfDay(hour: Int, minute: Int): Int = min(hour * 60 + minute, MINUTES_PER_DAY)
