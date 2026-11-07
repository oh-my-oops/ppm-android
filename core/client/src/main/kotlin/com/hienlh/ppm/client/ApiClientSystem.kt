package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA = "application/json".toMediaType()

/**
 * Full-tier metrics SSE stream (`/api/system/resources/stream?processes=1`) with lease renewal
 * every 10 s (`POST /api/system/resources/stream/:sid/ping`) and explicit lease cleanup on close
 * (`DELETE /api/system/resources/stream/:sid`).
 */
fun ApiClient.fullResourceSamples(processes: Boolean = true): Flow<JsonElement> = callbackFlow {
    var sid: String? = null
    val lease = launch {
        while (isActive) {
            delay(10_000)
            sid?.let { id -> runCatching { call("POST", "/api/system/resources/stream/${ApiClient.escape(id)}/ping") } }
        }
    }
    val request = request("GET", "/api/system/resources/stream", listOf("processes" to if (processes) "1" else "0"))
        .newBuilder().header("Accept", "text/event-stream").build()
    val call = PpmHttp.streamClient.newCall(request)
    val reader = launch(Dispatchers.IO) {
        try {
            call.await().use { response ->
                if (response.code == 401) throw PpmError.Unauthorized()
                if (response.code != 200) {
                    throw PpmError.Server(response.code, "The metrics stream answered HTTP ${response.code}.")
                }
                val source = response.body.source()
                var event = ""
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val json = WireJson.parseOrNull(line.removePrefix("data:"))
                            if (json != null) {
                                if (event == "session") {
                                    sid = json["sid"].string
                                } else if (event == "snapshot") {
                                    trySend(json)
                                }
                            }
                            event = ""
                        }
                    }
                }
            }
            close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            close(PpmError.Unreachable(describe(error)))
        } catch (error: Throwable) {
            close(error)
        }
    }
    awaitClose {
        reader.cancel()
        lease.cancel()
        call.cancel()
        sid?.let { id -> Background.scope.launch { runCatching { call("DELETE", "/api/system/resources/stream/${ApiClient.escape(id)}") } } }
    }
}.flowOn(Dispatchers.IO)

/** Static hardware inventory (`GET /api/system/hardware`). */
suspend fun ApiClient.hardwareInventory(): JsonElement = call("GET", "/api/system/hardware")

/** On-demand process details (`GET /api/system/resources/process/:pid`). */
suspend fun ApiClient.processDetails(pid: Int): JsonElement = call("GET", "/api/system/resources/process/$pid")

/**
 * Guarded process kill (`POST /api/system/resources/kill`) with `X-PPM-Request: 1` and
 * `Content-Type: application/json`.
 */
suspend fun ApiClient.killProcess(pid: Int, startedAt: Long, tree: Boolean): JsonElement {
    val body = buildJsonObject {
        put("pid", pid)
        put("startedAt", startedAt)
        put("tree", tree)
    }
    val req = request("POST", "/api/system/resources/kill", body = body.toString().toRequestBody(JSON_MEDIA))
        .newBuilder()
        .header("X-PPM-Request", "1")
        .build()
    return send(PpmHttp.client, req)
}

/**
 * Guarded process signal (`POST /api/system/resources/signal`) with `X-PPM-Request: 1` and
 * `Content-Type: application/json`.
 */
suspend fun ApiClient.signalProcess(pid: Int, startedAt: Long, signal: String, tree: Boolean): JsonElement {
    val body = buildJsonObject {
        put("pid", pid)
        put("startedAt", startedAt)
        put("signal", signal)
        put("tree", tree)
    }
    val req = request("POST", "/api/system/resources/signal", body = body.toString().toRequestBody(JSON_MEDIA))
        .newBuilder()
        .header("X-PPM-Request", "1")
        .build()
    return send(PpmHttp.client, req)
}

/** Services snapshot (`GET /api/system/services`). */
suspend fun ApiClient.systemServices(): JsonElement = call("GET", "/api/system/services")

/** Service details + journal (`GET /api/system/services/:scope/:unit`). */
suspend fun ApiClient.systemServiceDetails(scope: String, unit: String): JsonElement =
    call("GET", "/api/system/services/${ApiClient.escape(scope)}/${ApiClient.escape(unit)}")

/**
 * Guarded service action (`POST /api/system/services/:scope/:unit/:action`) with `{}` JSON body
 * and `X-PPM-Request: 1`.
 */
suspend fun ApiClient.systemServiceAction(scope: String, unit: String, action: String): JsonElement {
    val req = request(
        "POST",
        "/api/system/services/${ApiClient.escape(scope)}/${ApiClient.escape(unit)}/${ApiClient.escape(action)}",
        body = "{}".toRequestBody(JSON_MEDIA),
    )
        .newBuilder()
        .header("X-PPM-Request", "1")
        .build()
    return send(PpmHttp.client, req)
}

/** App icon bytes (`GET /api/system/app-icon/:id`). */
suspend fun ApiClient.appIconBytes(appId: String): ByteArray? {
    val req = request("GET", "/api/system/app-icon/${ApiClient.escape(appId)}")
    val response = try {
        PpmHttp.getClient.newCall(req).await()
    } catch (_: IOException) {
        return null
    }
    return withContext(Dispatchers.IO) {
        response.use {
            if (!it.isSuccessful) return@use null
            try {
                it.body.bytes()
            } catch (_: IOException) {
                null
            }
        }
    }
}
