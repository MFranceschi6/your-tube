package com.yourtube.core.data.codec

import com.yourtube.core.common.model.Playlist

interface PlaylistCodec {
    fun export(playlist: Playlist): String
    fun `import`(payload: String): Playlist
}
