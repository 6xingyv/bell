// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.*
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MusicOutputMenu(
    model: BellStore,
    state: BellState,
    modifier: Modifier,
    centerHorizontally: Boolean,
    onGroups: () -> Unit,
) {
    MusicMenu(
        listOf(
            MusicMenuAction(stringResource(Res.string.listen_together), "shareplay") { onGroups() }
        ),
        modifier,
        MusicMenuStyle.Player,
        "airplay",
        stringResource(Res.string.audio_output),
    )
}
