package com.weto.booxcal.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.weto.booxcal.MainActivity
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.model.CalendarViewMode
import com.weto.booxcal.ui.nav.Routes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "AgendaWidgets"

/**
 * Repintado y enlaces de los widgets.
 *
 * No se refrescan por tiempo: se repintan al terminar cada sincronización,
 * al guardar una nota, al salir de la app (por si se editó algo) y pasada
 * la medianoche, para cambiar de día. Tocar algo abre la app justo ahí:
 * el evento, el recordatorio, la nota, el día o el buscador.
 */
object AgendaWidgets {
    const val ACTION_REFRESH = "com.weto.booxcal.widget.REFRESH"

    /** Extra con el que la app abre directamente una ruta (ver `MainActivity`). */
    const val EXTRA_ROUTE = "com.weto.booxcal.widget.ROUTE"

    /** Repinta todos los widgets colocados, de todos los tipos. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        WidgetKind.entries.forEach { kind ->
            val ids = manager.getAppWidgetIds(component(context, kind))
            if (ids.isNotEmpty()) refresh(context, kind, ids)
        }
        scheduleMidnight(context)
    }

    /**
     * Repinta unos widgets concretos. Lee la base fuera del hilo principal.
     * El aviso a las listas está marcado como obsoleto desde Android 12 (el
     * sustituto no existe antes), pero sigue siendo lo que funciona.
     */
    @Suppress("DEPRECATION")
    fun refresh(context: Context, kind: WidgetKind, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        Graph.applicationScope.launch {
            runCatching {
                val summary = WidgetData.summary(kind)
                ids.forEach { id ->
                    val options = manager.getAppWidgetOptions(id)
                    manager.updateAppWidget(id, WidgetViews.build(app, id, kind, options, summary))
                }
                manager.notifyAppWidgetViewDataChanged(ids, com.weto.booxcal.R.id.widget_list)
            }.onFailure { Log.w(TAG, "No se pudo repintar el widget $kind", it) }
        }
    }

    fun component(context: Context, kind: WidgetKind): ComponentName =
        ComponentName(context, "${context.packageName}.widget.${kind.providerName}")

    /** Un aviso pasada la medianoche, para cambiar de día. Inexacto: no hace falta permiso. */
    fun scheduleMidnight(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val any = WidgetKind.entries.any { manager.getAppWidgetIds(component(context, it)).isNotEmpty() }
        if (!any) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TodayWidgetProvider::class.java).apply { action = ACTION_REFRESH }
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() +
            MIDNIGHT_MARGIN_MS
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending) }
    }

    // --- Enlaces ---------------------------------------------------------------

    /** Abre la app en una ruta concreta. `request` distingue los PendingIntent entre sí. */
    fun openRoute(context: Context, route: String, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(EXTRA_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Plantilla de una lista: cada fila pone su ruta como extra. */
    fun openTemplate(context: Context, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun fillIn(route: String): Intent = Intent().putExtra(EXTRA_ROUTE, route)

    fun dayRoute(date: LocalDate): String = Routes.calendar(CalendarViewMode.DAY, date)
    fun weekRoute(date: LocalDate): String = Routes.calendar(CalendarViewMode.WEEK, date)

    /**
     * Tamaño con el que pintar, según el hueco (en dp). A partir de Android
     * 12 se le dan a Android las tres versiones y elige él; antes, se mira
     * el hueco que informa el lanzador.
     */
    fun sizeFor(options: Bundle?): WidgetSize {
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        return when {
            width <= 0 && height <= 0 -> WidgetSize.MEDIUM
            width < SMALL_MAX_WIDTH_DP || height < SMALL_MAX_HEIGHT_DP -> WidgetSize.SMALL
            height >= LARGE_MIN_HEIGHT_DP -> WidgetSize.LARGE
            else -> WidgetSize.MEDIUM
        }
    }

    val supportsResponsive: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Las tres versiones para Android 12+: elige la mayor que quepa. */
    fun responsiveSizes(): Map<SizeF, WidgetSize> = linkedMapOf(
        SizeF(110f, 60f) to WidgetSize.SMALL,
        SizeF(SMALL_MAX_WIDTH_DP.toFloat(), SMALL_MAX_HEIGHT_DP.toFloat()) to WidgetSize.MEDIUM,
        SizeF(SMALL_MAX_WIDTH_DP.toFloat(), LARGE_MIN_HEIGHT_DP.toFloat()) to WidgetSize.LARGE,
    )

    private const val SMALL_MAX_WIDTH_DP = 200
    private const val SMALL_MAX_HEIGHT_DP = 100
    private const val LARGE_MIN_HEIGHT_DP = 220
    private const val MIDNIGHT_MARGIN_MS = 60_000L
}
