package com.mocharealm.foundation.audio

enum class PlayMode(internal val nativeValue: Int) {
    PLAY_ONCE(0),
    REPEAT_QUEUE(1),
    REPEAT_ONE(2),
    SHUFFLE(3),
}
