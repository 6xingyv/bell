package com.mocharealm.foundation.audio

data class PlaybackStatus(
    val currentMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
    val speed: Float,
    val playbackGeneration: Long,
)
