// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.composables

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.mocharealm.bell.resources.*
import com.mocharealm.bell.ui.utils.musicTypography
import org.jetbrains.compose.resources.stringResource

// Desktop host follows the sample's shared/desktop split. Android uses upstream Glasmio itself.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
actual fun BellTheme(content: @Composable () -> Unit) {
    val family =
        androidx.compose.runtime.remember {
            val file =
                java.io.File(
                    "E:/mocha/accompanist/lyrics-ui/sample/shared/src/commonMain/composeResources/font/sf_pro.ttf"
                )
            androidx.compose.ui.text.font.FontFamily(
                listOf(200, 300, 400, 500, 600, 700, 800).map { value ->
                    val weight = androidx.compose.ui.text.font.FontWeight(value)
                    androidx.compose.ui.text.platform.Font(
                        file = file,
                        weight = weight,
                        variationSettings =
                            androidx.compose.ui.text.font.FontVariation.Settings(
                                androidx.compose.ui.text.font.FontVariation.weight(value)
                            ),
                    )
                }
            )
        }
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        typography = Typography().musicTypography(family),
        content = content,
    )
}

actual fun artworkShape(): androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)

@Composable actual fun BellBackHandler(enabled: Boolean, back: () -> Unit) {}

@Composable
actual fun BellPage(title: String, back: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    val text_back = stringResource(Res.string.back)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                back?.let { BellButton(text_back, it) }
                Text(
                    title,
                    Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        content()
    }
}

@Composable
actual fun BellButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    FilledTonalButton(onClick, modifier, enabled) { Text(label) }
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
    TextField(
        value,
        change,
        Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions =
            if (onDone != null) KeyboardOptions(imeAction = ImeAction.Done)
            else KeyboardOptions.Default,
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
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
    Slider(value, change, valueRange = range, onValueChangeFinished = finished)
}

@Composable
actual fun BellGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
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
    Row(
        Modifier.fillMaxWidth()
            .then(onClick?.let { Modifier.clickable(enabled = enabled, onClick = it) } ?: Modifier)
            .heightIn(min = 68.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        trailing?.invoke()
    }
}

@Composable
actual fun BellFormSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
}

@Composable actual fun MusicSearchFieldGroup(content: @Composable () -> Unit) = content()
