package com.weto.booxcal.ink

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Control del modo de refresco de la pantalla e-ink (§11 del scope).
 *
 * Va por reflexión a propósito. `EpdController` ha cambiado de paquete entre
 * versiones del SDK de Onyx, y este control es una mejora, no un requisito: si
 * no está, la app debe seguir funcionando igual en un emulador, que es donde se
 * desarrolla el 90% del tiempo. El canvas de trazo (`PenCanvasView`) sí llama al
 * SDK directamente, porque ahí un fallo silencioso sería peor que uno ruidoso.
 */
object EinkRefresh {

    private const val TAG = "EinkRefresh"

    /** DU: rápido, con fantasmas. GU: equilibrado. GC: completo, limpia todo. */
    enum class Mode { DU, GU, GC, HAND_WRITING_REPAINT_MODE }

    /**
     * Modo con el que el demo de Onyx repinta la zona de un trazo al soltar
     * el lápiz (`PartialRefreshRequest`). Si esta versión no lo trae, GU.
     */
    fun handwritingRepaintMode(view: View) = setViewMode(view, Mode.HAND_WRITING_REPAINT_MODE)

    val isOnyxDevice: Boolean by lazy {
        Build.MANUFACTURER.equals("onyx", ignoreCase = true) ||
            Build.BRAND.equals("onyx", ignoreCase = true)
    }

    private const val EPD_CLASS = "com.onyx.android.sdk.api.device.epd.EpdController"
    private const val UPDATE_MODE_CLASS = "com.onyx.android.sdk.api.device.epd.UpdateMode"

    private var unavailable = false

    /** Modo rápido: escritura a mano y desplazamiento. */
    fun fastMode(view: View) = setViewMode(view, Mode.DU)

    /** Modo legible: listas, cuadrícula del mes, formularios. */
    fun readableMode(view: View) = setViewMode(view, Mode.GU)

    /** Refresco completo. Al cambiar de pantalla, para quitar fantasmas. */
    fun fullRefresh(view: View) {
        setViewMode(view, Mode.GC)
        repaintEverything()
    }

    /**
     * Apaga el táctil capacitivo (el dedo, la palma) en toda la pantalla.
     *
     * Es lo que hace el demo de Onyx al empezar un trazo: mientras se escribe,
     * la palma apoyada no debe pulsar botones. El lápiz va por otro
     * digitalizador y no se ve afectado. Hay que devolverlo con
     * [enableFingerTouch] en cuanto se levanta el lápiz.
     */
    fun disableFingerTouch(context: Context) {
        val metrics = context.resources.displayMetrics
        val whole = arrayOf(Rect(0, 0, metrics.widthPixels, metrics.heightPixels))
        withEpd { epd, _ ->
            epd.getMethod("setAppCTPDisableRegion", Context::class.java, Array<Rect>::class.java)
                .invoke(null, context, whole)
        }
    }

    fun enableFingerTouch(context: Context) {
        withEpd { epd, _ ->
            epd.getMethod("appResetCTPDisableRegion", Context::class.java).invoke(null, context)
        }
    }

    /**
     * Permite que la vista vuelque a pantalla aunque el trazo rápido del SDK
     * tenga la región congelada. El demo de Onyx lo llama sobre su vista
     * antes de cada render con el lápiz activo, y sobre la raíz de la
     * actividad al arrancar. No está documentado; si este SDK no lo trae, se
     * ignora sin dar por perdido el resto del control de refresco.
     */
    fun enablePost(view: View) {
        withEpd(optional = true) { epd, _ ->
            epd.getMethod("enablePost", View::class.java, Integer.TYPE).invoke(null, view, 1)
        }
    }

    /**
     * Presión máxima que entrega el digitalizador del lápiz (el SDK da los
     * puntos con la presión en bruto, no normalizada). 0 si no se puede saber.
     */
    val maxTouchPressure: Float by lazy {
        var value = 0f
        withEpd(optional = true) { epd, _ ->
            value = (epd.getMethod("getMaxTouchPressure").invoke(null) as? Number)?.toFloat() ?: 0f
        }
        value
    }

    private fun setViewMode(view: View, mode: Mode) {
        withEpd { epd, updateModeClass ->
            val value = enumValue(updateModeClass, mode.name)
                ?: enumValue(updateModeClass, Mode.GU.name)
                ?: return@withEpd
            epd.getMethod("setViewDefaultUpdateMode", View::class.java, updateModeClass)
                .invoke(null, view, value)
        }
    }

    private fun repaintEverything() {
        withEpd { epd, updateModeClass ->
            val value = enumValue(updateModeClass, Mode.GC.name) ?: return@withEpd
            epd.getMethod("repaintEveryThing", updateModeClass).invoke(null, value)
        }
    }

    /**
     * @param optional la llamada es un extra: si falla no se marca el SDK como
     *   ausente, porque las demás sí pueden estar.
     */
    private inline fun withEpd(optional: Boolean = false, block: (Class<*>, Class<*>) -> Unit) {
        if (!isOnyxDevice || unavailable) return
        try {
            block(Class.forName(EPD_CLASS), Class.forName(UPDATE_MODE_CLASS))
        } catch (t: Throwable) {
            if (optional) {
                Log.d(TAG, "Llamada opcional al SDK de refresco no disponible (${t.javaClass.simpleName})")
                return
            }
            Log.d(TAG, "SDK de refresco no disponible (${t.javaClass.simpleName}); se ignora")
            unavailable = true
        }
    }

    /** Todo enum tiene un `valueOf(String)` estático sintético. */
    private fun enumValue(enumClass: Class<*>, name: String): Any? =
        runCatching { enumClass.getMethod("valueOf", String::class.java).invoke(null, name) }
            .getOrNull()
}
