package dev.amps.app.update

import android.content.Context
import dev.amps.app.util.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** GitHub rejects API calls without a User-Agent outright. */
internal const val UPDATE_USER_AGENT = "AMPS/1.0 (android)"

/** The documented media type of the releases API. */
internal const val GITHUB_JSON_ACCEPT = "application/vnd.github+json"

/** Public repository, so no token is needed. */
internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/Mugen00/AMPS_MK/releases/latest"

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 20L

private const val UNKNOWN_VERSION = "0.0.0"

/**
 * One OkHttp client for both the check and the APK download.
 *
 * 15 s connect / 20 s read is enough for a JSON body and for an APK that keeps
 * streaming; a stalled read fails with a readable Russian message instead of
 * hanging the screen forever.
 */
internal fun updateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Asks GitHub whether a newer AMPS exists — the F-Droid pattern, minus the
 * repository index.
 *
 * The endpoint is `releases/latest` of the public repo, so the answer is a
 * single small JSON document and no token is involved. Everything that can go
 * wrong (no releases yet, rate limit, HTML instead of JSON, a release without
 * an APK, no network) is raised as an [IOException] whose message is already
 * written for the user: callers run it through [readableMessage] and never
 * show a stack trace.
 */
class UpdateChecker(
    private val context: Context,
    private val client: OkHttpClient = updateHttpClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `versionName` of the installed build, straight from the PackageManager —
     * `"1.0.1"`, `"1.0.1-debug"` for a debug install.
     */
    fun currentVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: UNKNOWN_VERSION

    /**
     * Compares the published release against the installed `versionName`.
     *
     * @throws IOException with a Russian, user-facing message.
     */
    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val currentRaw = currentVersion()
        val current = parseVersion(currentRaw) ?: AppVersion(0, 0, 0)

        val release = readLatestRelease()

        val tag = release.tagName.trim()
        if (tag.isEmpty()) throw IOException("GitHub не указал номер версии релиза")

        val remote = parseVersion(tag)
            ?: throw IOException("Не удалось разобрать номер вер релиза: $tag")

        // Nothing newer: an older release without an APK is not a problem.
        if (remote <= current) {
            return@withContext UpdateCheck.UpToDate(current = currentRaw, latest = remote.label)
        }

        val asset = release.pickApkAsset()
            ?: throw IOException("В релизе $tag нет APK-файла")

        UpdateCheck.Available(release.toRelease(version = remote, asset = asset))
    }

    /** One GET, mapped onto the three documented failures plus everything else. */
    private fun readLatestRelease(): GitHubReleaseDto {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", GITHUB_JSON_ACCEPT)
            .header("User-Agent", UPDATE_USER_AGENT)
            .get()
            .build()

        val payload = try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> throw IOException("На GitHub пока нет ни одного релиза")
                    403, 429 -> throw IOException("GitHub ограничил число запросов — попробуйте через несколько минут")
                }
                if (!response.isSuccessful) {
                    throw IOException("GitHub ответил ошибкой HTTP ${response.code}")
                }
                response.body?.string() ?: throw IOException("GitHub прислал пустой ответ")
            }
        } catch (error: IOException) {
            // Re-wrap so every network hiccup reaches the UI in one language.
            throw IOException(error.readableNetworkMessage(), error)
        }

        return try {
            json.decodeFromString<GitHubReleaseDto>(payload)
        } catch (error: Exception) {
            throw IOException("GitHub прислал ответ, который не удалось разобрать", error)
        }
    }

    /** OkHttp reports offline and timeouts in English; the screen speaks Russian. */
    private fun IOException.readableNetworkMessage(): String = when {
        this is UnknownHostException -> "Нет связи с интернетом: не найден api.github.com"
        this is SocketTimeoutException -> "GitHub не ответил вовремя — попробуйте позже"
        this is InterruptedIOException -> "Загрузка прервана — попробуйте ещё раз"
        message?.contains("Failed to connect", ignoreCase = true) == true ->
            "Нет связи с интернетом — проверьте подключение"
        // Russian messages thrown above land here and stay as they are.
        else -> readableMessage()
    }
}
