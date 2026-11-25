// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.ui.utils

import androidx.compose.runtime.*
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticProvider
import com.mocharealm.accompanist.lyrics.phonetics.*

@Composable
internal actual fun rememberMusicPhoneticProvider(): PhoneticProvider {
    return remember {
        val korean = com.ibm.icu.text.Transliterator.getInstance("Hangul-Latin; Latin-ASCII")
        createMusicPhonetics(SharedPackLoader(ClasspathPackLoader())) { text ->
            synchronized(korean) { korean.transliterate(text).lowercase(java.util.Locale.ROOT) }
        }
    }
}
