package com.a11yland.hissi.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

// Ported from HissiTests/Tests/AppUserAgentTests.swift. Every request names
// the app, its version and the platform.
class AppUserAgentTest {
    @Test
    fun namesAppVersionAndPlatform() {
        assertEquals("Hissi/1.5 (Android)", AppUserAgent.make("1.5"))
    }

    // No version to be had: still Hissi, never an empty slot.
    @Test
    fun missingVersionStillNamesTheApp() {
        assertEquals("Hissi (Android)", AppUserAgent.make(null))
        assertEquals("Hissi (Android)", AppUserAgent.make(""))
    }

    // No Swift counterpart: the header really goes out — instead of Ktor's
    // default "ktor-client".
    @Test
    fun requestsCarryTheUserAgent() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen.add(request.headers[HttpHeaders.UserAgent])
            respond("""{"docs":[],"hasNextPage":false}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = TransitApi(
            engine = engine, token = "trtok_test", pageCounts = InMemoryPageCountStore(),
            userAgent = AppUserAgent.make("1.5"),
        )
        api.fetchEquipment(listOf("101"))
        assertEquals(listOf<String?>("Hissi/1.5 (Android)"), seen)
    }
}
