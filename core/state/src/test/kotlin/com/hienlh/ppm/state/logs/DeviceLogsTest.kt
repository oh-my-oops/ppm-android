package com.hienlh.ppm.state.logs

import com.hienlh.ppm.client.TraceRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogsTest {
    private class MemoryStore : DeviceLogBuffer.Store {
        var value: String? = null
        override fun read(): String? = value
        override fun write(text: String) { value = text }
    }

    private var clock = 1_800_000_000_000L

    private fun buffer(capacity: Int = 100, store: DeviceLogBuffer.Store? = null, redactor: TraceRedactor = TraceRedactor()) =
        DeviceLogBuffer(capacity = capacity, store = store, now = { clock }, redactor = redactor)

    @Test
    fun `the app's Logs offer This device in place of Browser and keep every other source`() {
        assertEquals(LOG_SOURCES.map { it.id }.map { if (it == "browser") "device" else it }, APP_LOG_SOURCES.map { it.id })
        assertEquals("This device", APP_LOG_SOURCES.last().label)
        assertEquals("This device", logSourceLabel(DEVICE_LOG_SOURCE))
        assertEquals("Browser", logSourceLabel("browser"))
    }

    @Test
    fun `lines are kept oldest first, the oldest going past capacity, the version moving with each`() {
        val log = buffer(capacity = 3)
        repeat(5) { log.add("info", "PPM", "line $it"); clock += 1 }
        assertEquals(listOf("line 2", "line 3", "line 4"), log.entries().map { it.msg })
        assertEquals(5L, log.version.value)
        assertEquals(3, log.entries().map { it.id }.toSet().size)
        assertTrue(log.entries().all { it.src == DEVICE_LOG_SOURCE })
    }

    @Test
    fun `a multi-line message keeps its first line as the message and the rest, then the stack, under it`() {
        val log = buffer()
        log.add("warn", "PPM", "first\nsecond", listOf("java.lang.Exception: x", "\tat A.b(A.kt:1)"))
        val entry = log.entries().single()
        assertEquals("first", entry.msg)
        assertEquals(listOf("second", "java.lang.Exception: x", "\tat A.b(A.kt:1)"), entry.more)
    }

    @Test
    fun `a secret the app holds never reaches the buffer or its file`() {
        val redactor = TraceRedactor().apply { remember("hunter2-token") }
        val store = MemoryStore()
        val log = buffer(store = store, redactor = redactor)
        log.add("error", "Http", "refused hunter2-token", listOf("at ws://x/?token=hunter2-token"))
        log.persist()
        assertFalse(log.entries().toString().contains("hunter2-token"))
        assertFalse(store.value!!.contains("hunter2-token"))
    }

    @Test
    fun `the lines written out come back at the next launch, under ids of their own`() {
        val store = MemoryStore()
        val first = buffer(store = store)
        first.add("info", "PPM", "before", sid = "s-1")
        first.add("fatal", "crash", "boom", listOf("trace"))
        first.persist()
        val next = buffer(store = store)
        next.add("info", "PPM", "after")
        val lines = next.entries()
        assertEquals(listOf("before", "boom", "after"), lines.map { it.msg })
        assertEquals(listOf("info", "fatal", "info"), lines.map { it.lv })
        assertEquals("s-1", lines[0].sid)
        assertEquals(listOf("trace"), lines[1].more)
        assertEquals(3, lines.map { it.id }.toSet().size)
    }

    @Test
    fun `a broken file is an empty buffer, not a crash`() {
        val store = MemoryStore().apply { value = "{not json" }
        assertEquals(emptyList<LogEntry>(), buffer(store = store).entries())
    }

    @Test
    fun `This device's page keeps the range, the levels and the search, and counts its own lines`() {
        val log = buffer()
        clock = 1_800_000_000_000L
        log.add("error", "PPM", "an hour and more ago")
        clock += 2 * 60 * 60_000L
        log.add("error", "toast", "Could not save")
        log.add("debug", "NativeTerminal", "Replayed 10 bytes")
        log.add("info", "PPM", "opened server")
        val filter = DEFAULT_LOG_FILTER.copy(src = DEVICE_LOG_SOURCE)

        val page = deviceLogResult(log.entries(), filter, "1h", nowMs = clock)
        assertEquals(listOf("Could not save", "opened server"), page.entries.map { it.msg })
        assertEquals(3, page.inRange)
        assertFalse(page.hasMore)
        assertEquals(3, page.stats[DEVICE_LOG_SOURCE]!!.total)
        assertEquals(1, page.stats[DEVICE_LOG_SOURCE]!!.err)

        assertEquals(4, deviceLogResult(log.entries(), filter, "all", nowMs = clock).stats[DEVICE_LOG_SOURCE]!!.total)
        assertEquals(listOf("opened server"), deviceLogResult(log.entries(), filter.copy(q = "SERVER"), "1h", nowMs = clock).entries.map { it.msg })
        assertTrue(deviceLogResult(log.entries(), filter.copy(q = "(", regex = true), "1h", nowMs = clock).badRegex)
    }
}
