package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireDate
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/*
 * Spec mode's REST calls (`/api/project/:name/specs`, routes/specs.ts) as the web's `api-specs.ts`
 * makes them, and the shapes they answer (`shared/spec-types.ts` `SpecSummary`, `shared/spec-view.ts`
 * `SpecView`), read field by field so a field the server adds later never fails a decode. A gate that
 * says no answers 409 with the reason, which [ApiClient.call] throws as a [PpmError.Server] whose
 * message is that reason, shown as is.
 */

/** The three documents of a spec, in the order Kiro writes them. */
object SpecPhases {
    const val REQUIREMENTS = "requirements"
    const val DESIGN = "design"
    const val TASKS = "tasks"

    /** The stage the spec stands at once its tasks are approved. */
    const val EXECUTION = "execution"

    /** The UI drawing of a `UI: yes` spec, approved like a document. */
    const val DRAWING = "drawing"

    val ALL = listOf(REQUIREMENTS, DESIGN, TASKS)
}

/** One task of a spec as Chat History's progress bar reads it (`SpecSummary.tasks`). */
data class SpecSummaryTask(val id: String, val status: String, val optional: Boolean, val sessionId: String?)

/** A line of the Specs list (`SpecSummary`). */
data class SpecSummary(
    val slug: String,
    val title: String,
    /** `feature`, `bugfix` or `quick`. */
    val kind: String,
    /** `standard` or `thorough`. */
    val checks: String,
    /** A phase id, or [SpecPhases.EXECUTION]. */
    val current: String,
    val tasksDone: Int,
    val tasksTotal: Int,
    val red: Int,
    val running: Int,
    val waitingForYou: Boolean,
    /** Every task in tasks.md order; empty from a server older than the History progress bar. */
    val tasks: List<SpecSummaryTask>,
    /** The chat that drives it, when it has one. */
    val sessionId: String?,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(json: JsonElement?): SpecSummary? {
            val slug = json["slug"].string ?: return null
            return SpecSummary(
                slug = slug,
                title = json["title"].string ?: slug,
                kind = json["kind"].string ?: "feature",
                checks = json["checks"].string ?: "standard",
                current = json["current"].string ?: SpecPhases.REQUIREMENTS,
                tasksDone = json["tasksDone"].int ?: 0,
                tasksTotal = json["tasksTotal"].int ?: 0,
                red = json["red"].int ?: 0,
                running = json["running"].int ?: 0,
                waitingForYou = json["waitingForYou"].bool ?: false,
                tasks = json["tasks"].array.orEmpty().mapNotNull { t ->
                    val id = t["id"].string ?: return@mapNotNull null
                    SpecSummaryTask(id, t["status"].string ?: "todo", t["optional"].bool ?: false, t["sessionId"].string)
                },
                sessionId = json["sessionId"].string,
                updatedAt = WireDate.parse(json["updatedAt"]),
            )
        }
    }
}

/** A problem the parser found in a document or the wave plan (`SpecIssue`). */
data class SpecIssue(val line: Int, val severity: String, val message: String) {
    val isError: Boolean get() = severity == "error"

    companion object {
        fun list(json: JsonElement?): List<SpecIssue> = json.array.orEmpty().mapNotNull { i ->
            val message = i["message"].string ?: return@mapNotNull null
            SpecIssue(i["line"].int ?: 0, i["severity"].string ?: "warning", message)
        }
    }
}

/** One document's state (`SpecView.phases[phase]`). */
data class SpecPhaseView(
    /** `missing`, `invalid`, `draft`, `approved` or `stale`. */
    val status: String,
    val file: String,
    val issues: List<SpecIssue>,
    /** The document, or null before the AI wrote it. */
    val text: String?,
    /** The AI submitted it and it has not changed since: it waits for the user. */
    val submitted: Boolean,
    /** Why this phase cannot be approved now (the drawing, the test command, an earlier phase…); null when it can. */
    val approveBlocked: String? = null,
) {
    /**
     * Approve and Request changes are offered (`canAct` in spec-pane.tsx) once there is something to
     * review: a document the AI submitted, or one changed after its approval, that nothing else holds back.
     */
    val canAct: Boolean get() = (submitted || status == "stale") && approveBlocked == null
}

