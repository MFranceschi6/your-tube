package com.yourtube.app.sharing

import android.content.Context
import android.net.Uri
import com.yourtube.core.common.model.Playlist
import com.yourtube.core.data.codec.PlaylistCodec
import com.yourtube.core.data.codec.PlaylistCodecError
import com.yourtube.core.data.repository.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PlaylistImportResult {
    data class Success(val playlist: Playlist) : PlaylistImportResult
    data object UnsupportedSchema : PlaylistImportResult
    data object InvalidPayload : PlaylistImportResult
    data object Unreadable : PlaylistImportResult
}

@Singleton
class PlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: PlaylistCodec,
    private val repository: PlaylistRepository,
) {
    suspend fun import(uri: Uri): PlaylistImportResult = withContext(Dispatchers.IO) {
        val payload = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return@withContext PlaylistImportResult.Unreadable

        importFromPayload(payload)
    }

    internal suspend fun importFromPayload(payload: String): PlaylistImportResult {
        val playlist = try {
            codec.import(payload)
        } catch (e: PlaylistCodecError.UnsupportedSchemaVersion) {
            return PlaylistImportResult.UnsupportedSchema
        } catch (e: PlaylistCodecError.InvalidPayload) {
            return PlaylistImportResult.InvalidPayload
        }

        repository.importPlaylist(playlist)
        return PlaylistImportResult.Success(playlist)
    }
}
