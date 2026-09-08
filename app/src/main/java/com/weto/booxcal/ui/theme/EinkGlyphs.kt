package com.weto.booxcal.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Iconos dibujados a mano sobre un lienzo de 24×24 unidades.
 *
 * No se usa `material-icons` a propósito. Son formas triviales, así se controla
 * exactamente el grosor de trazo —que en e-ink es la diferencia entre legible y
 * emborronado— y no se depende de qué iconos trae cada versión de la BOM ni de
 * cuáles quedan marcados como obsoletos entre una y otra.
 */
enum class Glyph {
    ChevronLeft,
    ChevronRight,
    ChevronUp,
    ChevronDown,
    CaretDown,
    CaretRight,
    Search,
    SyncCloud,
    Mail,
    Menu,
    Plus,
    /** Una raya: quitar, reducir. */
    Minus,
    Gear,
    Today,
    /** Campana: recordatorio. */
    Bell,
    /** Reloj: hora de un evento. */
    Clock,
    /** Chincheta: lugar. */
    Pin,
    /** Lista con marcas: tareas. */
    ListCheck,
    /** Rejilla del mes. */
    GridMonth,
    /** Rejilla de la semana. */
    GridWeek,
    /** Vista de un solo día. */
    GridDay,
    /** Flecha entrando en una bandeja: importar / añadir cuenta. */
    Import,
    /** Flecha hacia arriba saliendo de la bandeja: subir, importar a la app. */
    Upload,
    /** Lápiz de madera, amarillo con goma roja (calcado del icono elegido). */
    Pencil,
    /** Bolígrafo verde con clip y pulsador (calcado del icono elegido). */
    Ballpoint,
    /** Subrayador rosa con punta de bisel y su raya debajo (calcado del icono elegido). */
    Marker,
    /** Editar: marco abierto con un lápiz que lo cruza (calcado del icono elegido). */
    Edit,
    /** Título: T y puntos suspensivos en un marco (calcado del icono elegido). */
    Title,
    /** Hoja nueva: página con la esquina doblada y un más. */
    NewSheet,
    /** Toda la nota a texto: un garabato que baja a una T. */
    ToText,
    /** Goma de borrar. */
    Eraser,
    /** Lazo de selección. */
    Lasso,
    /** Pluma: plumín estilográfico, macizo, hacia abajo (calcado del icono elegido). */
    Pen,
    /** Dos hojas superpuestas. */
    Copy,
    /** Tijeras. */
    Cut,
    /** Portapapeles con hoja. */
    Paste,
    /** Tres puntos con su renglón: lista. */
    Bullets,
    /** Deshacer. */
    Undo,
    /** Rehacer. */
    Redo,
    /** Paleta de colores. */
    Palette,
    /** Grosores: tres trazos de grueso creciente. */
    Thickness,
    /** Papelera. */
    Trash,
    /** Marca de verificación. */
    Check,
    /** Hoja con renglones: descripción o nota escrita. */
    Note,
    /** Pestaña: nota manuscrita del día. */
    TabMemo,
    /** Pestaña: recordatorios. */
    TabReminder,
    /** Pestaña: agenda próxima. */
    TabAgenda,
    /** Pestaña: cuaderno de notas. */
    TabNotebook,
    /** Carpeta con su pestaña: el gestor de notas. */
    Folder,
    /** Bombilla con sus rayos: volver a lo de fábrica. */
    Bulb,
    /** Etiqueta colgante con su ojal. */
    Tag,
}

