package com.weto.booxcal.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.weto.booxcal.ui.theme.Accent
import com.weto.booxcal.ui.theme.ControlCorner
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkButton
import com.weto.booxcal.ui.theme.EinkCard
import com.weto.booxcal.ui.theme.EinkCardHeader
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkTile
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.HairlineWidth
import com.weto.booxcal.ui.theme.einkClickable
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R

/**
 * Piezas de los editores de evento y recordatorio, con el mismo lenguaje que
 * la vista de detalle: barra con el tipo, tarjetas por sección y filas con
 * baldosa de color. Lo que se edita se reconoce por dónde está, no por leer
 * cada rótulo.
 */

/** Ancho máximo del formulario: a pantalla completa un campo de lado a lado es incómodo. */
internal val EDITOR_MAX_WIDTH = 820.dp

/**
 * Armazón del editor: barra superior con volver, baldosa del tipo, título,
 * borrar (si existe) y **Guardar**; debajo, las tarjetas centradas.
 */
@Composable
internal fun EditorScaffold(
    title: String,
    glyph: Glyph,
    accent: Color,
    onClose: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    loading: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Eink.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkIconButton(Glyph.ChevronLeft, onClose, contentDescription = stringResource(R.string.common_cancel))
            EinkTile(glyph, accent, size = 32.dp, glyphSize = 18.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            if (onDelete != null) {
                EinkIconButton(Glyph.Trash, onDelete, contentDescription = stringResource(R.string.common_delete), accent = Accent.Today)
            }
            EinkButton(label = stringResource(R.string.common_save), onClick = onSave, enabled = canSave, emphasized = true)
        }
        EinkDivider(color = Eink.Black)

        if (loading) {
            EinkHint(stringResource(R.string.common_loading), Modifier.padding(16.dp))
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = EDITOR_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (error != null) EditorNotice(error, strong = true)
                content()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Tarjeta de sección del editor: cabecera con el tema y los campos debajo. */
@Composable
internal fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    EinkCard(Modifier.fillMaxWidth()) {
        EinkCardHeader(title)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Fila de campo: baldosa, rótulo con su explicación, y a la derecha los
 * controles que lo cambian (botones de fecha, casilla…).
 */
@Composable
internal fun EditorRow(
    glyph: Glyph,
    accent: Color,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EinkTile(glyph, accent, size = 36.dp, glyphSize = 20.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Eink.Graphite,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = trailing,
        )
    }
}

/**
 * Aviso dentro del formulario: enmarcado, para que no se confunda con la
 * explicación gris de un campo. `strong` es para errores: marco negro grueso.
 */
@Composable
internal fun EditorNotice(text: String, strong: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Eink.Black,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .border(if (strong) 2.dp else HairlineWidth, if (strong) Eink.Black else Eink.Border, ControlCorner)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Opciones que caben en varias líneas: calendarios, listas, avisos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceFlow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/**
 * Opción con punto de color: un calendario o una lista. La elegida se
 * rellena de negro y conserva el punto, que es lo que la identifica.
 */
@Composable
internal fun ColorChoice(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(CircleShape)
            .background(if (selected) Eink.Black else Eink.White)
            .border(
                width = if (selected) 2.dp else HairlineWidth,
                color = if (selected) Eink.Black else Eink.Border,
                shape = CircleShape,
            )
            .einkClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(1.5.dp, Eink.White, CircleShape) else Modifier)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Eink.White else Eink.Black,
            maxLines = 1,
        )
    }
}
