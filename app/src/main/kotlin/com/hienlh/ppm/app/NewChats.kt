package com.hienlh.ppm.app

import com.hienlh.ppm.client.chatPreparationSettings
import com.hienlh.ppm.client.pickAccount
import com.hienlh.ppm.client.prepareChat
import com.hienlh.ppm.client.prewarmChat
import com.hienlh.ppm.protocol.AccountPick
import com.hienlh.ppm.protocol.ChatDraft
import com.hienlh.ppm.protocol.ChatPreparationSettings
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.PickedAccount
import com.hienlh.ppm.protocol.PreparedChat
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.ReplyReference
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.ChatPreparationCache
import com.hienlh.ppm.state.ChatPreparations
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.UsageScope
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** A new chat's first message as `handleSend` builds it, kept until its session can take it. */
class FirstMessage(
    /** What was typed, an agent's words included: the session is titled by its first 50 characters. */
    val text: String,
    /** The message as it goes, its attachments' markers ahead of the text. */
    val content: String,
    val images: List<InlineImage>,
    val imagePaths: List<String>,
    val replyTo: ReplyReference?,
)

/** The first message as it shows while its session is made (`firstSendPreview`). */
class FirstSendPreview(val content: String, val timestamp: Instant)

/** Why a new chat's provider could not be resolved (new-chat-provider-gate.tsx), with what the gate line offers. */
sealed interface NewChatGate {
    /** The provider the settings name is not one the project offers: "Use" one that is, or "Check again". */
    data class Unavailable(val provider: String, val settings: ChatPreparationSettings, val alternatives: List<ProviderInfo>) : NewChatGate

    /** The settings could not be read: "Retry". */
    data class Failed(val message: String) : NewChatGate
}

/** A tab's provider once resolved, and the mode it starts in (the gate's `PreparedChat`). */
data class ResolvedProvider(val providerId: String, val permissionMode: String?)

/** What a new chat's first message will run with (`useChatPrewarm`'s input): the process warmed for it serves only a match. */
data class PrewarmAsk(val providerId: String, val permissionMode: String?, val accountId: String?, val picks: TurnOptions)

/**
 * Every chat tab with no session yet: its one `/chat/prepare` (new-chat-prepare-client.ts), the
 * provider it resolves to while pending (new-chat-provider-gate.tsx), the account it claims
 * (use-chat-account-claim.ts), and its first message, which makes the session the tab then keeps
 * (chat-tab.tsx). Main thread only, as the model is.
 */
internal class NewChats(private val model: AppModel) {
    private val preparations = ChatPreparations(model.scope)

    /** The settings and providers read for new chats, a minute at a time; the provider chip opens on it. */
    internal val preparationCache = ChatPreparationCache(model.scope)

    /** The gate's resolution on its way, by tab (`inFlight`). */
    private val resolving = HashMap<String, Deferred<ResolvedProvider>>()

    /**
     * The tab's one `/chat/prepare`, when it is first shown: none for a stretch without a session
     * that was prepared already, and no account picked when the tab holds one for its provider.
     */
    fun prepare(chat: ChatTab) {
        val api = model.api ?: return
        val tabId = chat.tabId
        if (chat.session != null || preparations.isPrepared(tabId)) return
        val meta = model.tabMetadata(tabId) ?: chat.metadata
        val pendingOnOpen = meta["providerPending"].bool == true
        val openedOn = if (pendingOnOpen) null else meta["providerId"].string
        val focused = if (pendingOnOpen) meta["focusedProviderOnOpen"].string else null
        // A pending tab does not know its provider yet, so any claim it carries counts.
        val claimedFor = if (meta["pickedAccountId"].string != null) meta["pickedAccountProvider"].string else null
        val skipPick = claimedFor != null && (pendingOnOpen || claimedFor == openedOn)
        val project = chat.project
        // Taken now: what it answers belongs to this server, whichever is open by then.
        val cache = model.newChatCache
        val slash = model.slashItems
        val usages = model.usageCache
        preparations.start(
            tabId,
            request = { bounded(PREPARE_MILLIS, "The server did not answer in time.") { api.prepareChat(project, openedOn, focused, skipPick) } },
        ) { result, current ->
            cache.writeSettings(result.settings)
            cache.writeProviders(project, result.providers)
            preparationCache.seedProviders(project, result.providers)
            result.slash?.let { slash?.seed(it, project, result.providerId) }
            result.tags?.let { model.seedTags(project, it) }
            if (current) {
                applyPrepared(tabId, result)
                // `seedUsage`: the chip's first reading, under the scope of the account just picked.
                result.usage?.let {
                    val picked = (result.pickedAccount as? AccountPick.Picked)?.account
                    usages?.seed(UsageScope(project, result.providerId, null, picked?.id), it, it.lastFetchedAt)
                }
            }
        }
    }

