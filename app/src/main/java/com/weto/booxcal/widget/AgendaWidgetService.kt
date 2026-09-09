package com.weto.booxcal.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.weto.booxcal.R
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.StrokeCodec
import kotlinx.coroutines.runBlocking
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.weto.booxcal.util.AppLocale
import com.weto.booxcal.util.DateFormats

/** La lista de un widget: Android le pide las filas a la fábrica. */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val kind = runCatching { WidgetKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }
            .getOrDefault(WidgetKind.TODAY)
        return WidgetRowsFactory(applicationContext, kind)
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}

/** Lee la base (en el hilo del sistema, no en el principal) y pinta cada fila. */
private class WidgetRowsFactory(
    private val context: Context,
    private val kind: WidgetKind,
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetRow> = emptyList()
    private val thumbnails = HashMap<Long, Bitmap>()

    override fun onCreate() = Unit

    override fun onDestroy() {
        thumbnails.values.forEach { it.recycle() }
        thumbnails.clear()
    }

    override fun onDataSetChanged() {
        rows = runCatching { runBlocking { WidgetData.rows(kind) } }.getOrDefault(emptyList())
        // Las miniaturas de las notas que ya no están o cambiaron se sueltan.
        val wanted = rows.filterIsInstance<WidgetRow.Note>().associate { it.noteId to it.version }
        thumbnails.keys.toList().forEach { id -> if (id !in wanted) thumbnails.remove(id)?.recycle() }
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews = when (val row = rows.getOrNull(position)) {
        is WidgetRow.Day -> RemoteViews(context.packageName, R.layout.widget_row_day).apply {
            val locale = AppLocale.locale(context)
            val label = row.date.format(DateFormats.of(context, R.string.pattern_weekday_day)).replaceFirstChar { it.titlecase(locale) }
            setTextViewText(R.id.widget_day, if (row.isToday) label + context.getString(R.string.widget_day_today) else label)
            setTextViewText(R.id.widget_day_note, if (row.empty) context.getString(R.string.widget_day_free) else "")
            // Hoy, en negativo, para verlo de un vistazo.
            setInt(R.id.widget_day_row, "setBackgroundColor", if (row.isToday) Color.BLACK else Color.WHITE)
            setTextColor(R.id.widget_day, if (row.isToday) Color.WHITE else Color.BLACK)
            setTextColor(R.id.widget_day_note, if (row.isToday) Color.LTGRAY else Color.DKGRAY)
            setOnClickFillInIntent(R.id.widget_day_row, AgendaWidgets.fillIn(row.route))
        }

        is WidgetRow.Section -> RemoteViews(context.packageName, R.layout.widget_row_section).apply {
            setTextViewText(R.id.widget_section, row.title.uppercase(AppLocale.locale(context)))
        }

        is WidgetRow.Entry -> RemoteViews(context.packageName, R.layout.widget_row_entry).apply {
            setTextViewText(R.id.widget_time, row.time)
            setTextViewText(R.id.widget_entry_title, row.title)
            setInt(R.id.widget_bar, "setBackgroundColor", row.colorArgb or (0xFF shl 24))
            setTextColor(R.id.widget_entry_title, if (row.done) Color.GRAY else Color.BLACK)
            setTextColor(R.id.widget_time, if (row.isTask) Color.DKGRAY else Color.BLACK)
            setOnClickFillInIntent(R.id.widget_entry_row, AgendaWidgets.fillIn(row.route))
        }

        is WidgetRow.Note -> RemoteViews(context.packageName, R.layout.widget_row_note).apply {
            setTextViewText(R.id.widget_note_title, row.title)
            setTextViewText(R.id.widget_note_detail, row.detail)
            thumbnail(row)?.let { setImageViewBitmap(R.id.widget_thumb, it) }
            setOnClickFillInIntent(R.id.widget_note_row, AgendaWidgets.fillIn(row.route))
        }

        null -> RemoteViews(context.packageName, R.layout.widget_row_section)
    }

    /** La miniatura de la primera página, pintada una vez por versión de la nota. */
    private fun thumbnail(row: WidgetRow.Note): Bitmap? {
        thumbnails[row.noteId]?.let { return it }
        return runCatching {
            val note = runBlocking { Graph.inkNoteRepository.getById(row.noteId) } ?: return null
            val page = StrokeCodec.decodeNotebook(note.strokesJson).page(0)
            NoteThumbnail.render(page, THUMB_WIDTH_PX, THUMB_HEIGHT_PX)
        }.getOrNull()?.also { thumbnails[row.noteId] = it }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 4
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    private companion object {
        const val THUMB_WIDTH_PX = 240
        const val THUMB_HEIGHT_PX = 180
    }
}
