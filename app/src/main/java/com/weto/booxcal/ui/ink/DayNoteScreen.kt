package com.weto.booxcal.ui.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.repository.InkNoteRepository
import com.weto.booxcal.data.settings.SettingsStore
import com.weto.booxcal.data.sync.SyncScheduler
import com.weto.booxcal.di.Graph
import com.weto.booxcal.ink.InkNotebook
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.util.format
import com.weto.booxcal.ink.NoteTemplates
import com.weto.booxcal.ink.TemplateRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DayNoteState(
    val noteId: Long? = null,
    val notebook: InkNotebook = InkNotebook.EMPTY,
    val recognizedText: String? = null,
    val ocrLanguageTag: String = "es",
    val loading: Boolean = true,
    val closed: Boolean = false,
    /** Día al que se ancla la nota; null en las notas del gestor sin día. */
    val anchorDayMillis: Long? = null,
    val title: String? = null,
    /** Nota importada de otra app: se lee, no se escribe. */
    val readOnly: Boolean = false,
)

/**
 * Nota manuscrita anclada a un día: el equivalente al Calendar Memo de la app
 * nativa (§6). No se sincroniza con ningún backend, porque ninguno la
 * entendería; vive en la base local y se marca en la cuadrícula del mes.
 *
 * No hay botón de guardar. Un cuaderno que te pide confirmación cada vez que
 * apuntas algo no es un cuaderno; se escribe y ya está.
 */
class DayNoteViewModel(
    private val dayMillis: Long,
    /** Nota concreta a abrir; sin ella, la más reciente del día. */
    private val requestedNoteId: Long? = null,
    /** Carpeta del gestor para una nota nueva. */
    private val folderId: Long? = null,
    /** Una nota nueva se ancla al día (nota del día) o no (nota del gestor). */
    private val anchorToDay: Boolean = true,
    /** Empezar una nota en blanco aunque el día ya tenga otras. */
    private val blank: Boolean = false,
    /** Page template a new note starts from; null for a blank sheet. */
    private val template: TemplateRef? = null,
    private val inkNoteRepository: InkNoteRepository = Graph.inkNoteRepository,
    private val settingsStore: SettingsStore = Graph.settings,
    private val syncScheduler: SyncScheduler = Graph.syncScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(DayNoteState())

    /** Algo cambió desde que se abrió (o desde la última subida a Drive). */
    @Volatile
    private var modified = false
    val state: StateFlow<DayNoteState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val existing = requestedNoteId?.let { inkNoteRepository.loadNotebook(it)?.first }
                ?: if (anchorToDay && !blank) inkNoteRepository.observeForDay(dayMillis).first().maxByOrNull { it.updatedAt } else null
            val loaded = existing?.let { inkNoteRepository.loadNotebook(it.id) }
            // A new note from a template: its first page is the rendered
            // template, and the notebook remembers it for the pages to come.
            val fresh = if (existing == null && template != null) {
                withContext(Dispatchers.IO) { NoteTemplates.pageDocument(template) }
                    ?.let { page -> InkNotebook(pages = listOf(page), template = page.background) }
            } else {
                null
            }
            _state.value = DayNoteState(
                noteId = existing?.id,
                notebook = loaded?.second ?: fresh ?: InkNotebook.EMPTY,
                recognizedText = existing?.recognizedText,
                ocrLanguageTag = settings.ocrLanguageTag,
                loading = false,
                // Una nota que ya existe conserva su anclaje (o su falta de él).
                anchorDayMillis = if (existing != null) existing.anchorDayMillis else if (anchorToDay) dayMillis else null,
                title = existing?.title,
                readOnly = existing?.isImported == true,
            )
        }
    }

    private var pendingSave: Job? = null

    /**
     * Guarda con un respiro.
     *
     * El lienzo avisa en cada trazo, y escribir en Room una vez por letra es
     * mucho ruido de disco para nada: si el siguiente trazo llega antes de medio
     * segundo, se guarda una sola vez.
     */
    fun save(notebook: InkNotebook, recognizedText: String? = null) {
        // De una nota de solo lectura se guarda el texto reconocido y nada más.
        val current = _state.value
        _state.value = current.copy(
            notebook = if (current.readOnly) current.notebook else notebook,
            recognizedText = recognizedText ?: current.recognizedText,
        )
        if (current.readOnly && recognizedText == null) return
        modified = true
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MILLIS)
            flush()
        }
    }

    fun setRecognizedText(text: String) = save(_state.value.notebook, text)

    /**
     * Fuerza el guardado pendiente: al salir de la pantalla no hay más avisos.
     *
     * La escritura va en el ámbito de la aplicación y no en el del ViewModel
     * porque el caso que más importa es justo el de salir, y para entonces el
     * ámbito del ViewModel ya está cancelado.
     *
     * Con [sync], si la nota cambió desde que se abrió se sube a Drive en
     * cuanto está guardada: es lo que pasa al cerrarla o al dejar la app,
     * para no esperar al respiro de medio minuto del guardado normal.
     */
    fun flush(sync: Boolean = false) {
        pendingSave?.cancel()
        val current = _state.value
        if (current.loading) return
        val upload = sync && modified
        if (upload) modified = false
        Graph.applicationScope.launch {
            if (current.notebook.isEmpty) {
                current.noteId?.let { inkNoteRepository.delete(it) }
                _state.value = _state.value.copy(noteId = null)
            } else {
                val id = inkNoteRepository.saveNotebook(
                    id = current.noteId,
                    notebook = current.notebook,
                    recognizedText = current.recognizedText,
                    anchorDayMillis = current.anchorDayMillis,
                )
                // Nota nueva del gestor: a la carpeta desde la que se creó.
                if (current.noteId == null && folderId != null) inkNoteRepository.setFolder(id, folderId)
                _state.value = _state.value.copy(noteId = id)
            }
            if (upload) syncScheduler.syncNotesNow()
        }
    }

    override fun onCleared() {
        flush(sync = true)
        super.onCleared()
    }

    private companion object {
        const val SAVE_DEBOUNCE_MILLIS = 500L
    }

    fun discard() {
        viewModelScope.launch {
            _state.value.noteId?.let { inkNoteRepository.delete(it) }
            _state.value = _state.value.copy(closed = true)
        }
    }
}

