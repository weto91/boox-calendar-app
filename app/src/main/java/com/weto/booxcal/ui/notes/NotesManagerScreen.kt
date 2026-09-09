package com.weto.booxcal.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weto.booxcal.data.local.entity.InkNoteEntity
import com.weto.booxcal.data.local.entity.NoteFolderEntity
import com.weto.booxcal.ink.EinkRefresh
import com.weto.booxcal.ink.StrokeCodec
import com.weto.booxcal.ui.ink.InkPreview
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkTextField
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.util.MILLIS_PER_DAY
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import com.weto.booxcal.util.rememberDateFormat
import com.weto.booxcal.ui.theme.relativeTime
import com.weto.booxcal.ink.TemplateRef


private val TREE_WIDTH = 300.dp

/** Lo que hay abierto encima de la lista. */
private sealed class Sheet {
    data object NewFolder : Sheet()
    data class RenameFolder(val folder: NoteFolderEntity) : Sheet()
    data class DeleteFolder(val folder: NoteFolderEntity) : Sheet()
    data class MoveFolder(val folder: NoteFolderEntity) : Sheet()
    data class RenameNote(val note: InkNoteEntity) : Sheet()
    data class MoveNote(val note: InkNoteEntity) : Sheet()
    data class TagNote(val note: InkNoteEntity) : Sheet()
    data class DeleteNote(val note: InkNoteEntity) : Sheet()
}

/**
 * El cuaderno: todas las notas, en carpetas y con etiquetas.
 *
 * A la izquierda el árbol de carpetas y las etiquetas; a la derecha las notas
 * de lo elegido, con su miniatura. Las notas del día viven en «NOTAS DEL DÍA»
 * por fecha; las que llegan de Google Drive, en la raíz con la etiqueta
 * «importado», hasta que se coloquen a mano.
 */
