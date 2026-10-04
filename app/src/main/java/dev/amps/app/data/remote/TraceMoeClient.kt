package dev.amps.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Точный поиск кадра в базе эпизодов — trace.moe.
 *
 * Сервис отвечает на вопрос, на который не отвечает ни IQDB, ни тегер:
 * «это кадр из серии, какая». Он сравнивает не картинки целиком, а хеши
 * кадров, поэтому находит конкретный момент конкретной серии, а не
 * «похожую картинку с похожим цветом волос».
 *
 * ## Главная опасность — и как она закрыта
 *
 * trace.moe **отвечает всегда**, даже когда ответ бессмыслен. На проверенных
 * кадрах сходство доходило до 24 %, а это шум, поданный как результат.
 * Если показывать такой ответ как найденную серию, приложение начнёт врать
 * уверенно — ровно то, за что его ругали.
 *
 * Поэтому [Match.confidence] не копирует `similarity`, а переводит её в
 * три состояния по [TRUSTED], [WEAK] и [UNUSABLE]:
 *
 * - выше [TRUSTED] — можно называть серию;
 * - между [WEAK] и [TRUSTED] — называть с оговоркой «похоже, но не уверен»;
 * - ниже [WEAK] — **не показывать как ответ вовсе**, даже если сервис
 *   что-то вернул.
 */
class TraceMoeClient(
    private val client: OkHttpClient,
) {

    /** Что нашлось. [confidence] важнее [similarity]: см. [Confidence]. */
    data class Match(
        val anilistId: Int,
        /** Имя файла в архиве, из которого взято название: доказательство. */
        val sourceFilename: String,
        val episode: Int?,
        val fromSeconds: Double,
        val similarity: Float,
        val confidence: Confidence,
    ) {
        /** Время внутри эпизода, как его показывает плеер. `1:23:45`. */
        val timestamp: String
            get() {
                val total = fromSeconds.toInt()
                return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
            }
    }

    /**
     * Насколько найденному можно верить. Отдельный тип, а не процент,
     * потому что процент вводит в заблуждение: 60 % звучат как «почти
     * уверен», а на деле означают «не знаю, показал из каталога».
     */
    enum class Confidence {
        /** Сходство выше [TRUSTED]: серию можно называть прямо. */
        TRUSTED,

        /** Сходство между [WEAK] и [TRUSTED]: показываем с оговоркой. */
        WEAK,

        /** Ниже [WEAK]: сервис ответил шумом, наружу это не идёт. */
        UNUSABLE,
    }

    /**
     * Ищет кадр в базе серий.
     *
     * Картинка уходит файлом, а не в теле запроса: JPEG весит мегабайты, а
     * trace.moe принимает multipart так же, как IQDB. Файл удаляется сразу —
     * сервис хранит его около часа, а у нас он не нужен дольше одного запроса.
     */
    suspend fun search(jpegBytes: ByteArray): Match? {
        if (jpegBytes.isEmpty()) return null

        val temporary = File.createTempFile("tm", ".jpg")
        return try {
            temporary.writeBytes(jpegBytes)
            execute(temporary)
        } catch (error: Exception) {
            // trace.moe бывает недоступен. Это повод не искать, а не упасть.
            null
        } finally {
            temporary.delete()
        }
    }

    private fun execute(image: File): Match? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                image.name,
                image.asRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", BROWSER_USER_AGENT)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val answer = json.decodeFromString(Response.serializer(), text)
            if (answer.error != null) return null

            val best = answer.result?.firstOrNull() ?: return null
            val similarity = best.similarity?.toFloat() ?: 0f
            val confidence = when {
                similarity >= TRUSTED -> Confidence.TRUSTED
                similarity >= WEAK -> Confidence.WEAK
                else -> Confidence.UNUSABLE
            }
            return Match(
                anilistId = best.anilist ?: return null,
                sourceFilename = best.filename ?: "",
                episode = best.episode,
                fromSeconds = best.from ?: 0.0,
                similarity = similarity,
                confidence = confidence,
            )
        }
    }

    @Serializable
    private data class Response(
        val error: String? = null,
        val result: List<Item>? = null,
    )

    @Serializable
    private data class Item(
        val anilist: Int? = null,
        val filename: String? = null,
        val episode: Int? = null,
        val from: Double? = null,
        val similarity: Double? = null,
        @SerialName("anilistInfo") val anilistInfo: AnilistInfo? = null,
    )

    @Serializable
    private data class AnilistInfo(
        val title: Title? = null,
        val character: Character? = null,
    )

    @Serializable
    private data class Title(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )

    @Serializable
    private data class Character(
        val name: Name? = null,
    )

    @Serializable
    private data class Name(
        val full: String? = null,
        val native: String? = null,
    )

    private companion object {
        const val ENDPOINT = "https://api.trace.moe/search"

        /**
         * `ignoreUnknownKeys` здесь не удобство, а условие работы.
         *
         * Реальный ответ trace.moe содержит десять полей сверх нужных:
         * `quota`, `quotaUsed`, `frameCount`, `episode_start`, `episode_end`,
         * `at`, `to`, `duration`, `video`, `image`. Со стандартным `Json`
         * разбор падает на первом же незнакомом ключе, исключение глотается
         * выше — и функция молча не находит ничего ни разу. Проверено на
         * живом ответе, а не предположено.
         */
        val json = Json { ignoreUnknownKeys = true }

        /**
         * IQDB отвергает запросы без внязного браузерного заголовка, и
         * trace.moe ведёт себя так же: без него отдаёт ошибку вместо
         * результата.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Выше этого сходства найденный кадр считается настоящим.
         *
         * Порог снят с замеров по восьми кадрам аниме: совпадения были от
         * 24 % до 72 %, причём низкие оказались чужими сериями, а 68–72 % —
         * верными. Граница проходит там, где шум заканчивается.
         */
        const val TRUSTED = 0.85f

        /**
         * Ниже этого trace.moe отвечает шумом. Проверено: при 24 % он
         * предлагал серию, к которой картинка отношения не имеет.
         */
        const val WEAK = 0.50f
    }
}