package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ChangedFile
import com.hienlh.ppm.client.GitChanges
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ══════════════════════════════════════════════════════════════════════════════
// 8c.1 Project Scope (`packages/ext-git-graph/src/project-scope.ts`)
// ══════════════════════════════════════════════════════════════════════════════

data class GitGraphProjectRef(
    val name: String,
    val path: String,
)

private fun normalizeGitDir(path: String): String =
    path.replace('\\', '/').trimEnd('/')

/**
 * The registered project that owns [dirPath], or null when none does.
 * Longest match wins (`pickProject` in `project-scope.ts`).
 */
fun pickGitGraphProject(
    projects: List<GitGraphProjectRef>,
    dirPath: String,
): GitGraphProjectRef? {
    val target = normalizeGitDir(dirPath)
    var best: GitGraphProjectRef? = null
    var bestLength = -1
    for (project in projects) {
        val root = normalizeGitDir(project.path)
        if (target != root && !target.startsWith("$root/")) continue
        if (root.length > bestLength) {
            best = project
            bestLength = root.length
        }
    }
    return best
}

/**
 * Convert a repository-relative file path to a project-relative file path
 * (`toProjectRelative` in `project-scope.ts`).
 */
fun toGitGraphProjectRelative(
    projectPath: String,
    gitRoot: String,
    filePath: String,
): String {
    val root = normalizeGitDir(projectPath)
    val repo = normalizeGitDir(gitRoot)
    val file = filePath.replace('\\', '/').removePrefix("./").removePrefix("/")
    if (repo == root) return file
    if (!repo.startsWith("$root/")) return file
    val prefix = repo.substring(root.length + 1)
    return if (file.isNotEmpty()) "$prefix/$file" else prefix
}

// ══════════════════════════════════════════════════════════════════════════════
// 8c.1 Protocol Data Types (`packages/ext-git-graph/src/types.ts`)
// ══════════════════════════════════════════════════════════════════════════════

data class GitGraphRefData(
    val name: String,
    /** `"head"` | `"local"` | `"remote"` | `"tag"` | `"stash"` */
    val type: String,
)

data class GitGraphVertex(
    val hash: String,
    val parents: List<String>,
    val author: String,
    val authorEmail: String,
    val authorDate: Long,
    val committer: String,
    val committerEmail: String,
    val commitDate: Long,
    val refs: List<GitGraphRefData>,
    val message: String,
    val isWip: Boolean = false,
    val isStash: Boolean = false,
    val stashIndex: Int? = null,
)

data class GitGraphBranch(
    val name: String,
    val remote: String? = null,
    val current: Boolean = false,
    val hash: String = "",
)

data class GitGraphTag(
    val name: String,
    val hash: String,
)

data class GitGraphRemote(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String,
)

data class GitGraphStash(
    val index: Int,
    val hash: String,
    val parentHash: String,
    val message: String,
    val author: String = "",
    val authorEmail: String = "",
    val date: Long = 0L,
)

data class GitGraphWorktree(
    val path: String,
    val branch: String,
    val head: String,
    val isMain: Boolean,
    val isDetached: Boolean,
    val locked: Boolean,
    val lockReason: String? = null,
    val prunable: Boolean = false,
)

data class GitGraphSubmodule(
    val path: String,
    val hash: String,
    /** `"current"` | `"uninitialized"` | `"modified"` | `"conflicted"` */
    val state: String,
    val describe: String = "",
)

data class GitGraphFileChange(
    val path: String,
    val oldPath: String? = null,
    /** `"A"` | `"M"` | `"D"` | `"R"` | `"C"` | `"U"` */
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
)

data class GitGraphCommitDetail(
    val hash: String,
    val author: String,
    val authorEmail: String,
    val authorDate: Long,
    val committer: String,
    val committerEmail: String,
    val commitDate: Long,
    val message: String,
    val parents: List<String>,
    val fileChanges: List<GitGraphFileChange>?,
    val filesOmitted: Int = 0,
    val isPartial: Boolean = false,
)

data class GitGraphCommitStat(
    val files: Int,
    val insertions: Int,
    val deletions: Int,
)

data class GitGraphRemoteWeb(
    val base: String,
    val commitPath: String,
    val label: String,
)

data class GitGraphRepoInfo(
    val path: String = "",
    val branches: List<GitGraphBranch> = emptyList(),
    val tags: List<GitGraphTag> = emptyList(),
    val remotes: List<GitGraphRemote> = emptyList(),
    val stashes: List<GitGraphStash> = emptyList(),
    val head: String = "",
    val currentBranch: String = "",
    val remoteWeb: GitGraphRemoteWeb? = null,
)

data class GitGraphIssueRule(
    val pattern: String,
    val url: String = "",
)

data class GitGraphPrCreationConfig(
    /** `"github"` | `"gitlab"` | `"bitbucket"` | `"custom"` */
    val provider: String,
    val urlTemplate: String,
    val owner: String = "",
    val repo: String = "",
    val defaultTargetBranch: String = "main",
)

data class GitGraphSettings(
    val maxCommits: Int = 300,
    val showTags: Boolean = true,
    val showStashes: Boolean = true,
    val showRemoteBranches: Boolean = true,
    /** `"rounded"` | `"angular"` */
    val graphStyle: String = "rounded",
    val firstParentOnly: Boolean = false,
    /** `"relative"` | `"absolute"` | `"iso"` */
    val dateFormat: String = "relative",
    /** `"topo"` | `"date"` | `"author-date"` */
    val commitOrdering: String = "topo",
    val issueLinkingRules: List<GitGraphIssueRule> = listOf(GitGraphIssueRule(pattern = "#(\\d+)", url = "")),
    val prCreation: GitGraphPrCreationConfig? = null,
    val autoFetchInterval: Int = 0,
    val colChanges: Boolean = true,
    val colAuthor: Boolean = true,
    val colDate: Boolean = true,
    val colHash: Boolean = true,
)

data class GitGraphSearchHit(
    val hash: String,
    val subject: String,
    val author: String,
    val authorEmail: String,
    val authorDate: Long,
)

data class GitGraphActionResult(
    val ok: Boolean,
    val error: String? = null,
    val data: JsonObject? = null,
)

data class GitGraphCommitDraft(
    val message: String = "",
    val updatedAt: String? = null,
)

data class GitGraphUserDetails(
    val name: String = "",
    val email: String = "",
)

data class GitGraphOwnerRepo(
    val owner: String = "",
    val repo: String = "",
)

// ── Extension -> Webview Messages (`ExtToWebview`) ──