@Composable
fun EinkGlyph(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = Eink.Black,
    contentDescription: String? = null,
) {
    // Se copia a una local: dentro de `semantics {}` el nombre `contentDescription`
    // también es una propiedad del receptor, y la asignación se leería a sí misma.
    val description = contentDescription
    Canvas(
        modifier
            .size(size)
            .then(
                if (description != null) {
                    Modifier.semantics { this.contentDescription = description }
                } else {
                    Modifier
                }
            )
    ) {
        drawGlyph(glyph, tint)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val u = size.minDimension / 24f
    val line = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = 1.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun polyline(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
        }
        drawPath(path, tint, style = line)
    }

    fun closedPath(points: Array<out Pair<Float, Float>>): Path {
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
        }
        path.close()
        return path
    }

    fun filled(vararg points: Pair<Float, Float>) = drawPath(closedPath(points), tint)

    /** Relleno de un color propio: para los iconos que van en color, como el original. */
    fun fillWith(color: Color, vararg points: Pair<Float, Float>) = drawPath(closedPath(points), color)

    /** Contorno cerrado con el trazo normal. */
    fun outline(vararg points: Pair<Float, Float>) = drawPath(closedPath(points), tint, style = line)

    // El hueco de una forma maciza: blanco sobre fondo blanco, negro si el
    // icono va calado en blanco (botón seleccionado).
    val hole = if (tint == Eink.White) Eink.Black else Eink.White

    fun frame(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        corner: Float = 2.5f,
        style: Stroke = thin,
    ) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(left * u, top * u),
            size = Size((right - left) * u, (bottom - top) * u),
            cornerRadius = CornerRadius(corner * u, corner * u),
            style = style,
        )
    }

    when (glyph) {
        Glyph.ChevronLeft -> polyline(15f to 5f, 8.5f to 12f, 15f to 19f)
        Glyph.ChevronRight -> polyline(9f to 5f, 15.5f to 12f, 9f to 19f)
        Glyph.ChevronUp -> polyline(5f to 15f, 12f to 8.5f, 19f to 15f)
        Glyph.ChevronDown -> polyline(5f to 9f, 12f to 15.5f, 19f to 9f)

        Glyph.CaretDown -> filled(6f to 9f, 18f to 9f, 12f to 16f)
        Glyph.CaretRight -> filled(9f to 6f, 16f to 12f, 9f to 18f)

        Glyph.Search -> {
            drawCircle(tint, radius = 6.2f * u, center = Offset(10.5f * u, 10.5f * u), style = line)
            polyline(15.2f to 15.2f, 20f to 20f)
        }

        // Nube sólida con la marca calada en blanco. Una nube de contorno a
        // 22dp en e-ink se convierte en un borrón; rellena siempre se lee.
        Glyph.SyncCloud -> {
            val cloud = Path().apply {
                addOval(
                    androidx.compose.ui.geometry.Rect(
                        Offset(8f * u, 4.2f * u),
                        Size(9.5f * u, 9.5f * u),
                    )
                )
                addOval(
                    androidx.compose.ui.geometry.Rect(
                        Offset(2.5f * u, 8f * u),
                        Size(8f * u, 8f * u),
                    )
                )
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = 3.5f * u,
                        top = 11f * u,
                        right = 20.5f * u,
                        bottom = 18.5f * u,
                        cornerRadius = CornerRadius(3.6f * u, 3.6f * u),
                    )
                )
            }
            drawPath(cloud, tint)
            val check = Path().apply {
                moveTo(8.8f * u, 13.4f * u)
                lineTo(11.1f * u, 15.7f * u)
                lineTo(15.6f * u, 10.4f * u)
            }
            drawPath(
                check,
                Eink.White,
                style = Stroke(2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        Glyph.Mail -> {
            frame(2.5f, 5f, 21.5f, 19f, corner = 2.8f)
            polyline(4.5f to 7.5f, 12f to 13.5f, 19.5f to 7.5f)
        }

        Glyph.Menu -> {
            drawCircle(tint, radius = 10f * u, center = Offset(12f * u, 12f * u), style = thin)
            polyline(7.5f to 9f, 16.5f to 9f)
            polyline(7.5f to 12f, 16.5f to 12f)
            polyline(7.5f to 15f, 16.5f to 15f)
        }

        Glyph.Plus -> {
            polyline(12f to 5f, 12f to 19f)
            polyline(5f to 12f, 19f to 12f)
        }

        Glyph.Minus -> {
            polyline(5f to 12f, 19f to 12f)
        }

        Glyph.Gear -> {
            drawCircle(tint, radius = 4.4f * u, center = Offset(12f * u, 12f * u), style = thin)
            // Seis dientes radiales. Con más, a este tamaño, se juntan.
            polyline(12f to 2.5f, 12f to 6.2f)
            polyline(20.2f to 7.2f, 16.4f to 9.4f)
            polyline(20.2f to 16.8f, 16.4f to 14.6f)
            polyline(12f to 21.5f, 12f to 17.8f)
            polyline(3.8f to 16.8f, 7.6f to 14.6f)
            polyline(3.8f to 7.2f, 7.6f to 9.4f)
        }

        Glyph.Today -> {
            frame(3f, 5f, 21f, 20f, corner = 3f)
            polyline(3f to 9.5f, 21f to 9.5f)
            polyline(7.5f to 3f, 7.5f to 6.5f)
            polyline(16.5f to 3f, 16.5f to 6.5f)
            drawCircle(tint, radius = 2f * u, center = Offset(12f * u, 15f * u))
        }

        Glyph.Bell -> {
            // Cúpula sobre dos jambas, base y badajo. Sin relleno: a 24dp una
            // campana maciza se confunde con la nube de sincronizar.
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(7f * u, 6f * u),
                size = Size(10f * u, 10f * u),
                style = thin,
            )
            polyline(7f to 11f, 7f to 16.5f)
            polyline(17f to 11f, 17f to 16.5f)
            polyline(4.5f to 16.5f, 19.5f to 16.5f)
            drawCircle(tint, radius = 1.7f * u, center = Offset(12f * u, 19f * u))
        }

        Glyph.Clock -> {
            drawCircle(tint, radius = 8.6f * u, center = Offset(12f * u, 12f * u), style = thin)
            polyline(12f to 6.8f, 12f to 12f, 15.8f to 14.2f)
        }

        Glyph.Pin -> {
            drawCircle(tint, radius = 5.4f * u, center = Offset(12f * u, 9.8f * u), style = thin)
            polyline(7.7f to 13.1f, 12f to 20.5f, 16.3f to 13.1f)
            drawCircle(tint, radius = 2.1f * u, center = Offset(12f * u, 9.8f * u))
        }

        Glyph.ListCheck -> {
            polyline(3.5f to 7.6f, 5.6f to 9.7f, 9.4f to 5.4f)
            polyline(3.5f to 16.4f, 5.6f to 18.5f, 9.4f to 14.2f)
            polyline(12.5f to 7.6f, 20.5f to 7.6f)
            polyline(12.5f to 16.4f, 20.5f to 16.4f)
        }

        Glyph.GridMonth -> {
            frame(3f, 5f, 21f, 20f, corner = 3f)
            polyline(3f to 9.5f, 21f to 9.5f)
            polyline(7.5f to 3f, 7.5f to 6.5f)
            polyline(16.5f to 3f, 16.5f to 6.5f)
            listOf(7.5f, 12f, 16.5f).forEach { x ->
                listOf(13f, 17f).forEach { y ->
                    drawCircle(tint, radius = 1.3f * u, center = Offset(x * u, y * u))
                }
            }
        }

        Glyph.GridWeek -> {
            frame(3f, 5f, 21f, 20f, corner = 3f)
            polyline(3f to 9.5f, 21f to 9.5f)
            polyline(9f to 9.5f, 9f to 20f)
            polyline(15f to 9.5f, 15f to 20f)
        }

        Glyph.GridDay -> {
            frame(3f, 5f, 21f, 20f, corner = 3f)
            polyline(3f to 9.5f, 21f to 9.5f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(6.5f * u, 12f * u),
                size = Size(11f * u, 5f * u),
                cornerRadius = CornerRadius(1.5f * u, 1.5f * u),
            )
        }

        Glyph.Import -> {
            polyline(12f to 3.5f, 12f to 14.4f)
            polyline(7.8f to 10.2f, 12f to 14.6f, 16.2f to 10.2f)
            polyline(4f to 15f, 4f to 19.8f, 20f to 19.8f, 20f to 15f)
        }
        Glyph.Upload -> {
            polyline(12f to 14.6f, 12f to 3.7f)
            polyline(7.8f to 7.9f, 12f to 3.5f, 16.2f to 7.9f)
            polyline(4f to 15f, 4f to 19.8f, 20f to 19.8f, 20f to 15f)
        }

        // Los tres útiles van en diagonal, de la punta (abajo a la izquierda)
        // al extremo (arriba a la derecha). Se dibujan sobre un eje: `t` es
        // la distancia desde la punta y `s` el desvío lateral, y `p` lo pasa
        // a coordenadas del lienzo.

        Glyph.Pencil -> {
            val k = 0.7071f
            fun p(t: Float, s: Float) = (4f + (t + s) * k) to (20f - (t - s) * k)
            val w = 2.5f
            val wood = Color(0xFFF6CD95)
            val yellow = Color(0xFFF7C948)
            val ferrule = Color(0xFFC9E6F2)
            val rubber = Color(0xFFE5453C)
            fillWith(wood, p(0f, 0f), p(4f, w), p(4f, -w))
            fillWith(tint, p(0f, 0f), p(1.5f, 0.9f), p(1.5f, -0.9f))
            fillWith(yellow, p(4f, w), p(17.5f, w), p(17.5f, -w), p(4f, -w))
            fillWith(ferrule, p(17.5f, w), p(19.2f, w), p(19.2f, -w), p(17.5f, -w))
            fillWith(rubber, p(19.2f, w), p(22f, w), p(22f, -w), p(19.2f, -w))
            // Brillo del cuerpo.
            val shine = Path().apply {
                val a = p(6f, -1f)
                val b = p(15.5f, -1f)
                moveTo(a.first * u, a.second * u)
                lineTo(b.first * u, b.second * u)
            }
            drawPath(shine, Color(0xFFFFF0B3), style = Stroke(width = 1.1f * u, cap = StrokeCap.Round))
            outline(p(0f, 0f), p(4f, w), p(22f, w), p(22f, -w), p(4f, -w))
            polyline(p(4f, w), p(4f, -w))
            polyline(p(17.5f, w), p(17.5f, -w))
            polyline(p(19.2f, w), p(19.2f, -w))
        }

        Glyph.Ballpoint -> {
            val k = 0.7071f
            fun p(t: Float, s: Float) = (2.5f + (t + s) * k) to (21.5f - (t - s) * k)
            val w = 2.6f
            val green = Color(0xFF4E8F2E)
            val shade = Color(0xFF396A22)
            // Punta blanca, cuerpo verde con su sombra, el pulsador de aro en
            // el extremo y el clip que baja por el lado derecho.
            fillWith(hole, p(0f, 0f), p(4.2f, w), p(4.2f, -w))
            fillWith(green, p(4.2f, w), p(20.5f, w), p(20.5f, -w), p(4.2f, -w))
            val stripe = Path().apply {
                val a = p(6.5f, 1.1f)
                val b = p(18.5f, 1.1f)
                moveTo(a.first * u, a.second * u)
                lineTo(b.first * u, b.second * u)
            }
            drawPath(stripe, shade, style = Stroke(width = 1.3f * u, cap = StrokeCap.Round))
            outline(p(0f, 0f), p(4.2f, w), p(20.5f, w), p(20.5f, -w), p(4.2f, -w))
            polyline(p(4.2f, w), p(4.2f, -w))
            val ring = p(22.6f, 0f)
            drawCircle(tint, radius = 1.5f * u, center = Offset(ring.first * u, ring.second * u), style = thin)
            polyline(p(17.5f, w), p(17.5f, w + 3.6f), p(11f, w + 3.6f))
        }

        Glyph.Marker -> {
            val k = 0.7071f
            fun p(t: Float, s: Float) = (2.5f + (t + s) * k) to (19.5f - (t - s) * k)
            val w = 3.6f
            val pink = Color(0xFFD0195B)
            val felt = Color(0xFF6B6B6B)
            fillWith(pink, p(7f, w), p(24f, w), p(24f, -w), p(7f, -w))
            fillWith(felt, p(2f, -1.5f), p(3.5f, -2.4f), p(7f, -w), p(7f, w), p(3.5f, 2.4f), p(2f, 1.5f))
            fillWith(pink, p(0f, -0.7f), p(2f, -1.5f), p(2f, 1.5f), p(0f, 0.7f))
            outline(
                p(0f, -0.7f), p(2f, -1.5f), p(3.5f, -2.4f), p(7f, -w), p(24f, -w),
                p(24f, w), p(7f, w), p(3.5f, 2.4f), p(2f, 1.5f), p(0f, 0.7f),
            )
            polyline(p(7f, w), p(7f, -w))
            // La raya que deja debajo.
            val mark = Path().apply {
                moveTo(2f * u, 22.6f * u)
                lineTo(13.5f * u, 22.6f * u)
            }
            drawPath(mark, pink, style = Stroke(width = 1.9f * u, cap = StrokeCap.Round))
        }

        Glyph.Bulb -> {
            // Ampolla redonda, el casquillo debajo y cuatro rayos alrededor.
            drawCircle(tint, radius = 5f * u, center = Offset(12f * u, 9.5f * u), style = thin)
            polyline(9.5f to 15.5f, 9.5f to 18.5f, 14.5f to 18.5f, 14.5f to 15.5f)
            polyline(10.5f to 20.5f, 13.5f to 20.5f)
            polyline(12f to 1.5f, 12f to 3f)
            polyline(4f to 9.5f, 5.5f to 9.5f)
            polyline(18.5f to 9.5f, 20f to 9.5f)
            polyline(6.2f to 3.8f, 7.3f to 4.9f)
            polyline(17.8f to 3.8f, 16.7f to 4.9f)
        }

        Glyph.Edit -> {
            // Marco abierto por arriba a la derecha, por donde entra el lápiz.
            polyline(11.5f to 4f, 6f to 4f, 4f to 6f, 4f to 18f, 6f to 20f, 18f to 20f, 20f to 18f, 20f to 12.5f)
            outline(9.8f to 12.4f, 18.4f to 3.8f, 20.6f to 6f, 12f to 14.6f)
            polyline(12f to 14.6f, 8f to 16.4f, 9.8f to 12.4f)
        }

        Glyph.NewSheet -> {
            // Página con la esquina superior derecha doblada y un más dentro.
            polyline(14f to 3f, 6f to 3f, 4.5f to 4.5f, 4.5f to 19.5f, 6f to 21f, 18f to 21f, 19.5f to 19.5f, 19.5f to 8.5f, 14f to 3f)
            polyline(14f to 3f, 14f to 8.5f, 19.5f to 8.5f)
            polyline(12f to 11.5f, 12f to 17.5f)
            polyline(9f to 14.5f, 15f to 14.5f)
        }

        Glyph.ToText -> {
            // Arriba un garabato (la escritura), en medio la flecha, abajo la T.
            val scribble = Path().apply {
                moveTo(4f * u, 6.5f * u)
                cubicTo(7f * u, 2.5f * u, 9f * u, 9.5f * u, 12f * u, 6f * u)
                cubicTo(15f * u, 2.5f * u, 17f * u, 9.5f * u, 20f * u, 6f * u)
            }
            drawPath(scribble, tint, style = thin)
            polyline(12f to 9f, 12f to 12.5f)
            polyline(9.8f to 10.8f, 12f to 13f, 14.2f to 10.8f)
            filled(6f to 14.5f, 18f to 14.5f, 18f to 16.8f, 6f to 16.8f)
            filled(10.7f to 16.8f, 13.3f to 16.8f, 13.3f to 22f, 10.7f to 22f)
        }

        Glyph.Title -> {
            // Calcado del icono elegido: marco abierto por la derecha, una T con
            // remates y tres puntos.
            polyline(
                20f to 10.5f, 20f to 6.5f, 18f to 4.5f, 6f to 4.5f, 4f to 6.5f,
                4f to 17.5f, 6f to 19.5f, 18f to 19.5f, 20f to 17.5f, 20f to 15f,
            )
            polyline(5.8f to 10.4f, 5.8f to 8.6f, 11.2f to 8.6f, 11.2f to 10.4f)
            polyline(8.5f to 8.6f, 8.5f to 15.2f)
            listOf(12.6f, 15.4f, 18.2f).forEach { x ->
                drawCircle(tint, radius = 1.05f * u, center = Offset(x * u, 15.2f * u))
            }
        }

        Glyph.Eraser -> {
            // Calcado del icono nativo: goma inclinada, partida por una línea,
            // con la viruta curvada abajo a la derecha.
            polyline(4f to 14f, 12.5f to 5.5f, 19f to 12f, 10.5f to 20.5f, 4f to 14f)
            polyline(8f to 10f, 14.5f to 16.5f)
            val curl = Path().apply {
                moveTo(13f * u, 20.5f * u)
                cubicTo(15.5f * u, 22.5f * u, 18.5f * u, 21.5f * u, 21f * u, 19f * u)
            }
            drawPath(curl, tint, style = line)
        }

        Glyph.Lasso -> {
            // Calcado del icono nativo: bucle ancho y nudo abajo a la izquierda.
            drawOval(
                color = tint,
                topLeft = Offset(4.5f * u, 4.5f * u),
                size = Size(15.5f * u, 12f * u),
                style = line,
            )
            drawCircle(tint, radius = 2.2f * u, center = Offset(7.5f * u, 17.5f * u), style = line)
            polyline(8.8f to 15.6f, 10f to 14f)
        }

        Glyph.Undo -> {
            polyline(9.2f to 5.8f, 4.6f to 10.4f, 9.2f to 15f)
            polyline(4.6f to 10.4f, 14.2f to 10.4f)
            polyline(14.2f to 10.4f, 17.8f to 12.6f, 18.8f to 16.4f)
        }

        Glyph.Redo -> {
            polyline(14.8f to 5.8f, 19.4f to 10.4f, 14.8f to 15f)
            polyline(19.4f to 10.4f, 9.8f to 10.4f)
            polyline(9.8f to 10.4f, 6.2f to 12.6f, 5.2f to 16.4f)
        }

        Glyph.Palette -> {
            drawCircle(tint, radius = 8.6f * u, center = Offset(12f * u, 12f * u), style = thin)
            listOf(
                9f to 8.2f,
                15f to 8.2f,
                7.6f to 13.6f,
                16.4f to 13.6f,
                12f to 16.8f,
            ).forEach { (x, y) ->
                drawCircle(tint, radius = 1.35f * u, center = Offset(x * u, y * u))
            }
        }

        Glyph.Thickness -> {
            // Tres barras de grueso creciente. Rellenas y no trazadas: el grosor
            // del trazo es fijo en este dibujo, y aquí el grosor ES el mensaje.
            filled(4f to 6.4f, 20f to 6.4f, 20f to 7.4f, 4f to 7.4f)
            filled(4f to 11f, 20f to 11f, 20f to 13.2f, 4f to 13.2f)
            filled(4f to 16.4f, 20f to 16.4f, 20f to 20f, 4f to 20f)
        }

        Glyph.Trash -> {
            // Calcado del icono nativo: tapa con asa y cubo con dos rayas.
            polyline(4f to 7f, 20f to 7f)
            polyline(9.5f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 14.5f to 7f)
            polyline(6f to 7f, 6f to 20.5f, 18f to 20.5f, 18f to 7f)
            polyline(10f to 10.5f, 10f to 17f)
            polyline(14f to 10.5f, 14f to 17f)
        }

        Glyph.Check -> polyline(4.5f to 12.6f, 9.6f to 17.7f, 19.5f to 6.6f)

        Glyph.Note -> {
            frame(5f, 3f, 19f, 21f, corner = 2.6f)
            polyline(8f to 8f, 16f to 8f)
            polyline(8f to 12f, 16f to 12f)
            polyline(8f to 16f, 13f to 16f)
        }

        Glyph.TabMemo -> {
            frame(3.5f, 3.5f, 20.5f, 20.5f, corner = 3f)
            // Lápiz en diagonal, con la punta hacia abajo a la izquierda.
            polyline(8f to 16f, 16f to 8f)
            filled(7f to 17f, 7.6f to 14.6f, 9.4f to 16.4f)
        }

        Glyph.TabReminder -> {
            // Calendario con marca, como el icono amarillo de la app nativa.
            frame(3.5f, 5f, 20.5f, 20.5f, corner = 3f)
            polyline(3.5f to 9f, 20.5f to 9f)
            polyline(8f to 3f, 8f to 6.5f)
            polyline(16f to 3f, 16f to 6.5f)
            polyline(8f to 14f, 10.8f to 16.8f, 16.2f to 11.2f)
        }

        Glyph.Pen -> {
            // Plumín estilográfico macizo, hacia abajo, con su ojo y su hendidura.
            drawRoundRect(
                color = tint,
                topLeft = Offset(6f * u, 1.5f * u),
                size = Size(12f * u, 3.1f * u),
                cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
            )
            filled(7.6f to 6.2f, 16.4f to 6.2f, 19.4f to 13.6f, 12f to 22.4f, 4.6f to 13.6f)
            drawCircle(hole, radius = 2.3f * u, center = Offset(12f * u, 12.2f * u))
            drawRect(hole, topLeft = Offset(11.3f * u, 13.6f * u), size = Size(1.4f * u, 8.6f * u))
        }

        Glyph.Bullets -> {
            listOf(6f, 12f, 18f).forEach { y ->
                drawCircle(tint, radius = 1.6f * u, center = Offset(5f * u, y * u))
                polyline(9.5f to y, 20f to y)
            }
        }

        Glyph.Copy -> {
            frame(8f, 8f, 20f, 21f, corner = 2.2f)
            polyline(5f to 15.5f, 4f to 15.5f, 4f to 3.5f, 15.5f to 3.5f, 15.5f to 5f)
        }

        Glyph.Cut -> {
            drawCircle(tint, radius = 3f * u, center = Offset(7f * u, 17f * u), style = thin)
            drawCircle(tint, radius = 3f * u, center = Offset(17f * u, 17f * u), style = thin)
            polyline(9.2f to 14.8f, 19f to 3.5f)
            polyline(14.8f to 14.8f, 5f to 3.5f)
        }

        Glyph.Paste -> {
            frame(5f, 5f, 19f, 21f, corner = 2.4f)
            frame(9f, 3f, 15f, 7f, corner = 1.2f)
            polyline(8.5f to 12f, 15.5f to 12f)
            polyline(8.5f to 16f, 13.5f to 16f)
        }

        Glyph.TabAgenda -> {
            frame(3.5f, 3.5f, 20.5f, 20.5f, corner = 3f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(6.5f * u, 6.5f * u),
                size = Size(3.6f * u, 11f * u),
                cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
            )
            polyline(13.5f to 7.5f, 17.5f to 7.5f)
            polyline(13.5f to 12f, 17.5f to 12f)
            polyline(13.5f to 16.5f, 17.5f to 16.5f)
        }

        Glyph.Folder -> {
            // Cuerpo con la pestaña arriba a la izquierda.
            outline(
                3f to 7f, 3f to 19.5f, 21f to 19.5f, 21f to 8.5f,
                11.5f to 8.5f, 9.5f to 5.5f, 4.5f to 5.5f,
            )
            polyline(3f to 10.5f, 21f to 10.5f)
        }

        Glyph.Tag -> {
            // Etiqueta en rombo con el ojal arriba a la izquierda.
            outline(4f to 4f, 12.5f to 4f, 20.5f to 12f, 12.5f to 20f, 4f to 12f)
            drawCircle(tint, radius = 1.6f * u, center = Offset(8.3f * u, 8.3f * u), style = thin)
        }

        Glyph.TabNotebook -> {
            frame(5.5f, 3.5f, 20.5f, 20.5f, corner = 2.6f)
            // Lomo del cuaderno.
            polyline(9f to 3.5f, 9f to 20.5f)
            polyline(12f to 8.5f, 17.5f to 8.5f)
            polyline(12f to 12f, 17.5f to 12f)
            polyline(12f to 15.5f, 17.5f to 15.5f)
        }
    }
}
