package com.weto.booxcal

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.weto.booxcal.di.Graph
import com.weto.booxcal.util.AppLocale
import com.weto.booxcal.widget.AgendaWidgets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BooxCalApp : Application(), Configuration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Lo primero, antes de que nadie toque el SDK de Onyx. El trazo rápido
        // entra por APIs ocultas del sistema, y desde Android 9 esas llamadas
        // fallan en silencio si la app no pide la exención. El demo oficial de
        // Onyx hace exactamente esto en su Application.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
                .onFailure { Log.w("BooxCalApp", "Sin exención de APIs ocultas", it) }
        }
        // Fuentes y recursos de PDFBox: sin esto, el primer PDF falla.
        PDFBoxResourceLoader.init(this)
        Graph.init(this)

        Graph.applicationScope.launch {
            val settings = Graph.settings.settings.first()
            Graph.syncScheduler.schedulePeriodic(settings.syncIntervalMinutes)
            // La retención se aplica también al arrancar: si el dispositivo
            // estuvo días apagado, la purga pendiente se resuelve al abrir.
            runCatching { Graph.taskRepository.applyRetention(settings) }
            runCatching { Graph.inkNoteRepository.deleteOrphans() }
            // Los widgets, con lo de hoy, y su aviso de medianoche.
            runCatching { AgendaWidgets.refresh(this@BooxCalApp) }
        }
    }
}
