package com.weto.booxcal.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weto.booxcal.AppLinks
import com.weto.booxcal.BuildConfig
import com.weto.booxcal.R
import com.weto.booxcal.data.settings.AppSettings
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ink.InkFonts
import com.weto.booxcal.ink.ModelState
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkCard
import com.weto.booxcal.ui.theme.EinkCardHeader
import com.weto.booxcal.ui.theme.EinkCheckbox
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.ui.theme.relativeTime
import com.weto.booxcal.util.AppLocale
import com.weto.booxcal.util.rememberLocale
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle

private val SYNC_INTERVALS = listOf(15, 30, 60, 180)

/**
 * Settings: cards in two columns, like the home screen.
 *
 * Each card is a topic (account, calendar, defaults, sync, language,
 * handwriting, Drive, completed tasks) with its rows: a label and its
 * control. Explanations are kept to the minimum: a control that needs a
 * paragraph is a control that is badly named.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    LaunchedEffect(Unit) { EinkRefresh.fullRefresh(view) }

    val context = LocalContext.current
    // Coming back from the browser the account may have changed state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccount()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = stringResource(R.string.common_back))
            Text(
                text = stringResource(R.string.common_settings),
                style = MaterialTheme.typography.headlineSmall,
                color = Eink.Black,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        EinkDivider(color = Eink.Black)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.message?.let { message ->
                Notice(message, onDismiss = viewModel::clearMessage)
            }

            val browserOpened = stringResource(R.string.settings_browser_opened)
            val noBrowser = stringResource(R.string.settings_no_browser)
            val connectFailed = stringResource(R.string.settings_connect_failed)
            AccountCard(
                state = state,
                onConnect = {
                    viewModel.authorizationIntent()
                        .onFailure { viewModel.showMessage(connectFailed) }
                        .onSuccess { intent ->
                            runCatching { context.startActivity(intent) }
                                .onFailure { viewModel.showMessage(noBrowser) }
                                .onSuccess { viewModel.showMessage(browserOpened) }
                        }
                },
                onSyncNow = viewModel::syncNow,
                onSignOut = viewModel::signOut,
            )

            if (!state.settings.powerWarningAcknowledged) {
                PowerWarningCard(onAcknowledge = viewModel::acknowledgePowerWarning)
            }

            // Two columns with the cards shared out so they measure alike.
            // None is stretched to match the other: a padded card with a gap
            // inside looked worse than a finger of difference at the bottom.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val locale = rememberLocale()
                    SettingsCard(stringResource(R.string.common_calendar)) {
                        SettingRow(label = stringResource(R.string.settings_week_starts)) {
                            ChoiceRow(
                                options = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).map { day ->
                                    day to day.getDisplayName(JavaTextStyle.FULL, locale)
                                        .replaceFirstChar { it.titlecase(locale) }
                                },
                                selected = state.settings.weekStart,
                                onSelect = viewModel::setWeekStart,
                            )
                        }
                        EinkDivider()
                        ToggleRow(
                            label = stringResource(R.string.settings_reminders_in_calendar),
                            checked = state.settings.showTasksInCalendar,
                            onCheckedChange = viewModel::setShowTasksInCalendar,
                        )
                        EinkDivider()
                        SettingRow(label = stringResource(R.string.settings_status_bar)) {
                            ChoiceRow(
                                options = listOf(
                                    "app" to stringResource(R.string.settings_status_bar_app),
                                    "auto" to stringResource(R.string.settings_status_bar_auto),
                                    "system" to stringResource(R.string.settings_status_bar_system),
                                ),
                                selected = state.settings.statusStrip,
                                onSelect = viewModel::setStatusStrip,
                            )
                        }
                    }

                    DefaultsCard(
                        state = state,
                        onCalendar = viewModel::setDefaultCalendar,
                        onTaskList = viewModel::setDefaultTaskList,
                    )

                    SettingsCard(stringResource(R.string.settings_sync)) {
                        SettingRow(label = stringResource(R.string.settings_sync_every)) {
                            ChoiceRow(
                                options = SYNC_INTERVALS.map { minutes ->
                                    minutes to if (minutes < 60) {
                                        stringResource(R.string.duration_minutes, minutes)
                                    } else {
                                        stringResource(R.string.duration_hours, minutes / 60)
                                    }
                                },
                                selected = state.settings.syncIntervalMinutes,
                                onSelect = viewModel::setSyncInterval,
                            )
                        }
                    }

                    LanguageCard(current = state.language, onSelect = { tag ->
                        viewModel.setLanguage(tag)
                        // Before Android 13 nothing recreates the screen for us.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.findActivity()?.recreate()
                    })
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsCard(stringResource(R.string.settings_handwriting)) {
                        SettingRow(label = stringResource(R.string.settings_text_recognition)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val status = when (val model = state.ocrModel) {
                                    ModelState.Ready -> stringResource(R.string.settings_model_downloaded)
                                    ModelState.Downloading -> stringResource(R.string.settings_model_downloading)
                                    is ModelState.Unavailable -> model.reason
                                    ModelState.Unknown -> null
                                }
                                status?.let { EinkHint(it) }
                                EinkButton(
                                    label = stringResource(
                                        if (state.ocrModel == ModelState.Ready) R.string.settings_model_check else R.string.settings_model_download
                                    ),
                                    onClick = viewModel::downloadOcrModel,
                                    enabled = state.ocrModel != ModelState.Downloading,
                                )
                            }
                        }
                        EinkDivider()
                        SettingRow(label = stringResource(R.string.settings_text_tool)) {
                            ChoiceRow(
                                options = listOf(
                                    "pencil" to stringResource(R.string.tip_pencil),
                                    "ballpoint" to stringResource(R.string.tip_ballpoint),
                                    "any" to stringResource(R.string.settings_text_tool_any),
                                ),
                                selected = state.settings.inkTextTool,
                                onSelect = viewModel::setInkTextTool,
                            )
                        }
                        EinkDivider()
                        SettingRow(label = stringResource(R.string.settings_text_font)) {
                            FontChoices(
                                selected = state.settings.inkTextFont,
                                onSelect = viewModel::setInkTextFont,
                            )
                        }
                    }

                    DriveNotesCard(
                        state = state,
                        onEnable = viewModel::enableDriveFolder,
                        onClear = viewModel::clearDriveFolder,
                        onSyncNow = viewModel::syncNotesNow,
                    )

                    SettingsCard(stringResource(R.string.settings_completed_tasks)) {
                        SettingRow(label = stringResource(R.string.settings_hide_after)) {
                            ChoiceRow(
                                options = RETENTION_CHOICES.map { days ->
                                    days to when {
                                        days == AppSettings.NEVER_PURGE -> stringResource(R.string.settings_never)
                                        days >= 365 -> stringResource(R.string.settings_one_year)
                                        else -> stringResource(R.string.settings_days_short, days)
                                    }
                                },
                                selected = state.settings.retentionDays,
                                onSelect = viewModel::setRetentionDays,
                            )
                        }
                    }
                }
            }

            val noBrowserFor = stringResource(R.string.settings_no_browser)
            AboutCard(onOpen = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { viewModel.showMessage(noBrowserFor) }
            })

            // Signature at the foot: two points larger than small text, bold,
            // the name in italics as well.
            Text(
                text = buildAnnotatedString {
                    append("Boox Calendar by ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Álvaro Rubio Adán") }
                    append(" · ${stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)}")
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Bold,
                color = Eink.Slate,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// --- Account -----------------------------------------------------------------

@Composable
private fun AccountCard(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_google_account)) {
        when {
            !state.oauthConfigured -> EinkHint(stringResource(R.string.settings_oauth_missing))

            state.authorized -> {
                // One row: account, state and the two buttons at the end.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EinkTile(Glyph.Mail, Accent.Account, size = 40.dp)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = state.accountEmail ?: stringResource(R.string.settings_account_connected),
                            style = MaterialTheme.typography.titleMedium,
                            color = Eink.Black,
                        )
                        // No connect button: once the permission is given the
                        // app syncs by itself. All that matters is whether it works.
                        Text(
                            text = syncStatusText(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Eink.Graphite,
                        )
                    }
                    EinkButton(stringResource(R.string.settings_disconnect), onSignOut)
                    EinkButton(stringResource(R.string.settings_sync_now), onSyncNow, enabled = !state.syncing, emphasized = true)
                }
            }

            else -> {
                Text(
                    stringResource(R.string.settings_connect_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Eink.Black,
                )
                Text(
                    stringResource(R.string.settings_connect_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Eink.Graphite,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    EinkButton(stringResource(R.string.settings_connect_button), onConnect, emphasized = true)
                }
            }
        }
    }
}

@Composable
private fun syncStatusText(state: SettingsUiState): String = when {
    state.syncing -> stringResource(R.string.sync_status_syncing)
    state.settings.lastSyncError != null -> stringResource(R.string.common_not_synced, state.settings.lastSyncError)
    state.settings.lastSyncAt > 0 -> stringResource(R.string.sync_status_synced, relativeTime(state.settings.lastSyncAt))
    else -> stringResource(R.string.sync_status_never)
}

@Composable
private fun PowerWarningCard(onAcknowledge: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .border(2.dp, Eink.Black, ControlCorner)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_power_title),
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
        Text(
            text = stringResource(R.string.settings_power_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Eink.Graphite,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EinkButton(stringResource(R.string.settings_understood), onAcknowledge)
        }
    }
}

/**
 * Which calendar and which list the creation form opens with. Only the
 * initial value: in the form itself it can be changed every time.
 */
