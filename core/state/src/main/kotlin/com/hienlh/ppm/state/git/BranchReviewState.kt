package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.BranchDiffFile
import com.hienlh.ppm.client.GitBranch
import com.hienlh.ppm.client.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** One item in the searchable branch picker (`branchItems` in `branch-select-items.ts`). */
data class BranchSelectItem(
    val value: String,
    val label: String,
    /** `"Branches"` | `"Remote branches"`. */
    val group: String,
    val remote: Boolean,
    val hint: String? = null,
)

/**
 * Pure functions and persistence for the Branch Review tab (`branch-review-state.ts`,
 * `branch-select-items.ts`, `branch-review-tab.tsx`).
 */
object BranchReviewState {
    const val PREFIX = "ppm:branch-review"
    const val RECENT_KEY = "$PREFIX:recent"
    const val MAX_REMEMBERED = 20

    /** Keyed by ref names so progress survives new commits; per-file validity is decided by blob id. */
    fun reviewKey(projectName: String, base: String, head: String): String =
        "$PREFIX:$projectName:$base:$head"

    fun isReviewed(state: Map<String, String>, file: BranchDiffFile): Boolean =
        state[file.path] == file.blob

    fun toggleReviewed(state: Map<String, String>, file: BranchDiffFile): Map<String, String> {
        val next = LinkedHashMap(state)
        if (isReviewed(state, file)) next.remove(file.path)
        else next[file.path] = file.blob
        return next
    }

    fun setAllReviewed(
        state: Map<String, String>,
        files: List<BranchDiffFile>,
        reviewed: Boolean,
    ): Map<String, String> {
        if (!reviewed) return emptyMap()
        val next = LinkedHashMap(state)
        for (file in files) next[file.path] = file.blob
        return next
    }

    fun reviewedCount(state: Map<String, String>, files: List<BranchDiffFile>): Int =
        files.count { isReviewed(state, it) }

    /** Drop entries for files the diff no longer contains. */
    fun pruneReviewed(state: Map<String, String>, files: List<BranchDiffFile>): Map<String, String> {
        val live = files.mapTo(HashSet()) { it.path }
        val next = LinkedHashMap<String, String>()
        for ((path, blob) in state) {
            if (path in live) next[path] = blob
        }
        return next
    }

    /** First non-binary file, falling back to the first entry when all changed files are binary. */
    fun firstReviewable(files: List<BranchDiffFile>): BranchDiffFile? =
        files.firstOrNull { !it.binary } ?: files.firstOrNull()

    /** The next file still needing review, wrapping around from [fromPath]. */
    fun nextUnreviewed(
        state: Map<String, String>,
        files: List<BranchDiffFile>,
        fromPath: String?,
    ): BranchDiffFile? {
        if (files.isEmpty()) return null
        val start = if (fromPath != null) files.indexOfFirst { it.path == fromPath } + 1 else 0
        for (i in files.indices) {
            val file = files[(start + i) % files.size]
            if (!isReviewed(state, file)) return file
        }
        return null
    }

    data class RecentUpdate(
        val recent: List<String>,
        val evicted: List<String>,
    )

    /** [recent] with [key] moved to the front, capped at [max]. */
    fun nextRecent(
        recent: List<String>,
        key: String,
        max: Int = MAX_REMEMBERED,
    ): RecentUpdate {
        val moved = listOf(key) + recent.filter { it != key }
        return RecentUpdate(
            recent = moved.take(max),
            evicted = moved.drop(max),
        )
    }

    /** Default base branch: `main`, else `master`, else first local branch other than [current]. */
    fun defaultBase(branches: List<GitBranch>, current: String?): String {
        val local = branches.filter { !it.remote && it.name != current }
        return local.firstOrNull { it.name == "main" }?.name
            ?: local.firstOrNull { it.name == "master" }?.name
            ?: local.firstOrNull()?.name
            ?: ""
    }

    /** Local branches first under `"Branches"`, then remote-tracking branches under `"Remote branches"`. */
    fun branchItems(branches: List<GitBranch>): List<BranchSelectItem> =
        (branches.filter { !it.remote } + branches.filter { it.remote }).map { branch ->
            BranchSelectItem(
                value = branch.name,
                label = branch.name,
                group = if (branch.remote) "Remote branches" else "Branches",
                remote = branch.remote,
                hint = if (branch.current) "current" else null,
            )
        }

    fun decodeReviewed(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((path, value) in parsed) {
                val prim = value as? JsonPrimitive ?: continue
                if (!prim.isString) continue
                val str = prim.contentOrNull ?: continue
                out[path] = str
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun encodeReviewed(state: Map<String, String>): String =
        buildJsonObject {
            for ((path, blob) in state) put(path, JsonPrimitive(blob))
        }.toString()

    fun decodeRecent(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val prim = el as? JsonPrimitive ?: return@mapNotNull null
                if (prim.isString) prim.contentOrNull else null
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeRecent(recent: List<String>): String =
        JsonArray(recent.map(::JsonPrimitive)).toString()
}

/** Device-local store for Branch Review progress (`ppm:branch-review:*`). */
class BranchReviewStore(private val store: KeyValueStore) {
    fun loadReviewed(key: String): Map<String, String> =
        BranchReviewState.decodeReviewed(store.get(key))

    fun saveReviewed(key: String, state: Map<String, String>) {
        try {
            val currentRecent = BranchReviewState.decodeRecent(store.get(BranchReviewState.RECENT_KEY))
            if (state.isEmpty()) {
                store.put(key, null)
                val kept = currentRecent.filter { it != key }
                store.put(BranchReviewState.RECENT_KEY, BranchReviewState.encodeRecent(kept))
                return
            }
            store.put(key, BranchReviewState.encodeReviewed(state))
            val update = BranchReviewState.nextRecent(currentRecent, key)
            for (stale in update.evicted) store.put(stale, null)
            store.put(BranchReviewState.RECENT_KEY, BranchReviewState.encodeRecent(update.recent))
        } catch (_: Throwable) {
            // Quota or storage failure: losing review progress must never fail the UI.
        }
    }
}
