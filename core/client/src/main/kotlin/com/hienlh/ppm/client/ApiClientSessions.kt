package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ChatSearchPage
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Managing a project's sessions and their tags, as the web's Chat History does.

suspend fun ApiClient.renameSession(project: String, sessionId: String, title: String) {
    call("PATCH", "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}", body = buildJsonObject { put("title", title) })
}

/** Refused (409) while edited versions are branched from it. */
suspend fun ApiClient.deleteSession(project: String, sessionId: String, providerId: String) {
    call("DELETE", "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}", listOf("providerId" to providerId))
}

/**
 * Deletes the unpinned sessions started more than [olderThanDays] days ago, except those with
 * edited versions branched from them; answers how many went out of how many.
 */
suspend fun ApiClient.deleteSessions(project: String, olderThanDays: Int): Pair<Int, Int> {
    val json = call("DELETE", "${chatPath(project)}/sessions", listOf("olderThanDays" to "$olderThanDays"))
    return (json["deleted"].int ?: 0) to (json["total"].int ?: 0)
}

/** Puts a tag on a session, or takes it off with null. */
suspend fun ApiClient.setTag(tagId: Int?, project: String, sessionId: String) {
    val path = "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/tag"
    if (tagId != null) {
        call("PATCH", path, body = buildJsonObject { put("tagId", tagId) })
    } else {
        call("DELETE", path)
    }
}

/** Unread on every device until someone opens it (the server keeps it and says so to all). */
suspend fun ApiClient.markUnread(project: String, sessionId: String) {
    call(
        "POST",
        "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}/unread",
        body = buildJsonObject { put("projectName", project) },
    )
}

/** Sessions whose title or messages contain [query]: pinned first, title matches before message matches, newest first. */
suspend fun ApiClient.searchChats(project: String, query: String): ChatSearchPage =
    ChatSearchPage.from(call("GET", "${chatPath(project)}/search", listOf("q" to query)))

// Tags

suspend fun ApiClient.createTag(project: String, name: String, color: String) {
    call("POST", tagsPath(project), body = buildJsonObject {
        put("name", name)
        put("color", color)
    })
}

suspend fun ApiClient.updateTag(project: String, id: Int, name: String, color: String) {
    call("PATCH", "${tagsPath(project)}/$id", body = buildJsonObject {
        put("name", name)
        put("color", color)
    })
}

/** Its sessions are left untagged. */
suspend fun ApiClient.deleteTag(project: String, id: Int) {
    call("DELETE", "${tagsPath(project)}/$id")
}

/** The tag new sessions get; null for none. */
suspend fun ApiClient.setDefaultTag(tagId: Int?, project: String) {
    call("PATCH", "${tagsPath(project)}/default-tag", body = buildJsonObject {
        if (tagId != null) put("tagId", tagId) else put("tagId", JsonNull)
    })
}

/** Puts back the tags a project starts with. */
suspend fun ApiClient.resetTags(project: String) {
    call("POST", "${tagsPath(project)}/reset", body = JsonObject(emptyMap()))
}