@Composable
private fun DefaultsCard(
    state: SettingsUiState,
    onCalendar: (Long) -> Unit,
    onTaskList: (Long) -> Unit,
) {
    val calendars = state.calendars.filter { it.isWritable }
    val lists = state.taskLists
    SettingsCard(stringResource(R.string.settings_defaults)) {
        SettingRow(label = stringResource(R.string.settings_default_calendar)) {
            ChoiceList(
                options = listOf(0L to stringResource(R.string.settings_primary_calendar)) + calendars.map { it.id to it.name },
                selected = state.settings.defaultCalendarId,
                onSelect = onCalendar,
            )
        }
        EinkDivider()
        SettingRow(label = stringResource(R.string.settings_default_list)) {
            ChoiceList(
                options = listOf(0L to stringResource(R.string.settings_first_list)) + lists.map { it.id to it.name },
                selected = state.settings.defaultTaskListId,
                onSelect = onTaskList,
            )
        }
    }
}

/** The language of the app: the device's, Spanish or English. */
@Composable
private fun LanguageCard(current: String, onSelect: (String) -> Unit) {
    SettingsCard(stringResource(R.string.settings_language)) {
        ChoiceRow(
            options = listOf(
                AppLocale.SYSTEM to stringResource(R.string.settings_language_system),
                AppLocale.SPANISH to stringResource(R.string.settings_language_spanish),
                AppLocale.ENGLISH to stringResource(R.string.settings_language_english),
            ),
            selected = current,
            onSelect = onSelect,
        )
    }
}

