package dev.kagami.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A small hand-tuned palette. Material 3 roles are filled from these so the
 * wiki page keeps one consistent identity in both light and dark mode.
 */
object KagamiColors {
    val violet = Color(0xFF7C5CFF)
    val violetDeep = Color(0xFF4B2ED6)
    val cyan = Color(0xFF3ED6D0)
    val amber = Color(0xFFFFB454)
    val rose = Color(0xFFFF5470)

    /** Alias used by the music screens for licence/warning states. */
    val danger = rose
    val ink = Color(0xFF0B0B14)
    val inkSoft = Color(0xFF15151F)
    val surfaceGlass = Color(0xCC15151F)
    val onSurfaceVariant = Color(0xFFB9B4CC)
}
