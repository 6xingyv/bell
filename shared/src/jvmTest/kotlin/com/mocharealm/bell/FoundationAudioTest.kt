// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.data.platform.DesktopAudio
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue

class FoundationAudioTest {
    @Test
    fun nativeWindowsEnginePlaysSeeksAndPauses() = runBlocking {
        assumeTrue(
            "Local Windows foundation.audio fixture required",
            System.getProperty("os.name").startsWith("Windows") &&
                File("D:/JellyfinAudit/merge-fixtures/Sidecar.flac").isFile,
        )
        val fixture = File("D:/JellyfinAudit/merge-fixtures/Sidecar.flac")
        val client =
            HttpClient(MockEngine { respond(fixture.readBytes()) }) { configureClient(this) }
        val audio = DesktopAudio(client)
        try {
            audio.play("http://fixture/Sidecar.flac", 12_000)
            delay(600)
            assertFalse(audio.snapshot().paused)
            assertTrue(audio.snapshot().positionMs > 0)
            audio.seek(4000)
            delay(200)
            assertTrue(audio.snapshot().positionMs >= 3900)
            audio.pause(true)
            delay(100)
            val position = audio.snapshot().positionMs
            assertTrue(audio.snapshot().paused)
            delay(150)
            assertTrue(kotlin.math.abs(audio.snapshot().positionMs - position) < 100)
        } finally {
            audio.close()
            client.close()
        }
    }
}
