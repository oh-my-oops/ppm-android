package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatEvent
import com.hienlh.ppm.protocol.get
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The files one turn changed (`aggregate-turn-file-changes.ts`), for the change tray, and every
 * file a session's tools wrote, for the changes bar. Edit's `old_string`/`new_string` are
 * fragments at unknown offsets, so a file's edits are kept apart rather than composed.
 */

enum class FileChangeOp(internal val rank: Int) { CREATE(3), WRITE(2), NOTEBOOK(2), EDIT(1) }

data class FileEditFragment(
    /** "" for Write/NotebookEdit: the prior content is not in the call. */
    val oldStr: String,
    val newStr: String,
    val toolUseId: String?,
    /** Its place among the call's edits: 0 but for MultiEdit. */
    val editIndex: Int,
    /** `<toolUseId>-<editIndex>`, what "Show in chat" finds the call's card by. */
    val editRef: String?,
    val viaSubagent: Boolean,
)

data class TurnFileChange(
    val filePath: String,
    val op: FileChangeOp,
    val editCount: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val edits: List<FileEditFragment>,
    /** Any of its edits came from a sub-agent. */
    val viaSubagent: Boolean,
)

/** Tools whose input changes a file. */
val FILE_MUTATION_TOOL_NAMES = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")

/** A call as the walk reads it, whether a card of the answer or a sub-agent's event on one. */
private class Call(val name: String, val input: JsonElement, val toolUseId: String?, val result: String?, val children: List<ChatEvent>)

private fun ToolBlock.call() = Call(name, input, toolUseId, result?.output, children?.kept?.map { it.event }.orEmpty())

private fun ChatEvent.ToolUse.call() = Call(name, input, toolUseId, result?.output, children.orEmpty())

/** The answer's own calls: an approval request stands for a call but is not one. */
private fun ChatItem.calls(): List<Call> = blocks.mapNotNull { (it as? ChatBlock.Tool)?.tool?.takeIf { t -> t.requestId == null }?.call() }

private class RawChange(val filePath: String, val op: FileChangeOp, val fragments: List<Pair<String, String>>)

