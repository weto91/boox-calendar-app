package com.weto.booxcal.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val MILLIS_PER_DAY: Long = 86_400_000L
const val MILLIS_PER_MINUTE: Long = 60_000L

/**
 * Convenio de almacenamiento temporal de la app.
 *
 * Un evento o tarea guarda siempre `startMillis`/`endMillis` en epoch millis UTC,
 * pero su significado depende de `allDay`:
 *
 *  - `allDay = false`: instante real. Se renderiza en la zona del dispositivo.
 *  - `allDay = true`: medianoche **UTC** del día correspondiente, es decir
 *    `epochDay * MILLIS_PER_DAY`. Se renderiza en UTC, nunca en la zona local.
 *
 * Así una nota de "todo el día" del 4 de septiembre sigue cayendo el 4 de
 * septiembre después de un vuelo, que es lo que espera cualquiera. Todas las
 * conversiones pasan por los helpers de este fichero: no llames a
 * `Instant.atZone` a pelo sobre un valor de día completo.
 */
fun LocalDate.toDayMillis(): Long = toEpochDay() * MILLIS_PER_DAY

fun Long.toLocalDateFromDayMillis(): LocalDate =
    LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))

fun LocalDateTime.toMillis(zone: ZoneId): Long =
    atZone(zone).toInstant().toEpochMilli()

fun Long.toLocalDateTime(zone: ZoneId): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

/** Fecha en la que cae un valor almacenado, respetando el convenio de arriba. */
fun resolveDate(millis: Long, allDay: Boolean, zone: ZoneId): LocalDate =
    if (allDay) millis.toLocalDateFromDayMillis()
    else Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun resolveDateTime(millis: Long, allDay: Boolean, zone: ZoneId): LocalDateTime =
    if (allDay) millis.toLocalDateFromDayMillis().atStartOfDay()
    else Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

/** Rango [inicio, fin) en millis que cubre un día completo en la zona dada. */
fun LocalDate.dayRangeMillis(zone: ZoneId): LongRange {
    val start = atStartOfDay(zone).toInstant().toEpochMilli()
    val end = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start until end
}

/** Primer día de la cuadrícula mensual, alineado al inicio de semana elegido. */
fun LocalDate.startOfMonthGrid(weekStart: DayOfWeek): LocalDate {
    val first = withDayOfMonth(1)
    val shift = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
    return first.minusDays(shift.toLong())
}

fun LocalDate.startOfWeek(weekStart: DayOfWeek): LocalDate {
    val shift = ((dayOfWeek.value - weekStart.value) + 7) % 7
    return minusDays(shift.toLong())
}

/** Número de filas (semanas) que necesita el mes para dibujarse entero: 4, 5 o 6. */
fun LocalDate.monthGridWeeks(weekStart: DayOfWeek): Int {
    val gridStart = startOfMonthGrid(weekStart)
    val monthEnd = withDayOfMonth(lengthOfMonth())
    val days = ChronoUnit.DAYS.between(gridStart, monthEnd) + 1
    return ((days + 6) / 7).toInt()
}

fun LocalTime.roundToNextHalfHour(): LocalTime {
    val minute = minute
    return when {
        minute == 0 -> withMinute(0).withSecond(0).withNano(0)
        minute <= 30 -> withMinute(30).withSecond(0).withNano(0)
        else -> plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }
}
