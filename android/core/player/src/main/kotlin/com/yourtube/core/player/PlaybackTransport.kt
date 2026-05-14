package com.yourtube.core.player

sealed interface PlaybackResult {
    data object Success : PlaybackResult
    data class Failure(val message: String) : PlaybackResult
}

/**
 * Sink for `Player.Listener` events forwarded from the underlying media transport
 * (typically a `MediaController` bound to the playback service). The controller
 * implements this to reconcile its `PlayerState` with player-side events that
 * originate outside in-app interactions — auto-advance on track end (YT-0182),
 * lock-screen / notification pause-resume sync (YT-0185), and (when multi-item
 * queueing lands) lock-screen skip-next/prev (YT-0150).
 *
 * All callbacks fire on the main thread; implementations must dispatch back onto
 * their controller scope and must be idempotent — re-firing with the same value
 * is a no-op for callers, since in-app writes will trigger the same callback as
 * a feedback echo.
 */
interface PlaybackTransportListener {
    /** Player reached `STATE_ENDED` for the currently-queued media item. */
    fun onTrackEnded()

    /** `Player.isPlaying` flipped to [isPlaying]; mirrors `Player.Listener.onIsPlayingChanged`. */
    fun onIsPlayingChanged(isPlaying: Boolean)

    /**
     * YT-0150 — the underlying player swapped its current `MediaItem`. The transport
     * forwards `MediaItem.mediaId` (= `Track.videoId` per the [PlaybackPlayerAdapter]
     * contract) so the controller can resync its in-memory queue index without trusting
     * the controller-side timeline (which can lag re-orders).
     *
     * Transports filter by transition reason and only forward `REASON_AUTO`,
     * `REASON_SEEK`, and `REASON_REPEAT`. `REASON_PLAYLIST_CHANGED` is suppressed
     * because it echoes back from in-app `setMediaItem(...)` writes that already
     * mutated `PlayerState` synchronously.
     *
     * Default body: most listeners (e.g. the unit-test fakes that pre-date this
     * extension) do not need to react.
     */
    fun onMediaItemTransition(mediaId: String?) = Unit

    /**
     * YT-0150 — the underlying player's playback position jumped due to an
     * external seek (lock-screen slider, system shell, future Auto / Wear
     * surfaces). The transport forwards `newPosition.positionMs` so the
     * controller can resync `PlayerState.positionMs` with the engine.
     *
     * Transports filter by discontinuity reason and only forward
     * `DISCONTINUITY_REASON_SEEK` and `DISCONTINUITY_REASON_SEEK_ADJUSTMENT`.
     * Auto-transition / internal reasons are suppressed — they are already
     * handled by [onMediaItemTransition] / engine-internal bookkeeping and
     * re-applying here would clobber freshly-written state.
     *
     * Implementations must be idempotent for the current [PlayerState.positionMs]
     * so the feedback echo from in-app `seekTo(...)` is a no-op.
     *
     * Default body: most listeners (e.g. the unit-test fakes that pre-date this
     * extension) do not need to react.
     */
    fun onPositionChanged(positionMs: Long) = Unit

    /**
     * YT-0244 — the underlying player flipped between `STATE_BUFFERING` (true) and
     * `STATE_READY` (false). Distinguishing buffering from a user-driven pause lets the
     * controller surface a loading spinner during in-track seeks + re-buffers without
     * misclassifying them as PAUSED.
     *
     * Deliberately separate from [onIsPlayingChanged]: at the player level the two events
     * describe different concerns — `onIsPlayingChanged` reflects whether audio output is
     * gated (user pause, audio-focus loss) and `onPlaybackStateChanged(STATE_BUFFERING)`
     * reflects whether the engine has enough data to play. YT-0185 / YT-0150 already rely
     * on the existing `onIsPlayingChanged` semantics, so do NOT collapse the callbacks.
     *
     * Default body: pre-existing fakes / call sites that do not need to react keep
     * compiling with no source changes.
     */
    fun onBufferingStateChanged(isBuffering: Boolean) = Unit

    /**
     * YT-0309 round-5 — engine dropped to `Player.STATE_IDLE` AFTER having held a playable
     * MediaItem (the buffer drained, the network died mid-stream, or a hard error untracked
     * by `STATE_ENDED` made the engine give up). The controller must treat this as
     * "engineLoaded is now false" so the next `resume()` re-enters the `playQueueItem` path
     * (and its 15s `withTimeoutOrNull` ceiling) rather than calling `playbackTransport.resume()`
     * against a now-empty engine — which is the bug that left the spinner stuck during the
     * airplane-mode round-5 smoke.
     *
     * Pre-prepare IDLE is filtered upstream in the transport (a `@Volatile` "has ever been
     * non-IDLE" guard) so this callback only fires for real "engine gave up after running"
     * transitions, not for cold-start / release transitions.
     *
     * Default body: pre-existing fakes / call sites that do not need to react keep compiling.
     */
    fun onEngineUnloaded() = Unit
}

interface PlaybackTransport {
    /**
     * Register (or clear when null) a callback sink for player-side events. The
     * controller calls this once at construction; the transport keeps a single
     * listener slot to avoid duplicate registrations across MediaController
     * rebuilds.
     */
    fun setListener(listener: PlaybackTransportListener?)

    suspend fun playTrack(request: PlaybackRequest): PlaybackResult

    suspend fun pause()

    suspend fun resume()

    suspend fun seekTo(positionMs: Long)

    /**
     * Halt any currently-playing audio and reset playback position to 0 BEFORE a
     * new stream URL has been resolved (YT-0050).
     *
     * Called by [PlayerController] the moment the user requests a new track so the
     * previous audio falls silent and the position/duration counters reset
     * immediately, instead of leaking through the several hundred milliseconds it
     * can take to resolve a new YouTube stream URL.
     *
     * Implementations MUST pause the player and seek to 0. They MUST NOT clear the
     * underlying timeline (YT-0238): dropping `mediaItemCount` to 0 tears down the
     * foreground notification (lock-screen card flicker) and drives the player
     * through `STATE_ENDED`, which feeds back into auto-advance and cascades
     * `skipNext` to the last queue item. The follow-up [playTrack] call replaces
     * the current media item in place once the stream URL is resolved.
     */
    suspend fun stopAndClearCurrent()

    /**
     * Forwards persistent shuffle to the underlying player (`Player.shuffleModeEnabled`).
     * No-op (or best-effort) when no player is attached; the [PlayerController] keeps its
     * own state-flow in lockstep so the UI reflects intent regardless.
     */
    suspend fun setShuffleMode(enabled: Boolean)

    /**
     * Forwards persistent repeat mode to the underlying player (`Player.repeatMode`). Modes
     * map to `androidx.media3.common.Player` constants (`REPEAT_MODE_OFF` = 0,
     * `REPEAT_MODE_ONE` = 1, `REPEAT_MODE_ALL` = 2).
     */
    suspend fun setRepeatMode(mode: Int)

    /**
     * Applies [speed] to the underlying player's `PlaybackParameters`. Values outside the
     * 0.5–2.0 range are silently clamped by ExoPlayer; callers should validate before calling.
     * No-op default so pre-existing fakes and test doubles compile without changes.
     */
    suspend fun setPlaybackSpeed(speed: Float) = Unit
}