sealed interface GitGraphExtMessage {
    data class LoadRepoInfo(val data: GitGraphRepoInfo) : GitGraphExtMessage
    data class LoadCommits(
        val data: List<GitGraphVertex>,
        val append: Boolean,
        val skip: Int,
        val scope: String,
    ) : GitGraphExtMessage
    data class LoadCommitStats(val data: Map<String, GitGraphCommitStat>) : GitGraphExtMessage
    data class CommitDetails(val data: GitGraphCommitDetail) : GitGraphExtMessage
    data class LoadSearchResults(
        val mode: String,
        val text: String,
        val hits: List<GitGraphSearchHit>,
    ) : GitGraphExtMessage
    data class LoadSettings(val data: GitGraphSettings) : GitGraphExtMessage
    data class LoadUserDetails(val data: GitGraphUserDetails) : GitGraphExtMessage
    data class LoadOwnerRepo(val data: GitGraphOwnerRepo) : GitGraphExtMessage
    data class ActionResult(
        val action: String,
        val args: JsonObject?,
        val result: GitGraphActionResult,
        val reqId: Int?,
    ) : GitGraphExtMessage
    data class LoadWorktrees(val data: List<GitGraphWorktree>) : GitGraphExtMessage
    data class LoadStashes(val data: List<GitGraphStash>) : GitGraphExtMessage
    data class LoadSubmodules(val data: List<GitGraphSubmodule>) : GitGraphExtMessage
    data class LoadChanges(val data: GitChanges?, val error: String?) : GitGraphExtMessage
    data class LoadDraft(val data: GitGraphCommitDraft) : GitGraphExtMessage
    data class ErrorMessage(
        val message: String,
        val failed: String?,
        val reqId: Int?,
    ) : GitGraphExtMessage
}

fun parseGitGraphExtMessage(element: JsonElement?): GitGraphExtMessage? {
    val root = element?.obj ?: return null
    val command = root["command"].string ?: return null
    return when (command) {
        "loadRepoInfo" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            val branches = d["branches"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val name = o["name"].string ?: return@mapNotNull null
                GitGraphBranch(
                    name = name,
                    remote = o["remote"].string,
                    current = o["current"].bool ?: false,
                    hash = o["hash"].string.orEmpty(),
                )
            }
            val tags = d["tags"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val name = o["name"].string ?: return@mapNotNull null
                GitGraphTag(name = name, hash = o["hash"].string.orEmpty())
            }
            val remotes = d["remotes"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val name = o["name"].string ?: return@mapNotNull null
                GitGraphRemote(
                    name = name,
                    fetchUrl = o["fetchUrl"].string.orEmpty(),
                    pushUrl = o["pushUrl"].string.orEmpty(),
                )
            }
            val stashes = parseGitGraphStashes(d["stashes"])
            val rwObj = d["remoteWeb"].obj
            val remoteWeb = if (rwObj != null) {
                val base = rwObj["base"].string
                val cp = rwObj["commitPath"].string
                val lbl = rwObj["label"].string
                if (!base.isNullOrEmpty() && !cp.isNullOrEmpty() && !lbl.isNullOrEmpty()) {
                    GitGraphRemoteWeb(base = base, commitPath = cp, label = lbl)
                } else null
            } else null
            GitGraphExtMessage.LoadRepoInfo(
                GitGraphRepoInfo(
                    path = d["path"].string.orEmpty(),
                    branches = branches,
                    tags = tags,
                    remotes = remotes,
                    stashes = stashes,
                    head = d["head"].string.orEmpty(),
                    currentBranch = d["currentBranch"].string.orEmpty(),
                    remoteWeb = remoteWeb,
                ),
            )
        }
        "loadCommits" -> {
            val commits = root["data"].array.orEmpty().mapNotNull(::parseGitGraphVertex)
            GitGraphExtMessage.LoadCommits(
                data = commits,
                append = root["append"].bool ?: false,
                skip = root["skip"].int ?: 0,
                scope = root["scope"].string ?: "all",
            )
        }
        "loadCommitStats" -> {
            val map = LinkedHashMap<String, GitGraphCommitStat>()
            root["data"].obj?.forEach { (hash, v) ->
                val o = v.obj ?: return@forEach
                map[hash] = GitGraphCommitStat(
                    files = o["files"].int ?: 0,
                    insertions = o["insertions"].int ?: 0,
                    deletions = o["deletions"].int ?: 0,
                )
            }
            GitGraphExtMessage.LoadCommitStats(map)
        }
        "commitDetails" -> {
            val d = root["data"].obj ?: return null
            val hash = d["hash"].string ?: return null
            val files = d["fileChanges"].array?.mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val path = o["path"].string ?: return@mapNotNull null
                GitGraphFileChange(
                    path = path,
                    oldPath = o["oldPath"].string,
                    status = o["status"].string ?: "M",
                    additions = o["additions"].int ?: 0,
                    deletions = o["deletions"].int ?: 0,
                )
            }
            GitGraphExtMessage.CommitDetails(
                GitGraphCommitDetail(
                    hash = hash,
                    author = d["author"].string.orEmpty(),
                    authorEmail = d["authorEmail"].string.orEmpty(),
                    authorDate = d["authorDate"].long ?: 0L,
                    committer = d["committer"].string.orEmpty(),
                    committerEmail = d["committerEmail"].string.orEmpty(),
                    commitDate = d["commitDate"].long ?: 0L,
                    message = d["message"].string.orEmpty(),
                    parents = d["parents"].array.orEmpty().mapNotNull { it.string },
                    fileChanges = files,
                    filesOmitted = d["filesOmitted"].int ?: 0,
                    isPartial = false,
                ),
            )
        }
        "loadSearchResults" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            val hits = d["hits"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val hash = o["hash"].string ?: return@mapNotNull null
                GitGraphSearchHit(
                    hash = hash,
                    subject = o["subject"].string.orEmpty(),
                    author = o["author"].string.orEmpty(),
                    authorEmail = o["authorEmail"].string.orEmpty(),
                    authorDate = o["authorDate"].long ?: 0L,
                )
            }
            GitGraphExtMessage.LoadSearchResults(
                mode = d["mode"].string ?: "message",
                text = d["text"].string.orEmpty(),
                hits = hits,
            )
        }
        "loadSettings" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            val rules = d["issueLinkingRules"].array?.mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val pat = o["pattern"].string ?: return@mapNotNull null
                GitGraphIssueRule(pattern = pat, url = o["url"].string.orEmpty())
            } ?: listOf(GitGraphIssueRule(pattern = "#(\\d+)", url = ""))
            val prObj = d["prCreation"].obj
            val pr = if (prObj != null) {
                val provider = prObj["provider"].string
                if (!provider.isNullOrEmpty()) {
                    GitGraphPrCreationConfig(
                        provider = provider,
                        urlTemplate = prObj["urlTemplate"].string.orEmpty(),
                        owner = prObj["owner"].string.orEmpty(),
                        repo = prObj["repo"].string.orEmpty(),
                        defaultTargetBranch = prObj["defaultTargetBranch"].string ?: "main",
                    )
                } else null
            } else null
            GitGraphExtMessage.LoadSettings(
                GitGraphSettings(
                    maxCommits = d["maxCommits"].int ?: 300,
                    showTags = d["showTags"].bool ?: true,
                    showStashes = d["showStashes"].bool ?: true,
                    showRemoteBranches = d["showRemoteBranches"].bool ?: true,
                    graphStyle = d["graphStyle"].string ?: "rounded",
                    firstParentOnly = d["firstParentOnly"].bool ?: false,
                    dateFormat = d["dateFormat"].string ?: "relative",
                    commitOrdering = d["commitOrdering"].string ?: "topo",
                    issueLinkingRules = rules,
                    prCreation = pr,
                    autoFetchInterval = d["autoFetchInterval"].int ?: 0,
                    colChanges = d["colChanges"].bool ?: true,
                    colAuthor = d["colAuthor"].bool ?: true,
                    colDate = d["colDate"].bool ?: true,
                    colHash = d["colHash"].bool ?: true,
                ),
            )
        }
        "loadUserDetails" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            GitGraphExtMessage.LoadUserDetails(
                GitGraphUserDetails(
                    name = d["name"].string.orEmpty(),
                    email = d["email"].string.orEmpty(),
                ),
            )
        }
        "loadOwnerRepo" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            GitGraphExtMessage.LoadOwnerRepo(
                GitGraphOwnerRepo(
                    owner = d["owner"].string.orEmpty(),
                    repo = d["repo"].string.orEmpty(),
                ),
            )
        }
        "actionResult" -> {
            val resObj = root["result"].obj
            val result = GitGraphActionResult(
                ok = resObj?.get("ok").bool ?: false,
                error = resObj?.get("error").string,
                data = resObj?.get("data") as? JsonObject,
            )
            GitGraphExtMessage.ActionResult(
                action = root["action"].string.orEmpty(),
                args = root["args"] as? JsonObject,
                result = result,
                reqId = root["reqId"].int,
            )
        }
        "loadWorktrees" -> {
            val wts = root["data"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val path = o["path"].string ?: return@mapNotNull null
                GitGraphWorktree(
                    path = path,
                    branch = o["branch"].string.orEmpty(),
                    head = o["head"].string.orEmpty(),
                    isMain = o["isMain"].bool ?: false,
                    isDetached = o["isDetached"].bool ?: false,
                    locked = o["locked"].bool ?: false,
                    lockReason = o["lockReason"].string,
                    prunable = o["prunable"].bool ?: false,
                )
            }
            GitGraphExtMessage.LoadWorktrees(wts)
        }
        "loadStashes" -> {
            GitGraphExtMessage.LoadStashes(parseGitGraphStashes(root["data"]))
        }
        "loadSubmodules" -> {
            val sms = root["data"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val path = o["path"].string ?: return@mapNotNull null
                GitGraphSubmodule(
                    path = path,
                    hash = o["hash"].string.orEmpty(),
                    state = o["state"].string ?: "current",
                    describe = o["describe"].string.orEmpty(),
                )
            }
            GitGraphExtMessage.LoadSubmodules(sms)
        }
        "loadChanges" -> {
            val dataEl = root["data"]
            val parsedChanges = if (dataEl != null && dataEl !is JsonNull) {
                runCatching { GitChanges.from(dataEl) }.getOrNull()
            } else null
            GitGraphExtMessage.LoadChanges(
                data = parsedChanges,
                error = root["error"].string,
            )
        }
        "loadDraft" -> {
            val d = root["data"].obj ?: JsonObject(emptyMap())
            GitGraphExtMessage.LoadDraft(
                GitGraphCommitDraft(
                    message = d["message"].string.orEmpty(),
                    updatedAt = d["updatedAt"].string,
                ),
            )
        }
        "error" -> {
            GitGraphExtMessage.ErrorMessage(
                message = root["message"].string.orEmpty(),
                failed = root["failed"].string,
                reqId = root["reqId"].int,
            )
        }
        else -> null
    }
}

