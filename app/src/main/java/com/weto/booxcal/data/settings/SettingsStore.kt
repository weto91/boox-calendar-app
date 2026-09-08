package com.weto.booxcal.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "booxcal_settings")

data class AppSettings(
    /** Días que sobrevive una tarea completada. [NEVER_PURGE] = no purgar nunca. */
    val retentionDays: Int = 30,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val syncIntervalMinutes: Int = 15,
    val showTasksInCalendar: Boolean = true,
    val defaultCalendarId: Long = 0,
    val defaultTaskListId: Long = 0,
    val inkStrokeWidth: Float = 3f,
    /** Punta elegida: "PENCIL", "BALLPOINT" o "MARKER". */
    val inkTool: String = "PENCIL",
    /** Color del lápiz y el boli, y el del subrayador, que recuerda el suyo. */
    val inkColorArgb: Int = 0xFF000000.toInt(),
    val inkMarkerColorArgb: Int = 0xFFFF00FF.toInt(),
    /** Grosores en milímetros, uno por punta. */
    val inkPencilWidthMm: Float = 0.20f,
    val inkBallpointWidthMm: Float = 0.50f,
    val inkMarkerWidthMm: Float = 5.00f,
    /** Textura del lápiz (0 menos densa, 1 más densa) y sensibilidad a la presión de lápiz y boli (0..100 %). */
    val inkPencilTexture: Int = 0,
    val inkPencilPressure: Int = 50,
    val inkBallpointPressure: Int = 50,
    /** "auto", "sdk" o "touch". Ver [com.weto.booxcal.ink.PenMode]. */
    val penMode: String = "auto",
    val ocrLanguageTag: String = "es",
    /** Letra del texto transcrito. Ver [com.weto.booxcal.ink.InkFonts]. */
    val inkTextFont: String = "sans",
    /**
     * Qué se convierte con «toda la nota a texto»: lo escrito con "pencil",
     * con "ballpoint", o "any" (cualquier punta, y la app decide por la forma
     * qué es dibujo). Con una punta concreta, lo demás se queda como dibujo.
     */
    val inkTextTool: String = "ballpoint",
    /**
     * Barra superior con hora y batería: "app" (la app oculta la del sistema
     * y pone la suya; es lo fiable en el Boox, que deja la de Android en
     * blanco), "auto" (la de la app solo si el sistema esconde la suya) o
     * "system" (nunca la de la app).
     */
    val statusStrip: String = "app",
    /** Carpeta de Google Drive de las notas (id y nombre). Null: sin sincronizar notas. */
    val driveNotesFolderId: String? = null,
    val driveNotesFolderName: String? = null,
    val driveNotesSyncAt: Long = 0,
    val driveNotesError: String? = null,
    /** Versión del lector de PDF con la que se bajaron las notas ajenas. */
    val driveImportVersion: Int = 0,
    /** El aviso del gestor de energía de Onyx ya se mostró (§11). */
    val powerWarningAcknowledged: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastSyncError: String? = null,
    /** Resumen de la última sincronización, conflictos incluidos (§10). */
    val lastSyncSummary: String? = null,
) {
    val purgeEnabled: Boolean get() = retentionDays != NEVER_PURGE

    companion object {
        const val NEVER_PURGE = -1
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val retentionDays = intPreferencesKey("retention_days")
        val weekStart = intPreferencesKey("week_start")
        val syncIntervalMinutes = intPreferencesKey("sync_interval_minutes")
        val showTasksInCalendar = booleanPreferencesKey("show_tasks_in_calendar")
        val defaultCalendarId = longPreferencesKey("default_calendar_id")
        val defaultTaskListId = longPreferencesKey("default_task_list_id")
        val inkStrokeWidth = floatPreferencesKey("ink_stroke_width")
        val inkTool = stringPreferencesKey("ink_tool")
        val inkColorArgb = intPreferencesKey("ink_color_argb")
        val inkMarkerColorArgb = intPreferencesKey("ink_marker_color_argb")
        val inkPencilWidthMm = floatPreferencesKey("ink_pencil_width_mm")
        val inkBallpointWidthMm = floatPreferencesKey("ink_ballpoint_width_mm")
        val inkMarkerWidthMm = floatPreferencesKey("ink_marker_width_mm")
        val inkPencilTexture = intPreferencesKey("ink_pencil_texture")
        val inkBallpointPressure = intPreferencesKey("ink_ballpoint_pressure")
        val inkPencilPressure = intPreferencesKey("ink_pencil_pressure")
        val penMode = stringPreferencesKey("pen_mode")
        val ocrLanguageTag = stringPreferencesKey("ocr_language_tag")
        val inkTextFont = stringPreferencesKey("ink_text_font")
        val inkTextTool = stringPreferencesKey("ink_text_tool")
        val statusStrip = stringPreferencesKey("status_strip")
        val driveNotesFolderId = stringPreferencesKey("drive_notes_folder_id")
        val driveNotesFolderName = stringPreferencesKey("drive_notes_folder_name")
        val driveNotesSyncAt = longPreferencesKey("drive_notes_sync_at")
        val driveNotesError = stringPreferencesKey("drive_notes_error")
        val driveImportVersion = intPreferencesKey("drive_import_version")
        val powerWarningAcknowledged = booleanPreferencesKey("power_warning_ack")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val lastSyncSummary = stringPreferencesKey("last_sync_summary")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            retentionDays = p[Keys.retentionDays] ?: defaults.retentionDays,
            weekStart = p[Keys.weekStart]?.let { DayOfWeek.of(it) } ?: defaults.weekStart,
            syncIntervalMinutes = p[Keys.syncIntervalMinutes] ?: defaults.syncIntervalMinutes,
            showTasksInCalendar = p[Keys.showTasksInCalendar] ?: defaults.showTasksInCalendar,
            defaultCalendarId = p[Keys.defaultCalendarId] ?: defaults.defaultCalendarId,
            defaultTaskListId = p[Keys.defaultTaskListId] ?: defaults.defaultTaskListId,
            inkStrokeWidth = p[Keys.inkStrokeWidth] ?: defaults.inkStrokeWidth,
            inkTool = p[Keys.inkTool] ?: defaults.inkTool,
            inkColorArgb = p[Keys.inkColorArgb] ?: defaults.inkColorArgb,
            inkMarkerColorArgb = p[Keys.inkMarkerColorArgb] ?: defaults.inkMarkerColorArgb,
            inkPencilWidthMm = p[Keys.inkPencilWidthMm] ?: defaults.inkPencilWidthMm,
            inkBallpointWidthMm = p[Keys.inkBallpointWidthMm] ?: defaults.inkBallpointWidthMm,
            inkMarkerWidthMm = p[Keys.inkMarkerWidthMm] ?: defaults.inkMarkerWidthMm,
            inkPencilTexture = p[Keys.inkPencilTexture] ?: defaults.inkPencilTexture,
            inkBallpointPressure = p[Keys.inkBallpointPressure] ?: defaults.inkBallpointPressure,
            inkPencilPressure = p[Keys.inkPencilPressure] ?: defaults.inkPencilPressure,
            penMode = p[Keys.penMode] ?: defaults.penMode,
            ocrLanguageTag = p[Keys.ocrLanguageTag] ?: defaults.ocrLanguageTag,
            inkTextFont = p[Keys.inkTextFont] ?: defaults.inkTextFont,
            inkTextTool = p[Keys.inkTextTool] ?: defaults.inkTextTool,
            statusStrip = p[Keys.statusStrip] ?: defaults.statusStrip,
            driveNotesFolderId = p[Keys.driveNotesFolderId],
            driveNotesFolderName = p[Keys.driveNotesFolderName],
            driveNotesSyncAt = p[Keys.driveNotesSyncAt] ?: defaults.driveNotesSyncAt,
            driveNotesError = p[Keys.driveNotesError],
            driveImportVersion = p[Keys.driveImportVersion] ?: defaults.driveImportVersion,
            powerWarningAcknowledged = p[Keys.powerWarningAcknowledged]
                ?: defaults.powerWarningAcknowledged,
            lastSyncAt = p[Keys.lastSyncAt] ?: defaults.lastSyncAt,
            lastSyncError = p[Keys.lastSyncError],
            lastSyncSummary = p[Keys.lastSyncSummary],
        )
    }

    suspend fun setRetentionDays(days: Int) = edit { it[Keys.retentionDays] = days }
    suspend fun setWeekStart(day: DayOfWeek) = edit { it[Keys.weekStart] = day.value }
    suspend fun setSyncIntervalMinutes(minutes: Int) =
        edit { it[Keys.syncIntervalMinutes] = minutes.coerceAtLeast(15) }

    suspend fun setShowTasksInCalendar(show: Boolean) =
        edit { it[Keys.showTasksInCalendar] = show }

    suspend fun setDefaultCalendarId(id: Long) = edit { it[Keys.defaultCalendarId] = id }
    suspend fun setDefaultTaskListId(id: Long) = edit { it[Keys.defaultTaskListId] = id }
    suspend fun setInkStrokeWidth(width: Float) = edit { it[Keys.inkStrokeWidth] = width }

    /** Todo lo de la pluma de una vez: se recuerda entre sesiones. */
    suspend fun setInkTools(
        tool: String,
        colorArgb: Int,
        markerColorArgb: Int,
        pencilWidthMm: Float,
        ballpointWidthMm: Float,
        markerWidthMm: Float,
        pencilTexture: Int,
        ballpointPressure: Int,
        pencilPressure: Int,
    ) = edit {
        it[Keys.inkTool] = tool
        it[Keys.inkColorArgb] = colorArgb
        it[Keys.inkMarkerColorArgb] = markerColorArgb
        it[Keys.inkPencilWidthMm] = pencilWidthMm
        it[Keys.inkBallpointWidthMm] = ballpointWidthMm
        it[Keys.inkMarkerWidthMm] = markerWidthMm
        it[Keys.inkPencilTexture] = pencilTexture
        it[Keys.inkBallpointPressure] = ballpointPressure
        it[Keys.inkPencilPressure] = pencilPressure
    }
    suspend fun setPenMode(mode: String) = edit { it[Keys.penMode] = mode }
    suspend fun setOcrLanguageTag(tag: String) = edit { it[Keys.ocrLanguageTag] = tag }
    suspend fun setInkTextFont(id: String) = edit { it[Keys.inkTextFont] = id }
    suspend fun setInkTextTool(tool: String) = edit { it[Keys.inkTextTool] = tool }
    suspend fun setStatusStrip(mode: String) = edit { it[Keys.statusStrip] = mode }

    suspend fun setDriveNotesFolder(id: String?, name: String?) = edit {
        if (id == null) {
            it.remove(Keys.driveNotesFolderId)
            it.remove(Keys.driveNotesFolderName)
        } else {
            it[Keys.driveNotesFolderId] = id
            it[Keys.driveNotesFolderName] = name ?: id
        }
        it.remove(Keys.driveNotesError)
    }

    suspend fun recordDriveNotesSuccess(at: Long) = edit {
        it[Keys.driveNotesSyncAt] = at
        it.remove(Keys.driveNotesError)
    }

    suspend fun recordDriveNotesFailure(message: String) = edit { it[Keys.driveNotesError] = message }

    suspend fun setDriveImportVersion(version: Int) = edit { it[Keys.driveImportVersion] = version }
    suspend fun acknowledgePowerWarning() = edit { it[Keys.powerWarningAcknowledged] = true }

    suspend fun recordSyncSuccess(at: Long, summary: String) = edit {
        it[Keys.lastSyncAt] = at
        it[Keys.lastSyncSummary] = summary
        it.remove(Keys.lastSyncError)
    }

    suspend fun recordSyncFailure(message: String) = edit { it[Keys.lastSyncError] = message }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