@Composable
fun NotesManagerScreen(
    onBack: () -> Unit,
    /** Abrir una nota por una página (desde 0). */
    onOpenNote: (InkNoteEntity, Int) -> Unit,
    onNewNote: (folderId: Long?, template: TemplateRef?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesManagerViewModel = viewModel(),
    /** Desde la búsqueda: nota a la que ir (se despliega su carpeta y se abre) y página del hallazgo. */
    revealNoteId: Long? = null,
    revealPage: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var templatePicker by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(Unit) { EinkRefresh.fullRefresh(view) }
    // El selector de archivos del sistema: enseña el Drive del usuario (si
    // tiene la app de Drive), la memoria de la tablet y lo demás. Lo elegido
    // se copia a la carpeta de la app en Drive y entra como nota importada.
    val context = LocalContext.current
    val importPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importPdfs(context, uris)
    }

    // Una sola vez por entrada: al volver de la nota, la pantalla se recompone
    // y no tiene que volver a abrirla. `rememberSaveable` sobrevive en la pila.
    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(revealNoteId) {
        if (revealNoteId == null || revealed) return@LaunchedEffect
        revealed = true
        viewModel.reveal(revealNoteId)?.let { onOpenNote(it, revealPage) }
    }

    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onBack, contentDescription = stringResource(R.string.common_back))
            EinkTile(Glyph.Folder, Accent.Note, size = 32.dp, glyphSize = 18.dp)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    text = stringResource(R.string.common_notebook),
                    style = MaterialTheme.typography.titleLarge,
                    color = Eink.Black,
                )
                Text(
                    text = driveLine(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = Eink.Graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EinkIconButton(
                glyph = Glyph.SyncCloud,
                onClick = viewModel::syncNow,
                contentDescription = stringResource(R.string.notes_sync_drive),
                enabled = !state.syncing,
                accent = Accent.Sync,
            )
            EinkIconButton(
                glyph = Glyph.Folder,
                onClick = { sheet = Sheet.NewFolder },
                contentDescription = stringResource(R.string.notes_new_folder),
                accent = Accent.Note,
            )
            EinkIconButton(
                glyph = Glyph.Upload,
                onClick = { runCatching { importPdfs.launch(arrayOf("application/pdf")) } },
                contentDescription = stringResource(R.string.notes_import_pdf),
                enabled = state.importProgress == null,
                accent = Accent.Sync,
            )
            EinkIconButton(
                glyph = Glyph.Plus,
                onClick = { templatePicker = true },
                contentDescription = stringResource(R.string.notes_new_note),
                accent = Accent.Note,
            )
        }
        EinkDivider(color = Eink.Black)

        state.importProgress?.let { progress ->
            ImportProgressBar(progress)
            EinkDivider()
        }

        state.message?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .einkClickable { viewModel.clearMessage() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Eink.Black, modifier = Modifier.weight(1f))
                EinkHint(stringResource(R.string.common_close))
            }
            EinkDivider()
        }

        Row(Modifier.fillMaxSize()) {
            FolderPane(
                state = state,
                onSelect = viewModel::select,
                onToggle = viewModel::toggleExpanded,
                onTag = viewModel::setTag,
                modifier = Modifier.width(TREE_WIDTH).fillMaxHeight(),
            )
            EinkDivider(Modifier.fillMaxHeight(), color = Eink.Border, vertical = true)
            NotesPane(
                state = state,
                onQuery = viewModel::setQuery,
                onOpen = { note -> onOpenNote(note, 0) },
                onSheet = { sheet = it },
                onNewNote = { templatePicker = true },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }

    if (templatePicker) {
        TemplatePickerDialog(
            onDismiss = { templatePicker = false },
            onPick = { template ->
                templatePicker = false
                onNewNote(state.selectedFolderId, template)
            },
        )
    }

    when (val open = sheet) {
        null -> Unit
        Sheet.NewFolder -> TextPrompt(
            title = stringResource(R.string.notes_new_folder),
            hint = state.selectedFolder?.let { stringResource(R.string.notes_folder_inside, it.name) } ?: stringResource(R.string.notes_folder_root_hint),
            initial = "",
            placeholder = stringResource(R.string.notes_folder_name),
            confirm = stringResource(R.string.common_create),
            onDismiss = { sheet = null },
            onConfirm = { viewModel.createFolder(it); sheet = null },
        )
        is Sheet.RenameFolder -> TextPrompt(
            title = stringResource(R.string.notes_rename_folder),
            hint = null,
            initial = open.folder.name,
            placeholder = stringResource(R.string.notes_name),
            confirm = stringResource(R.string.common_save),
            onDismiss = { sheet = null },
            onConfirm = { viewModel.renameFolder(open.folder.id, it); sheet = null },
        )
        is Sheet.DeleteFolder -> ConfirmPrompt(
            title = stringResource(R.string.notes_delete_folder),
            text = stringResource(R.string.notes_delete_folder_text, open.folder.name),
            confirm = stringResource(R.string.notes_delete_folder_confirm),
            onDismiss = { sheet = null },
            onConfirm = { viewModel.deleteFolder(open.folder.id); sheet = null },
        )
        is Sheet.MoveFolder -> FolderPicker(
            title = stringResource(R.string.notes_move_folder_to, open.folder.name),
            folders = state.folders,
            current = open.folder.parentId,
            exclude = open.folder.id,
            onDismiss = { sheet = null },
            onPick = { viewModel.moveFolder(open.folder.id, it); sheet = null },
        )
        is Sheet.RenameNote -> TextPrompt(
            title = stringResource(R.string.note_title_dialog),
            hint = stringResource(R.string.notes_note_title_hint),
            initial = open.note.title.orEmpty(),
            placeholder = stringResource(R.string.common_title),
            confirm = stringResource(R.string.common_save),
            allowBlank = true,
            onDismiss = { sheet = null },
            onConfirm = { viewModel.renameNote(open.note.id, it); sheet = null },
        )
        is Sheet.MoveNote -> FolderPicker(
            title = stringResource(R.string.notes_move_note_to),
            folders = state.folders,
            current = open.note.folderId,
            exclude = null,
            onDismiss = { sheet = null },
            onPick = { viewModel.moveNote(open.note.id, it); sheet = null },
        )
        is Sheet.TagNote -> TagsPrompt(
            note = open.note,
            known = state.tags.map { it.first },
            onDismiss = { sheet = null },
            onSave = { viewModel.setTags(open.note.id, it); sheet = null },
        )
        is Sheet.DeleteNote -> ConfirmPrompt(
            title = stringResource(R.string.notes_delete_note),
            text = stringResource(
                if (open.note.driveFileId != null) R.string.notes_delete_note_drive else R.string.notes_delete_note_local
            ),
            confirm = stringResource(R.string.notes_delete_note_confirm),
            onDismiss = { sheet = null },
            onConfirm = { viewModel.deleteNote(open.note.id); sheet = null },
        )
    }
}

