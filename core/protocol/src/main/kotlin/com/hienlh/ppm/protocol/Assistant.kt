package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The PPM Assistant as the server describes it (src/shared/assistant-cards.ts,
 * assistant-managers.ts, assistant-project.ts, assistant-ui-protocol.ts): cards, the waiting
 * counts, manager chats, the main session and the screen tools' frames. Read field by field, so a
 * server that adds a field breaks nothing here.
 */

/** The reserved project every Assistant session lives in (`ASSISTANT_PROJECT_NAME`). */
const val ASSISTANT_PROJECT_NAME = "__assistant__"

fun isAssistantProject(name: String?): Boolean = name == ASSISTANT_PROJECT_NAME

/** Kinds that wait on the user while open: the waiting counts are made of these. */
val WAITING_CARD_KINDS = setOf("needs_decision", "urgent", "approval", "question", "rules_change", "budget")
val DISMISSABLE_CARD_KINDS = setOf("needs_decision", "urgent", "done", "failed", "budget")
val ANSWERABLE_CARD_KINDS = setOf("approval", "question")
val OK_CARD_KINDS = setOf("needs_decision", "urgent")
val MANAGER_REPLY_CARD_KINDS = setOf("needs_decision", "urgent", "approval", "question", "done", "failed")
val REPORT_KINDS = setOf("needs_decision", "urgent", "done", "failed", "digest")

const val NO_REPLY_TEXT = "[no reply]"

fun isNoReplyText(text: String?): Boolean = text != null && text.trim().lowercase() == NO_REPLY_TEXT

/** Who answered a card, where, and when. */
data class CardAnswer(
    val by: String,
    val channel: String,
    val at: Long,
    val approved: Boolean? = null,
    val note: String? = null,
    val ok: Boolean? = null,
    val choice: CardOption? = null,
    val delivery: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): CardAnswer? {
            if (json?.obj == null) return null
            return CardAnswer(
                by = json["by"].string ?: "user",
                channel = json["channel"].string ?: "ppm",
                at = json["at"].long ?: 0,
                approved = json["approved"].bool,
                note = json["note"].string,
                ok = json["ok"].bool,
                choice = CardOption.from(json["choice"]),
                delivery = json["delivery"].string,
            )
        }
    }
}

data class CardFollowUp(val text: String, val truncated: Boolean, val at: Long, val newCards: List<Pair<String, String>>) {
    companion object {
        fun from(json: JsonElement?): CardFollowUp? {
            if (json?.obj == null) return null
            return CardFollowUp(
                text = json["text"].string.orEmpty(),
                truncated = json["truncated"].bool ?: false,
                at = json["at"].long ?: 0,
                newCards = json["newCards"].array.orEmpty().mapNotNull { c ->
                    val id = c["id"].string ?: return@mapNotNull null
                    id to (c["title"].string ?: "")
                },
            )
        }
    }
}

data class CardOption(val id: String, val label: String, val description: String? = null) {
    companion object {
        fun from(json: JsonElement?): CardOption? {
            val id = json["id"].string ?: return null
            return CardOption(id, json["label"].string ?: id, json["description"].string)
        }
    }
}

data class CardDraft(val to: String, val text: String, val sha256: String)

data class CardImage(val id: String, val designTitle: String, val file: String, val label: String)

data class DecidingFact(val label: String, val value: String)

/** An approval card's part a person must see before Allow means anything. */
data class CardDeciding(
    val kind: String,
    val title: String,
    val facts: List<DecidingFact>,
    val text: String,
    val label: String?,
    val complete: Boolean,
    val incompleteReason: String?,
)

data class CardQuestionOption(val label: String, val description: String?)

data class CardQuestion(
    val id: String,
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val allowsFreeText: Boolean,
    val options: List<CardQuestionOption>,
)

data class RulesLine(val add: Boolean, val line: String)

data class DigestProject(val name: String, val answered: Boolean, val items: List<String>, val waitingSummary: String?)

data class CardTaskChange(val ref: String?, val summary: String)

