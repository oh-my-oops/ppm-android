package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.ASSISTANT_PROJECT_NAME
import com.hienlh.ppm.protocol.AssistantCard
import com.hienlh.ppm.protocol.AssistantCardsPage
import com.hienlh.ppm.protocol.AssistantTelegram
import com.hienlh.ppm.protocol.AssistantWaiting
import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.protocol.MainSessionRef
import com.hienlh.ppm.protocol.ManagerAssignCheck
import com.hienlh.ppm.protocol.ManagerChatSummary
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The PPM Assistant over HTTP (`assistant-cards-api.ts`, `use-manager-chats.ts`,
 * `use-assistant-main-session.ts`, `assistant-settings-client.ts`). A card answered elsewhere
 * comes back as a refusal whose `data` may carry the card as it is now and a question to ask.
 */

private const val ASSISTANT = "/api/assistant"

private fun cardPath(id: String) = "$ASSISTANT/cards/${ApiClient.escape(id)}"

/** A page of cards, newest first; [before] reads the next older page. */
suspend fun ApiClient.assistantCards(before: Long? = null, limit: Int = 30, project: String? = null): AssistantCardsPage =
    AssistantCardsPage.from(
        call(
            "GET",
            "$ASSISTANT/cards",
            listOfNotNull(
                before?.let { "before" to it.toString() },
                "limit" to limit.toString(),
                project?.takeIf { it.isNotEmpty() }?.let { "project" to it },
            ),
        ),
    )

/** Every card still waiting on the user, oldest first. */
suspend fun ApiClient.assistantOpenCards(): List<AssistantCard> = AssistantCard.list(call("GET", "$ASSISTANT/cards/open")["cards"])

suspend fun ApiClient.assistantWaiting(): AssistantWaiting = AssistantWaiting.from(call("GET", "$ASSISTANT/waiting"))

/** What an OK, a choice or a reply answers with: the card now, and where the words went. */
data class CardPress(val card: AssistantCard?, val to: String?, val projectName: String?) {
    companion object {
        fun from(json: JsonElement) = CardPress(AssistantCard.from(json["card"]), json["to"].string, json["projectName"].string)
    }
}

/** Allow or deny on an approval card. */
suspend fun ApiClient.answerAssistantCard(id: String, allow: Boolean): AssistantCard? =
    AssistantCard.from(call("POST", "${cardPath(id)}/answer", body = buildJsonObject { put("decision", if (allow) "allow" else "deny") })["card"])

/** A question card's answers, by question id. */
suspend fun ApiClient.answerAssistantQuestions(id: String, answersById: Map<String, List<String>>): AssistantCard? =
    AssistantCard.from(
        call(
            "POST",
            "${cardPath(id)}/answer",
            body = buildJsonObject {
                put("answersById", JsonObject(answersById.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }))
            },
        )["card"],
    )

suspend fun ApiClient.dismissAssistantCard(id: String): AssistantCard? = AssistantCard.from(call("POST", "${cardPath(id)}/dismiss")["card"])

