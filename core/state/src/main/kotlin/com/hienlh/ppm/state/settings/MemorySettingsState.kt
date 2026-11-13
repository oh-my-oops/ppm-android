package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.MemoryEmbeddingTest
import com.hienlh.ppm.protocol.MemoryInstallStatus
import com.hienlh.ppm.protocol.MemoryProviderStatus
import com.hienlh.ppm.protocol.MemoryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Settings → Memory (`memory-settings-section.tsx` + `memory-embedding-source.tsx`): PPM's shared
 * memory store, its on/off switch, what each provider's memory came to, and where memory vectors
 * come from. The words are the web's; the state below is the two components' state, so the pane
 * behaves the same on the tablet and the phone.
 */

/** Each provider's name, where PPM reads its memories, and what an empty store means (`PROVIDERS`). */
data class MemoryProviderDef(val id: String, val label: String, val source: String, val empty: String)

val MEMORY_PROVIDERS: List<MemoryProviderDef> = listOf(
    MemoryProviderDef(
        "claude",
        "Claude",
        "~/.claude/projects/<this project>/memory",
        "No memories yet. Claude Code writes them during a chat, if auto memory is on.",
    ),
    MemoryProviderDef(
        "codex",
        "Codex",
        "~/.codex/memories",
        "No memories yet. Codex writes them after a chat ends, if memories are on in Codex.",
    ),
    MemoryProviderDef(
        "antigravity",
        "Antigravity",
        "~/.gemini/antigravity/knowledge",
        "No memories yet. Antigravity saves knowledge items as it works.",
    ),
)

/** The line under a provider's name: its error, what it skipped, or where it reads from (`providerNote`). */
data class MemoryProviderNote(val text: String, val error: Boolean)

fun memoryProviderNote(status: MemoryProviderStatus?, def: MemoryProviderDef): MemoryProviderNote {
    status?.error?.let { return MemoryProviderNote(it, true) }
    val skipped = status?.skipped.orEmpty()
    if (skipped.isNotEmpty()) {
        val items = if (skipped.size == 1) "1 item" else "${skipped.size} items"
        return MemoryProviderNote("Skipped $items: ${skipped[0]}", true)
    }
    if (status == null || status.count == 0) return MemoryProviderNote(def.empty, false)
    return MemoryProviderNote(def.source, false)
}

/** `providerSummary`: "Not synced yet", or "3 memories · 5m ago" with [relative] writing the time. */
fun memoryProviderSummary(status: MemoryProviderStatus?, relative: (Instant) -> String): String {
    val syncedAt = status?.syncedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Not synced yet"
    val count = when (status.count) {
        0 -> "0"
        1 -> "1 memory"
        else -> "${status.count} memories"
    }
    return "$count · ${relative(syncedAt)}"
}

/** `vectorCount`: "4 of 6 memories have meanings; 2 are being computed." */
fun memoryVectorCount(vectors: Int, total: Int, running: Boolean): String {
    val computing = if (running && vectors < total) "; ${total - vectors} are being computed." else "."
    return "$vectors of $total memories have meanings$computing"
}

/** The install bar's figures: MB done and in all, and the percent drawn. */
data class MemoryInstallProgress(val doneMb: Long, val totalMb: Long, val percent: Int)

fun memoryInstallProgress(install: MemoryInstallStatus): MemoryInstallProgress {
    val mb = 1024.0 * 1024.0
    val totalMb = (install.totalBytes / mb).roundToLong()
    val doneMb = min((install.bytes / mb).roundToLong(), if (totalMb > 0) totalMb else Long.MAX_VALUE)
    val percent = if (install.totalBytes > 0) min(100L, (install.bytes * 100.0 / install.totalBytes).roundToLong()).toInt() else 0
    return MemoryInstallProgress(doneMb, totalMb, percent)
}

const val MEMORY_LOCAL_MODEL_NAME = "multilingual-e5-small"

/** Where memory vectors come from (`SOURCES`). */
val MEMORY_EMBEDDING_SOURCES: List<Pair<String, String>> = listOf("off" to "Off", "local" to "Local model", "api" to "API")

/**
 * The pane's state (`MemorySettingsSection` + `MemoryEmbeddingSource`). Errors the web toasts go
 * to [onError]; everything else is in [state]. Main thread only.
 */
class MemorySettingsData(private val source: Source, private val onError: (String) -> Unit = {}) {
    interface Source {
        suspend fun status(): MemoryStatus
        suspend fun setEnabled(enabled: Boolean): Boolean
        suspend fun sync()
        suspend fun setEmbedding(fields: Map<String, String>)
        suspend fun install(): MemoryInstallStatus
        suspend fun remove()
        suspend fun test(fields: Map<String, String>): MemoryEmbeddingTest
    }

