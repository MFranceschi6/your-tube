package com.yourtube.core.player

interface PlaybackEngine {
    fun queue(preparedPlayback: PreparedPlayback)
    fun prepare()
    fun play()
}
