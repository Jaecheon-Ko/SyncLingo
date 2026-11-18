package com.synclingo.app

import android.app.Application
import com.synclingo.app.data.Engine

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