/** What a test run looked like (`SpecRunRecord`), as much of it as the task list shows. */
data class SpecRunRecord(val outcome: String, val failing: List<String>, val passed: Int?, val failed: Int?) {
    companion object {
        fun from(json: JsonElement?): SpecRunRecord? {
            if (json.obj == null) return null
            return SpecRunRecord(
                outcome = json["outcome"].string ?: "",
                failing = json["failing"].array.orEmpty().mapNotNull { it.string },
                passed = json["passed"].int,
                failed = json["failed"].int,
            )
        }
    }
}

/** A task's run (`SpecTaskState`): its chat, why it failed, and its red and green test runs. */
data class SpecTaskRun(
    val sessionId: String?,
    val error: String?,
    val red: SpecRunRecord?,
    val green: SpecRunRecord?,
    val lastRun: SpecRunRecord?,
) {
    companion object {
        fun from(json: JsonElement?): SpecTaskRun? {
            if (json.obj == null) return null
            return SpecTaskRun(
                sessionId = json["sessionId"].string,
                error = json["error"].string,
                red = SpecRunRecord.from(json["red"]),
                green = SpecRunRecord.from(json["green"]),
                lastRun = SpecRunRecord.from(json["lastRun"]),
            )
        }
    }
}

/**
 * A run PPM has in flight for a task now (`SpecTaskView.testing`): its `red` or `green` tests, or
 * (`suite`) the whole suite its `task_green` call runs after the task went green, before that call answers.
 */
data class SpecTaskTesting(val kind: String, val startedAt: Instant?) {
    companion object {
        fun from(json: JsonElement?): SpecTaskTesting? {
            if (json.obj == null) return null
            val kind = json["kind"].string?.takeIf { it == "red" || it == "green" || it == "suite" } ?: return null
            return SpecTaskTesting(kind, WireDate.parse(json["startedAt"]))
        }
    }
}

/** One task (`SpecTaskView`). */
data class SpecTaskView(
    val id: String,
    val title: String,
    val optional: Boolean,
    /** `todo`, `running`, `red`, `done` or `failed`. */
    val status: String,
    val done: Boolean,
    /** `start`, `done`, `red`, `green`, `finished` or `blocked`. */
    val next: String,
    val blockedReason: String?,
    val wave: Int?,
    val requirements: List<String>,
    val deps: List<String>,
    val files: List<String>,
    val tests: List<String>,
    val noTest: String?,
    val run: SpecTaskRun?,
    /** A run PPM has in flight for this task now; null from a server older than it, or when none runs. */
    val testing: SpecTaskTesting? = null,
) {
    /** Running, or red and writing its code. */
    val active: Boolean get() = status == "running" || status == "red"
}

/** The run of Run all (`SpecRunState`), as far as the screen shows it. */
data class SpecRunState(val active: Boolean, val wave: Int)

