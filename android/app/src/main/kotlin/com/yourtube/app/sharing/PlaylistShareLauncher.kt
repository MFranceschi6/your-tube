package com.yourtube.app.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.yourtube.app.R
import java.io.File

object PlaylistShareLauncher {

    private const val MIME_JSON = "application/json"
    private const val SHARE_DIR = "shares"

    /**
     * Writes the encoded [payload] for [playlistName] to a private cache file and starts
     * a system chooser via `ACTION_SEND`. The receiving app gets a temporary read grant
     * on the FileProvider URI; no broad storage permission is needed.
     */
    fun share(context: Context, playlistName: String, payload: String) {
        val file = writeShareFile(context, playlistName, payload)
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_JSON
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.share_playlist_subject, playlistName),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(
            send,
            context.getString(R.string.share_playlist_chooser_title),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        context.startActivity(chooser)
    }

    internal fun writeShareFile(context: Context, playlistName: String, payload: String): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val safe = playlistName.toSafeFileName().ifBlank { "playlist" }
        val file = File(dir, "$safe.ytplaylist.json")
        file.writeText(payload)
        return file
    }

    internal fun String.toSafeFileName(): String =
        trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
}