/**
 * La barra de una importación en marcha: qué archivo y en qué paso va, y
 * una barra negra que se llena. Sin animación: en e-ink un avance a saltos
 * se ve mejor que uno continuo.
 */
@Composable
private fun ImportProgressBar(progress: ImportProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (progress.count > 1) {
                    stringResource(R.string.notes_importing_n_of_m, progress.index, progress.count, progress.fileName)
                } else {
                    stringResource(R.string.notes_importing, progress.fileName)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(progress.overall * 100).toInt()} %",
                style = MaterialTheme.typography.labelLarge,
                color = Eink.Black,
            )
        }
        Text(
            text = progress.label,
            style = MaterialTheme.typography.bodySmall,
            color = Eink.Graphite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(HairlineWidth, Eink.Black, RoundedCornerShape(5.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.overall.coerceIn(0.02f, 1f))
                    .background(Eink.Black),
            )
        }
    }
}

@Composable
private fun driveLine(state: NotesManagerState): String = when {
    state.syncing -> stringResource(R.string.notes_syncing_drive)
    !state.driveConfigured -> stringResource(R.string.notes_count_no_drive, state.totalNotes)
    state.driveError != null -> stringResource(R.string.notes_drive_error, state.driveError)
    state.driveSyncAt > 0 -> stringResource(R.string.notes_count_drive, state.totalNotes, relativeTime(state.driveSyncAt))
    else -> stringResource(R.string.notes_count_drive_pending, state.totalNotes)
}

// --- Árbol --------------------------------------------------------------------

@Composable
private fun FolderPane(
    state: NotesManagerState,
    onSelect: (FolderScope) -> Unit,
    onToggle: (Long) -> Unit,
    onTag: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        TreeRow(
            glyph = Glyph.Bullets,
            label = stringResource(R.string.notes_all),
            count = state.totalNotes,
            depth = 0,
            selected = state.scope == FolderScope.All,
            expandable = false,
            expanded = false,
            onClick = { onSelect(FolderScope.All) },
            onToggle = {},
        )
        TreeRow(
            glyph = Glyph.Folder,
            label = stringResource(R.string.notes_root),
            count = state.rootCount,
            depth = 0,
            selected = state.scope == FolderScope.Root,
            expandable = false,
            expanded = false,
            onClick = { onSelect(FolderScope.Root) },
            onToggle = {},
        )
        state.tree.forEach { node ->
            TreeRow(
                glyph = Glyph.Folder,
                label = node.folder.name,
                count = node.noteCount,
                depth = node.depth + 1,
                selected = state.selectedFolderId == node.folder.id,
                expandable = node.hasChildren,
                expanded = node.folder.id in state.expanded,
                onClick = { onSelect(FolderScope.Folder(node.folder.id)) },
                onToggle = { onToggle(node.folder.id) },
            )
        }

        if (state.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            EinkDivider()
            Text(
                text = stringResource(R.string.notes_tags),
                style = MaterialTheme.typography.labelSmall,
                color = Eink.Graphite,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp),
            )
            state.tags.forEach { (tag, count) ->
                TreeRow(
                    glyph = Glyph.Tag,
                    label = tag,
                    count = count,
                    depth = 0,
                    selected = state.tag.equals(tag, ignoreCase = true),
                    expandable = false,
                    expanded = false,
                    onClick = { onTag(tag) },
                    onToggle = {},
                )
            }
        }
    }
}

