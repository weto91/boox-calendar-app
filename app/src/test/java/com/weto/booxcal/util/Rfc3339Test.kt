package com.weto.booxcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class Rfc3339Test {

    @Test
    fun `instante ida y vuelta`() {
        val millis = 1_772_000_000_000L
        val text = Rfc3339.formatInstant(millis)
        assertEquals(millis, Rfc3339.parseInstantOrNull(text))
    }

    @Test
    fun `acepta offset ademas de Z`() {
        val withOffset = Rfc3339.parseInstantOrNull("2026-09-04T12:00:00+02:00")
        val asUtc = Rfc3339.parseInstantOrNull("2026-09-04T10:00:00.000Z")
        assertEquals(asUtc, withOffset)
    }

    @Test
    fun `fecha de dia completo no se desplaza`() {
        val day = LocalDate.of(2026, 9, 4).toDayMillis()
        assertEquals("2026-09-04", Rfc3339.formatDate(day))
        assertEquals(day, Rfc3339.parseDateOrNull("2026-09-04"))
    }

    /**
     * El caso que rompe a casi todo el mundo: Google devuelve el vencimiento
     * como instante UTC. Interpretarlo en zona local mueve la tarea un día en
     * cualquier huso al oeste de Greenwich.
     */
    @Test
    fun `vencimiento de tarea conserva el dia que manda Google`() {
        val parsed = Rfc3339.parseTaskDueOrNull("2026-09-04T00:00:00.000Z")
        assertEquals(LocalDate.of(2026, 9, 4).toDayMillis(), parsed)
        assertEquals("2026-09-04T00:00:00.000Z", Rfc3339.formatTaskDue(parsed!!))
    }

    @Test
    fun `entradas vacias devuelven null`() {
        assertNull(Rfc3339.parseInstantOrNull(null))
        assertNull(Rfc3339.parseInstantOrNull(""))
        assertNull(Rfc3339.parseDateOrNull("no es una fecha"))
        assertNull(Rfc3339.parseTaskDueOrNull(null))
    }
}
