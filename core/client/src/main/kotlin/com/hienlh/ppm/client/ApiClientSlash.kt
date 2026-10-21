package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.SlashItemsPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET …/chat/slash-items`: what `/` offers in a chat on [providerId]; a session's own when there is one. */
suspend fun ApiClient.slashItems(project: String, providerId: String, sessionId: String?): SlashItemsPayload {
    val query = buildList {
        add("providerId" to providerId)
        if (sessionId != null) add("sessionId" to sessionId)
    }
    return SlashItemsPayload.from(call("GET", "${chatPath(project)}/slash-items", query)) ?: SlashItemsPayload(emptyList())
}

/** `POST …/chat/slash-recents`: an item was picked, so it leads the list next time. */
suspend fun ApiClient.recordSlashUse(project: String, name: String, type: String) {
    call("POST", "${chatPath(project)}/slash-recents", body = buildJsonObject {
        put("name", name)
        put("type", type)
    })
}

/** `DELETE …/chat/slash-items/cache`: the server reads skills and commands from disk again. */
suspend fun ApiClient.clearSlashCache(project: String) {
    call("DELETE", "${chatPath(project)}/slash-items/cache")
}
