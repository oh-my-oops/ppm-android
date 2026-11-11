package com.hienlh.ppm.app.ui.phone.onboarding

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A new PPM feature the tour asks about once per install (`FeatureIntro` of `src/shared/feature-intros.ts`). */
data class FeatureIntro(val id: String, val title: String, val body: String, val tools: List<String>, val since: String = "")

/** `GET /api/feature-intros` (`FeatureIntrosView`). */
data class FeatureIntrosView(
    val pending: List<FeatureIntro>,
    /** The install has at least one chat session: it gets the question at once and no basic-tour offer. */
    val isExistingUser: Boolean,
    val newInSettings: List<String> = emptyList(),
)

/** `parseFeatureIntrosView`: the server's answer, or null when it is not one, which shows nothing. */
fun parseFeatureIntrosView(json: JsonElement?): FeatureIntrosView? {
    val pending = json["pending"].array ?: return null
    val existing = json["isExistingUser"].bool ?: return null
    return FeatureIntrosView(
        pending = pending.mapNotNull { raw ->
            val id = raw["id"].string ?: return@mapNotNull null
            val title = raw["title"].string ?: return@mapNotNull null
            val body = raw["body"].string ?: return@mapNotNull null
            val tools = raw["tools"].array?.map { it.string ?: return@mapNotNull null } ?: return@mapNotNull null
            FeatureIntro(id, title, body, tools, raw["since"].string.orEmpty())
        },
        isExistingUser = existing,
        newInSettings = json["newInSettings"].array.orEmpty().mapNotNull { it.string },
    )
}

/** The three calls the question makes (`feature-intros-client.ts`). */
interface FeatureIntrosBackend {
    suspend fun load(): FeatureIntrosView?
    suspend fun answer(id: String, on: Boolean)
    suspend fun skip(id: String)

    companion object {
        private fun base(id: String) = "/api/feature-intros/${ApiClient.escape(id)}"

        fun api(client: ApiClient): FeatureIntrosBackend = object : FeatureIntrosBackend {
            override suspend fun load() = parseFeatureIntrosView(client.call("GET", "/api/feature-intros"))
            override suspend fun answer(id: String, on: Boolean) {
                client.call("POST", "${base(id)}/answer", body = buildJsonObject { put("answer", if (on) "on" else "off") })
            }
            override suspend fun skip(id: String) {
                client.call("POST", "${base(id)}/skip")
            }
        }
    }
}

/**
 * The tour's "PPM has new features" part for this app open (`feature-intros-store.ts`). What has
 * been asked and answered is the server's, install-wide; this only remembers what this app open
 * already did, so it records at most one skip per feature, and a refetch after its own answer or
 * skip does not bring the question back. Nothing here is persisted.
 */
@Stable
class PhoneFeatureIntros(private val backend: FeatureIntrosBackend, private val scope: CoroutineScope) {
    var view by mutableStateOf<FeatureIntrosView?>(null)
        private set

    /** The first load finished, with an answer or without one (an unreachable or older server). */
    var settled by mutableStateOf(false)
        private set

    /** Ids in the order they were first shown, so the counter does not jump on a refetch. */
    private var order by mutableStateOf(emptyList<String>())

    /** Answered or put off on this device during this app open. */
    private var handled by mutableStateOf(emptySet<String>())

    /** Skips already recorded during this app open. */
    private var skipped = emptySet<String>()

    /** The part was closed for the rest of this app open. */
    var closed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loadSeq = 0

    /** Every feature still pending on the server or handled here, first-seen order (`featureIntroSteps`). */
    val steps: List<String>
        get() {
            val pending = view?.pending.orEmpty().map { it.id }.toSet()
            return order.filter { it in pending || it in handled }
        }

    val remaining: List<FeatureIntro>
        get() {
            val byId = view?.pending.orEmpty().associateBy { it.id }
            return steps.filter { it !in handled }.mapNotNull { byId[it] }
        }

    val current: FeatureIntro? get() = remaining.firstOrNull()

    /**
     * `featurePartAllowed`: a basic tour under way on this device always goes first; otherwise an
     * install already in use gets the part at once, and a new one once its tour is done or dismissed.
     */
    fun allowed(tourInProgress: Boolean, tourDone: Boolean): Boolean {
        val v = view ?: return false
        if (closed || remaining.isEmpty() || tourInProgress) return false
        return v.isExistingUser || tourDone
    }

    fun load() {
        val seq = ++loadSeq
        scope.launch {
            try {
                val loaded = backend.load()
                // A slower, older request must not bring back a feature a newer one says is answered.
                if (seq != loadSeq) return@launch
                if (loaded != null) {
                    view = loaded
                    order = order + loaded.pending.map { it.id }.filter { it !in order }
                }
                settled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never worth an error in the user's way; the next open asks again.
                if (seq == loadSeq) settled = true
            }
        }
    }

    fun answer(id: String, on: Boolean) {
        busy = true
        error = null
        scope.launch {
            try {
                backend.answer(id, on)
                handled = handled + id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not save your choice: ${e.message ?: "request failed"}"
            } finally {
                busy = false
            }
        }
    }

    fun later(id: String) {
        handled = handled + id
        scope.launch { skipOnce(id) }
    }

    /** Closing without answering puts off every feature not yet handled, once per app open. */
    fun close() {
        val open = remaining.map { it.id }
        closed = true
        error = null
        handled = handled + open
        scope.launch { for (id in open) skipOnce(id) }
    }

    /** Another device put [id] off: handled here too, without a skip of this device's own. */
    fun skippedElsewhere(id: String) {
        handled = handled + id
        skipped = skipped + id
    }

    /** A restarted tour is a new app open for the question. */
    fun reset() {
        loadSeq++
        view = null
        settled = false
        order = emptyList()
        handled = emptySet()
        skipped = emptySet()
        closed = false
        busy = false
        error = null
    }

    private suspend fun skipOnce(id: String) {
        if (id in skipped) return
        skipped = skipped + id
        try {
            backend.skip(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
