package com.weto.booxcal.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Date formatters that follow the app language.
 *
 * The patterns live in the string resources ("EEEE, d MMMM yyyy" in English,
 * "EEEE d 'de' MMMM 'de' yyyy" in Spanish), so both the words and the shape
 * of a date change with the language. Formatters are cached per pattern and
 * locale: building one is not free and the same few are asked for constantly.
 */
object DateFormats {

    private val cache = ConcurrentHashMap<String, DateTimeFormatter>()

    /** The formatter for the pattern resource, in the context's language. */
    fun of(context: Context, @StringRes pattern: Int): DateTimeFormatter =
        literal(context.getString(pattern), AppLocale.locale(context))

    /** A formatter for a fixed pattern (a clock, an ISO stamp) in the given locale. */
    fun literal(pattern: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        cache.getOrPut("$pattern|${locale.toLanguageTag()}") { DateTimeFormatter.ofPattern(pattern, locale) }
}

/** The formatter for a pattern resource, remembered until the language changes. */
@Composable
fun rememberDateFormat(@StringRes pattern: Int): DateTimeFormatter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val text = stringResource(pattern)
    return remember(text, configuration) { DateFormats.literal(text, AppLocale.locale(context)) }
}

/** The current locale of the composition, following the app language. */
@Composable
fun rememberLocale(): Locale {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) { AppLocale.locale(context) }
}

/** Abbreviated month and weekday names without their trailing dots ("sept" instead of "sept."). */
fun LocalDate.format(formatter: DateTimeFormatter, capitalize: Boolean, locale: Locale): String {
    val text = format(formatter).replace(".", "")
    return if (capitalize) text.replaceFirstChar { it.titlecase(locale) } else text
}
