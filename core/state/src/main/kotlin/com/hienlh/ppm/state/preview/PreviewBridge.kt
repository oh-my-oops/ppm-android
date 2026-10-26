package com.hienlh.ppm.state.preview

import com.hienlh.ppm.protocol.BridgeChildMessage
import com.hienlh.ppm.protocol.CanvasCheckReport
import com.hienlh.ppm.protocol.LayoutCheckReport
import com.hienlh.ppm.protocol.MAX_BRIDGE_ISSUES
import com.hienlh.ppm.protocol.RuntimeIssue
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.checkRunEnvelope
import com.hienlh.ppm.protocol.newBridgeNonce
import com.hienlh.ppm.protocol.withRuntimeIssues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The parent side of one HTML preview's bridge (use-design-bridge.ts, and the check of
 * use-design-canvas-check.ts as html-preview.tsx runs it): which page messages count, what goes
 * back to the page, the runtime issues of the current load, and the page's self-check.
 *
 * The view relays what the page's frame posts ([receive]: only messages whose source is that
 * frame) and the frame's `load` events ([frameLoaded]); [post] takes an envelope to the frame. A
 * message counts only with the current load's nonce: a page the frame navigated to on its own
 * never saw it. Nothing goes to the frame while it looks dead — loaded more often than it said
 * `ready` — the only window in which such a page could otherwise catch a message meant for the
 * real one. Not thread-safe: the main thread drives it.
 */
class PreviewBridge(private val post: (JsonObject) -> Boolean) {
    /** The current load's nonce; null until the first load starts. */
    var nonce: String? = null
        private set

    /** The last `ready` of the current load, or null while the page is (re)loading. */
    var ready: BridgeChildMessage.Ready? = null
        private set

    private var loads = 0
    private var readies = 0
    private val issues = ArrayList<RuntimeIssue>()
    private val readyWaiters = ArrayList<CompletableDeferred<BridgeChildMessage.Ready>>()
    private val checks = HashMap<String, CompletableDeferred<Result<LayoutCheckReport>>>()

    /** A new document is on its way under [nonce]: nothing from the old one counts any more. */
    fun startLoad(nonce: String) {
        this.nonce = nonce
        loads = 0
        readies = 0
        ready = null
        issues.clear()
    }

    /** The frame fired `load`. */
    fun frameLoaded() {
        loads++
    }

    /** What the page's frame posted, as JSON text. */
    fun receive(text: String) {
        val message = WireJson.parseOrNull(text)?.let(BridgeChildMessage::parse) ?: return
        val current = nonce ?: return
        if (message.nonce != current) return
        when (message) {
            is BridgeChildMessage.Ready -> {
                readies++
                ready = message
                for (waiter in readyWaiters.toList()) waiter.complete(message)
            }
            is BridgeChildMessage.Issue ->
                if (issues.size < MAX_BRIDGE_ISSUES) issues += RuntimeIssue(message.kind, message.message, message.source, message.line)
            is BridgeChildMessage.CheckResult -> checks[message.requestId]?.complete(Result.success(message.report))
            is BridgeChildMessage.CheckError -> checks[message.requestId]?.complete(Result.failure(IllegalStateException(message.message)))
        }
    }

    /** Posts [envelope] to the page, unless no load is current or the frame looks dead. */
    fun send(envelope: JsonObject): Boolean {
        if (nonce == null || loads > readies) return false
        return post(envelope)
    }

    /**
     * Measures the page as it is now (`runCanvasCheck`): the frame measures itself, and this side
     * adds the runtime issues of the load and the device [frame]. The screenshot library comes from
     * [lib] only when a [screenshot] is wanted. Throws, with what to tell the AI, when the page
     * cannot answer.
     */
    suspend fun check(screenshot: Boolean, frame: String, lib: suspend () -> String?): CanvasCheckReport {
        val source = if (!screenshot) {
            null
        } else {
            try {
                lib()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val ready = ready ?: nextReady() ?: throw IllegalStateException("The canvas is still loading")
        val requestId = newBridgeNonce()
        val answer = CompletableDeferred<Result<LayoutCheckReport>>()
        checks[requestId] = answer
        val report = try {
            val envelope = checkRunEnvelope(requestId, screenshot, source)
            if (envelope == null || !send(envelope)) throw IllegalStateException("The canvas is not accepting messages")
            withTimeoutOrNull(CHECK_TIMEOUT_MS) { answer.await() }?.getOrThrow()
                ?: throw IllegalStateException("The canvas did not answer in time")
        } finally {
            checks -= requestId
        }
        val withIssues = report.withRuntimeIssues(issues.toList())
        val note = if (screenshot && source == null) "The screenshot library could not be loaded." else withIssues.screenshotNote
        return CanvasCheckReport(withIssues.copy(screenshotNote = note), ready.file, ready.gen, frame)
    }

    /** The next `ready`, from this load or the one that replaces it; null if none comes in time. */
    private suspend fun nextReady(): BridgeChildMessage.Ready? {
        val waiter = CompletableDeferred<BridgeChildMessage.Ready>()
        readyWaiters += waiter
        try {
            return withTimeoutOrNull(READY_WAIT_MS) { waiter.await() }
        } finally {
            readyWaiters -= waiter
        }
    }

    companion object {
        const val CHECK_TIMEOUT_MS = 15_000L

        /** How long a page that is (re)loading gets to say `ready` before a check gives up. */
        const val READY_WAIT_MS = 8_000L
    }
}
