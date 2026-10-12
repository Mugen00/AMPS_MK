package dev.amps.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.amps.app.data.remote.anilist.AniListClient
import dev.amps.app.data.remote.backend.ApiResult
import dev.amps.app.data.remote.backend.BackendApi
import dev.amps.app.data.remote.backend.BackendSession
import dev.amps.app.data.remote.backend.dto.AnimeListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 1.2.2: нагадування про нові серії аніме — раз на 6 годин.
 *
 * Читає аніме-список акаунта з бекенду, бере тайтли з увімкненим
 * нагадуванням, питає в AniList nextAiringEpisode і показує сповіщення,
 * коли серія виходить протягом доби. Серії, про які вже повідомлено,
 * запам'ятовуються — повторного сповіщення по тій самій серії не буде.
 *
 * Локальний опитувач без WorkManager: живе у скоупі контейнера, як
 * [CommunityNotifier]. Без сесії або без дозволу на сповіщення — мовчить.
 */
class AnimeAiringNotifier(
    private val context: Context,
    private val backendApi: BackendApi,
    private val backendSession: BackendSession,
    private val anilist: AniListClient,
    private val notifier: AmpsNotifier,
) {

    private object Keys {
        /** JSON-мапа anilistId → номер серії, про яку вже повідомлено. */
        val notified = stringPreferencesKey("anime_notified_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    private val intervalMillis = 6L * 60 * 60 * 1000
    private val firstCheckDelayMillis = 90L * 1000

    private val Context.animeDataStore by preferencesDataStore(name = "amps_anime")
    private val store = context.animeDataStore

    /** Запускає цикл нагадувань; повторний виклик не робить нічого. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(firstCheckDelayMillis)
            while (true) {
                runCatching { check() }
                delay(intervalMillis)
            }
        }
    }

    /** Один прохід; кожна помилка — тихо і до наступної перевірки. */
    private suspend fun check() {
        val me = backendSession.current() ?: return
        val listResult = backendSession.authedCall { header -> backendApi.getAnimeList(header) }
        val list = (listResult as? ApiResult.Success)?.data as? AnimeListResponse ?: return
        val watching = list.items.filter { it.reminder }
        if (watching.isEmpty()) return

        val notified: Map<Int, Int> = store.data
            .first()
            .let { prefs -> prefs[Keys.notified] }
            ?.let { runCatching { json.decodeFromString<Map<Int, Int>>(it) }.getOrNull() }
            ?: emptyMap()

        val nowSec = System.currentTimeMillis() / 1000
        val window = 24L * 3600
        val lines = mutableListOf<String>()
        val updated = HashMap<Int, Int>()
        for (item in watching) {
            val next = anilist.nextAiring(item.anilistId) ?: continue
            val (episode, airingAtSec) = next
            val already = notified[item.anilistId] ?: -1
            val within24h = airingAtSec in nowSec..(nowSec + window)
            val isFresh = episode > already
            if (isFresh && within24h) {
                val whenText = if (airingAtSec <= nowSec + 3600) {
                    Loc.translate("сьогодні")
                } else {
                    val hours = (airingAtSec - nowSec) / 3600
                    if (hours < 48) Loc.translate("через ") + hours + Loc.translate(" год") else Loc.translate("незабаром")
                }
                lines += item.title + ": " + Loc.translate("серія ") + episode + " — " + whenText
                updated[item.anilistId] = episode
            }
        }
        if (lines.isNotEmpty()) {
            notifier.notifyAnimeEpisode(
                Loc.translate("Аніме"),
                lines.take(3).joinToString("\n"),
            )
        }
        // Оновлюємо пам'ять лише по серіях, що справді повідомлені.
        if (updated.isNotEmpty()) {
            val merged = HashMap(notified)
            merged.putAll(updated)
            store.edit { it[Keys.notified] = json.encodeToString(merged) }
        }
    }
}
