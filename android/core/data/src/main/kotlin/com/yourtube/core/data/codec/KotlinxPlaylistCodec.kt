package com.yourtube.core.data.codec

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class KotlinxPlaylistCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    },
) : PlaylistCodec {

    override fun export(playlist: Playlist): String =
        json.encodeToString(
            PlaylistPayload(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                id = playlist.id,
                name = playlist.name,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                tracks = playlist.tracks.map { track -> track.toPayload() },
            ),
        )

    override fun `import`(payload: String): Playlist =
        try {
            val decoded = json.decodeFromString<PlaylistPayload>(payload)
            if (decoded.schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw PlaylistCodecError.UnsupportedSchemaVersion(
                    found = decoded.schemaVersion,
                    supported = CURRENT_SCHEMA_VERSION,
                )
            }

            Playlist(
                id = decoded.id,
                name = decoded.name,
                createdAt = decoded.createdAt,
                updatedAt = decoded.updatedAt,
                tracks = decoded.tracks.map { track -> track.toDomain() },
            )
        } catch (error: PlaylistCodecError) {
            throw error
        } catch (error: SerializationException) {
            throw PlaylistCodecError.InvalidPayload(error)
        } catch (error: IllegalArgumentException) {
            throw PlaylistCodecError.InvalidPayload(error)
        }

    private fun Track.toPayload(): TrackPayload =
        TrackPayload(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )

    private fun TrackPayload.toDomain(): Track =
        Track(
            videoId = videoId,
            title = title,
            channel = channel,
            durationSec = durationSec,
            thumbnailUrl = thumbnailUrl,
        )

    @Serializable
    private data class PlaylistPayload(
        val schemaVersion: Int,
        val id: String,
        val name: String,
        val createdAt: String,
        val updatedAt: String,
        val tracks: List<TrackPayload>,
    )

    @Serializable
    private data class TrackPayload(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Int,
        val thumbnailUrl: String,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
