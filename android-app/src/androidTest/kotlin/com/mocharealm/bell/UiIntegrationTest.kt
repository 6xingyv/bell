// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.mocharealm.bell.ui.BellIntent
import com.mocharealm.bell.ui.BellStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class UiIntegrationTest {
    @Test
    fun loginLibrarySongsAndNowPlayingOpenOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.runOnMainSync {
            GlobalContext.get().get<BellStore>().accept(BellIntent.Logout)
        }
        fun click(text: String, last: Boolean = false) {
            repeat(10) {
                try {
                    device.waitForIdle()
                    val nodes = device.findObjects(By.text(text))
                    if (nodes.isNotEmpty()) {
                        val point = (if (last) nodes.last() else nodes.first()).visibleCenter
                        device.click(point.x, point.y)
                        return
                    }
                } catch (_: StaleObjectException) {
                    /* Compose may replace semantics after text edits. */
                }
                Thread.sleep(100)
            }
            fail("Control not found: $text")
        }
        // Launch from the shell, as a user opening the app. Some OEMs block the test framework's
        // intermediate EmptyActivity even though the target app itself launches normally.
        device.executeShellCommand(
            "am start -W -n com.mocharealm.bell/com.mocharealm.bell.ui.MainActivity"
        )
        run {
            try {
                assertTrue(
                    "Login page failed to render",
                    device.wait(Until.hasObject(By.text("连接音乐库")), 10_000),
                )
                val fields = device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(3, fields.size)
                fields[0].text = "http://127.0.0.1:18097"
                fields[1].text = "lyric-audit"
                fields[2].text = "LocalFixtureOnly123!"
                click("连接音乐库")
                // The lyric audit fixture intentionally stores four flat files, without album
                // folders. Verify its song library instead of assuming Jellyfin creates albums.
                assertTrue(
                    "Music library did not open",
                    device.wait(Until.hasObject(By.text("歌曲")), 15_000),
                )
                click("歌曲")
                assertTrue(
                    "Songs did not load",
                    device.wait(Until.hasObject(By.text("Embedded")), 10_000),
                )
                click("Embedded")
                assertTrue(
                    "Mini player did not open",
                    device.wait(Until.hasObject(By.text("暂停")), 10_000),
                )
                click("Embedded", last = true)
                assertTrue(
                    "Now playing page did not open",
                    device.wait(Until.hasObject(By.text("正在播放")), 5_000),
                )
                device.takeScreenshot(
                    File(
                        instrumentation.targetContext.getExternalFilesDir(null),
                        "jellify-now-playing.png",
                    )
                )
                val model = GlobalContext.get().get<BellStore>()
                repeat(20) { if (model.state.value.lyrics.tracks.isEmpty()) Thread.sleep(100) }
                assertTrue("Lyrics were not loaded", model.state.value.lyrics.tracks.isNotEmpty())
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 4 / 5,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    20,
                )
                instrumentation.runOnMainSync { model.accept(BellIntent.Seek(5200)) }
                Thread.sleep(500)
                assertEquals(
                    "Lyric renderer left the app",
                    "com.mocharealm.bell",
                    device.currentPackageName,
                )
                device.takeScreenshot(
                    File(
                        instrumentation.targetContext.getExternalFilesDir(null),
                        "jellify-lyrics.png",
                    )
                )
                val before = model.state.value.audio.positionMs
                device.pressHome()
                Thread.sleep(1000)
                assertFalse("Background playback paused", model.state.value.audio.paused)
                assertTrue("Background clock stopped", model.state.value.audio.positionMs > before)
                device.executeShellCommand(
                    "am start -n com.mocharealm.bell/com.mocharealm.bell.ui.MainActivity"
                )
                assertTrue(device.wait(Until.hasObject(By.text("正在播放")), 5000))
            } finally {
                instrumentation.runOnMainSync {
                    GlobalContext.get().get<BellStore>().accept(BellIntent.Logout)
                }
            }
        }
    }
}
