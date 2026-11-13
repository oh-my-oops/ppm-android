package com.hienlh.ppm.state

import com.hienlh.ppm.client.SpecPhaseView
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Spec mode's pure rules on the app side, as the web keeps them beside its views: the workflow a
 * chat starts in (`api-specs.ts`), what a document shows (`spec-pane.tsx`), Chat History's family
 * tree (`session-tree.ts`) and a spec chat's progress in it (`spec-family.ts`).
 */

/** The workflows a chat offers (Kiro's "Start with a workflow"): `chat`, `spec`, `quick` or `bugfix`. */
data class SpecStartChoice(
    val workflow: String = "chat",
    /** `requirements` or `design`: where a Spec starts. */
    val startFrom: String = "requirements",
    /** `standard` or `thorough`. */
    val checks: String = "standard",
) {
    val isSpec: Boolean get() = workflow != "chat"

    /** The tab metadata the web keeps the choice in (`specStart`). */
    fun toJson(): JsonObject = buildJsonObject {
        put("workflow", workflow)
        put("startFrom", startFrom)
        put("checks", checks)
    }

    /** `kind` and `workflow` of the spec this choice creates (`specStartBody`), or null for a plain chat. */
    fun specKind(): Pair<String, String>? {
        if (!isSpec) return null
        val kind = if (workflow == "spec") "feature" else workflow
        val flow = if (kind == "feature" && startFrom == "design") "design-first" else "requirements-first"
        return kind to flow
    }

    companion object {
        val WORKFLOWS = listOf("chat", "spec", "quick", "bugfix")

        /** A stored choice read back as `specStartFromMetadata` reads it, with its defaults for anything else. */
        fun from(json: JsonElement?): SpecStartChoice {
            val workflow = json["workflow"].string
            return SpecStartChoice(
                workflow = if (workflow == "spec" || workflow == "quick" || workflow == "bugfix") workflow else "chat",
                startFrom = if (json["startFrom"].string == "design") "design" else "requirements",
                checks = if (json["checks"].string == "thorough") "thorough" else "standard",
            )
        }

        /** The spec's title from the chat's first line, cut at 80 characters with an ellipsis. */
        fun titleFrom(text: String): String {
            val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (first.length > 80) first.take(77).trimEnd() + "…" else first
        }
    }
}

/** The names a workflow is offered under, and what it does (`WORKFLOWS` in spec-workflow.tsx). */
object SpecWorkflowCopy {
    fun label(workflow: String): String = when (workflow) {
        "spec" -> "Spec"
        "quick" -> "Quick Spec"
        "bugfix" -> "Bug Fix"
        else -> "Chat"
    }

    fun note(workflow: String): String = when (workflow) {
        "spec" -> "Requirements, design, tasks — you approve each step."
        "quick" -> "All three documents in one go, no stops."
        "bugfix" -> "What happens, what should, what must not change."
        else -> "Talk it through. No documents."
    }

    /** How a spec's kind is named in its header. */
    fun kind(kind: String): String = when (kind) {
        "bugfix" -> "Bug fix"
        "quick" -> "Quick spec"
        else -> "Feature"
    }

    /** The Specs list's stage names (`STAGE`). */
    fun stage(current: String): String = when (current) {
        "requirements" -> "Requirements"
        "design" -> "Design"
        "tasks", "execution" -> "Tasks"
        else -> current
    }

    /** A document's state under it (the pane's footer line, `specDocumentStatus`), and what an approval waits for. */
    fun phaseState(phase: SpecPhaseView): String {
        when (phase.status) {
            "approved" -> return "Approved"
            "missing" -> return "Not written yet"
            "invalid" -> return "Has problems the AI must fix"
        }
        val base = when {
            phase.status == "stale" -> "Changed after you approved it"
            phase.submitted -> "Submitted by the AI · waiting for you"
            else -> "Draft · the AI has not submitted it yet"
        }
        return phase.approveBlocked?.let { "$base · $it" } ?: base
    }
}

