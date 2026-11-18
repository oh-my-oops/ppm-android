package com.hienlh.ppm.app.ui.tasks

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

const val TASK_A = "11111111-1111-4111-8111-111111111111"
const val TASK_B = "22222222-2222-4222-8222-222222222222"
const val TASK_C = "33333333-3333-4333-8333-333333333333"

/** One task on the wire (`TaskView`), every field at a plain default. */
fun taskJson(
    id: String,
    number: Int,
    title: String,
    urgency: String = "today",
    baseGroup: String = "not_started",
    statusId: String = "todo",
    statusName: String = "To do",
    workingSessionId: String? = null,
    working: String = """{"kind":"start_chat"}""",
    links: String = "[]",
    type: String = "task",
    decision: String = "null",
    revision: Int = 1,
    parentId: String? = null,
    nextStep: String? = null,
    waitingOn: String? = null,
): String = """
    {"id":"$id","projectKey":"k1","number":$number,"title":"$title","type":"$type",
     "statusId":"$statusId","urgency":"$urgency","importance":"should","rank":$number,"why":null,"nextStep":${nextStep?.let { "\"$it\"" } ?: "null"},"notes":null,
     "dueDate":null,"tags":[],"assigneeMemberId":null,"waitingOnMemberId":${waitingOn?.let { "\"$it\"" } ?: "null"},"waitingSince":null,
     "parentId":${parentId?.let { "\"$it\"" } ?: "null"},"workingSessionId":${workingSessionId?.let { "\"$it\"" } ?: "null"},
     "decision":$decision,"fields":{},"links":$links,
     "result":null,"handedOffAt":null,"snoozedUntil":null,"snoozeUntilChange":false,"snoozedAt":null,"resurfacedAt":null,
     "revision":$revision,"createdAt":1,"updatedAt":2,"closedAt":null,"deletedAt":null,"baseGroup":"$baseGroup","closedAs":null,
     "effectiveUrgency":"$urgency","urgencyRaised":false,"snoozed":false,"resurfacedToday":false,
     "project":"ppm","ref":"ppm/$number","statusName":"$statusName",
     "workingSession":$working,"check":null}
""".trimIndent()

/**
 * The task routes of one project, `ppm`, with tasks in it: lists, details, history, settings,
 * the manager list and the writes, each recorded as "METHOD /path" with its body.
 */
class TaskServer(
    var tasks: MutableMap<String, String> = linkedMapOf(
        TASK_A to taskJson(TASK_A, 12, "Fix login", urgency = "now", baseGroup = "active", statusId = "doing", statusName = "In progress", workingSessionId = "chat-1",
            working = """{"kind":"session","session":{"sessionId":"chat-1","state":"running","title":"Login chat","runningSince":9}}""",
            links = """[{"kind":"jira","ref":"NX-1","label":null}]""", nextStep = "Ship it"),
        TASK_B to taskJson(TASK_B, 13, "Write docs", urgency = "week", workingSessionId = "chat-1"),
        TASK_C to taskJson(TASK_C, 14, "Pick a vendor", urgency = "today", type = "decision",
            decision = """{"question":"Which vendor?","options":[{"id":"a","label":"Acme"},{"id":"b","label":"Globex"}],"chosen":null}"""),
    ),
    val enabled: Boolean = true,
    val managers: String = """{"managers":[{"sessionId":"mgr-1","projectName":"ppm","providerId":"claude"}]}""",
    val history: String = """[{"id":5,"taskId":"$TASK_A","at":1700000000000,"actor":{"kind":"ai","label":"Claude","sessionId":"chat-1"},"via":"tool","kind":"field","field":"urgency","from":"week","to":"now","reason":"Release is today","unattended":false,"undoable":true,"undoneBy":null}]""",
) : Dispatcher() {
    val seen = CopyOnWriteArrayList<Pair<String, String>>()

    private val projects get() = """[{"name":"ppm","projectKey":"k1","enabled":$enabled,"hasManager":true,"open":${tasks.size},"now":1,"snoozed":0}]"""

    private val settings get() = """{"project":{"name":"ppm","projectKey":"k1","enabled":$enabled,"hasManager":true,"open":${tasks.size},"now":1,"snoozed":0,"enabledBy":"user","checkIntervalMin":30},
        "statuses":[{"id":"todo","name":"To do","baseGroup":"not_started","closedAs":null,"position":0},
                    {"id":"doing","name":"In progress","baseGroup":"active","closedAs":null,"position":1},
                    {"id":"done","name":"Done","baseGroup":"closed","closedAs":"done","position":2}],
        "members":[{"id":"m1","name":"Alice","position":0}],"fieldDefs":[]}"""

    private fun ok(data: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"ok":true,"data":$data}""").build()

    private fun written(id: String) = ok("""{"task":${tasks[id]},"managerPrompt":false}""")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val method = request.method
        seen += "$method $path" to request.body?.utf8().orEmpty()
        val id = path.removePrefix("/api/tasks/").substringBefore('/')
        return when {
            path == "/api/assistant/managers" -> ok(managers)
            path == "/api/schedules" -> ok("""{"schedules":[]}""")
            path == "/api/tasks" && method == "GET" -> {
                val session = request.url.queryParameter("session")
                val rows = tasks.values.filter { session == null || it.contains("\"workingSessionId\":\"$session\"") }
                ok("""{"tasks":[${rows.joinToString(",")}],"projects":$projects}""")
            }
            path == "/api/tasks/projects" -> ok(projects)
            path == "/api/tasks/projects/ppm/settings" -> ok(settings)
            path == "/api/tasks/link-states" -> ok("""{"tasks":{}}""")
            path.endsWith("/history") -> ok(history)
            path.startsWith("/api/tasks/history/") -> written(TASK_A)
            method == "GET" && id in tasks -> ok("""{"task":${tasks[id]},"children":[],"linkStates":[],"jiraSite":null}""")
            method == "POST" && path.endsWith("/close") && id in tasks -> {
                tasks[id] = tasks.getValue(id).replace("\"baseGroup\":\"not_started\"", "\"baseGroup\":\"closed\"")
                    .replace("\"baseGroup\":\"active\"", "\"baseGroup\":\"closed\"").replace("\"revision\":1", "\"revision\":2")
                written(id)
            }
            id in tasks -> written(id)
            else -> ok("null")
        }
    }

    fun request(line: String): String? = seen.firstOrNull { it.first == line }?.second
}
