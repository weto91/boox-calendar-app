package com.weto.booxcal.ui.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.weto.booxcal.ink.PenTool
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkDivider
import com.weto.booxcal.ui.theme.EinkGlyph
import com.weto.booxcal.ui.theme.EinkHint
import com.weto.booxcal.ui.theme.EinkIconButton
import com.weto.booxcal.ui.theme.EinkWindowDensity
import com.weto.booxcal.ui.theme.Glyph
import com.weto.booxcal.ui.theme.einkClickable
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.weto.booxcal.R
import androidx.annotation.StringRes
import com.weto.booxcal.util.rememberLocale

/**
 * El panel de la pluma, calcado del de Boox Notes pero como ventana: una
 * tarjeta de tamaño razonable encima de lo que se estuviera viendo, que se
 * cierra tocando fuera. Va por secciones separadas por una raya: la punta y
 * su grosor con la bombilla de «a fábrica»; las tres puntas con un punto
 * bajo la elegida; lo propio de cada punta (la textura del lápiz, la
 * sensibilidad del boli); el ancho de línea con sus flechas y el cono; y
 * los colores, con el nombre y el código del elegido.
 */
@Composable
internal fun PenOptionsPanel(
    tools: InkTools,
    onTools: (InkTools) -> Unit,
    onDismiss: () -> Unit,
    /** Nota al pie: con qué se está pintando (el SDK y sus pinceles, o la app). */
    footnote: String? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        // Un diálogo es otra ventana y trae su propia densidad.
        EinkWindowDensity {
            Column(
                Modifier
                    .width(PANEL_WIDTH)
                    .clip(PanelCorner)
                    .background(Eink.White)
                    .border(2.dp, Eink.Black, PanelCorner)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                PanelSections(tools, onTools)
                footnote?.let { EinkHint(it, Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
private fun PanelSections(tools: InkTools, onTools: (InkTools) -> Unit) {
    val tip = tools.lastDrawing

    // Título: la punta y su grosor, con la bombilla de volver a lo de fábrica.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${stringResource(tipName(tip))} ${formatMm(tools.widthMm)}",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
        Spacer(Modifier.width(10.dp))
        EinkIconButton(
            glyph = Glyph.Bulb,
            onClick = { onTools(tools.resetActive()) },
            contentDescription = stringResource(R.string.pen_reset),
            box = 36.dp,
            size = 20.dp,
        )
    }

    // Las puntas, con el punto bajo la elegida.
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        INK_TIPS.forEach { (candidate, glyph, name) ->
            Marked(selected = tip == candidate, onClick = { onTools(tools.withTool(candidate)) }, label = stringResource(name)) {
                EinkGlyph(glyph, size = 36.dp)
            }
        }
    }
    SectionRule()

    when (tip) {
        PenTool.PENCIL -> {
            SectionTitle(stringResource(R.string.pen_texture))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(0, 1).forEach { texture ->
                    Marked(
                        selected = tools.pencilTexture == texture,
                        onClick = { onTools(tools.copy(pencilTexture = texture)) },
                        label = stringResource(R.string.pen_texture_n, texture + 1),
                    ) {
                        TextureSwatch(texture)
                    }
                }
            }
            SectionRule()
        }

        else -> Unit
    }

    WidthSection(tools, onTools)
    SectionRule()

    // Solo la pluma del SDK (el boli) tiene sensibilidad; el carboncillo
    // del lápiz responde a la presión a su manera y no se gradúa.
    if (tip == PenTool.BALLPOINT) {
        PressureSection(tools, onTools)
        SectionRule()
    }

    ColorsSection(tools, onTools)
}

// --- Ancho de línea -----------------------------------------------------------

@Composable
private fun WidthSection(tools: InkTools, onTools: (InkTools) -> Unit) {
    val range = tools.widthRange
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.pen_line_width), Modifier.weight(1f))
        StepButton(Glyph.ChevronLeft, stringResource(R.string.pen_thinner)) { onTools(tools.withWidthMm(tools.widthMm - InkTools.STEP_MM)) }
        Text(
            text = formatMm(tools.widthMm),
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(84.dp),
        )
        StepButton(Glyph.ChevronRight, stringResource(R.string.pen_thicker)) { onTools(tools.withWidthMm(tools.widthMm + InkTools.STEP_MM)) }
    }
    val fraction = ((tools.widthMm - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    WidthCone(
        fraction = fraction,
        onFraction = { f -> onTools(tools.withWidthMm(range.start + f * (range.endInclusive - range.start))) },
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp),
    )
}