/** Version, and the links Google wants to see from the app: privacy and code. */
@Composable
private fun AboutCard(onOpen: (String) -> Unit) {
    SettingsCard(stringResource(R.string.settings_about)) {
        Text(
            text = "Boox Calendar ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
        Text(
            text = stringResource(R.string.settings_about_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Eink.Graphite,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton(stringResource(R.string.settings_privacy_policy), { onOpen(AppLinks.PRIVACY) })
            EinkButton(stringResource(R.string.settings_code_and_versions), { onOpen(AppLinks.REPOSITORY) })
        }
    }
}

// --- Pieces ------------------------------------------------------------------

/** Notes and Google Drive: the folder, the state of the last pass and the sync-now button. */
@Composable
private fun DriveNotesCard(
    state: SettingsUiState,
    onEnable: () -> Unit,
    onClear: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val settings = state.settings
    val configured = settings.driveNotesFolderId != null
    val canEnable = state.authorized && state.hasDriveScope
    SettingsCard(stringResource(R.string.settings_drive_notes)) {
        SettingRow(label = stringResource(R.string.settings_drive_folder)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = settings.driveNotesFolderName ?: stringResource(R.string.settings_drive_off),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (configured) Eink.Black else Eink.Graphite,
                    modifier = Modifier.weight(1f),
                )
                if (configured) EinkButton(stringResource(R.string.common_remove), onClear)
                EinkButton(
                    stringResource(if (configured) R.string.settings_drive_use_app_folder else R.string.settings_enable),
                    onEnable,
                    enabled = canEnable,
                    emphasized = !configured,
                )
            }
        }
        if (state.authorized && !state.hasDriveScope) {
            EinkDivider()
            Text(
                text = stringResource(R.string.settings_drive_reconnect),
                style = MaterialTheme.typography.bodyMedium,
                color = Eink.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ControlCorner)
                    .border(2.dp, Eink.Black, ControlCorner)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (configured) {
            EinkDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when {
                        state.notesSyncing -> stringResource(R.string.settings_notes_syncing)
                        settings.driveNotesError != null -> stringResource(R.string.common_not_synced, settings.driveNotesError)
                        settings.driveNotesSyncAt > 0 -> stringResource(R.string.settings_notes_synced, relativeTime(settings.driveNotesSyncAt))
                        else -> stringResource(R.string.settings_notes_not_synced_yet)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Eink.Black,
                    modifier = Modifier.weight(1f),
                )
                EinkButton(stringResource(R.string.settings_sync_notes), onSyncNow, enabled = !state.notesSyncing)
            }
        }
    }
}

