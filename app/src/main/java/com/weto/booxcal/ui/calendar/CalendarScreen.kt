package com.weto.booxcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weto.booxcal.domain.model.CalendarViewMode
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.monthTitle
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    initialMode: CalendarViewMode,
    initialDate: LocalDate,
    onBack: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenDayNote: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = viewModel(
        key = "calendar-$initialMode-$initialDate",
        factory = viewModelFactory {
            initializer { CalendarViewModel(initialMode, initialDate) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val view = LocalView.current

    // Cambiar de vista repinta media pantalla: es el momento de gastar un
    // refresco completo y quitar los fantasmas acumulados (§11).
    LaunchedEffect(state.mode) { EinkRefresh.fullRefresh(view) }

    Column(modifier.fillMaxSize().background(Eink.White)) {
        CalendarTopBar(
            state = state,
            onBack = onBack,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onToday = viewModel::goToToday,
            onMode = viewModel::setMode,
            onSync = viewModel::syncNow,
            onNewEvent = { onNewEvent(state.selected) },
            onDayNote = { onOpenDayNote(state.selected) },
            onOpenTasks = onOpenTasks,
            onOpenSettings = onOpenSettings,
        )
        EinkDivider(color = Eink.Black)

        Box(Modifier.weight(1f)) {
            when (state.mode) {
                CalendarViewMode.MONTH -> MonthGrid(
                    state = state,
                    zone = zone,
                    onSelectDay = viewModel::select,
                    onOpenDay = {
                        viewModel.select(it)
                        viewModel.setMode(CalendarViewMode.DAY)
                    },
                    onToggleTask = viewModel::toggleTask,
                )

                CalendarViewMode.WEEK -> TimeGrid(
                    days = state.buckets.values.toList(),
                    zone = zone,
                    selected = state.selected,
                    showDayHeaders = true,
                    onSelectDay = viewModel::select,
                    onOpenEvent = onOpenEvent,
                    onOpenTask = onOpenTask,
                    onToggleTask = viewModel::toggleTask,
                )

                CalendarViewMode.DAY -> TimeGrid(
                    days = state.buckets.values.toList(),
                    zone = zone,
                    selected = state.selected,
                    showDayHeaders = false,
                    onSelectDay = viewModel::select,
                    onOpenEvent = onOpenEvent,
                    onOpenTask = onOpenTask,
                    onToggleTask = viewModel::toggleTask,
                )
            }
        }

        state.lastSyncError?.let { error ->
            EinkDivider()
            Text(
                text = "Sincronización: $error",
                style = MaterialTheme.typography.labelSmall,
                color = Eink.Graphite,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Eink.White)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

private val dayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.getDefault())

@Composable
private fun CalendarTopBar(
    state: CalendarUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onMode: (CalendarViewMode) -> Unit,
    onSync: () -> Unit,
    onNewEvent: () -> Unit,
    onDayNote: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = "Volver al panel")
            EinkIconButton(Glyph.ChevronLeft, onPrevious, contentDescription = "Anterior", box = 44.dp)
            EinkIconButton(Glyph.ChevronRight, onNext, contentDescription = "Siguiente", box = 44.dp)
            Text(
                text = when (state.mode) {
                    CalendarViewMode.DAY -> state.anchor.format(dayTitleFormatter)
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

                    else -> monthTitle(state.anchor)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            EinkIconButton(
                glyph = Glyph.SyncCloud,
                onClick = onSync,
                contentDescription = "Sincronizar",
                enabled = !state.syncing,
                accent = Accent.Sync,
            )
            EinkIconButton(Glyph.ListCheck, onOpenTasks, contentDescription = "Recordatorios", accent = Accent.Reminder)
            EinkIconButton(Glyph.Gear, onOpenSettings, contentDescription = "Ajustes", accent = Accent.Settings)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Mes, semana y día como iconos de rejilla: la forma dice cuántas
            // columnas se van a ver mejor que la palabra.
            CalendarViewMode.entries.forEach { mode ->
                EinkIconButton(
                    glyph = when (mode) {
                        CalendarViewMode.MONTH -> Glyph.GridMonth
                        CalendarViewMode.WEEK -> Glyph.GridWeek
                        CalendarViewMode.DAY -> Glyph.GridDay
                    },
                    onClick = { onMode(mode) },
                    contentDescription = mode.label,
                    selected = state.mode == mode,
                    accent = Accent.Event,
                )
            }
            EinkIconButton(Glyph.Today, onToday, contentDescription = "Hoy", accent = Accent.Today)
            Spacer(Modifier.weight(1f))
            EinkIconButton(Glyph.Pencil, onDayNote, contentDescription = "Escribir a mano", accent = Accent.Note)
            EinkIconButton(Glyph.Plus, onNewEvent, contentDescription = "Evento nuevo", accent = Accent.Event)
        }
    }
}
