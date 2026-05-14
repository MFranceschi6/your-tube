package com.yourtube.core.common.model

/**
 * YT-0093 — Sleep timer domain types. Kept in `core:common` so both `:core:player`
 * (implementation) and `:core:ui` (composable) can reference them without creating a
 * cross-module dependency between player and UI.
 */
sealed interface SleepTimerState {
    data object Inactive : SleepTimerState

    data class Active(
        val remainingMs: Long,
        val preset: SleepTimerPreset,
    ) : SleepTimerState
}

enum class SleepTimerPreset(val durationMs: Long) {
    Min15(15 * 60 * 1_000L),
    Min30(30 * 60 * 1_000L),
    Min60(60 * 60 * 1_000L),
    /** Pause at the end of the current track (video-ID change or queue auto-advance). */
    EndOfTrack(0L),
}
