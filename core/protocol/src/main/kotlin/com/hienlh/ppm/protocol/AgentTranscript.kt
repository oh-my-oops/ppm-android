package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The Agent card's session window, carried over `/ws/global` (`agent-transcript-protocol.ts`).
 * One hub per (provider, session) on the server streams a card's or teammate's transcript from
 * disk, from the offsets the client already holds, and keeps pushing steps as they are written.
 * The client names only an [AgentSource] and a cursor of offsets the server handed out earlier.
 */

/** Claude and Codex are the only hubs there are; anything else is read as Claude (`normalizeProviderId`). */
fun agentTranscriptProvider(providerId: String?): String = if (providerId == "codex") "codex" else "claude"

/** Whose session a window streams: an Agent/Task card this session spawned, or a named teammate. */
sealed interface AgentSource {
    data class Card(val cardId: String) : AgentSource

    data class Member(val teamName: String, val memberName: String) : AgentSource

    fun toJson(): JsonObject = when (this) {
        is Card -> buildJsonObject {
            put("kind", "card")
            put("cardId", cardId)
        }
        is Member -> buildJsonObject {
            put("kind", "member")
            put("teamName", teamName)
            put("memberName", memberName)
        }
    }

    companion object {
        /** [toJson] read back, as a window's payload keeps it; null for anything else. */
        fun from(json: JsonElement?): AgentSource? = when (json["kind"].string) {
            "card" -> json["cardId"].string?.let(::Card)
            "member" -> {
                val teamName = json["teamName"].string
                val memberName = json["memberName"].string
                if (teamName != null && memberName != null) Member(teamName, memberName) else null
            }
            else -> null
        }
    }
}

/** One window or sheet per session (`fallbackKey`): opening the same one again focuses it. */
fun agentSessionKey(sessionId: String, source: AgentSource): String = when (source) {
    is AgentSource.Card -> "$sessionId:${source.cardId}"
    is AgentSource.Member -> "$sessionId:${source.teamName}:${source.memberName}"
}

/**
 * One step on the wire. [key] de-dupes it: a page sent again after a dropped connection carries
 * the same keys. [replace] (Codex) updates the step already shown under [key] instead of adding one.
 */
data class AgentEnvelope(val event: ChatEvent, val ts: Long, val key: String, val replace: Boolean = false)

/**
 * A running agent of a session: [cardId] for one it spawned (with [memberName] when it was given
 * a name), or [memberName] alone for a teammate with no card.
 */
data class AgentRunningEntry(val cardId: String?, val memberName: String?, val lastWriteAt: Long, val lastStep: String?)

/** What the hub sends back, each addressed to one subscription. */
sealed interface AgentTranscriptFrame {
    val subId: String

    /**
     * `agent-transcript:events`. [cursor] is what to resume from; [more] says a backlog is still
     * draining; [reset] says history was rewritten and everything held must go; [available] that
     * the source resolved to a readable file; [running] that the agent still looks active.
     */
    data class Page(
        override val subId: String,
        val events: List<AgentEnvelope>,
        val cursor: JsonObject,
        val more: Boolean = false,
        val reset: Boolean = false,
        val available: Boolean = false,
        val running: Boolean = false,
    ) : AgentTranscriptFrame

    /** `agent-transcript:error`: `not_found`, `forbidden`, `limit` or `bad_request`, never more. */
    data class Error(override val subId: String, val code: String) : AgentTranscriptFrame

    /** `agent-activity`: everyone running in the session, the whole list every time. */
    data class Activity(override val subId: String, val running: List<AgentRunningEntry>) : AgentTranscriptFrame

    companion object {
        fun from(json: JsonElement): AgentTranscriptFrame? {
            val subId = json["subId"].string ?: return null
            return when (json["type"].string) {
                "agent-transcript:events" -> Page(
                    subId = subId,
                    events = json["events"].array.orEmpty().mapNotNull(::envelope),
                    cursor = json["cursor"] as? JsonObject ?: JsonObject(emptyMap()),
                    more = json["more"].bool == true,
                    reset = json["reset"].bool == true,
                    available = json["available"].bool == true,
                    running = json["running"].bool == true,
                )
                "agent-transcript:error" -> Error(subId, json["code"].string ?: "bad_request")
                "agent-activity" -> Activity(
                    subId,
                    json["running"].array.orEmpty().mapNotNull { entry ->
                        val cardId = entry["cardId"].string?.takeIf { it.isNotEmpty() }
                        val memberName = entry["memberName"].string?.takeIf { it.isNotEmpty() }
                        if (cardId == null && memberName == null) return@mapNotNull null
                        AgentRunningEntry(cardId, memberName, entry["lastWriteAt"].double?.toLong() ?: 0L, entry["lastStep"].string)
                    },
                )
                else -> null
            }
        }

        private fun envelope(json: JsonElement): AgentEnvelope? {
            val event = json["ev"]?.takeIf { it is JsonObject } ?: return null
            val key = json["k"].string ?: return null
            return AgentEnvelope(ChatEvent.from(event), json["ts"].double?.toLong() ?: 0L, key, json["replace"].bool == true)
        }
    }
}

/** What the client sends the hub. */
object AgentTranscriptMessages {
    fun subscribe(subId: String, projectName: String, providerId: String, sessionId: String, source: AgentSource, cursor: JsonObject?): String =
        buildJsonObject {
            put("type", "agent-transcript:subscribe")
            put("subId", subId)
            put("projectName", projectName)
            put("providerId", providerId)
            put("sessionId", sessionId)
            put("source", source.toJson())
            if (cursor != null) put("cursor", cursor)
        }.toString()

    fun unsubscribe(subId: String): String = buildJsonObject {
        put("type", "agent-transcript:unsubscribe")
        put("subId", subId)
    }.toString()

    fun activitySubscribe(subId: String, projectName: String, providerId: String, sessionId: String): String = buildJsonObject {
        put("type", "agent-activity:subscribe")
        put("subId", subId)
        put("projectName", projectName)
        put("providerId", providerId)
        put("sessionId", sessionId)
    }.toString()

    fun activityUnsubscribe(subId: String): String = buildJsonObject {
        put("type", "agent-activity:unsubscribe")
        put("subId", subId)
    }.toString()
}