/** Settings card: header with the topic and the rows below, with air between them. */
@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    EinkCard(modifier.fillMaxWidth()) {
        EinkCardHeader(title)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A settings row: the label and the control below (the controls here are rows of buttons that do not fit beside). */
@Composable
private fun SettingRow(
    label: String,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            fontWeight = FontWeight.Medium,
        )
        control()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        EinkCheckbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Mutually exclusive buttons in a row; the chosen one goes black. */
@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            EinkButton(
                label = label,
                onClick = { onSelect(value) },
                selected = value == selected,
            )
        }
    }
}

/**
 * A framed list of options with its own scroll: for lists whose length is
 * not known (the calendars and the lists of the account). The frame keeps
 * a fixed maximum height, so twenty calendars do not push the rest of the
 * screen down; four rows show and the rest scrolls inside.
 */
@Composable
private fun <T> ChoiceList(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .border(HairlineWidth, Eink.Black, ControlCorner)
            .heightIn(max = CHOICE_LIST_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
    ) {
        options.forEachIndexed { index, (value, label) ->
            val chosen = value == selected
            if (index > 0) EinkDivider(color = Eink.Border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (chosen) Eink.Black else Eink.White)
                    .einkClickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp)) {
                    if (chosen) EinkGlyph(Glyph.Check, size = 16.dp, tint = Eink.White)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chosen) Eink.White else Eink.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Four rows of options before the list scrolls inside its frame. */
private val CHOICE_LIST_MAX_HEIGHT = 168.dp

/** The fonts, each written in itself: the only way to choose a font. The chosen one goes black. */
@Composable
private fun FontChoices(selected: String, onSelect: (String) -> Unit) {
    // Two columns: six rows edge to edge made the card twice as tall as the one beside it.
    val sample = stringResource(R.string.settings_font_sample)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InkFonts.all.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { font ->
                    val chosen = font.id == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(ControlCorner)
                            .background(if (chosen) Eink.Black else Eink.White)
                            .border(if (chosen) 2.dp else 1.dp, Eink.Black, ControlCorner)
                            .einkClickable { onSelect(font.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(font.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chosen) Eink.White else Eink.Graphite,
                        )
                        Text(
                            text = sample,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = font.family),
                            color = if (chosen) Eink.White else Eink.Black,
                            maxLines = 1,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Notice of a recent action. Closes when tapped. */
@Composable
private fun Notice(message: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .border(1.dp, Eink.Black, ControlCorner)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Eink.Black,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            EinkIconButton(Glyph.Check, onDismiss, contentDescription = stringResource(R.string.common_close), box = 40.dp, size = 18.dp)
        }
    }
}
