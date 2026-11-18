package com.hienlh.ppm.state.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The device half of the Assistant's command tools (`assistant-commands.ts`): listing this
 * device's command registry and checking that one may run. Only ids in the registry run, and one
 * that changes data runs only with the approval the server attached, still for the same label and
 * the project this device shows now.
 */

const val MAX_ASSISTANT_COMMANDS_LISTED = 200
const val MAX_ASSISTANT_COMMAND_ID_CHARS = 200
private const val MAX_QUERY_CHARS = 200

/** One command of the registry (`AppCommand`) as this device offers it. */
class AssistantCommand(
    val id: String,
    val label: String,
    val keywords: String = "",
    val hint: String? = null,
    val shortcut: String? = null,
    val changesData: Boolean = false,
    val run: () -> Unit,
)

private fun argString(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun commandId(args: JsonObject): String {
    val value = argString(args, "id")
    if (value.isNullOrEmpty() || value.length > MAX_ASSISTANT_COMMAND_ID_CHARS) throw IllegalArgumentException("`id` must be a command id from list_commands.")
    return value
}

private fun entry(cmd: AssistantCommand) = buildJsonObject {
    put("id", cmd.id)
    put("label", cmd.label)
    cmd.hint?.let { put("hint", it) }
    cmd.shortcut?.let { put("shortcut", it) }
    put("changesData", cmd.changesData)
}

/** `listAssistantCommands`: by `id`, or every word of `query` in the id, label, hint or keywords. */
fun listAssistantCommands(commands: List<AssistantCommand>, args: JsonObject, project: String?): JsonObject {
    var shown = commands
    if (args.containsKey("id") && args["id"] !is JsonNull) {
        val id = commandId(args)
        shown = shown.filter { it.id == id }
    } else {
        val query = argString(args, "query")?.trim()
        if (!query.isNullOrEmpty()) {
            val words = query.take(MAX_QUERY_CHARS).lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            shown = shown.filter { cmd ->
                val text = "${cmd.id} ${cmd.label} ${cmd.hint.orEmpty()} ${cmd.keywords}".lowercase()
                words.all { it in text }
            }
        }
    }
    return buildJsonObject {
        put("project", project?.let(::JsonPrimitive) ?: JsonNull)
        put("commands", JsonArray(shown.take(MAX_ASSISTANT_COMMANDS_LISTED).map(::entry)))
        put("total", shown.size)
    }
}

/** `runAssistantCommand`: runs it once every check passed, or throws the reason nothing ran. */
fun runAssistantCommand(commands: List<AssistantCommand>, args: JsonObject, project: String?): JsonObject {
    val id = commandId(args)
    val cmd = commands.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("No command \"$id\" is offered on this device right now; list them with list_commands.")
    if (cmd.changesData) {
        if ((args["approved"] as? JsonPrimitive)?.content != "true") {
            throw IllegalArgumentException("\"${cmd.label}\" changes data and runs only once the user approved it.")
        }
        if (argString(args, "approvedLabel") != cmd.label) {
            throw IllegalArgumentException("\"$id\" is no longer the command the user approved (it is now \"${cmd.label}\"); nothing was run.")
        }
        // The approval named the project the command would act in; the user may have switched since.
        val approved = argString(args, "approvedProject")
        if (approved != project) {
            fun named(p: String?) = if (p != null) "\"$p\"" else "no project"
            throw IllegalArgumentException("\"${cmd.label}\" was approved for ${named(approved)}, but this device now shows ${named(project)}; nothing was run.")
        }
    }
    cmd.run()
    return buildJsonObject {
        put("ran", true)
        put("id", cmd.id)
        put("label", cmd.label)
        put("project", project?.let(::JsonPrimitive) ?: JsonNull)
    }
}
