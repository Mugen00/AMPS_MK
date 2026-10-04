package dev.amps.app.imaging

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Распознавание персонажа и внешности по картинке, целиком на устройстве.
 *
 * Модель — `SmilingWolf/wd-swinv2-tagger-v3` (Apache-2.0), сжатая до int8.
 * Выбор обоснован замерами на наборе из 36 картинок с известным ответом:
 * эталонный персонаж попадает в первую пятёрку в 100% случаев, первым —
 * в 55,6% при fp32 и 52,8% при int8. То есть правильный ответ в списке
 * есть всегда, просто не всегда на первом месте, — поэтому наружу
 * отдаётся пятёрка, а не один тег.
 *
 * Разрешение на вход не влияет: на тех же картинках сжатие до 230 px по
 * ширине меняло итог на 0 из 21. Поэтому модель тянет 448×448, как обучена.
 *
 * Порог 0,60 выбран по измеренному разрыву: уверенное определение даёт 0,73,
 * а мусор на не-anime картинках держится у 0,50–0,53. Между ними пусто,
 * поэтому 0,60 отсекает шум, не задевая настоящие находки.
 *
 * Сам вычислитель живёт в отдельном процессе, см. [TagService]. Здесь только
 * разговор с ним. Это не архитектура ради архитектуры: падение внутри ONNX
 * Runtime — нативный SIGSEGV, мимо любых Java-обработчиков, и без отдельного
 * процесса оно уносит всё приложение вместе с музыкой и поиском по вики.
 */
class AnimeTagger(private val context: Context) {

    data class Tag(val name: String, val category: Int, val probability: Float)

    data class Result(
        /** Персонажи, отсортированные по убыванию вероятности. */
        val characters: List<Tag>,
        /** Признаки внешности: волосы, одежда, фон. */
        val appearance: List<Tag>,
        /** Сколько занял анализ, для показа в интерфейсе. */
        val elapsedMs: Long,
    ) {
        /**
         * Есть ли на картинке кто-то вообще. Раньше этот сигнал давал ML Kit,
         * теперь он выводится из тегов: у одиночного персонажа есть тег
         * `1girl` или `1boy`, у группового — `multiple_girls`/`multiple_boys`.
         */
        val hasPerson: Boolean
            get() = appearance.any {
                it.name == "1girl" || it.name == "1boy" ||
                    it.name == "multiple_girls" || it.name == "multiple_boys"
            }
    }

    private val tokens = AtomicInteger(0)

    /** Ответы, которые ещё не пришли, по номеру запроса. */
    private val pending = ConcurrentHashMap<Int, (Result?) -> Unit>()

    /** `null` — процесса тегера нет, и мы уже пробовали его поднять. */
    @Volatile
    private var remote: Messenger? = null

    /**
     * Тегер упал или не поднялся. После этого `tag()` возвращает `null`
     * мгновенно, не пытаясь поднять процесс заново на каждом кадре: если он
     * один раз не поднялся, повторные попытки только замучают телефон.
     */
    @Volatile
    private var dead = false

