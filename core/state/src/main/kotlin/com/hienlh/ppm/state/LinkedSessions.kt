package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatFromSession
import com.hienlh.ppm.protocol.LinkedSessionUnlinks
import com.hienlh.ppm.protocol.SessionCollabApprovalPush
import com.hienlh.ppm.protocol.SessionCreatedPush
import com.hienlh.ppm.protocol.SessionInfo
import com.hienlh.ppm.protocol.SessionLink
import com.hienlh.ppm.protocol.SessionLinkChangedPush
import com.hienlh.ppm.protocol.SessionLinkRelation
import com.hienlh.ppm.protocol.SessionUnread
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.jsTrim
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Undo window after unlinking linked sessions (`UNDO_MS` in `linked-sessions-panel.tsx`). */
const val UNDO_UNLINK_MS = 6_000L

/** Live status of a linked session (`LinkedSessionStatus` in `linked-sessions.ts`). */
enum class LinkedSessionStatus(val wire: String, val label: String) {
    RUNNING("running", "Running"),
    IDLE("idle", "Idle"),
    NEEDS_APPROVAL("needs_approval", "Needs you"),
    NEW_MESSAGE("new_message", "New message"),
}

/** One session linked to the current chat (`LinkedSession`). */
data class LinkedSession(
    val sessionId: String,
    val title: String,
    val providerId: String,
    val relations: List<SessionLinkRelation>,
)

/** Linked session paired with its live status (`LinkedSessionView`). */
data class LinkedSessionView(
    val sessionId: String,
    val title: String,
    val providerId: String,
    val relations: List<SessionLinkRelation>,
    val status: LinkedSessionStatus,
)

/** Visible vs unlinked split (`splitUnlinked`). */
data class SplitLinkedSessions<T>(
    val visible: List<T>,
    val hidden: List<T>,
)

/** Title and provider announced over `/ws/global` (`AnnouncedSession`). */
data class AnnouncedSession(
    val title: String,
    val providerId: String,
    val projectName: String,
)

/**
 * Client-side link & collaboration card tracker (`session-collab-store.ts`), fed by `/ws/global`
 * and seeded per open chat from `GET …/sessions/:id/links`.
 */
data class SessionCollabState(
    /** `sessionId -> (otherSessionId -> relations)`. Symmetric: both directions are recorded. */
    val links: Map<String, Map<String, List<SessionLinkRelation>>> = emptyMap(),
    val announced: Map<String, AnnouncedSession> = emptyMap(),
    /** `sessionId -> pendingApprovalRequestId`. */
    val pendingApprovals: Map<String, String> = emptyMap(),
) {
    fun onSessionCreated(event: SessionCreatedPush): SessionCollabState {
        val nextAnnounced = LinkedHashMap(announced)
        if (!event.spawnedBy.isNullOrEmpty() && !event.parentTitle.isNullOrEmpty() && !event.parentProviderId.isNullOrEmpty()) {
            nextAnnounced[event.spawnedBy!!] = AnnouncedSession(
                title = event.parentTitle!!,
                providerId = event.parentProviderId!!,
                projectName = event.projectName,
            )
        }
        nextAnnounced[event.sessionId] = AnnouncedSession(
            title = event.title,
            providerId = event.providerId,
            projectName = event.projectName,
        )
        val nextLinks = if (!event.spawnedBy.isNullOrEmpty()) {
            addSymmetricLink(links, event.spawnedBy!!, event.sessionId, SessionLinkRelation.CHILD)
        } else {
            links
        }
        return copy(links = nextLinks, announced = nextAnnounced)
    }

    fun onLinkChanged(event: SessionLinkChangedPush): SessionCollabState {
        var nextLinks = links
        val ids = event.sessionIds.filter { it.isNotEmpty() }
        for (a in ids) {
            for (b in ids) {
                if (a < b && nextLinks[a]?.get(b).isNullOrEmpty()) {
                    nextLinks = addSymmetricLink(nextLinks, a, b, SessionLinkRelation.MESSAGED)
                }
            }
        }
        return if (nextLinks === links) this else copy(links = nextLinks)
    }

    fun onApprovalChanged(event: SessionCollabApprovalPush): SessionCollabState = when {
        event.state == "pending" -> copy(pendingApprovals = pendingApprovals + (event.sessionId to event.requestId))
        pendingApprovals[event.sessionId] == event.requestId -> copy(pendingApprovals = pendingApprovals - event.sessionId)
        else -> this
    }

    fun seedLinks(sessionId: String, seeded: List<SessionLink>): SessionCollabState {
        var nextLinks = links
        for (link in seeded) {
            for (relation in link.relations) {
                nextLinks = addSymmetricLink(nextLinks, sessionId, link.sessionId, relation)
            }
        }
        return if (nextLinks === links) this else copy(links = nextLinks)
    }

    companion object {
        private fun addSymmetricLink(
            links: Map<String, Map<String, List<SessionLinkRelation>>>,
            a: String,
            b: String,
            relation: SessionLinkRelation,
        ): Map<String, Map<String, List<SessionLinkRelation>>> {
            if (a == b) return links
            fun put(
                from: String,
                to: String,
                rel: SessionLinkRelation,
                acc: Map<String, Map<String, List<SessionLinkRelation>>>,
            ): Map<String, Map<String, List<SessionLinkRelation>>> {
                val current = acc[from]?.get(to).orEmpty()
                if (rel in current) return acc
                val fromMap = LinkedHashMap(acc[from].orEmpty())
                fromMap[to] = current + rel
                val next = LinkedHashMap(acc)
                next[from] = fromMap
                return next
            }
            return put(b, a, relation.mirror, put(a, b, relation, links))
        }
    }
}

