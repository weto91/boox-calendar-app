package com.weto.booxcal.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weto.booxcal.di.Graph
import com.weto.booxcal.widget.AgendaWidgets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = Graph.syncEngine.syncAll()
        // Las notas van después y no tumban la pasada: su error queda en
        // Ajustes, junto a la carpeta de Drive.
        runCatching { Graph.noteDriveSync.sync() }
            .onFailure { Log.w(TAG, "Notas con Drive: ${it.message}") }
        AgendaWidgets.refresh(applicationContext)
        if (outcome.ok) return Result.success()

        Log.w(TAG, "Sincronización con errores: ${outcome.error}")
        // Reintentar eternamente contra un error que no es de red (credenciales
        // revocadas, por ejemplo) solo gasta batería.
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_ATTEMPTS = 3
        const val PERIODIC_NAME = "booxcal-sync-periodic"
        const val ONE_SHOT_NAME = "booxcal-sync-now"
        const val SOON_NAME = "booxcal-sync-soon"
        const val NOTES_SOON_NAME = "booxcal-notes-soon"
    }
}

/** Solo las notas con Drive: lo que se dispara al guardar una nota. */
class NoteSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = Graph.noteDriveSync.sync()
        AgendaWidgets.refresh(applicationContext)
        if (outcome.error == null) return Result.success()
        return if (runAttemptCount < 2) Result.retry() else Result.success()
    }
}

class SyncScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 15 minutos es el mínimo que permite Android para trabajo periódico. En un
     * Boox, además, el gestor de energía de Onyx puede matar el proceso igual:
     * ver §11 y §14 del scope, y el aviso del primer arranque.
     */
    fun schedulePeriodic(intervalMinutes: Int) {
        val interval = intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Sube un cambio local en cuanto pase un momento. Es lo que llama cada
     * cambio hecho desde una lista (completar, borrar): antes esos cambios
     * solo viajaban con la pasada periódica, cada 15 minutos como poco, y
     * una tarea completada en la tablet tardaba eso en completarse en
     * Google. Los editores ya pedían `syncNow` al guardar.
     *
     * El retardo agrupa varios toques seguidos en una sola pasada: cada
     * llamada sustituye a la anterior, que casi siempre sigue esperando.
     */
    fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(SOON_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.SOON_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Sube una nota a Drive cuando lleve un rato sin cambios. Medio minuto:
     * mientras se escribe se guarda cada pocos segundos, y subir un PDF por
     * guardado sería un desperdicio.
     */
    fun syncNotesSoon() {
        val request = OneTimeWorkRequestBuilder<NoteSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(NOTES_SOON_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.NOTES_SOON_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Las notas con Drive, ahora. */
    fun syncNotesNow() {
        val request = OneTimeWorkRequestBuilder<NoteSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.NOTES_SOON_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(SyncWorker.PERIODIC_NAME)
        workManager.cancelUniqueWork(SyncWorker.ONE_SHOT_NAME)
        workManager.cancelUniqueWork(SyncWorker.SOON_NAME)
        workManager.cancelUniqueWork(SyncWorker.NOTES_SOON_NAME)
    }

    /** True mientras haya una sincronización en curso o encolada. */
    fun observeRunning(): Flow<Boolean> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_SHOT_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.SOON_NAME),
        ) { manual, soon -> (manual + soon).any { it.state.isActive } }

    /** True mientras las notas estén subiendo o bajando de Drive. */
    fun observeNotesRunning(): Flow<Boolean> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.NOTES_SOON_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_SHOT_NAME),
        ) { notes, manual -> (notes + manual).any { it.state == WorkInfo.State.RUNNING } }

    private val WorkInfo.State.isActive: Boolean
        get() = this == WorkInfo.State.RUNNING || this == WorkInfo.State.ENQUEUED

    private companion object {
        const val SOON_DELAY_SECONDS = 3L
        const val NOTES_SOON_DELAY_SECONDS = 30L
    }
}
