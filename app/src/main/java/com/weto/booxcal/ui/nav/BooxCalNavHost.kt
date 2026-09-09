package com.weto.booxcal.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.di.Graph
import com.weto.booxcal.domain.model.CalendarViewMode
import com.weto.booxcal.ui.calendar.CalendarScreen
import com.weto.booxcal.ui.ink.BlockInkWhileShown
import com.weto.booxcal.ui.create.QuickCreateScreen
import com.weto.booxcal.ui.editor.EventEditorScreen
import com.weto.booxcal.ui.editor.TaskEditorScreen
import com.weto.booxcal.ui.home.HomeScreen
import com.weto.booxcal.ui.ink.DayNoteScreen
import com.weto.booxcal.ui.notes.NotesManagerScreen
import com.weto.booxcal.ui.search.SearchScreen
import com.weto.booxcal.ui.settings.SettingsScreen
import com.weto.booxcal.ui.tasks.TasksScreen
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.util.MILLIS_PER_DAY
import java.time.LocalDate
import com.weto.booxcal.ink.TemplateRef
import com.weto.booxcal.ink.InkDocument

/** Lo que pidió el «+»: el día y, si viene del lazo, qué crear y con qué título. */
private data class CreateRequest(
    val date: LocalDate,
    val kind: String,
    val title: String,
    /** Qué hacer cuando lo creado se guarda (no al cancelar). */
    val onSaved: (() -> Unit)? = null,
    /** Handwritten note to attach from the start (a drawing from the quick note). */
    val ink: InkDocument? = null,
)

object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val CREATE = "create?day={day}&kind={kind}&title={title}"
    const val CALENDAR = "calendar?mode={mode}&day={day}"
    const val EVENT = "event?eventId={eventId}&day={day}"
    const val TASK = "task?taskId={taskId}&day={day}"
    const val NOTES = "notes?noteId={noteId}&page={page}"
    const val DAY_NOTE = "dayNote/{dayMillis}?noteId={noteId}&folderId={folderId}&anchor={anchor}&page={page}&blank={blank}&tpl={tpl}&tplPage={tplPage}"

    private const val NONE = -1L

    fun calendar(mode: CalendarViewMode, date: LocalDate): String =
        "calendar?mode=${mode.name}&day=${date.toEpochDay()}"

    /** `kind`: "event" o "reminder". El título llega del lazo con OCR. */
    fun create(date: LocalDate, kind: String = "event", title: String = ""): String =
        "create?day=${date.toEpochDay()}&kind=$kind&title=${Uri.encode(title)}"

    fun event(eventId: Long? = null, date: LocalDate? = null): String =
        "event?eventId=${eventId ?: NONE}&day=${date?.toEpochDay() ?: NONE}"

    fun task(taskId: Long? = null, dueDate: LocalDate? = null): String =
        "task?taskId=${taskId ?: NONE}&day=${dueDate?.toEpochDay() ?: NONE}"

    /** El cuaderno; con `noteId`, abre en la carpeta de esa nota y la enseña por la página [page]. */
    fun notes(noteId: Long? = null, page: Int = 0): String =
        "notes?noteId=${noteId ?: NONE}&page=$page"

    /** Sin `noteId` abre la nota más reciente del día (o una en blanco). */
    fun dayNote(date: LocalDate, noteId: Long? = null, page: Int = 0): String =
        "dayNote/${date.toEpochDay() * MILLIS_PER_DAY}?noteId=${noteId ?: NONE}&folderId=$NONE&anchor=1&page=$page&blank=0"

    /** Una nota del día nueva, en blanco, aunque el día ya tenga otras. */
    fun dayNoteBlank(date: LocalDate): String =
        "dayNote/${date.toEpochDay() * MILLIS_PER_DAY}?noteId=$NONE&folderId=$NONE&anchor=1&page=0&blank=1"

    /** A new notebook note in a folder, anchored to no day, optionally started from a page template. */
    fun folderNote(folderId: Long?, template: TemplateRef? = null): String =
        "dayNote/${LocalDate.now().toEpochDay() * MILLIS_PER_DAY}?noteId=$NONE&folderId=${folderId ?: NONE}&anchor=0&page=0&blank=0" +
            (template?.let { "&tpl=${Uri.encode(it.path)}&tplPage=${it.page}" } ?: "")

    /** La fecha con la que se abre una nota: su día anclado o, si no tiene, el de su creación. */
    fun noteDate(note: InkNoteEntity): LocalDate =
        LocalDate.ofEpochDay(Math.floorDiv(note.anchorDayMillis ?: note.createdAt, MILLIS_PER_DAY))

    internal fun optionalId(value: Long): Long? = value.takeIf { it != NONE }

    internal fun optionalDate(epochDay: Long): LocalDate? =
        epochDay.takeIf { it != NONE }?.let(LocalDate::ofEpochDay)

    internal fun mode(name: String?): CalendarViewMode =
        CalendarViewMode.entries.firstOrNull { it.name == name } ?: CalendarViewMode.MONTH
}

