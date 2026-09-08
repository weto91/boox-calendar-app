package com.weto.booxcal.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.einkClickable
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val SYNC_INTERVALS = listOf(15, 30, 60, 180)

/**
 * Ajustes: tarjetas en dos columnas, como la portada.
 *
 * Cada tarjeta es un tema (cuenta, calendario, sincronización, tareas,
 * escritura) con sus filas: rótulo a la izquierda, control a la derecha, y la
 * explicación debajo solo cuando hace falta. Nada de listas sueltas.
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
    // Al volver del navegador la cuenta puede haber cambiado de estado.
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
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = "Volver")
            Text(
                text = "Ajustes",
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

            AccountCard(
                state = state,
                onConnect = {
                    viewModel.authorizationIntent()
                        .onFailure { viewModel.showMessage("No se pudo preparar la conexión: $it") }
                        .onSuccess { intent ->
                            runCatching { context.startActivity(intent) }
                                .onFailure { viewModel.showMessage("No hay navegador: $it") }
                                .onSuccess { viewModel.showMessage("Se ha abierto el navegador. Vuelve aquí al terminar.") }
                        }
                },
                onSyncNow = viewModel::syncNow,
                onSignOut = viewModel::signOut,
                packageName = context.packageName,
            )

            if (!state.settings.powerWarningAcknowledged) {
                PowerWarningCard(onAcknowledge = viewModel::acknowledgePowerWarning)
            }

            // Dos columnas con las tarjetas repartidas para que midan parecido.
            // Ninguna se estira para igualar a la otra: una tarjeta hinchada
            // con hueco dentro se veía peor que un dedo de diferencia abajo.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsCard("Calendario") {
                        SettingRow(
                            label = "La semana empieza en",
                        ) {
                            ChoiceRow(
                                options = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).map { day ->
                                    day to day.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
                                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                                },
                                selected = state.settings.weekStart,
                                onSelect = viewModel::setWeekStart,
                            )
                        }
                        EinkDivider()
                        ToggleRow(
                            label = "Recordatorios en el calendario",
                            hint = "Los recordatorios con fecha salen en el mes, la semana y el día.",
                            checked = state.settings.showTasksInCalendar,
                            onCheckedChange = viewModel::setShowTasksInCalendar,
                        )
                        EinkDivider()
                        SettingRow(
                            label = "Barra superior (hora y batería)",
                            hint = "«De la app»: se oculta la de Android y la app pone la suya; " +
                                "deslizar desde arriba trae la de Android un momento. " +
                                "«Automática»: la de la app solo si el sistema esconde la de " +
                                "Android. «Del sistema»: solo la de Android, negra con iconos claros.",
                        ) {
                            ChoiceRow(
                                options = listOf(
                                    "app" to "De la app",
                                    "auto" to "Automática",
                                    "system" to "Del sistema",
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

                    SettingsCard("Sincronización") {
                        SettingRow(
                            label = "Cada",
                            hint = "Quince minutos es el mínimo que permite Android. Lo que cambia " +
                                "en Google llega a la tablet con esta cadencia; lo que cambia " +
                                "aquí sube en cuanto se hace.",
                        ) {
                            ChoiceRow(
                                options = SYNC_INTERVALS.map { minutes ->
                                    minutes to if (minutes < 60) "$minutes min" else "${minutes / 60} h"
                                },
                                selected = state.settings.syncIntervalMinutes,
                                onSelect = viewModel::setSyncInterval,
                            )
                        }
                    }

                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsCard("Escritura a mano") {
                        SettingRow(
                            label = "Reconocimiento de texto",
                            hint = "Modelo del idioma «${state.settings.ocrLanguageTag}». Funciona " +
                                "sin red una vez descargado. Es lo que usa «A texto» y la " +
                                "creación de eventos desde el lazo.",
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val status = when (val model = state.ocrModel) {
                                    ModelState.Ready -> "Descargado"
                                    ModelState.Downloading -> "Descargando…"
                                    is ModelState.Unavailable -> model.reason
                                    ModelState.Unknown -> null
                                }
                                status?.let { EinkHint(it) }
                                EinkButton(
                                    label = if (state.ocrModel == ModelState.Ready) "Comprobar" else "Descargar",
                                    onClick = viewModel::downloadOcrModel,
                                    enabled = state.ocrModel != ModelState.Downloading,
                                )
                            }
                        }
                        EinkDivider()
                        SettingRow(
                            label = "«Toda la nota a texto» convierte lo escrito con",
                            hint = "Con lápiz o bolígrafo, lo hecho con la otra punta se queda " +
                                "como dibujo, sin adivinar nada. Con «cualquiera» la app decide " +
                                "por la forma qué es dibujo, y a veces se equivoca.",
                        ) {
                            ChoiceRow(
                                options = listOf(
                                    "pencil" to "Lápiz",
                                    "ballpoint" to "Bolígrafo",
                                    "any" to "Cualquiera",
                                ),
                                selected = state.settings.inkTextTool,
                                onSelect = viewModel::setInkTextTool,
                            )
                        }
                        EinkDivider()
                        SettingRow(
                            label = "Letra del texto transcrito",
                            hint = "La que usan «A texto» y «toda la nota a texto». Cambiarla " +
                                "cambia también el texto ya convertido.",
                        ) {
                            FontChoices(
                                selected = state.settings.inkTextFont,
                                onSelect = viewModel::setInkTextFont,
                            )
                        }
                        EinkDivider()
                        Text(
                            text = "Solo escribe el lápiz; con el dedo se pulsan botones. Grosor, " +
                                "color y punta se eligen tocando la pluma sobre el cuaderno.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Eink.Graphite,
                        )
                        if (!EinkRefresh.isOnyxDevice) {
                            EinkHint(
                                "Este dispositivo no es un Onyx: se usa la captura táctil normal, " +
                                    "con el retardo habitual de Android."
                            )
                        }
                    }

                    DriveNotesCard(
                        state = state,
                        onEnable = viewModel::enableDriveFolder,
                        onClear = viewModel::clearDriveFolder,
                        onSyncNow = viewModel::syncNotesNow,
                    )

                    SettingsCard("Tareas completadas") {
                        SettingRow(
                            label = "Ocultar pasados",
                            hint = "Se cuenta desde que se completó o desde que vencía, lo que " +
                                "sea más tarde. No se borra nada: la tarea sigue en Google.",
                        ) {
                            ChoiceRow(
                                options = RETENTION_CHOICES.map { days ->
                                    days to when {
                                        days == AppSettings.NEVER_PURGE -> "Nunca"
                                        days >= 365 -> "1 año"
                                        else -> "$days d"
                                    }
                                },
                                selected = state.settings.retentionDays,
                                onSelect = viewModel::setRetentionDays,
                            )
                        }
                    }
                }
            }

            AboutCard(onOpen = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { viewModel.showMessage("No hay navegador para abrir $url") }
            })

            // Firma al pie: dos puntos más que el texto pequeño (11 → 13), en
            // negrita, y el nombre además en cursiva.
            Text(
                text = buildAnnotatedString {
                    append("Boox Calendar By ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Álvaro Rubio Adán") }
                    append(" · versión ${BuildConfig.VERSION_NAME}")
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

// --- Cuenta -----------------------------------------------------------------

@Composable
private fun AccountCard(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    packageName: String,
) {
    SettingsCard("Cuenta de Google") {
        when {
            !state.oauthConfigured -> EinkHint(
                "Falta el client ID de OAuth. Añade GOOGLE_OAUTH_CLIENT_ID a " +
                    "local.properties y vuelve a compilar. Ver docs/SETUP_GOOGLE.md."
            )

            state.authorized -> {
                // Una sola fila: cuenta, estado y los dos botones al final.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EinkTile(Glyph.Mail, Accent.Account, size = 40.dp)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = state.accountEmail ?: "Cuenta conectada",
                            style = MaterialTheme.typography.titleMedium,
                            color = Eink.Black,
                        )
                        // Sin botón de conectar: una vez dado el permiso, la app
                        // se sincroniza sola. Lo único que hace falta saber es
                        // si va o no va.
                        Text(
                            text = state.syncStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Eink.Graphite,
                        )
                    }
                    EinkButton("Desconectar", onSignOut)
                    EinkButton("Sincronizar ahora", onSyncNow, enabled = !state.syncing, emphasized = true)
                }
            }

            else -> {
                Text(
                    "Conecta tu cuenta una sola vez",
                    style = MaterialTheme.typography.titleMedium,
                    color = Eink.Black,
                )
                // Paso a paso, porque "algo en un navegador" no le dice nada a
                // quien no lo ha hecho nunca.
                listOf(
                    "Solo la primera vez, en Google Cloud → Credenciales → tu cliente " +
                        "Android → Configuración avanzada: activa «Habilitar esquema " +
                        "de URI personalizado» y guarda. Sin eso Google responde " +
                        "«Error 400: invalid_request».",
                    "Pulsa «Conectar». Se abre el navegador de la tablet.",
                    "Elige tu cuenta de Google y escribe tu contraseña si la pide.",
                    "Si sale «Google no ha verificado esta aplicación», pulsa " +
                        "«Configuración avanzada» y luego «Ir a Calendario».",
                    "Marca los permisos de Calendario y Tareas y pulsa «Continuar».",
                    "El navegador vuelve aquí solo. A partir de ahí se sincroniza sola.",
                ).forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Eink.Black,
                            modifier = Modifier.width(26.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyLarge, color = Eink.Black)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    EinkButton("Conectar mi cuenta de Google", onConnect, emphasized = true)
                }
                EinkDivider()
                // Lo que la app manda a Google, para cotejarlo con la consola
                // cuando responde «invalid_request».
                Text("Datos para la consola de Google", style = MaterialTheme.typography.labelMedium, color = Eink.Graphite)
                EinkHint(
                    "Cliente: ${BuildConfig.OAUTH_CLIENT_ID}\n" +
                        "Redirección: ${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect\n" +
                        "Paquete: $packageName"
                )
            }
        }
    }
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
            text = "Excluye esta app de la optimización de energía",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
        Text(
            text = "Ajustes del sistema → Energía → Optimización de apps → desactiva la " +
                "optimización para «Boox Calendar». Si no, BooxOS matará la sincronización " +
                "en segundo plano y parecerá que la app no funciona.",
            style = MaterialTheme.typography.bodyMedium,
            color = Eink.Graphite,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EinkButton("Entendido", onAcknowledge)
        }
    }
}

/**
 * Con qué calendario y qué lista se abre el formulario de crear. Es solo el
 * valor inicial: en el propio formulario se puede cambiar cada vez.
 */
