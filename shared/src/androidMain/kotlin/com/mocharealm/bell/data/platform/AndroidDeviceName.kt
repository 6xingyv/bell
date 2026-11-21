// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

fun androidDeviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
