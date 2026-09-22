package com.weto.booxcal.ui.ink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Compuerta del lápiz para las ventanas flotantes de la propia app.
 *
 * El trazo rápido de Onyx captura el lápiz por debajo de Compose: con la
 * ventana de creación encima del lienzo, un toque del lápiz sobre uno de sus
 * botones acababa pintado en la nota rápida en vez de pulsado. Los diálogos
 * de Android no lo sufren (son otra ventana y el lienzo pierde el foco), pero
 * lo que flota dentro de la misma ventana sí. Mientras haya algo flotando,
 * todos los lienzos pausan el lápiz y lo reanudan al cerrarse.
 */
object InkGate {
    /** Cuántas ventanas flotantes hay abiertas; con más de cero, el lápiz espera. */
    var overlays by mutableIntStateOf(0)
        private set

    val blocked: Boolean get() = overlays > 0

    /**
     * Whether a canvas that lives at [ownerDepth] (how many overlays were
     * open when it was created) is covered by an overlay opened after it.
     * The handwriting sheet inside the creation window is at depth 1: the
     * window blocks the quick note below (depth 0), not the sheet itself.
     */
    fun blocks(ownerDepth: Int): Boolean = overlays > ownerDepth

    internal fun enter() { overlays++ }
    internal fun leave() { overlays = (overlays - 1).coerceAtLeast(0) }
}

/** Ponerlo dentro de lo que flota: el lápiz se pausa mientras esté en pantalla. */
@Composable
fun BlockInkWhileShown() {
    DisposableEffect(Unit) {
        InkGate.enter()
        onDispose { InkGate.leave() }
    }
}
