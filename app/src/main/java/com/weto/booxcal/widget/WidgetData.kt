package com.weto.booxcal.widget

import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.isCompleted
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.domain.usecase.buildDayBuckets
import com.weto.booxcal.ui.nav.Routes
import com.weto.booxcal.util.resolveDateTime
import com.weto.booxcal.util.toDayMillis
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Una fila de una lista de widget. */
sealed class WidgetRow {
    /** Cabecera de un día (semana y agenda). */
    data class Day(val date: LocalDate, val isToday: Boolean, val empty: Boolean, val route: String) : WidgetRow()

    /** Título de sección («Hoy», «Resto de la semana»). */
    data class Section(val title: String) : WidgetRow()

    /** Un evento o un recordatorio. */
    data class Entry(
        val time: String,
        val title: String,
        val colorArgb: Int,
        val route: String,
        val done: Boolean,
        val isTask: Boolean,
    ) : WidgetRow()

    /** Una nota: con miniatura, título y una línea de detalle. */
    data class Note(val noteId: Long, val title: String, val detail: String, val route: String, val version: Long) : WidgetRow()
}

/** Lo que va en la cabecera del widget, ya calculado. */
data class WidgetSummary(
    val title: String,
    val subtitle: String,
    /** Para el widget pequeño de hoy: el día en grande. */
    val dayNumber: String = "",
    val weekday: String = "",
    val month: String = "",
    /** Para el widget pequeño de la semana: siete días con su marca. */
    val weekDays: List<WeekDayCell> = emptyList(),
    /** Para el de notas: cuántas hay hoy. */
    val noteCount: Int = 0,
)

data class WeekDayCell(val letter: String, val number: String, val date: LocalDate, val isToday: Boolean, val busy: Boolean)

/**
 * Lee la base para los widgets. Todo va con la misma función de reparto por
 * días que usa la app, así el widget y la portada cuentan lo mismo.
 */
object WidgetData {

    suspend fun weekStart(): DayOfWeek =
        runCatching { Graph.settings.settings.first().weekStart }.getOrDefault(DayOfWeek.MONDAY)

    /** Primer y último día (exclusivo) de la semana en curso. */
    fun week(today: LocalDate, weekStart: DayOfWeek): Pair<LocalDate, LocalDate> {
        val start = today.with(TemporalAdjusters.previousOrSame(weekStart))
        return start to start.plusDays(7)
    }

    suspend fun buckets(from: LocalDate, to: LocalDate): Map<LocalDate, DayBucket> {
        val zone = ZoneId.systemDefault()
        val events = Graph.eventRepository.observeRange(from, to, zone).first()
        val tasks = Graph.taskRepository.observeDueInRange(from, to).first()
        val noteDays = runCatching {
            Graph.inkNoteRepository.observeAnchoredDays().first().toSet()
        }.getOrDefault(emptySet())
        return buildDayBuckets(from, to, events, tasks, noteDays, zone)
    }

    // --- Cabeceras ---------------------------------------------------------------

    suspend fun summary(kind: WidgetKind): WidgetSummary {
        val today = LocalDate.now()
        val weekStart = weekStart()
        return when (kind) {
            WidgetKind.TODAY -> {
                val bucket = buckets(today, today.plusDays(1))[today] ?: DayBucket(today)
                WidgetSummary(
                    title = today.format(LONG_DAY).capitalized(),
                    subtitle = countText(bucket),
                    dayNumber = today.dayOfMonth.toString(),
                    weekday = today.format(WEEKDAY).capitalized(),
                    month = today.format(MONTH).capitalized(),
                )
            }

            WidgetKind.WEEK, WidgetKind.AGENDA -> {
                val (from, to) = week(today, weekStart)
                val buckets = buckets(from, to)
                val total = buckets.values.sumOf { it.entryCount }
                val cells = buckets.values.map { bucket ->
                    WeekDayCell(
                        letter = bucket.date.format(DAY_LETTER).uppercase(Locale.getDefault()).take(1),
                        number = bucket.date.dayOfMonth.toString(),
                        date = bucket.date,
                        isToday = bucket.date == today,
                        busy = bucket.entryCount > 0,
                    )
                }
                WidgetSummary(
                    title = if (kind == WidgetKind.AGENDA) today.format(LONG_DAY).capitalized() else weekTitle(from, to),
                    subtitle = if (kind == WidgetKind.AGENDA) countText(buckets[today] ?: DayBucket(today)) else plural(total, "entrada", "entradas"),
                    dayNumber = today.dayOfMonth.toString(),
                    weekday = today.format(WEEKDAY).capitalized(),
                    month = today.format(MONTH).capitalized(),
                    weekDays = cells,
                )
            }

            WidgetKind.NOTES -> {
                val notes = Graph.inkNoteRepository.observeForDay(today.toDayMillis()).first()
                WidgetSummary(
                    title = "Notas de hoy",
                    subtitle = today.format(DAY_MONTH),
                    noteCount = notes.size,
                )
            }

            WidgetKind.SEARCH -> WidgetSummary(title = "Buscar", subtitle = "notas, eventos y recordatorios")
        }
    }

