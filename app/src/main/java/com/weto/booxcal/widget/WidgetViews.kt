package com.weto.booxcal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.weto.booxcal.R
import com.weto.booxcal.ui.nav.Routes
import java.time.LocalDate

/**
 * Las vistas de cada widget en cada tamaño.
 *
 * - **Pequeño**: lo justo de un vistazo. Hoy: el día en grande y cuántas
 *   cosas hay. Semana: los siete días con un punto en los que tienen algo.
 *   Notas: cuántas hay y la hoja nueva. Buscar: la barra.
 * - **Medio y grande**: cabecera en negativo y la lista, con más filas cuanto
 *   más alto el hueco.
 */
object WidgetViews {

    fun build(context: Context, appWidgetId: Int, kind: WidgetKind, options: Bundle?, summary: WidgetSummary): RemoteViews {
        if (AgendaWidgets.supportsResponsive) {
            val variants = AgendaWidgets.responsiveSizes().mapValues { (_, size) -> sized(context, appWidgetId, kind, size, summary) }
            return RemoteViews(variants)
        }
        return sized(context, appWidgetId, kind, AgendaWidgets.sizeFor(options), summary)
    }

    private fun sized(context: Context, appWidgetId: Int, kind: WidgetKind, size: WidgetSize, summary: WidgetSummary): RemoteViews =
        when {
            size == WidgetSize.SMALL && kind == WidgetKind.TODAY -> todaySmall(context, summary)
            size == WidgetSize.SMALL && (kind == WidgetKind.WEEK || kind == WidgetKind.AGENDA) -> weekSmall(context, summary)
            size == WidgetSize.SMALL && kind == WidgetKind.NOTES -> notesSmall(context, summary)
            size == WidgetSize.SMALL && kind == WidgetKind.SEARCH -> searchSmall(context)
            kind == WidgetKind.SEARCH -> searchList(context, appWidgetId)
            else -> list(context, appWidgetId, kind, size, summary)
        }

    // --- Listas ----------------------------------------------------------------

    private fun list(context: Context, appWidgetId: Int, kind: WidgetKind, size: WidgetSize, summary: WidgetSummary): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        views.setTextViewText(R.id.widget_title, summary.title)
        views.setTextViewText(R.id.widget_subtitle, summary.subtitle)
        views.setTextViewText(R.id.widget_empty, emptyText(kind))
        // La cabecera lleva al sitio que resume: la portada, el día o la semana.
        val today = LocalDate.now()
        val headerRoute = when (kind) {
            WidgetKind.WEEK -> AgendaWidgets.weekRoute(today)
            WidgetKind.NOTES -> Routes.dayNote(today)
            else -> Routes.HOME
        }
        views.setOnClickPendingIntent(R.id.widget_header, AgendaWidgets.openRoute(context, headerRoute, request(kind, 1)))

        // El botón de la cabecera: hoja nueva en las notas, nuevo evento en el resto.
        val (actionIcon, actionRoute, actionLabel) = when (kind) {
            WidgetKind.NOTES -> Triple(R.drawable.ic_widget_plus, Routes.dayNoteBlank(today), "Hoja nueva")
            else -> Triple(R.drawable.ic_widget_plus, Routes.event(date = today), "Evento nuevo")
        }
        views.setImageViewResource(R.id.widget_action, actionIcon)
        views.setContentDescription(R.id.widget_action, actionLabel)
        views.setOnClickPendingIntent(R.id.widget_action, AgendaWidgets.openRoute(context, actionRoute, request(kind, 2)))
        views.setViewVisibility(R.id.widget_action, View.VISIBLE)

        // En el grande cabe el reloj-calendario de la semana encima de la lista.
        views.setViewVisibility(R.id.widget_strip, if (size == WidgetSize.LARGE && summary.weekDays.isNotEmpty()) View.VISIBLE else View.GONE)
        if (summary.weekDays.isNotEmpty()) fillStrip(context, views, summary, request(kind, 30))

