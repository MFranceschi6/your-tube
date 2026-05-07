package com.yourtube.core.data.codec

sealed class PlaylistCodecError(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    data class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Int,
    ) : PlaylistCodecError(
        message = "This playlist file needs a newer app version before it can be imported.",
    )

    data class InvalidPayload(
        override val cause: Throwable,
    ) : PlaylistCodecError(
        message = "This playlist file could not be read.",
        cause = cause,
    )
}
