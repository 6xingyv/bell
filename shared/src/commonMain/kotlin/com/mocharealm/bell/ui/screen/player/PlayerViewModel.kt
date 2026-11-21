// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player

import com.mocharealm.bell.domain.model.AudioSnapshot
import com.mocharealm.bell.domain.model.Item
import com.mocharealm.bell.domain.model.LyricDocument
import com.mocharealm.bell.domain.model.PlaybackHistoryEntry
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import com.mocharealm.bell.ui.mvi.ScreenViewModel

data class PlayerState(
    val current: Item?,
    val queue: List<Item>,
    val queueIndex: Int,
    val audio: AudioSnapshot,
    val lyrics: LyricDocument,
    val shuffle: Boolean,
    val repeat: Boolean,
    val repeatOne: Boolean,
    val buffering: Boolean,
    val history: List<PlaybackHistoryEntry>,
)

enum class PlayerPanel {
    Cover,
    Lyrics,
    Queue,
}

enum class QueueConfirmation {
    History,
    Queue,
}

data class PlayerPresentation(
    val panel: PlayerPanel = PlayerPanel.Cover,
    val controlsVisible: Boolean = true,
    val queueDragging: Boolean = false,
    val queueConfirmation: QueueConfirmation? = null,
)

sealed interface PlayerIntent {
    data class QueueDragging(val active: Boolean) : PlayerIntent

    data class ConfirmClear(val kind: QueueConfirmation) : PlayerIntent

    data object DismissClear : PlayerIntent

    data object ClearConfirmed : PlayerIntent

    data class MoveQueue(val fromKey: Long, val toKey: Long) : PlayerIntent

    data class PlayHistory(val key: Long) : PlayerIntent

    data object AutoPlay : PlayerIntent

    data object AutoMix : PlayerIntent

    data class SelectPanel(val panel: PlayerPanel) : PlayerIntent

    data object RevealControls : PlayerIntent

    data object HideControls : PlayerIntent

    data object ToggleControls : PlayerIntent

    data class Play(val items: List<Item>, val index: Int = 0, val positionMs: Long = 0) :
        PlayerIntent

    data object Toggle : PlayerIntent

    data class Seek(val ms: Long) : PlayerIntent

    data class Next(val automatic: Boolean = false) : PlayerIntent

    data object Previous : PlayerIntent

    data object Shuffle : PlayerIntent

    data object Repeat : PlayerIntent

    data class Remove(val index: Int) : PlayerIntent

    data class Enqueue(val item: Item) : PlayerIntent
}

class PlayerViewModel(private val store: BellStore) :
    ScreenViewModel<PlayerState, PlayerIntent>(
        store,
        { s ->
            PlayerState(
                s.current,
                s.queue,
                s.queueIndex,
                s.audio,
                s.lyrics,
                s.shuffle,
                s.repeat,
                s.repeatOne,
                s.buffering || s.audio.buffering,
                s.history,
            )
        },
    ) {
    private val presentationMutable = kotlinx.coroutines.flow.MutableStateFlow(PlayerPresentation())
    val presentation: kotlinx.coroutines.flow.StateFlow<PlayerPresentation> = presentationMutable

    override fun accept(intent: PlayerIntent) {
        val current = presentationMutable.value
        when (intent) {
            is PlayerIntent.QueueDragging -> {
                presentationMutable.value = current.copy(queueDragging = intent.active)
                return
            }
            is PlayerIntent.ConfirmClear -> {
                presentationMutable.value = current.copy(queueConfirmation = intent.kind)
                return
            }
            PlayerIntent.DismissClear -> {
                presentationMutable.value = current.copy(queueConfirmation = null)
                return
            }
            PlayerIntent.ClearConfirmed -> {
                current.queueConfirmation?.let {
                    store.accept(
                        if (it == QueueConfirmation.History) BellIntent.ClearHistory
                        else BellIntent.ClearQueue
                    )
                }
                presentationMutable.value = current.copy(queueConfirmation = null)
                return
            }
            is PlayerIntent.SelectPanel -> {
                val nextPanel =
                    if (current.panel == intent.panel) PlayerPanel.Cover else intent.panel
                if (nextPanel == PlayerPanel.Lyrics) store.ensureLyricsForCurrent()
                presentationMutable.value =
                    current.copy(
                        panel = nextPanel,
                        controlsVisible = true,
                    )
                return
            }
            PlayerIntent.RevealControls -> {
                presentationMutable.value = current.copy(controlsVisible = true)
                return
            }
            PlayerIntent.HideControls -> {
                presentationMutable.value = current.copy(controlsVisible = false)
                return
            }
            PlayerIntent.ToggleControls -> {
                presentationMutable.value = current.copy(controlsVisible = !current.controlsVisible)
                return
            }
            else -> Unit
        }
        store.accept(
            when (intent) {
                is PlayerIntent.MoveQueue -> BellIntent.MoveQueue(intent.fromKey, intent.toKey)
                is PlayerIntent.PlayHistory -> BellIntent.PlayHistory(intent.key)
                PlayerIntent.AutoPlay -> BellIntent.AutoPlay
                PlayerIntent.AutoMix -> BellIntent.AutoMix
                is PlayerIntent.Play ->
                    BellIntent.Play(intent.items, intent.index, intent.positionMs)
                PlayerIntent.Toggle -> BellIntent.Toggle
                is PlayerIntent.Seek -> BellIntent.Seek(intent.ms)
                is PlayerIntent.Next -> BellIntent.Next(intent.automatic)
                PlayerIntent.Previous -> BellIntent.Previous
                PlayerIntent.Shuffle -> BellIntent.Shuffle
                PlayerIntent.Repeat -> BellIntent.Repeat
                is PlayerIntent.Remove -> BellIntent.Remove(intent.index)
                is PlayerIntent.Enqueue -> BellIntent.Enqueue(intent.item)
                else -> return
            }
        )
    }
}
