package com.yourtube.app.sharing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistShareLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sharesDir: File get() = File(context.cacheDir, "shares")

    @Test
    fun `writeShareFile writes payload under cacheDir shares with sanitized name`() {
        val file = PlaylistShareLauncher.writeShareFile(
            context = context,
            playlistName = "My / Crazy: Playlist *?",
            payload = "{\"hello\":\"world\"}",
        )

        assertEquals(sharesDir.canonicalPath, file.parentFile?.canonicalPath)
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".ytplaylist.json"))
        // No path separators or shell-unsafe chars in the file name.
        assertTrue(file.name.matches(Regex("[A-Za-z0-9._-]+\\.ytplaylist\\.json")))
        assertEquals("{\"hello\":\"world\"}", file.readText())
    }

    @Test
    fun `writeShareFile collapses runs of unsafe characters into single underscore`() {
        val file = PlaylistShareLauncher.writeShareFile(
            context = context,
            playlistName = "weird   ///   name",
            payload = "{}",
        )

        // Multiple unsafe chars (spaces and slashes) should collapse to one underscore each run.
        assertEquals("weird_name.ytplaylist.json", file.name)
    }

    @Test
    fun `writeShareFile falls back to playlist when name has no safe chars`() {
        val file = PlaylistShareLauncher.writeShareFile(
            context = context,
            playlistName = "   ",
            payload = "{}",
        )

        assertEquals("playlist.ytplaylist.json", file.name)
        assertEquals(sharesDir.canonicalPath, file.parentFile?.canonicalPath)
    }

    @Test
    fun `writeShareFile truncates very long names to 60 characters`() {
        val long = "a".repeat(200)

        val file = PlaylistShareLauncher.writeShareFile(
            context = context,
            playlistName = long,
            payload = "{}",
        )

        // Stem (before extension) must be at most 60 chars per the sanitizer.
        val stem = file.name.removeSuffix(".ytplaylist.json")
        assertTrue(stem.length <= 60, "stem was ${stem.length} chars: $stem")
    }

    @Test
    fun `writeShareFile creates shares directory when missing`() {
        sharesDir.deleteRecursively()
        assertTrue(!sharesDir.exists())

        PlaylistShareLauncher.writeShareFile(
            context = context,
            playlistName = "first",
            payload = "{}",
        )

        assertTrue(sharesDir.exists() && sharesDir.isDirectory)
    }
}
