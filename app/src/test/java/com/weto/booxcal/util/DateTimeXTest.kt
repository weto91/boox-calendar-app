package com.weto.booxcal.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DateTimeXTest {

    @Test
    fun `la rejilla del mes empieza en el inicio de semana elegido`() {
        // 1 de septiembre de 2026 es martes.
        val september = LocalDate.of(2026, 9, 15)
        assertEquals(
            LocalDate.of(2026, 8, 31),
            september.startOfMonthGrid(DayOfWeek.MONDAY),
        )
        assertEquals(
            LocalDate.of(2026, 8, 30),
            september.startOfMonthGrid(DayOfWeek.SUNDAY),
        )
    }

    @Test
    fun `un mes cabe en cinco o seis semanas segun donde caiga`() {
        assertEquals(5, LocalDate.of(2026, 9, 1).monthGridWeeks(DayOfWeek.MONDAY))
        // Agosto de 2026 empieza en sábado: necesita seis filas con semana en lunes.
        assertEquals(6, LocalDate.of(2026, 8, 1).monthGridWeeks(DayOfWeek.MONDAY))
    }

    @Test
    fun `febrero de un año no bisiesto que empieza en lunes cabe en cuatro`() {
        // Febrero de 2027 empieza en lunes y tiene 28 días: cuatro filas justas.
        assertEquals(4, LocalDate.of(2027, 2, 1).monthGridWeeks(DayOfWeek.MONDAY))
    }

    @Test
    fun `los millis de dia van y vuelven sin perder el dia`() {
        val date = LocalDate.of(1969, 7, 20)
        assertEquals(date, date.toDayMillis().toLocalDateFromDayMillis())
    }

    @Test
    fun `fechas anteriores a 1970 no se redondean hacia el dia equivocado`() {
        // División entera con negativos: sin floorDiv esto daría el día siguiente.
        val date = LocalDate.of(1965, 3, 2)
        assertEquals(date, date.toDayMillis().toLocalDateFromDayMillis())
    }
}