/** What a spec document shows (`withoutHtmlComments` and `emphasizeEars` in spec-pane.tsx). */
object SpecDocument {
    private val FENCE = Regex("(?m)(^```[\\s\\S]*?^```)")
    private val COMMENT = Regex("<!--[\\s\\S]*?-->\\n?")
    private val EARS = Regex("(?<![\\w*])(THE SYSTEM SHALL|WHEN|WHILE|WHERE|IF|THEN|SHALL)(?![\\w*])")
    private val LIST_ITEM = Regex("^\\s*(\\d+[.)]|[-*+])\\s")
    private val FENCE_LINE = Regex("^\\s*(```|~~~)")
    private val INLINE_CODE = Regex("(`[^`]*`)")

    /** The templates' `<!-- … -->` notes are for the AI; code blocks are left as written. */
    fun withoutHtmlComments(markdown: String): String {
        val out = StringBuilder()
        var at = 0
        for (fence in FENCE.findAll(markdown)) {
            out.append(markdown.substring(at, fence.range.first).replace(COMMENT, ""))
            out.append(fence.value)
            at = fence.range.last + 1
        }
        out.append(markdown.substring(at).replace(COMMENT, ""))
        return out.toString()
    }

    /** Bolds the EARS keywords of acceptance criteria: list items only, never in code. */
    fun emphasizeEars(markdown: String): String {
        var fenced = false
        return markdown.split("\n").joinToString("\n") { line ->
            if (FENCE_LINE.containsMatchIn(line)) {
                fenced = !fenced
                return@joinToString line
            }
            if (fenced || !LIST_ITEM.containsMatchIn(line)) return@joinToString line
            splitKeeping(line, INLINE_CODE).mapIndexed { i, part -> if (i % 2 == 1) part else part.replace(EARS, "**$1**") }.joinToString("")
        }
    }

    /** What the screen renders for [phase]'s [text]. */
    fun shown(phase: String, text: String): String =
        if (phase == "requirements") emphasizeEars(withoutHtmlComments(text)) else withoutHtmlComments(text)

    /** `String.split` with a capturing group, as JavaScript's: the matches are kept at odd indexes. */
    private fun splitKeeping(text: String, regex: Regex): List<String> {
        val parts = mutableListOf<String>()
        var at = 0
        for (m in regex.findAll(text)) {
            parts += text.substring(at, m.range.first)
            parts += m.value
            at = m.range.last + 1
        }
        parts += text.substring(at)
        return parts
    }
}

/** A task's state as Chat History draws it (`SpecBarCell`). */
enum class SpecBarCell { DONE, RUNNING, FAILED, TODO }

/** A spec chat's progress in Chat History, and the order its task chats are listed in (`spec-family.ts`). */
object SpecFamily {
    fun cellOf(status: String): SpecBarCell = when (status) {
        "done" -> SpecBarCell.DONE
        "running", "red" -> SpecBarCell.RUNNING
        "failed" -> SpecBarCell.FAILED
        else -> SpecBarCell.TODO
    }

    /** One cell per required task, in tasks.md order. */
    fun bar(spec: SpecSummary): List<SpecBarCell> = spec.tasks.filter { !it.optional }.map { cellOf(it.status) }

    data class Progress(val done: Int, val total: Int, val running: Int, val failed: Int)

    fun progress(spec: SpecSummary): Progress {
        val cells = spec.tasks.map { cellOf(it.status) }
        return Progress(spec.tasksDone, spec.tasksTotal, cells.count { it == SpecBarCell.RUNNING }, cells.count { it == SpecBarCell.FAILED })
    }

    /** The task state of each chat that ran a task of this spec. */
    fun cellBySession(spec: SpecSummary): Map<String, SpecBarCell> =
        spec.tasks.mapNotNull { t -> t.sessionId?.let { it to cellOf(t.status) } }.toMap()

    /** The specs History shows a progress bar for: those with a chat and tasks, by that chat. */
    fun byChat(specs: List<SpecSummary>): Map<String, SpecSummary> =
        specs.filter { it.sessionId != null && it.tasks.isNotEmpty() }.associateBy { it.sessionId!! }

    private fun rank(cell: SpecBarCell?): Int = when (cell) {
        SpecBarCell.RUNNING -> 0
        SpecBarCell.FAILED -> 1
        else -> 2
    }

