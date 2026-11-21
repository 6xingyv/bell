// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.musicPlus
import io.github.neoruaa.glasmio.icons.SfIcon

@Composable
actual fun MusicIcon(
    name: String,
    color: Color,
    size: Dp,
    description: String?,
    modifier: Modifier,
) {
    if (name == "automix") {
        androidx.compose.foundation.layout.Box(
            modifier.then(androidx.compose.ui.Modifier.size(size))
        ) {
            MusicIcon(
                "circle.lefthalf.filled",
                color,
                size * .66f,
                modifier =
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterStart),
            )
            MusicIcon(
                "circle.righthalf.filled",
                color,
                size * .66f,
                modifier =
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            )
        }
        return
    }

    SfIcon(
        musicSymbolName(name),
        description,
        modifier =
            modifier.then(
                if (LocalMusicPlayerPlus.current && color.alpha < 1f) Modifier.musicPlus()
                else Modifier
            ),
        tint = color,
        size = size,
        mirrored = name == "back" || name == "chevron",
    )
}
