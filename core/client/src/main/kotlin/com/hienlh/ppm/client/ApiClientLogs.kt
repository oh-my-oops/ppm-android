package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Query log records (`GET /api/logs`). */
suspend fun ApiClient.queryLogs(query: List<Pair<String, String>>): JsonElement =
    call("GET", "/api/logs", query)

/** Context records around a selection (`GET /api/logs/around`). */
suspend fun ApiClient.logsAround(ids: List<String>, n: Int, src: String): JsonElement =
    call("GET", "/api/logs/around", listOf("ids" to ids.joinToString(","), "n" to "$n", "src" to src))

/** AI-grouped issues (`GET /api/logs/issues`). */
suspend fun ApiClient.logsIssues(): JsonElement =
    call("GET", "/api/logs/issues")

/** Issues summary for the rail badge (`GET /api/logs/issues/summary`). */
suspend fun ApiClient.logsIssuesSummary(): JsonElement =
    call("GET", "/api/logs/issues/summary")

/** Start an AI issues analysis (`POST /api/logs/issues/analyze`). */
suspend fun ApiClient.logsAnalyzeIssues(full: Boolean): JsonElement =
    call("POST", "/api/logs/issues/analyze", body = buildJsonObject { put("full", full) })

/** Toggle automatic AI sorting (`POST /api/logs/issues/auto`). */
suspend fun ApiClient.logsSetIssuesAuto(on: Boolean): JsonElement =
    call("POST", "/api/logs/issues/auto", body = buildJsonObject { put("on", on) })

/** Undismiss all hidden issues (`POST /api/logs/issues/undismiss-all`). */
suspend fun ApiClient.logsUndismissAllIssues(): JsonElement =
    call("POST", "/api/logs/issues/undismiss-all")

/** Dismiss or undismiss one issue (`POST /api/logs/issues/:id/dismiss|undismiss`). */
suspend fun ApiClient.logsSetIssueDismissed(id: String, dismissed: Boolean): JsonElement =
    call("POST", "/api/logs/issues/${ApiClient.escape(id)}/${if (dismissed) "dismiss" else "undismiss"}")

/** Host environment facts for bug reports (`GET /api/logs/environment`). */
suspend fun ApiClient.logsEnvironment(): JsonElement =
    call("GET", "/api/logs/environment")

/** Draft a bug report with AI (`POST /api/logs/report/draft`). */
suspend fun ApiClient.logsDraftReport(body: JsonElement): JsonElement =
    call("POST", "/api/logs/report/draft", body = body)

/** GitHub repository labels (`GET /api/logs/github/labels`). */
suspend fun ApiClient.logsGithubLabels(): JsonElement =
    call("GET", "/api/logs/github/labels")

/** Search duplicate GitHub issues (`GET /api/logs/github/duplicates?q=...`). */
suspend fun ApiClient.logsGithubDuplicates(q: String): JsonElement =
    call("GET", "/api/logs/github/duplicates", listOf("q" to q))