@Composable
fun DayNoteScreen(
    dayMillis: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    noteId: Long? = null,
    /** Crear un evento ("event") o un recordatorio ("reminder") con el texto reconocido. */
    onCreate: ((LocalDate, String, String) -> Unit)? = null,
    /** Carpeta del gestor para una nota nueva; con `anchorToDay` en false no es nota del día. */
    folderId: Long? = null,
    anchorToDay: Boolean = true,
    /** Página (desde 0) por la que se abre: la del hallazgo, viniendo de la búsqueda. */
    initialPage: Int = 0,
    /** Una hoja nueva del día, aunque haya otras (el «+» del widget). */
    blank: Boolean = false,
    /** Page template for a new note; null for a blank sheet. */
    template: TemplateRef? = null,
) {
    val viewModel: DayNoteViewModel = viewModel(
        key = "daynote-$dayMillis-${noteId ?: 0}-${folderId ?: 0}-$anchorToDay-$blank-${template?.path}-${template?.page}",
        factory = viewModelFactory { initializer { DayNoteViewModel(dayMillis, noteId, folderId, anchorToDay, blank, template) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var acceptedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    // Al cerrar la nota o dejar la app con ella abierta, lo escrito se guarda
    // y, si cambió, sube a Drive en ese momento.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.flush(sync = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flush(sync = true)
        }
    }

    val date = LocalDate.ofEpochDay(Math.floorDiv(dayMillis, MILLIS_PER_DAY))
    val dayLabel = rememberDateFormat(R.string.pattern_date_full)
    val locale = rememberLocale()

    Column(modifier.fillMaxSize().background(Eink.White).padding(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onClose, contentDescription = stringResource(R.string.common_back))
            Text(
                // Una nota sin día se presenta por su título; una del día, por la fecha.
                text = if (state.loading || state.anchorDayMillis != null) {
                    date.format(dayLabel, capitalize = true, locale = locale)
                } else {
                    state.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.daynote_untitled)
                },
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (state.noteId != null && !state.readOnly) {
                EinkIconButton(
                    glyph = Glyph.Trash,
                    onClick = viewModel::discard,
                    contentDescription = stringResource(R.string.daynote_delete_whole),
                    accent = Accent.Today,
                )
            }
        }
        EinkDivider(color = Eink.Black)

        if (state.loading) {
            EinkHint(stringResource(R.string.common_loading), Modifier.padding(16.dp))
            return@Column
        }

        // La transcripción acompaña al trazo, no lo sustituye (§8).
        (acceptedText ?: state.recognizedText)?.let { text ->
            // Una línea, no la nota entera: el texto completo se busca desde el buscador.
            val short = text.replace(InkNoteEntity.PAGE_BREAK, ' ').replace('\n', ' ').trim().let { if (it.length > 140) it.take(140) + "…" else it }
            EinkHint(stringResource(R.string.daynote_transcription, short), Modifier.padding(vertical = 4.dp))
        }

        InkBoard(
            notebook = state.notebook,
            languageTag = state.ocrLanguageTag,
            // La clave es el día (y la nota pedida), no el id que acaba
            // teniendo: el id aparece al guardar por primera vez, y si la clave
            // cambiara ahí el cuaderno se recargaría en mitad de la primera frase.
            key = "$dayMillis-${noteId ?: 0}",
            initialPage = initialPage,
            modifier = Modifier.weight(1f).padding(top = 6.dp),
            onNotebookChanged = viewModel::save,
            onUseText = { text ->
                acceptedText = text
                viewModel.setRecognizedText(text)
            },
            onCreateEntry = onCreate?.let { create ->
                { isEvent, text -> create(date, if (isEvent) "event" else "reminder", text) }
            },
            readOnly = state.readOnly,
        )
    }
}
