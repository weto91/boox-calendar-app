package com.weto.booxcal.ui.notes

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weto.booxcal.R
import com.weto.booxcal.ink.NoteTemplates
import com.weto.booxcal.ink.TemplateFile
import com.weto.booxcal.ink.TemplateRef
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkDialog
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a new note starts from: a blank sheet or a page of one of the PDFs
 * in the templates folder. One tile per page, with its rendering.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    /** Null: the blank sheet. */
    onPick: (TemplateRef?) -> Unit,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(NoteTemplates.hasAccess(context)) }
    var templates by remember { mutableStateOf<List<TemplateFile>?>(null) }
    LaunchedEffect(access) {
        templates = if (access) withContext(Dispatchers.IO) { NoteTemplates.list() } else emptyList()
    }
    // Coming back from the system screen that grants the access.
    val askReadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = NoteTemplates.hasAccess(context)
    }
    val backFromSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = NoteTemplates.hasAccess(context)
    }

    EinkDialog(onDismiss = onDismiss, title = stringResource(R.string.notes_new_note), modifier = Modifier.width(560.dp)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TemplateTile(label = stringResource(R.string.template_blank), onClick = { onPick(null) }) {
                    Box(Modifier.fillMaxSize().background(Eink.White))
                }
                templates.orEmpty().forEach { template ->
                    repeat(template.pageCount) { page ->
                        val ref = TemplateRef(template.file.path, page)
                        TemplateTile(
                            label = if (template.pageCount > 1) {
                                stringResource(R.string.template_page, template.name, page + 1)
                            } else {
                                template.name
                            },
                            onClick = { onPick(ref) },
                        ) {
                            TemplateThumbnail(ref)
                        }
                    }
                }
            }

            when {
                !access -> {
                    Spacer(Modifier.padding(top = 12.dp))
                    EinkHint(stringResource(R.string.template_access_hint, NoteTemplates.FOLDER_NAME))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        EinkButton(stringResource(R.string.template_allow_access), {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { backFromSettings.launch(intent) }
                                    .onFailure { runCatching { backFromSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                            } else {
                                askReadPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        })
                    }
                }

                templates?.isEmpty() == true -> {
                    Spacer(Modifier.padding(top = 12.dp))
                    EinkHint(stringResource(R.string.template_empty_hint, NoteTemplates.FOLDER_NAME))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                EinkButton(stringResource(R.string.common_cancel), onDismiss)
            }
        }
    }
}

@Composable
private fun TemplateTile(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .einkClickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = TILE_WIDTH - 8.dp, height = TILE_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .border(HairlineWidth, Eink.Black, RoundedCornerShape(6.dp))
                .background(Eink.White),
        ) {
            content()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Eink.Black,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The page, rendered once at tile size off the main thread. */
@Composable
private fun TemplateThumbnail(ref: TemplateRef) {
    val widthPx = with(LocalDensity.current) { TILE_WIDTH.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, ref) {
        value = withContext(Dispatchers.IO) { NoteTemplates.thumbnail(ref, widthPx) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val TILE_WIDTH = 120.dp
private val TILE_HEIGHT = 160.dp
