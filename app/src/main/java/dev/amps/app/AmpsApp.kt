package dev.amps.app

import android.app.Application
import dev.amps.app.core.AppContainer

class AmpsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
