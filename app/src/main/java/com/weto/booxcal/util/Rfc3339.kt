package com.weto.booxcal.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Traducción entre el convenio interno (millis + allDay) y RFC 3339, que es lo
 * que hablan tanto Google Calendar como Google Tasks. Aislado aquí para que el
 * día que entre CalDAV no haya que tocar más que el backend.
 */
object Rfc3339 {

    private val instantFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun formatInstant(millis: Long): String = instantFormatter.format(Instant.ofEpochMilli(millis))

    fun parseInstantOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }

    fun formatDate(dayMillis: Long): String = dayMillis.toLocalDateFromDayMillis().toString()

    fun parseDateOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value).toDayMillis() }.getOrNull()
    }

    /**
     * Google Tasks guarda `due` como RFC 3339 pero ignora por completo la hora:
     * su interfaz solo muestra la fecha. Normalizamos a medianoche UTC para que
     * el ida y vuelta no desplace el día.
     */
    fun formatTaskDue(dayMillis: Long): String =
        dayMillis.toLocalDateFromDayMillis().toString() + "T00:00:00.000Z"

    fun parseTaskDueOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        // Nos quedamos con la parte de fecha tal cual la manda Google, sin
        // convertir a zona local: convertir es justo lo que provoca que las
        // tareas se muevan un día arriba o abajo.
        val datePart = value.substringBefore('T')
        return runCatching { LocalDate.parse(datePart).toDayMillis() }.getOrNull()
    }

    fun timeZoneId(): String = ZoneId.systemDefault().id
}