        bindList(context, views, appWidgetId, kind)
        return views
    }

    private fun searchList(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_search)
        views.setOnClickPendingIntent(R.id.widget_search_bar, AgendaWidgets.openRoute(context, Routes.SEARCH, request(WidgetKind.SEARCH, 1)))
        views.setTextViewText(R.id.widget_empty, emptyText(WidgetKind.SEARCH))
        bindList(context, views, appWidgetId, WidgetKind.SEARCH)
        return views
    }

    // El adaptador por servicio está marcado como obsoleto desde Android 12,
    // pero el sustituto (RemoteCollectionItems) no existe en versiones anteriores.
    @Suppress("DEPRECATION")
    private fun bindList(context: Context, views: RemoteViews, appWidgetId: Int, kind: WidgetKind) {
        val serviceIntent = Intent(context, AgendaWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AgendaWidgetService.EXTRA_KIND, kind.name)
            // Un URI distinto por widget: si no, Android reutiliza la misma fábrica para todos.
            data = Uri.parse("booxcal://widget/${kind.name}/$appWidgetId")
        }
        views.setRemoteAdapter(R.id.widget_list, serviceIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_list, AgendaWidgets.openTemplate(context, request(kind, 3)))
    }

    private fun emptyText(kind: WidgetKind) = when (kind) {
        WidgetKind.TODAY, WidgetKind.AGENDA -> "Nada previsto para hoy"
        WidgetKind.WEEK -> "Nada esta semana"
        WidgetKind.NOTES -> "Sin notas hoy. Toca + para empezar una."
        WidgetKind.SEARCH -> "Aún no hay notas con texto indexado"
    }

    // --- Pequeños --------------------------------------------------------------

    private fun todaySmall(context: Context, summary: WidgetSummary): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_small)
        views.setTextViewText(R.id.widget_weekday, summary.weekday)
        views.setTextViewText(R.id.widget_day_number, summary.dayNumber)
        views.setTextViewText(R.id.widget_month, summary.month)
        views.setTextViewText(R.id.widget_count, summary.subtitle)
        views.setOnClickPendingIntent(R.id.widget_root, AgendaWidgets.openRoute(context, AgendaWidgets.dayRoute(LocalDate.now()), request(WidgetKind.TODAY, 4)))
        return views
    }

    private fun weekSmall(context: Context, summary: WidgetSummary): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_week_small)
        views.setTextViewText(R.id.widget_title, summary.title)
        fillStrip(context, views, summary, request(WidgetKind.WEEK, 30))
        views.setOnClickPendingIntent(R.id.widget_title, AgendaWidgets.openRoute(context, AgendaWidgets.weekRoute(LocalDate.now()), request(WidgetKind.WEEK, 4)))
        return views
    }

    /** Los siete días de la tira: letra, número y el punto de «tiene algo»; hoy en negativo. */
    private fun fillStrip(context: Context, views: RemoteViews, summary: WidgetSummary, requestBase: Int) {
        summary.weekDays.take(7).forEachIndexed { index, cell ->
            val column = STRIP_COLUMNS[index]
            views.setTextViewText(column.letter, cell.letter)
            views.setTextViewText(column.number, cell.number)
            views.setTextViewText(column.mark, if (cell.busy) "●" else "")
            val fg = if (cell.isToday) Color.WHITE else Color.BLACK
            views.setInt(column.root, "setBackgroundColor", if (cell.isToday) Color.BLACK else Color.WHITE)
            views.setTextColor(column.letter, fg)
            views.setTextColor(column.number, fg)
            views.setTextColor(column.mark, fg)
            views.setOnClickPendingIntent(column.root, AgendaWidgets.openRoute(context, AgendaWidgets.dayRoute(cell.date), requestBase + index))
        }
    }

    private fun notesSmall(context: Context, summary: WidgetSummary): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_notes_small)
        val today = LocalDate.now()
        views.setTextViewText(R.id.widget_count, summary.noteCount.toString())
        views.setTextViewText(R.id.widget_subtitle, if (summary.noteCount == 1) "nota hoy" else "notas hoy")
        views.setOnClickPendingIntent(R.id.widget_root, AgendaWidgets.openRoute(context, Routes.dayNote(today), request(WidgetKind.NOTES, 4)))
        views.setOnClickPendingIntent(R.id.widget_action, AgendaWidgets.openRoute(context, Routes.dayNoteBlank(today), request(WidgetKind.NOTES, 2)))
        return views
    }

    private fun searchSmall(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_search_small)
        views.setOnClickPendingIntent(R.id.widget_search_bar, AgendaWidgets.openRoute(context, Routes.SEARCH, request(WidgetKind.SEARCH, 1)))
        return views
    }

    /** Un código de PendingIntent distinto por widget y por botón. */
    private fun request(kind: WidgetKind, slot: Int): Int = kind.ordinal * 100 + slot

    private class StripColumn(val root: Int, val letter: Int, val number: Int, val mark: Int)

    private val STRIP_COLUMNS = listOf(
        StripColumn(R.id.widget_col_1, R.id.widget_wd_1, R.id.widget_wn_1, R.id.widget_wm_1),
        StripColumn(R.id.widget_col_2, R.id.widget_wd_2, R.id.widget_wn_2, R.id.widget_wm_2),
        StripColumn(R.id.widget_col_3, R.id.widget_wd_3, R.id.widget_wn_3, R.id.widget_wm_3),
        StripColumn(R.id.widget_col_4, R.id.widget_wd_4, R.id.widget_wn_4, R.id.widget_wm_4),
        StripColumn(R.id.widget_col_5, R.id.widget_wd_5, R.id.widget_wn_5, R.id.widget_wm_5),
        StripColumn(R.id.widget_col_6, R.id.widget_wd_6, R.id.widget_wn_6, R.id.widget_wm_6),
        StripColumn(R.id.widget_col_7, R.id.widget_wd_7, R.id.widget_wn_7, R.id.widget_wm_7),
    )
}
