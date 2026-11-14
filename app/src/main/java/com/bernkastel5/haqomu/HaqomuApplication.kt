package com.bernkastel5.haqomu

import android.app.Application

/**
 * Application entry point. Dictionary packs and the morphology database are
 * initialized lazily, never at process start; the lookup popup must open fast.
 */
class HaqomuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Phase 5: register pack registry here (lazy). Do not block onCreate.
    }
}
