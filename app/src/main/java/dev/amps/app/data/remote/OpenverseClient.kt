package dev.amps.app.data.remote

import dev.amps.app.data.model.AudioFormat
import dev.amps.app.data.model.FreeTrack
import dev.amps.app.data.model.MusicLicense
import dev.amps.app.data.model.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * 1.1.1: Openverse — офіційний агрегатор відкритих аудіо.
 *
 * Один клієнт покриває одразу кілька бібліотек, які перелічені в правилах
 * проєкту: Free Music Archive, freesound, Wikimedia тощо. Кожен запис несе
 * ліцензію Creative Commons або позначку суспільного надбання, і разом з ним
 * — пряме посилання на файл. Ключ не потрібен: анонімний доступ дозволений,
 * сторінка обмежена 20 записами, і цього вистачає на один пошук.
 *
 * **Чесні межі анонімного режиму.** Без реєстрації Openverse обмежує частоту
 * запитів, і той, хто шукає без перерв, побачить банер «Openverse не
 * відповів» — інші джерела при цьому працюють, бо пошук не тримається за
 * одне джерело. Ключ реєстрації безкоштовний, і окремого поля під нього у
 * налаштуваннях поки немає: анонімного режиму вистачає для перевірки, чи
 * джерело приживеться.
 *
 * **Чому Jamendo відсікається.** Прямий клієнт Jamendo вбудований у
 * застосунок з версії 1.0.3, і та сама знахідка через агрегатора була б не
 * другим джерелом, а дублем того самого рядка. Решта провайдерів
 * залишаються.
 */
class OpenverseClient(
    private val client: OkHttpClient,
) {

    /** Пошук треків із відкритою ліцензією. Порожній запит не ходить у мережу. */
    suspend fun search(query: String, limit: Int): List<FreeTrack> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("page_size", limit.coerceIn(1, MAX_PAGE).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AMPS_USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            json.decodeFromString(OpenversePage.serializer(), body)
                .results
                .mapNotNull { it.toFreeTrack() }
        }
    }

    // --- відповідь API ------------------------------------------------------

    /** Кожен запис Openverse несе усе, чого вимагає [FreeTrack]: файл і ліцензію. */
    private fun OpenverseAudio.toFreeTrack(): FreeTrack? {
        val fileUrl = url?.takeIf { it.isNotBlank() } ?: return null
        if (provider.equals("jamendo", ignoreCase = true)) return null
        return FreeTrack(
            source = MusicSource.OPENVERSE,
            sourceId = id,
            title = title,
            artistName = creator?.takeIf { it.isNotBlank() },
            license = licenseOf(license, licenseVersion, licenseUrl),
            audioUrl = fileUrl,
            pageUrl = foreignLandingUrl,
            authorUrl = creatorUrl,
            format = AudioFormat(
                durationSec = duration?.let { (it / 1000L).toInt() },
                fileBytes = filesize,
                mimeType = mimeOf(filetype),
            ),
        )
    }

    /**
     * Код ліцензії (`by`, `by-nc-sa`, `cc0`, `pdm`) зводиться до чотирьох
     * ознак, якими користуються фільтр і бейдж. Ліцензія без посилання
     * вважається невідомою — це той самий принцип, що й в усіх інших
     * джерелах: «не вказана» означає «закрита».
     */
    private fun licenseOf(code: String, version: String?, url: String?): MusicLicense {
        if (code.isBlank() || url.isNullOrBlank()) return MusicLicense.UNKNOWN
        val normalized = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        val name = when (normalized) {
            "cc0" -> "CC0"
            "pdm" -> "Public Domain Mark"
            else -> "CC ${normalized.uppercase(Locale.ROOT)}"
        } + (version?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())
        return MusicLicense(
            name = name,
            url = url,
            requiresAttribution = normalized.startsWith("by"),
            nonCommercial = normalized.contains("nc"),
            shareAlike = normalized.contains("sa"),
            noDerivatives = normalized.contains("nd"),
        )
    }

    private fun mimeOf(filetype: String?): String? = when (filetype?.lowercase(Locale.ROOT)) {
        "mp3", "mp32" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        else -> null
    }

    @Serializable
    private data class OpenversePage(
        val results: List<OpenverseAudio> = emptyList(),
    )

    /**
     * Поля, яких тут немає (tags, alt_files, waveform тощо), API повертає
     * теж — вони відкидаються `ignoreUnknownKeys`.
     */
    @Serializable
    private data class OpenverseAudio(
        val id: String = "",
        val title: String = "",
        /** Пряме посилання на файл; у частини записів його немає — такі пропускаються. */
        val url: String? = null,
        @SerialName("foreign_landing_url") val foreignLandingUrl: String? = null,
        val creator: String? = null,
        @SerialName("creator_url") val creatorUrl: String? = null,
        val license: String = "",
        @SerialName("license_version") val licenseVersion: String? = null,
        @SerialName("license_url") val licenseUrl: String? = null,
        val filesize: Long? = null,
        val filetype: String? = null,
        /** Мілісекунди — Openverse віддає тривалість саме в них. */
        val duration: Long? = null,
        val provider: String? = null,
    )

    private companion object {
        const val ENDPOINT = "https://api.openverse.org/v1/audio/"

        /** Анонімний доступ не віддає більше 20 записів на сторінку. */
        const val MAX_PAGE = 20

        val json = Json { ignoreUnknownKeys = true }
    }
}
