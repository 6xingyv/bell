// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.model

import kotlinx.serialization.Serializable

/** Identity belongs to a playback occurrence, so repeated songs remain distinct. */
@Serializable data class PlaybackHistoryEntry(val key: Long, val item: Item)
