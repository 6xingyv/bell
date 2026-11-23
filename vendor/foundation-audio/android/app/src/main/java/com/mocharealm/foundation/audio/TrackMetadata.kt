package com.mocharealm.foundation.audio

/** Metadata for the active track. Missing numeric tags are represented by 0. */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val lyrics: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
)
