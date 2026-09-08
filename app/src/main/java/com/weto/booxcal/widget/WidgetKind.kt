package com.weto.booxcal.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Los cinco widgets del escritorio. Todos comparten el mismo aire: tarjeta
 * blanca con marco negro, cabecera en negativo y lista debajo, en blanco y
 * negro y sin animaciones. Cada uno tiene tres tamaños (pequeño, medio y
 * grande) que Android elige según el hueco que se le dé.
 */
enum class WidgetKind(val providerName: String) {
    /** Los eventos y recordatorios de hoy. */
    TODAY("TodayWidgetProvider"),
    /** La semana en curso, día a día. */
    WEEK("WeekWidgetProvider"),
    /** Hoy en detalle y, debajo, el resto de la semana. */
    AGENDA("AgendaWidgetProvider"),
    /** Las notas manuscritas de hoy, con su miniatura, y una hoja nueva. */
    NOTES("NotesWidgetProvider"),
    /** Buscar en todo lo indexado, y las últimas notas con texto. */
    SEARCH("SearchWidgetProvider"),
}

/** El tamaño con el que se pinta el widget, según el hueco. */
enum class WidgetSize { SMALL, MEDIUM, LARGE }

/** Base de los cinco: pintan al colocarse, al cambiar de tamaño y al recibir el aviso de refresco. */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    abstract val kind: WidgetKind

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AgendaWidgets.refresh(context, kind, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        AgendaWidgets.refresh(context, kind, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AgendaWidgets.ACTION_REFRESH) AgendaWidgets.refresh(context)
    }
}

class TodayWidgetProvider : BaseWidgetProvider() {
    override val kind = WidgetKind.TODAY
}

class WeekWidgetProvider : BaseWidgetProvider() {
    override val kind = WidgetKind.WEEK
}

class AgendaWidgetProvider : BaseWidgetProvider() {
    override val kind = WidgetKind.AGENDA
}

class NotesWidgetProvider : BaseWidgetProvider() {
    override val kind = WidgetKind.NOTES
}

class SearchWidgetProvider : BaseWidgetProvider() {
    override val kind = WidgetKind.SEARCH
}
