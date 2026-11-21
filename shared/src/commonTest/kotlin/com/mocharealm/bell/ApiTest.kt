// SPDX-License-Identifier: LicenseRef-Mocha-1.0
package com.mocharealm.bell

import com.mocharealm.bell.data.network.JellyfinApi
import com.mocharealm.bell.data.network.configureClient
import com.mocharealm.bell.domain.model.Session
import com.mocharealm.bell.domain.model.User
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApiTest {
    @Test
    fun loginUsesJellyfinAuthorizationAndSupportsReverseProxyBasePath() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                "https://music.example/jellyfin/Users/AuthenticateByName",
                request.url.toString(),
            )
            assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("Client=\"Bell\""))
            respond(
                """{"AccessToken":"test-token","User":{"Id":"user","Name":"listener"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { configureClient(this) }
        val api = JellyfinApi(client)
        val session =
            api.login(" https://music.example/jellyfin/ ", "listener", "password", "device")
        assertEquals("https://music.example/jellyfin", session.server)
        assertEquals("test-token", session.token)
        client.close()
    }

    @Test
    fun configuredDeviceNameIsEncodedForLoginAndAuthenticatedRequests() = runTest {
        val name = """Simon 的手机 + "Bell""""
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    val authorization = request.headers[HttpHeaders.Authorization]!!
                    val encoded = authorization.substringAfter("Device=\"").substringBefore('"')
                    assertEquals(name, encoded.decodeURLQueryComponent())
                    assertTrue(authorization.all { it.code < 128 })
                    calls++
                    if (request.url.encodedPath.endsWith("AuthenticateByName"))
                        respond(
                            """{"AccessToken":"token","User":{"Id":"user","Name":"listener"}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    else
                        respond(
                            "[]",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                }
            ) {
                configureClient(this)
            }
        val api = JellyfinApi(client, deviceName = { name })
        val session = api.login("https://music.example", "listener", "password", "device")
        api.listeningGroups(session)
        assertEquals(2, calls)
        client.close()
    }

    @Test
    fun libraryPaginatesWithoutTruncatingAtFirstPage() = runTest {
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    val offset = request.url.parameters["StartIndex"]!!.toInt()
                    assertEquals(calls, offset)
                    calls++
                    respond(
                        """{"Items":[{"Id":"$offset","Name":"track"}],"TotalRecordCount":3}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureClient(this)
            }
        val items =
            JellyfinApi(client).items(Session("http://localhost", "token", User("u"), "d"), "Audio")
        assertEquals(3, items.size)
        assertEquals(3, calls)
        client.close()
    }

    @Test
    fun nonHttpAndCredentialsInServerUrlAreRejected() {
        val client = HttpClient(MockEngine { error("No request expected") })
        val api = JellyfinApi(client)
        assertFailsWith<IllegalArgumentException> { api.normalizeServer("file:///etc/passwd") }
        assertFailsWith<IllegalArgumentException> {
            api.normalizeServer("https://user:password@example.com")
        }
        assertEquals("http://192.168.0.5:8096", api.normalizeServer("192.168.0.5:8096/"))
        client.close()
    }
}
