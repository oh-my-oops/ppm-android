package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Ships the app's log lines and its errors to the session trace (`POST /api/trace`), as every
 * web tab does (`src/web/lib/trace-client.ts`), so they show in PPM's Logs window and a session's
 * timeline tagged with this device.
 *
 * The web's rules, kept: errors go out within a second and everything else within five; each
 * error carries the 50 lines before it; 300 entries a minute, past which only a count is kept;
 * a payload is shrunk to fit; a 429 or a 5xx keeps the batch and backs off (Retry-After
 * honoured), a 401 waits for another token, any other refusal drops the batch. The queue (300
 * entries) is persisted, debounced, and at once by [recordFatal], so a crash's entries go out at
 * the next start. Two differences: payloads are measured in UTF-8 bytes (the server's unit) rather
 * than characters, and every string is redacted here as well as on the server ([TraceRedactor]).
 *
 * Nothing here may make things worse than the failure it reports: no method throws.
 */
class TraceClient(
    private val transport: TraceTransport,
    private val deviceId: () -> String,
    private val storage: TraceQueueStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val timers: TraceTimers = TraceTimers.Default,
    /** The chat session in front, so the row joins that session's trace. */
    private val refId: () -> String? = { null },
    /** Fields stamped on every entry (which launch, which project). */
    private val context: () -> Map<String, String?> = { emptyMap() },
    private val redactor: TraceRedactor = TraceRedactor.Global,
    /** Runs a flush a timer asked for. */
    private val launch: (suspend () -> Unit) -> Unit = { block -> Background.scope.launch { block() } },
) {
    enum class Level(val wire: String) { LOG("log"), INFO("info"), DEBUG("debug"), WARN("warn"), ERROR("error") }

    private class Breadcrumb(val ts: Long, val level: String, val text: String)

    private class Timer(var cancel: () -> Unit = {})

    private val lock = Any()
    private var queue: MutableList<TraceEntry> = restore()
    /** Entries in a request that has not answered yet — never put in a second one. */
    private val sending = HashSet<TraceEntry>()
    private val breadcrumbs = ArrayDeque<Breadcrumb>()
    private var flushTimer: Timer? = null
    private var flushDueAt = Long.MAX_VALUE
    private var persistTimer: Timer? = null
    private var inFlight = false
    private var backoffMs = 0L
    private var blockedUntil = 0L
    /** The token a 401 answered; [NOT_REFUSED] while nothing has been. */
    private var rejectedToken: String? = NOT_REFUSED
    private var windowStart = now()
    private var windowCount = 0
    private var droppedInWindow = 0

    init {
        if (queue.isNotEmpty()) synchronized(lock) { schedule(START_FLUSH_MS) }
    }

    /** Entries not yet accepted by a server. */
    fun pending(): Int = synchronized(lock) { queue.size }

    /** One entry of [type] (an error type carries the breadcrumbs). */
    fun record(type: String, payload: JsonObject) {
        guard {
            synchronized(lock) {
                val t = now()
                if (!admit(t)) return@guard
                val isError = type in ERROR_TYPES
                push(type, if (isError) payload.plusField("breadcrumbs", crumbs()) else payload, t)
                schedule(if (isError) ERROR_FLUSH_MS else LOG_FLUSH_MS)
            }
        }
    }

    /** A thrown value as an entry of [type], with [extra] fields. */
    fun error(type: String, error: Throwable?, message: String? = null, extra: Map<String, String?> = emptyMap()) {
        guard { record(type, errorPayload(error, message, extra)) }
    }

    /**
     * The process is about to die: recorded past the per-minute cap and written to storage before
     * this returns, so the next start sends it.
     */
    fun recordFatal(type: String, error: Throwable, extra: Map<String, String?> = emptyMap()) {
        guard {
            synchronized(lock) {
                val t = now()
                push(type, errorPayload(error, null, extra).plusField("breadcrumbs", crumbs()), t)
                persistNow()
            }
        }
    }

    /** A log line: its arguments as text, the way `console.*` hands them over. */
    fun console(level: Level, args: List<String>) {
        guard {
            synchronized(lock) {
                val t = now()
                if (!admit(t)) return@guard
                val texts = args.map { cap(redactor.redact(it), MAX_ARG_CHARS) }
                val type = "console_${level.wire}"
                val isError = type in ERROR_TYPES
                val argsJson = JsonArray(texts.map(::JsonPrimitive))
                // The line itself is not its own breadcrumb: breadcrumbs are what came before.
                val payload = buildJsonObject {
                    put("args", argsJson)
                    if (isError) put("breadcrumbs", crumbs())
                }
                push(type, payload, t)
                breadcrumbs.addLast(Breadcrumb(t, level.wire, cap(texts.joinToString(" "), MAX_BREADCRUMB_CHARS)))
                if (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
                schedule(if (isError) ERROR_FLUSH_MS else LOG_FLUSH_MS)
            }
        }
    }

    /** The app went to the background, where Android may end it: storage now, and a send now. */
    fun onHidden() {
        guard {
            synchronized(lock) {
                persistNow()
                if (queue.isNotEmpty()) schedule(0)
            }
        }
    }

    /** Back in front, or a sign-in: try now rather than at the next timer. */
    fun flushSoon() {
        guard { synchronized(lock) { if (queue.isNotEmpty()) schedule(0) } }
    }

    suspend fun flush() {
        val (batch, token, body) = try {
            synchronized(lock) { prepareSend() }
        } catch (_: Exception) {
            null
        } ?: return
        val result = try {
            transport.send(body, token)
        } catch (error: CancellationException) {
            synchronized(lock) {
                inFlight = false
                sending -= batch.toSet()
            }
            throw error
        } catch (_: Throwable) {
            TraceSendResult(0)
        }
        guard {
            synchronized(lock) {
                inFlight = false
                sending -= batch.toSet()
                when {
                    result.status in 200..299 -> {
                        remove(batch)
                        backoffMs = 0
                        blockedUntil = 0
                        rejectedToken = NOT_REFUSED
                        if (queue.isNotEmpty()) schedule(0)
                    }
                    // Kept for the sign-in that replaces this token; the rows only ever reach the server authenticated.
                    result.status == 401 -> {
                        rejectedToken = token
                        schedule(AUTH_RECHECK_MS)
                    }
                    retryable(result.status) -> backOff(result.retryAfterMs)
                    // 400/403/413: this batch will never be accepted, and retrying it forever would pin the queue.
                    else -> remove(batch)
                }
                schedulePersist()
            }
        }
    }

    // Under the lock from here on.

    /** The next batch with its token and body, marked as sending; null when nothing should go now. */
    private fun prepareSend(): Triple<List<TraceEntry>, String, String>? {
        if (queue.isEmpty()) return null
        val t = now()
        if (inFlight || t < blockedUntil) {
            schedule(maxOf(blockedUntil - t, ERROR_FLUSH_MS))
            return null
        }
        val auth = try { transport.token() } catch (_: Exception) { null }
        if (auth == null || auth == rejectedToken) {
            // Signed out, or refused with this token: a request would only be refused again.
            schedule(AUTH_RECHECK_MS)
            return null
        }
        val batch = takeBatch()
        if (batch.isEmpty()) return null
        inFlight = true
        sending += batch
        return Triple(batch, auth, body(batch, t))
    }

    private fun restore(): MutableList<TraceEntry> {
        val raw = try { storage?.read() } catch (_: Exception) { null } ?: return mutableListOf()
        val parsed = WireJson.parseOrNull(raw) as? JsonArray ?: return mutableListOf()
        return parsed.mapNotNull(TraceEntry::from).takeLast(MAX_QUEUE).toMutableList()
    }

    private fun persistNow() {
        persistTimer?.cancel?.invoke()
        persistTimer = null
        try {
            if (queue.isEmpty()) {
                storage?.write(null)
                return
            }
            var kept: List<TraceEntry> = queue
            var json = serialize(kept)
            while (json.length > MAX_PERSIST_CHARS && kept.size > 1) {
                kept = kept.drop((kept.size + 3) / 4)
                json = serialize(kept)
            }
            storage?.write(json)
        } catch (_: Exception) {
            // The in-memory queue is still sent.
        }
    }

    private fun serialize(entries: List<TraceEntry>): String = JsonArray(entries.map { it.json }).toString()

    private fun schedulePersist() {
        if (persistTimer != null || storage == null) return
        val timer = Timer()
        persistTimer = timer
        timer.cancel = timers.schedule(PERSIST_DEBOUNCE_MS) {
            guard {
                synchronized(lock) {
                    if (persistTimer === timer) {
                        persistTimer = null
                        persistNow()
                    }
                }
            }
        }
    }

    private fun schedule(ms: Long) {
        val due = now() + ms
        if (flushTimer != null && flushDueAt <= due) return
        flushTimer?.cancel?.invoke()
        flushDueAt = due
        val timer = Timer()
        flushTimer = timer
        timer.cancel = timers.schedule(ms) {
            val mine = synchronized(lock) {
                (flushTimer === timer).also {
                    if (it) {
                        flushTimer = null
                        flushDueAt = Long.MAX_VALUE
                    }
                }
            }
            if (mine) guard { launch { flush() } }
        }
    }

    private fun push(type: String, payload: JsonObject, t: Long) {
        val fields = try { context() } catch (_: Exception) { emptyMap() }
        val merged = buildJsonObject {
            for ((key, value) in fields) if (value != null) put(key, JsonPrimitive(value))
            for ((key, value) in payload) put(key, value)
        }
        val ref = try { refId() } catch (_: Exception) { null }?.takeIf { TraceContract.REF_ID.matches(it) }
        queue.add(TraceEntry(t, type, ref, fit(redactor.redact(merged))))
        if (queue.size > MAX_QUEUE) queue = queue.drop(queue.size - MAX_QUEUE).toMutableList()
        schedulePersist()
    }

    /** The per-minute budget. False once spent, counting what it turned away. */
    private fun admit(t: Long): Boolean {
        if (t - windowStart >= 60_000) {
            val dropped = droppedInWindow
            windowStart = t
            windowCount = 0
            droppedInWindow = 0
            if (dropped > 0) {
                windowCount++
                push("console_dropped", buildJsonObject { put("count", JsonPrimitive(dropped)) }, t)
            }
        }
        if (windowCount >= MAX_ENTRIES_PER_MINUTE) {
            droppedInWindow++
            return false
        }
        windowCount++
        return true
    }

    private fun crumbs(): JsonArray = buildJsonArray {
        for (crumb in breadcrumbs) {
            add(buildJsonObject {
                put("ts", JsonPrimitive(crumb.ts))
                put("level", JsonPrimitive(crumb.level))
                put("text", JsonPrimitive(crumb.text))
            })
        }
    }

    private fun takeBatch(): List<TraceEntry> =
        queue.asSequence().filter { it !in sending }.take(TraceContract.MAX_BATCH).toList()

    private fun body(batch: List<TraceEntry>, t: Long): String = buildJsonObject {
        put("deviceId", JsonPrimitive(deviceId()))
        put("sentAt", JsonPrimitive(t))
        put("entries", JsonArray(batch.map { it.json }))
    }.toString()

    private fun remove(batch: List<TraceEntry>) {
        val sent = batch.toSet()
        queue = queue.filterNot { it in sent }.toMutableList()
    }

    private fun backOff(retryAfter: Long?) {
        backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
        blockedUntil = now() + maxOf(backoffMs, retryAfter ?: 0)
        schedule(blockedUntil - now())
    }

    private fun errorPayload(error: Throwable?, message: String?, extra: Map<String, String?>): JsonObject = buildJsonObject {
        if (error != null) {
            put("name", JsonPrimitive(error.javaClass.name))
            put("message", JsonPrimitive(cap(redactor.redact(message ?: error.message ?: ""), MAX_ARG_CHARS)))
            put("stack", JsonPrimitive(cap(redactor.redact(error.stackTraceToString()), MAX_ARG_CHARS * 3)))
        } else {
            put("message", JsonPrimitive(cap(redactor.redact(message ?: ""), MAX_ARG_CHARS)))
        }
        for ((key, value) in extra) if (value != null) put(key, JsonPrimitive(value))
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Recording must never be the thing that breaks the app.
        }
    }

    companion object {
        const val MAX_QUEUE = 300
        const val MAX_BREADCRUMBS = 50
        const val MAX_ENTRIES_PER_MINUTE = 300
        /** Under the server's 16 KB per entry, as the web keeps it; counted in UTF-8 bytes here. */
        const val MAX_PAYLOAD_BYTES = 12_000
        const val ERROR_FLUSH_MS = 1_000L
        const val LOG_FLUSH_MS = 5_000L
        const val AUTH_RECHECK_MS = 5_000L
        const val START_FLUSH_MS = 2_000L
        const val PERSIST_DEBOUNCE_MS = 1_000L
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val MAX_PERSIST_CHARS = 256 * 1024
        const val MAX_ARG_CHARS = 2_000
        private const val MAX_BREADCRUMB_CHARS = 300
        private val NOT_REFUSED: String? = "\u0000not-refused"

        val ERROR_TYPES: Set<String> = setOf("console_error", "browser_error", "unhandled_rejection", "render_error", "chunk_error")

        /** Worth another try: offline, rate-limited, or a server in trouble. */
        fun retryable(status: Int): Boolean = status == 0 || status == 429 || status >= 500

        fun cap(text: String, max: Int): String = if (text.length <= max) text else "${text.take(max)}… [${text.length - max} more]"

        private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        private fun JsonObject.plusField(key: String, value: JsonArray): JsonObject = JsonObject(this + (key to value))

        /** Shrink a payload until it fits one entry: oldest breadcrumbs first, then long strings, then a head. */
        fun fit(payload: JsonObject): JsonObject {
            var json = payload.toString()
            if (bytes(json) <= MAX_PAYLOAD_BYTES) return payload
            var out = payload
            val crumbs = (payload["breadcrumbs"] as? JsonArray)?.toMutableList()
            if (crumbs != null) {
                while (crumbs.isNotEmpty() && bytes(json) > MAX_PAYLOAD_BYTES) {
                    crumbs.removeAt(0)
                    out = JsonObject(out + ("breadcrumbs" to JsonArray(crumbs)))
                    json = out.toString()
                }
                if (bytes(json) <= MAX_PAYLOAD_BYTES) return out
            }
            out = JsonObject(
                out.mapValues { (key, value) ->
                    when {
                        value is JsonPrimitive && value.isString && value.content.length > 1_000 -> JsonPrimitive(cap(value.content, 1_000))
                        key == "args" && value is JsonArray -> JsonArray(
                            value.map { if (it is JsonPrimitive && it.isString) JsonPrimitive(cap(it.content, 1_000)) else it },
                        )
                        else -> value
                    }
                },
            )
            json = out.toString()
            if (bytes(json) <= MAX_PAYLOAD_BYTES) return out
            var head = json.take(MAX_PAYLOAD_BYTES - 200)
            while (bytes(head) > MAX_PAYLOAD_BYTES - 200) head = head.take(head.length * 3 / 4)
            return buildJsonObject {
                put("truncated", JsonPrimitive(true))
                put("head", JsonPrimitive(head))
            }
        }
    }
}