@Composable
fun BooxCalNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val weekStart = settings.weekStart

    // La creación rápida no es una pantalla: es una ventana encima de la que
    // haya, con la app visible alrededor. Va en la misma ventana de Android y
    // no en un Dialog, que abre otra ventana y ahí se pierden la densidad, la
    // barra de estado y el lápiz.
    var createRequest by remember { mutableStateOf<CreateRequest?>(null) }

    // La ruta que pidió un widget: se navega a ella en cuanto llega y se olvida.
    val pendingRoute by Graph.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        Graph.pendingRoute.value = null
        runCatching { navController.navigate(route) }
    }

    Box(modifier) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            // Sin transiciones: en e-ink cada fotograma intermedio es un refresco
            // parcial, y el resultado es peor que un salto seco.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenEvent = { id -> navController.navigate(Routes.event(eventId = id)) },
                    onNewEvent = { date -> navController.navigate(Routes.event(date = date)) },
                    onOpenTask = { id -> navController.navigate(Routes.task(taskId = id)) },
                    onOpenTasks = { navController.navigate(Routes.TASKS) },
                    onOpenDayNote = { date -> navController.navigate(Routes.dayNote(date)) },
                    onOpenNote = { date, id -> navController.navigate(Routes.dayNote(date, id)) },
                    onOpenCalendar = { mode, date ->
                        navController.navigate(Routes.calendar(mode, date))
                    },
                    onCreate = { day, kind, title, ink, onSaved -> createRequest = CreateRequest(day, kind, title, onSaved, ink) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenNotes = { navController.navigate(Routes.notes()) },
                )
            }

            composable(
                route = Routes.NOTES,
                arguments = listOf(
                    navArgument("noteId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("page") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                NotesManagerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { note, page ->
                        navController.navigate(Routes.dayNote(Routes.noteDate(note), note.id, page))
                    },
                    onNewNote = { folderId, template -> navController.navigate(Routes.folderNote(folderId, template)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    revealNoteId = Routes.optionalId(entry.arguments?.getLong("noteId") ?: -1L),
                    revealPage = entry.arguments?.getInt("page") ?: 0,
                )
            }

            composable(
                route = Routes.CALENDAR,
                arguments = listOf(
                    navArgument("mode") {
                        type = NavType.StringType
                        defaultValue = CalendarViewMode.MONTH.name
                    },
                    navArgument("day") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                val date = Routes.optionalDate(entry.arguments?.getLong("day") ?: -1L)
                CalendarScreen(
                    initialMode = Routes.mode(entry.arguments?.getString("mode")),
                    initialDate = date ?: LocalDate.now(),
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { id -> navController.navigate(Routes.event(eventId = id)) },
                    onNewEvent = { day -> navController.navigate(Routes.event(date = day)) },
                    onOpenTask = { id -> navController.navigate(Routes.task(taskId = id)) },
                    onOpenTasks = { navController.navigate(Routes.TASKS) },
                    onOpenDayNote = { day -> navController.navigate(Routes.dayNote(day)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.TASKS) {
                TasksScreen(
                    onOpenTask = { id -> navController.navigate(Routes.task(taskId = id)) },
                    onNewTask = { navController.navigate(Routes.task()) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenEvent = { id -> navController.navigate(Routes.event(eventId = id)) },
                    onOpenTask = { id -> navController.navigate(Routes.task(taskId = id)) },
                    // Una nota del cuaderno se enseña desde el cuaderno: con su
                    // carpeta desplegada y abierta por la página del hallazgo.
                    // Una nota pegada a un evento o tarea no tiene carpeta:
                    // se abre directamente.
                    onOpenNote = { note, page ->
                        if (note.belongsToManager) navController.navigate(Routes.notes(note.id, page))
                        else navController.navigate(Routes.dayNote(Routes.noteDate(note), note.id, page))
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.CREATE,
                arguments = listOf(
                    navArgument("day") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("kind") { type = NavType.StringType; defaultValue = "event" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val day = Routes.optionalDate(entry.arguments?.getLong("day") ?: -1L)
                QuickCreateScreen(
                    date = day ?: LocalDate.now(),
                    weekStart = weekStart,
                    initialReminder = entry.arguments?.getString("kind") == "reminder",
                    initialTitle = entry.arguments?.getString("title").orEmpty(),
                    onClose = { navController.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.EVENT,
                arguments = listOf(
                    navArgument("eventId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("day") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                val eventId = Routes.optionalId(entry.arguments?.getLong("eventId") ?: -1L)
                val date = Routes.optionalDate(entry.arguments?.getLong("day") ?: -1L)
                EventEditorScreen(
                    eventId = eventId,
                    initialDate = date ?: LocalDate.now(),
                    weekStart = weekStart,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.TASK,
                arguments = listOf(
                    navArgument("taskId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("day") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                val taskId = Routes.optionalId(entry.arguments?.getLong("taskId") ?: -1L)
                val date = Routes.optionalDate(entry.arguments?.getLong("day") ?: -1L)
                TaskEditorScreen(
                    taskId = taskId,
                    initialDueDate = date,
                    weekStart = weekStart,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DAY_NOTE,
                arguments = listOf(
                    navArgument("dayMillis") { type = NavType.LongType },
                    navArgument("noteId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("folderId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("anchor") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("page") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("blank") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("tpl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("tplPage") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                val templatePath = entry.arguments?.getString("tpl").orEmpty()
                DayNoteScreen(
                    blank = (entry.arguments?.getInt("blank") ?: 0) == 1,
                    dayMillis = entry.arguments?.getLong("dayMillis") ?: 0L,
                    noteId = Routes.optionalId(entry.arguments?.getLong("noteId") ?: -1L),
                    folderId = Routes.optionalId(entry.arguments?.getLong("folderId") ?: -1L),
                    anchorToDay = (entry.arguments?.getInt("anchor") ?: 1) == 1,
                    initialPage = entry.arguments?.getInt("page") ?: 0,
                    template = templatePath.takeIf { it.isNotEmpty() }?.let { TemplateRef(it, entry.arguments?.getInt("tplPage") ?: 0) },
                    onClose = { navController.popBackStack() },
                    // La ventana de creación flota sobre el cuaderno, como en
                    // la portada: el texto reconocido va de título.
                    onCreate = { day, kind, title, ink -> createRequest = CreateRequest(day, kind, title, ink = ink) },
                )
            }
        }

        createRequest?.let { request ->
            CreateWindow(
                request = request,
                weekStart = weekStart,
                onClose = { createRequest = null },
            )
        }
    }
}

/**
 * Ventana flotante de creación: el 70 % de la pantalla, centrada, con la app
 * visible por los lados. Tocar fuera la cierra, y «atrás» también.
 *
 * Sin velo gris encima de la app: en e-ink un gris semitransparente es una
 * trama sucia, y el marco grueso con su sombra ya dice que es una ventana.
 */
@Composable
private fun CreateWindow(
    request: CreateRequest,
    weekStart: java.time.DayOfWeek,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // El lápiz de la nota rápida de debajo espera mientras la ventana esté
    // abierta; si no, sus toques sobre los botones acababan pintados.
    BlockInkWhileShown()

    Box(
        Modifier
            .fillMaxSize()
            // Se traga todos los toques de fuera: uno solo cierra, y ninguno
            // llega a la pantalla de debajo.
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        // Sin sombra: en e-ink el rectángulo desplazado se veía como una
        // mancha. El marco grueso ya dice que es una ventana.
        Box(
            Modifier
                .fillMaxWidth(WINDOW_FRACTION)
                .fillMaxHeight(WINDOW_FRACTION)
                .clip(ControlCorner)
                .background(Eink.White)
                .border(2.dp, Eink.Black, ControlCorner)
                // Los toques dentro se quedan dentro: sin esto el fondo los
                // tomaría como "tocar fuera".
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            QuickCreateScreen(
                date = request.date,
                weekStart = weekStart,
                initialReminder = request.kind == "reminder",
                initialTitle = request.title,
                onClose = onClose,
                onSaved = request.onSaved,
                initialInk = request.ink,
            )
        }
    }
}

private const val WINDOW_FRACTION = 0.7f