/** Cono de grosor: fino a la izquierda, grueso a la derecha, con la marca redonda del elegido. */
@Composable
private fun WidthCone(fraction: Float, onFraction: (Float) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> onFraction((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onFraction((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val h = size.height
        val w = size.width
        val knob = 10.dp.toPx()
        val cone = Path().apply {
            moveTo(knob, h / 2f - 1.dp.toPx())
            lineTo(w - knob / 2f, h / 2f - h * 0.32f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(w - knob, h / 2f - h * 0.32f),
                    Size(knob, h * 0.64f),
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(knob, h / 2f + 1.dp.toPx())
            close()
        }
        drawPath(cone, Eink.Black)
        val x = knob + (w - knob * 2f) * fraction
        drawCircle(Eink.White, radius = knob, center = Offset(x, h / 2f))
        drawCircle(Eink.Black, radius = knob, center = Offset(x, h / 2f), style = Stroke(width = 2.dp.toPx()))
    }
}

// --- Sensibilidad a la presión -------------------------------------------------

@Composable
private fun PressureSection(tools: InkTools, onTools: (InkTools) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.pen_pressure), Modifier.weight(1f))
        Text(
            text = "${tools.pressure} %",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
    }
    TickSlider(
        value = tools.pressure,
        onValue = { onTools(tools.withPressure(it)) },
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp),
    )
}

/** Regla de 0 a 100 con una marca cada diez, y el pomo redondo en el valor. */
@Composable
private fun TickSlider(value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    fun pick(x: Float, width: Float, knob: Float): Int {
        val f = ((x - knob) / (width - knob * 2f)).coerceIn(0f, 1f)
        return (f * 10f).roundToInt() * 10
    }
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> onValue(pick(offset.x, size.width.toFloat(), 10.dp.toPx())) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onValue(pick(change.position.x, size.width.toFloat(), 10.dp.toPx()))
                }
            },
    ) {
        val h = size.height
        val w = size.width
        val knob = 10.dp.toPx()
        val line = 2.dp.toPx()
        val left = knob
        val right = w - knob
        drawLine(Eink.Black, Offset(left, h / 2f), Offset(right, h / 2f), strokeWidth = line)
        for (i in 0..10) {
            val x = left + (right - left) * i / 10f
            val tall = if (i == 0 || i == 10) 9.dp.toPx() else 5.dp.toPx()
            drawLine(Eink.Black, Offset(x, h / 2f - tall), Offset(x, h / 2f + tall), strokeWidth = line)
        }
        val x = left + (right - left) * (value.coerceIn(0, 100) / 100f)
        drawCircle(Eink.White, radius = knob, center = Offset(x, h / 2f))
        drawCircle(Eink.Black, radius = knob, center = Offset(x, h / 2f), style = Stroke(width = 2.dp.toPx()))
    }
}

// --- Colores --------------------------------------------------------------------

@Composable
private fun ColorsSection(tools: InkTools, onTools: (InkTools) -> Unit) {
    val current = INK_COLORS.firstOrNull { it.argb == tools.activeColorArgb }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.pen_colors), Modifier.weight(1f))
        Text(
            text = "${current?.let { stringResource(it.name) } ?: stringResource(R.string.pen_color)} (#${"%08X".format(tools.activeColorArgb)})",
            style = MaterialTheme.typography.titleMedium,
            color = Eink.Black,
        )
    }
    INK_COLORS.chunked(8).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            row.forEach { color ->
                Marked(
                    selected = tools.activeColorArgb == color.argb,
                    onClick = { onTools(tools.withColor(color.argb)) },
                    label = stringResource(color.name),
                ) {
                    ColorDot(color)
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: InkColor) {
    val fill = Color(color.argb)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, Eink.Black, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        color.label?.let { label ->
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (color.argb == Eink.White.toArgb()) Eink.Black else Eink.White,
            )
        }
    }
}

// --- Piezas ---------------------------------------------------------------------

/** Algo elegible con el punto de «elegido» debajo, como en el panel de Boox. */
@Composable
private fun Marked(selected: Boolean, onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .einkClickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (selected) Eink.Black else Color.Transparent),
        )
    }
}

/** Muestra de la textura del lápiz: un cuadrado de grano, más o menos denso. */
@Composable
private fun TextureSwatch(texture: Int) {
    Canvas(Modifier.size(38.dp)) {
        val random = Random(1234L + texture)
        drawRect(Eink.White)
        val cell = 2.dp.toPx()
        val cols = (size.width / cell).toInt()
        val rows = (size.height / cell).toInt()
        val darkShare = if (texture == 0) 0.42f else 0.7f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (random.nextFloat() < darkShare) {
                    val shade = 0.35f + random.nextFloat() * 0.65f
                    drawRect(
                        color = Color(0f, 0f, 0f, shade),
                        topLeft = Offset(c * cell, r * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
        drawRect(Eink.Black, style = Stroke(width = 1.5f.dp.toPx()))
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Eink.Black,
        modifier = modifier,
    )
}

@Composable
private fun SectionRule() {
    EinkDivider(Modifier.padding(vertical = 10.dp), color = Eink.Black)
}

/** Botón cuadrado con borde y una flecha dentro: un paso de grosor. */
@Composable
private fun StepButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, Eink.Black, RoundedCornerShape(6.dp))
            .einkClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EinkGlyph(glyph, size = 18.dp, contentDescription = description)
    }
}

@StringRes
private fun tipName(tool: PenTool): Int = INK_TIPS.firstOrNull { it.first == tool }?.third ?: R.string.pen_pen

/** "0.15mm", with the decimal separator of the app language. */
@Composable
internal fun formatMm(mm: Float): String = String.format(rememberLocale(), "%.2fmm", mm)

private val PanelCorner = RoundedCornerShape(14.dp)

/** Ancho de la ventana: como el diálogo de antes, no la pantalla entera. */
private val PANEL_WIDTH: Dp = 440.dp
