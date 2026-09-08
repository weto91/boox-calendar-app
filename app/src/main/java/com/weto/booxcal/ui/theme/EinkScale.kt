package com.weto.booxcal.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Ancho lógico al que se lleva el lado corto de la pantalla.
 *
 * Es el valor que reparte el diseño. 730 dp deja el tamaño "compacto", que es el
 * que cabe bien en el Note Air 5C sin dejar la interfaz en cuatro botones
 * gigantes. Es un número de este proyecto y se ajusta aquí, no por usuario:
 * la app es para un modelo concreto de tablet y un ajuste que cada cual tiene
 * que descubrir y calibrar es un ajuste de más.
 */
private const val TARGET_SHORT_SIDE_DP = 730f

/**
 * Factor de escala en vigor.
 *
 * Se publica porque los `Dialog` no lo heredan: cada diálogo abre su propia
 * ventana con su propio `AndroidComposeView`, y ese vuelve a poner
 * `LocalDensity` a partir de los recursos del sistema. Por eso los menús y los
 * selectores salían diminutos mientras el resto de la app estaba bien.
 */
val LocalEinkScale = compositionLocalOf { 1f }

/**
 * Reescala la densidad de toda la interfaz.
 *
 * BooxOS declara una densidad baja para aprovechar los 1404 px del panel, así
 * que Android cree tener unos 900 dp de ancho y reparte el diseño como si fuera
 * una pantalla enorme: texto diminuto, botones imposibles de acertar con el dedo
 * y aire sobrante por todas partes.
 *
 * Corregirlo subiendo tamaño a tamaño sería interminable y quedaría atado a este
 * dispositivo. En su lugar se cambia la densidad una sola vez, aquí: dp y sp
 * escalan a la vez, el diseño mantiene sus proporciones y el mismo código sigue
 * valiendo para un emulador o para un Boox de otro tamaño.
 */
@Composable
fun EinkScaledDensity(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val shortSideDp = minOf(maxWidth.value, maxHeight.value)
        val automatic = if (shortSideDp > 0f) shortSideDp / TARGET_SHORT_SIDE_DP else 1f
        // El tope inferior en 1 evita encoger en un móvil, donde el problema no
        // existe; el superior, que un panel muy grande deje la app en cuatro
        // botones gigantes.
        val factor = automatic.coerceIn(1f, 2.5f)

        CompositionLocalProvider(
            LocalDensity provides Density(base.density * factor, base.fontScale),
            LocalEinkScale provides factor,
            content = content,
        )
    }
}

/**
 * Reaplica la escala dentro de una ventana nueva.
 *
 * Todo lo que se pinta en un `Dialog` tiene que pasar por aquí, o se verá al
 * tamaño que el sistema cree correcto — que en este panel es la mitad de
 * pequeño de lo que debería.
 */
@Composable
fun EinkWindowDensity(content: @Composable () -> Unit) {
    val factor = LocalEinkScale.current
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * factor, base.fontScale),
        content = content,
    )
}