private fun parseGitGraphStashes(element: JsonElement?): List<GitGraphStash> =
    element.array.orEmpty().mapNotNull { el ->
        val o = el.obj ?: return@mapNotNull null
        val hash = o["hash"].string ?: return@mapNotNull null
        GitGraphStash(
            index = o["index"].int ?: 0,
            hash = hash,
            parentHash = o["parentHash"].string.orEmpty(),
            message = o["message"].string.orEmpty(),
            author = o["author"].string.orEmpty(),
            authorEmail = o["authorEmail"].string.orEmpty(),
            date = o["date"].long ?: 0L,
        )
    }

private fun parseGitGraphVertex(element: JsonElement?): GitGraphVertex? {
    val o = element?.obj ?: return null
    val hash = o["hash"].string ?: return null
    val refs = o["refs"].array.orEmpty().mapNotNull { r ->
        val ro = r.obj ?: return@mapNotNull null
        val name = ro["name"].string ?: return@mapNotNull null
        GitGraphRefData(name = name, type = ro["type"].string ?: "local")
    }
    return GitGraphVertex(
        hash = hash,
        parents = o["parents"].array.orEmpty().mapNotNull { it.string },
        author = o["author"].string.orEmpty(),
        authorEmail = o["authorEmail"].string.orEmpty(),
        authorDate = o["authorDate"].long ?: 0L,
        committer = o["committer"].string.orEmpty(),
        committerEmail = o["committerEmail"].string.orEmpty(),
        commitDate = o["commitDate"].long ?: 0L,
        refs = refs,
        message = o["message"].string.orEmpty(),
    )
}

// ── Webview -> Extension Messages (`WebviewToExt`) + `reqId` Request Tracker ──

class GitGraphRequestTracker {
    private var nextReqId = 0
    private val pending = LinkedHashMap<String, LinkedHashMap<Int, ((GitGraphActionResult) -> Unit)?>>()

    /**
     * Register a request for [action] (`request(msg, action, cb)` in `webview-script-core.ts`)
     * and return the allocated `reqId`.
     */
    fun register(action: String, callback: ((GitGraphActionResult) -> Unit)?): Int {
        val reqId = ++nextReqId
        val map = pending.getOrPut(action) { LinkedHashMap() }
        map[reqId] = callback
        return reqId
    }

    /**
     * Take the callback waiting on (`action`, `reqId`):
     * - `Taken(callback)` if found (`callback` is null when asked silently, e.g. background auto-fetch)
     * - `Unknown` if nothing registered this `(action, reqId)`
     */
    sealed interface Outcome {
        data class Taken(val callback: ((GitGraphActionResult) -> Unit)?) : Outcome
        data object Unknown : Outcome
    }

    fun take(action: String, reqId: Int?): Outcome {
        if (reqId == null) return Outcome.Unknown
        val map = pending[action] ?: return Outcome.Unknown
        if (!map.containsKey(reqId)) return Outcome.Unknown
        val cb = map.remove(reqId)
        return Outcome.Taken(cb)
    }
}

object GitGraphWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun requestRepoInfo(): JsonObject = buildJsonObject { put("command", "requestRepoInfo") }

    fun requestCommits(branch: String? = null, maxCommits: Int? = null, skip: Int? = null): JsonObject =
        buildJsonObject {
            put("command", "requestCommits")
            if (!branch.isNullOrEmpty() && branch != "all") put("branch", branch)
            if (maxCommits != null) put("maxCommits", maxCommits)
            if (skip != null) put("skip", skip)
        }

    fun requestCommitDetails(hash: String): JsonObject = buildJsonObject {
        put("command", "requestCommitDetails")
        put("hash", hash)
    }

    fun requestStashDetails(hash: String): JsonObject = buildJsonObject {
        put("command", "requestStashDetails")
        put("hash", hash)
    }

    fun requestChanges(): JsonObject = buildJsonObject { put("command", "requestChanges") }

    fun requestWorktrees(): JsonObject = buildJsonObject { put("command", "requestWorktrees") }

    fun requestStashes(): JsonObject = buildJsonObject { put("command", "requestStashes") }

    fun requestSubmodules(): JsonObject = buildJsonObject { put("command", "requestSubmodules") }

    fun requestSettings(): JsonObject = buildJsonObject { put("command", "requestSettings") }

    fun requestUserDetails(): JsonObject = buildJsonObject { put("command", "requestUserDetails") }

    fun requestOwnerRepo(): JsonObject = buildJsonObject { put("command", "requestOwnerRepo") }

    fun updateSetting(key: String, value: JsonElement): JsonObject = buildJsonObject {
        put("command", "updateSetting")
        put("key", key)
        put("value", value)
    }

    fun updateUserDetails(name: String, email: String): JsonObject = buildJsonObject {
        put("command", "updateUserDetails")
        put("name", name)
        put("email", email)
    }

    fun addRemote(name: String, url: String): JsonObject = buildJsonObject {
        put("command", "addRemote")
        put("name", name)
        put("url", url)
    }

    fun removeRemote(name: String): JsonObject = buildJsonObject {
        put("command", "removeRemote")
        put("name", name)
    }

    fun editRemoteUrl(name: String, url: String): JsonObject = buildJsonObject {
        put("command", "editRemoteUrl")
        put("name", name)
        put("url", url)
    }

    fun searchCommits(mode: String, text: String): JsonObject = buildJsonObject {
        put("command", "searchCommits")
        put("mode", mode)
        put("text", text)
    }

    fun openDiff(filePath: String, hash: String, parentHash: String?): JsonObject = buildJsonObject {
        put("command", "openDiff")
        put("filePath", filePath)
        put("hash", hash)
        put("parentHash", if (parentHash.isNullOrEmpty()) JsonNull else JsonPrimitive(parentHash))
    }

    fun openFile(filePath: String): JsonObject = buildJsonObject {
        put("command", "openFile")
        put("filePath", filePath)
    }

    fun openConflictFile(filePath: String): JsonObject = buildJsonObject {
        put("command", "openConflictFile")
        put("filePath", filePath)
    }

    fun openReview(path: String? = null): JsonObject = buildJsonObject {
        put("command", "openReview")
        if (!path.isNullOrEmpty()) put("path", path)
    }

    fun openBlame(filePath: String, hash: String? = null): JsonObject = buildJsonObject {
        put("command", "openBlame")
        put("filePath", filePath)
        if (!hash.isNullOrEmpty()) put("hash", hash)
    }

    fun openFileHistory(filePath: String): JsonObject = buildJsonObject {
        put("command", "openFileHistory")
        put("filePath", filePath)
    }

    fun openCompare(ref1: String? = null, ref2: String? = null): JsonObject = buildJsonObject {
        put("command", "openCompare")
        if (!ref1.isNullOrEmpty()) put("ref1", ref1)
        if (!ref2.isNullOrEmpty()) put("ref2", ref2)
    }

    fun openReflog(): JsonObject = buildJsonObject { put("command", "openReflog") }

    fun openWorktree(path: String): JsonObject = buildJsonObject {
        put("command", "openWorktree")
        put("path", path)
    }

    fun openSubmodule(path: String): JsonObject = buildJsonObject {
        put("command", "openSubmodule")
        put("path", path)
    }

    fun saveDraft(message: String): JsonObject = buildJsonObject {
        put("command", "saveDraft")
        put("message", message)
    }

    fun gitAction(action: String, args: JsonObject, reqId: Int): JsonObject = buildJsonObject {
        put("command", "gitAction")
        put("action", action)
        put("args", args)
        put("reqId", reqId)
    }

    fun sync(action: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "sync")
        put("action", action)
        put("reqId", reqId)
    }

    fun stageFiles(paths: List<String>, reqId: Int): JsonObject = buildJsonObject {
        put("command", "stageFiles")
        put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
        put("reqId", reqId)
    }

    fun unstageFiles(paths: List<String>, reqId: Int): JsonObject = buildJsonObject {
        put("command", "unstageFiles")
        put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
        put("reqId", reqId)
    }

    fun discardFiles(paths: List<String>, reqId: Int): JsonObject = buildJsonObject {
        put("command", "discardFiles")
        put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
        put("reqId", reqId)
    }

    fun undoDiscard(id: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "undoDiscard")
        put("id", id)
        put("reqId", reqId)
    }

    fun commitStaged(
        message: String,
        amend: Boolean = false,
        signoff: Boolean = false,
        push: Boolean = false,
        reqId: Int,
    ): JsonObject = buildJsonObject {
        put("command", "commitStaged")
        put("message", message)
        put("amend", amend)
        put("signoff", signoff)
        put("push", push)
        put("reqId", reqId)
    }

    fun undoCommit(hash: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "undoCommit")
        put("hash", hash)
        put("reqId", reqId)
    }

    fun stash(message: String?, includeUntracked: Boolean, reqId: Int): JsonObject = buildJsonObject {
        put("command", "stash")
        if (!message.isNullOrEmpty()) put("message", message)
        put("includeUntracked", includeUntracked)
        put("reqId", reqId)
    }

    fun stashAction(action: String, index: Int, hash: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "stashAction")
        put("action", action)
        put("index", index)
        put("hash", hash)
        put("reqId", reqId)
    }

    fun operation(action: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "operation")
        put("action", action)
        put("reqId", reqId)
    }

    fun addWorktree(
        path: String,
        branch: String? = null,
        newBranch: String? = null,
        startPoint: String? = null,
        reqId: Int,
    ): JsonObject = buildJsonObject {
        put("command", "addWorktree")
        put("path", path)
        if (!branch.isNullOrEmpty()) put("branch", branch)
        if (!newBranch.isNullOrEmpty()) put("newBranch", newBranch)
        if (!startPoint.isNullOrEmpty()) put("startPoint", startPoint)
        put("reqId", reqId)
    }

    fun removeWorktree(path: String, force: Boolean, reqId: Int): JsonObject = buildJsonObject {
        put("command", "removeWorktree")
        put("path", path)
        put("force", force)
        put("reqId", reqId)
    }

    fun pruneWorktrees(reqId: Int): JsonObject = buildJsonObject {
        put("command", "pruneWorktrees")
        put("reqId", reqId)
    }

    fun updateSubmodule(path: String, reqId: Int): JsonObject = buildJsonObject {
        put("command", "updateSubmodule")
        put("path", path)
        put("reqId", reqId)
    }
}

// ── Paging & Scope State Update (`loadCommits` in `webview-script-main.ts`) ──

data class GitGraphCommitsPageState(
    val commits: List<GitGraphVertex> = emptyList(),
    val stats: Map<String, GitGraphCommitStat> = emptyMap(),
    val scope: String = "all",
    val hasMore: Boolean = false,
    val commitsLoaded: Boolean = false,
    val loading: Boolean = false,
)

/**
 * Applies a `loadCommits` message (`webview-script-main.ts` lines 106-128):
 * - When `append == true`, only appends if `msg.skip == current.commits.size` (drops stale page).
 * - When `append == false`, replaces `commits`, resets `stats`, and updates `scope` (handling gone-branch fallback to `"all"`).
 */