    /** The tab's preparation on its way or just answered (`getPrepare`), which its usage reading waits on. */
    fun preparation(tabId: String): Deferred<PreparedChat>? = preparations[tabId]

    /** The draft the tab's preparation read for its box; null with none to join, so the draft is asked for. */
    fun preparedDraft(chat: ChatTab): (suspend () -> ChatDraft?)? {
        if (chat.session != null) return null
        val preparation = preparations[chat.tabId] ?: return null
        return {
            try {
                preparation.await().draft
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("The chat preparation stopped.")
            }
        }
    }

    /**
     * A tab with no session was first shown, after [prepare]: one restored still pending resolves
     * from this device's cache before it is drawn when it can, else from the server; one that
     * knows its provider claims its account.
     */
    fun started(chat: ChatTab) {
        if (chat.session != null) return
        warmResolve(chat)
        if (chat.providerPending) resolveQuietly(chat) else claimInBackground(chat)
    }

    /**
     * The tab changed in the layout (the `metadata` prop): the chat takes its title and pending
     * flag, and, with no session and a provider resolved, that provider and its mode.
     */
    fun follow(chat: ChatTab, tab: WorkspaceTab) {
        val meta = tab.metadata
        chat.metadata = meta
        chat.tabTitle = tab.title
        val pending = meta["providerPending"].bool == true
        val wasPending = chat.providerPending
        chat.providerPending = pending
        if (chat.session != null || pending) return
        val provider = meta["providerId"].string ?: return
        val changed = provider != chat.providerId
        chat.chooseProvider(provider)
        chat.permissionMode = PermissionMode.of(meta["permissionMode"].string)
        if (chat.started && (changed || wasPending)) claimInBackground(chat)
    }

    private fun follow(chat: ChatTab) {
        model.tabOf(chat.tabId)?.let { follow(chat, it) }
    }

    /**
     * Whether a mode the server answers may replace the tab's (`permissionReplaceable`): only one
     * cached from the settings, or none. One picked by hand, or kept from a session, is a decision.
     */
    private fun permissionReplaceable(chat: ChatTab): Boolean {
        if (chat.permissionChosenByUser) return false
        val meta = model.tabMetadata(chat.tabId) ?: chat.metadata
        return meta["permissionMode"].string.isNullOrEmpty() || meta["permissionModeSource"].string == "cache"
    }

    /**
     * The tab's preparation answered while it still had no session (`applyPrepared` and the
     * permission effect): its account becomes the tab's claim, and its mode the chip's, unless the
     * tab's mode is a decision rather than a cached default.
     */
    private fun applyPrepared(tabId: String, result: PreparedChat) {
        applyAccountClaim(tabId, result.providerId, (result.pickedAccount as? AccountPick.Picked)?.account)
        val chat = model.chat(tabId)?.takeIf { it.session == null } ?: return
        if (!permissionReplaceable(chat)) return
        val fresh = result.settings.permissionMode(result.providerId)
        if (fresh == model.tabMetadata(tabId)?.get("permissionMode").string) return
        chat.permissionMode = PermissionMode.of(fresh)
        model.patchTabMetadata(tabId, "permissionMode" to JsonPrimitive(fresh), "permissionModeSource" to JsonPrimitive("cache"))
    }

