package dev.amps.app

import android.app.Application
import dev.amps.app.core.AppContainer
import dev.amps.app.update.AutoUpdateWorker

class AmpsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 1.1.2: канали сповіщень одразу, фонова перевірка Спільноти — цикл.
        container.notifier.createChannels()
        container.communityWatcher.start()
        // 1.2.0: фонові патчі — поки застосунок закритий, перевіряє GitHub
        // і завантажує нову збірку; при наступному відкритті одразу
        // пропонується системне встановлення.
        AutoUpdateWorker.schedule(this)
    }
}