@Composable
private fun TreeRow(
    glyph: Glyph,
    label: String,
    count: Int,
    depth: Int,
    selected: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(ControlCorner)
            .background(if (selected) Eink.Black else Eink.White)
            .einkClickable(onClick = onClick)
            .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .then(if (expandable) Modifier.einkClickable(onClick = onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (expandable) {
                EinkGlyph(
                    glyph = if (expanded) Glyph.CaretDown else Glyph.CaretRight,
                    size = 14.dp,
                    tint = if (selected) Eink.White else Eink.Graphite,
                )
            }
        }
        EinkGlyph(glyph, size = 18.dp, tint = if (selected) Eink.White else Eink.Black)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Eink.White else Eink.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) Eink.White else Eink.Graphite,
            )
        }
    }
}

// --- Lista de notas -------------------------------------------------------------

@Composable
private fun NotesPane(
    state: NotesManagerState,
    onQuery: (String) -> Unit,
    onOpen: (InkNoteEntity) -> Unit,
    onSheet: (Sheet) -> Unit,
    onNewNote: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val title = when (val scope = state.scope) {
                    FolderScope.All -> stringResource(R.string.notes_all)
                    FolderScope.Root -> stringResource(R.string.notes_root)
                    is FolderScope.Folder -> state.folders.firstOrNull { it.id == scope.id }?.name ?: stringResource(R.string.notes_folder)
                }
                Text(text = title, style = MaterialTheme.typography.titleLarge, color = Eink.Black)
                val path = state.selectedPath
                if (path.size > 1) {
                    Text(
                        text = path.joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = Eink.Graphite,
                    )
                }
                state.tag?.let {
                    Text(
                        text = stringResource(R.string.notes_tag_filter, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = Eink.Graphite,
                    )
                }
            }
            state.selectedFolder?.let { folder ->
                if (folder.id in state.lockedFolders) {
                    // Con notas importadas dentro, la carpeta es de Drive: aquí no se toca.
                    EinkHint(stringResource(R.string.notes_drive_folder), Modifier.padding(end = 8.dp))
                } else {
                    EinkIconButton(Glyph.Title, { onSheet(Sheet.RenameFolder(folder)) }, contentDescription = stringResource(R.string.notes_rename_folder), box = 44.dp)
                    EinkIconButton(Glyph.Folder, { onSheet(Sheet.MoveFolder(folder)) }, contentDescription = stringResource(R.string.notes_move_folder), box = 44.dp)
                    EinkIconButton(Glyph.Trash, { onSheet(Sheet.DeleteFolder(folder)) }, contentDescription = stringResource(R.string.notes_delete_folder_confirm), accent = Accent.Today, box = 44.dp)
                }
            }
        }
        EinkTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.notes_search_placeholder),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        EinkDivider(Modifier.padding(top = 6.dp), color = Eink.Border)

        if (state.notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(if (state.query.isNotBlank() || state.tag != null) R.string.notes_no_match else R.string.notes_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Eink.Graphite,
                    )
                    Spacer(Modifier.height(10.dp))
                    EinkButton(stringResource(R.string.notes_new_note), onNewNote)
                    if (!state.driveConfigured) {
                        Spacer(Modifier.height(6.dp))
                        EinkButton(stringResource(R.string.notes_enable_drive_settings), onOpenSettings)
                    }
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.notes, key = { it.id }) { note ->
                NoteRow(
                    note = note,
                    folderPath = if (state.scope == FolderScope.All) state.pathOf(note.folderId) else "",
                    onOpen = { onOpen(note) },
                    onRename = { onSheet(Sheet.RenameNote(note)) },
                    onMove = { onSheet(Sheet.MoveNote(note)) },
                    onTags = { onSheet(Sheet.TagNote(note)) },
                    onDelete = { onSheet(Sheet.DeleteNote(note)) },
                )
                EinkDivider(color = Eink.Border)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteRow(
    note: InkNoteEntity,
    folderPath: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTags: () -> Unit,
    onDelete: () -> Unit,
) {
    // Decodificar el JSON de cada nota en cada recomposición sería caro:
    // se recuerda por nota y versión.
    val preview = remember(note.id, note.updatedAt) { StrokeCodec.decodeNotebook(note.strokesJson).page(0) }
    val zone = ZoneId.systemDefault()
    val stamp = rememberDateFormat(R.string.pattern_stamp)
    val dayLabel = rememberDateFormat(R.string.pattern_date_short_year)
    val noteOf = stringResource(R.string.notes_note_of)
    val readOnlyLabel = stringResource(R.string.notes_drive_read_only)

    Row(
        Modifier
            .fillMaxWidth()
            .einkClickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 120.dp, height = 88.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(HairlineWidth, Eink.Border, RoundedCornerShape(6.dp))
                .padding(4.dp),
        ) {
            if (!preview.isEmpty) InkPreview(document = preview, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                text = note.title?.takeIf { it.isNotBlank() } ?: defaultTitle(note, zone, noteOf, stamp, dayLabel),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(Instant.ofEpochMilli(note.updatedAt).atZone(zone).format(stamp).replace(".", ""))
                    if (folderPath.isNotEmpty()) append(" · ").append(folderPath)
                    if (note.isImported) append(" · ").append(readOnlyLabel)
                    else if (note.driveFileId != null) append(" · Drive")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Eink.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            note.recognizedFlat?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = Eink.Graphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val tags = note.tagList
            if (tags.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { TagChip(it) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (note.isImported) {
                // Importada: se abre para leer y se etiqueta; lo demás es de la app que la creó.
                Row {
                    EinkIconButton(Glyph.Note, onOpen, contentDescription = stringResource(R.string.notes_open), accent = Accent.Note, box = 40.dp, size = 20.dp)
                    EinkIconButton(Glyph.Tag, onTags, contentDescription = stringResource(R.string.notes_tags_action), box = 40.dp, size = 20.dp)
                }
            } else {
                Row {
                    EinkIconButton(Glyph.Edit, onOpen, contentDescription = stringResource(R.string.notes_open), accent = Accent.Note, box = 40.dp, size = 20.dp)
                    EinkIconButton(Glyph.Title, onRename, contentDescription = stringResource(R.string.common_title), box = 40.dp, size = 20.dp)
                }
                Row {
                    EinkIconButton(Glyph.Folder, onMove, contentDescription = stringResource(R.string.notes_move), box = 40.dp, size = 20.dp)
                    EinkIconButton(Glyph.Tag, onTags, contentDescription = stringResource(R.string.notes_tags_action), box = 40.dp, size = 20.dp)
                    EinkIconButton(Glyph.Trash, onDelete, contentDescription = stringResource(R.string.common_delete), accent = Accent.Today, box = 40.dp, size = 20.dp)
                }
            }
        }
    }
}

/** [noteOf] is "Note of %s"; the date comes from the anchored day, or the creation time when there is none. */
private fun defaultTitle(
    note: InkNoteEntity,
    zone: ZoneId,
    noteOf: String,
    stamp: DateTimeFormatter,
    dayLabel: DateTimeFormatter,
): String {
    val day = note.anchorDayMillis?.let { LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY)) }
    return if (day != null) {
        noteOf.format(day.format(dayLabel).replace(".", ""))
    } else {
        noteOf.format(Instant.ofEpochMilli(note.createdAt).atZone(zone).format(stamp).replace(".", ""))
    }
}

@Composable
private fun TagChip(label: String, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(HairlineWidth, Eink.Border, CircleShape)
            .then(if (onRemove != null) Modifier.einkClickable(onClick = onRemove) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkGlyph(Glyph.Tag, size = 12.dp, tint = Eink.Graphite)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Eink.Black,
            modifier = Modifier.padding(start = 5.dp),
        )
        if (onRemove != null) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelLarge,
                color = Eink.Graphite,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

// --- Diálogos -----------------------------------------------------------------

@Composable
private fun TextPrompt(
    title: String,
    hint: String?,
    initial: String,
    placeholder: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    allowBlank: Boolean = false,
) {
    var value by remember { mutableStateOf(initial) }
    EinkDialog(onDismiss = onDismiss, title = title, modifier = Modifier.width(420.dp)) {
        EinkTextField(value = value, onValueChange = { value = it }, placeholder = placeholder)
        if (hint != null) EinkHint(hint, Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(confirm, { onConfirm(value) }, enabled = allowBlank || value.isNotBlank(), emphasized = true)
        }
    }
}

@Composable
private fun ConfirmPrompt(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    EinkDialog(onDismiss = onDismiss, title = title, modifier = Modifier.width(420.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Eink.Black)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(confirm, onConfirm, emphasized = true)
        }
    }
}

/** Elegir una carpeta del árbol (o la raíz). `exclude` deja fuera una carpeta y sus hijas. */
@Composable
private fun FolderPicker(
    title: String,
    folders: List<NoteFolderEntity>,
    current: Long?,
    exclude: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    val childrenOf = remember(folders) { folders.groupBy { it.parentId } }
    val rows = remember(folders, exclude) {
        val out = mutableListOf<Pair<NoteFolderEntity, Int>>()
        fun visit(parentId: Long?, depth: Int) {
            childrenOf[parentId].orEmpty().sortedBy { it.name.lowercase() }.forEach { folder ->
                if (folder.id == exclude) return@forEach
                out += folder to depth
                visit(folder.id, depth + 1)
            }
        }
        visit(null, 0)
        out
    }
    EinkDialog(onDismiss = onDismiss, title = title, modifier = Modifier.width(460.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PickerRow(stringResource(R.string.notes_root), 0, selected = current == null) { onPick(null) }
            rows.forEach { (folder, depth) ->
                PickerRow(folder.name, depth + 1, selected = current == folder.id) { onPick(folder.id) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Spacer(Modifier.weight(1f))
                EinkButton(stringResource(R.string.common_cancel), onDismiss)
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, depth: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .einkClickable(onClick = onClick)
            .padding(start = (6 + depth * 18).dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkGlyph(Glyph.Folder, size = 18.dp, tint = Eink.Black)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        if (selected) EinkGlyph(Glyph.Check, size = 18.dp, tint = Eink.Black)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsPrompt(
    note: InkNoteEntity,
    known: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var tags by remember { mutableStateOf(note.tagList) }
    var draft by remember { mutableStateOf("") }
    // «importado» va fija en las notas de otra app: se ve, pero no se quita.
    fun fixed(tag: String) = note.isImported && tag.equals(InkNoteEntity.TAG_IMPORTED, ignoreCase = true)
    fun add(tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty() || tags.any { it.equals(clean, ignoreCase = true) }) return
        tags = tags + clean
    }
    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.notes_tags_action), modifier = Modifier.width(460.dp)) {
        if (tags.isEmpty()) {
            EinkHint(stringResource(R.string.notes_no_tags_hint))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    if (fixed(tag)) TagChip(tag) else TagChip(tag) { tags = tags - tag }
                }
            }
            if (note.isImported) {
                EinkHint(stringResource(R.string.notes_imported_tag_fixed), Modifier.padding(top = 6.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = stringResource(R.string.notes_new_tag),
                modifier = Modifier.weight(1f),
            )
            EinkButton(stringResource(R.string.common_add), { add(draft); draft = "" }, enabled = draft.isNotBlank())
        }
        val suggestions = known.filter { k -> tags.none { it.equals(k, ignoreCase = true) } }
        if (suggestions.isNotEmpty()) {
            EinkHint(stringResource(R.string.notes_tags_in_use), Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { tag ->
                    Box(Modifier.einkClickable { add(tag) }) { TagChip(tag) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            EinkButton(stringResource(R.string.common_cancel), onDismiss)
            EinkButton(stringResource(R.string.common_save), { onSave(tags) }, emphasized = true)
        }
    }
}