/** `str()`: a string field, or "" for anything else. */
private fun str(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** Write's input is the same whether the file existed: only its result says it was created. */
private fun writeOp(output: String?): FileChangeOp =
    if (output != null && Regex("created successfully", RegexOption.IGNORE_CASE).containsMatchIn(output)) FileChangeOp.CREATE else FileChangeOp.WRITE

private fun extractChange(tool: String, input: JsonElement, result: String? = null): RawChange? = when (tool) {
    "Write" -> str(input["file_path"]).ifEmpty { null }?.let { RawChange(it, writeOp(result), listOf("" to str(input["content"]))) }
    "Edit" -> str(input["file_path"]).ifEmpty { null }?.let { RawChange(it, FileChangeOp.EDIT, listOf(str(input["old_string"]) to str(input["new_string"]))) }
    "MultiEdit" -> {
        val path = str(input["file_path"])
        val edits = input["edits"] as? kotlinx.serialization.json.JsonArray
        val fragments = edits?.filterIsInstance<JsonObject>()?.map { str(it["old_string"]) to str(it["new_string"]) }.orEmpty()
        if (path.isEmpty() || edits == null || fragments.isEmpty()) null else RawChange(path, FileChangeOp.EDIT, fragments)
    }
    // The real field is `notebook_path`; `file_path` is taken too, as callers vary.
    "NotebookEdit" -> str(input["notebook_path"]).ifEmpty { str(input["file_path"]) }.ifEmpty { null }
        ?.let { RawChange(it, FileChangeOp.NOTEBOOK, listOf("" to str(input["new_source"]))) }
    else -> null
}

/** A codex patch over several files is one Edit/Write call whose `files` names each of them. */
private fun patchFiles(tool: String, input: JsonElement): List<RawChange>? {
    if (tool != "Edit" && tool != "Write") return null
    val files = input["files"] as? kotlinx.serialization.json.JsonArray ?: return null
    val out = files.filterIsInstance<JsonObject>().mapNotNull { file ->
        val path = str(file["file_path"]).ifEmpty { return@mapNotNull null }
        RawChange(path, if (str(file["op"]) == "add") FileChangeOp.CREATE else FileChangeOp.EDIT, listOf(str(file["old_string"]) to str(file["new_string"])))
    }
    return out.ifEmpty { null }
}

private fun changesOf(call: Call, result: String?): List<RawChange> {
    val single = extractChange(call.name, call.input, result)
    return patchFiles(call.name, call.input) ?: listOfNotNull(single)
}

object TurnFileChanges {
    /** The files [turn] — its run of answers — changed, in the order it first touched them. */
    fun of(turn: List<ChatItem>): List<TurnFileChange> {
        val results = HashMap<String, String>()
        for (item in turn) for (call in item.calls()) collectResults(call, results)
        val files = LinkedHashMap<String, Building>()
        for (item in turn) walk(item.calls(), viaSubagent = false, files, results)
        return files.values.map { it.build() }
    }

    /** Every result by its call, a sub-agent's too; a result copied onto its call counts when none came on its own. */
    private fun collectResults(call: Call, out: MutableMap<String, String>) {
        val id = call.toolUseId
        if (id != null && call.result != null) out.putIfAbsent(id, call.result)
        collectEvents(call.children, out)
    }

    private fun collectEvents(events: List<ChatEvent>, out: MutableMap<String, String>) {
        for (event in events) {
            if (event is ChatEvent.ToolResult) event.toolUseId?.let { out[it] = event.outcome.output }
            if (event is ChatEvent.ToolUse) collectResults(event.call(), out)
        }
    }

    private class Building(val filePath: String, var op: FileChangeOp) {
        var added = 0
        var removed = 0
        var viaSubagent = false
        val edits = mutableListOf<FileEditFragment>()

        fun build() = TurnFileChange(filePath, op, edits.size, added, removed, edits.toList(), viaSubagent)
    }

    private fun walk(calls: List<Call>, viaSubagent: Boolean, files: MutableMap<String, Building>, results: Map<String, String>) {
        for (call in calls) {
            // An agent's own calls are all sub-agent work.
            if (call.children.isNotEmpty()) walk(call.children.filterIsInstance<ChatEvent.ToolUse>().map { it.call() }, true, files, results)
            if (call.name !in FILE_MUTATION_TOOL_NAMES || call.input !is JsonObject) continue
            for (raw in changesOf(call, call.toolUseId?.let(results::get))) add(files, raw, call.toolUseId, viaSubagent)
        }
    }

    private fun add(files: MutableMap<String, Building>, raw: RawChange, toolUseId: String?, viaSubagent: Boolean) {
        val entry = files.getOrPut(raw.filePath) { Building(raw.filePath, raw.op) }
        if (raw.op.rank > entry.op.rank) entry.op = raw.op
        if (viaSubagent) entry.viaSubagent = true
        raw.fragments.forEachIndexed { index, (old, new) ->
            val (added, removed) = JsDiff.countLines(old, new)
            entry.added += added
            entry.removed += removed
            entry.edits += FileEditFragment(old, new, toolUseId, index, toolUseId?.let { "$it-$index" }, viaSubagent)
        }
    }
}

/**
 * Every file a session's tools wrote, and how many of the calls that can change files — those
 * writes and every shell command — have finished (`sessionFileWrites`). The changes bar asks the
 * server again whenever either moves. One per chat: it keeps what each message wrote while that
 * message stays the same object, so only the one streaming is walked again.
 */
class SessionWrites {
    /** What one message's tools wrote (`fileWritesOf`). */
    private class Writes(val paths: List<String>, val writeIds: List<String>, val resultIds: List<String>)

    private val cache = HashMap<String, Pair<ChatItem, Writes>>()

    fun of(items: List<ChatItem>): Pair<List<String>, Int> {
        val paths = LinkedHashSet<String>()
        val writes = LinkedHashSet<String>()
        val results = HashSet<String>()
        if (cache.size > 4 * items.size + 64) cache.keys.retainAll(items.mapTo(HashSet()) { it.id })
        for (item in items) {
            if (item.role != ChatItem.Role.ASSISTANT) continue
            val found = writesOf(item)
            paths += found.paths
            writes += found.writeIds
            results += found.resultIds
        }
        return paths.toList() to writes.count { it in results }
    }

    private fun writesOf(item: ChatItem): Writes {
        cache[item.id]?.let { (seen, writes) -> if (seen === item) return writes }
        val paths = LinkedHashSet<String>()
        val writeIds = mutableListOf<String>()
        val resultIds = mutableListOf<String>()
        fun visit(calls: List<Call>, events: List<ChatEvent>) {
            for (event in events) if (event is ChatEvent.ToolResult) event.toolUseId?.let { resultIds += it }
            for (call in calls) {
                val id = call.toolUseId
                if (id != null && call.result != null) resultIds += id
                visit(call.children.filterIsInstance<ChatEvent.ToolUse>().map { it.call() }, call.children)
                if (call.name in SHELL_TOOLS && id != null) writeIds += id
                if (call.name !in FILE_MUTATION_TOOL_NAMES || call.input !is JsonObject) continue
                val raws = changesOf(call, null)
                raws.forEach { paths += it.filePath }
                if (raws.isNotEmpty() && id != null) writeIds += id
            }
        }
        visit(item.calls(), emptyList())
        return Writes(paths.toList(), writeIds, resultIds).also { cache[item.id] = item to it }
    }
}
