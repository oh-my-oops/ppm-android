package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** `POST /api/trace`'s contract, as `src/shared/session-trace.ts` defines it. */
object TraceContract {
    /** Most entries one request may carry; a larger batch is refused whole (`TRACE_INGEST_MAX_BATCH`). */
    const val MAX_BATCH = 50

    /** Largest serialised payload one entry may carry; a larger entry is dropped (`TRACE_INGEST_MAX_PAYLOAD_BYTES`). */
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    /** `TRACE_DEVICE_ID_RE`: anything else is refused. */
    val DEVICE_ID = Regex("^[A-Za-z0-9-]{8,64}$")

    /** The server drops an entry whose `refId` does not match this (`browser-trace-ingest.ts`), rather than the field. */
    val REF_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

    /** `BROWSER_TRACE_TYPES`: the row types a client may write. */
    val TYPES: Set<String> = setOf(
        "console_log", "console_info", "console_debug", "console_warn", "console_error",
        "browser_error", "unhandled_rejection", "render_error", "chunk_error", "entry_never_ran", "console_dropped",
    )
}

/** One row of `BrowserTraceBatch.entries`. Compared by identity: two equal lines are two entries. */
class TraceEntry(val ts: Long, val type: String, val refId: String?, val payload: JsonObject) {
    val json: JsonObject
        get() = buildJsonObject {
            put("ts", JsonPrimitive(ts))
            put("type", JsonPrimitive(type))
            put("refId", refId?.let(::JsonPrimitive) ?: JsonNull)
            put("payload", payload)
        }

    companion object {
        fun from(json: JsonElement): TraceEntry? {
            val ts = (json["ts"] as? JsonPrimitive)?.longOrNull ?: return null
            val type = json["type"].string?.takeIf { it in TraceContract.TYPES } ?: return null
            val payload = json["payload"] as? JsonObject ?: return null
            return TraceEntry(ts, type, json["refId"].string, payload)
        }
    }
}

/** What a send came back with: 0 when no answer arrived at all. */
data class TraceSendResult(val status: Int, val retryAfterMs: Long? = null)

/** Where batches go. */
interface TraceTransport {
    /** The token a request would carry now; null while no server is signed in, which pauses sending. */
    fun token(): String?

    suspend fun send(body: String, token: String): TraceSendResult
}

/** Where the queue waits between launches, so a crash's entries go out at the next start. Synchronous on purpose. */
interface TraceQueueStore {
    fun read(): String?

    /** Null removes it. */
    fun write(json: String?)
}

/** The queue in a file of its own, replaced whole by rename so a crash mid-write leaves the previous copy. */
class FileTraceQueueStore(private val file: File) : TraceQueueStore {
    override fun read(): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null
    }

    override fun write(json: String?) {
        try {
            if (json == null) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, "${file.name}.tmp")
            next.writeText(json)
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        } catch (_: Exception) {
            // A full disk: the in-memory queue is still sent.
        }
    }
}

/** Timers the trace client sets; the returned function cancels one. Tasks must not throw. */
fun interface TraceTimers {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit

    companion object {
        private val executor: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ppm-trace").apply { isDaemon = true } }
        }

        val Default = TraceTimers { delayMs, task ->
            val future = executor.schedule({ runCatching(task) }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            return@TraceTimers { future.cancel(false) }
        }
    }
}

/**
 * This install's id (`device-id.ts`): minted once, a UUID like the web's, so the server keeps
 * `browser:<id>` rows and a rate limit per device.
 */
object TraceDeviceId {
    const val KEY = "ppm-device-id"

    fun get(store: KeyValueStore): String {
        store.get(KEY)?.takeIf { TraceContract.DEVICE_ID.matches(it) }?.let { return it }
        val id = UUID.randomUUID().toString()
        store.put(KEY, id)
        return id
    }
}
