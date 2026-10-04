package dev.amps.app.imaging

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Процесс тегера: `:tagger`.
 *
 * Вся работа с ONNX Runtime происходит здесь и больше нигде. Основной процесс
 * сюда не заглядывает — он видит только [AnimeTagger], который через Binder
 * просит разобрать картинку и получает теги обратно.
 *
 * Что это даёт: если модель уронит процесс нативным SIGSEGV, Android убьёт
 * только `:tagger`. Основное приложение получит отключение связи и покажет
 * «распознавание недоступно», продолжив работать.
 *
 * Разбор идёт на одном потоке: модель не создаётся заново и веса не дублируются,
 * а параллельные запросы просто ждут в очереди.
 */
class TagService : Service() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anime-tagger").apply { priority = Thread.NORM_PRIORITY }
    }

    /** Создаётся при первом разборе и живёт до остановки сервиса. */
    private var engine: TaggerEngine? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                TagProtocol.MSG_ANALYZE -> handleAnalyze(message)
                else -> super.handleMessage(message)
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    private fun handleAnalyze(message: Message) {
        val path = message.data?.getString(TagProtocol.KEY_PATH)
        val replyTo = message.replyTo
        val token = message.arg1

        if (path == null || replyTo == null) {
            sendUnavailable(replyTo, token, "неполный запрос к тегеру")
            return
        }

        // Читаем файл до ухода в фон: это быстро, и если файла уже нет,
        // основной процесс получит внятный отказ, а не падение.
        val bytes = runCatching { File(path).readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            File(path).delete()
            sendUnavailable(replyTo, token, "картинка не сохранилась")
            return
        }
        File(path).delete()

        executor.execute {
            // `tag` — suspend, а здесь обычный поток. Именно для этого и
            // нужен runBlocking: вызов всё равно должен дождаться разбора, и
            // ответ уходит уже после него, а не «когда-нибудь потом».
            val result = try {
                runBlocking { ensureEngine()?.tag(bytes) }
            } catch (error: Throwable) {
                Log.w(TAG, "разбор не удался: ${error.javaClass.simpleName}: ${error.message}")
                null
            }

            if (result == null) {
                sendUnavailable(replyTo, token, "модель не смогла отработать")
            } else {
                sendResult(replyTo, token, result)
            }
        }
    }

    private fun ensureEngine(): TaggerEngine? =
        engine ?: TaggerEngine(applicationContext).also { engine = it }

    private fun sendResult(replyTo: Messenger, token: Int, result: TaggerEngine.Result) {
        val payload = Bundle().apply {
            putBoolean(TagProtocol.KEY_OK, true)
            putLong(TagProtocol.KEY_ELAPSED_MS, result.elapsedMs)
            putStringArrayList(
                TagProtocol.KEY_NAMES,
                ArrayList(result.characters.map { it.name }),
            )
            putFloatArray(
                TagProtocol.KEY_PROBS,
                result.characters.map { it.probability }.toFloatArray(),
            )
            // Внешность идёт вторым набором: один Bundle на оба не хватит,
            // а плодить сообщения ради двух списков смысла нет.
            putStringArrayList(
                TagProtocol.KEY_NAMES + TagProtocol.KEY_NAMES,
                ArrayList(result.appearance.map { it.name }),
            )
            putFloatArray(
                TagProtocol.KEY_PROBS + TagProtocol.KEY_PROBS,
                result.appearance.map { it.probability }.toFloatArray(),
            )
        }
        replyTo.send(Message.obtain(null, TagProtocol.MSG_RESULT).apply {
            arg1 = token
            data = payload
        })
    }

    private fun sendUnavailable(replyTo: Messenger?, token: Int, reason: String) {
        replyTo ?: return
        replyTo.send(Message.obtain(null, TagProtocol.MSG_UNAVAILABLE).apply {
            arg1 = token
            data = Bundle().apply {
                putBoolean(TagProtocol.KEY_OK, false)
                putString(TagProtocol.KEY_REASON, reason)
            }
        })
    }

    override fun onDestroy() {
        // Сессия держит нативную память, которой сборщик мусора не видит:
        // без закрытия процесс уйдёт, унося 167 МБ до конца своей жизни.
        runCatching { engine?.close() }
        engine = null
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TagService"
    }
}