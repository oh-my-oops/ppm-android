package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.SocketConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class TerminalValues : KeyValueStore {
    val values = mutableMapOf<String, String>()
    var fail = false
    override fun get(key: String): String? {
        check(!fail) { "Storage unavailable" }
        return values[key]
    }
    override fun put(key: String, value: String?) {
        check(!fail) { "Storage unavailable" }
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal class TestTerminalSocket : TerminalConnection {
    override val events = Channel<SocketConnection.Event>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closed = false
    var acceptSend = true
    override fun send(text: String): Boolean {
        if (closed || !acceptSend) return false
        sent += text
        return true
    }
    override fun close() {
        if (closed) return
        closed = true
        events.trySend(SocketConnection.Event.Closed(null))
        events.close()
    }
    fun text(text: String) { events.trySend(SocketConnection.Event.Text(text)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class TerminalFixture(
    val test: TestScope,
    values: TerminalValues = TerminalValues(),
    prefix: String = "server-a:",
    tabId: String = "tab-1",
    project: String = "p /+ 雪",
    initialSessionId: String = "new",
    scope: CoroutineScope = test,
    onReset: (() -> Unit)? = null,
    onOutput: ((ByteArray) -> Unit)? = null,
    /** An owner that tells a replay from live output: it lands in [replays] and in [delivery] as `REPLAY:<text>`. Else it is plain output. */
    separateReplay: Boolean = false,
) : AutoCloseable {
    val urls = mutableListOf<String>()
    val sockets = mutableListOf<TestTerminalSocket>()
    val output = mutableListOf<ByteArray>()
    val replays = mutableListOf<ByteArray>()
    val delivery = mutableListOf<String>()
    val controller = TerminalController(
        api = ApiClient("https://ppm.test/base/?obsolete=1#old".toHttpUrl(), "t +/&?#"),
        project = project,
        tabId = tabId,
        scope = scope,
        store = values,
        keyPrefix = prefix,
        cwd = "/work/a +& 雪",
        initialSessionId = initialSessionId,
        onOutput = { output += it; delivery += it.toString(Charsets.UTF_8); onOutput?.invoke(it) },
        onReset = { delivery += "RESET"; onReset?.invoke() },
        clock = { test.testScheduler.currentTime },
        connectSocket = { url ->
            urls += url
            TestTerminalSocket().also { sockets += it }
        },
        onReplay = if (separateReplay) { bytes -> replays += bytes; delivery += "REPLAY:" + bytes.toString(Charsets.UTF_8) } else null,
    )
    val socket get() = sockets.last()
    val state get() = controller.state.value
    fun open() {
        socket.events.trySend(SocketConnection.Event.Opened)
        test.runCurrent()
    }
    fun text(text: String) {
        socket.text(text)
        test.runCurrent()
    }
    fun drop(reason: String = "Network lost") {
        socket.events.trySend(SocketConnection.Event.Closed(reason))
        test.runCurrent()
    }
    override fun close() = controller.close()
}
