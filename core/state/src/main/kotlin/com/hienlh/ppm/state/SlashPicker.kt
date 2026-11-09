package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.clearSlashCache
import com.hienlh.ppm.client.recordSlashUse
import com.hienlh.ppm.client.slashItems
import com.hienlh.ppm.protocol.FuzzySearch
import com.hienlh.ppm.protocol.SlashCommandLine
import com.hienlh.ppm.protocol.SlashItem
import com.hienlh.ppm.protocol.SlashItemsPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Filter tabs in `slash-command-picker.tsx`: `Commands` covers both project commands and built-ins.
 */
enum class SlashKindFilter(val id: String, val label: String) {
    ALL("all", "All"),
    SKILL("skill", "Skills"),
    COMMAND("command", "Commands"),
    AGENT("agent", "Agents"),
    ;

    fun matches(item: SlashItem): Boolean = when (this) {
        ALL -> true
        COMMAND -> item.type == "command" || item.type == "builtin"
        else -> item.type == id
    }
}

/**
 * Scope pill text in `slash-command-picker-row.tsx` (`slashScopeLabel`):
 * `Codex` for `$` skills, `PPM` for `bundled`, `Global` for `user`, `Project` for `project`,
 * `Built-in` for `builtin`, else capitalized `type`.
 */
fun slashScopeLabel(item: SlashItem): String = when {
    item.invokeSigil == "$" -> "Codex"
    item.scope == "bundled" -> "PPM"
    item.scope == "user" -> "Global"
    item.scope == "project" -> "Project"
    item.type == "builtin" -> "Built-in"
    item.type.isEmpty() -> ""
    else -> item.type.replaceFirstChar { it.uppercase() }
}

/**
 * What the slash picker lists (`displayItems` and `anyMatch` in `slash-command-picker.tsx`):
 * scoped by [kind], fuzzy-searched up to 20 when [query] is non-empty, or recents-first when idle.
 * An empty non-`ALL` tab stays open (`anyMatch == true`) when another tab has matches so the user
 * can switch tabs, while a query matching nothing anywhere closes the picker (`anyMatch == false`).
 */
class SlashPickerList(
    allItems: List<SlashItem>,
    val query: String,
    recentNames: List<String>,
    val kind: SlashKindFilter = SlashKindFilter.ALL,
) {
    val items: List<SlashItem>

    /** How many lead under "Recent"; none while searching. */
    val recentCount: Int

    /** Whether anything matches across any tab (`anyMatch` in `slash-command-picker.tsx`). */
    val anyMatch: Boolean

    init {
        val scoped = if (kind == SlashKindFilter.ALL) allItems else allItems.filter(kind::matches)
        if (query.isNotEmpty()) {
            this.items = FuzzySearch.search(scoped, query, 20, recentNames)
            recentCount = 0
        } else {
            val place = HashMap<String, Int>()
            recentNames.forEachIndexed { index, name -> place.putIfAbsent(name, index) }
            val recents = scoped.filter { it.name in place }.sortedBy { place.getValue(it.name) }
            this.items = recents + scoped.filter { it.name !in place }
            recentCount = recents.size
        }
        anyMatch = when {
            this.items.isNotEmpty() -> true
            kind == SlashKindFilter.ALL -> false
            query.isNotEmpty() -> FuzzySearch.search(allItems, query, 1).isNotEmpty()
            else -> allItems.isNotEmpty()
        }
    }

    /** ArrowUp (-1) or ArrowDown (+1) from [index], round from either end. */
    fun step(index: Int, delta: Int): Int = stepRound(index, delta, items.size)
}

/**
 * A message the app acts on itself rather than sending (`handleInputSend`). Only `/clear` is one,
 * and it works before the list has loaded; while a reply is being written it is refused, as is
 * any command the list says the app or PPM runs, since neither could carry the reply.
 */
sealed interface ClientCommand {
    /** "Cancel reply before running this command": the message stays in the box. */
    data object Refused : ClientCommand

    /** `/clear [title]`: a new chat tab, with an empty context. */
    data class Clear(val title: String?) : ClientCommand

