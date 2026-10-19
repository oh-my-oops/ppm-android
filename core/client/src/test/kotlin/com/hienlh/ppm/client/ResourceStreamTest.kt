package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ResourceSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** `GET /api/system/resources/stream` as the server writes it (`routes/resources.ts`). */
class ResourceStreamTest {
    @Test
    fun `the stream's snapshot gives the machine's CPU and memory`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "retry: 5000\n\n" +
                        "event: session\ndata: {\"sid\":\"d30bc976\",\"tier\":\"light\",\"intervalMs\":5000}\n\n" +
                        "event: snapshot\ndata: {\"ts\":1,\"system\":{\"cpu\":{\"total\":6.6,\"cores\":[5.6]},\"mem\":{\"totalMB\":63958.1,\"usedMB\":45772.8}},\"groups\":[]}\n\n",
                )
                .build(),
        )
        server.start()
        try {
            val api = ApiClient(server.url("/"), "t")
            val sample = withTimeout(5_000) { api.resourceSamples().first() }
            assertEquals(ResourceSample(6.6, 45772.8), sample)
            val request = server.takeRequest()
            assertEquals("/api/system/resources/stream?processes=0", request.target)
        } finally {
            server.close()
        }
    }
}
