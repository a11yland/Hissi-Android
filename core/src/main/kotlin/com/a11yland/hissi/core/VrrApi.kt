package com.a11yland.hissi.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlinx.coroutines.CancellationException

// The request half of the VRR source (the parser is VrrClient). The app
// always asks for the whole list: it is one cheap request (~430 KB), and it
// tells the operator nothing about which lifts a user follows. Ported from
// `Shared/VRRClient.swift` (`fetchAll`).
class VrrApi(
    engine: HttpClientEngine,
    private val apiKey: String = API_KEY,
    timeoutMillis: Long = 20_000,
    // Words the feed's known status codes (string resources in the app).
    private val explain: (VrrClient.Explanation) -> String = { it.german },
    private val logger: (String) -> Unit = {},
) {
    companion object {
        const val INFRASTRUCTURE_URL = "https://mip.vrr.de/vrr-api/rest/stop/all/infrastructure"

        // Printed on https://www.opendata-oepnv.de/ht/de/api — public, not a
        // secret, which is why it lives here and not in local.properties.
        const val API_KEY = "F3E46748D2DED"
    }

    private val client = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
        expectSuccess = false
    }

    // Every VRR elevator with its live status; null when the request fails
    // or the answer doesn't parse — callers then keep what they have (seed
    // records with status unknown), never a guess.
    suspend fun fetchAll(now: Instant = Instant.now()): List<AccessibilityCloudClient.Equipment>? = try {
        val response = client.get(INFRASTRUCTURE_URL) { header("x-api-key", apiKey) }
        if (response.status == HttpStatusCode.OK) {
            VrrClient.parse(response.bodyAsText(), now, explain)
                ?: null.also { logger("VRR: unexpected answer") }
        } else {
            logger("VRR: HTTP ${response.status.value}")
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger("VRR: ${error::class.simpleName}")
        null
    }
}