data class CardTask(val taskId: String, val sessionId: String, val ref: String, val title: String?)

/** What a card carries beyond its title and body; every field is kind-specific. */
data class CardPayload(
    val draft: CardDraft? = null,
    val options: List<CardOption> = emptyList(),
    val items: List<String> = emptyList(),
    val date: String? = null,
    val projects: List<DigestProject> = emptyList(),
    val resurfaced: List<Triple<String, String, String>> = emptyList(),
    val images: List<CardImage> = emptyList(),
    val subagent: Boolean = false,
    val urgentCapped: Boolean = false,
    val requestId: String? = null,
    val chatTitle: String? = null,
    val expiresAt: Long? = null,
    val answerInPpm: String? = null,
    val deciding: CardDeciding? = null,
    val questions: List<CardQuestion> = emptyList(),
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val changes: List<RulesLine> = emptyList(),
    val rulesComplete: Boolean = false,
    val rulesHash: String? = null,
    val rulesReason: String? = null,
    val detected: Boolean = false,
    val tasks: List<CardTask> = emptyList(),
    val taskChanges: List<CardTaskChange> = emptyList(),
    val taskChangesMore: Int = 0,
) {
    companion object {
        private fun strings(json: JsonElement?) = json.array.orEmpty().mapNotNull { it.string }

        fun from(json: JsonElement?): CardPayload {
            if (json?.obj == null) return CardPayload()
            val draft = json["draft"]?.takeIf { it.obj != null }?.let {
                CardDraft(it["to"].string.orEmpty(), it["text"].string.orEmpty(), it["sha256"].string.orEmpty())
            }
            val deciding = json["deciding"]?.takeIf { it.obj != null }?.let { d ->
                CardDeciding(
                    kind = d["kind"].string.orEmpty(),
                    title = d["title"].string.orEmpty(),
                    facts = d["facts"].array.orEmpty().map { DecidingFact(it["label"].string.orEmpty(), it["value"].string.orEmpty()) },
                    text = d["text"].string.orEmpty(),
                    label = d["label"].string,
                    complete = d["complete"].bool ?: false,
                    incompleteReason = d["incompleteReason"].string,
                )
            }
            return CardPayload(
                draft = draft,
                options = json["options"].array.orEmpty().mapNotNull(CardOption::from),
                items = strings(json["items"]),
                date = json["date"].string,
                projects = json["projects"].array.orEmpty().mapNotNull { p ->
                    val name = p["name"].string ?: return@mapNotNull null
                    val waiting = p["waiting"].array
                    val summary = if (waiting == null) null else {
                        val total = waiting.size + (p["waitingMore"].int ?: 0)
                        val mine = p["waitingMine"].int ?: waiting.count { it["mine"].bool == true }
                        "$total ${if (total == 1) "task" else "tasks"} waiting" + if (mine > 0) ", $mine on you" else ""
                    }
                    DigestProject(name, p["state"].string != "no_answer", strings(p["items"]), summary.takeIf { waiting?.isNotEmpty() == true })
                },
                resurfaced = json["resurfaced"].array.orEmpty().map { Triple(it["project"].string.orEmpty(), it["ref"].string.orEmpty(), it["title"].string.orEmpty()) },
                images = json["images"].array.orEmpty().mapNotNull { i ->
                    val id = i["id"].string ?: return@mapNotNull null
                    CardImage(id, i["designTitle"].string.orEmpty(), i["file"].string.orEmpty(), i["label"].string.orEmpty())
                },
                subagent = json["subagent"].bool ?: false,
                urgentCapped = json["urgentCapped"].bool ?: false,
                requestId = json["requestId"].string,
                chatTitle = json["chat"]["title"].string,
                expiresAt = json["expiresAt"].long,
                answerInPpm = json["answerInPpm"].string,
                deciding = deciding,
                questions = json["questions"].array.orEmpty().mapNotNull { q ->
                    val id = q["id"].string ?: return@mapNotNull null
                    CardQuestion(
                        id = id,
                        question = q["question"].string.orEmpty(),
                        header = q["header"].string,
                        multiSelect = q["multiSelect"].bool ?: false,
                        allowsFreeText = q["allowsFreeText"].bool ?: false,
                        options = q["options"].array.orEmpty().map { CardQuestionOption(it["label"].string.orEmpty(), it["description"].string) },
                    )
                },
                added = strings(json["added"]),
                removed = strings(json["removed"]),
                changes = json["changes"].array.orEmpty().mapNotNull { c ->
                    val line = c["line"].string ?: return@mapNotNull null
                    RulesLine(c["op"].string == "add", line)
                },
                rulesComplete = json["rulesComplete"].bool ?: false,
                rulesHash = json["rulesHash"].string,
                rulesReason = json["rulesReason"].string,
                detected = json["detected"].bool ?: false,
                tasks = json["tasks"].array.orEmpty().mapNotNull { t ->
                    val taskId = t["taskId"].string ?: return@mapNotNull null
                    CardTask(taskId, t["sessionId"].string.orEmpty(), t["ref"].string.orEmpty(), t["title"].string)
                },
                taskChanges = json["taskChanges"].array.orEmpty().mapNotNull { c ->
                    val summary = c["summary"].string ?: return@mapNotNull null
                    CardTaskChange(c["ref"].string, summary)
                },
                taskChangesMore = json["taskChangesMore"].int ?: 0,
            )
        }
    }
}