/** What the server answers about a spec (`SpecView`), as far as the phone shows it. */
data class SpecView(
    val slug: String,
    val title: String,
    val kind: String,
    val workflow: String,
    val checks: String,
    val folder: String,
    val current: String,
    val order: List<String>,
    val phases: Map<String, SpecPhaseView>,
    val needsDrawing: Boolean,
    /** `not-needed`, `missing`, `draft`, `approved` or `stale`. */
    val drawing: String,
    val designSlug: String?,
    val waves: List<List<String>>,
    /** Tasks put in a later wave than their deps required, and why. */
    val moved: Map<String, String>,
    val waveIssues: List<SpecIssue>,
    val tasks: List<SpecTaskView>,
    val baseline: SpecRunRecord?,
    val baselineRunning: Boolean,
    /** Failures the whole suite has that its baseline did not. */
    val suiteNewFailures: List<String>,
    val suiteRunning: Boolean,
    val run: SpecRunState?,
    /** A run marked active that this server process drives; false after a restart interrupted it. */
    val runLive: Boolean,
    val sessionId: String?,
    val updatedAt: Instant?,
) {
    val thorough: Boolean get() = checks == "thorough"

    /** Run all is going (`running` in `SpecRunControls`). */
    val running: Boolean get() = run?.active == true && runLive

    /** A restart stopped the run: Stop clears it, then Run all starts it again. */
    val interrupted: Boolean get() = run?.active == true && !runLive

    /** Every required task is done, so Run all has nothing left (`allDone`). */
    val allDone: Boolean get() = tasks.any { !it.optional } && tasks.all { it.optional || it.done }

    companion object {
        fun from(json: JsonElement?): SpecView? {
            val slug = json["slug"].string ?: return null
            val phases = json["phases"].obj.orEmpty().mapValues { (phase, p) ->
                SpecPhaseView(
                    status = p["status"].string ?: "missing",
                    file = p["file"].string ?: "specs/$slug/$phase.md",
                    issues = SpecIssue.list(p["issues"]),
                    text = p["text"].string,
                    submitted = p["submitted"].bool ?: false,
                    approveBlocked = p["approveBlocked"].string,
                )
            }
            val waves = json["waves"]
            return SpecView(
                slug = slug,
                title = json["title"].string ?: slug,
                kind = json["kind"].string ?: "feature",
                workflow = json["workflow"].string ?: "requirements-first",
                checks = json["checks"].string ?: "standard",
                folder = json["folder"].string ?: "specs/$slug",
                current = json["current"].string ?: SpecPhases.REQUIREMENTS,
                order = json["order"].array?.mapNotNull { it.string } ?: SpecPhases.ALL,
                phases = phases,
                needsDrawing = json["needsDrawing"].bool ?: false,
                drawing = json["drawing"].string ?: "not-needed",
                designSlug = json["designSlug"].string,
                waves = waves["waves"].array.orEmpty().map { w -> w.array.orEmpty().mapNotNull { it.string } },
                moved = waves["moved"].array.orEmpty().mapNotNull { m ->
                    val id = m["id"].string ?: return@mapNotNull null
                    id to (m["reason"].string ?: "")
                }.toMap(),
                waveIssues = SpecIssue.list(waves["issues"]),
                tasks = json["tasks"].array.orEmpty().mapNotNull(::task),
                baseline = SpecRunRecord.from(json["baseline"]),
                baselineRunning = json["baselineRunning"].bool ?: false,
                suiteNewFailures = json["suite"]["newFailures"].array.orEmpty().mapNotNull { it.string },
                suiteRunning = json["suiteRunning"].bool ?: false,
                run = json["run"].takeIf { it.obj != null }?.let { SpecRunState(it["active"].bool ?: false, it["wave"].int ?: 0) },
                runLive = json["runLive"].bool ?: false,
                sessionId = json["sessionId"].string,
                updatedAt = WireDate.parse(json["updatedAt"]),
            )
        }

        private fun task(t: JsonElement): SpecTaskView? {
            val id = t["id"].string ?: return null
            val strings = { key: String -> t[key].array.orEmpty().mapNotNull { it.string } }
            return SpecTaskView(
                id = id,
                title = t["title"].string ?: "",
                optional = t["optional"].bool ?: false,
                status = t["status"].string ?: "todo",
                done = t["done"].bool ?: false,
                next = t["next"].string ?: "blocked",
                blockedReason = t["blockedReason"].string,
                wave = t["wave"].int,
                requirements = strings("requirements"),
                deps = strings("deps"),
                files = strings("files"),
                tests = strings("tests"),
                noTest = t["noTest"].string,
                run = SpecTaskRun.from(t["run"]),
                testing = SpecTaskTesting.from(t["testing"]),
            )
        }
    }
}

/** What an act answers (`SpecActionResult`): what to tell the user, and the spec as it is now. */
data class SpecActionResult(val message: String, val spec: SpecView)

/** Which spec a chat drives or runs a task of, and the chat's provider (`GET …/specs/by-session/:id`). */
data class SpecBinding(val slug: String, val taskId: String?, val providerId: String?)

internal fun ApiClient.specsPath(project: String) = "/api/project/${ApiClient.escape(project)}/specs"

private fun ApiClient.specPath(project: String, slug: String) = "${specsPath(project)}/${ApiClient.escape(slug)}"

private fun view(json: JsonElement): SpecView = SpecView.from(json) ?: throw PpmError.BadResponse("Unexpected spec.")

private fun result(json: JsonElement): SpecActionResult =
    SpecActionResult(json["message"].string ?: "", SpecView.from(json["spec"]) ?: throw PpmError.BadResponse("Unexpected spec."))

/** The project's specs, newest first. */
suspend fun ApiClient.specs(project: String): List<SpecSummary> =
    call("GET", specsPath(project)).array.orEmpty().mapNotNull(SpecSummary::from)

suspend fun ApiClient.spec(project: String, slug: String): SpecView = view(call("GET", specPath(project, slug)))

