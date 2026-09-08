package com.weto.booxcal.ink

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ModelState {
    data object Unknown : ModelState
    data object Downloading : ModelState
    data object Ready : ModelState
    data class Unavailable(val reason: String) : ModelState
}

/**
 * OCR de tinta con ML Kit, en el dispositivo.
 *
 * Los trazos se pasan **como puntos**, no rasterizados a bitmap: ML Kit
 * reconoce mucho mejor con la trayectoria y el tiempo de escritura que con una
 * imagen, y rasterizar tiraría justo la información que le sirve.
 *
 * El modelo se descarga una vez y hace falta red para ello. A partir de ahí
 * todo es local.
 */
class InkRecognizer {

    private var recognizer: DigitalInkRecognizer? = null
    private var loadedTag: String? = null

    /**
     * Asegura que el modelo del idioma está en el dispositivo. Descarga si hace
     * falta, y solo entonces necesita red.
     */
    /**
     * Preparar y reconocer van de uno en uno: el lazo y el indexado de fondo
     * comparten este objeto, y cerrar el cliente en mitad de un reconocimiento
     * ajeno lo dejaba colgado.
     */
    private val lock = Mutex()

    suspend fun prepare(languageTag: String, allowDownload: Boolean = true): ModelState = lock.withLock {
        prepareLocked(languageTag, allowDownload)
    }

    private suspend fun prepareLocked(languageTag: String, allowDownload: Boolean): ModelState {
        if (loadedTag == languageTag && recognizer != null) return ModelState.Ready

        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
        } catch (t: Throwable) {
            null
        } ?: return ModelState.Unavailable("ML Kit no reconoce el idioma '$languageTag'")

        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()

        return try {
            val downloaded = manager.isModelDownloaded(model).await()
            if (!downloaded) {
                if (!allowDownload) return ModelState.Unavailable("Modelo no descargado")
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            close()
            recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            loadedTag = languageTag
            ModelState.Ready
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo preparar el modelo de $languageTag", t)
            ModelState.Unavailable(t.message ?: "Fallo descargando el modelo")
        }
    }

    /** Candidatos ordenados de más a menos probable. Lista vacía si no hay nada. */
    suspend fun recognize(document: InkDocument): Result<List<String>> = lock.withLock {
        val client = recognizer
            ?: return@withLock Result.failure(IllegalStateException("Llama antes a prepare()"))
        if (document.strokes.isEmpty()) return@withLock Result.success(emptyList())

        val ink = document.toMlKitInk()
        try {
            val result = client.recognize(ink).await()
            Result.success(result.candidates.map { it.text }.filter { it.isNotBlank() })
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo reconociendo tinta", t)
            Result.failure(t)
        }
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        loadedTag = null
    }

    /**
     * Los tiempos van estrictamente crecientes dentro de cada trazo. Los
     * puntos que entrega el SDK de Onyx llegan todos de golpe, con el mismo
     * instante, y a ML Kit le vale, pero no hay por qué fiarse de eso.
     */
    private fun InkDocument.toMlKitInk(): Ink {
        val builder = Ink.builder()
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val strokeBuilder = Ink.Stroke.builder()
            var last = Long.MIN_VALUE
            stroke.points.forEach { point ->
                val t = if (point.t > last) point.t else last + 1
                last = t
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, t))
            }
            builder.addStroke(strokeBuilder.build())
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "InkRecognizer"
    }
}

/**
 * Puente Task -> corrutina. Evita arrastrar kotlinx-coroutines-play-services
 * solo por esto.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    // Una tarea cancelada por ML Kit es un fallo para quien esperaba, no una
    // cancelación de su corrutina: si no, el estado se quedaba en
    // "Reconociendo…" para siempre.
    addOnCanceledListener {
        if (cont.isActive) cont.resumeWithException(IllegalStateException("Reconocimiento cancelado"))
    }
}
