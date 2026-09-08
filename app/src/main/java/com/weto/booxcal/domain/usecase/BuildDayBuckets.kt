package com.weto.booxcal.domain.usecase

import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.util.MILLIS_PER_DAY
import com.weto.booxcal.util.resolveDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reparte eventos y tareas por día.
 *
 * Es el punto donde se aplica la zona horaria real y se corrige el margen
 * holgado con el que consulta SQL (ver `EventDao.observeRange`). Función pura:
 * todo lo delicado de fechas de esta app se puede probar desde aquí.
 */
fun buildDayBuckets(
    from: LocalDate,
    toExclusive: LocalDate,
    events: List<EventWithCalendar>,
    tasks: List<TaskWithList>,
    inkNoteDays: Set<Long>,
    zone: ZoneId,
): Map<LocalDate, DayBucket> {
    val banners = mutableMapOf<LocalDate, MutableList<EventWithCalendar>>()
    val timed = mutableMapOf<LocalDate, MutableList<EventWithCalendar>>()
    val byDayTasks = mutableMapOf<LocalDate, MutableList<TaskWithList>>()

    for (row in events) {
        val event = row.event
        val startDate = resolveDate(event.startMillis, event.allDay, zone)
        val lastDate = eventLastDate(
            startDate = startDate,
            endMillis = event.endMillis,
            allDay = event.allDay,
            zone = zone,
        )

        // Un evento de varios días se dibuja como franja aunque tenga hora: una
        // "reunión" de martes a jueves no pinta nada en la rejilla horaria.
        val isBanner = event.allDay || lastDate > startDate
        val target = if (isBanner) banners else timed

        var day = maxOf(startDate, from)
        val end = minOf(lastDate, toExclusive.minusDays(1))
        while (!day.isAfter(end)) {
            target.getOrPut(day) { mutableListOf() }.add(row)
            day = day.plusDays(1)
        }
    }

    for (row in tasks) {
        val due = row.task.dueDayMillis ?: continue
        val date = LocalDate.ofEpochDay(Math.floorDiv(due, MILLIS_PER_DAY))
        if (date < from || date >= toExclusive) continue
        byDayTasks.getOrPut(date) { mutableListOf() }.add(row)
    }

    val result = LinkedHashMap<LocalDate, DayBucket>()
    var day = from
    while (day < toExclusive) {
        val dayMillis = day.toEpochDay() * MILLIS_PER_DAY
        result[day] = DayBucket(
            date = day,
            banners = banners[day].orEmpty(),
            timed = timed[day].orEmpty().sortedBy { it.event.startMillis },
            tasks = byDayTasks[day].orEmpty(),
            hasInkNote = dayMillis in inkNoteDays,
        )
        day = day.plusDays(1)
    }
    return result
}

/**
 * Último día que ocupa un evento, inclusive.
 *
 * Dos trampas: el fin de un evento de día completo es **exclusivo** (igual que
 * en iCalendar), y un evento con hora que acaba justo a medianoche no ocupa el
 * día siguiente.
 */
private fun eventLastDate(
    startDate: LocalDate,
    endMillis: Long,
    allDay: Boolean,
    zone: ZoneId,
): LocalDate {
    if (allDay) {
        val exclusiveEnd = resolveDate(endMillis, true, zone)
        return maxOf(startDate, exclusiveEnd.minusDays(1))
    }
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)
    val endDate = end.toLocalDate()
    val adjusted =
        if (end.toLocalTime() == LocalTime.MIDNIGHT && endDate.isAfter(startDate)) {
            endDate.minusDays(1)
        } else {
            endDate
        }
    return maxOf(startDate, adjusted)
}
