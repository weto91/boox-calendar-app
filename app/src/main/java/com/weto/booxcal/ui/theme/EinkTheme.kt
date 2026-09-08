package com.weto.booxcal.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paleta base.
 *
 * **Todo el fondo es blanco**: el de la página y el de las tarjetas. Lo que
 * separa una tarjeta del resto es su borde de 1dp, nunca un relleno gris. En un
 * panel e-ink un fondo tintado se come contraste y obliga a refrescar más
 * superficie de la necesaria.
 *
 * Los grises solo hacen dos cosas: dibujar líneas y bajar de nivel un texto
 * secundario. Nunca portan información por sí solos.
 *
 * El color vive aparte, en [EinkPalette], y solo aparece en barras de acento y
 * puntos de día — nunca en texto corrido.
 */
object Eink {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /** Texto secundario: hora, nombre de lista, pie de tarjeta. */
    val Graphite = Color(0xFF585858)

    /** Días de otro mes, tareas completadas, texto deshabilitado. */
    val Slate = Color(0xFF9A9A9A)

    /**
     * Borde de tarjeta y de control.
     *
     * Bastante más oscuro de lo que pediría una pantalla retroiluminada: en
     * e-ink un gris claro sobre blanco desaparece, y con él el único elemento
     * que delimita una tarjeta.
     */
    val Border = Color(0xFF8A8A8A)

    /** Separador entre filas de una misma lista: más suave que el borde. */
    val Hairline = Color(0xFFBFBFBF)
}

val HairlineWidth = 1.dp

/**
 * §11: áreas táctiles amplias. Un lápiz apunta mejor que un dedo, pero no tanto,
 * y aquí se navega con el dedo aunque se escriba con el lápiz.
 */
val MinTouchTarget = 52.dp

/** Radio de las tarjetas del panel principal. */
val CardCorner = RoundedCornerShape(14.dp)

/** Radio de botones, pestañas y celdas seleccionables. */
val ControlCorner = RoundedCornerShape(8.dp)

/** Radio de las baldosas de color que llevan un icono calado en blanco. */
val TileCorner = RoundedCornerShape(10.dp)

private val EinkColorScheme = lightColorScheme(
    primary = Eink.Black,
    onPrimary = Eink.White,
    secondary = Eink.Graphite,
    onSecondary = Eink.White,
    background = Eink.White,
    onBackground = Eink.Black,
    surface = Eink.White,
    onSurface = Eink.Black,
    surfaceVariant = Eink.White,
    onSurfaceVariant = Eink.Graphite,
    outline = Eink.Border,
    error = Eink.Black,
    onError = Eink.White,
)

private val Sans = FontFamily.SansSerif

private val EinkTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 56.sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp),
)

@Composable
fun BooxCalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EinkColorScheme,
        typography = EinkTypography,
        content = content,
    )
}

/**
 * Fuente de interacción compartida y sin efecto visual. En e-ink una onda de
 * pulsación obliga a refrescar toda la zona: se ve peor que no ver nada.
 */
@Composable
fun rememberNoIndicationSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }
