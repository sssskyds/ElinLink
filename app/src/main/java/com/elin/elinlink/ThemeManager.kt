package com.elin.elinlink

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Persists and applies the app's light/dark theme choice. Default is dark. */
object ThemeManager {
    private const val PREFS = "elinlink_prefs"
    private const val KEY_DARK = "theme_dark"

    fun isDark(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

    fun setDark(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    /** Applies the night mode globally; AppCompat recreates started activities to match. */
    fun apply(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
