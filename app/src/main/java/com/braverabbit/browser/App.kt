package com.braverabbit.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        PinManager.ensureDefault()
        applyTheme()
        // A fresh process means the browser was closed: start clean (Instagram session is kept).
        if (Prefs.autoClean) {
            PrivacyCleaner.clean(this) { markReady() }
        } else {
            markReady()
        }
    }

    companion object {
        lateinit var instance: App

        @Volatile
        var ready = false
        private val waiting = ArrayList<() -> Unit>()

        fun whenReady(cb: () -> Unit) {
            if (ready) cb() else waiting.add(cb)
        }

        fun markReady() {
            ready = true
            val list = ArrayList(waiting)
            waiting.clear()
            for (cb in list) cb()
        }

        fun applyTheme() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.theme) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
