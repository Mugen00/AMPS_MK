package dev.kagami.app

import android.app.Application
import dev.kagami.app.core.AppContainer

class KagamiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
