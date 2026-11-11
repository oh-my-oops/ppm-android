package com.hienlh.ppm.state

import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.AccountPick
import com.hienlh.ppm.protocol.ChatPreparationSettings
import com.hienlh.ppm.protocol.PreparedChat
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This device's copy of what a new chat tab is resolved from before any request answers
 * (chat-preference-local-cache.ts): the provider part of the AI settings, once per server, and
 * each project's providers. Nothing else of the settings is ever kept.
 */
class NewChatCache(private val values: KeyValueStore, private val scope: String) {
    private val settingsKey = "newChat.$scope.settings"

    private fun providersKey(project: String) = "newChat.$scope.providers." + JsonPrimitive(project)

    fun settings(): ChatPreparationSettings? = values.get(settingsKey)?.let(WireJson::parseOrNull)?.let(ChatPreparationSettings::from)

    fun writeSettings(settings: ChatPreparationSettings) {
        val json = buildJsonObject {
            put("default_provider", settings.defaultProvider)
            settings.newChatProviderMode?.let { put("new_chat_provider_mode", it) }
            val ids = settings.permissionModes.keys + settings.defaults.keys
            put("providers", JsonObject(ids.associateWith { id ->
                buildJsonObject {
                    settings.permissionModes[id]?.let { put("permission_mode", it) }
                    settings.defaults[id]?.model?.let { put("model", it) }
                    settings.defaults[id]?.effort?.let { put("effort", it) }
                }
            }))
        }
        values.put(settingsKey, json.toString())
    }

    fun providers(project: String): List<ProviderInfo>? =
        values.get(providersKey(project))?.let(WireJson::parseOrNull)?.array?.mapNotNull(ProviderInfo::from)

    private fun revisionKey(project: String) = providersKey(project) + ".revision"

    /** Capture before starting a request; shared by cache instances for this server. */
    fun projectRevision(project: String): Long = values.get(revisionKey(project))?.toLongOrNull() ?: 0L

    fun forgetProject(project: String) {
        values.put(revisionKey(project), (projectRevision(project) + 1L).toString())
        values.put(providersKey(project), null)
    }

    fun writeProvidersIfCurrent(project: String, revision: Long, providers: List<ProviderInfo>): Boolean {
        if (projectRevision(project) != revision) return false
        writeProviders(project, providers)
        return true
    }

    fun writeProviders(project: String, providers: List<ProviderInfo>) {
        val json = JsonArray(providers.map { buildJsonObject { put("id", it.id); put("name", it.name) } })
        values.put(providersKey(project), json.toString())
    }
}

/**
 * What new chat tabs read before their session (chat-preparation-cache.ts), kept in memory for
 * [ttlMillis]: the provider part of the AI settings, and each project's providers, which a tab's
 * preparation seeds so the provider chip opens on a fresh list. One request at a time per key,
 * bounded to [timeoutMillis]; a failed one is forgotten. [clear] drops both, and moves
 * [generation] on so a resolution begun before it knows it is out of date. [scope] must be a
 * supervisor on one thread, as the app's is.
 */