    /** Running first, then failed, then the rest; otherwise the list's own order (newest first). */
    fun orderTaskChats(nodes: List<SessionTreeNode>, cells: Map<String, SpecBarCell>): List<SessionTreeNode> =
        nodes.withIndex().sortedWith(compareBy({ rank(cells[it.value.session.id]) }, { it.index })).map { it.value }
}

/** A session and the sessions its AI created, at any depth. */
data class SessionTreeNode(val session: SessionInfo, val children: List<SessionTreeNode>)

/**
 * One top-level entry of Chat History's tree. [session] is null when the creator is not in the list
 * (on a later page, or filtered out by a tag): the entry then stands for it, named by its children's
 * [parentTitle], so they still sit together.
 */
data class SessionTreeRoot(
    val id: String,
    val session: SessionInfo?,
    val parentTitle: String?,
    val children: List<SessionTreeNode>,
) {
    /** Every session under this entry, at any depth. */
    val descendants: List<SessionInfo> get() = SessionTree.descendantsOf(children)

    /** The entry and everything under it. */
    val members: List<SessionInfo> get() = listOfNotNull(session) + descendants
}

/**
 * Folds a session list into a family tree (`buildSessionTree`): a session another session's AI
 * created (a Spec task chat, a chat opened through `create_session`) sits under its creator, so a
 * Run all that opens eighteen task chats adds one entry, not nineteen. Order-preserving: every
 * family takes the place of its first member in [sessions]. A pinned session stays on its own,
 * and a chain that loops back is cut where it would close.
 */
object SessionTree {
    fun build(sessions: List<SessionInfo>): List<SessionTreeRoot> {
        val byId = sessions.associateBy { it.id }
        val kids = sessions.associate { it.id to mutableListOf<String>() }
        val missingKids = LinkedHashMap<String, MutableList<String>>()
        val missingTitle = HashMap<String, String?>()
        val rootOf = HashMap<String, String>()
        val rootOrder = LinkedHashMap<String, Int>()

        fun parentIn(s: SessionInfo): String? = if (s.pinned) null else s.spawnedBy?.takeIf { it.isNotEmpty() }
        fun loopsBack(s: SessionInfo): Boolean {
            val seen = mutableSetOf(s.id)
            var p = parentIn(s)
            while (p != null && byId.containsKey(p)) {
                if (!seen.add(p)) return true
                p = parentIn(byId.getValue(p))
            }
            return false
        }
        fun parentOf(s: SessionInfo): String? = if (loopsBack(s)) null else parentIn(s)
        fun topOf(s: SessionInfo): String = rootOf.getOrPut(s.id) {
            val parent = parentOf(s)
            when {
                parent != null && byId.containsKey(parent) -> topOf(byId.getValue(parent))
                parent != null -> {
                    if (!missingTitle.containsKey(parent)) missingTitle[parent] = s.parentTitle
                    parent
                }
                else -> s.id
            }
        }

        sessions.forEachIndexed { i, s ->
            val root = topOf(s)
            rootOrder.putIfAbsent(root, i)
            val parent = parentOf(s) ?: return@forEachIndexed
            if (byId.containsKey(parent)) kids.getValue(parent) += s.id else missingKids.getOrPut(parent) { mutableListOf() } += s.id
        }

        fun node(id: String): SessionTreeNode = SessionTreeNode(byId.getValue(id), kids.getValue(id).map(::node))
        return rootOrder.entries.sortedBy { it.value }.map { (id, _) ->
            val session = byId[id]
            if (session != null) {
                SessionTreeRoot(id, session, null, kids.getValue(id).map(::node))
            } else {
                SessionTreeRoot(id, null, missingTitle[id], missingKids[id].orEmpty().map(::node))
            }
        }
    }

    fun descendantsOf(children: List<SessionTreeNode>): List<SessionInfo> {
        val out = mutableListOf<SessionInfo>()
        fun walk(list: List<SessionTreeNode>) {
            for (n in list) {
                out += n.session
                walk(n.children)
            }
        }
        walk(children)
        return out
    }
}
