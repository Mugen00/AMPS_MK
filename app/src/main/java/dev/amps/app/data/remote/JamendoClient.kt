package dev.amps.app.data.remote

import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicSource
import dev.amps.app.data.model.musicDedupeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Jamendo — единственный проверенный источник, который отдаёт **полный трек**
 * вместе с открытой лицензией.
 *
 * Именно здесь, а не в iTunes или Deezer, лежит разница между «можно положить
 * файл в музыкальную библиотеку телефона» и «нельзя». iTunes и Deezer дают
 * 30-секундные превью коммерческих записей: их можно послушать, но сохранять
 * их как трек — нарушение лицензии. У Jamendo лицензия CC стоит рядом с файлом,
 * и импорт законен.
 *
 * **Ключ нужен только для поиска.** Сам CDN отдаёт файл по
 * `prod-1.storage.jamendo.com/?trackid=…&format=mp32` без всякой аутентификации,
 * поэтому даже без client_id пользователь может импортировать трек, который уже
 * нашёлся, — просто не найдёт новых.
 */
class JamendoClient(
    private val client: OkHttpClient,
    private val clientIdProvider: suspend () -> String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Serializable
    private data class ApiResponse(
        val results: List<Row> = emptyList(),
        val headers: Headers? = null,
    )

    @Serializable
    private data class Headers(
        val status: String? = null,
        val code: Int? = null,
        val errorMessage: String? = null,
    )

    @Serializable
    private data class Row(
        val id: String = "",
        val name: String = "",
        val duration: Int = 0,
        @kotlinx.serialization.SerialName("musicproducer_credit_url") val producerUrl: String? = null,
        @kotlinx.serialization.SerialName("audio_url") val audioUrl: String? = null,
        @kotlinx.serialization.SerialName("license_ccurl") val licenseUrl: String? = null,
        @kotlinx.serialization.SerialName("license_ccversion") val licenseVersion: String? = null,
        @kotlinx.serialization.SerialName("album_image") val albumImage: String? = null,
        @kotlinx.serialization.SerialName("releasedate") val releaseDate: String? = null,
        val artistId: String? = null,
        val artistName: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val sharesone: Int? = null,
        val tags: List<String> = emptyList(),
    )

    /**
     * @return пустой список, если client_id не задан или Jamendo его отверг —
     * это «источник недоступен», а не «треков нет», и вызывающий обязан отличать
     * одно от другого сам, потому что «ничего не нашлось» звучит для
     * пользователя как «этой музыки не существует».
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
        audioFormat: String = "mp32",
    ): FreeTrackSearchResult = withContext(Dispatchers.IO) {
        val clientId = clientIdProvider()?.trim().orEmpty()
        if (clientId.isEmpty()) {
            return@withContext FreeTrackSearchResult(emptyList(), "Задайте client_id Jamendo в настройках — без него поиск недоступен")
        }

        val url = SEARCH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("search", query.trim())
            .addQueryParameter("audioformat", audioFormat)
            .addQueryParameter("include", "musicinfo+licenses")
            .build()

        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext FreeTrackSearchResult(
                    emptyList(),
                    "Jamendo ответил ${response.code}",
                )
            }
            val parsed = runCatching { json.decodeFromString<ApiResponse>(text) }.getOrNull()
                ?: return@withContext FreeTrackSearchResult(emptyList(), "Ответ Jamendo не разобрался")
            parsed.headers?.let { headers ->
                // Jamendo сообщает об ошибке HTTP 200 кодом в теле — из-за
                // неверного client_id, например. Без этой проверки пустой
                // список выглядел бы как «музыки такого жанра нет».
                if (headers.status != null && headers.status != "success") {
                    return@withContext FreeTrackSearchResult(
                        emptyList(),
                        "Jamendo: ${headers.errorMessage ?: "запрос отклонён"}",
                    )
                }
            }
            FreeTrackSearchResult(parsed.results.mapNotNull { it.toTrack() })
        }
    }

    private fun Row.toTrack(): FreeTrack? {
        val trackId = id.trim()
        if (trackId.isEmpty()) return null
        // URL строим сами: в ответе audio_url может быть пустым, а файл на CDN
        // лежит по предсказуемому адресу и отдаётся без ключа.
        val fileUrl = audioUrl?.takeIf { it.isNotBlank() }
            ?: "https://prod-1.storage.jamendo.com/?trackid=$trackId&format=mp32"
        return FreeTrack(
            source = MusicSource.JAMENDO,
            sourceId = trackId,
            title = name.ifBlank { "Без названия" },
            artistName = artistName,
            album = albumName,
            year = releaseDate?.take(4)?.toIntOrNull(),
            license = licenseFrom(licenseUrl, licenseVersion),
            audioUrl = fileUrl,
            pageUrl = "https://www.jamendo.com/track/$trackId",
            authorUrl = producerUrl,
            coverUrl = albumImage?.let { url ->
                // Размер приходит в конце пути; 100×100 для карточки телефона мало.
                url.replace("/100x100/", "/600x600/")
            },
            format = AudioFormat(
                durationSec = duration.takeIf { it > 0 },
                mimeType = "audio/mpeg",
            ),
            tags = tags,
        )
    }

    /** Имя лицензии выводится из её URL — так вернее, чем разбирать версию. */
    private fun licenseFrom(url: String?, version: String?): MusicLicense {
        val href = url?.trim().orEmpty()
        if (href.isEmpty()) return MusicLicense.UNKNOWN
        val name = when {
            "/by-nc-nd/" in href -> "CC BY-NC-ND"
            "/by-nc-sa/" in href -> "CC BY-NC-SA"
            "/by-nc/" in href -> "CC BY-NC"
            "/by-nd/" in href -> "CC BY-ND"
            "/by-sa/" in href -> "CC BY-SA"
            "/by/" in href -> "CC BY"
            "/publicdomain/" in href || "/zero/" in href -> "Public Domain"
            else -> return MusicLicense.UNKNOWN
        }
        return MusicLicense(
            name = if (version.isNullOrBlank()) name else "$name $version",
            url = href,
        )
    }

    /** Ответ поиска вместе с причиной пустого списка — она почти всегда полезна. */
    data class FreeTrackSearchResult(
        val tracks: List<FreeTrack>,
        val note: String? = null,
    ) {
        val dedupeKeys: List<String> get() = tracks.map { it.dedupeKey }
    }

    companion object {
        private const val SEARCH_ENDPOINT = "https://api.jamendo.com/v3.0/tracks/"
        private const val USER_AGENT = "AMPS/1.0.3 (android)"

        /** Аудиохаш для поиска дублей при импорте. */
        fun dedupeKey(track: FreeTrack): String = musicDedupeKey(track.artistName, track.title)
    }
}
