package com.weto.booxcal.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.TaskListEntity
import com.weto.booxcal.data.remote.google.GoogleAuthManager
import com.weto.booxcal.data.remote.google.GoogleBackend
import com.weto.booxcal.data.remote.google.GoogleDriveClient
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkRecognizer
import com.weto.booxcal.ink.ModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import com.weto.booxcal.R
import androidx.annotation.StringRes
import com.weto.booxcal.util.AppLocale

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val oauthConfigured: Boolean = false,
    val accountEmail: String? = null,
    val authorized: Boolean = false,
    /** La sesión de Google tiene el permiso de Drive (las antiguas no). */
    val hasDriveScope: Boolean = false,
    val notesSyncing: Boolean = false,
    val calendars: List<CalendarEntity> = emptyList(),
    val taskLists: List<TaskListEntity> = emptyList(),
    val syncing: Boolean = false,
    val ocrModel: ModelState = ModelState.Unknown,
    val message: String? = null,
    /** The pinned app language ([AppLocale.SYSTEM] to follow the device). */
    val language: String = AppLocale.SYSTEM,
)

val RETENTION_CHOICES = listOf(7, 30, 90, 365, AppSettings.NEVER_PURGE)

class SettingsViewModel(
    private val settingsStore: SettingsStore = Graph.settings,
    private val taskRepository: TaskRepository = Graph.taskRepository,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
    private val auth: GoogleAuthManager = Graph.googleAuth,
    private val database: AppDatabase = Graph.database,
    private val recognizer: InkRecognizer = Graph.inkRecognizer,
    private val drive: GoogleDriveClient = Graph.driveClient,
) : ViewModel() {

    private val local = MutableStateFlow(
        SettingsUiState(
            oauthConfigured = auth.isConfigured,
            authorized = auth.isAuthorized,
            accountEmail = auth.accountEmail,
            hasDriveScope = auth.hasDriveScope,
            language = AppLocale.current(Graph.appContext),
        )
    )

    private val notesSyncing = syncScheduler.observeNotesRunning()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        database.calendarDao().observeAll(),
        database.taskListDao().observeAll(),
        combine(syncScheduler.observeRunning(), notesSyncing) { a, b -> a to b },
        local,
    ) { settings, calendars, lists, (syncing, notes), localState ->
        localState.copy(
            settings = settings,
            calendars = calendars,
            taskLists = lists,
            syncing = syncing,
            notesSyncing = notes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    // --- Cuenta -------------------------------------------------------------

    /** El intent, o el motivo por el que no se pudo preparar. Nunca un null mudo. */
    fun authorizationIntent(): Result<Intent> = runCatching { auth.authorizationIntent() }

    fun onAuthorizationResult(data: Intent?) {
        viewModelScope.launch {
            val result = auth.handleAuthorizationResult(data)
            local.value = local.value.copy(
                authorized = auth.isAuthorized,
                accountEmail = auth.accountEmail,
                hasDriveScope = auth.hasDriveScope,
                message = result.exceptionOrNull()?.message
                    ?: text(R.string.settings_msg_connected),
            )
            if (result.isSuccess) syncScheduler.syncNow()
        }
    }

    fun refreshAccount() {
        local.value = local.value.copy(
            authorized = auth.isAuthorized,
            accountEmail = auth.accountEmail,
            hasDriveScope = auth.hasDriveScope,
        )
    }

    // --- Notas en Google Drive -----------------------------------------------

    /**
     * La carpeta de la app en Drive («Calendario Boox», en «Mi unidad»),
     * creada si no existe. Con `drive.file` la app solo ve lo que ella crea,
     * así que la carpeta es suya o no es: no hay nada que elegir.
     */
    fun enableDriveFolder() {
        viewModelScope.launch {
            val result = runCatching { drive.findOrCreateFolder(GoogleDriveClient.ROOT, APP_FOLDER) }
            result.onSuccess { folder ->
                settingsStore.setDriveNotesFolder(folder.id, folder.name)
                local.value = local.value.copy(message = text(R.string.settings_msg_drive_folder, folder.name))
                syncScheduler.syncNotesNow()
            }.onFailure { e ->
                local.value = local.value.copy(message = text(R.string.settings_msg_drive_folder_failed, e.message.orEmpty()))
            }
        }
    }

    fun clearDriveFolder() {
        viewModelScope.launch {
            settingsStore.setDriveNotesFolder(null, null)
            local.value = local.value.copy(message = text(R.string.settings_msg_drive_off))
        }
    }

    fun syncNotesNow() = syncScheduler.syncNotesNow()

    fun showMessage(text: String) {
        local.value = local.value.copy(message = text)
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            local.value = local.value.copy(hasDriveScope = false)
            syncScheduler.cancelAll()
            // Se borran las colecciones del backend y, en cascada, sus eventos
            // y tareas. Las notas manuscritas no: son solo locales.
            database.calendarDao().deleteAllForBackend(GoogleBackend.BACKEND_ID)
            database.taskListDao().deleteAllForBackend(GoogleBackend.BACKEND_ID)
            database.syncMapDao().deleteAllForBackend(GoogleBackend.BACKEND_ID)
            local.value = local.value.copy(
                authorized = false,
                accountEmail = null,
                message = text(R.string.settings_msg_disconnected),
            )
        }
    }

    // --- Preferencias -------------------------------------------------------

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsStore.setRetentionDays(days)
            taskRepository.recomputeRetention(settingsStore.settings.first())
        }
    }

    fun setWeekStart(day: DayOfWeek) {
        viewModelScope.launch { settingsStore.setWeekStart(day) }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            settingsStore.setSyncIntervalMinutes(minutes)
            syncScheduler.schedulePeriodic(minutes)
        }
    }

    fun setShowTasksInCalendar(show: Boolean) {
        viewModelScope.launch { settingsStore.setShowTasksInCalendar(show) }
    }

    /** Calendario con el que se abre el formulario de evento nuevo. 0: el principal de la cuenta. */
    fun setDefaultCalendar(id: Long) {
        viewModelScope.launch { settingsStore.setDefaultCalendarId(id) }
    }

    /** Lista con la que se abre el formulario de recordatorio nuevo. 0: la primera de la cuenta. */
    fun setDefaultTaskList(id: Long) {
        viewModelScope.launch { settingsStore.setDefaultTaskListId(id) }
    }

    fun setInkTextFont(id: String) {
        viewModelScope.launch { settingsStore.setInkTextFont(id) }
    }

    fun setInkTextTool(tool: String) {
        viewModelScope.launch { settingsStore.setInkTextTool(tool) }
    }

    fun setStatusStrip(mode: String) {
        viewModelScope.launch { settingsStore.setStatusStrip(mode) }
    }

    /** Pins the app language; the screen recreates itself afterwards. */
    fun setLanguage(tag: String) {
        AppLocale.set(Graph.appContext, tag)
        local.value = local.value.copy(language = tag)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = Graph.appContext.getString(id, *args)

    fun acknowledgePowerWarning() {
        viewModelScope.launch { settingsStore.acknowledgePowerWarning() }
    }

    fun downloadOcrModel() {
        viewModelScope.launch {
            local.value = local.value.copy(ocrModel = ModelState.Downloading)
            val tag = settingsStore.settings.first().ocrLanguageTag
            local.value = local.value.copy(ocrModel = recognizer.prepare(tag))
        }
    }

    fun syncNow() = syncScheduler.syncNow()

    fun clearMessage() {
        local.value = local.value.copy(message = null)
    }

    private companion object {
        const val APP_FOLDER = "Calendario Boox"
    }
}
