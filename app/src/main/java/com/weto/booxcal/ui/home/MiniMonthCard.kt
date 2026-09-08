package com.weto.booxcal.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weto.booxcal.domain.model.DayBucket
import com.weto.booxcal.ui.theme.Eink
import com.weto.booxcal.ui.theme.EinkPalette
import com.weto.booxcal.ui.theme.einkClickable
import com.weto.booxcal.ui.theme.weekdayLabels
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weekdayName: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val fullDate: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

private const val MAX_DOTS = 4

/**
 * Tarjeta del mes: el día elegido en grande y debajo la rejilla compacta.
 *
 * A diferencia de la vista de mes a pantalla completa, aquí cada día solo lleva
 * puntos. En una rejilla de este tamaño el título de un evento no cabría, y un
 * punto por entrada dice lo único que hace falta saber de un vistazo: si ese día
 * tiene algo.
 */
@Composable
fun MiniMonthCard(
    state: HomeUiState,
    onSelectDay: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val anchorMonth = YearMonth.from(state.anchor)

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(state.selected.dayOfMonth),
                style = MaterialTheme.typography.displayLarge,
                color = Eink.Black,
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = state.selected.format(weekdayName)
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.titleMedium,
                    color = Eink.Black,
                )
                Text(
                    text = state.selected.format(fullDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Eink.Graphite,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            weekdayLabels(state.weekStart).forEach { label ->
                Text(
                    text = label,
                    // Un punto más grandes y en negrita: a este tamaño en e-ink
                    // el gris fino casi no se veía.
                    style = MaterialTheme.typography.labelLarge,
                    color = Eink.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Las semanas se reparten el alto que quede. Con celdas de altura fija
        // la rejilla flotaba en medio de la tarjeta y dejaba un hueco muerto
        // debajo, que es lo que se veía en el dispositivo.
        Column(Modifier.fillMaxWidth().weight(1f)) {
            repeat(state.gridWeeks) { week ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    repeat(7) { dayOfWeek ->
                        val date = state.gridStart.plusDays((week * 7 + dayOfWeek).toLong())
                        MiniDayCell(
                            bucket = state.bucket(date),
                            inAnchorMonth = YearMonth.from(date) == anchorMonth,
                            isToday = date == today,
                            isSelected = date == state.selected,
                            onClick = {
                                if (date == state.selected) onOpenDay(date) else onSelectDay(date)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MiniDayCell(
    bucket: DayBucket,
    inAnchorMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .einkClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(27.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    when {
                        isSelected -> Modifier.background(Eink.Black)
                        // Hoy sin estar elegido: contorno, no relleno. Dos
                        // rellenos negros en la misma rejilla se confunden.
                        isToday -> Modifier.border(1.5.dp, Eink.Black, RoundedCornerShape(8.dp))
                        else -> Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = bucket.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isSelected -> Eink.White
                    inAnchorMonth -> Eink.Black
                    else -> Eink.Slate
                },
            )
        }

        DayDots(bucket, dimmed = !inAnchorMonth)
    }
}

/** Qué hay ese día, en la forma que lo distingue: ○ evento, □ recordatorio, + nota. */
private enum class MarkShape { CIRCLE, SQUARE, PLUS }

private class DayMark(val shape: MarkShape, val color: Color)

/**
 * Hasta cuatro marcas bajo el número: un círculo por evento, un cuadrado por
 * recordatorio y una cruz si el día tiene notas. El color viene del evento (si
 * en Google tiene uno propio), del calendario o de la lista; la forma es lo
 * que se lee en blanco y negro.
 */
@Composable
private fun DayDots(bucket: DayBucket, dimmed: Boolean) {
    // Una marca por entrada, con su color; si varias comparten color, la marca
    // se repite igualmente: dos círculos rojos son dos citas del calendario rojo.
    // La cruz de las notas va siempre, aunque haya que quitar una entrada.
    val entries = buildList {
        bucket.banners.forEach { add(DayMark(MarkShape.CIRCLE, EinkPalette.forArgb(it.accentArgb))) }
        bucket.timed.forEach { add(DayMark(MarkShape.CIRCLE, EinkPalette.forArgb(it.accentArgb))) }
        bucket.tasks.forEach { add(DayMark(MarkShape.SQUARE, EinkPalette.forArgb(it.listColorArgb))) }
    }
    val marks = if (bucket.hasInkNote) {
        entries.take(MAX_DOTS - 1) + DayMark(MarkShape.PLUS, Eink.Black)
    } else {
        entries.take(MAX_DOTS)
    }

    // Las marcas van en color también fuera del mes: en Kaleido la capa de
    // color va a un tercio de resolución, y un punto de 4 dp atenuado a gris
    // era un punto negro sin más. Con 7 dp ya se ve de qué calendario es.
    Box(Modifier.height(11.dp), contentAlignment = Alignment.Center) {
        if (marks.isEmpty()) return@Box
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (dimmed) Modifier.alpha(0.55f) else Modifier,
        ) {
            marks.forEach { mark -> DayMarkGlyph(mark) }
        }
    }
}

@Composable
private fun DayMarkGlyph(mark: DayMark) {
    Canvas(Modifier.size(MARK_SIZE)) {
        when (mark.shape) {
            MarkShape.CIRCLE -> drawCircle(mark.color)
            MarkShape.SQUARE -> drawRect(mark.color)
            MarkShape.PLUS -> {
                // Una cruz de trazo grueso: fina, en e-ink se perdía entre los
                // puntos de al lado.
                val stroke = MARK_PLUS_STROKE.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawLine(
                    mark.color,
                    Offset(cx, 0f),
                    Offset(cx, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Butt,
                )
                drawLine(
                    mark.color,
                    Offset(0f, cy),
                    Offset(size.width, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Butt,
                )
            }
        }
    }
}

private val MARK_SIZE = 7.dp
private val MARK_PLUS_STROKE = 2.dp
