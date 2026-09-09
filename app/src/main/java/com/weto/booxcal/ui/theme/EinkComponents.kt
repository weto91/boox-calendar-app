package com.weto.booxcal.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R

/**
 * Pulsación sin ninguna realimentación visual animada. Todos los controles de
 * la app pasan por aquí en vez de por los de Material, que traen onda de
 * pulsación y transiciones de color.
 */
fun Modifier.einkClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = rememberNoIndicationSource(),
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Tarjeta del panel principal: fondo blanco como el de la página, y un borde de
 * 1dp que es lo único que la delimita.
 */
@Composable
fun EinkCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(CardCorner)
            .background(Eink.White)
            .border(HairlineWidth, Eink.Border, CardCorner)
            .padding(contentPadding),
        content = content,
    )
}

/** Cabecera de tarjeta: título a la izquierda, acción opcional a la derecha. */
@Composable
fun EinkCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Eink.Black,
            )
            trailing?.invoke()
        }
        EinkDivider(color = Eink.Border)
    }
}

/**
 * Línea de 1dp. Horizontal por defecto.
 *
 * Ocupa todo el ancho (o el alto, si es vertical) por sí sola: sin eso, en una
 * columna quedaba con cero de ancho y no se pintaba ningún separador.
 */
@Composable
fun EinkDivider(
    modifier: Modifier = Modifier,
    color: Color = Eink.Hairline,
    vertical: Boolean = false,
) {
    Box(
        modifier
            .then(
                if (vertical) Modifier.width(HairlineWidth).fillMaxHeight()
                else Modifier.fillMaxWidth().height(HairlineWidth)
            )
            .background(color)
    )
}

/**
 * Botón rectangular de esquinas suaves. `selected` invierte el relleno: en un
 * panel donde el color puede fallar, la inversión es la única señal de estado
 * que se lee sin dudar.
 */
@Composable
fun EinkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    minWidth: Dp = 0.dp,
    shape: Shape = ControlCorner,
) {
    val background = if (selected) Eink.Black else Eink.White
    val foreground = when {
        !enabled -> Eink.Slate
        selected -> Eink.White
        else -> Eink.Black
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = MinTouchTarget)
            .clip(shape)
            .background(background)
            .border(
                width = if (emphasized) 2.dp else HairlineWidth,
                color = if (enabled) Eink.Black else Eink.Border,
                shape = shape,
            )
            .einkClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Botón de solo icono, sin marco: barra superior y acciones de tarjeta.
 *
 * `accent` tiñe el pictograma. El color es solo una ayuda para localizar el
 * botón de un vistazo: la forma del icono ya distingue la acción, así que si el
 * panel apaga el tono no se pierde nada.
 */
@Composable
fun EinkIconButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 22.dp,
    accent: Color? = null,
    /** Lado del área táctil. Se encoge solo en filas muy pobladas. */
    box: Dp = MinTouchTarget,
) {
    Box(
        modifier
            .size(box)
            .clip(ControlCorner)
            .then(if (selected) Modifier.background(Eink.Black) else Modifier)
            .einkClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EinkGlyph(
            glyph = glyph,
            size = size,
            tint = when {
                !enabled -> Eink.Slate
                selected -> Eink.White
                else -> accent ?: Eink.Black
            },
            contentDescription = contentDescription,
        )
    }
}

/**
 * Baldosa de color: cuadrado relleno con el pictograma calado en blanco.
 *
 * Es la pieza que da color a la pantalla principal. Se cala en blanco y no se
 * dibuja el icono en color sobre blanco porque en Kaleido la capa de color va a
 * un tercio de resolución: un trazo fino teñido se emborrona, mientras que una
 * mancha grande de color con un hueco blanco dentro se lee siempre.
 */
@Composable
fun EinkTile(
    glyph: Glyph,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyphSize: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(TileCorner)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        EinkGlyph(
            glyph = glyph,
            size = glyphSize,
            tint = Eink.White,
            contentDescription = contentDescription,
        )
    }
}

/**
 * Baldosa pulsable, con el rótulo debajo o sin él.
 *
 * En una fila de pestañas el rótulo se quita: cuatro baldosas con texto debajo
 * se comían casi una cuarta parte del alto útil del módulo, y ahí el nombre de
 * la pestaña activa ya se dice al lado. Donde la baldosa está sola, el rótulo se
 * queda: un icono suelto obliga a recordar qué significa.
 */
