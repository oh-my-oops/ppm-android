package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.providerModels
import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ModelOption
import com.hienlh.ppm.protocol.PromptCacheState
import com.hienlh.ppm.protocol.SessionStateFrame
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.TurnUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One chat's model, effort and thinking (`use-chat.ts`): what the server reports in
 * `session_state`, and what was picked here. A pick shows at once and holds until the server
 * reports the same value, so a report sent before the pick reached it cannot undo it — the
 * web guards only the model this way, and a new chat's first greeting reset its effort.
 * Confined to one thread, as [ChatController] is.
 */
class ChatSettings {
    /** The session's model, its own or its provider's default; null until the server says. */
    var model: String? = null
        private set
    var effort: String? = null
        private set

    /** On unless the server or a pick says otherwise. */
    var thinking: Boolean = true
        private set

    /** How long the prompt cache has left, from the last full report. */
    var promptCache: PromptCacheState? = null
        private set

    private var pendingModel: String? = null
    private var pendingEffort: String? = null
    private var pendingThinking: Boolean? = null

    /** Thinking rides on a message only once picked here: a default copied from the server would pin the session to it. */
    private var thinkingPick: Boolean? = null

    fun apply(state: SessionStateFrame) {
        state.model?.let { reported ->
            if (pendingModel == null || pendingModel == reported) {
                pendingModel = null
                model = reported
            }
        }
        state.effort?.let { reported ->
            if (pendingEffort == null || pendingEffort == reported) {
                pendingEffort = null
                effort = reported
            }
        }
        state.thinking?.let { reported ->
            if (pendingThinking == null || pendingThinking == reported) {
                pendingThinking = null
                thinking = reported
            }
        }
        promptCache = state.promptCache
    }

    /**
     * A finished turn rewrote the cache, so its clock restarts [now], ahead of the next report
     * (`done` in use-chat.ts): a phone left on the chat may not reconnect for hours. A figure the
     * turn did not measure keeps the one in hand; [TurnUsage.compactedAt] is cleared when absent.
     */
    fun turnEnded(usage: TurnUsage, now: Long) {
        val cache = promptCache ?: return
        promptCache = cache.copy(
            ttlMs = usage.cacheTtlMs ?: cache.ttlMs,
            lastTurnEndedAt = now.toDouble(),
            billedPrefixTokens = usage.prefixTokens.toDouble(),
            contextTokens = usage.contextTokens ?: cache.contextTokens,
            compactedAt = usage.compactedAt,
        )
    }

    /** Each returns the frame that stores the pick for the session. */
    fun pickModel(value: String): ChatClientMessage {
        model = value
        pendingModel = value
        return ChatClientMessage.SetModel(value)
    }

    fun pickEffort(value: Effort): ChatClientMessage {
        effort = value.value
        pendingEffort = value.value
        return ChatClientMessage.SetEffort(value.value)
    }

    fun pickThinking(enabled: Boolean): ChatClientMessage {
        thinking = enabled
        thinkingPick = enabled
        pendingThinking = enabled
        return ChatClientMessage.SetThinking(enabled)
    }

    /** [options] with what every message carries: the model and effort once known, thinking once picked. */
    fun turnOptions(options: TurnOptions): TurnOptions = options.copy(model = model, effort = effort, thinking = thinkingPick)
}

/** What the model list says instead of models. */
enum class ModelListStatus(val text: String) {
    LOADING("Loading models..."),
    FAILED("Unable to load models"),
    EMPTY("No models available"),
}

/**
 * The models each provider of a project offers, shared by its chats and asked again once
 * five minutes old (`modelCache` on the web). A list that loaded once stays up while it is
 * asked again, and when a later answer is empty or fails. Confined to one thread.
 */
class ModelCatalog(
    private val api: ApiClient,
    private val project: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val models: List<ModelOption>, val failed: Boolean = false, val loadedAt: Long? = null)

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Collect it to redraw when a list arrives; [models], [status] and [displayName] read it. */
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()
    private val inFlight = mutableSetOf<String>()

    fun models(providerId: String): List<ModelOption> = _entries.value[providerId]?.models.orEmpty()

    fun status(providerId: String): ModelListStatus? {
        val entry = _entries.value[providerId] ?: return ModelListStatus.LOADING
        if (entry.models.isEmpty()) return if (entry.failed) ModelListStatus.FAILED else ModelListStatus.EMPTY
        return null
    }

    /** The chip's name for [model]: the list's label (without "Claude " on Claude), else a readable id. */
    fun displayName(model: String?, providerId: String): String {
        val effective = ModelNames.effectiveModel(model, providerId) ?: return "Model"
        models(providerId).firstOrNull { it.value == effective }?.let { return ModelNames.shortLabel(it.label, providerId) }
        return ModelNames.displayName(effective)
    }

    /** Asks the server unless a fresh list is at hand. */
    suspend fun load(providerId: String) {
        val loaded = _entries.value[providerId]?.loadedAt
        if (loaded != null && clock() - loaded < LIFETIME_MS) return
        if (!inFlight.add(providerId)) return
        val previous = models(providerId)
        try {
            val models = api.providerModels(project, providerId)
            // Only a list with something in it is kept as fresh; an empty one is asked again.
            put(providerId, Entry(models.ifEmpty { previous }, loadedAt = if (models.isEmpty()) null else clock()))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            put(providerId, Entry(previous, failed = previous.isEmpty()))
        } finally {
            inFlight.remove(providerId)
        }
    }

    private fun put(providerId: String, entry: Entry) {
        _entries.value += providerId to entry
    }

    companion object {
        const val LIFETIME_MS = 5 * 60 * 1000L
    }
}
