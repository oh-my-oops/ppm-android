package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.Group
import com.hienlh.ppm.protocol.GroupMember
import com.hienlh.ppm.protocol.GroupMessage
import com.hienlh.ppm.protocol.GroupTranscript
import com.hienlh.ppm.protocol.MemberValues
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/* Group chat (`api-group-chat.ts`), under `/api/group-chat`. */

private const val GROUPS = "/api/group-chat"

private fun groupPath(id: String) = "$GROUPS/${ApiClient.escape(id)}"

private fun MemberValues.json(withColor: Boolean): JsonObject = buildJsonObject {
    put("role", role)
    put("name", name)
    put("persona", persona)
    put("model", model)
    if (withColor) put("color", color)
}

private fun group(json: JsonElement): Group = Group.from(json) ?: throw PpmError.BadResponse("Unexpected group.")

private fun member(json: JsonElement): GroupMember = GroupMember.from(json) ?: throw PpmError.BadResponse("Unexpected member.")

/** The project's groups, newest first; a project is named by its absolute path here. */
suspend fun ApiClient.groups(projectPath: String): List<Group> =
    call("GET", GROUPS, listOf("projectPath" to projectPath)).array.orEmpty().mapNotNull(Group::from)

/** One group with its whole roster: persona and model included, which the socket does not carry. */
suspend fun ApiClient.group(id: String): Group = group(call("GET", groupPath(id)))

/** Exactly one of [members] must be the leader. Creating starts nothing: sessions come with the first message. */
suspend fun ApiClient.createGroup(projectName: String, projectPath: String, name: String, maxTurns: Int, members: List<MemberValues>): Group =
    group(
        call("POST", GROUPS, body = buildJsonObject {
            put("projectName", projectName)
            put("projectPath", projectPath)
            put("name", name)
            put("maxTurns", maxTurns)
            putJsonArray("members") { members.forEach { add(it.json(withColor = true)) } }
        }),
    )

/** The reply cap, 1–50 AI turns per user message. */
suspend fun ApiClient.setGroupReplyCap(id: String, maxTurns: Int): Group =
    group(call("PATCH", groupPath(id), body = buildJsonObject { put("maxTurns", maxTurns) }))

/** The last [limit] messages, oldest first. */
suspend fun ApiClient.groupFeed(id: String, limit: Int): List<GroupMessage> =
    call("GET", "${groupPath(id)}/feed", listOf("limit" to "$limit"))["messages"].array.orEmpty().mapNotNull(GroupMessage::from)

/** Starts a reply burst, or queues the message behind the one running. */
suspend fun ApiClient.sendGroupMessage(id: String, content: String) {
    call("POST", "${groupPath(id)}/message", body = buildJsonObject { put("content", content) })
}

suspend fun ApiClient.stopGroup(id: String) {
    call("POST", "${groupPath(id)}/stop")
}

/** Answers the last user message again if a Stop cut its burst; otherwise does nothing. */
suspend fun ApiClient.resumeGroup(id: String) {
    call("POST", "${groupPath(id)}/resume")
}

suspend fun ApiClient.deleteGroup(id: String) {
    call("DELETE", groupPath(id))
}

/** A member added from the roster: no colour, as the web's form sends none. */
suspend fun ApiClient.addGroupMember(id: String, values: MemberValues): GroupMember =
    member(call("POST", "${groupPath(id)}/members", body = values.json(withColor = false)))

/** Making a member the leader takes the role from the one who had it. */
suspend fun ApiClient.updateGroupMember(id: String, memberId: String, values: MemberValues): GroupMember =
    member(call("PATCH", "${groupPath(id)}/members/${ApiClient.escape(memberId)}", body = values.json(withColor = false)))

/** Refused for the leader and for a group's last member. */
suspend fun ApiClient.removeGroupMember(id: String, memberId: String) {
    call("DELETE", "${groupPath(id)}/members/${ApiClient.escape(memberId)}")
}

/** A member session's transcript: live while the group lives, archived once it or the member is deleted. */
suspend fun ApiClient.groupTranscript(id: String, sessionRef: String): GroupTranscript =
    GroupTranscript.from(call("GET", "${groupPath(id)}/transcript", listOf("sessionRef" to sessionRef)))