    /**
     * `applyAccountClaim`: [picked] becomes the tab's claim, unless the tab already claims one on
     * [provider], has a session, or has moved to another provider.
     */
    private fun applyAccountClaim(tabId: String, provider: String, picked: PickedAccount?): PickedAccount? {
        val meta = model.tabMetadata(tabId) ?: return picked
        if (picked != null && meta["sessionId"].string.isNullOrEmpty() &&
            (meta["pickedAccountId"].string.isNullOrEmpty() || meta["pickedAccountProvider"].string != provider) &&
            (meta["providerPending"].bool == true || meta["providerId"].string == provider)
        ) {
            model.patchTabMetadata(
                tabId,
                "pickedAccountId" to JsonPrimitive(picked.id),
                "pickedAccountLabel" to (picked.label?.let(::JsonPrimitive) ?: JsonNull),
                "pickedAccountProvider" to JsonPrimitive(provider),
            )
        }
        return picked
    }

    /** The account the tab claims on [provider], from its metadata. */
    private fun heldClaim(tabId: String, provider: String): PickedAccount? {
        val meta = model.tabMetadata(tabId) ?: return null
        if (meta["pickedAccountProvider"].string != provider) return null
        val id = meta["pickedAccountId"].string ?: return null
        return PickedAccount(id, meta["pickedAccountLabel"].string)
    }

    /**
     * The account [chat] starts on with [provider] (`claim`): the one it holds; none once its
     * preparation heard there is none; else one from the pool — asked only after its preparation,
     * which may pick one itself, so a quick send does not take two. One claim per provider.
     */
    suspend fun claim(chat: ChatTab, provider: String): PickedAccount? {
        val tabId = chat.tabId
        heldClaim(tabId, provider)?.let { return it }
        if (preparations.foundNoAccount(tabId, provider)) return null
        chat.claims[provider]?.let { return applyAccountClaim(tabId, provider, it.await()) }
        val api = model.api ?: return null
        val preparation = preparations[tabId]
        lateinit var claim: Deferred<PickedAccount?>
        claim = model.scope.async(start = CoroutineStart.LAZY) {
            try {
                preparation?.join()
                heldClaim(tabId, provider)?.let { return@async it }
                if (preparations.foundNoAccount(tabId, provider)) return@async null
                val picked = bounded(PICK_MILLIS, "The account pick timed out.") { api.pickAccount(provider) }
                if (model.chat(tabId) === chat) applyAccountClaim(tabId, provider, picked) else picked
            } catch (error: Exception) {
                if (chat.claims[provider] === claim) chat.claims.remove(provider)
                throw error
            }
        }
        chat.claims[provider] = claim
        claim.start()
        return applyAccountClaim(tabId, provider, claim.await())
    }

