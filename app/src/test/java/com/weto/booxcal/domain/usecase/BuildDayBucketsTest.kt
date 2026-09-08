package com.weto.booxcal.domain.usecase

import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.EventEntity
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.util.toDayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
private val FROM = LocalDate.of(2026, 8, 31)
private val TO = LocalDate.of(2026, 10, 5)

private fun timed(
    title: String,
    start: LocalDateTime,
    end: LocalDateTime,
    zone: ZoneId = MADRID,
) = EventWithCalendar(
    event = EventEntity(
        id = title.hashCode().toLong(),
        calendarId = 1,
        title = title,
        startMillis = start.atZone(zone).toInstant().toEpochMilli(),
        endMillis = end.atZone(zone).toInstant().toEpochMilli(),
        allDay = false,
        updatedAt = 0,
    ),
    calendarName = "Principal",
    calendarColorArgb = 0,
    calendarWritable = true,
)

/** `end` es el primer día que ya NO ocupa, igual que en iCalendar. */
private fun allDay(title: String, start: LocalDate, endExclusive: LocalDate) = EventWithCalendar(
    event = EventEntity(
        id = title.hashCode().toLong(),
        calendarId = 1,
        title = title,
        startMillis = start.toDayMillis(),
        endMillis = endExclusive.toDayMillis(),
        allDay = true,
        updatedAt = 0,
    ),
    calendarName = "Principal",
    calendarColorArgb = 0,
    calendarWritable = true,
)

private fun task(title: String, due: LocalDate?) = TaskWithList(
    task = TaskEntity(
        id = title.hashCode().toLong(),
        taskListId = 1,
        title = title,
        dueDayMillis = due?.toDayMillis(),
        updatedAt = 0,
    ),
    listName = "Mis tareas",
    listColorArgb = 0,
)

private fun buckets(
    events: List<EventWithCalendar> = emptyList(),
    tasks: List<TaskWithList> = emptyList(),
    inkDays: Set<Long> = emptySet(),
) = buildDayBuckets(FROM, TO, events, tasks, inkDays, MADRID)

class BuildDayBucketsTest {

    @Test
    fun `un evento con hora cae solo en su dia`() {
        val day = LocalDate.of(2026, 9, 4)
        val result = buckets(
            events = listOf(
                timed(
                    "Dentista",
                    LocalDateTime.of(2026, 9, 4, 10, 0),
                    LocalDateTime.of(2026, 9, 4, 11, 0),
                )
            )
        )

        assertEquals(1, result.getValue(day).timed.size)
        assertTrue(result.getValue(day).banners.isEmpty())
        assertTrue(result.getValue(day.minusDays(1)).isEmpty)
        assertTrue(result.getValue(day.plusDays(1)).isEmpty)
    }

    @Test
    fun `un evento de un dia completo no invade el dia siguiente`() {
        val day = LocalDate.of(2026, 9, 4)
        val result = buckets(events = listOf(allDay("Festivo", day, day.plusDays(1))))

        assertEquals(1, result.getValue(day).banners.size)
        assertTrue(result.getValue(day.plusDays(1)).isEmpty)
    }

    @Test
    fun `un evento de varios dias aparece en cada uno de ellos`() {
        val start = LocalDate.of(2026, 9, 4)
        val result = buckets(events = listOf(allDay("Vacaciones", start, start.plusDays(3))))

        assertEquals(1, result.getValue(start).banners.size)
        assertEquals(1, result.getValue(start.plusDays(1)).banners.size)
        assertEquals(1, result.getValue(start.plusDays(2)).banners.size)
        assertTrue(result.getValue(start.plusDays(3)).isEmpty)
    }

    @Test
    fun `un evento que cruza medianoche ocupa los dos dias, como franja`() {
        val result = buckets(
            events = listOf(
                timed(
                    "Vuelo nocturno",
                    LocalDateTime.of(2026, 9, 4, 23, 0),
                    LocalDateTime.of(2026, 9, 5, 6, 0),
                )
            )
        )

        val first = result.getValue(LocalDate.of(2026, 9, 4))
        val second = result.getValue(LocalDate.of(2026, 9, 5))
        assertEquals(1, first.banners.size)
        assertEquals(1, second.banners.size)
        assertTrue(first.timed.isEmpty())
    }

    /**
     * Una reunión de 9:00 a 24:00 termina *en* el día siguiente según el reloj,
     * pero no lo ocupa. Sin esta corrección, media agenda aparecería duplicada.
     */
    @Test
    fun `un evento que acaba justo a medianoche no toca el dia siguiente`() {
        val result = buckets(
            events = listOf(
                timed(
                    "Guardia",
                    LocalDateTime.of(2026, 9, 4, 9, 0),
                    LocalDateTime.of(2026, 9, 5, 0, 0),
                )
            )
        )

        assertEquals(1, result.getValue(LocalDate.of(2026, 9, 4)).entryCount)
        assertTrue(result.getValue(LocalDate.of(2026, 9, 5)).isEmpty)
    }

    @Test
    fun `las tareas con vencimiento caen en su dia y las demas no aparecen`() {
        val day = LocalDate.of(2026, 9, 10)
        val result = buckets(tasks = listOf(task("Renovar DNI", day), task("Algún día", null)))

        assertEquals(1, result.getValue(day).tasks.size)
        assertEquals(1, result.values.sumOf { it.tasks.size })
    }

    @Test
    fun `lo que cae fuera del rango se descarta`() {
        val result = buckets(
            events = listOf(
                allDay("Del mes pasado", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2))
            ),
            tasks = listOf(task("Del año que viene", LocalDate.of(2027, 1, 1))),
        )

        assertTrue(result.values.all { it.isEmpty })
    }

    @Test
    fun `la marca de nota manuscrita se asigna al dia anclado`() {
        val day = LocalDate.of(2026, 9, 12)
        val result = buckets(inkDays = setOf(day.toDayMillis()))

        assertTrue(result.getValue(day).hasInkNote)
        assertTrue(!result.getValue(day.plusDays(1)).hasInkNote)
    }
}