/** One of the PPM Assistant's cards (`AssistantCard`). */
data class AssistantCard(
    val id: String,
    val kind: String,
    val projectName: String?,
    val projectKey: String?,
    /** The manager chat the card came from, when it came from one. */
    val sessionId: String?,
    val title: String,
    val body: String,
    val payload: CardPayload,
    val status: String,
    val answer: CardAnswer?,
    val followUp: CardFollowUp?,
    val provenance: String?,
    val okOffered: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isOpen: Boolean get() = status == "open"

    /** Open and of a kind that waits on the user (`isWaitingCard`). */
    val isWaiting: Boolean get() = isOpen && kind in WAITING_CARD_KINDS

    companion object {
        fun from(json: JsonElement?): AssistantCard? {
            val id = json["id"].string ?: return null
            val kind = json["kind"].string ?: return null
            return AssistantCard(
                id = id,
                kind = kind,
                projectName = json["projectName"].string,
                projectKey = json["projectKey"].string,
                sessionId = json["sessionId"].string,
                title = json["title"].string.orEmpty(),
                body = json["body"].string.orEmpty(),
                payload = CardPayload.from(json["payload"]),
                status = json["status"].string ?: "open",
                answer = CardAnswer.from(json["answer"]),
                followUp = CardFollowUp.from(json["followUp"]),
                provenance = json["provenance"].string,
                okOffered = json["okOffered"].bool ?: false,
                createdAt = json["createdAt"].long ?: 0,
                updatedAt = json["updatedAt"].long ?: 0,
            )
        }

        fun list(json: JsonElement?): List<AssistantCard> = json.array.orEmpty().mapNotNull(::from)
    }
}

/** `GET /cards`: a page, newest first, and where the next older one starts. */
data class AssistantCardsPage(val cards: List<AssistantCard>, val nextBefore: Long?) {
    companion object {
        fun from(json: JsonElement?) = AssistantCardsPage(AssistantCard.list(json["cards"]), json["nextBefore"].long)
    }
}

/** Cards waiting on the user, in all and per project (`GET /waiting`, `assistant:waiting_changed`). */
data class AssistantWaiting(val total: Int, val byProject: Map<String, Int>) {
    companion object {
        val NONE = AssistantWaiting(0, emptyMap())

        fun from(json: JsonElement?): AssistantWaiting {
            val by = json["byProject"].obj.orEmpty().mapNotNull { (k, v) -> v.int?.let { k to it } }.toMap()
            return AssistantWaiting(json["total"].int ?: by.values.sum(), by)
        }
    }
}

