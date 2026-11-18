package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.FromSessionReference
import com.hienlh.ppm.protocol.HistoryMessage
import com.hienlh.ppm.protocol.TurnLinkTarget
import com.hienlh.ppm.protocol.TurnLinks
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.generated.TurnReference
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement

/**
 * A chat session's turns, for turn links, turn chips and the jump to a turn
 * (`chat-session-turns.ts`, `chat-turn-window.ts`). Every answer is for a session of the project
 * asked: another project's session is a 404 as if it did not exist.
 */

/** The machine-readable codes a turn route fails with. */
object TurnErrorCodes {
    const val NOT_FOUND = "not_found"
    const val ROLLED_BACK = "rolled_back"
    const val SESSION_MISSING = "session_missing"

    /** A turn a rollback removed, or one the session no longer has. */
    fun gone(error: Throwable): Boolean = (error as? PpmError.Server)?.code.let { it == NOT_FOUND || it == ROLLED_BACK || it == SESSION_MISSING }
}

private fun ApiClient.turnsPath(project: String, sessionId: String) =
    "${chatPath(project)}/sessions/${ApiClient.escape(sessionId)}"

/**
 * The turn one message started, by its record uuid (`GET …/turns?resolve=`); null when the index
 * does not know it. Every other failure is null too: the copy then waits for the turn to start.
 */
suspend fun ApiClient.resolveTurnId(project: String, sessionId: String, providerId: String, messageUuid: String): String? {
    if (messageUuid.length > 128) return null
    val data = call(
        "GET",
        "${turnsPath(project, sessionId)}/turns",
        listOf("providerId" to providerId, "resolve" to messageUuid),
    )
    return data["resolved"]["turnId"].string?.takeIf { FromSessionReference.TURN_ID_RE.matches(it) }
}

/** What `GET …/turns/:turnId` says about a turn: what a chip shows. */
suspend fun ApiClient.sessionTurnMeta(target: TurnLinkTarget): JsonElement =
    call(
        "GET",
        "${turnsPath(target.projectName, target.sessionId)}/turns/${ApiClient.escape(target.turnId)}",
        listOf("providerId" to target.providerId),
    )

/** The reference a pasted link resolves to, or null when the answer is not one a chip can send. */
suspend fun ApiClient.turnReference(target: TurnLinkTarget): TurnReference? =
    TurnLinks.refFromMeta(target, sessionTurnMeta(target))

/** One turn of a history window's answer. */
data class WindowTurn(val turnId: String, val n: Int, val running: Boolean)

/** One answer of `GET …/turn-window` (`TurnWindowPage`). */
data class TurnWindowPage(
    /** The id that owns the transcript (after a Codex migration it differs from the one asked for). */
    val sessionId: String,
    val turns: List<WindowTurn>,
    /** The turns' messages in order, each stamped with its turn. */
    val messages: List<HistoryMessage>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    /** The session's newest turn, so the chat can tell when the window has reached the live list. */
    val latestTurnId: String?,
) {
    companion object {
        fun from(json: JsonElement, askedFor: String): TurnWindowPage = TurnWindowPage(
            sessionId = json["sessionId"].string ?: askedFor,
            turns = json["turns"].array.orEmpty().mapNotNull { t ->
                val id = t["turnId"].string ?: return@mapNotNull null
                WindowTurn(id, t["n"].int ?: 0, t["status"].string == "running")
            },
            messages = json["messages"].array.orEmpty().mapNotNull(HistoryMessage::from),
            hasOlder = json["hasOlder"].bool ?: false,
            hasNewer = json["hasNewer"].bool ?: false,
            latestTurnId = json["latestTurnId"].string,
        )
    }
}

/** Which turns a window page asks for. */
sealed interface TurnWindowQuery {
    /** The turn and some turns either side of it (the server's defaults: 2 before, 3 after). */
    data class Around(val turnId: String) : TurnWindowQuery

    /** Turns after this one, for scrolling down. */
    data class After(val turnId: String, val count: Int) : TurnWindowQuery

    /** Turns before this one, for scrolling up. */
    data class Before(val turnId: String, val count: Int) : TurnWindowQuery
}

/** A block of a session's turns read straight from the turn index (`GET …/turn-window`). */
suspend fun ApiClient.turnWindow(project: String, sessionId: String, providerId: String, query: TurnWindowQuery): TurnWindowPage {
    val params = listOf("providerId" to providerId) + when (query) {
        is TurnWindowQuery.Around -> listOf("turn" to query.turnId)
        is TurnWindowQuery.After -> listOf("afterTurn" to query.turnId, "count" to query.count.toString())
        is TurnWindowQuery.Before -> listOf("beforeTurn" to query.turnId, "count" to query.count.toString())
    }
    return TurnWindowPage.from(call("GET", "${turnsPath(project, sessionId)}/turn-window", params), sessionId)
}

/** The installed extensions' contributions (`GET /api/extensions/contributions`), read again after `extensions:changed`. */
suspend fun ApiClient.extensionContributions(): JsonElement = call("GET", "/api/extensions/contributions")