    companion object {
        fun of(text: String, items: List<SlashItem>, replying: Boolean): ClientCommand? {
            val line = SlashCommandLine.parse(text) ?: return null
            val item = items.firstOrNull { it.handler == "client" && (it.name == line.name || line.name in it.aliases) }
            if (replying && (item != null || line.name == "clear" || items.any { it.handler == "ppm" && it.name == line.name })) return Refused
            if (line.name != "clear" && item?.name != "clear") return null
            return Clear(line.argument?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/** ArrowUp (-1) or ArrowDown (+1) from [index] in a list of [count], round from either end. */
internal fun stepRound(index: Int, delta: Int, count: Int): Int {
    if (count == 0) return 0
    if (delta < 0) return if (index > 0) index - 1 else count - 1
    return if (index < count - 1) index + 1 else 0
}

/**
 * Each project's slash list per provider (`slash-items-cache.ts`): read once and kept half an
 * hour, then read again the next time it is asked for, the old one shown meanwhile and kept when
 * the read fails. A provider's skills that are not a project's own serve its other projects at
 * once. Driven from one thread, as every controller here is.
 */
class SlashItemsStore(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val project: String, val provider: String)

    private class Entry(val payload: SlashItemsPayload, val expiresAt: Long)

    private val entries = HashMap<Key, Entry>()
    private val shared = HashMap<String, SlashItemsPayload>()
    private val loading = HashMap<Key, Job>()
    private val _revision = MutableStateFlow(0)

    /** Moves whenever a list changes, for a view to read [payload] again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** The project's list, else what the provider offers everywhere; null before any read. */
    fun payload(project: String, provider: String): SlashItemsPayload? = entries[Key(project, provider)]?.payload ?: shared[provider]

    /** Reads the list, unless one read within the half hour is held; a read already on its way is waited for rather than asked twice. */
    suspend fun load(api: ApiClient, project: String, provider: String, sessionId: String?) {
        val key = Key(project, provider)
        loading[key]?.takeIf { it.isActive }?.let {
            it.join()
            return
        }
        entries[key]?.let { if (clock() < it.expiresAt) return }
        val job = scope.launch {
            val payload = try {
                api.slashItems(project, provider, sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            store(payload, key)
        }
        loading[key] = job
        job.join()
        if (loading[key] === job) loading.remove(key)
    }

    /** From `/chat/prepare`, which reads the list beside the rest of what a new chat needs. */
    fun seed(payload: SlashItemsPayload, project: String, provider: String) {
        store(payload, Key(project, provider))
    }

    /** A pick leads the recent ones at once, five at most, and is told to the server. */
    fun recordUse(item: SlashItem, api: ApiClient, project: String, provider: String) {
        val key = Key(project, provider)
        entries[key]?.let { entry ->
            val recent = (listOf(item.name) + entry.payload.recentNames.filter { it != item.name }).take(5)
            entries[key] = Entry(entry.payload.copy(recentNames = recent), entry.expiresAt)
            _revision.value++
        }
        scope.launch {
            try {
                api.recordSlashUse(project, item.name, item.type)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The picker's Refresh: the server reads skills from disk again, every list held here is read
     * again when next asked for, and this one is read once the server has answered — which is when
     * this returns, as the web's spinner stops there and not when the list comes.
     */
    suspend fun refresh(api: ApiClient, project: String, provider: String, sessionId: String?) {
        try {
            api.clearSlashCache(project)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        for (key in entries.keys.toList()) entries[key] = Entry(entries.getValue(key).payload, 0)
        scope.launch { load(api, project, provider, sessionId) }
    }

    private fun store(payload: SlashItemsPayload, key: Key) {
        entries[key] = Entry(payload, clock() + LIFETIME_MS)
        shared[key.provider] = SlashItemsPayload(payload.items.filter { it.scope != "project" })
        _revision.value++
    }

    companion object {
        /** `SLASH_ITEMS_TTL_MS`. */
        const val LIFETIME_MS = 30 * 60_000L
    }
}