@Composable
private fun DefaultsCard(
    state: SettingsUiState,
    onCalendar: (Long) -> Unit,
    onTaskList: (Long) -> Unit,
) {
    val calendars = state.calendars.filter { it.isWritable }
    val lists = state.taskLists
    SettingsCard("Al crear, por defecto") {
        SettingRow(
            label = "Calendario del evento nuevo",
            hint = if (calendars.isEmpty()) "Aún no hay calendarios: llegan con la primera sincronización." else null,
        ) {
            ChoiceGrid(
                options = listOf(0L to "El principal") + calendars.map { it.id to it.name },
                selected = state.settings.defaultCalendarId,
                onSelect = onCalendar,
            )
        }
        EinkDivider()
        SettingRow(
            label = "Lista del recordatorio nuevo",
            hint = if (lists.isEmpty()) "Aún no hay listas: llegan con la primera sincronización." else null,
        ) {
            ChoiceGrid(
                options = listOf(0L to "La primera") + lists.map { it.id to it.name },
                selected = state.settings.defaultTaskListId,
                onSelect = onTaskList,
            )
        }
    }
}

/** Versión, y los enlaces que Google quiere ver desde la app: privacidad y código. */
@Composable
private fun AboutCard(onOpen: (String) -> Unit) {
    SettingsCard("Acerca de") {
        Text(
            text = "Boox Calendar ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
        Text(
            text = "Calendario, recordatorios y notas a mano para tabletas Onyx Boox, con tu cuenta de Google. " +
                "Tus datos están solo en esta tablet y en tu cuenta de Google: no hay servidores de terceros, " +
                "ni analítica, ni publicidad.",
            style = MaterialTheme.typography.bodyMedium,
            color = Eink.Graphite,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton("Política de privacidad", { onOpen(AppLinks.PRIVACY) })
            EinkButton("Código y versiones", { onOpen(AppLinks.REPOSITORY) })
        }
    }
}

// --- Piezas -----------------------------------------------------------------

/**
 * Las notas y Google Drive: la carpeta elegida, el estado de la última pasada
 * y el botón de sincronizar ahora.
 */
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
    SettingsCard("Notas en Google Drive") {
        SettingRow(
            label = "Carpeta de Drive",
            hint = "La app crea «Calendario Boox» en Mi unidad y guarda ahí las notas como PDF " +
                "vectorial editable, en carpetas iguales a las del cuaderno. La app solo ve " +
                "lo que ella pone en Drive: un PDF de otra app (OneNote, un escaneo) entra con " +
                "«Importar» desde el cuaderno, que lo copia a esa carpeta.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = settings.driveNotesFolderName ?: "Sin activar",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (configured) Eink.Black else Eink.Graphite,
                    modifier = Modifier.weight(1f),
                )
                if (configured) EinkButton("Quitar", onClear)
                EinkButton(
                    if (configured) "Usar la carpeta de la app" else "Activar",
                    onEnable,
                    enabled = canEnable,
                    emphasized = !configured,
                )
            }
        }
        if (state.authorized && !state.hasDriveScope) {
            EinkDivider()
            Text(
                text = "La cuenta se conectó antes de que la app pidiera permiso para Drive. " +
                    "Desconecta y vuelve a conectar la cuenta, y acepta el permiso de Drive.",
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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.notesSyncing -> "Sincronizando notas…"
                            settings.driveNotesError != null -> "Sin sincronizar: ${settings.driveNotesError}"
                            settings.driveNotesSyncAt > 0 -> {
                                val minutes = (System.currentTimeMillis() - settings.driveNotesSyncAt) / 60_000
                                when {
                                    minutes < 1 -> "Notas sincronizadas hace un momento"
                                    minutes < 60 -> "Notas sincronizadas hace $minutes min"
                                    else -> "Notas sincronizadas hace ${minutes / 60} h"
                                }
                            }
                            else -> "Las notas aún no se han sincronizado"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Eink.Black,
                    )
                    Text(
                        text = "Cada nota sube medio minuto después del último trazo, y la carpeta " +
                            "se revisa con cada sincronización.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Eink.Graphite,
                    )
                }
                EinkButton("Sincronizar notas", onSyncNow, enabled = !state.notesSyncing)
            }
        }
    }
}

