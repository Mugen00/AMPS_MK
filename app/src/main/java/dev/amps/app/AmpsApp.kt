package dev.amps.app

import android.app.Application
import dev.amps.app.core.AppContainer

class AmpsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 1.1.2: канали сповіщень одразу, фонова перевірка Спільноти — цикл.
        container.notifier.createChannels()
        container.communityWatcher.start()
    }
}