@Composable
fun EinkTileButton(
    glyph: Glyph,
    accent: Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tileSize: Dp = 40.dp,
    showLabel: Boolean = true,
) {
    Column(
        modifier
            .clip(ControlCorner)
            .then(
                // La pestaña activa se marca con recuadro negro y no invirtiendo
                // el relleno: sobre una baldosa de color, invertir no se ve.
                if (selected) Modifier.border(2.dp, Eink.Black, ControlCorner) else Modifier
            )
            .einkClickable(onClick = onClick)
            .padding(horizontal = if (showLabel) 8.dp else 5.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EinkTile(glyph, accent, size = tileSize, contentDescription = label)
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Eink.Black else Eink.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Fila de menú: baldosa de color, rótulo y una línea de explicación. */
@Composable
fun EinkMenuRow(
    glyph: Glyph,
    accent: Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .einkClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkTile(glyph, accent, size = 40.dp, contentDescription = null)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Eink.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Eink.Graphite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        EinkGlyph(Glyph.ChevronRight, size = 18.dp, tint = Eink.Slate)
    }
}

/**
 * Pestañas anchas con icono en color, al estilo de las de "Evento" y
 * "Recordatorio" de la app nativa. La activa se rellena de negro.
 */
@Composable
fun EinkTabs(
    tabs: List<Triple<Glyph, Color, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .border(HairlineWidth, Eink.Border, ControlCorner),
    ) {
        tabs.forEachIndexed { index, (glyph, accent, label) ->
            val selected = index == selectedIndex
            if (index > 0) EinkDivider(Modifier.height(MinTouchTarget), Eink.Border, vertical = true)
            Row(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .background(if (selected) Eink.Black else Eink.White)
                    .einkClickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                EinkGlyph(
                    glyph = glyph,
                    size = 22.dp,
                    // Sobre el relleno negro el color desaparece: allí va blanco.
                    tint = if (selected) Eink.White else accent,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Eink.White else Eink.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Fila de formulario: baldosa de color, rótulo y el valor a la derecha. Toda la
 * fila es pulsable, así que no hay que acertarle al texto pequeño.
 */
@Composable
fun EinkFieldRow(
    glyph: Glyph,
    accent: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlCorner)
            .then(if (onClick != null) Modifier.einkClickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = MinTouchTarget)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkTile(glyph, accent, size = 34.dp, glyphSize = 20.dp, contentDescription = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Eink.Black,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = Eink.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/** Botón flotante de creación. Círculo negro, como en la app nativa. */
@Composable
fun EinkFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph = Glyph.Plus,
    contentDescription: String? = stringResource(R.string.fab_create),
) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Eink.Black)
            .einkClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EinkGlyph(glyph, size = 24.dp, tint = Eink.White, contentDescription = contentDescription)
    }
}

/** Casilla cuadrada, dibujada a mano: la de Material anima el check. */
@Composable
fun EinkCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(MinTouchTarget)
            .einkClickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .border(2.dp, if (enabled) Eink.Black else Eink.Border, RoundedCornerShape(5.dp))
                .drawBehind {
                    if (!checked) return@drawBehind
                    val stroke = 2.5.dp.toPx()
                    val w = this.size.width
                    val h = this.size.height
                    // Marca trazada, no relleno: se distingue mejor de una
                    // casilla deshabilitada, que sí sería un bloque uniforme.
                    drawLine(
                        color = Eink.Black,
                        start = Offset(w * 0.22f, h * 0.52f),
                        end = Offset(w * 0.43f, h * 0.74f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Eink.Black,
                        start = Offset(w * 0.43f, h * 0.74f),
                        end = Offset(w * 0.78f, h * 0.26f),
                        strokeWidth = stroke,
                    )
                }
        )
    }
}

/**
 * Casilla redonda de la lista de recordatorios: hueca cuando está pendiente,
 * rellena con la marca en blanco cuando está hecha.
 */
@Composable
fun EinkRadioCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(MinTouchTarget)
            .einkClickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .then(if (checked) Modifier.background(Eink.Graphite) else Modifier)
                .border(
                    width = if (checked) 0.dp else 2.dp,
                    color = if (checked) Color.Transparent else Eink.Slate,
                    shape = CircleShape,
                )
                .drawBehind {
                    if (!checked) return@drawBehind
                    val stroke = 2.dp.toPx()
                    val w = this.size.width
                    val h = this.size.height
                    drawLine(
                        color = Eink.White,
                        start = Offset(w * 0.26f, h * 0.52f),
                        end = Offset(w * 0.44f, h * 0.71f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Eink.White,
                        start = Offset(w * 0.44f, h * 0.71f),
                        end = Offset(w * 0.75f, h * 0.31f),
                        strokeWidth = stroke,
                    )
                }
        )
    }
}

/** Campo de texto enmarcado, sin animación de etiqueta flotante. */
@Composable
fun EinkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Eink.Graphite,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ControlCorner)
                .border(HairlineWidth, Eink.Border, ControlCorner)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = textStyle, color = Eink.Slate)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = textStyle.copy(color = Eink.Black),
                cursorBrush = SolidColor(Eink.Black),
                keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Cabecera de sección dentro de una lista. Sobre blanco y separada por una
 * línea: un relleno gris obligaría a refrescar una banda entera en cada scroll.
 */
@Composable
fun EinkSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = Eink.Graphite,
        )
        trailing?.invoke()
    }
}

/** Diálogo enmarcado, sin sombras. */
@Composable
fun EinkDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        // Un diálogo es otra ventana y trae su propia densidad: sin esto sale a
        // la mitad de tamaño que el resto de la app.
        EinkWindowDensity {
            Column(
                modifier
                    .clip(CardCorner)
                    .background(Eink.White)
                    .border(2.dp, Eink.Black, CardCorner)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Eink.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                EinkDivider(color = Eink.Border)
                Column(Modifier.padding(16.dp), content = content)
            }
        }
    }
}

/** Texto de apoyo, para estados vacíos y notas al pie. */
@Composable
fun EinkHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Eink.Graphite,
        modifier = modifier,
    )
}

/** "a moment ago", "12 min ago", "3 h ago": how long since [atMillis]. */
@Composable
fun relativeTime(atMillis: Long): String {
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes_ago, minutes)
        else -> stringResource(R.string.time_hours_ago, minutes / 60)
    }
}
