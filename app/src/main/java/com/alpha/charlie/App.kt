package com.alpha.charlie

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Native backend init happens lazily in LlamaEngine
    }
}
