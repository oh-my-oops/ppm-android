package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.GitRef

sealed interface PickerRow {
    val isSelectable: Boolean

    data class Action(val action: String, val label: String) : PickerRow {
        override val isSelectable: Boolean get() = true
    }

    data class Group(val label: String) : PickerRow {
        override val isSelectable: Boolean get() = false
    }

    data class Ref(val ref: GitRef) : PickerRow {
        override val isSelectable: Boolean get() = true
    }
}

data class CheckoutTarget(
    val ref: String,
    /** `"checkout"` | `"track"` */
    val mode: String,
)

/**
 * Pure filtering, row construction, keyboard selection, and checkout target logic for the
 * branch picker (`src/web/lib/git-ref-picker.ts`).
 */
object GitRefPicker {
    private val ACTIONS = listOf(
        PickerRow.Action("create", "Create new branch..."),
        PickerRow.Action("create-from", "Create new branch from..."),
        PickerRow.Action("detach", "Checkout detached..."),
    )

    fun buildRows(refs: List<GitRef>, query: String, includeActions: Boolean = true): List<PickerRow> {
        val q = query.trim().lowercase()
        val match = { r: GitRef ->
            if (q.isEmpty()) true
            else r.name.lowercase().contains(q) || r.subject.lowercase().contains(q)
        }

        val rows = mutableListOf<PickerRow>()
        if (includeActions) {
            for (action in ACTIONS) {
                if (q.isEmpty() || action.label.lowercase().contains(q)) {
                    rows += action
                }
            }
        }

        val branches = refs.filter { it.kind == "branch" && match(it) }
        val remotes = refs.filter { it.kind == "remote" && match(it) }
        val tags = refs.filter { it.kind == "tag" && match(it) }

        if (branches.isNotEmpty()) {
            rows += PickerRow.Group("branches")
            for (r in branches) rows += PickerRow.Ref(r)
        }
        if (remotes.isNotEmpty()) {
            rows += PickerRow.Group("remote branches")
            for (r in remotes) rows += PickerRow.Ref(r)
        }
        if (tags.isNotEmpty()) {
            rows += PickerRow.Group("tags")
            for (r in tags) rows += PickerRow.Ref(r)
        }
        return rows
    }

    fun firstSelectable(rows: List<PickerRow>): Int =
        rows.indexOfFirst { it.isSelectable }

    fun moveSelection(rows: List<PickerRow>, current: Int, delta: Int): Int {
        val selectable = rows.indices.filter { rows[it].isSelectable }
        if (selectable.isEmpty()) return -1
        val pos = selectable.indexOf(current)
        if (pos == -1) return selectable[0]
        val next = (pos + delta + selectable.size) % selectable.size
        return selectable[next]
    }

    /** Strip the remote prefix (`origin/feat/x` -> `feat/x`) for checking if a local branch exists. */
    fun localNameFor(remoteRef: String): String {
        val slash = remoteRef.indexOf('/')
        return if (slash == -1) remoteRef else remoteRef.substring(slash + 1)
    }

    /**
     * Decide how a picked ref should be checked out:
     * - Remote branch whose local counterpart already exists -> switch to that local branch
     *   (`git checkout -t` would fail with `branch '...' already exists`).
     * - Remote branch with no local branch -> `checkout -t <remote>/<name>`.
     * - Local branch or tag -> plain `git checkout <name>`.
     */
    fun checkoutTarget(ref: GitRef, allRefs: List<GitRef>): CheckoutTarget {
        if (ref.kind == "remote") {
            val local = localNameFor(ref.name)
            val hasLocal = allRefs.any { it.kind == "branch" && it.name == local }
            return if (hasLocal) {
                CheckoutTarget(ref = local, mode = "checkout")
            } else {
                CheckoutTarget(ref = ref.name, mode = "track")
            }
        }
        return CheckoutTarget(ref = ref.name, mode = "checkout")
    }

    /** Secondary line under a ref name (`author · shortHash · subject`). */
    fun refDetail(ref: GitRef): String =
        listOf(ref.author, ref.shortHash, ref.subject)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
}
