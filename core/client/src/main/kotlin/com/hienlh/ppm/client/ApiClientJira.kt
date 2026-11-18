package com.hienlh.ppm.client

import com.hienlh.ppm.client.ApiClient.Companion.escape
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * The Jira panel's ticket and filter-builder requests (`jira-ticket-detail.tsx`,
 * `jira-filter-builder.tsx`): PPM proxies them to the project's Jira site with its saved token.
 * The config, watcher and result requests live in `ApiClientSettings12c.kt`.
 */

private fun ticketPath(configId: Int, issueKey: String) = "/api/jira/ticket/$configId/${escape(issueKey)}"

/** A ticket as Jira answers it: `{ key, fields: { summary, description, status, priority, assignee, updated } }`. */
suspend fun ApiClient.jiraGetTicket(configId: Int, issueKey: String): JsonElement =
    call("GET", ticketPath(configId, issueKey))

/** Renames the ticket: the detail dialog's Summary. */
suspend fun ApiClient.jiraUpdateTicketSummary(configId: Int, issueKey: String, summary: String): JsonElement =
    call("PUT", ticketPath(configId, issueKey), body = buildJsonObject { putJsonObject("fields") { put("summary", summary) } })

/** The ways the ticket may move from its status: `[{ id, name, to: { name } }]`. */
suspend fun ApiClient.jiraTicketTransitions(configId: Int, issueKey: String): JsonElement =
    call("GET", "${ticketPath(configId, issueKey)}/transitions")

suspend fun ApiClient.jiraTransitionTicket(configId: Int, issueKey: String, transitionId: String): JsonElement =
    call("POST", "${ticketPath(configId, issueKey)}/transition", body = buildJsonObject { put("transitionId", transitionId) })

/** The filter builder's choices: `projects` (`[{ key, name }]`), `assignees` (`[{ accountId, displayName }]`), or `issuetype` | `priority` | `status` (`[{ id, name }]`). */
suspend fun ApiClient.jiraMetadata(configId: Int, field: String): JsonElement =
    call("GET", "/api/jira/metadata/$configId/$field")

/** The watcher form's Save Changes: every field, the template null when it was emptied. */
suspend fun ApiClient.jiraEditWatcher(id: Int, name: String, jql: String, promptTemplate: String?, intervalMs: Int, mode: String): JsonElement =
    call(
        "PUT",
        "/api/jira/watchers/$id",
        body = buildJsonObject {
            put("name", name)
            put("jql", jql)
            put("promptTemplate", promptTemplate)
            put("intervalMs", intervalMs)
            put("mode", mode)
        },
    )