/** The spec [sessionId] drives or runs a task of, by any id the chat has had; null when none. */
suspend fun ApiClient.specBySession(project: String, sessionId: String): SpecBinding? {
    val json = call("GET", "${specsPath(project)}/by-session/${ApiClient.escape(sessionId)}")
    val slug = json["slug"].string ?: return null
    return SpecBinding(slug, json["taskId"].string, json["providerId"].string)
}

/** A new spec, bound to [sessionId] when given: that chat's AI is told about it with its next message. */
suspend fun ApiClient.createSpec(
    project: String,
    title: String,
    kind: String,
    workflow: String,
    checks: String,
    sessionId: String? = null,
): SpecView = view(
    call(
        "POST",
        specsPath(project),
        body = buildJsonObject {
            put("title", title)
            put("kind", kind)
            put("workflow", workflow)
            put("checks", checks)
            if (sessionId != null) put("sessionId", sessionId)
        },
    ),
)

/** Binds a spec with no chat (one `ppm spec new` made) to [sessionId]. */
suspend fun ApiClient.attachSpecSession(project: String, slug: String, sessionId: String): SpecView =
    view(call("POST", "${specPath(project, slug)}/session", body = buildJsonObject { put("sessionId", sessionId) }))

/** Task checks: `standard` (the AI marks tasks done) or `thorough` (PPM runs the tests). */
suspend fun ApiClient.setSpecChecks(project: String, slug: String, checks: String): SpecView =
    view(call("PUT", "${specPath(project, slug)}/checks", body = buildJsonObject { put("checks", checks) }))

/** Approves a document (or the drawing); the spec's chat is told to go on. */
suspend fun ApiClient.approveSpec(project: String, slug: String, phase: String): SpecActionResult =
    result(call("POST", "${specPath(project, slug)}/approve/${ApiClient.escape(phase)}", body = buildJsonObject { put("continue", true) }))

/** Sends [note] about a document to the spec's chat, which is asked to change it and submit it again. */
suspend fun ApiClient.requestSpecChanges(project: String, slug: String, phase: String, note: String): SpecView =
    view(
        call(
            "POST",
            "${specPath(project, slug)}/request-changes/${ApiClient.escape(phase)}",
            body = buildJsonObject {
                put("note", note)
                put("continue", true)
            },
        ),
    )

/** Start (Kiro's button): the spec's own chat is asked to run the task. */
suspend fun ApiClient.runSpecTask(project: String, slug: String, taskId: String): SpecActionResult =
    result(call("POST", "${specPath(project, slug)}/tasks/${ApiClient.escape(taskId)}/run", body = buildJsonObject {}))

/** Puts a task back to not started, unlocking its tests. */
suspend fun ApiClient.resetSpecTask(project: String, slug: String, taskId: String): SpecView =
    view(call("POST", "${specPath(project, slug)}/tasks/${ApiClient.escape(taskId)}/reset", body = buildJsonObject {}))

/** Runs the whole suite again after new failures; it answers whether it is clean now. */
suspend fun ApiClient.checkSpecSuite(project: String, slug: String): SpecActionResult =
    result(call("POST", "${specPath(project, slug)}/suite/check", body = buildJsonObject {}))

/**
 * Run all: the spec's own chat is handed every open task and gives each one a subagent; a run in
 * new chats per task happens only when the user asks the chat for it (`spec_run_sessions`).
 */
suspend fun ApiClient.runAllSpec(project: String, slug: String): SpecActionResult =
    result(call("POST", "${specPath(project, slug)}/run", body = buildJsonObject {}))

/** Stops Run all; tasks already running finish in their own chats. */
suspend fun ApiClient.stopSpec(project: String, slug: String): SpecActionResult =
    result(call("POST", "${specPath(project, slug)}/stop", body = buildJsonObject {}))

/** The History list's flat/tree choice (`chatHistoryViewMode` in `/api/settings/ui-prefs`); null when never set. */
suspend fun ApiClient.chatHistoryViewMode(): String? =
    call("GET", "/api/settings/ui-prefs")["chatHistoryViewMode"].string?.takeIf { it == "flat" || it == "tree" }

/** Saves the History list's flat/tree choice for every device. */
suspend fun ApiClient.putChatHistoryViewMode(mode: String) {
    putUiPrefs(buildJsonObject { put("chatHistoryViewMode", mode) })
}
