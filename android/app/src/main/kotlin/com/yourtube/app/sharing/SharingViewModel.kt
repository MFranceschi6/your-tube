package com.yourtube.app.sharing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SharingViewModel @Inject constructor(
    private val importer: PlaylistImporter,
) : ViewModel() {

    private val _events = Channel<PlaylistImportResult>(Channel.BUFFERED)
    val events: Flow<PlaylistImportResult> = _events.receiveAsFlow()

    fun import(uri: Uri) {
        viewModelScope.launch { _events.send(importer.import(uri)) }
    }
}
