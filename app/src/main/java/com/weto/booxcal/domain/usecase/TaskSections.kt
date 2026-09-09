package com.weto.booxcal.domain.usecase

import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.util.MILLIS_PER_DAY
import java.time.LocalDate

enum class TaskGrouping { BY_DATE, BY_LIST }

/** What a section is; the screen puts the words to it. [LIST] carries the list name in [TaskSection.title]. */
enum class SectionKind { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, UNDATED, COMPLETED, LIST }

data class TaskSection(
    val kind: SectionKind,
    /** The list name for [SectionKind.LIST]; empty otherwise. */
    val title: String = "",
    val tasks: List<TaskWithList>,
    /** Las completadas se pintan tachadas y al final (§7). */
    val completed: Boolean = false,
    val overdue: Boolean = false,
)

/**
 * Agrupa las tareas para la pantalla de Tareas. Las completadas siempre van en
 * su propia sección al final, nunca mezcladas: es lo que pide §7 y lo que hace
 * que la lista siga siendo legible cuando hay treinta tareas hechas.
 */
fun buildTaskSections(
    tasks: List<TaskWithList>,
    today: LocalDate,
    grouping: TaskGrouping,
): List<TaskSection> {
    val (done, pending) = tasks.partition { it.task.completedAt != null }

    val sections = when (grouping) {
        TaskGrouping.BY_LIST -> pending
            .groupBy { it.listName }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (name, items) -> TaskSection(SectionKind.LIST, name, items.sortedWith(pendingOrder)) }

        TaskGrouping.BY_DATE -> {
            val todayMillis = today.toEpochDay() * MILLIS_PER_DAY
            val tomorrowMillis = todayMillis + MILLIS_PER_DAY
            val weekEndMillis = todayMillis + 7 * MILLIS_PER_DAY

            val overdue = pending.filter { it.task.dueDayMillis?.let { d -> d < todayMillis } == true }
            val dueToday = pending.filter { it.task.dueDayMillis == todayMillis }
            val dueTomorrow = pending.filter { it.task.dueDayMillis == tomorrowMillis }
            val thisWeek = pending.filter {
                val due = it.task.dueDayMillis ?: return@filter false
                due > tomorrowMillis && due < weekEndMillis
            }
            val later = pending.filter {
                val due = it.task.dueDayMillis ?: return@filter false
                due >= weekEndMillis
            }
            val undated = pending.filter { it.task.dueDayMillis == null }

            listOf(
                TaskSection(SectionKind.OVERDUE, tasks = overdue.sortedWith(pendingOrder), overdue = true),
                TaskSection(SectionKind.TODAY, tasks = dueToday.sortedWith(pendingOrder)),
                TaskSection(SectionKind.TOMORROW, tasks = dueTomorrow.sortedWith(pendingOrder)),
                TaskSection(SectionKind.THIS_WEEK, tasks = thisWeek.sortedWith(pendingOrder)),
                TaskSection(SectionKind.LATER, tasks = later.sortedWith(pendingOrder)),
                TaskSection(SectionKind.UNDATED, tasks = undated.sortedWith(pendingOrder)),
            )
        }
    }

    val completedSection = TaskSection(
        kind = SectionKind.COMPLETED,
        tasks = done.sortedByDescending { it.task.completedAt ?: 0L },
        completed = true,
    )

    return (sections + completedSection).filter { it.tasks.isNotEmpty() }
}

/** Vencimiento primero, y dentro del mismo día el orden manual del usuario. */
private val pendingOrder = compareBy<TaskWithList>(
    { it.task.dueDayMillis ?: Long.MAX_VALUE },
    { it.task.sortIndex },
    { it.task.id },
)
