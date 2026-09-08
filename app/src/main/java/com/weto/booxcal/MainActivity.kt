package com.weto.booxcal

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ui.nav.BooxCalNavHost
import com.weto.booxcal.ui.theme.BooxCalTheme
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkScaledDensity
import com.weto.booxcal.ui.theme.EinkStatusStrip
import com.weto.booxcal.widget.AgendaWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var systemBars: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Borde a borde: la app recibe los insets de las barras y los respeta
        // ella misma (safeDrawingPadding). Antes los aplicaba el decorado y
        // Compose veía siempre cero: no había forma de saber si la barra de
        // estado del sistema estaba a la vista o el Boox la había escondido.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        systemBars = WindowInsetsControllerCompat(window, window.decorView).apply {
            // Barra negra con iconos claros (ver themes.xml): se ve la honre
            // el firmware o no.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
            // Si la app esconde la barra del sistema, deslizar desde arriba
            // la trae un momento: ahí van los controles de refresco del Boox.
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        handleRedirect(intent)
        handleWidgetRoute(intent)
        setContent {
            val settings by Graph.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val mode = settings.statusStrip
            LaunchedEffect(mode) { applyStatusBar(mode) }

            // Se lee aquí, antes de safeDrawingPadding, que consume los insets
            // para todo lo que cuelga de él.
            val systemBarShown = WindowInsets.statusBars.getTop(LocalDensity.current) > 0
            val showStrip = when (mode) {
                "app" -> true
                "system" -> false
                else -> !systemBarShown
            }

            BooxCalTheme {
                // El reescalado envuelve a toda la app. Los diálogos, que abren
                // su propia ventana, lo reaplican por su cuenta con
                // `EinkWindowDensity`: la ventana nueva vuelve a poner la
                // densidad del sistema y se perdería.
                EinkScaledDensity {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Eink.White)
                            // La app no debe dibujar bajo las barras del
                            // sistema: en un Boox la franja superior lleva los
                            // controles de refresco del propio dispositivo, y
                            // taparlos deja la tablet a medias.
                            .safeDrawingPadding()
                    ) {
                        // Hora, fecha y batería cuando la barra del sistema no
                        // está: el Boox la esconde o deja su franja en blanco.
                        if (showStrip) EinkStatusStrip()
                        BooxCalNavHost(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    /**
     * Por defecto la barra del sistema se esconde y la app pone la suya. Si el
     * usuario prefiere la del sistema, se pide expresamente (negra, iconos
     * claros) y se fuerza un repintado para que el e-ink la enseñe.
     */
    private fun applyStatusBar(mode: String) {
        statusStripMode = mode
        if (mode == "app") {
            systemBars.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            systemBars.show(WindowInsetsCompat.Type.statusBars())
            repaintSystemBarSoon()
        }
        systemBars.show(WindowInsetsCompat.Type.navigationBars())
    }

    private var statusStripMode: String = "app"

    /**
     * El trazo rápido del lápiz congela el panel en cuanto arranca (en la
     * portada, medio segundo después de abrir): lo que el sistema pinte en su
     * barra después de eso no llega al e-ink. Un repintado completo un poco
     * más tarde lo saca.
     */
    private fun repaintSystemBarSoon() {
        val root = window.decorView
        root.postDelayed({ EinkRefresh.fullRefresh(root) }, SYSTEM_BAR_REPAINT_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
        handleWidgetRoute(intent)
    }

    /** Un widget pide abrir la app en un sitio concreto: la ruta la consume el NavHost. */
    private fun handleWidgetRoute(intent: Intent?) {
        val route = intent?.getStringExtra(AgendaWidgets.EXTRA_ROUTE) ?: return
        intent.removeExtra(AgendaWidgets.EXTRA_ROUTE)
        Graph.pendingRoute.value = route
    }

    override fun onPause() {
        super.onPause()
        // Al salir, los widgets se enteran de lo que se haya cambiado.
        AgendaWidgets.refresh(applicationContext)
    }

    /**
     * La vuelta del navegador con el código de Google. Se canjea en el ámbito
     * de la aplicación: si la actividad se recrea a mitad, el canje sigue.
     */
    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!Graph.googleAuth.isRedirect(uri)) return
        intent.data = null
        Graph.applicationScope.launch {
            val result = Graph.googleAuth.handleRedirect(uri)
            if (result.isSuccess) Graph.syncScheduler.syncNow()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    result.fold(
                        onSuccess = { "Cuenta de Google conectada. Sincronizando…" },
                        onFailure = { "No se pudo conectar: ${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del navegador (OAuth) o de otra app, la pantalla suele
        // quedar con fantasmas: un refresco completo limpia y no cuesta nada
        // porque ya se va a repintar todo.
        window.decorView.post { EinkRefresh.fullRefresh(window.decorView) }
        if (statusStripMode != "app") repaintSystemBarSoon()
    }

    private companion object {
        const val SYSTEM_BAR_REPAINT_DELAY_MS = 1500L
    }
}
