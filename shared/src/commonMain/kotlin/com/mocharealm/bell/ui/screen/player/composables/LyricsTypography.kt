// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.screen.player.composables

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.*
import com.mocharealm.bell.ui.utils.MusicLineHeightStyle
import com.mocharealm.bell.ui.utils.musicPlatformTextStyle
import com.mocharealm.bell.ui.utils.musicPlus

internal data class MusicLyricsTypography(
    val normal: TextStyle,
    val accompaniment: TextStyle,
    val translation: TextStyle,
    val phonetic: TextStyle,
)

@Composable
internal fun rememberMusicLyricsTypography(): MusicLyricsTypography {
    val base = LocalTextStyle.current
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    return remember(base, family) {
        fun style(size: Int, weight: FontWeight, motion: TextMotion = TextMotion.Animated) =
            base.copy(
                fontFamily = family,
                fontSize = size.sp,
                lineHeight = TextUnit.Unspecified,
                fontWeight = weight,
                textMotion = motion,
                platformStyle = musicPlatformTextStyle(),
                lineHeightStyle = MusicLineHeightStyle,
            )
        MusicLyricsTypography(
            style(34, FontWeight.Bold),
            style(20, FontWeight.Bold),
            style(18, FontWeight.Bold),
            style(18, FontWeight.SemiBold, TextMotion.Static),
        )
    }
}

@Composable
internal fun MusicStaticLyricText(text: String, style: TextStyle, alpha: Float = 1f) {
    androidx.compose.material3.Text(
        text,
        style = style,
        color = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha),
        modifier =
            if (alpha < 1f) androidx.compose.ui.Modifier.musicPlus()
            else androidx.compose.ui.Modifier,
    )
}
