package com.meldiq.android.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, independent of the phone's (2026-10-17, docs/LANGUAGES.md): English, Simplified Chinese or
 * Bengali. On Android 13 and later this is Android's per-app language (also shown in the system's app settings); on
 * older versions the activity is started with the chosen locale. Strings live in res/values{,-zh-rCN,-bn}.
 */
object AppLanguage {
    /** Language tags offered in Settings › Language ("system": follow the phone). */
    val SUPPORTED = listOf("system", "en", "zh-CN", "bn")
    private const val PREFS = "griip_settings"
    private const val KEY = "language"

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (l != null && !l.isEmpty) return normalize(l[0].toLanguageTag())
            return "system"
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"
    }

    /** "zh-Hans-CN" and similar spellings → the tags this app uses. */
    fun normalize(tag: String): String = when {
        tag.startsWith("zh", ignoreCase = true) -> "zh-CN"
        tag.startsWith("bn", ignoreCase = true) -> "bn"
        tag.startsWith("en", ignoreCase = true) -> "en"
        else -> "system"
    }

    /** Before Android 13: the context the activity starts with, in the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"
        if (tag == "system") return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    /** Switches the app to [tag] and restarts the screen in it; messages, accounts and settings are untouched. */
    fun apply(activity: Activity, tag: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            // Android recreates the activity in the new language itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    /** The language's own name, shown in its own script. */
    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "zh-CN" -> "简体中文"
        "bn" -> "বাংলা"
        else -> ""
    }
}
