package com.yourtube.core.data.codec

import com.yourtube.core.common.model.Playlist
import com.yourtube.core.common.model.Track
import java.io.BufferedReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-platform parity coverage for YT-0034.
 *
 * Pairs with `ios/YourTubeTests/CrossPlatformParityTests.swift`. Both tests share the
 * canonical fixtures under `docs/fixtures/` and the canonical domain payload below.
 *
 * What each side proves:
 *  - Android decodes the iOS-shaped export (`playlist-ios-export-canonical.ytplaylist.json`)
 *    into the same [Playlist] the iOS test asserts on.
 *  - Re-exporting that [Playlist] through [KotlinxPlaylistCodec] produces the committed
 *    Android-canonical fixture byte-for-byte (after a `trim` to absorb trailing newlines).
 *  - Both clients reject `playlist-future-schema.ytplaylist.json` with their documented
 *    unsupported-schema-version error.
 *
 * The fixtures stay byte-deterministic: no clocks, no UUID generation, no environment data.
 *
 * Note: this test must NOT change codec output. If it ever fails because the encoded bytes
 * drift, file a follow-up task — the parity test exists to expose drift, not to paper over it.
 */
class CrossPlatformParityTest {

    private val codec: PlaylistCodec = KotlinxPlaylistCodec()

    @Test
    fun `decodes the iOS canonical export into the shared domain payload`() {
        val decoded = codec.`import`(fixture(IOS_CANONICAL_FIXTURE))

        assertEquals(canonicalDomainPayload, decoded)
    }

    @Test
    fun `re-exports the canonical domain payload as the Android canonical fixture bytes`() {
        // Anchor the canonical Android-side bytes against the codec output so cross-
        // platform fixtures stay in lock-step with the codec we ship.
        val exported = codec.export(canonicalDomainPayload)

        assertEquals(
            fixture(ANDROID_CANONICAL_FIXTURE).trim(),
            exported.trim(),
        )
    }

    @Test
    fun `rejects the future-schema fixture with UnsupportedSchemaVersion`() {
        val error = assertFailsWith<PlaylistCodecError.UnsupportedSchemaVersion> {
            codec.`import`(fixture(FUTURE_SCHEMA_FIXTURE))
        }

        assertEquals(999, error.found)
        assertEquals(1, error.supported)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing fixture resource: $name (did docs/fixtures wiring break in build.gradle.kts?)"
        }.bufferedReader().use(BufferedReader::readText)

    private companion object {
        const val IOS_CANONICAL_FIXTURE = "playlist-ios-export-canonical.ytplaylist.json"
        const val ANDROID_CANONICAL_FIXTURE = "playlist-android-export-canonical.ytplaylist.json"
        const val FUTURE_SCHEMA_FIXTURE = "playlist-future-schema.ytplaylist.json"

        val canonicalDomainPayload = Playlist(
            id = "00000000-0000-4000-8000-000000000010",
            name = "Cross Platform Round Trip",
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
        )
    }
}
