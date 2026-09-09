package com.weto.booxcal.ui.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.domain.usecase.TaskGrouping
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkRadioCheck
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkSectionHeader
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.MILLIS_PER_DAY
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.domain.usecase.SectionKind
import com.weto.booxcal.domain.usecase.TaskSection
import com.weto.booxcal.util.rememberDateFormat


@Composable
fun TasksScreen(
    onOpenTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var completedExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(Unit) { EinkRefresh.fullRefresh(view) }

    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = stringResource(R.string.common_back))
            Text(
                text = stringResource(R.string.common_tasks),
                style = MaterialTheme.typography.headlineSmall,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            EinkIconButton(
                glyph = Glyph.SyncCloud,
                onClick = viewModel::syncNow,
                contentDescription = stringResource(R.string.common_sync),
                enabled = !state.syncing,
                accent = Accent.Sync,
            )
            EinkIconButton(Glyph.Plus, onNewTask, contentDescription = stringResource(R.string.common_new_reminder), accent = Accent.Reminder)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Agrupar por fecha es un calendario; por lista, unos puntos.
            TaskGrouping.entries.forEach { grouping ->
                EinkIconButton(
                    glyph = when (grouping) {
                        TaskGrouping.BY_DATE -> Glyph.Today
                        TaskGrouping.BY_LIST -> Glyph.Bullets
                    },
                    onClick = { viewModel.setGrouping(grouping) },
                    contentDescription = stringResource(
                        if (grouping == TaskGrouping.BY_DATE) R.string.tasks_group_by_date else R.string.tasks_group_by_list
                    ),
                    selected = state.grouping == grouping,
                    accent = Accent.Reminder,
                )
            }
            Spacer(Modifier.weight(1f))
            EinkHint(stringResource(R.string.tasks_pending_count, state.pendingCount))
        }
        EinkDivider(color = Eink.Black)

        if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EinkHint(stringResource(R.string.tasks_empty))
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            state.sections.forEach { section ->
                item(key = "header-${section.kind}-${section.title}") {
                    if (section.completed) {
                        // La sección de completadas se pliega: con el tiempo es
                        // la más larga y no es la que se viene a consultar.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .einkClickable { completedExpanded = !completedExpanded }
                                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EinkGlyph(
                                glyph = if (completedExpanded) Glyph.CaretDown else Glyph.CaretRight,
                                size = 16.dp,
                                tint = Eink.Graphite,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${sectionTitle(section)} (${section.tasks.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = Eink.Graphite,
                            )
                        }
                    } else {
                        EinkSectionHeader(
                            title = sectionTitle(section),
                            trailing = {
                                Text(
                                    text = section.tasks.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Eink.Graphite,
                                )
                            },
                        )
                    }
                }
                itemsIndexed(
                    items = if (section.completed && !completedExpanded) emptyList() else section.tasks,
                    key = { _, row -> "task-${row.task.id}" },
                ) { index, row ->
                    TaskRow(
                        row = row,
                        overdue = section.overdue,
                        canMoveUp = !section.completed && index > 0,
                        canMoveDown = !section.completed && index < section.tasks.lastIndex,
                        onToggle = { checked -> viewModel.toggle(row.task.id, checked) },
                        onClick = { onOpenTask(row.task.id) },
                        onMoveUp = { viewModel.move(section.tasks, index, -1) },
                        onMoveDown = { viewModel.move(section.tasks, index, 1) },
                    )
                    EinkDivider()
                }
            }
            item {
                Column(Modifier.padding(12.dp)) {
                    EinkHint(retentionCaption(state.retentionDays))
                }
            }
        }
    }
}

@Composable
private fun retentionCaption(retentionDays: Int): String =
    if (retentionDays < 0) {
        stringResource(R.string.tasks_retention_never)
    } else {
        stringResource(R.string.tasks_retention_days, retentionDays)
    }

@Composable
private fun sectionTitle(section: TaskSection): String = when (section.kind) {
    SectionKind.OVERDUE -> stringResource(R.string.section_overdue)
    SectionKind.TODAY -> stringResource(R.string.section_today)
    SectionKind.TOMORROW -> stringResource(R.string.section_tomorrow)
    SectionKind.THIS_WEEK -> stringResource(R.string.section_this_week)
    SectionKind.LATER -> stringResource(R.string.section_later)
    SectionKind.UNDATED -> stringResource(R.string.section_undated)
    SectionKind.COMPLETED -> stringResource(R.string.section_completed)
    SectionKind.LIST -> section.title
}

@Composable
private fun TaskRow(
    row: TaskWithList,
    overdue: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val task = row.task
    val completed = task.completedAt != null

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkRadioCheck(checked = completed, onCheckedChange = onToggle)

        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(
                text = task.title.ifBlank { stringResource(R.string.common_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (completed) Eink.Slate else Eink.Black,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val dueFormatter = rememberDateFormat(R.string.pattern_day_month)
            val meta = buildList {
                task.dueDayMillis?.let { add(formatDue(it, overdue, dueFormatter)) }
                add(row.listName)
                if (!task.notes.isNullOrBlank()) add(stringResource(R.string.tasks_meta_note))
            }.joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue && !completed) Eink.Black else Eink.Graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (canMoveUp || canMoveDown) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (canMoveUp) EinkIconButton(Glyph.ChevronUp, onMoveUp, contentDescription = stringResource(R.string.tasks_move_up), box = 40.dp)
                if (canMoveDown) EinkIconButton(Glyph.ChevronDown, onMoveDown, contentDescription = stringResource(R.string.tasks_move_down), box = 40.dp)
            }
        }
    }
}

@Composable
private fun formatDue(dueDayMillis: Long, overdue: Boolean, dueFormatter: DateTimeFormatter): String {
    val date = LocalDate.ofEpochDay(Math.floorDiv(dueDayMillis, MILLIS_PER_DAY))
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.tasks_today_lower)
        today.plusDays(1) -> stringResource(R.string.tasks_tomorrow_lower)
        today.minusDays(1) -> stringResource(R.string.tasks_yesterday_lower)
        else -> date.format(dueFormatter).replace(".", "")
    }
    return if (overdue) stringResource(R.string.tasks_was_due, label) else label
}
