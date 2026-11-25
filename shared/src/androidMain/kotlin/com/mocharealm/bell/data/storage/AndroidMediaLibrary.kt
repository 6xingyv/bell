// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell.data.storage

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.mocharealm.bell.data.playback.StreamingAudioCache
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidMediaLibrary(context: Context, client: HttpClient): FileMediaLibrary {
    val root =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Bell")
    val cache = StreamingAudioCache(File(context.cacheDir, "audio-stream"), client)
    val downloads = context.getSystemService(DownloadManager::class.java)
    return FileMediaLibrary(root, cache) { session, item, target ->
        withContext(Dispatchers.IO) {
            var found = false
            downloads
                .query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))
                ?.use { cursor ->
                    while (!found && cursor.moveToNext()) {
                        val uri =
                            runCatching {
                                android.net.Uri.parse(
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                                    )
                                )
                            }
                                .getOrNull() ?: continue
                        val server = android.net.Uri.parse(session.server)
                        if (
                            uri.host != server.host ||
                                uri.port != server.port ||
                                uri.path?.endsWith("/Audio/${item.id}/stream") != true
                        )
                            continue
                        val id =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                        try {
                            target.parentFile.mkdirs()
                            val pending = File(target.parentFile, "audio.media.import")
                            ParcelFileDescriptor.AutoCloseInputStream(
                                    downloads.openDownloadedFile(id)
                                )
                                .use { input -> pending.outputStream().use { input.copyTo(it) } }
                            val expected =
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                    )
                                )
                            check(
                                pending.length() > 0 &&
                                    (expected <= 0 || pending.length() == expected)
                            )
                            java.nio.file.Files.move(
                                pending.toPath(),
                                target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            )
                            found = true
                        } catch (_: Exception) {}
                    }
                }
            found
        }
    }
}