    /** The claim a tab makes once it knows its provider (`useChatAccountClaim`'s effect), unless it holds one for it. */
    private fun claimInBackground(chat: ChatTab) {
        if (chat.session != null || chat.providerPending) return
        val provider = chat.providerId
        if (heldClaim(chat.tabId, provider) != null) return
        model.scope.launch {
            try {
                claim(chat, provider)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A tab restored still pending, with this device's cache able to answer: resolved before it is
     * drawn (the gate's layout effect), so its first frame is not "Preparing chat…".
     */
    private fun warmResolve(chat: ChatTab) {
        val meta = model.tabMetadata(chat.tabId) ?: return
        if (meta["providerPending"].bool != true) return
        val cache = model.newChatCache
        val settings = cache.settings() ?: return
        val resolved = settings.resolveProvider(meta["focusedProviderOnOpen"].string)
        if (cache.providers(chat.project)?.any { it.id == resolved } != true) return
        select(chat, resolved, settings)
    }

    /**
     * `selectProvider`: the tab resolved to [providerId], in the mode it carries, else [settings]'
     * default for that provider, marked as one its preparation may still replace.
     */
    fun select(chat: ChatTab, providerId: String, settings: ChatPreparationSettings): ResolvedProvider {
        val meta = model.tabMetadata(chat.tabId) ?: chat.metadata
        val known = meta["providerId"].string
        if (meta["providerPending"].bool != true && known != null) return ResolvedProvider(known, meta["permissionMode"].string)
        val carried = meta["permissionMode"].string
        val mode = carried ?: settings.permissionMode(providerId)
        val changes = mutableListOf<Pair<String, JsonElement?>>(
            "providerId" to JsonPrimitive(providerId),
            "permissionMode" to JsonPrimitive(mode),
            "providerPending" to null,
            "focusedProviderOnOpen" to null,
        )
        if (carried.isNullOrEmpty()) changes += "permissionModeSource" to JsonPrimitive("cache")
        model.patchTabMetadata(chat.tabId, *changes.toTypedArray())
        follow(chat)
        return ResolvedProvider(providerId, mode)
    }

    /**
     * The gate's `prepare()`: a pending tab's provider, from its preparation or, should that fail,
     * from the AI settings and the project's providers. One at a time per tab; a failure shows on
     * the gate line ([ChatTab.gate]) and is thrown to whoever waits on it.
     */
    fun resolve(chat: ChatTab): Deferred<ResolvedProvider> {
        val tabId = chat.tabId
        val meta = model.tabMetadata(tabId) ?: chat.metadata
        if (meta["providerPending"].bool != true) {
            val provider = meta["providerId"].string?.takeIf { it.isNotEmpty() }
            return CompletableDeferred<ResolvedProvider>().apply {
                if (provider != null) complete(ResolvedProvider(provider, meta["permissionMode"].string))
                else completeExceptionally(IllegalStateException("Choose a provider for this chat."))
            }
        }
        resolving[tabId]?.let { return it }
        val api = model.api ?: return CompletableDeferred<ResolvedProvider>().apply { completeExceptionally(IllegalStateException("No server is open.")) }
        chat.gate = null
        val generation = preparationCache.generation
        val project = chat.project
        val focused = meta["focusedProviderOnOpen"].string
        val preparation = preparations[tabId]
        lateinit var request: Deferred<ResolvedProvider>
        request = model.scope.async(start = CoroutineStart.LAZY) {
            try {
                val prepared = preparation?.let { answerOf(it) }
                val (settings, providers, providerId) = if (prepared != null) {
                    Triple(prepared.settings, prepared.providers, prepared.resolvedProviderId)
                } else {
                    // The settings and providers the gate read before `/chat/prepare` existed.
                    coroutineScope {
                        val read = async { preparationCache.settings(project) { api.chatPreparationSettings() } }
                        val offered = async { preparationCache.providers(project) { api.providers(project) } }
                        val settings = read.await()
                        Triple(settings, offered.await(), settings.resolveProvider(focused))
                    }
                }
                if (model.chat(tabId) !== chat) throw IllegalStateException("Chat was closed.")
                if (generation != preparationCache.generation) throw IllegalStateException("Chat settings changed. Please retry.")
                val now = model.tabMetadata(tabId)
                val known = now?.get("providerId").string
                if (now?.get("providerPending").bool != true && known != null) {
                    return@async ResolvedProvider(known, now?.get("permissionMode").string)
                }
                if (providers.none { it.id == providerId }) {
                    chat.gate = NewChatGate.Unavailable(providerId, settings, providers)
                    throw IllegalStateException("$providerId is not available. Choose a provider for this chat.")
                }
                select(chat, providerId, settings)
            } catch (error: Exception) {
                if (error !is CancellationException && model.chat(tabId) === chat && chat.gate == null) {
                    chat.gate = NewChatGate.Failed(error.message ?: "Could not load chat settings.")
                }
                throw error
            } finally {
                if (resolving[tabId] === request) resolving.remove(tabId)
            }
        }
        resolving[tabId] = request
        request.start()
        return request
    }

    private fun resolveQuietly(chat: ChatTab) {
        // Its failure is on the gate line; nobody waits on this one.
        resolve(chat)
    }

    /** Retry and Check again (`retry`): what was read before is dropped, and the provider is resolved again. */
    fun retry(chat: ChatTab) {
        preparationCache.clear()
        resolveQuietly(chat)
    }

    /**
     * A provider picked on the chip before the session (`handleProviderChange`): the tab moves to
     * it in the mode it has, and claims an account there, as an account does not cross providers.
     */
    fun pickProvider(chat: ChatTab, providerId: String) {
        if (chat.session != null || chat.providerPending || chat.firstSendPending || providerId == chat.providerId) return
        model.patchTabMetadata(chat.tabId, "providerId" to JsonPrimitive(providerId))
        chat.chooseProvider(providerId)
        if (chat.started) claimInBackground(chat)
    }

    /**
     * `useChatPrewarm`'s ask: the server starts the process [ask] describes, for the first message
     * to run on rather than wait for one to boot. Only Claude starts one; nothing comes back, and
     * a failure is let go.
     */
    fun prewarm(chat: ChatTab, ask: PrewarmAsk) {
        val api = model.api ?: return
        chat.prewarmAskedAt = System.currentTimeMillis()
        val picks = ask.picks
        model.scope.launch {
            try {
                api.prewarmChat(chat.project, ask.providerId, ask.permissionMode, ask.accountId, picks.model, picks.effort, picks.thinking)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    /** Someone writing in a new chat on screen (`touch`): asked again at most once a minute, which keeps its process while they write. */
    fun touchPrewarm(chat: ChatTab) {
        val ask = chat.prewarm ?: return
        if (System.currentTimeMillis() - chat.prewarmAskedAt >= PREWARM_REASK_MILLIS) prewarm(chat, ask)
    }

    /**
     * The first message of a chat with no session (`handleSend` without a session): shown at once
     * over "Starting conversation...", then the provider resolved, the mode its preparation answered
     * taken when the tab's is a cached default, an account claimed and the session made on it, which
     * the tab then keeps. The message goes once that session's socket is up. A step that fails puts
     * it back in the box and says why.
     */
    fun sendFirst(chat: ChatTab, message: FirstMessage) {
        val api = model.api ?: return
        if (chat.firstSendPending || chat.session != null) return
        val attempt = chat.beginFirstSend(message)
        val tabId = chat.tabId
        val project = chat.project
        chat.firstSendJob = model.scope.launch {
            val session: SessionInfo
            val mode: String?
            try {
                val resolved = if (chat.providerPending) resolve(chat).await() else null
                if (attempt != chat.firstSendAttempt) return@launch
                val provider = resolved?.providerId ?: chat.providerId
                var permission = resolved?.permissionMode ?: chat.permissionMode?.value ?: model.tabMetadata(tabId)?.get("permissionMode").string
                // The tab's own preparation may still be on its way (a send in its first second), or
                // carry a fresher mode than the tab opened with; a failed one leaves the mode as it is.
                val prepared = preparations[tabId]?.let { answerOf(it) }
                if (attempt != chat.firstSendAttempt) return@launch
                if (prepared != null && permissionReplaceable(chat)) permission = prepared.settings.permissionModes[provider] ?: permission
                chat.chooseProvider(provider)
                chat.permissionMode = PermissionMode.of(permission)
                model.patchTabMetadata(tabId, "permissionMode" to permission?.let(::JsonPrimitive))
                // Advisory, as the server's use of it is: a pick that fails starts the session on the
                // account the server chooses rather than refusing the message.
                val picked = try {
                    claim(chat, provider)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (attempt != chat.firstSendAttempt) return@launch
                val latest = model.tabMetadata(tabId)
                val accountId = if (latest?.get("pickedAccountProvider").string == provider) latest?.get("pickedAccountId").string else picked?.id
                // Bounded: the box is empty by now, and a create that hangs would leave it so over an
                // unchanged chat. One the server finished after the wait is an empty session left behind.
                session = withTimeoutOrNull(SESSION_CREATE_MILLIS) {
                    api.createSession(project, provider, title = message.text.take(50), clearedFrom = latest?.get("clearedFrom").string, accountId = accountId)
                } ?: throw ServerSilent()
                mode = permission
            } catch (error: Exception) {
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                if (attempt != chat.firstSendAttempt) return@launch
                model.signInIfRefused(error)
                val reason = if (error is ServerSilent) error.message!! else "Could not start the chat: ${error.message?.takeIf { it.isNotEmpty() } ?: "unknown error"}."
                restore(chat, reason)
                return@launch
            }
            if (attempt != chat.firstSendAttempt) return@launch
            model.addSession(project, session)
            chat.adopt(session)
            preparations.forget(tabId)
            // Kept on the tab as the session's, and the claim dropped: the session's own binding is the
            // one account that matters now. A mode a session ran in is no longer a cached default.
            val source = if (model.tabMetadata(tabId)?.get("permissionModeSource").string == "user") "user" else "inherited"
            model.patchTabMetadata(
                tabId,
                "sessionId" to JsonPrimitive(session.id),
                "providerId" to JsonPrimitive(session.providerId),
                "permissionMode" to mode?.let(::JsonPrimitive),
                "permissionModeSource" to mode?.let { JsonPrimitive(source) },
                "pickedAccountId" to null,
                "pickedAccountLabel" to null,
                "pickedAccountProvider" to null,
                "pendingMessage" to null,
            )
            // Bounded: a socket that never says hello would keep the message with nothing on screen.
            val connected = withTimeoutOrNull(PENDING_SEND_MILLIS) {
                chat.controller?.state?.first { it.sessionId == session.id && it.canSend }
            }
            if (attempt != chat.firstSendAttempt) return@launch
            if (connected == null) abandon(chat, "The chat did not connect in time.") else chat.flushFirst(mode)
        }
    }

    /** A first message that never reached its socket (`abandonPendingSend`), back in the box with [reason]. */
    fun abandon(chat: ChatTab, reason: String) {
        if (!chat.firstSendPending) return
        chat.abandonFirst()
        restore(chat, reason)
    }

    /** `restoreUnsentMessage`: the message back in the box, saved as its draft again, and said out loud. */
    private fun restore(chat: ChatTab, reason: String) {
        chat.restoreFirst(reason)
        model.toast = ErrorToastMessage("Message not sent", "$reason Your text is back in the input.")
    }

    /** [call], or a failure once [millis] pass: never a cancellation, which would cancel whoever waits on it. */
    private suspend fun <T> bounded(millis: Long, message: String, call: suspend () -> T): T {
        val answer = withTimeoutOrNull(millis) { Answer(call()) } ?: throw IllegalStateException(message)
        return answer.value
    }

    private class Answer<T>(val value: T)

    /** What [deferred] answered, or null when it failed; only this coroutine's own cancellation is thrown. */
    private suspend fun <T> answerOf(deferred: Deferred<T>): T? = try {
        deferred.await()
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Exception) {
        null
    }

    /** `AbortSignal.timeout` on the create: the one failure told as the server's silence. */
    private class ServerSilent : Exception("The server did not answer in time.")
}

/** `CHAT_PREPARATION_TIMEOUT_MS`. */
private const val PREPARE_MILLIS = 30_000L

/** `pickAccountForTab`'s own bound. */
private const val PICK_MILLIS = 30_000L

/** `SESSION_CREATE_TIMEOUT_MS`: a codex app-server's cold start alone takes about 30 s. */
private const val SESSION_CREATE_MILLIS = 30_000L

/** `PENDING_SEND_TIMEOUT_MS`. */
private const val PENDING_SEND_MILLIS = 45_000L

/** `SETTLE_MS`: how long the picks hold still before a prewarm is asked, so a tab passed through asks nothing. */
internal const val PREWARM_SETTLE_MILLIS = 300L

/** `REASK_MS`: the server keeps a warmed process 5 minutes. */
private const val PREWARM_REASK_MILLIS = 60_000L
