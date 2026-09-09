package com.weto.booxcal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weto.booxcal.domain.model.CalendarViewMode
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.ControlCorner
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkCard
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkFab
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkMenuRow
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.monthTitle
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberLocale
import com.weto.booxcal.ui.theme.relativeTime
import com.weto.booxcal.ink.InkDocument

/**
 * Panel principal.
 *
 * Tres tarjetas sobre fondo blanco: el mes, los eventos del día elegido y un
 * módulo con pestañas. Todo lo que se consulta a diario cabe en una pantalla,
 * que en e-ink vale más que cualquier navegación: cada salto de pantalla es un
 * refresco completo.
 */
@Composable
fun HomeScreen(
    onOpenEvent: (Long) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    /**
     * Abrir la ventana de creación. `onSaved` se llama cuando lo creado se
     * guarda de verdad (no al cancelar): la nota rápida que se convirtió
     * entera en evento o recordatorio se borra ahí.
     */
    onCreate: (date: LocalDate, kind: String, title: String, ink: InkDocument?, onSaved: (() -> Unit)?) -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenDayNote: (LocalDate) -> Unit,
    onOpenNote: (LocalDate, Long) -> Unit,
    onOpenCalendar: (CalendarViewMode, LocalDate) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val view = LocalView.current

    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { EinkRefresh.fullRefresh(view) }

    // Al salir de la pantalla o dejar la app, la nota rápida se guarda y, si
    // cambió, sube a Drive en ese momento.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.leaveNote()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.leaveNote()
        }
    }

    Box(modifier.fillMaxSize().background(Eink.White)) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                title = monthTitle(state.anchor, rememberLocale()),
                syncing = state.syncing,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::goToToday,
                onSearch = onOpenSearch,
                onSync = viewModel::syncNow,
                onMenu = { menuOpen = true },
            )

            // Arriba (mes y eventos del día) algo menos de la mitad; abajo (nota
            // rápida, recordatorios, agenda) el resto: es donde se escribe.
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(TOP_FRACTION)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EinkCard(Modifier.weight(1f).fillMaxSize()) {
                    MiniMonthCard(
                        state = state,
                        onSelectDay = viewModel::select,
                        onOpenDay = { onOpenCalendar(CalendarViewMode.DAY, it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                EinkCard(Modifier.weight(1f).fillMaxSize()) {
                    DayItemsCard(
                        events = state.dayEvents,
                        tasks = state.dayTasks,
                        notes = state.dayNotes,
                        date = state.selected,
                        zone = zone,
                        onOpenEvent = onOpenEvent,
                        onOpenTask = onOpenTask,
                        onToggleTask = viewModel::toggleTask,
                        onOpenNote = { note -> onOpenNote(state.selected, note.id) },
                        onNewEvent = { onNewEvent(state.selected) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            EinkCard(
                Modifier
                    .fillMaxWidth()
                    .weight(1f - TOP_FRACTION)
                    .padding(horizontal = 8.dp)
            ) {
                ModuleCard(
                    state = state,
                    zone = zone,
                    onSelectModule = viewModel::setModule,
                    onToggleTask = viewModel::toggleTask,
                    onOpenTask = onOpenTask,
                    onOpenEvent = onOpenEvent,
                    onSaveDayNote = viewModel::saveDayNote,
                    onNoteText = viewModel::setDayNoteText,
                    onNewSheet = viewModel::newSheet,
                    onEditNote = viewModel::editNote,
                    onRenameNote = viewModel::renameNote,
                    onDeleteNote = viewModel::deleteNote,
                    onOpenNote = { note -> onOpenNote(state.selected, note.id) },
                    onCreateFromText = { isEvent, text, wholeNote, ink ->
                        onCreate(
                            state.selected,
                            if (isEvent) "event" else "reminder",
                            text,
                            ink,
                            // Toda la nota ya es el evento o el recordatorio:
                            // en cuanto se guarde, la nota sobra. Una selección
                            // del lazo no: el resto de la nota sigue valiendo.
                            if (wholeNote) ({ viewModel.consumeCurrentNote() }) else null,
                        )
                    },
                    onOpenAll = { module ->
                        when (module) {
                            HomeModule.REMINDERS, HomeModule.DUE -> onOpenTasks()
                            HomeModule.NOTE, HomeModule.TODAY_NOTES ->
                                onOpenDayNote(state.selected)
                        }
                    },
                )
            }

            if (!state.accountConnected) {
                // Sin cuenta nada sube. Se dice grande y con el camino al lado,
                // no en una línea gris al pie que nadie lee.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(ControlCorner)
                        .border(HairlineWidth, Eink.Black, ControlCorner)
                        .einkClickable(onClick = onOpenSettings)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EinkTile(Glyph.SyncCloud, Accent.Sync, size = 34.dp, glyphSize = 20.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            stringResource(R.string.home_no_account_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Eink.Black,
                        )
                        Text(
                            stringResource(R.string.home_no_account_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Eink.Graphite,
                        )
                    }
                    EinkGlyph(Glyph.ChevronRight, size = 18.dp, tint = Eink.Black)
                }
            } else {
                Text(
                    text = syncStatusText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = Eink.Graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        EinkFab(
            onClick = { onCreate(state.selected, "event", "", null, null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 22.dp),
        )
    }

    if (menuOpen) {
        HomeMenuDialog(
            onDismiss = { menuOpen = false },
            onPick = { action ->
                menuOpen = false
                when (action) {
                    MenuAction.MONTH -> onOpenCalendar(CalendarViewMode.MONTH, state.selected)
                    MenuAction.WEEK -> onOpenCalendar(CalendarViewMode.WEEK, state.selected)
                    MenuAction.DAY -> onOpenCalendar(CalendarViewMode.DAY, state.selected)
                    MenuAction.REMINDERS -> onOpenTasks()
                    MenuAction.NOTEBOOK -> onOpenNotes()
                    MenuAction.SEARCH -> onOpenSearch()
                    MenuAction.SETTINGS -> onOpenSettings()
                }
            },
        )
    }

}

private enum class MenuAction { MONTH, WEEK, DAY, REMINDERS, NOTEBOOK, SEARCH, SETTINGS }

/**
 * Menú de las tres rayas.
 *
 * Cada entrada lleva su baldosa de color y una línea que dice qué hace. Ocupa
 * más que una lista de botones, pero es la pantalla desde la que alguien que no
 * ha usado nunca la app tiene que poder llegar a todo sin preguntar.
 */
@Composable
private fun HomeMenuDialog(onDismiss: () -> Unit, onPick: (MenuAction) -> Unit) {
    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.common_menu), modifier = Modifier.width(420.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            EinkMenuRow(
                glyph = Glyph.GridMonth,
                accent = Accent.Event,
                label = stringResource(R.string.home_menu_month),
                subtitle = stringResource(R.string.home_menu_month_hint),
                onClick = { onPick(MenuAction.MONTH) },
            )
            EinkMenuRow(
                glyph = Glyph.GridWeek,
                accent = Accent.Event,
                label = stringResource(R.string.common_week),
                subtitle = stringResource(R.string.home_menu_week_hint),
                onClick = { onPick(MenuAction.WEEK) },
            )
            EinkMenuRow(
                glyph = Glyph.GridDay,
                accent = Accent.Event,
                label = stringResource(R.string.common_day),
                subtitle = stringResource(R.string.home_menu_day_hint),
                onClick = { onPick(MenuAction.DAY) },
            )

            EinkDivider(Modifier.padding(vertical = 6.dp))

            EinkMenuRow(
                glyph = Glyph.ListCheck,
                accent = Accent.Reminder,
                label = stringResource(R.string.common_reminders),
                subtitle = stringResource(R.string.home_menu_reminders_hint),
                onClick = { onPick(MenuAction.REMINDERS) },
            )
            EinkMenuRow(
                glyph = Glyph.Folder,
                accent = Accent.Note,
                label = stringResource(R.string.common_notebook),
                subtitle = stringResource(R.string.home_menu_notebook_hint),
                onClick = { onPick(MenuAction.NOTEBOOK) },
            )
            EinkMenuRow(
                glyph = Glyph.Search,
                accent = Accent.Search,
                label = stringResource(R.string.common_search),
                subtitle = stringResource(R.string.home_menu_search_hint),
                onClick = { onPick(MenuAction.SEARCH) },
            )

            EinkDivider(Modifier.padding(vertical = 6.dp))

            EinkMenuRow(
                glyph = Glyph.Gear,
                accent = Accent.Settings,
                label = stringResource(R.string.home_menu_settings),
                subtitle = stringResource(R.string.home_menu_settings_hint),
                onClick = { onPick(MenuAction.SETTINGS) },
            )

            Spacer(Modifier.height(10.dp))
            EinkButton(stringResource(R.string.common_close), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HomeTopBar(
    title: String,
    syncing: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.common_calendar),
            style = MaterialTheme.typography.titleLarge,
            color = Eink.Black,
        )

        Spacer(Modifier.weight(1f))

        EinkIconButton(Glyph.ChevronLeft, onPrevious, contentDescription = stringResource(R.string.home_previous_month))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Eink.Black,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        EinkIconButton(Glyph.ChevronRight, onNext, contentDescription = stringResource(R.string.home_next_month))

        Spacer(Modifier.weight(1f))

        EinkIconButton(
            glyph = Glyph.Search,
            onClick = onSearch,
            contentDescription = stringResource(R.string.common_search),
            accent = Accent.Search,
        )
        EinkIconButton(
            glyph = Glyph.SyncCloud,
            onClick = onSync,
            contentDescription = stringResource(if (syncing) R.string.common_syncing else R.string.common_sync),
            // Sin animación de progreso: en e-ink un spinner es un refresco
            // continuo. El estado se dice apagando el icono.
            enabled = !syncing,
            accent = Accent.Sync,
        )
        EinkIconButton(
            glyph = Glyph.Today,
            onClick = onToday,
            contentDescription = stringResource(R.string.common_today),
            accent = Accent.Today,
        )
        // El menú se queda en negro: es el único botón que no hace una cosa
        // concreta, y darle color lo pondría al mismo nivel que los demás.
        EinkIconButton(Glyph.Menu, onMenu, contentDescription = stringResource(R.string.common_menu))

        Spacer(Modifier.width(2.dp))
    }
}

/** One line that says whether sync is working: tapping the cloud has no other visible effect. */
@Composable
private fun syncStatusText(state: HomeUiState): String = when {
    !state.accountConnected -> stringResource(R.string.sync_status_no_account)
    state.syncing -> stringResource(R.string.sync_status_syncing)
    state.lastSyncError != null -> stringResource(R.string.common_not_synced, state.lastSyncError)
    state.lastSyncAt > 0 -> stringResource(R.string.sync_status_synced, relativeTime(state.lastSyncAt))
    else -> stringResource(R.string.sync_status_never)
}

/** Part of the height (bar excluded) for the month and the day's items; the rest for the modules. */
private const val TOP_FRACTION = 0.43f