    /** Поднимает процесс тегера только один поток, остальные ждут здесь. */
    private val bindLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val answer = pending.remove(message.arg1) ?: return
            answer(if (message.what == TagProtocol.MSG_RESULT) read(message.data) else null)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = Messenger(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Процесс `:tagger` умер — обычно вместе с моделью. Всё, что было
            // в полёте, честно отменяется, и дальше тегер не поднимается.
            remote = null
            dead = true
            releasePending(null)
            Log.w(TAG, "процесс тегера умер, дальше распознавание недоступно")
        }
    }

    /**
     * Разбирает один кадр.
     *
     * `null` — распознать нечего: процесс тегера недоступен, упал или не
     * ответил вовремя. Это честный отказ, а не «персонажа на картинке нет».
     */
    suspend fun tag(bytes: ByteArray): Result? {
        if (dead || bytes.isEmpty()) return null

        val messenger = ensureBound() ?: return null
        val path = writeTemporary(bytes) ?: return null
        val token = tokens.incrementAndGet()

        return try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pending[token] = { result -> continuation.resume(result) }
                    val request = Message.obtain(null, TagProtocol.MSG_ANALYZE).apply {
                        arg1 = token
                        data = Bundle().apply {
                            putString(TagProtocol.KEY_PATH, path)
                            putParcelable(TagProtocol.KEY_REPLY_TO, replyMessenger)
                        }
                    }
                    try {
                        messenger.send(request)
                    } catch (error: RemoteException) {
                        pending.remove(token)
                        continuation.resume(null)
                    }
                    continuation.invokeOnCancellation { pending.remove(token) }
                }
            }
        } finally {
            File(path).delete()
        }
    }

    private fun read(data: Bundle?): Result? {
        if (data == null) return null
        val characters = readTags(data, TagProtocol.KEY_NAMES, TagProtocol.KEY_PROBS, TaggerEngine.CATEGORY_CHARACTER)
        val appearance = readTags(
            data,
            TagProtocol.KEY_NAMES + TagProtocol.KEY_NAMES,
            TagProtocol.KEY_PROBS + TagProtocol.KEY_PROBS,
            TaggerEngine.CATEGORY_GENERAL,
        )
        return Result(
            characters = characters,
            appearance = appearance,
            elapsedMs = data.getLong(TagProtocol.KEY_ELAPSED_MS),
        )
    }

    private fun readTags(
        data: Bundle,
        namesKey: String,
        probsKey: String,
        category: Int,
    ): List<Tag> {
        val names = data.getStringArrayList(namesKey).orEmpty()
        val probs = data.getFloatArray(probsKey) ?: FloatArray(0)
        val count = minOf(names.size, probs.size)
        return (0 until count)
            .map { Tag(names[it], category, probs[it]) }
            .sortedByDescending { it.probability }
    }

    /**
     * Кладёт картинку во временный файл.
     *
     * Не через `Bundle`: JPEG весит мегабайты, а транзакция Binder ограничена
     * примерно 1 МБ, и большая картинка порвала бы связь сама по себе.
     */
    private fun writeTemporary(bytes: ByteArray): String? = runCatching {
        val directory = File(context.cacheDir, "tagger").apply { mkdirs() }
        val file = File(directory, "frame-${tokens.get()}-${bytes.size}.jpg")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    /**
     * Дожидается готовой связи с процессом тегера.
     *
     * Поднятие процесса идёт асинхронно, поэтому здесь мы ждём. Раньше второй
     * вызов, увидев пометку «привязка идёт», сразу получал `null` — то есть
     * два кадра подряд давали ложный отказ, хотя тегер был в порядке. Теперь
     * все вызовы стоят в одной очереди на замке и выходят по первому же
     * готовому соединению.
     */
    private fun ensureBound(): Messenger? {
        if (dead) return null
        remote?.let { return it }

        synchronized(bindLock) {
            if (dead) return null
            remote?.let { return it }

            val intent = Intent(context, TagService::class.java)
            val started = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)

            if (!started) {
                Log.w(TAG, "процесс тегера не поднялся")
                dead = true
                return null
            }

            repeat(BIND_ATTEMPTS) {
                remote?.let { return it }
                if (dead) return null
                Thread.sleep(BIND_POLL_MS)
            }
        }
        return remote
    }

    private fun releasePending(result: Result?) {
        val waiting = pending.values.toList()
        pending.clear()
        mainHandler.post { waiting.forEach { it(result) } }
    }

    companion object {
        private const val TAG = "AnimeTagger"

        /**
         * Первый разбор дольше: модель копируется из assets — 167 МБ. Потом
         * секунды. Две минуты — это уже не «медленно», а «не работает».
         */
        private const val REPLY_TIMEOUT_MS = 120_000L
        private const val BIND_ATTEMPTS = 60
        private const val BIND_POLL_MS = 100L
    }
}