/** OK on a needs-decision card, for the exact draft it showed (its hash), or none. */
suspend fun ApiClient.okAssistantCard(id: String, draftSha256: String?): CardPress = CardPress.from(
    call(
        "POST",
        "${cardPath(id)}/ok",
        body = buildJsonObject {
            put("draftSha256", draftSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("deviceId", ChatClientId.value)
        },
    ),
)

/** A press on one of a card's choices, named by its id. */
suspend fun ApiClient.chooseAssistantCardOption(id: String, optionId: String): CardPress = CardPress.from(
    call(
        "POST",
        "${cardPath(id)}/choose",
        body = buildJsonObject {
            put("optionId", optionId)
            put("deviceId", ChatClientId.value)
        },
    ),
)

/**
 * The user's own words to a card; [force] after the user confirmed a stale card, [confirmAge] to
 * confirm only its age. Pictures go as their bytes.
 */
suspend fun ApiClient.replyToAssistantCard(
    id: String,
    text: String,
    force: Boolean = false,
    images: List<InlineImage> = emptyList(),
    confirmAge: Boolean = false,
): CardPress = CardPress.from(
    call(
        "POST",
        "${cardPath(id)}/reply",
        body = buildJsonObject {
            put("text", text)
            if (force) put("force", true)
            if (confirmAge) put("confirmAge", true)
            put("deviceId", ChatClientId.value)
            if (images.isNotEmpty()) {
                put(
                    "images",
                    JsonArray(
                        images.map {
                            buildJsonObject {
                                put("data", it.data)
                                put("mediaType", it.mediaType)
                            }
                        },
                    ),
                )
            }
        },
    ),
)

/** A held rules change: approve or revert it, named by its hash. */
suspend fun ApiClient.decideRulesChange(project: String, approve: Boolean, hash: String) {
    call(
        "POST",
        "$ASSISTANT/managers/${ApiClient.escape(project)}/rules/decision",
        body = buildJsonObject {
            put("action", if (approve) "approve" else "revert")
            put("hash", hash)
        },
    )
}

/** The main Assistant session, or null for none. */
suspend fun ApiClient.assistantMainSession(): MainSessionRef? = MainSessionRef.from(call("GET", "$ASSISTANT/main-session")["main"])

suspend fun ApiClient.setAssistantMainSession(sessionId: String) {
    call("PUT", "$ASSISTANT/main-session", body = buildJsonObject { put("sessionId", sessionId) })
}

suspend fun ApiClient.managerChats(): List<ManagerChatSummary> = ManagerChatSummary.list(call("GET", "$ASSISTANT/managers")["managers"])

suspend fun ApiClient.checkManagerChat(project: String, sessionId: String): ManagerAssignCheck = ManagerAssignCheck.from(
    call("GET", "$ASSISTANT/managers/${ApiClient.escape(project)}/check", listOf("sessionId" to sessionId)),
)

suspend fun ApiClient.setManagerChat(project: String, sessionId: String) {
    call("PUT", "$ASSISTANT/managers/${ApiClient.escape(project)}", body = buildJsonObject { put("sessionId", sessionId) })
}

suspend fun ApiClient.unsetManagerChat(project: String) {
    call("DELETE", "$ASSISTANT/managers/${ApiClient.escape(project)}")
}

suspend fun ApiClient.assistantTelegram(): AssistantTelegram = AssistantTelegram.from(call("GET", "$ASSISTANT/telegram"))

/** "Quiet while I'm at PPM" (`clawbot.quiet_while_in_ppm`). */
suspend fun ApiClient.setTelegramQuiet(on: Boolean) {
    call("PUT", "/api/settings/clawbot", body = buildJsonObject { put("quiet_while_in_ppm", on) })
}

/** The Assistant's settings the window needs: the persona's name and the default provider. */
data class AssistantBasics(val name: String?, val defaultProvider: String?)

suspend fun ApiClient.assistantBasics(): AssistantBasics {
    val settings = call("GET", "$ASSISTANT/settings")["settings"]
    return AssistantBasics(settings["persona"]["name"].string?.takeIf { it.isNotBlank() }, settings["default_provider"].string)
}

/**
 * The configured providers that can run the Assistant (`useAssistantProviders`): Claude or Codex,
 * and only when the server says it will enforce the Assistant's policy on them.
 */
suspend fun ApiClient.assistantProviders(): List<ProviderInfo> =
    call("GET", "${chatPath(ASSISTANT_PROJECT_NAME)}/providers").array.orEmpty()
        .filter { it["supportsAssistantSessions"].bool == true && it["id"].string in listOf("claude", "codex") }
        .mapNotNull(ProviderInfo::from)

/** What a refused OK, choice or reply said back (`cardRefusal`). */
data class CardRefusal(val error: String, val question: String?, val card: AssistantCard?) {
    companion object {
        fun of(error: Throwable): CardRefusal {
            val data = (error as? PpmError.Server)?.data
            return CardRefusal(error.message ?: error.toString(), data["question"].string, AssistantCard.from(data["card"]))
        }
    }
}

/** A design screenshot a manager put on a card (`/api/design-shots/<id>.png`, behind the login). */
suspend fun ApiClient.designShot(id: String): ByteArray =
    bytes(request("GET", "/api/design-shots/" + ApiClient.escape(id) + ".png"))
