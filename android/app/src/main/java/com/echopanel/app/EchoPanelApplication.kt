package com.echopanel.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EchoPanelApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installHoverExitCrashGuard()
    }


    private fun installHoverExitCrashGuard() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isHoverExitGlitch = throwable is IllegalStateException &&
                    throwable.message?.contains("ACTION_HOVER_EXIT") == true
            if (isHoverExitGlitch) {
                android.util.Log.w(
                    "EchoPanelApplication",
                    "Suppressed known Compose hover-exit framework glitch",
                    throwable,
                )
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}