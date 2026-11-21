package com.hienlh.ppm.state.logs

import com.hienlh.ppm.client.TraceRedactor
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** The Logs source the app shows in place of the web's "Browser": this app's own lines, read on the device. */
const val DEVICE_LOG_SOURCE = "device"
const val DEVICE_LOG_LABEL = "This device"

/**
 * The sources the app's Logs offers: the server's, with "Browser" swapped for [DEVICE_LOG_SOURCE].
 * Other devices' browser lines are still in "All", which the server answers whole.
 */
val APP_LOG_SOURCES: List<LogSourceInfo> =
    LOG_SOURCES.map { if (it.id == "browser") LogSourceInfo(DEVICE_LOG_SOURCE, DEVICE_LOG_LABEL, it.group) else it }

/**
 * The app's own recent log lines, kept on the device so "This device" reads them with no server
 * and before they are uploaded. A ring of [capacity] lines; [persist] writes it to [store], so the
 * lines before a crash are there at the next launch. Every string is redacted as the trace's are.
 * Safe from any thread; nothing here throws.
 */
class DeviceLogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val store: Store? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val redactor: TraceRedactor = TraceRedactor.Global,
) {
    /** Where the lines wait between launches. Synchronous on purpose. */
    interface Store {
        fun read(): String?

        fun write(text: String)
    }

    private val lock = Any()
    private val lines = ArrayDeque<LogEntry>()
    private val launch = UUID.randomUUID().toString().take(8)
    private var seq = 0L
    private val _version = MutableStateFlow(0L)

    /** Moves on every change, for a view to read [entries] again. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        guard { restore() }
    }

    /** One line: [level] is `debug`, `info`, `warn`, `error` or `fatal`; [more] the lines under it (a stack). */
    fun add(level: String, tag: String, message: String, more: List<String> = emptyList(), sid: String? = null) {
        guard {
            val text = redactor.redact(message)
            val head = text.substringBefore('\n')
            val rest = text.substringAfter('\n', "").lines().filter { it.isNotBlank() }
            val under = (rest + more.flatMap { redactor.redact(it).lines() }.filter { it.isNotBlank() }).take(MAX_MORE_LINES)
            synchronized(lock) {
                lines.addLast(
                    LogEntry(
                        id = "$DEVICE_LOG_SOURCE:$launch:${++seq}",
                        ts = now(),
                        lv = level,
                        src = DEVICE_LOG_SOURCE,
                        tag = tag,
                        msg = head.take(MAX_MESSAGE_CHARS),
                        more = under,
                        sid = sid,
                    ),
                )
                while (lines.size > capacity) lines.removeFirst()
            }
            _version.update { it + 1 }
        }
    }

    /** Oldest first. */
    fun entries(): List<LogEntry> = synchronized(lock) { lines.toList() }

    /** To [store] now: the app is going to the background, or about to die. */
    fun persist() {
        val store = store ?: return
        guard {
            val json = JsonArray(
                entries().takeLast(MAX_PERSISTED).map { e ->
                    buildJsonObject {
                        put("id", JsonPrimitive(e.id))
                        put("ts", JsonPrimitive(e.ts))
                        put("lv", JsonPrimitive(e.lv))
                        put("tag", JsonPrimitive(e.tag))
                        put("msg", JsonPrimitive(e.msg))
                        put("more", JsonArray(e.more.map(::JsonPrimitive)))
                        put("sid", e.sid?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            )
            store.write(json.toString())
        }
    }

    private fun restore() {
        val raw = store?.read() ?: return
        val saved = (WireJson.parseOrNull(raw) as? JsonArray)?.mapNotNull { LogEntry.from(it)?.copy(src = DEVICE_LOG_SOURCE) }.orEmpty()
        synchronized(lock) {
            for (entry in saved.takeLast(capacity)) lines.addLast(entry)
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Keeping a log line must never be what breaks the app.
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
        private const val MAX_PERSISTED = 1_000
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_MORE_LINES = 60
    }
}

/**
 * What "This device" shows: [entries] inside [range] that pass [filter], as a page the Logs views
 * render like the server's, with this device's counts in [serverStats] for its chip.
 */
fun deviceLogResult(
    entries: List<LogEntry>,
    filter: LogFilter,
    range: String,
    serverStats: Map<String, LogSourceStats> = emptySourceStats(),
    nowMs: Long = System.currentTimeMillis(),
): LogQueryResult {
    val from = rangeStart(range, nowMs)
    val inRange = entries.filter { it.ts >= from }
    val search = compileLogSearch(filter)
    val matching = inRange.filter { matchesLogFilter(it, filter.copy(src = DEVICE_LOG_SOURCE), search) }
    return LogQueryResult(
        entries = matching,
        matched = matching.size,
        inRange = inRange.size,
        stats = withDeviceStats(serverStats, inRange),
        fromTs = from,
        badRegex = search is RegexResult.Invalid,
    )
}

/** [stats] with this device's line counts under [DEVICE_LOG_SOURCE]. */
fun withDeviceStats(stats: Map<String, LogSourceStats>, deviceEntries: List<LogEntry>): Map<String, LogSourceStats> =
    stats + (DEVICE_LOG_SOURCE to (computeSourceStats(deviceEntries)[DEVICE_LOG_SOURCE] ?: LogSourceStats()))
