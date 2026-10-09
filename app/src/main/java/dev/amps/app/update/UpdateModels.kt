package dev.amps.app.update

import dev.amps.app.util.formatBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A file attached to a GitHub release. Only the fields the update flow reads. */
@Serializable
internal data class GitHubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String? = null,
)

/**
 * `GET /repos/{owner}/{repo}/releases/latest`.
 *
 * Every field carries a default, so a release that GitHub trims (an empty
 * `body`, no assets at all) still decodes instead of throwing.
 */
@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

/** The `-release.apk` asset wins; any other `.apk` is the fallback. */
internal const val RELEASE_APK_SUFFIX = "-release.apk"
internal const val APK_SUFFIX = ".apk"

/** The installable file of a release. */
data class UpdateApk(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val contentType: String?,
) {
    /** `6.72 МБ` / null when the asset did not announce a size. */
    val sizeLabel: String? get() = formatBytes(sizeBytes)
}

/**
 * `1.0.1` as three numbers.
 *
 * The comparison is semantic only: `1.0.2` is newer than `1.0.1`, and the app's
 * `versionCode` is never consulted — a release can legitimately ship the same
 * versionCode as the installed build.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /**
     * Буквенный суффикс патча: `1.0.3a` — это `1.0.3` плюс `a`.
     *
     * Собственные версии Android такие (`versionName` = `1.0.3a`) допускает, и
     * это естественный способ пометить патч к конкретной версии. Но молча
     * отбрасывать букву нельзя: `1.0.3a` превратился бы в `1.0.0`, и обновление
     * до него никогда не дошло бы — приложение решило бы, что у пользователя
     * уже новее. Такое молчаливое искажение хуже явной ошибки.
     */
    val suffix: String = "",
) : Comparable<AppVersion> {

    /** Порядок суффикса: `a` = 1, `b` = 2 … Для сравнения версий. */
    val suffixRank: Int
        get() = if (suffix.isEmpty()) {
            0
        } else if (suffix.length == 1) {
            (suffix[0].lowercaseChar() - 'a' + 1).coerceAtLeast(0)
        } else {
            26 + suffix.lowercase().sumOf { it - 'a' + 1 }
        }

    val label: String
        get() = "$major.$minor.$patch" + suffix

    /** True only when this version is strictly greater than [other]. */
    fun isNewerThan(other: AppVersion): Boolean = this > other

    override fun compareTo(other: AppVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        patch != other.patch -> patch.compareTo(other.patch)
        else -> suffixRank.compareTo(other.suffixRank)
    }

    override fun toString(): String = label
}

/** `v1.0.1`, `1.0.1`, `1.0.1-debug`, `1.2`, `1.0.3a` → [AppVersion]. */
private val VERSION_PATTERN = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?([a-z]{1,2})?$""", RegexOption.IGNORE_CASE)

/**
 * Разбирает номер версии в [AppVersion], либо возвращает `null`, если номер
 * не распознан.
 *
 * `null` — это важный результат: он означает «я не понял, что здесь написано»,
 * и позволяет показать пользователю честную ошибку. Превращать непонятное в
 * `0.0.0` нельзя — так ломается всё сравнение версий.
 *
 * Поддерживаются: ведущий `v`, буквенный суффикс патча (`1.0.3a`) и
 * `-debug`-хвост у debug-сборки, который отбрасывается.
 */
fun parseVersion(raw: String?): AppVersion? {
    val cleaned = raw?.trim().orEmpty()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .substringBefore(' ')
        .trim()
    if (cleaned.isEmpty()) return null

    val match = VERSION_PATTERN.matchEntire(cleaned) ?: return null
    val (majorRaw, minorRaw, patchRaw, suffix) = match.destructured
    val major = majorRaw.toIntOrNull() ?: return null
    // Ранний возврат для «1» и «1.0»: у них суффикса быть не может.
    if (minorRaw.isEmpty()) return AppVersion(major, 0, 0)
    val minor = minorRaw.toIntOrNull() ?: return null
    if (patchRaw.isEmpty()) return AppVersion(major, minor, 0)
    val patch = patchRaw.toIntOrNull() ?: return null
    return AppVersion(major, minor, patch, suffix.lowercase())
}

/** A published release that carries an installable APK. */
data class UpdateRelease(
    val tag: String,
    val title: String,
    val notes: String?,
    val pageUrl: String?,
    val publishedAt: String?,
    val version: AppVersion,
    val apk: UpdateApk,
    /**
     * Номер збирання патча з тіла релізу (`build-code: 21`).
     * Для патчів, що не міняють versionName: саме він вирішує, пропонувати
     * оновлення, коли версія та сама. null — реліз без мітки (старі релізи).
     */
    val buildCode: Long? = null,
) {
    val versionLabel: String get() = version.label

    /** e.g. `6.72 МБ`. */
    val sizeLabel: String? get() = apk.sizeLabel

    /** `2026-01-31` out of `2026-01-31T18:04:11Z`. */
    val publishedDate: String?
        get() = publishedAt?.substringBefore('T')?.takeIf { it.length >= 10 }
}

/** Result of one check against the GitHub API. */
sealed interface UpdateCheck {
    /** Nothing newer than [current] is published. */
    data class UpToDate(val current: String, val latest: String) : UpdateCheck

    /** [release] is strictly newer than the installed build. */
    data class Available(val release: UpdateRelease) : UpdateCheck
}

/** `-release.apk` first, then the first plain `.apk`, then null. */
internal fun GitHubReleaseDto.pickApkAsset(): GitHubAssetDto? =
    assets.firstOrNull { it.name.endsWith(RELEASE_APK_SUFFIX, ignoreCase = true) }
        ?: assets.firstOrNull { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }

/**
 * Номер патча з тіла релізу: рядок `build-code: 21` (або `[build-code]: 21`).
 * Реліз без мітки — null: порівняння тоді падає назад на versionName.
 */
internal fun GitHubReleaseDto.patchBuildCode(): Long? =
    body?.let { text ->
        Regex("""build-code\]?:?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

/** Maps a decoded release plus its chosen asset onto the domain model. */
internal fun GitHubReleaseDto.toRelease(version: AppVersion, asset: GitHubAssetDto): UpdateRelease =
    UpdateRelease(
        tag = tagName.trim(),
        title = name?.trim()?.takeIf { it.isNotEmpty() } ?: tagName.trim(),
        notes = body?.takeIf { it.isNotBlank() },
        pageUrl = htmlUrl?.takeIf { it.isNotBlank() },
        publishedAt = publishedAt?.takeIf { it.isNotBlank() },
        version = version,
        apk = UpdateApk(
            name = asset.name,
            url = asset.browserDownloadUrl,
            sizeBytes = asset.size,
            contentType = asset.contentType,
        ),
        buildCode = patchBuildCode(),
    )
