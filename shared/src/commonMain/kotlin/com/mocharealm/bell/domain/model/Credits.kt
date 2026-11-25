// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreditDocument(
    @SerialName("SongId") val songId: String = "",
    @SerialName("Groups") val groups: List<CreditGroup> = emptyList(),
)

@Serializable
data class CreditGroup(
    @SerialName("Kind") val kind: String = "other",
    @SerialName("Title") val title: String = "",
    @SerialName("People") val people: List<CreditArtist> = emptyList(),
)

@Serializable
data class CreditArtist(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Roles") val roles: List<String> = emptyList(),
    @SerialName("ArtworkUrl") val artworkUrl: String? = null,
)