private val NEEDS_USER_TYPES = setOf("approval_request", "question", "collab_approval")
private val COLLAB_TOOL_RE = Regex("^(?:mcp__ppm-sessions__|ppm_sessions:)(create_session|send_to_session|session_status)$")
private val CODEX_IMAGE_LABEL_RE = Regex("^\\[image(?: [^\\]\\s]+)?]$")

/**
 * Waiting on the user outranks running, which outranks an unread answer (`linkedSessionStatus`).
 */
fun linkedSessionStatus(
    sessionId: String,
    running: Set<String>,
    unread: Map<String, SessionUnread>,
    pendingApprovals: Map<String, String> = emptyMap(),
): LinkedSessionStatus {
    val mark = unread[sessionId]
    if (pendingApprovals.containsKey(sessionId) || (mark != null && mark.count > 0 && mark.type in NEEDS_USER_TYPES)) {
        return LinkedSessionStatus.NEEDS_APPROVAL
    }
    if (sessionId in running) return LinkedSessionStatus.RUNNING
    if (mark != null && mark.count > 0) return LinkedSessionStatus.NEW_MESSAGE
    return LinkedSessionStatus.IDLE
}

/** Idle and finished sessions can be hidden; one still working or waiting on the person cannot (`canUnlink`). */
fun canUnlink(status: LinkedSessionStatus): Boolean =
    status == LinkedSessionStatus.IDLE || status == LinkedSessionStatus.NEW_MESSAGE

/**
 * Collects every session linked to [sessionId] from the session list, the chat's own items, and
 * `/ws/global` live links (`collectLinkedSessions` in `linked-sessions.ts`).
 */
