package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.deleteDraft
import com.hienlh.ppm.client.draft
import com.hienlh.ppm.client.saveDraft
import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.DraftAttachment
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This device's copy of each tab's draft (the web keeps it in `sessionStorage`): written on
 * every edit, so text typed in the second before the app was killed is still there when it
 * opens again, though the server never had it. Until the server has it, it wins over the
 * server's copy; after that it is only what to show when the server cannot be asked, so a
 * draft carried on from another device is not hidden behind it.
 */
class DraftLocalStore(
    private val values: KeyValueStore,
    /** The server, so that two servers' drafts never meet. */
    private val scope: String,
) {
    /** A draft as this device keeps it; [synced] once the server is known to hold the same. */
    class Copy(val draft: ChatDraft, val synced: Boolean)

    /** `[project, tabId, id]`, as the web keys it. */
    fun key(project: String, tabId: String, id: String): String =
        "draft.$scope." + JsonArray(listOf(JsonPrimitive(project), JsonPrimitive(tabId), JsonPrimitive(id)))

    fun read(key: String): Copy? {
        val json = values.get(key)?.let(WireJson::parseOrNull) ?: return null
        val content = json["content"].string ?: return null
        val files = json["attachments"].array ?: return null
        return Copy(ChatDraft.stored(content, files.mapNotNull(DraftAttachment::from)), json["synced"].bool ?: false)
    }

    fun write(key: String, draft: ChatDraft, synced: Boolean = false) {
        val value = buildJsonObject {
            put("content", draft.storedContent)
            put("attachments", JsonArray(draft.attachments.map { buildJsonObject { put("name", it.name); put("path", it.path) } }))
            if (synced) put("synced", true)
        }
        values.put(key, value.toString())
    }

    /** The server took [draft]: noted, unless something newer was typed since. */
    fun markSynced(key: String, draft: ChatDraft) {
        val local = read(key) ?: return
        if (local.draft != draft || local.synced) return
        write(key, draft, synced = true)
    }

    fun remove(key: String) = values.put(key, null)
}

/**
 * One chat tab's draft (`use-draft.ts`): loaded when the tab starts, saved on each edit — here at
 * once, on the server a second after typing stops — and deleted once its message is on a socket.
 * A new chat's is kept under [NEW_CHAT] until its first message creates the session ([moveTo]).
 * Calls are made from [scope]'s single thread, as every controller's are.
 */
class DraftSync(
    private val api: ApiClient,
    val project: String,
    val tabId: String,
    /** The session it belongs to, which the server keeps it under; [NEW_CHAT] for a chat with none yet. */
    sessionId: String,
    private val store: DraftLocalStore,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 1_000,
) {
    var sessionId = sessionId
        private set
    private var localKey = store.key(project, tabId, sessionId)

    /** The save waiting out the debounce, and what it saves. */
    private var pendingSave: Job? = null
    private var pendingDraft: ChatDraft? = null

    /** The last request sent: each waits for the one before, so a save never lands after the delete that followed it. */
    private var lastRequest: Job? = null

    /** Bumped by every save and clear: a load that started before one is out of date. */
    private var revision = 0

    /**
     * The draft to show: this device's copy while the server does not have it yet, else the
     * server's, and this device's again when the server cannot be asked. Null when there is
     * none, or when an edit came first (what was typed wins). [read] is the server's answer
     * when something already asked for it (a new chat's `/chat/prepare`); should it fail, the
     * draft is asked for on its own.
     */
    suspend fun load(read: (suspend () -> ChatDraft?)? = null): ChatDraft? {
        val local = store.read(localKey)
        if (local != null && !local.synced) return local.draft
        val revision = revision
        val server = try {
            Result.success(fromServer(read))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (revision != this.revision) return null
        if (server.isFailure) return local?.draft
        val draft = server.getOrNull()
        // Gone from the server: sent or cleared somewhere else.
        if (draft == null && local != null) store.remove(localKey)
        return draft
    }

    private suspend fun fromServer(read: (suspend () -> ChatDraft?)?): ChatDraft? {
        if (read != null) {
            try {
                return read()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return api.draft(project, sessionId)
    }

    /**
     * The session a new chat's first message created (`moveDraft`): this device's copy goes
     * under it, as one the server does not have yet, and later saves follow. The server's copy
     * stays where it is until [clear] names it.
     */
    fun moveTo(sessionId: String) {
        if (sessionId == this.sessionId) return
        val next = store.key(project, tabId, sessionId)
        store.read(localKey)?.let { copy ->
            store.write(next, copy.draft)
            store.remove(localKey)
        }
        this.sessionId = sessionId
        localKey = next
    }

    /** Kept here at once, and on the server once typing has stopped for a second. A save already waiting is replaced; one that has started still lands. */
    fun save(draft: ChatDraft) {
        revision++
        store.write(localKey, draft)
        pendingSave?.cancel()
        pendingDraft = draft
        pendingSave = scope.launch {
            delay(debounceMillis)
            pendingSave = null
            pendingDraft = null
            send(draft)
        }
    }

    /** A save still waiting goes now: the app is leaving the screen, and may be killed before the second is up. */
    fun flush() {
        val draft = pendingDraft ?: return
        cancelPendingSave()
        send(draft)
    }

    /**
     * Drops a save still waiting (`cancelPendingSave`), without touching the server. Called as a
     * message is sent: the send deletes the draft once the message is on a socket, usually within
     * that second, and a save landing after it would bring the message back as a draft.
     */
    fun cancelPendingSave() {
        pendingSave?.cancel()
        pendingSave = null
        pendingDraft = null
    }

    /**
     * Deletes the draft once its message has gone. [draftId] is the one it was written under:
     * a new chat's first message went out after [moveTo], with its draft still under [NEW_CHAT].
     */
    fun clear(draftId: String = sessionId) {
        revision++
        store.remove(localKey)
        if (draftId != sessionId) store.remove(store.key(project, tabId, draftId))
        cancelPendingSave()
        request { api.deleteDraft(project, draftId) }
    }

    private fun send(draft: ChatDraft) {
        // Under the session it was typed for, even should the request wait behind another.
        val id = sessionId
        val key = localKey
        request {
            api.saveDraft(project, id, draft)
            store.markSynced(key, draft)
        }
    }

    /** [call] after the request before it; a failure is dropped, as the web drops it (this device's copy still has the text). */
    private fun request(call: suspend () -> Unit) {
        val previous = lastRequest
        lastRequest = scope.launch {
            previous?.join()
            try {
                call()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** What a new chat's draft is kept under until it has a session: one per project on the server. */
        const val NEW_CHAT = "__new__"
    }
}
