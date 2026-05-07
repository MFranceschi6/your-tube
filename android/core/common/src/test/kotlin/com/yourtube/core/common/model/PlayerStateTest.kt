package com.yourtube.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlayerStateTest {

    @Test
    fun `default state has sensible initial values`() {
        val state = PlayerState()
        assertNull(state.currentTrack)
        assertEquals(emptyList(), state.queue)
        assertEquals(-1, state.currentQueueIndex)
        assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertNull(state.errorMessage)
    }

    @Test
    fun `copy with changed isPlaying reflects new value`() {
        val state = PlayerState().copy(isPlaying = true)
        assertEquals(true, state.isPlaying)
    }

    @Test
    fun `equality holds for identical states`() {
        val state = PlayerState()
        assertEquals(state, state.copy())
    }
}
