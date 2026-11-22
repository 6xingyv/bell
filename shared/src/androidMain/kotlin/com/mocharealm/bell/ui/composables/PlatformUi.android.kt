// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.mocharealm.bell.ui.utils.musicTypography
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.ui.*

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
actual fun BellTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val face =
        remember(context) {
            android.graphics.Typeface.createFromAsset(context.assets, "sf_pro.ttf")
        }
    val family =
        remember(context) {
            androidx.compose.ui.text.font.FontFamily(
                listOf(200, 300, 400, 500, 600, 700, 800).map { value ->
                    val weight = androidx.compose.ui.text.font.FontWeight(value)
                    androidx.compose.ui.text.font.Font(
                        path = "sf_pro.ttf",
                        assetManager = context.assets,
                        weight = weight,
                        variationSettings =
                            androidx.compose.ui.text.font.FontVariation.Settings(
                                androidx.compose.ui.text.font.FontVariation.weight(value)
                            ),
                    )
                }
            )
        }
    val dark = isSystemInDarkTheme()
    GlasmioTheme(
        isDark = dark,
        typography = glasmioTypography(family).musicTypography(family),
        colorScheme = glasmioColorScheme(dark).copy(primary = MusicAccent),
        colors = defaultGlassColors(dark, MusicAccent),
    ) {
        CompositionLocalProvider(LocalMusicDark provides dark) {
            GlasmioIconProvider(symbolTypeface = face) {
                GlasmioBackdropHost(
                    sampledColor = LocalGlassColors.current.groupedBackground,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content()
                }
            }
        }
    }
}

actual fun artworkShape(): androidx.compose.ui.graphics.Shape = ContinuousRoundedRectangle(16.dp)

@Composable
actual fun BellBackHandler(enabled: Boolean, back: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, back)
}

@Composable
actual fun BellPage(title: String, back: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    GlasmioPinnedListPage(
        title,
        bottomPadding = if (LocalBellFullscreen.current) 32.dp else 210.dp,
        onNavigateBack = back,
        content = content,
    )
}

@Composable
actual fun BellButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    GlasmioButton(onClick, modifier, enabled = enabled) { androidx.compose.material3.Text(label) }
}

@Composable
actual fun BellField(
    value: String,
    change: (String) -> Unit,
    hint: String,
    secret: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onDone: (() -> Unit)?,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    GlasmioTextField(
        value,
        change,
        modifier = Modifier.onFocusChanged { onFocusChanged(it.isFocused) },
        placeholder = hint,
        keyboardOptions =
            if (onDone != null) KeyboardOptions(imeAction = ImeAction.Done)
            else KeyboardOptions.Default,
        keyboardActions =
            KeyboardActions(
                onDone = {
                    onDone?.invoke()
                    if (onDone != null) keyboard?.hide()
                }
            ),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
actual fun BellSlider(
    value: Float,
    change: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    finished: () -> Unit,
) {
    GlasmioSlider(
        value,
        change,
        Modifier.fillMaxWidth(),
        valueRange = range,
        onValueChangeFinished = finished,
    )
}

@Composable
actual fun BellGroup(content: @Composable ColumnScope.() -> Unit) {
    GlasmioGroupedList(content = content)
}

@Composable
actual fun BellRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    showTopSeparator: Boolean,
    enabled: Boolean,
) {
    GlasmioListRow(
        title,
        subtitle = subtitle,
        onClick = onClick,
        leading = leading,
        trailing = trailing,
        showTopSeparator = showTopSeparator,
        enabled = enabled,
    )
}

@Composable
actual fun BellFormSurface(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = defaultGlassColors(dark, MusicAccent)
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalMusicDark provides dark,
        androidx.compose.material3.LocalContentColor provides colors.content,
    ) {
        androidx.compose.material3.Surface(
            color = colors.groupedBackground,
            contentColor = colors.content,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

@Composable
actual fun MusicSearchFieldGroup(content: @Composable () -> Unit) {
    val colors = defaultGlassColors(LocalMusicDark.current, MusicAccent)
    CompositionLocalProvider(
        LocalGlassColors provides colors.copy(elevatedBackground = colors.groupedBackground),
        content = content,
    )
}
