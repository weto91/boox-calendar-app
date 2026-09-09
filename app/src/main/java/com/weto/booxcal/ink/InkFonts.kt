package com.weto.booxcal.ink

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.core.content.res.ResourcesCompat
import com.weto.booxcal.R

/**
 * Letras disponibles para el texto transcrito («A texto», «toda la nota a
 * texto»). Una sola lista para el lienzo (que pinta con `Typeface`), las
 * miniaturas y el selector de Ajustes (que pinta con `FontFamily`).
 *
 * Las de sistema son las de cualquier documento de oficina: la sans de
 * Android hace de Arial/Calibri, la serif de Times, la mono de Courier. Las
 * manuscritas van en el APK.
 */
object InkFonts {

    class InkFont(
        val id: String,
        @StringRes val label: Int,
        private val resource: Int?,
        private val systemFamily: String?,
        val family: FontFamily,
    ) {
        fun typeface(context: Context): Typeface = when {
            resource != null -> ResourcesCompat.getFont(context, resource) ?: Typeface.SANS_SERIF
            systemFamily != null -> Typeface.create(systemFamily, Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }

    const val DEFAULT = "sans"

    private fun systemFamily(name: String): FontFamily =
        FontFamily(ComposeTypeface(Typeface.create(name, Typeface.NORMAL)))

    val all: List<InkFont> = listOf(
        InkFont("sans", R.string.font_sans, null, "sans-serif", FontFamily.SansSerif),
        InkFont("serif", R.string.font_serif, null, "serif", FontFamily.Serif),
        InkFont("condensed", R.string.font_condensed, null, "sans-serif-condensed", systemFamily("sans-serif-condensed")),
        InkFont("mono", R.string.font_mono, null, "monospace", FontFamily.Monospace),
        InkFont("handmade", R.string.font_handmade, R.font.simple_handmade, null, FontFamily(Font(R.font.simple_handmade))),
        InkFont("learners", R.string.font_learners, R.font.letters_for_learners, null, FontFamily(Font(R.font.letters_for_learners))),
    )

    fun byId(id: String?): InkFont = all.firstOrNull { it.id == id } ?: all.first()

    fun typeface(context: Context, id: String?): Typeface = byId(id).typeface(context)
}
