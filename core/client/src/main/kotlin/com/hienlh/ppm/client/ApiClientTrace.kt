package com.hienlh.ppm.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * `POST /api/trace` on whichever server [api] names when a batch goes out (`routes/trace.ts`).
 * The answer is only a status: 2xx accepted, 429 with `Retry-After`, 503 kept for later.
 */
class HttpTraceTransport(private val api: () -> ApiClient?) : TraceTransport {
    override fun token(): String? = api()?.token

    override suspend fun send(body: String, token: String): TraceSendResult {
        val target = api() ?: return TraceSendResult(0)
        val request = Request.Builder()
            .url(target.url("/api/trace", emptyList()))
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("x-ppm-client", PpmHttp.CLIENT_NAME)
            .build()
        return try {
            PpmHttp.getClient.newCall(request).await().use { response ->
                TraceSendResult(response.code, retryAfterMs(response.header("Retry-After")))
            }
        } catch (_: IOException) {
            TraceSendResult(0)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** `Retry-After` in seconds, as the server sends it; anything else is ignored. */
        fun retryAfterMs(header: String?): Long? = header?.trim()?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).toLong() }
    }
}
