package com.weto.booxcal.di

import android.content.Context
import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.LocalCollections
import com.weto.booxcal.data.remote.SyncBackend
import com.weto.booxcal.data.remote.google.GoogleAuthManager
import com.weto.booxcal.data.remote.google.GoogleBackend
import com.weto.booxcal.data.remote.google.GoogleDriveClient
import com.weto.booxcal.data.remote.google.GoogleNetwork
import com.weto.booxcal.data.repository.EventRepository
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.repository.NoteFolderRepository
import com.weto.booxcal.data.repository.TaskRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.NoteDriveSync
import com.weto.booxcal.data.sync.SyncEngine
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.ink.InkRecognizer
import com.weto.booxcal.ink.NoteStorage
import com.weto.booxcal.ink.NoteIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Localizador de servicios.
 *
 * Deliberadamente no hay Hilt: la app tiene un puñado de dependencias, todas de
 * ámbito de aplicación, y meter generación de código a cambio de eso solo
 * añadiría una fuente más de fallos de compilación en un proyecto que ya
 * depende de un SDK propietario con versiones frágiles.
 *
 * Los ViewModels toman sus dependencias como argumentos por defecto desde aquí,
 * lo que les deja un constructor sin parámetros para `viewModel()` y a la vez
 * permite inyectar dobles en los tests.
 */
object Graph {

    lateinit var applicationScope: CoroutineScope
        private set

    /** The application context: for strings built off the main thread (sync results, widgets). */
    lateinit var appContext: Context
        private set

    /**
     * Ruta que la app tiene que abrir en cuanto pueda: la pone un widget al
     * tocarlo (llega en el Intent de la actividad) y la consume el NavHost.
     */
    val pendingRoute = MutableStateFlow<String?>(null)

    lateinit var database: AppDatabase
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var googleAuth: GoogleAuthManager
        private set

    lateinit var eventRepository: EventRepository
        private set

    lateinit var taskRepository: TaskRepository
        private set

    lateinit var inkNoteRepository: InkNoteRepository
        private set

    lateinit var noteFolderRepository: NoteFolderRepository
        private set

    /** Las notas y su copia en Drive como PDF vectorial. */
    lateinit var noteDriveSync: NoteDriveSync
        private set

    /** Para elegir la carpeta de Drive desde Ajustes. */
    lateinit var driveClient: GoogleDriveClient
        private set

    lateinit var syncEngine: SyncEngine
        private set

    lateinit var syncScheduler: SyncScheduler
        private set

    lateinit var backends: List<SyncBackend>
        private set

    /** Único y compartido: cargar el modelo de OCR dos veces cuesta memoria. */
    val inkRecognizer: InkRecognizer by lazy { InkRecognizer() }

    /** Transcribe las notas en segundo plano para que la búsqueda las encuentre. */
    lateinit var noteIndexer: NoteIndexer
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            this.appContext = appContext

            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Antes de la base: la migración a v5 saca a fichero los cuadernos grandes.
            NoteStorage.dir = File(appContext.filesDir, "notes")
            database = AppDatabase.build(appContext)
            settings = SettingsStore(appContext)

            googleAuth = GoogleAuthManager(appContext)
            val client = GoogleNetwork.okHttpClient(googleAuth)
            backends = listOf(
                GoogleBackend(
                    auth = googleAuth,
                    calendarApi = GoogleNetwork.calendarApi(client),
                    tasksApi = GoogleNetwork.tasksApi(client),
                )
                // v2: CaldavBackend. v3: MicrosoftBackend. La lista es lo único
                // que hay que tocar para añadirlos.
            )

            eventRepository = EventRepository(database)
            taskRepository = TaskRepository(database)
            noteFolderRepository = NoteFolderRepository(database)
            inkNoteRepository = InkNoteRepository(database, noteFolderRepository)
            noteIndexer = NoteIndexer(
                repository = inkNoteRepository,
                recognizer = inkRecognizer,
                settings = settings,
                scope = applicationScope,
            )
            // Las notas de antes de que existiera la transcripción de fondo.
            noteIndexer.indexMissing()

            syncEngine = SyncEngine(
                db = database,
                backends = backends,
                settings = settings,
                taskRepository = taskRepository,
            )
            syncScheduler = SyncScheduler(appContext)
            driveClient = GoogleNetwork.driveClient(client)
            noteDriveSync = NoteDriveSync(
                drive = driveClient,
                auth = googleAuth,
                notes = inkNoteRepository,
                folders = noteFolderRepository,
                db = database,
                settings = settings,
            )
            inkNoteRepository.onSaved = { id ->
                noteIndexer.schedule(id)
                syncScheduler.syncNotesSoon()
                com.weto.booxcal.widget.AgendaWidgets.refresh(appContext)
            }
            // Las notas del día de antes del gestor: a su carpeta y con su etiqueta.
            applicationScope.launch { runCatching { inkNoteRepository.backfillDayNotes() } }

            // El calendario y la lista locales tienen que existir antes de que
            // el usuario abra el formulario de creación, y abrirlo requiere ver
            // primero la pantalla principal: da tiempo de sobra a esta corrutina.
            applicationScope.launch { LocalCollections.ensure(database) }

            initialized = true
        }
    }
}
