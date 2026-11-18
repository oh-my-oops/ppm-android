package com.hienlh.ppm.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Which turn a user message started, for Copy turn link (`resolve-turn-id.ts`). In this order,
 * and never guessed:
 *
 * 1. [ChatItem.turnId], which every history read stamps.
 * 2. The binding `turn_started` made for the id this device sent the message under.
 * 3. The session's turn index, asked by the message's record uuid.
 *
 * A message with none of these yet (just sent, its turn not started) waits for 1 or 2 rather than
 * taking the newest turn, which for a queued message would be someone else's.
 */
object TurnIdResolver {
    /** `TURN_ID_WAIT_MS`: how long a just-sent message may wait for its turn to start. */
    const val WAIT_MILLIS = 120_000L
    const val POLL_MILLIS = 200L

    /** Steps 1 and 2: the id when it is already known, without asking anyone (`knownTurnId`). */
    fun known(item: ChatItem, bindings: Map<String, String>): String? = item.turnId ?: bindings[item.id]

    /**
     * The turn of the message [current] answers, read again on every check (a history reload
     * replaces the item and stamps it). Null when nothing names it within [waitMillis].
     */
    suspend fun resolve(
        current: () -> ChatItem,
        bindings: () -> Map<String, String>,
        byUuid: suspend (String) -> String?,
        waitMillis: Long = WAIT_MILLIS,
    ): String? {
        known(current(), bindings())?.let { return it }
        val uuid = current().sdkUuid
        if (uuid != null) {
            val resolved = try {
                byUuid(uuid)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (resolved != null) return resolved
        }
        var waited = 0L
        while (waited < waitMillis) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
            known(current(), bindings())?.let { return it }
        }
        return null
    }
}