/** What every surface listing a project's manager shows (`ManagerChatSummary`). */
data class ManagerChatSummary(
    val projectName: String,
    val projectPath: String,
    val sessionId: String,
    val providerId: String,
    val title: String?,
    val phase: String,
    val permissionMode: String,
    val waiting: Int,
    val rulesPending: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): ManagerChatSummary? {
            val project = json["projectName"].string ?: return null
            val session = json["sessionId"].string ?: return null
            return ManagerChatSummary(
                projectName = project,
                projectPath = json["projectPath"].string.orEmpty(),
                sessionId = session,
                providerId = json["providerId"].string ?: "claude",
                title = json["title"].string,
                phase = json["phase"].string ?: "idle",
                permissionMode = json["permissionMode"].string ?: "default",
                waiting = json["waiting"].int ?: 0,
                rulesPending = json["rulesPending"].bool ?: false,
            )
        }

        fun list(json: JsonElement?): List<ManagerChatSummary> = json.array.orEmpty().mapNotNull(::from)
    }
}

/** `GET /managers/:project/check`: what the assign dialog states before it opens. */
data class ManagerAssignCheck(val permissionMode: String, val replacesSessionId: String?, val replacesTitle: String?, val already: Boolean) {
    companion object {
        fun from(json: JsonElement?) = ManagerAssignCheck(
            permissionMode = json["permissionMode"].string ?: "default",
            replacesSessionId = json["replaces"]["sessionId"].string,
            replacesTitle = json["replaces"]["title"].string,
            already = json["already"].bool ?: false,
        )
    }
}

/** The main Assistant session (`parseMainSession`); null for none. */
data class MainSessionRef(val sessionId: String, val providerId: String) {
    companion object {
        fun from(json: JsonElement?): MainSessionRef? {
            val id = json["sessionId"].string?.takeIf { it.isNotEmpty() } ?: return null
            return MainSessionRef(id, json["providerId"].string?.takeIf { it.isNotEmpty() } ?: "claude")
        }
    }
}

/** `GET /api/assistant/telegram`: what the head's bell and label need. */
data class AssistantTelegram(val enabled: Boolean, val running: Boolean, val quietWhileInPpm: Boolean, val chats: Int) {
    companion object {
        fun from(json: JsonElement?) = AssistantTelegram(
            enabled = json["enabled"].bool ?: false,
            running = json["running"].bool ?: false,
            quietWhileInPpm = json["quietWhileInPpm"].bool ?: false,
            chats = json["chats"].array?.size ?: 0,
        )
    }
}

/** The screen tools' operations (`ASSISTANT_UI_OPS`). */
val ASSISTANT_UI_OPS = listOf(
    "get_state", "open_tab", "focus_tab", "close_tab", "move_tab", "switch_project",
    "reveal_path", "describe_tab", "list_commands", "run_command",
)

/** `assistant_ui`: a screen tool asking the device that sent the Assistant's last message. */
data class AssistantUiRequest(val requestId: String, val op: String, val args: JsonObject) {
    companion object {
        fun from(json: JsonElement): AssistantUiRequest? {
            val id = json["requestId"].string ?: return null
            return AssistantUiRequest(id, json["op"].string.orEmpty(), (json["args"] as? JsonObject) ?: JsonObject(emptyMap()))
        }
    }
}

/** `assistant_ui_result`: [data] when it went, [error] when it did not. */
data class AssistantUiResult(val requestId: String, val data: JsonElement?, val error: String?) : ChatClientMessage {
    override val json: JsonObject
        get() = buildJsonObject {
            put("type", "assistant_ui_result")
            put("requestId", requestId)
            if (error == null) {
                put("ok", true)
                put("data", data ?: JsonNull)
            } else {
                put("ok", false)
                put("error", error.take(MAX_ASSISTANT_UI_ERROR_CHARS))
            }
        }

    companion object {
        const val MAX_ASSISTANT_UI_ERROR_CHARS = 500
    }
}
