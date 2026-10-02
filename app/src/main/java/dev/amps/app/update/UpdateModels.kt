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
) : Comparable<AppVersion> {

    val label: String get() = "$major.$minor.$patch"

    /** True only when this version is strictly greater than [other]. */
    fun isNewerThan(other: AppVersion): Boolean = this > other

    override fun compareTo(other: AppVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun toString(): String = label
}

/**
 * Parses `"v1.0.1"`, `"1.0.1"`, `"1.0.1-debug"`, `"1.2"` into an [AppVersion].
 *
 * A leading `v` (either case) is dropped, as is a `-suffix` / `+suffix` build
 * qualifier, and missing components default to zero. Returns null when the
 * major component is missing or not a number, so callers can tell a broken tag
 * from a valid one.
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

    val parts = cleaned.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return AppVersion(major, minor, patch)
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
    )
