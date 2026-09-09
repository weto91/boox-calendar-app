package com.weto.booxcal.domain.model

import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.dao.TaskWithList
import java.time.LocalDate

/**
 * Lo que hay que pintar en un día.
 *
 * `banners` son los eventos que ocupan el día entero o cruzan de un día a otro;
 * se dibujan como franja. `timed` son los de hora concreta.
 *
 * Las tareas van aparte a propósito: mostrarlas junto a los eventos, pero
 * distinguibles de un vistazo, es el punto diferencial del proyecto (§6).
 */
data class DayBucket(
    val date: LocalDate,
    val banners: List<EventWithCalendar> = emptyList(),
    val timed: List<EventWithCalendar> = emptyList(),
    val tasks: List<TaskWithList> = emptyList(),
    val hasInkNote: Boolean = false,
) {
    val isEmpty: Boolean
        get() = banners.isEmpty() && timed.isEmpty() && tasks.isEmpty() && !hasInkNote

    val entryCount: Int get() = banners.size + timed.size + tasks.size
}

enum class CalendarViewMode { MONTH, WEEK, DAY }
