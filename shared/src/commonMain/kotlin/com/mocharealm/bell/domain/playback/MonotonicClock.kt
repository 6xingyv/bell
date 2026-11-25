// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.domain.playback

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

fun monotonicMillis(): Long = origin.elapsedNow().inWholeMilliseconds
