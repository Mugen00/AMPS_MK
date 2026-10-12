package dev.amps.app

import android.app.Application
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import dev.amps.app.core.AppContainer
import dev.amps.app.update.AutoUpdateWorker

/**
 * 1.2.0 (патч 23): ImageLoaderFactory — єдиний ImageLoader Coil з
 * GIF-декодером. Без цього AsyncImage малює GIF першим статичним кадром;
 * з декодером анімація грає всюди: пости, сторіс, аватари.
 * API 28+ — системний ImageDecoderDecoder (gif+webp анімовані),
 * старіше — бібліотечний GifDecoder.
 */
class AmpsApp : Application(), coil.ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 1.1.2: канали сповіщень одразу, фонова перевірка Спільноти — цикл.
        container.notifier.createChannels()
        container.communityWatcher.start()
        // 1.2.2: нагадування про нові серії аніме — свій шестигодинний цикл.
        container.animeWatcher.start()
        // 1.2.0: фонові патчі — поки застосунок закритий, перевіряє GitHub
        // і завантажує нову збірку; при наступному відкритті одразу
        // пропонується системне встановлення.
        AutoUpdateWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            // 1.4.0: стабільність медіа — картинки при переходах між
            // екранами не перезавантажуються з мережі, а беруться з кешів.
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "coil_image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