fun collectLinkedSessions(
    sessionId: String,
    list: List<SessionInfo>,
    items: List<ChatItem>,
    liveLinks: Map<String, List<SessionLinkRelation>> = emptyMap(),
    announced: Map<String, AnnouncedSession> = emptyMap(),
): List<LinkedSession> {
    class Entry {
        val relations = LinkedHashSet<SessionLinkRelation>()
        var title: String? = null
        var providerId: String? = null
    }

    val found = LinkedHashMap<String, Entry>()
    fun add(id: String?, relation: SessionLinkRelation, title: String? = null, providerId: String? = null) {
        if (id.isNullOrEmpty() || id == sessionId) return
        val entry = found.getOrPut(id) { Entry() }
        entry.relations.add(relation)
        if (entry.title == null && !title.isNullOrEmpty()) entry.title = title
        if (entry.providerId == null && !providerId.isNullOrEmpty()) entry.providerId = providerId
    }

    val self = list.firstOrNull { it.id == sessionId }
    add(self?.spawnedBy, SessionLinkRelation.PARENT, self?.parentTitle)
    for (s in list) {
        if (s.spawnedBy == sessionId) add(s.id, SessionLinkRelation.CHILD, s.title, s.providerId)
    }

    for (item in items) {
        if (item.role == ChatItem.Role.USER) {
            val from = ChatFromSession.decodeFromMessage(item.text)
            if (from != null) add(from.sessionId, SessionLinkRelation.MESSAGED, from.title, from.providerId)
            continue
        }
        if (item.role != ChatItem.Role.ASSISTANT) continue
        for (block in item.blocks) {
            if (block !is ChatBlock.Tool) continue
            val tool = block.tool
            val toolKind = COLLAB_TOOL_RE.matchEntire(tool.name)?.groupValues?.get(1) ?: continue
            if (toolKind == "session_status") continue
            val inputObj = tool.input as? JsonObject ?: continue
            val args = (inputObj["arguments"] as? JsonObject) ?: inputObj
            val outcome = tool.result
            val parsedResult = if (outcome != null && !outcome.isError) parseCollabToolResult(outcome.output) else null
            if (toolKind == "create_session") {
                val callTitle = args["title"].string?.takeIf { it.jsTrim().isNotEmpty() } ?: "New session"
                add(parsedResult?.sessionId, SessionLinkRelation.CHILD, parsedResult?.title ?: callTitle, parsedResult?.providerId)
            } else if (toolKind == "send_to_session") {
                val callSessionId = args["session_id"].string?.takeIf { it.jsTrim().isNotEmpty() } ?: continue
                // Only a send the server accepted: a refused one names whatever id the AI wrote.
                if (parsedResult != null) {
                    add(parsedResult.sessionId ?: callSessionId, SessionLinkRelation.MESSAGED, parsedResult.title, parsedResult.providerId)
                }
            }
        }
    }

    for ((id, relations) in liveLinks) {
        for (r in relations) add(id, r)
    }

    val byId = list.associateBy { it.id }
    val out = ArrayList<LinkedSession>(found.size)
    for ((id, entry) in found) {
        val raw = entry.relations.toList()
        val kept = (if (raw.any { it != SessionLinkRelation.MESSAGED }) {
            raw.filter { it != SessionLinkRelation.MESSAGED }
        } else {
            raw
        }).sortedBy { it.order }
        val known = byId[id]
        val ann = announced[id]
        val title = known?.title?.takeIf(String::isNotEmpty)
            ?: ann?.title?.takeIf(String::isNotEmpty)
            ?: entry.title?.takeIf(String::isNotEmpty)
            ?: "Untitled session"
        val providerId = known?.providerId?.takeIf(String::isNotEmpty)
            ?: ann?.providerId?.takeIf(String::isNotEmpty)
            ?: entry.providerId.orEmpty()
        out += LinkedSession(
            sessionId = id,
            title = title,
            providerId = providerId,
            relations = kept,
        )
    }
    return out.sortedBy { it.relations.first().order }
}

private data class ParsedCollabResult(
    val sessionId: String?,
    val title: String?,
    val providerId: String?,
)

private fun parseCollabToolResult(output: String): ParsedCollabResult? {
    val raw = stripToolResultWrapper(output).trim()
    if (!raw.startsWith("{")) return null
    val parsed = WireJson.parseOrNull(raw) as? JsonObject ?: return null
    val statusObj = parsed["status"] as? JsonObject
    val validStatus = statusObj?.takeIf { it["sessionId"].string != null && it["phase"].string != null }
    val sessionId = parsed["sessionId"].string ?: validStatus?.get("sessionId").string
    val title = parsed["title"].string ?: validStatus?.get("title").string
    val providerId = parsed["providerId"].string ?: validStatus?.get("providerId").string?.takeIf(String::isNotEmpty)
    val delivered = parsed["delivered"].string?.takeIf { it == "inserted" || it == "queued" || it == "started" }
    val hasReply = (parsed["reply"] as? JsonObject) != null
    val hasSessions = parsed["sessions"].array != null
    if (sessionId == null && title == null && providerId == null && delivered == null && validStatus == null && !hasReply && !hasSessions) {
        return null
    }
    return ParsedCollabResult(sessionId = sessionId, title = title, providerId = providerId)
}

