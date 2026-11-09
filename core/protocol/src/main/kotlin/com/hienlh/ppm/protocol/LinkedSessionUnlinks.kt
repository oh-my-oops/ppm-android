package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * Codec and shape check for synced `unlinkedSessions` UI preferences
 * (`shared/linked-session-unlinks.ts`): `chatId -> (linkedSessionId -> unlinkedAtEpochMs)`.
 */
object LinkedSessionUnlinks {
    /** Chats remembered at most; the oldest unlink falls off first. */
    const val MAX_UNLINK_CHATS = 300

    /** Hidden sessions remembered per chat. */
    const val MAX_UNLINKS_PER_CHAT = 200

    /**
     * `isUnlinkMap`: true only for a JSON object with at most [MAX_UNLINK_CHATS] chats, each a JSON
     * object with at most [MAX_UNLINKS_PER_CHAT] finite numeric timestamps.
     */
    fun isUnlinkMap(value: JsonElement?): Boolean = parse(value) != null

    /**
     * Parses a valid `UnlinkMap` or returns null when malformed (so callers leave it out rather
     * than keeping partial or corrupted entries, matching `isUnlinkMap`).
     */
    fun parse(value: JsonElement?): Map<String, Map<String, Long>>? {
        val root = value as? JsonObject ?: return null
        if (root.size > MAX_UNLINK_CHATS) return null
        val out = LinkedHashMap<String, Map<String, Long>>(root.size)
        for ((chatId, chatVal) in root) {
            val chatObj = chatVal as? JsonObject ?: return null
            if (chatObj.size > MAX_UNLINKS_PER_CHAT) return null
            val entries = LinkedHashMap<String, Long>(chatObj.size)
            for ((sessionId, tsVal) in chatObj) {
                val prim = tsVal as? JsonPrimitive ?: return null
                if (prim.isString || prim is JsonNull) return null
                val num = prim.doubleOrNull ?: return null
                if (!num.isFinite()) return null
                entries[sessionId] = floor(num).toLong()
            }
            out[chatId] = entries
        }
        return out
    }

    fun encode(map: Map<String, Map<String, Long>>): JsonObject = JsonObject(
        map.mapValues { (_, entries) ->
            JsonObject(entries.mapValues { (_, at) -> JsonPrimitive(at) })
        },
    )
}
