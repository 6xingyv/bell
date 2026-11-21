// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.*
import com.mocharealm.bell.ui.utils.LocalMusicPlayerPlus
import com.mocharealm.bell.ui.utils.musicPlus
import java.io.File

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

    val font = remember {
        FontFamily(
            Font(
                File(
                    "E:/mocha/accompanist/lyrics-ui/sample/shared/src/commonMain/composeResources/font/sf_pro.ttf"
                )
            )
        )
    }
    val glyph = musicSymbolCodePoint(name)?.let { String(Character.toChars(it)) }.orEmpty()
    Box(
        modifier
            .then(
                if (LocalMusicPlayerPlus.current && color.alpha < 1f) Modifier.musicPlus()
                else Modifier
            )
            .size(size)
            .clearAndSetSemantics { description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = color,
            style =
                TextStyle(
                    fontFamily = font,
                    fontSize = size.value.sp,
                    fontFeatureSettings = "'ss16' 1",
                ),
            maxLines = 1,
        )
    }
}
