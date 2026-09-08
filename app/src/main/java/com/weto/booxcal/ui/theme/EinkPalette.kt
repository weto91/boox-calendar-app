package com.weto.booxcal.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.min

/**
 * Paleta de acento para pantalla e-ink **a color** (Kaleido 3 del Note Air 5C).
 *
 * El filtro de color del panel apaga la saturación a algo menos de la mitad de
 * lo que se vería en un LCD, y la capa de color va a un tercio de la resolución
 * del blanco y negro. De ahí las dos reglas de esta paleta:
 *
 *  - **Colores oscuros y saturados.** Un pastel se convierte en un gris sucio.
 *    Todos los tonos de aquí quedan por debajo del 45% de luminosidad, así que
 *    también funcionan como grises distinguibles si el color falla.
 *  - **El color nunca porta información sola.** Es redundante con la forma y la
 *    posición: la barra de un evento y el punto de un día se ven igual de bien
 *    en escala de grises.
 *
 * El texto sigue siendo negro sobre blanco, siempre. Ningún tono de estos se
 * usa para texto corrido.
 */
object EinkPalette {

    val Graphite = Color(0xFF3A3A3A)
    val Indigo = Color(0xFF2B4A8B)
    val Teal = Color(0xFF116A62)
    val Green = Color(0xFF3B6B2F)
    val Ochre = Color(0xFF8A6410)
    val Rust = Color(0xFF9B4A1E)
    val Crimson = Color(0xFF96253C)
    val Violet = Color(0xFF5B3A82)

    val all: List<Color> =
        listOf(Graphite, Indigo, Teal, Green, Ochre, Rust, Crimson, Violet)

    /** Tono de cada color cromático. El grafito queda fuera: es el acromático. */
    private val chromatic: List<Pair<Color, Float>> = listOf(
        Rust to 21f,
        Ochre to 41f,
        Green to 108f,
        Teal to 175f,
        Indigo to 221f,
        Violet to 268f,
        Crimson to 348f,
    )

    /**
     * Ajusta un color arbitrario —el que manda Google para cada calendario— al
     * tono más cercano de la paleta.
     *
     * Se conserva el color original en la base de datos y el ajuste se hace al
     * pintar: así, si algún día se cambia de panel o de paleta, el dato sigue
     * siendo el bueno.
     */
    fun forArgb(argb: Int?): Color {
        if (argb == null || argb == 0) return Graphite

        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        // Casi sin croma: en Kaleido saldría gris de todas formas.
        if (delta < 0.12f) return Graphite

        val rawHue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val hue = if (rawHue < 0f) rawHue + 360f else rawHue

        return chromatic.minByOrNull { (_, reference) -> hueDistance(hue, reference) }
            ?.first
            ?: Graphite
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val raw = abs(a - b)
        return min(raw, 360f - raw)
    }
}

/**
 * Color de cada función de la app.
 *
 * Vive aquí y no repartido por las pantallas para que "recordatorio" sea del
 * mismo tono en el menú, en la pestaña y en el formulario. Es lo que convierte
 * el color en algo que se aprende sin querer: siempre que algo sea ocre, es un
 * recordatorio.
 */
object Accent {
    val Event = EinkPalette.Indigo
    val Reminder = EinkPalette.Ochre
    val Note = EinkPalette.Violet
    val Agenda = EinkPalette.Teal
    val Search = EinkPalette.Green
    val Sync = EinkPalette.Teal
    val Today = EinkPalette.Crimson
    val Account = EinkPalette.Indigo
    val Settings = EinkPalette.Graphite
    val Time = EinkPalette.Teal
    val Place = EinkPalette.Rust
    val Alarm = EinkPalette.Crimson
    val List = EinkPalette.Green
}
