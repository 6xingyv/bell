// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListeningGroup(
    @SerialName("GroupId") val id: String,
    @SerialName("GroupName") val name: String = "",
    @SerialName("Participants") val participants: List<String> = emptyList(),
)

@Serializable
data class ListeningEntry(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaylistItemId") val id: String,
    @SerialName("AddedByUserId") val userId: String? = null,
    @SerialName("AddedByUserName") val userName: String? = null,
)

data class ListeningState(
    val group: ListeningGroup? = null,
    val groups: List<ListeningGroup> = emptyList(),
    val members: List<User> = emptyList(),
    val entries: List<ListeningEntry> = emptyList(),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: String = "RepeatNone",
) {
    val active
        get() = group != null
}
