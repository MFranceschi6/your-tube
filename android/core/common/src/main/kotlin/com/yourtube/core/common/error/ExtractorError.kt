package com.yourtube.core.common.error

sealed class ExtractorError : Exception() {
    data object BotChallenge : ExtractorError()
    data class VideoUnavailable(val videoId: String) : ExtractorError()
    data class NetworkError(override val cause: Throwable) : ExtractorError()
    data class Unknown(override val cause: Throwable) : ExtractorError()
}