    enum class Busy { Source, Install, Remove, Save, Test }

    data class State(
        val status: MemoryStatus? = null,
        val loadError: String? = null,
        val saving: Boolean = false,
        val syncing: Boolean = false,
        /** The source picked, which can run ahead of the saved one: API opens its form before Save. */
        val chosenSource: String = "off",
        val install: MemoryInstallStatus = MemoryInstallStatus(),
        val url: String = "",
        val model: String = "",
        val apiKey: String = "",
        val busy: Busy? = null,
        val testResult: MemoryEmbeddingTest? = null,
    ) {
        /** Whenever vectors cannot be used, the block says search runs on words only (`wordsOnly`). */
        val wordsOnly: Boolean
            get() = status?.embedding?.let { !it.ready || it.reason.isNotEmpty() } ?: true

        /** Save and Test need an endpoint and a model. */
        val apiFormComplete: Boolean
            get() = url.isNotBlank() && model.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val errorText = { e: Exception -> e.message ?: e.toString() }

    /** `load`: the status; the embedding block follows each saved value only when it changes. */
    suspend fun load() {
        try {
            val next = source.status()
            _state.update { s ->
                val prev = s.status?.embedding
                val emb = next.embedding
                s.copy(
                    status = next,
                    loadError = null,
                    chosenSource = if (prev == null || prev.source != emb.source) emb.source else s.chosenSource,
                    install = if (prev == null || prev.install != emb.install) emb.install else s.install,
                    url = if (prev == null || prev.apiUrl != emb.apiUrl) emb.apiUrl else s.url,
                    model = if (prev == null || prev.apiModel != emb.apiModel) emb.apiModel else s.model,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadError = errorText(e)) }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(saving = true) }
        try {
            val saved = source.setEnabled(enabled)
            _state.update { s -> s.copy(status = s.status?.copy(enabled = saved)) }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Could not save: ${errorText(e)}")
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    suspend fun syncNow() {
        _state.update { it.copy(syncing = true) }
        try {
            source.sync()
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Sync failed: ${errorText(e)}")
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value) }
    fun setModel(value: String) = _state.update { it.copy(model = value) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value) }

    /** `choose`: an API source with no saved endpoint only opens the form; Save sends it. */
    suspend fun choose(next: String) {
        val s = _state.value
        if (next == s.chosenSource || s.busy != null) return
        val emb = s.status?.embedding
        if (next == "api" && !(emb != null && emb.apiUrl.isNotEmpty() && emb.apiModel.isNotEmpty())) {
            _state.update { it.copy(chosenSource = "api") }
            return
        }
        val previous = s.chosenSource
        _state.update { it.copy(chosenSource = next, busy = Busy.Source) }
        try {
            source.setEmbedding(mapOf("source" to next))
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(chosenSource = previous) }
            onError("Could not save: ${errorText(e)}")
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    suspend fun startInstall() {
        _state.update { it.copy(busy = Busy.Install) }
        try {
            val install = source.install()
            _state.update { it.copy(install = install) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Could not start the install: ${errorText(e)}")
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Cancels a running install, or removes the installed model. */
    suspend fun removeInstall() {
        _state.update { it.copy(busy = Busy.Remove) }
        try {
            source.remove()
            _state.update { it.copy(install = MemoryInstallStatus()) }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Could not remove the model: ${errorText(e)}")
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** `apiFields`: the API fields as the server takes them; an empty key is left out so the stored one stays. */
    fun apiFields(): Map<String, String> {
        val s = _state.value
        val fields = linkedMapOf("source" to "api", "api_url" to s.url.trim(), "api_model" to s.model.trim())
        if (s.apiKey.isNotBlank()) fields["api_key"] = s.apiKey.trim()
        return fields
    }

    suspend fun save() {
        _state.update { it.copy(busy = Busy.Save) }
        try {
            source.setEmbedding(apiFields())
            _state.update { it.copy(apiKey = "") }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Could not save: ${errorText(e)}")
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    suspend fun test() {
        _state.update { it.copy(busy = Busy.Test, testResult = null) }
        try {
            val result = source.test(apiFields())
            _state.update { it.copy(testResult = result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(testResult = MemoryEmbeddingTest(false, error = errorText(e))) }
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /**
     * `memory:embed-install` arrived. Readiness and the reason come from the status route, so it
     * reloads once the install settles. Returns whether a reload is due.
     */
    fun onInstallEvent(status: MemoryInstallStatus): Boolean {
        _state.update { it.copy(install = status) }
        return status.state != MemoryInstallStatus.INSTALLING
    }
}