private fun stripToolResultWrapper(output: String): String {
    fun plain(): String = output.split('\n').filterNot { CODEX_IMAGE_LABEL_RE.matches(it) }.joinToString("\n").trimEnd()
    if (!output.startsWith("[")) return plain()
    val blocks = WireJson.parseOrNull(output) as? JsonArray ?: return plain()
    return blocks.mapNotNull { b ->
        val obj = b as? JsonObject ?: return@mapNotNull null
        if (obj["type"].string == "text") obj["text"].string else null
    }.joinToString("\n\n")
}

/**
 * When each session last messaged this chat (`lastMessageFrom` in `linked-session-unlinks.ts`),
 * measured on the server's clock (`fromSession.sentAt`, else the message's own timestamp).
 */
fun lastMessageFrom(items: List<ChatItem>): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for (item in items) {
        if (item.role != ChatItem.Role.USER) continue
        val from = ChatFromSession.decodeFromMessage(item.text) ?: continue
        val at = isoMillis(from.sentAt.ifEmpty { item.timestampText.orEmpty() })
            ?: item.timestamp?.toEpochMilli()
            ?: continue
        val prev = out[from.sessionId] ?: 0L
        if (at > prev) out[from.sessionId] = at
    }
    return out
}

/**
 * Splits [linked] into visible and hidden (`splitUnlinked` in `linked-session-unlinks.ts`).
 * A hidden session comes back when it messages after its unlink or while running / needing approval.
 */
fun splitUnlinked(
    linked: List<LinkedSessionView>,
    unlinkedForChat: Map<String, Long>?,
    lastFrom: Map<String, Long>,
): SplitLinkedSessions<LinkedSessionView> {
    val visible = ArrayList<LinkedSessionView>(linked.size)
    val hidden = ArrayList<LinkedSessionView>()
    for (item in linked) {
        val at = unlinkedForChat?.get(item.sessionId)
        val busy = item.status == LinkedSessionStatus.RUNNING || item.status == LinkedSessionStatus.NEEDS_APPROVAL
        if (at != null && !busy && !((lastFrom[item.sessionId] ?: 0L) > at)) {
            hidden += item
        } else {
            visible += item
        }
    }
    return SplitLinkedSessions(visible, hidden)
}

/**
 * Updates `unlinkedSessions` with [ids] hidden from or restored to [chat], enforcing
 * [LinkedSessionUnlinks.MAX_UNLINKS_PER_CHAT] and [LinkedSessionUnlinks.MAX_UNLINK_CHATS] (`updateUnlinks`).
 */
fun updateUnlinks(
    map: Map<String, Map<String, Long>>,
    chat: String,
    ids: Collection<String>,
    unlink: Boolean,
    atProvider: (String) -> Long = { 0L },
): Map<String, Map<String, Long>> {
    val forChat = LinkedHashMap(map[chat].orEmpty())
    for (id in ids) {
        if (unlink) forChat[id] = atProvider(id) else forChat.remove(id)
    }
    val next = LinkedHashMap(map)
    val kept = forChat.entries
        .sortedByDescending { it.value }
        .take(LinkedSessionUnlinks.MAX_UNLINKS_PER_CHAT)
    if (kept.isNotEmpty()) {
        next[chat] = kept.associate { it.key to it.value }
    } else {
        next.remove(chat)
    }
    if (next.size <= LinkedSessionUnlinks.MAX_UNLINK_CHATS) return next
    fun newest(entries: Map<String, Long>): Long = entries.values.maxOrNull() ?: Long.MIN_VALUE
    return next.entries
        .sortedByDescending { newest(it.value) }
        .take(LinkedSessionUnlinks.MAX_UNLINK_CHATS)
        .associate { it.key to it.value }
}
