package com.yourtube.core.data.codec

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import java.io.BufferedReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExportImportTest {

    private val codec: PlaylistCodec = KotlinxPlaylistCodec()

    @Test
    fun `import reads the canonical shared fixture`() {
        val imported = codec.`import`(fixture("playlist-valid-v1.ytplaylist.json"))

        assertEquals(
            Playlist(
                id = "00000000-0000-4000-8000-000000000001",
                name = "MVP Round Trip",
                createdAt = "2026-05-02T09:00:00Z",
                updatedAt = "2026-05-02T09:30:00Z",
                tracks = listOf(
                    Track(
                        videoId = "dQw4w9WgXcQ",
                        title = "Fixture Track One",
                        channel = "Fixture Channel",
                        durationSec = 213,
                        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
                    ),
                    Track(
                        videoId = "jfKfPfyJRdk",
                        title = "Fixture Track Two",
                        channel = "Fixture Channel",
                        durationSec = 0,
                        thumbnailUrl = "https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg",
                    ),
                ),
            ),
            imported,
        )
    }

    @Test
    fun `export round-trips the canonical fixture without changing order or metadata`() {
        val imported = codec.`import`(fixture("playlist-valid-v1.ytplaylist.json"))

        val exported = codec.export(imported)

        assertEquals(
            fixture("playlist-valid-v1.ytplaylist.json").trim(),
            exported.trim(),
        )
    }

    @Test
    fun `import rejects unsupported future schema versions with a user safe error`() {
        val error = assertFailsWith<PlaylistCodecError.UnsupportedSchemaVersion> {
            codec.`import`(fixture("playlist-future-schema.ytplaylist.json"))
        }

        assertEquals(999, error.found)
        assertEquals(1, error.supported)
        assertContains(error.message, "newer app version")
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing fixture resource: $name"
        }.bufferedReader().use(BufferedReader::readText)
}