fun applyGitGraphCommitsPage(
    current: GitGraphCommitsPageState,
    msg: GitGraphExtMessage.LoadCommits,
    maxCommits: Int,
): GitGraphCommitsPageState {
    if (msg.append) {
        if (msg.skip != current.commits.size) {
            return current
        }
        return current.copy(
            commits = current.commits + msg.data,
            hasMore = msg.data.size >= maxCommits,
            commitsLoaded = true,
            loading = false,
        )
    }
    return current.copy(
        commits = msg.data,
        stats = emptyMap(),
        scope = msg.scope.ifEmpty { current.scope },
        hasMore = msg.data.size >= maxCommits,
        commitsLoaded = true,
        loading = false,
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 8c.2 Graph Lane Layout (`packages/ext-git-graph/src/webview-script-graph.ts`)
// Faithful 1:1 port of vscode-git-graph's GBranch / GVertex / graphDeterminePath
// ══════════════════════════════════════════════════════════════════════════════

private const val NULL_VERTEX_ID = -1
const val GRAPH_MIN_W = 64f
const val GRAPH_GRID_X = 16f
const val GRAPH_OFFSET_X = 12f

data class GitGraphPoint(
    val x: Int,
    val y: Int,
)

data class GitGraphSegment(
    val p1: GitGraphPoint,
    val p2: GitGraphPoint,
    val isCommitted: Boolean,
    val lockedFirst: Boolean,
)

data class GitGraphBranchLayout(
    val colour: Int,
    val isStash: Boolean,
    val end: Int,
    val segments: List<GitGraphSegment>,
)

enum class GitGraphNodeKind {
    COMMIT,
    MERGE,
    HEAD,
    WIP,
    STASH,
}

data class GitGraphNodeLayout(
    val id: Int,
    val hash: String,
    val x: Int,
    val colour: Int,
    val kind: GitGraphNodeKind,
    val isStash: Boolean,
    val isWip: Boolean,
)

data class GitGraphLayoutResult(
    val vertices: List<GitGraphNodeLayout>,
    val branches: List<GitGraphBranchLayout>,
    val headColour: Int,
    val maxLaneX: Int,
    val naturalWidthDp: Float,
    private val commitIndexByHash: Map<String, Int>,
) {
    /**
     * Maps raw branch colour to palette slot `0..5`:
     * - `5` is reserved for stash (`--ln-5` / subtext)
     * - `0` is always the checked-out HEAD branch (`laneVar` in `webview-script-graph.ts`)
     * - `1..4` cycle through the 4 non-status branch hues (`1 + ((k - 1) % 4)`)
     */
    fun resolvedLaneSlot(rawColour: Int, isStash: Boolean): Int {
        if (isStash) return 5
        var k = rawColour
        if (headColour > 0) {
            if (k == headColour) k = 0
            else if (k == 0) k = headColour
        }
        return if (k == 0) 0 else 1 + ((k - 1) % 4)
    }

    fun commitLaneSlot(commit: GitGraphVertex): Int? {
        if (commit.isStash) return 5
        val idx = commitIndexByHash[commit.hash] ?: return null
        val node = vertices.getOrNull(idx) ?: return null
        return resolvedLaneSlot(node.colour, isStash = false)
    }
}

private class InternalGBranch(
    val colour: Int,
    val isStash: Boolean,
) {
    private data class RawLine(
        val p1: GitGraphPoint,
        val p2: GitGraphPoint,
        val lockedFirst: Boolean,
    )

    var end: Int = 0
    private val lines = ArrayList<RawLine>()
    private var numUncommitted = 0

    fun addLine(p1: GitGraphPoint, p2: GitGraphPoint, isCommitted: Boolean, lockedFirst: Boolean) {
        lines.add(RawLine(p1, p2, lockedFirst))
        if (isCommitted) {
            if (p2.x == 0 && p2.y < numUncommitted) numUncommitted = p2.y
        } else {
            numUncommitted++
        }
    }

    fun toLayout(): GitGraphBranchLayout {
        val segs = ArrayList<GitGraphSegment>(lines.size)
        for (i in lines.indices) {
            val ln = lines[i]
            segs.add(
                GitGraphSegment(
                    p1 = ln.p1,
                    p2 = ln.p2,
                    isCommitted = i >= numUncommitted,
                    lockedFirst = ln.lockedFirst,
                ),
            )
        }
        // Simplify consecutive vertical segments (`webview-script-graph.ts` lines 70-78)
        var si = 0
        while (si < segs.size - 1) {
            val a = segs[si]
            val b = segs[si + 1]
            if (
                a.p1.x == a.p2.x &&
                a.p2.x == b.p1.x &&
                b.p1.x == b.p2.x &&
                a.p2.y == b.p1.y &&
                a.isCommitted == b.isCommitted
            ) {
                segs[si] = a.copy(p2 = GitGraphPoint(a.p2.x, b.p2.y))
                segs.removeAt(si + 1)
            } else {
                si++
            }
        }
        return GitGraphBranchLayout(
            colour = colour,
            isStash = isStash,
            end = end,
            segments = segs,
        )
    }
}

private class InternalGVertex(
    val id: Int,
    val isStash: Boolean,
    val isWip: Boolean,
) {
    private data class Connection(
        val connectsTo: InternalGVertex?,
        val onBranch: InternalGBranch,
    )

    var x: Int = 0
        private set
    private val children = ArrayList<InternalGVertex>()
    private val parents = ArrayList<InternalGVertex>()
    private var nextParent = 0
    var onBranch: InternalGBranch? = null
        private set
    var isCommitted: Boolean = true
        private set
    var isCurrent: Boolean = false
        private set
    private var nextX = 0
    private val connections = ArrayList<Connection?>()

    fun addChild(v: InternalGVertex) {
        children.add(v)
    }

    fun addParent(v: InternalGVertex) {
        parents.add(v)
    }

    fun getNextParent(): InternalGVertex? =
        if (nextParent < parents.size) parents[nextParent] else null

    fun registerParentProcessed() {
        nextParent++
    }

    fun isMerge(): Boolean = parents.size > 1

    fun addToBranch(branch: InternalGBranch, xCoord: Int) {
        if (onBranch == null) {
            onBranch = branch
            x = xCoord
        }
    }

    fun isNotOnBranch(): Boolean = onBranch == null

    fun getPoint(): GitGraphPoint = GitGraphPoint(x, id)

    fun getNextPoint(): GitGraphPoint = GitGraphPoint(nextX, id)

    fun getPointConnectingTo(vertex: InternalGVertex?, branch: InternalGBranch?): GitGraphPoint? {
        for (i in connections.indices) {
            val c = connections[i]
            if (c != null && c.connectsTo === vertex && c.onBranch === branch) {
                return GitGraphPoint(i, id)
            }
        }
        return null
    }

    fun registerUnavailablePoint(pointX: Int, connectsTo: InternalGVertex?, branch: InternalGBranch) {
        if (pointX == nextX) {
            nextX = pointX + 1
            while (connections.size <= pointX) connections.add(null)
            connections[pointX] = Connection(connectsTo, branch)
        }
    }

    fun getColour(): Int = onBranch?.colour ?: 0

    fun setNotCommitted() {
        isCommitted = false
    }

    fun setCurrent() {
        isCurrent = true
    }
}

/**
 * Compute the exact Git Graph lane layout (`graphLoadCommits` in `webview-script-graph.ts`).
 */
fun computeGitGraphLayout(
    commits: List<GitGraphVertex>,
    headHash: String?,
): GitGraphLayoutResult {
    if (commits.isEmpty()) {
        return GitGraphLayoutResult(
            vertices = emptyList(),
            branches = emptyList(),
            headColour = 0,
            maxLaneX = 0,
            naturalWidthDp = GRAPH_MIN_W,
            commitIndexByHash = emptyMap(),
        )
    }

    val gVertices = ArrayList<InternalGVertex>(commits.size)
    val gBranches = ArrayList<InternalGBranch>()
    val gAvailColours = ArrayList<Int>()
    val nullVertex = InternalGVertex(NULL_VERTEX_ID, isStash = false, isWip = false)
    val lookup = HashMap<String, Int>(commits.size * 2)

    for (i in commits.indices) {
        val c = commits[i]
        lookup[c.hash] = i
        gVertices.add(InternalGVertex(i, isStash = c.isStash, isWip = c.isWip))
    }

    for (i in commits.indices) {
        val parents = commits[i].parents
        for (ph in parents) {
            val parentIdx = lookup[ph]
            if (parentIdx != null) {
                gVertices[i].addParent(gVertices[parentIdx])
                gVertices[parentIdx].addChild(gVertices[i])
            } else {
                gVertices[i].addParent(nullVertex)
            }
        }
    }

    if (!headHash.isNullOrEmpty()) {
        lookup[headHash]?.let { idx -> gVertices[idx].setCurrent() }
    }
    if (commits[0].isWip) {
        gVertices[0].setNotCommitted()
    }

    fun getAvailableColour(startAt: Int): Int {
        for (cIdx in gAvailColours.indices) {
            if (startAt > gAvailColours[cIdx]) return cIdx
        }
        gAvailColours.add(0)
        return gAvailColours.size - 1
    }

    fun determinePath(startAt: Int) {
        var i = startAt
        var vertex = gVertices[i]
        var parentVertex = gVertices[i].getNextParent()
        var lastPoint = if (vertex.isNotOnBranch()) vertex.getNextPoint() else vertex.getPoint()

        if (
            parentVertex != null &&
            parentVertex.id != NULL_VERTEX_ID &&
            vertex.isMerge() &&
            !vertex.isNotOnBranch() &&
            !parentVertex.isNotOnBranch()
        ) {
            var foundPtp = false
            val pBranch = parentVertex.onBranch!!
            for (row in (startAt + 1) until gVertices.size) {
                i = row
                val curVertex = gVertices[row]
                var curPoint = curVertex.getPointConnectingTo(parentVertex, pBranch)
                if (curPoint != null) {
                    foundPtp = true
                } else {
                    curPoint = curVertex.getNextPoint()
                }
                pBranch.addLine(
                    p1 = lastPoint,
                    p2 = curPoint,
                    isCommitted = vertex.isCommitted,
                    lockedFirst = if (!foundPtp && curVertex !== parentVertex) lastPoint.x < curPoint.x else true,
                )
                curVertex.registerUnavailablePoint(curPoint.x, parentVertex, pBranch)
                lastPoint = curPoint
                if (foundPtp) {
                    vertex.registerParentProcessed()
                    break
                }
            }
        } else {
            val branch = InternalGBranch(getAvailableColour(startAt), vertex.isStash)
            vertex.addToBranch(branch, lastPoint.x)
            vertex.registerUnavailablePoint(lastPoint.x, vertex, branch)
            i = startAt + 1
            while (i < gVertices.size) {
                val curVertex = gVertices[i]
                val curPoint = if (parentVertex === curVertex && !parentVertex.isNotOnBranch()) {
                    curVertex.getPoint()
                } else {
                    curVertex.getNextPoint()
                }
                branch.addLine(
                    p1 = lastPoint,
                    p2 = curPoint,
                    isCommitted = vertex.isCommitted,
                    lockedFirst = lastPoint.x < curPoint.x,
                )
                curVertex.registerUnavailablePoint(curPoint.x, parentVertex, branch)
                lastPoint = curPoint
                if (parentVertex === curVertex) {
                    vertex.registerParentProcessed()
                    val parentAlreadyOnBranch = !parentVertex.isNotOnBranch()
                    parentVertex.addToBranch(branch, curPoint.x)
                    vertex = parentVertex
                    parentVertex = vertex.getNextParent()
                    if (parentVertex == null || parentAlreadyOnBranch) {
                        i++
                        break
                    }
                }
                i++
            }
            if (i == gVertices.size && parentVertex != null && parentVertex.id == NULL_VERTEX_ID) {
                vertex.registerParentProcessed()
            }
            branch.end = i
            gBranches.add(branch)
            gAvailColours[branch.colour] = i
        }
    }

    var idx = 0
    while (idx < gVertices.size) {
        val v = gVertices[idx]
        if (v.getNextParent() != null || v.isNotOnBranch()) {
            determinePath(idx)
        } else {
            idx++
        }
    }

    val headColour = if (!headHash.isNullOrEmpty() && lookup.containsKey(headHash)) {
        gVertices[lookup.getValue(headHash)].getColour()
    } else {
        0
    }

    var maxX = 0
    val nodeLayouts = ArrayList<GitGraphNodeLayout>(gVertices.size)
    for (i in gVertices.indices) {
        val v = gVertices[i]
        val nextP = v.getNextPoint()
        if (nextP.x > maxX) maxX = nextP.x
        val kind = when {
            v.isWip -> GitGraphNodeKind.WIP
            v.isStash -> GitGraphNodeKind.STASH
            v.isCurrent -> GitGraphNodeKind.HEAD
            v.isMerge() -> GitGraphNodeKind.MERGE
            else -> GitGraphNodeKind.COMMIT
        }
        nodeLayouts.add(
            GitGraphNodeLayout(
                id = v.id,
                hash = commits[i].hash,
                x = v.x,
                colour = v.getColour(),
                kind = kind,
                isStash = v.isStash,
                isWip = v.isWip,
            ),
        )
    }

    val naturalWidth = max(GRAPH_MIN_W, 28f + max(maxX - 1, 0) * GRAPH_GRID_X)

    return GitGraphLayoutResult(
        vertices = nodeLayouts,
        branches = gBranches.map { it.toLayout() },
        headColour = headColour,
        maxLaneX = maxX,
        naturalWidthDp = naturalWidth,
        commitIndexByHash = lookup,
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 8c.2 Display Commits, Ref Pills, Avatars, Search, Message Links & PR URLs
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Whether the uncommitted-changes (`WIP`) row should be shown above the commit list
 * (`showWipRow` in `webview-script-list.ts`).
 */
fun shouldShowGitGraphWipRow(
    commits: List<GitGraphVertex>,
    headHash: String,
    changedFiles: List<ChangedFile>,
): Boolean {
    if (changedFiles.isEmpty()) return false
    if (headHash.isEmpty()) return true
    return commits.any { it.hash == headHash }
}

/**
 * Interleave the WIP row and stash spurs into the loaded commits (`getDisplayCommits` in `webview-script-list.ts`).
 * Stashes must appear BEFORE their parent commit so the forward-scanning lane layout connects them.
 */
fun buildGitGraphDisplayCommits(
    commits: List<GitGraphVertex>,
    headHash: String,
    changedFiles: List<ChangedFile>,
    showStashes: Boolean,
    stashes: List<GitGraphStash>,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
): List<GitGraphVertex> {
    var list = if (shouldShowGitGraphWipRow(commits, headHash, changedFiles)) {
        listOf(
            GitGraphVertex(
                hash = "uncommitted",
                parents = if (headHash.isNotEmpty()) listOf(headHash) else emptyList(),
                author = "",
                authorEmail = "",
                authorDate = nowEpochSec,
                committer = "",
                committerEmail = "",
                commitDate = nowEpochSec,
                refs = emptyList(),
                message = "Uncommitted changes",
                isWip = true,
            ),
        ) + commits
    } else {
        commits
    }

    if (showStashes && stashes.isNotEmpty()) {
        val commitHashSet = list.mapTo(HashSet()) { it.hash }
        val stashesByParent = LinkedHashMap<String, MutableList<GitGraphStash>>()
        for (s in stashes) {
            if (s.parentHash.isEmpty() || s.parentHash !in commitHashSet) continue
            stashesByParent.getOrPut(s.parentHash) { ArrayList() }.add(s)
        }
        if (stashesByParent.isNotEmpty()) {
            val result = ArrayList<GitGraphVertex>(list.size + stashes.size)
            for (c in list) {
                val forCommit = stashesByParent[c.hash]
                if (forCommit != null) {
                    for (s in forCommit) {
                        result.add(
                            GitGraphVertex(
                                hash = s.hash,
                                parents = listOf(s.parentHash),
                                author = s.author,
                                authorEmail = s.authorEmail,
                                authorDate = s.date,
                                committer = "",
                                committerEmail = "",
                                commitDate = s.date,
                                refs = listOf(GitGraphRefData(name = "stash@{${s.index}}", type = "stash")),
                                message = s.message,
                                isStash = true,
                                stashIndex = s.index,
                            ),
                        )
                    }
                }
                result.add(c)
            }
            list = result
        }
    }
    return list
}

data class GitGraphClassifiedRef(
    val name: String,
    /** `"head"` | `"local"` | `"remote"` | `"tag"` | `"stash"` */
    val type: String,
    val synced: Boolean = false,
    val aheadCount: Int = 0,
)

/**
 * Classify commit refs using the repository's real remotes, merge synced remote refs into their
 * local branch pill (`synced = true`), filter by settings, and put `head` first
 * (`classifyRefs` in `webview-script-list.ts`).
 */
fun classifyGitGraphRefs(
    refs: List<GitGraphRefData>,
    remotes: List<GitGraphRemote>,
    showTags: Boolean,
    showRemoteBranches: Boolean,
    headAheadCount: Int = 0,
): List<GitGraphClassifiedRef> {
    if (refs.isEmpty()) return emptyList()
    val prefixes = remotes.map { "${it.name}/" }
    val classified = refs.map { ref ->
        if (ref.type == "head" || ref.type == "tag" || ref.type == "stash") {
            ref
        } else {
            val isRemote = prefixes.any { ref.name.startsWith(it) }
            GitGraphRefData(name = ref.name, type = if (isRemote) "remote" else "local")
        }
    }
    val localNames = classified
        .filter { it.type == "head" || it.type == "local" }
        .mapTo(HashSet()) { it.name }
    val mergedRemotes = HashSet<String>()
    for (ref in classified) {
        if (ref.type != "remote") continue
        val prefix = prefixes.firstOrNull { ref.name.startsWith(it) }
        val stripped = if (prefix != null) ref.name.substring(prefix.length) else ref.name
        if (stripped in localNames) {
            mergedRemotes.add(ref.name)
        }
    }
    val out = ArrayList<GitGraphClassifiedRef>(classified.size)
    for (ref in classified) {
        if (ref.type == "tag" && !showTags) continue
        if (ref.type == "remote" && !showRemoteBranches) continue
        if (ref.name in mergedRemotes) continue
        val synced = (ref.type == "head" || ref.type == "local") &&
            classified.any { r -> r.type == "remote" && prefixes.any { p -> r.name == p + ref.name } }
        out.add(
            GitGraphClassifiedRef(
                name = ref.name,
                type = ref.type,
                synced = synced,
                aheadCount = if (ref.type == "head") headAheadCount else 0,
            ),
        )
    }
    out.sortWith(compareByDescending { it.type == "head" })
    return out
}

/** Parse `"WIP on main: message"` / `"On main: message"` -> `(branch, message)` (`stashParts`). */
fun parseGitGraphStashMessage(raw: String): Pair<String?, String> {
    val match = Regex("""^(?:WIP )?[Oo]n ([^:]+): ([\s\S]*)$""").find(raw)
    return if (match != null) {
        match.groupValues[1] to match.groupValues[2]
    } else {
        null to raw
    }
}

/** Deterministic author initials (`authorInitials` in `webview-shell.ts`) — never calls Gravatar. */
fun gitGraphAuthorInitials(name: String): String {
    val parts = name.trim().ifEmpty { "?" }.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(2).uppercase(Locale.ROOT)
    return "${parts[0].first()}${parts.last().first()}".uppercase(Locale.ROOT)
}

/** Deterministic gradient index `0..11` keyed by `email` (or `name` if email is empty) (`avatarFor`). */
fun gitGraphAvatarGradientIndex(name: String, email: String, paletteSize: Int = 12): Int {
    val key = email.ifEmpty { name }
    var h = 0
    for (ch in key) {
        h = h * 31 + ch.code
    }
    return abs(h.toLong()).rem(max(1, paletteSize)).toInt()
}

// ── Dates (`formatDate`, `longAgo`, `absDate` in `webview-script-core.ts`) ──

private val ISO_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(ZoneId.of("UTC"))

private val ABS_SHORT_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneId.systemDefault())

fun formatGitGraphDate(
    epochSec: Long,
    dateFormat: String,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
): String {
    return when (dateFormat) {
        "iso" -> ISO_DATE_FMT.format(Instant.ofEpochSecond(epochSec))
        "absolute" -> ABS_SHORT_FMT.format(Instant.ofEpochSecond(epochSec))
        else -> {
            val diff = nowEpochSec - epochSec
            when {
                diff < 60 -> "now"
                diff < 3600 -> "${diff / 60}m"
                diff < 86400 -> "${diff / 3600}h"
                diff < 2592000 -> "${diff / 86400}d"
                diff < 31536000 -> "${diff / 2592000}mo"
                else -> "${diff / 31536000}y"
            }
        }
    }
}

fun formatGitGraphLongAgo(
    epochSec: Long,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
): String {
    val diff = nowEpochSec - epochSec
    if (diff < 60) return "just now"
    val units = listOf(
        31536000L to "year",
        2592000L to "month",
        604800L to "week",
        86400L to "day",
        3600L to "hour",
        60L to "minute",
    )
    for ((secs, word) in units) {
        if (diff >= secs) {
            val n = diff / secs
            return "$n ${if (n == 1L) word else "${word}s"} ago"
        }
    }
    return "just now"
}

// ── Commit Message Formatting (`packages/ext-git-graph/src/commit-message-html.ts`) ──

sealed interface GitGraphMessageToken {
    data class Plain(val text: String) : GitGraphMessageToken
    data class ExternalLink(val text: String, val url: String) : GitGraphMessageToken
    data class IssueHighlight(val text: String, val url: String?) : GitGraphMessageToken
    data class CommitHashLink(val hash: String) : GitGraphMessageToken
}

/**
 * Tokenize a commit message against raw text (`formatCommitMessage` in `commit-message-html.ts`):
 * Order of precedence: URLs -> issueLinkingRules -> bare 7..40 char hex hashes.
 * Capped at 500 link spans.
 */
fun tokenizeGitGraphCommitMessage(
    message: String,
    rules: List<GitGraphIssueRule>,
): List<GitGraphMessageToken> {
    if (message.isEmpty()) return emptyList()
    data class SpanClaim(val start: Int, val end: Int, val token: GitGraphMessageToken)
    val claims = ArrayList<SpanClaim>()

    fun addClaim(start: Int, end: Int, token: GitGraphMessageToken) {
        if (end <= start || claims.size >= 500) return
        for (existing in claims) {
            if (start < existing.end && end > existing.start) return
        }
        claims.add(SpanClaim(start, end, token))
    }

    // 1. URLs
    val urlRegex = Regex("""https?://[^\s<]+""")
    for (match in urlRegex.findAll(message)) {
        val url = match.value
        addClaim(match.range.first, match.range.last + 1, GitGraphMessageToken.ExternalLink(url, url))
    }

    // 2. Issue linking rules
    for (rule in rules) {
        if (rule.pattern.isEmpty() || rule.pattern.length > 200) continue
        val re = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
        for (match in re.findAll(message)) {
            val found = match.value
            val resolvedUrl = if (rule.url.isNotEmpty()) {
                var href = rule.url
                for (groupIdx in 1 until match.groupValues.size) {
                    href = href.replace("$$groupIdx", match.groupValues[groupIdx])
                }
                href
            } else null
            addClaim(
                match.range.first,
                match.range.last + 1,
                if (resolvedUrl != null) {
                    GitGraphMessageToken.ExternalLink(found, resolvedUrl)
                } else {
                    GitGraphMessageToken.IssueHighlight(found, null)
                },
            )
        }
    }

    // 3. Bare commit hashes
    val hashRegex = Regex("""\b[0-9a-f]{7,40}\b""")
    for (match in hashRegex.findAll(message)) {
        addClaim(match.range.first, match.range.last + 1, GitGraphMessageToken.CommitHashLink(match.value))
    }

    if (claims.isEmpty()) return listOf(GitGraphMessageToken.Plain(message))
    claims.sortBy { it.start }

    val tokens = ArrayList<GitGraphMessageToken>(claims.size * 2 + 1)
    var at = 0
    for (claim in claims) {
        if (claim.start > at) {
            tokens.add(GitGraphMessageToken.Plain(message.substring(at, claim.start)))
        }
        tokens.add(claim.token)
        at = claim.end
    }
    if (at < message.length) {
        tokens.add(GitGraphMessageToken.Plain(message.substring(at)))
    }
    return tokens
}

data class GitGraphCommitBodyBlock(
    /** `"prose"` or `"pre"` */
    val kind: String,
    val text: String,
)

/**
 * Split a commit body into `"prose"` (hard-wrapped paragraphs that can reflow) vs `"pre"`
 * (lists, code fences, indented blocks, short lines) (`splitCommitBody` in `commit-message-html.ts`).
 */
fun splitGitGraphCommitBody(body: String): List<GitGraphCommitBodyBlock> {
    val source = body.replace(Regex("""\r\n?"""), "\n")
    val blocks = ArrayList<GitGraphCommitBodyBlock>()
    val minWrapWidth = 40
    val bulletOrHeadingRe = Regex("""^([-*+>|#]|\d+[.)])""")

    for (chunk in source.split(Regex("""\n[ \t]*\n+"""))) {
        if (chunk.isBlank()) continue
        val lines = chunk.trimEnd().split("\n")
        val widest = lines.maxOfOrNull { it.length } ?: 0
        val flowing = widest >= minWrapWidth && lines.withIndex().all { (i, line) ->
            if (line.isEmpty() || line.first().isWhitespace()) return@all false
            if (bulletOrHeadingRe.containsMatchIn(line)) return@all false
            if (line.startsWith("```")) return@all false
            i == lines.lastIndex || line.length * 3 >= widest * 2
        }
        blocks.add(
            if (flowing) {
                GitGraphCommitBodyBlock(kind = "prose", text = lines.joinToString(" ") { it.trim() })
            } else {
                GitGraphCommitBodyBlock(kind = "pre", text = lines.joinToString("\n"))
            },
        )
    }
    return blocks
}

// ── Search & Find (`FIND_MODES` & `doSearch` in `webview-script-list.ts`) ──

data class GitGraphFindModeInfo(
    val id: String,
    val label: String,
    val chip: String,
    val placeholder: String,
)

val GIT_GRAPH_FIND_MODES: List<GitGraphFindModeInfo> = listOf(
    GitGraphFindModeInfo("loaded", "Loaded commits", "Loaded", "Find commits"),
    GitGraphFindModeInfo("message", "Message", "Message", "Search all history by message"),
    GitGraphFindModeInfo("author", "Author", "Author", "Search all history by author"),
    GitGraphFindModeInfo("content", "Code change", "Code", "Search code added or removed"),
    GitGraphFindModeInfo("file", "File path", "Path", "Commits touching a path"),
)

fun searchLoadedGitGraphCommits(
    displayCommits: List<GitGraphVertex>,
    query: String,
): List<String> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return emptyList()
    return displayCommits.mapNotNull { commit ->
        if (commit.isWip) return@mapNotNull null
        val matches = commit.message.lowercase(Locale.ROOT).contains(q) ||
            commit.author.lowercase(Locale.ROOT).contains(q) ||
            commit.hash.lowercase(Locale.ROOT).startsWith(q)
        if (matches) commit.hash else null
    }
}

// ── Pull Request URL Builder (`prTemplate` & `openPrUrl` in `webview-script-settings.ts`) ──

fun gitGraphPrTemplate(provider: String): String = when (provider) {
    "github" -> "https://github.com/\${owner}/\${repo}/compare/\${targetBranch}...\${sourceBranch}?expand=1"
    "gitlab" -> "https://gitlab.com/\${owner}/\${repo}/-/merge_requests/new?source_branch=\${sourceBranch}&target_branch=\${targetBranch}"
    "bitbucket" -> "https://bitbucket.org/\${owner}/\${repo}/pull-requests/new?source=\${sourceBranch}&dest=\${targetBranch}"
    else -> ""
}

fun buildGitGraphPrUrl(config: GitGraphPrCreationConfig?, sourceBranch: String): String? {
    if (config == null || config.urlTemplate.isBlank()) return null
    val encode = { s: String -> java.net.URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20") }
    return config.urlTemplate
        .replace("\${owner}", encode(config.owner))
        .replace("\${repo}", encode(config.repo))
        .replace("\${sourceBranch}", encode(sourceBranch))
        .replace("\${targetBranch}", encode(config.defaultTargetBranch.ifBlank { "main" }))
}
