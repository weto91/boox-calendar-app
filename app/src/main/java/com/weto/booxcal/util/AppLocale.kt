package com.weto.booxcal.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language the app runs in.
 *
 * By default the app follows the device: Spanish on a tablet set to Spanish,
 * English anywhere else (English is the default resource set). The user can
 * pin one of the two from Settings; the choice is kept in a small
 * SharedPreferences file because it has to be read synchronously, before any
 * context exists, from `attachBaseContext`.
 *
 * On Android 13+ the choice is also handed to the system's per-app locale, so
 * widgets, system dialogs and the app's entry in system settings follow it.
 * The context wrapping covers every version, including the app process's own
 * resources used by background work.
 */
object AppLocale {

    /** Follow the device language. */
    const val SYSTEM = ""
    const val SPANISH = "es"
    const val ENGLISH = "en"

    /** What Settings offers, in order. */
    val choices: List<String> = listOf(SYSTEM, SPANISH, ENGLISH)

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** The pinned language tag, or [SYSTEM]. */
    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, SYSTEM) ?: SYSTEM

    /**
     * Pins a language (or [SYSTEM]). The caller recreates the activity so the
     * screen is rebuilt in the new language.
     */
    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java)
                manager.applicationLocales =
                    if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            }
        }
    }

    /** The given context, or a copy of it configured with the pinned language. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** The locale the given context resolves resources with. */
    fun locale(context: Context): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
}
