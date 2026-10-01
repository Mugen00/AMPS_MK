package dev.amps.app.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString

/**
 * AniList and the music sources hand back HTML descriptions. Compose cannot
 * render HTML, so tags are stripped here while paragraph breaks are kept.
 */
fun htmlToAnnotatedString(html: String?): AnnotatedString? {
    if (html.isNullOrBlank()) return null
    val cleaned = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("(?i)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)</?p[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    if (cleaned.isEmpty()) return null

    return buildAnnotatedString {
        cleaned.split("\n").forEachIndexed { index, line ->
            if (index > 0) append("\n")
            append(line.trim())
        }
    }
}

/** Same conversion for one-line contexts such as chips and subtitles. */
fun htmlToPlainText(html: String?): String? =
    htmlToAnnotatedString(html)
        ?.text
        ?.replace(Regex("[ \\t]+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
