package com.yourtube.core.common.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExtractorErrorTest {

    @Test
    fun `BotChallenge is an ExtractorError`() {
        val error: ExtractorError = ExtractorError.BotChallenge
        assertIs<ExtractorError>(error)
        assertIs<ExtractorError.BotChallenge>(error)
    }

    @Test
    fun `VideoUnavailable carries videoId`() {
        val error: ExtractorError = ExtractorError.VideoUnavailable(videoId = "abc123")
        assertIs<ExtractorError.VideoUnavailable>(error)
        assertEquals("abc123", error.videoId)
    }

    @Test
    fun `NetworkError carries cause`() {
        val cause = RuntimeException("timeout")
        val error: ExtractorError = ExtractorError.NetworkError(cause = cause)
        assertIs<ExtractorError.NetworkError>(error)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `Unknown carries cause`() {
        val cause = IllegalStateException("unexpected")
        val error: ExtractorError = ExtractorError.Unknown(cause = cause)
        assertIs<ExtractorError.Unknown>(error)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `all subtypes are distinct`() {
        val errors: List<ExtractorError> = listOf(
            ExtractorError.BotChallenge,
            ExtractorError.VideoUnavailable("v1"),
            ExtractorError.NetworkError(RuntimeException()),
            ExtractorError.Unknown(RuntimeException()),
        )
        assertEquals(4, errors.size)
    }
}
