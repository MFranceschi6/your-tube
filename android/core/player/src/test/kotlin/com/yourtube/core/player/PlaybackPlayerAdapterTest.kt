package com.yourtube.core.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import com.yourtube.core.common.model.Track
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the HLS routing branch added in YT-0163 (gating item #3 of round 2
 * review). The adapter must call `setMediaSource` with an `HlsMediaSource` for
 * livestream URLs (container == "hls") and `setMediaItem` for everything else.
 *
 * Asserts via a hand-rolled `MediaItemQueueing` fake — matches the
 * existing-pattern fakes (FakePlaybackTransport, RecordingPlaybackEngine) used
 * elsewhere in `core/player/src/test/`. Uses `mockk` only for the abstract
 * `MediaSource` surface that the HLS factory returns; everything else is a
 * hand-rolled fake. Robolectric is required because `MediaItem.Builder` walks
 * `android.net.Uri` / `android.os.Bundle` static initializers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackPlayerAdapterTest {

    @Test
    fun `livestream container routes through HlsMediaSource setMediaSource path`() {
        val fakeSource = mockk<MediaSource>(relaxed = true)
        val capturedFactoryInputs = mutableListOf<MediaItem>()
        val adapter = PlaybackPlayerAdapter(
            hlsMediaSourceFactory = { mediaItem ->
                capturedFactoryInputs += mediaItem
                fakeSource
            },
        )
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.queue(
            PreparedPlayback(
                track = LIVESTREAM_TRACK,
                streamUrl = "https://manifest.googlevideo.com/hls/manifest.m3u8",
                bitrateKbps = 0,
                codec = null,
                container = "hls",
            ),
        )

        assertEquals(
            1,
            queueing.setMediaSourceCalls.size,
            "Expected exactly one setMediaSource call, got ${queueing.setMediaSourceCalls.size}",
        )
        assertSame(
            fakeSource,
            queueing.setMediaSourceCalls.single(),
            "Adapter must hand the HLS-built MediaSource straight to the player.",
        )
        assertTrue(
            queueing.setMediaItemCalls.isEmpty(),
            "setMediaItem must NOT be called for HLS playback. Got: ${queueing.setMediaItemCalls}",
        )
        // Assert the HLS factory ran exactly once with the videoId we built
        // the MediaItem from. We deliberately avoid asserting the parsed URI
        // here: with `isReturnDefaultValues = true`, `android.net.Uri.parse`
        // returns the default mock value; the URL routing is the load-bearing
        // assertion above (setMediaSource called, setMediaItem not called).
        assertEquals(1, capturedFactoryInputs.size)
        assertEquals("live456", capturedFactoryInputs.single().mediaId)
    }

    @Test
    fun `livestream container is matched case-insensitively`() {
        // Defensive — InnerTube layer emits lowercase "hls" today, but the
        // adapter does `equals(..., ignoreCase = true)`. Lock that so a future
        // refactor doesn't regress it silently.
        val fakeSource = mockk<MediaSource>(relaxed = true)
        val adapter = PlaybackPlayerAdapter(hlsMediaSourceFactory = { fakeSource })
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.queue(
            PreparedPlayback(
                track = LIVESTREAM_TRACK,
                streamUrl = "https://manifest.googlevideo.com/hls/manifest.m3u8",
                bitrateKbps = 0,
                codec = null,
                container = "HLS",
            ),
        )

        assertEquals(1, queueing.setMediaSourceCalls.size)
        assertTrue(queueing.setMediaItemCalls.isEmpty())
    }

    @Test
    fun `m4a container routes through setMediaItem and never builds an HLS source`() {
        var hlsFactoryInvocations = 0
        val adapter = PlaybackPlayerAdapter(
            hlsMediaSourceFactory = { _ ->
                hlsFactoryInvocations++
                mockk<MediaSource>(relaxed = true)
            },
        )
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.queue(
            PreparedPlayback(
                track = AUDIO_TRACK,
                streamUrl = "https://cdn.example.com/audio.m4a",
                bitrateKbps = 128,
                codec = "mp4a.40.2",
                container = "m4a",
            ),
        )

        assertEquals(0, hlsFactoryInvocations, "HLS factory must NOT run for m4a.")
        assertEquals(1, queueing.setMediaItemCalls.size)
        assertTrue(
            queueing.setMediaSourceCalls.isEmpty(),
            "setMediaSource must NOT be called for non-HLS playback.",
        )
        val mediaItem = queueing.setMediaItemCalls.single()
        // mediaId proxies for "the right MediaItem reached the player";
        // localConfiguration?.uri is unstable under unit-test Uri stubbing.
        assertEquals("alpha123", mediaItem.mediaId)
    }

    @Test
    fun `webm container routes through setMediaItem (opus fallback path)`() {
        // Android-specific divergence from iOS — opus/webm is a valid audio
        // fallback. It must NOT take the HLS branch.
        val adapter = PlaybackPlayerAdapter(hlsMediaSourceFactory = { error("HLS factory must not run") })
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.queue(
            PreparedPlayback(
                track = AUDIO_TRACK,
                streamUrl = "https://cdn.example.com/audio.webm",
                bitrateKbps = 160,
                codec = "opus",
                container = "webm",
            ),
        )

        assertEquals(1, queueing.setMediaItemCalls.size)
        assertTrue(queueing.setMediaSourceCalls.isEmpty())
    }

    @Test
    fun `null container does not match HLS branch`() {
        val adapter = PlaybackPlayerAdapter(hlsMediaSourceFactory = { error("HLS factory must not run") })
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.queue(
            PreparedPlayback(
                track = AUDIO_TRACK,
                streamUrl = "https://cdn.example.com/unknown",
                bitrateKbps = 128,
                codec = null,
                container = null,
            ),
        )

        assertEquals(1, queueing.setMediaItemCalls.size)
        assertTrue(queueing.setMediaSourceCalls.isEmpty())
    }

    @Test
    fun `prepare and play forward to the attached queueing`() {
        val adapter = PlaybackPlayerAdapter(hlsMediaSourceFactory = { mockk(relaxed = true) })
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)

        adapter.prepare()
        adapter.play()

        assertEquals(1, queueing.prepareCalls)
        assertEquals(1, queueing.playCalls)
    }

    @Test
    fun `detach clears the attached queueing`() {
        val adapter = PlaybackPlayerAdapter(hlsMediaSourceFactory = { mockk(relaxed = true) })
        val queueing = RecordingMediaItemQueueing()
        adapter.attach(queueing)
        adapter.detach()

        // After detach, calls fail with the requireNotNull message.
        val error = kotlin.runCatching { adapter.play() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "Expected detached failure, got $error")
    }

    private class RecordingMediaItemQueueing : MediaItemQueueing {
        val setMediaItemCalls = mutableListOf<MediaItem>()
        val setMediaSourceCalls = mutableListOf<MediaSource>()
        var prepareCalls: Int = 0
            private set
        var playCalls: Int = 0
            private set

        override fun setMediaItem(mediaItem: MediaItem) {
            setMediaItemCalls += mediaItem
        }

        override fun setMediaSource(mediaSource: MediaSource) {
            setMediaSourceCalls += mediaSource
        }

        override fun prepare() {
            prepareCalls += 1
        }

        override fun play() {
            playCalls += 1
        }
    }

    companion object {
        private val AUDIO_TRACK = Track(
            videoId = "alpha123",
            title = "Audio Track",
            channel = "Audio Channel",
            durationSec = 180,
            thumbnailUrl = "",
        )
        private val LIVESTREAM_TRACK = Track(
            videoId = "live456",
            title = "Live Stream",
            channel = "Live Channel",
            durationSec = 0,
            thumbnailUrl = "",
        )
    }
}