    fun weekTitle(from: LocalDate, toExclusive: LocalDate): String {
        val last = toExclusive.minusDays(1)
        return if (from.month == last.month) {
            "Semana del ${from.dayOfMonth} al ${last.format(DAY_MONTH)}"
        } else {
            "Semana del ${from.format(DAY_MONTH)} al ${last.format(DAY_MONTH)}"
        }
    }

    private fun countText(bucket: DayBucket): String {
        val events = bucket.banners.size + bucket.timed.size
        val tasks = bucket.tasks.size
        if (events == 0 && tasks == 0) return "Nada previsto"
        return listOfNotNull(
            events.takeIf { it > 0 }?.let { plural(it, "evento", "eventos") },
            tasks.takeIf { it > 0 }?.let { plural(it, "recordatorio", "recordatorios") },
        ).joinToString(" · ")
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    // --- Listas ----------------------------------------------------------------

    suspend fun rows(kind: WidgetKind): List<WidgetRow> {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return when (kind) {
            WidgetKind.TODAY -> {
                val bucket = buckets(today, today.plusDays(1))[today] ?: DayBucket(today)
                entries(bucket, zone)
            }

            WidgetKind.WEEK -> {
                val (from, to) = week(today, weekStart())
                buckets(from, to).values.flatMap { bucket ->
                    listOf(WidgetRow.Day(bucket.date, bucket.date == today, bucket.entryCount == 0, AgendaWidgets.dayRoute(bucket.date))) +
                        entries(bucket, zone)
                }
            }

            WidgetKind.AGENDA -> {
                val (from, to) = week(today, weekStart())
                val buckets = buckets(from, to)
                val out = mutableListOf<WidgetRow>()
                out += WidgetRow.Section("Hoy")
                out += entries(buckets[today] ?: DayBucket(today), zone)
                val rest = buckets.values.filter { it.date > today }
                if (rest.isNotEmpty()) {
                    out += WidgetRow.Section("Resto de la semana")
                    rest.forEach { bucket ->
                        out += WidgetRow.Day(bucket.date, false, bucket.entryCount == 0, AgendaWidgets.dayRoute(bucket.date))
                        out += entries(bucket, zone)
                    }
                }
                out
            }

            WidgetKind.NOTES -> {
                Graph.inkNoteRepository.observeForDay(today.toDayMillis()).first().map { note ->
                    WidgetRow.Note(
                        noteId = note.id,
                        title = note.title?.takeIf { it.isNotBlank() } ?: note.firstLine() ?: "Nota de las ${note.createdAtClock(zone)}",
                        detail = note.createdAtClock(zone),
                        route = Routes.dayNote(today, note.id),
                        version = note.updatedAt,
                    )
                }
            }

            WidgetKind.SEARCH -> {
                Graph.inkNoteRepository.observeAll().first()
                    .filter { it.belongsToManager && !it.recognizedText.isNullOrBlank() }
                    .take(RECENT_DOCS)
                    .map { note ->
                        WidgetRow.Note(
                            noteId = note.id,
                            title = note.title?.takeIf { it.isNotBlank() } ?: note.firstLine() ?: "Nota",
                            detail = note.recognizedFlat.orEmpty().replace('\n', ' ').trim().take(80),
                            route = Routes.notes(note.id),
                            version = note.updatedAt,
                        )
                    }
            }
        }
    }

    private fun entries(bucket: DayBucket, zone: ZoneId): List<WidgetRow> {
        val out = mutableListOf<WidgetRow>()
        bucket.banners.forEach { row -> out += entry(row, "Todo el día") }
        bucket.timed.forEach { row ->
            out += entry(row, resolveDateTime(row.event.startMillis, false, zone).toLocalTime().format(CLOCK))
        }
        bucket.tasks.forEach { row -> out += task(row) }
        return out
    }

    private fun entry(row: EventWithCalendar, time: String) = WidgetRow.Entry(
        time = time,
        title = row.event.title,
        colorArgb = row.accentArgb,
        route = Routes.event(eventId = row.event.id),
        done = false,
        isTask = false,
    )

    private fun task(row: TaskWithList) = WidgetRow.Entry(
        time = if (row.task.isCompleted) "Hecho" else "Recordatorio",
        title = row.task.title,
        colorArgb = row.listColorArgb,
        route = Routes.task(taskId = row.task.id),
        done = row.task.isCompleted,
        isTask = true,
    )

    private fun InkNoteEntity.firstLine(): String? =
        recognizedFlat?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(60)

    private fun InkNoteEntity.createdAtClock(zone: ZoneId): String =
        java.time.Instant.ofEpochMilli(createdAt).atZone(zone).toLocalTime().format(CLOCK)

    private fun String.capitalized() = replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private const val RECENT_DOCS = 12
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.getDefault())
    private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.getDefault())
    private val DAY_LETTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
}