class ChatPreparationCache(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 60_000,
    private val timeoutMillis: Long = 30_000,
) {
    private class Entry<T>(var value: T? = null, var expires: Long = 0, var request: Deferred<T>? = null)

    private val settings = HashMap<String, Entry<ChatPreparationSettings>>()
    private val providers = HashMap<String, Entry<List<ProviderInfo>>>()

    /** Moved on by [clear] (`chatPreparationGeneration`). */
    var generation = 0
        private set

    suspend fun settings(project: String, fetch: suspend () -> ChatPreparationSettings): ChatPreparationSettings = cached(settings, project, fetch)

    suspend fun providers(project: String, fetch: suspend () -> List<ProviderInfo>): List<ProviderInfo> = cached(providers, project, fetch)

    /** The project's providers while fresh, without asking (`peekChatProviders`). */
    fun peekProviders(project: String): List<ProviderInfo>? = providers[project]?.takeIf { it.expires > now() }?.value

    /** `seedChatProviders`: what a preparation answered, fresh from now. */
    fun seedProviders(project: String, list: List<ProviderInfo>) {
        providers[project] = Entry(list, now() + ttlMillis)
    }

    /** `clearChatPreparationCache`: Retry, Check again, or AI settings saved. */
    fun clear() {
        generation++
        settings.clear()
        providers.clear()
    }

    private suspend fun <T> cached(cache: HashMap<String, Entry<T>>, key: String, fetch: suspend () -> T): T {
        val previous = cache[key]
        previous?.value?.let { if (previous.expires > now()) return it }
        previous?.request?.let { return it.await() }
        val entry = Entry<T>()
        cache[key] = entry
        lateinit var request: Deferred<T>
        request = scope.async(start = CoroutineStart.LAZY) {
            try {
                val value = withTimeoutOrNull(timeoutMillis) { Answer(fetch()) }?.value
                    ?: throw IllegalStateException("Chat preparation timed out. Please retry.")
                if (cache[key] === entry) {
                    entry.value = value
                    entry.expires = now() + ttlMillis
                    entry.request = null
                }
                value
            } catch (error: Exception) {
                if (cache[key] === entry) cache.remove(key)
                throw error
            }
        }
        entry.request = request
        request.start()
        return request.await()
    }

    private class Answer<T>(val value: T)
}

/**
 * One `/chat/prepare` per chat tab with no session (new-chat-prepare-client.ts): asking again
 * while it is on its way, or within [joinMillis] of its answer, joins it. Two facts outlive that
 * window, so that they are not learned again by asking the server: the tab's stretch without a
 * session was prepared (another ask would take another account from the pool), and its pick found
 * no account to use. [forget] drops all three once the tab has a session. [scope] must be a
 * supervisor on one thread, as the app's is: a failed preparation is only its asker's to see.
 */
class ChatPreparations(private val scope: CoroutineScope, private val joinMillis: Long = 30_000) {
    private val inFlight = HashMap<String, Deferred<PreparedChat>>()
    private val prepared = HashSet<String>()
    private val noAccount = HashMap<String, String>()

    /**
     * [tabId]'s preparation: the one on its way or just answered, else a new one by [request].
     * Once it answers, [applied] gets the answer, told whether the tab still waits on it — a tab
     * that got its session, or was forgotten, meanwhile has moved on.
     */
    fun start(tabId: String, request: suspend () -> PreparedChat, applied: (PreparedChat, current: Boolean) -> Unit): Deferred<PreparedChat> {
        inFlight[tabId]?.let { return it }
        lateinit var preparation: Deferred<PreparedChat>
        preparation = scope.async(start = CoroutineStart.LAZY) {
            val result = request()
            val current = inFlight[tabId] === preparation
            if (current) {
                prepared += tabId
                if (result.pickedAccount == AccountPick.None) noAccount[tabId] = result.providerId
            }
            applied(result, current)
            result
        }
        inFlight[tabId] = preparation
        preparation.start()
        scope.launch {
            preparation.join()
            delay(joinMillis)
            if (inFlight[tabId] === preparation) inFlight.remove(tabId)
        }
        return preparation
    }

    /** The preparation on its way or just answered, if any. */
    operator fun get(tabId: String): Deferred<PreparedChat>? = inFlight[tabId]

    /** Whether this stretch of the tab was prepared, even long after the answer. */
    fun isPrepared(tabId: String): Boolean = tabId in prepared

    /** The preparation asked for an account on [providerId] and heard that none can be used: final for the tab. */
    fun foundNoAccount(tabId: String, providerId: String): Boolean = noAccount[tabId] == providerId

    fun forget(tabId: String) {
        inFlight.remove(tabId)
        prepared.remove(tabId)
        noAccount.remove(tabId)
    }
}