/** Tarjeta de ajustes: cabecera con el tema y las filas debajo, con aire entre ellas. */
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

/**
 * Una fila de ajuste: el rótulo, el control debajo (los controles de aquí
 * son filas de botones que no caben al lado) y la explicación al final.
 */
@Composable
private fun SettingRow(
    label: String,
    hint: String? = null,
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
        hint?.let { EinkHint(it) }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
                fontWeight = FontWeight.Medium,
            )
            hint?.let { EinkHint(it) }
        }
        EinkCheckbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Botones excluyentes en fila; el elegido va en negro. */
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
 * Como [ChoiceRow], pero en varias filas: para listas que no se sabe cuántas
 * son (los calendarios y las listas de la cuenta). Tres por fila.
 */
@Composable
private fun <T> ChoiceGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value, label) ->
                    EinkButton(
                        label = label,
                        onClick = { onSelect(value) },
                        selected = value == selected,
                    )
                }
            }
        }
    }
}

/**
 * Las letras, cada una escrita con ella misma: es la única forma de elegir
 * una letra. La elegida va en negro.
 */
@Composable
private fun FontChoices(selected: String, onSelect: (String) -> Unit) {
    // En dos columnas: seis filas de lado a lado hacían la tarjeta el doble
    // de alta que la columna de al lado.
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
                            text = font.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chosen) Eink.White else Eink.Graphite,
                        )
                        Text(
                            text = "Reunión el jueves a las 10",
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

/** Aviso de una acción reciente. Se cierra tocándolo. */
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
            EinkIconButton(Glyph.Check, onDismiss, contentDescription = "Cerrar el aviso", box = 40.dp, size = 18.dp)
        }
    }
}
