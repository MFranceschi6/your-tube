package com.yourtube.core.common.model

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val tracks: List<Track>,
)
