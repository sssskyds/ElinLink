package com.elin.elinlink

import android.app.Application

/** Applies the saved (default dark) theme before any activity is created. */
class ElinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.apply(ThemeManager.isDark(this))
    }
}
