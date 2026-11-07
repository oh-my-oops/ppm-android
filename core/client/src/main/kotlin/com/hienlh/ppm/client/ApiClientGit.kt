package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One repository under a project. [relative] is `.` when it is the project folder itself. */
data class GitRepo(val path: String, val name: String, val relative: String)

/** The repositories under a project (`GET /git/repos`): a project folder is often a container of checkouts. */
data class GitRepos(val rootIsRepo: Boolean, val repos: List<GitRepo>, val root: String = "") {
    /**
     * The repository to run git in without asking (`resolveRepo`): the project itself, or the only
     * one under it. Null when there is none, or several to choose from.
     */
    val resolved: GitRepo? get() = if (rootIsRepo) repos.firstOrNull() else repos.singleOrNull()
}

/** One file entry from `GET /git/status`. */
data class GitFileChange(
    val path: String,
    val status: String,
    val oldPath: String? = null,
)

/**
 * What `GET /git/status` returns: the branch and how far it is from its upstream, plus the
 * staged, unstaged, and untracked paths used by the sidebar badge and Explorer decorations.
 */
data class GitBranchStatus(
    val branch: String?,
    val ahead: Int,
    val behind: Int,
    val tracking: String?,
    val staged: List<GitFileChange> = emptyList(),
    val unstaged: List<GitFileChange> = emptyList(),
    val untracked: List<String> = emptyList(),
)

/** One side of a changed file (`staged` or `unstaged`) in `GET /git/changes`. */
data class ChangeSide(
    val status: String,
    val added: Int,
    val removed: Int,
    val blocks: List<String>,
    val whole: String? = null,
) {
    companion object {
        fun from(json: JsonElement?, fallbackStatus: String = "M"): ChangeSide? {
            val obj = json.obj ?: return null
            val status = obj["status"].string ?: fallbackStatus
            return ChangeSide(
                status = status,
                added = obj["added"].int ?: 0,
                removed = obj["removed"].int ?: 0,
                blocks = obj["blocks"].array.orEmpty().mapNotNull { it.string ?: it.obj?.get("id")?.string },
                whole = obj["whole"].string,
            )
        }
    }
}

/** One changed file in `GET /git/changes`. */
data class ChangedFile(
    val path: String,
    val oldPath: String? = null,
    val staged: ChangeSide? = null,
    val unstaged: ChangeSide? = null,
    val conflict: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): ChangedFile? {
            val obj = json.obj ?: return null
            val path = obj["path"].string ?: return null
            val x = obj["x"].string ?: "."
            val y = obj["y"].string ?: "."
            val untracked = obj["untracked"].bool ?: false
            val stagedFallback = when (x) {
                ".", "?" -> "A"
                else -> x
            }
            val unstagedFallback = when {
                untracked || y == "?" -> "A"
                y == "." -> "M"
                else -> y
            }
            val conflictEl = obj["conflict"]
            val conflict = when {
                conflictEl == null -> null
                conflictEl.bool == true -> "U"
                conflictEl.bool == false -> null
                else -> conflictEl.string
            }
            return ChangedFile(
                path = path,
                oldPath = obj["oldPath"].string,
                staged = ChangeSide.from(obj["staged"], stagedFallback),
                unstaged = ChangeSide.from(obj["unstaged"], unstagedFallback),
                conflict = conflict,
            )
        }
    }
}

/** Branch metadata in `GET /git/changes`. */
data class GitBranchState(
    val head: String?,
    val oid: String?,
    val upstream: String?,
    val upstreamGone: Boolean,
    val ahead: Int,
    val behind: Int,
    val hasRemote: Boolean = true,
) {
    companion object {
        fun from(json: JsonElement?): GitBranchState = GitBranchState(
            head = json["head"].string,
            oid = json["oid"].string,
            upstream = json["upstream"].string,
            upstreamGone = json["upstreamGone"].bool ?: false,
            ahead = json["ahead"].int ?: 0,
            behind = json["behind"].int ?: 0,
            hasRemote = json["hasRemote"].bool ?: true,
        )
    }
}

/** In-progress git operation (`merge`, `rebase`, `cherry-pick`, `revert`, `am`). */
data class GitOperation(
    val kind: String,
    val head: String? = null,
    val step: Int? = null,
    val total: Int? = null,
) {
    companion object {
        fun from(json: JsonElement?): GitOperation? {
            val obj = json.obj ?: return null
            val kind = obj["kind"].string ?: return null
            return GitOperation(
                kind = kind,
                head = obj["head"].string,
                step = obj["step"].int,
                total = obj["total"].int,
            )
        }
    }
}

/** The most recent commit in `GET /git/changes`. */
data class LastCommit(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val date: String,
    val hasParent: Boolean,
    val pushed: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): LastCommit? {
            val obj = json.obj ?: return null
            val hash = obj["hash"].string ?: return null
            return LastCommit(
                hash = hash,
                shortHash = obj["shortHash"].string ?: hash.take(7),
                subject = obj["subject"].string ?: "",
                date = obj["date"].string ?: "",
                hasParent = obj["hasParent"].bool ?: false,
                pushed = obj["pushed"].bool ?: false,
            )
        }
    }
}

/** Full Source Control payload (`GET /git/changes`). */
data class GitChanges(
    val branch: GitBranchState,
    val operation: GitOperation?,
    val lastCommit: LastCommit?,
    val files: List<ChangedFile>,
    val truncated: Boolean,
    val stashes: Int,
) {
    companion object {
        fun from(json: JsonElement?): GitChanges = GitChanges(
            branch = GitBranchState.from(json["branch"]),
            operation = GitOperation.from(json["operation"]),
            lastCommit = LastCommit.from(json["lastCommit"]),
            files = json["files"].array.orEmpty().mapNotNull(ChangedFile::from),
            truncated = json["truncated"].bool ?: false,
            stashes = json["stashes"].int ?: 0,
        )
    }
}

/** Result of `POST /git/discard`. */
data class DiscardRecord(
    val id: String,
    val createdAt: Long,
    val files: List<String>,
    val skipped: List<String>,
) {
    companion object {
        fun from(json: JsonElement?): DiscardRecord {
            val target = json?.obj?.get("undo")?.takeIf { it.obj != null } ?: json
            return DiscardRecord(
                id = target["id"].string ?: "",
                createdAt = target["createdAt"].long ?: 0L,
                files = (target["files"].array ?: target["paths"].array).orEmpty().mapNotNull { it.string },
                skipped = target["skipped"].array.orEmpty().mapNotNull { it.string },
            )
        }
    }
}

/** One line in a [DiffHunk] (`" "` context, `"+"` addition, `"-"` deletion). */
data class DiffLine(
    val type: String,
    val content: String,
    val oldNumber: Int? = null,
    val newNumber: Int? = null,
) {
    companion object {
        fun from(json: JsonElement?, fallbackOld: Int? = null, fallbackNew: Int? = null): DiffLine? {
            val obj = json.obj ?: return null
            val type = obj["type"].string ?: obj["kind"].string ?: return null
            return DiffLine(
                type = type,
                content = obj["content"].string ?: obj["text"].string ?: "",
                oldNumber = obj["oldNumber"].int ?: fallbackOld,
                newNumber = obj["newNumber"].int ?: fallbackNew,
            )
        }
    }
}

/** One hunk in `GET /git/hunks`. */
data class DiffHunk(
    val index: Int,
    val header: String,
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<DiffLine>,
    val id: String,
) {
    companion object {
        fun from(json: JsonElement?, fallbackIndex: Int = 0): DiffHunk? {
            val obj = json.obj ?: return null
            val id = obj["id"].string ?: return null
            val oldStart = obj["oldStart"].int ?: 0
            val oldLines = obj["oldLines"].int ?: 0
            val newStart = obj["newStart"].int ?: 0
            val newLines = obj["newLines"].int ?: 0
            val heading = obj["heading"].string ?: ""
            val header = obj["header"].string
                ?: "@@ -$oldStart,$oldLines +$newStart,$newLines @@${if (heading.isNotEmpty()) " $heading" else ""}"
            var oldCur = oldStart
            var newCur = newStart
            val parsedLines = obj["lines"].array.orEmpty().mapNotNull { lineEl ->
                val kind = lineEl.obj?.get("type")?.string ?: lineEl.obj?.get("kind")?.string
                val (oNum, nNum) = when (kind) {
                    "-" -> oldCur++ to null
                    "+" -> null to newCur++
                    " " -> oldCur++ to newCur++
                    else -> null to null
                }
                DiffLine.from(lineEl, oNum, nNum)
            }
            return DiffHunk(
                index = obj["index"].int ?: fallbackIndex,
                header = header,
                oldStart = oldStart,
                oldLines = oldLines,
                newStart = newStart,
                newLines = newLines,
                lines = parsedLines,
                id = id,
            )
        }
    }
}

/** Result of `GET /git/hunks`. */
data class FileHunks(
    val path: String,
    val binary: Boolean,
    val hunks: List<DiffHunk>,
) {
    companion object {
        fun from(json: JsonElement?): FileHunks = FileHunks(
            path = json["path"].string ?: json["filePath"].string ?: "",
            binary = json["binary"].bool ?: false,
            hunks = json["hunks"].array.orEmpty().mapIndexedNotNull { idx, el -> DiffHunk.from(el, idx) },
        )
    }
}

/** Request item for `POST /git/{stage,unstage,discard}-hunks`. */
data class HunkSelectionItem(
    val hunk: Int,
    val id: String,
    val lines: List<Int>? = null,
) {
    fun toJson() = buildJsonObject {
        put("hunk", hunk)
        put("id", id)
        if (lines != null) put("lines", JsonArray(lines.map(::JsonPrimitive)))
    }
}

/** Shared commit message draft (`GET / PUT /git/commit-draft`). */
data class CommitDraft(
    val message: String,
    val updatedAt: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): CommitDraft = CommitDraft(
            message = json["message"].string ?: "",
            updatedAt = json["updatedAt"].string,
        )
    }
}

/** Result of `POST /git/commit`. */
data class GitCommitResult(val hash: String)

/** Result of `POST /git/pull`. */
data class GitPullResult(
    val files: List<String>,
    val insertions: Int,
    val deletions: Int,
) {
    companion object {
        fun from(json: JsonElement?): GitPullResult = GitPullResult(
            files = json["files"].array.orEmpty().mapNotNull { it.string },
            insertions = json["insertions"].int ?: 0,
            deletions = json["deletions"].int ?: 0,
        )
    }
}

/** Ref entry in `GET /git/refs` (`branch`, `remote`, or `tag`). */
data class GitRef(
    val name: String,
    val kind: String,
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val date: String,
    val current: Boolean = false,
    val upstream: String? = null,
    val upstreamGone: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
) {
    companion object {
        fun from(json: JsonElement?): GitRef? {
            val obj = json.obj ?: return null
            val name = obj["name"].string ?: return null
            val kind = obj["kind"].string ?: obj["type"].string ?: return null
            val hash = obj["hash"].string ?: ""
            return GitRef(
                name = name,
                kind = kind,
                hash = hash,
                shortHash = obj["shortHash"].string ?: hash.take(7),
                subject = obj["subject"].string ?: "",
                author = obj["author"].string ?: "",
                date = obj["date"].string ?: "",
                current = obj["current"].bool ?: false,
                upstream = obj["upstream"].string,
                upstreamGone = obj["upstreamGone"].bool ?: obj["gone"].bool ?: false,
                ahead = obj["ahead"].int ?: 0,
                behind = obj["behind"].int ?: 0,
            )
        }
    }
}

/** Branch entry in `GET /git/branches`. */
data class GitBranch(
    val name: String,
    val current: Boolean,
    val commit: String,
    val tracking: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
) {
    companion object {
        fun from(json: JsonElement?): GitBranch? {
            val obj = json.obj ?: return null
            val name = obj["name"].string ?: return null
            return GitBranch(
                name = name,
                current = obj["current"].bool ?: false,
                commit = obj["commit"].string ?: obj["commitHash"].string ?: "",
                tracking = obj["tracking"].string,
                ahead = obj["ahead"].int,
                behind = obj["behind"].int,
            )
        }
    }
}

/** Stash entry in `GET /git/stashes` and `POST /git/stash`. */
data class StashEntry(
    val index: Int,
    val hash: String,
    val message: String,
    val date: String,
) {
    companion object {
        fun from(json: JsonElement?): StashEntry? {
            val obj = json?.obj?.get("stash")?.obj ?: json.obj ?: return null
            val index = obj["index"].int ?: return null
            val hash = obj["hash"].string ?: return null
            return StashEntry(
                index = index,
                hash = hash,
                message = obj["message"].string ?: "stash@{$index}",
                date = obj["date"].string ?: "",
            )
        }
    }
}

/** Result of `POST /git/stash/{apply,pop,drop}`. */
data class StashApplyResult(val indexRestored: Boolean)

/** Worktree entry in `GET /git/worktrees` and `POST /git/worktree/add`. */
data class GitWorktree(
    val path: String,
    val head: String,
    val branch: String?,
    val isMain: Boolean,
    val isLocked: Boolean,
    val lockReason: String? = null,
    val prunable: Boolean,
) {
    companion object {
        fun from(json: JsonElement?): GitWorktree? {
            val obj = json.obj ?: return null
            val path = obj["path"].string ?: return null
            return GitWorktree(
                path = path,
                head = obj["head"].string ?: "",
                branch = obj["branch"].string?.takeIf { it.isNotEmpty() },
                isMain = obj["isMain"].bool ?: false,
                isLocked = obj["isLocked"].bool ?: obj["locked"].bool ?: false,
                lockReason = obj["lockReason"].string,
                prunable = obj["prunable"].bool ?: false,
            )
        }
    }
}

internal fun gitRepoScope(repo: GitRepo?): List<Pair<String, String>> =
    if (repo == null || repo.relative == ".") emptyList() else listOf("repo" to repo.path)

private fun gitFileChange(json: JsonElement): GitFileChange? {
    val path = json["path"].string ?: return null
    val status = json["status"].string ?: return null
    return GitFileChange(path, status, json["oldPath"].string)
}

suspend fun ApiClient.gitRepos(project: String): GitRepos {
    val data = call("GET", "/api/project/${ApiClient.escape(project)}/git/repos")
    return GitRepos(
        rootIsRepo = data["rootIsRepo"].bool ?: false,
        repos = data["repos"].array.orEmpty().mapNotNull { repo ->
            val path = repo["path"].string ?: return@mapNotNull null
            GitRepo(path, repo["name"].string ?: path, repo["relative"].string ?: ".")
        },
        root = data["root"].string ?: "",
    )
}

/** [repo] is scoped with `repo=` only when it is not the project folder, so an ordinary project's address stays as the web's. */
suspend fun ApiClient.gitStatus(project: String, repo: GitRepo?): GitBranchStatus {
    val data = call("GET", "/api/project/${ApiClient.escape(project)}/git/status", gitRepoScope(repo))
    return GitBranchStatus(
        branch = data["current"].string,
        ahead = data["ahead"].int ?: 0,
        behind = data["behind"].int ?: 0,
        tracking = data["tracking"].string,
        staged = data["staged"].array.orEmpty().mapNotNull(::gitFileChange),
        unstaged = data["unstaged"].array.orEmpty().mapNotNull(::gitFileChange),
        untracked = data["untracked"].array.orEmpty().mapNotNull { it.string },
    )
}

suspend fun ApiClient.gitChanges(project: String, repo: GitRepo? = null): GitChanges =
    GitChanges.from(call("GET", "/api/project/${ApiClient.escape(project)}/git/changes", gitRepoScope(repo)))

suspend fun ApiClient.gitCommitDraft(project: String, repo: GitRepo? = null): CommitDraft =
    CommitDraft.from(call("GET", "/api/project/${ApiClient.escape(project)}/git/commit-draft", gitRepoScope(repo)))

suspend fun ApiClient.putGitCommitDraft(
    project: String,
    message: String,
    clientId: String? = null,
    repo: GitRepo? = null,
): CommitDraft {
    val body = buildJsonObject {
        put("message", message)
        if (!clientId.isNullOrEmpty()) put("clientId", clientId)
    }
    return CommitDraft.from(call("PUT", "/api/project/${ApiClient.escape(project)}/git/commit-draft", gitRepoScope(repo), body))
}

suspend fun ApiClient.gitStage(project: String, files: List<String>, repo: GitRepo? = null) {
    val body = buildJsonObject { put("files", JsonArray(files.map(::JsonPrimitive))) }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/stage", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitUnstage(project: String, files: List<String>, repo: GitRepo? = null) {
    val body = buildJsonObject { put("files", JsonArray(files.map(::JsonPrimitive))) }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/unstage", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitDiscard(project: String, files: List<String>, repo: GitRepo? = null): DiscardRecord {
    val body = buildJsonObject { put("files", JsonArray(files.map(::JsonPrimitive))) }
    return DiscardRecord.from(call("POST", "/api/project/${ApiClient.escape(project)}/git/discard", gitRepoScope(repo), body))
}

suspend fun ApiClient.gitDiscardUndo(project: String, id: String, repo: GitRepo? = null) {
    val body = buildJsonObject { put("id", id) }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/discard/undo", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitHunks(
    project: String,
    path: String,
    scope: String = "worktree",
    repo: GitRepo? = null,
): FileHunks {
    val query = listOf("path" to path, "scope" to scope) + gitRepoScope(repo)
    return FileHunks.from(call("GET", "/api/project/${ApiClient.escape(project)}/git/hunks", query))
}

suspend fun ApiClient.gitStageHunks(
    project: String,
    path: String,
    hunks: List<HunkSelectionItem>,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("path", path)
        put("hunks", JsonArray(hunks.map { it.toJson() }))
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/stage-hunks", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitUnstageHunks(
    project: String,
    path: String,
    hunks: List<HunkSelectionItem>,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("path", path)
        put("hunks", JsonArray(hunks.map { it.toJson() }))
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/unstage-hunks", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitDiscardHunks(
    project: String,
    path: String,
    hunks: List<HunkSelectionItem>,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("path", path)
        put("hunks", JsonArray(hunks.map { it.toJson() }))
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/discard-hunks", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitCommit(
    project: String,
    message: String,
    amend: Boolean = false,
    signoff: Boolean = false,
    repo: GitRepo? = null,
): GitCommitResult {
    val body = buildJsonObject {
        put("message", message)
        if (amend) put("amend", true)
        if (signoff) put("signoff", true)
    }
    val data = call("POST", "/api/project/${ApiClient.escape(project)}/git/commit", gitRepoScope(repo), body)
    return GitCommitResult(data["hash"].string ?: "")
}

suspend fun ApiClient.gitUndoCommit(project: String, hash: String, repo: GitRepo? = null) {
    val body = buildJsonObject { put("hash", hash) }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/commit/undo", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitPush(
    project: String,
    remote: String? = null,
    branch: String? = null,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        if (!remote.isNullOrEmpty()) put("remote", remote)
        if (!branch.isNullOrEmpty()) put("branch", branch)
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/push", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitPublish(project: String, repo: GitRepo? = null) {
    call("POST", "/api/project/${ApiClient.escape(project)}/git/publish", gitRepoScope(repo), buildJsonObject {})
}

suspend fun ApiClient.gitPull(
    project: String,
    rebase: Boolean = false,
    repo: GitRepo? = null,
): GitPullResult {
    val body = buildJsonObject {
        if (rebase) put("rebase", true)
    }
    return GitPullResult.from(call("POST", "/api/project/${ApiClient.escape(project)}/git/pull", gitRepoScope(repo), body))
}

suspend fun ApiClient.gitFetch(
    project: String,
    remote: String? = null,
    prune: Boolean = false,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        if (!remote.isNullOrEmpty()) put("remote", remote)
        if (prune) put("prune", true)
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/fetch", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitRefs(project: String, repo: GitRepo? = null): List<GitRef> =
    call("GET", "/api/project/${ApiClient.escape(project)}/git/refs", gitRepoScope(repo))
        .array.orEmpty().mapNotNull(GitRef::from)

suspend fun ApiClient.gitBranches(project: String, repo: GitRepo? = null): List<GitBranch> =
    call("GET", "/api/project/${ApiClient.escape(project)}/git/branches", gitRepoScope(repo))
        .array.orEmpty().mapNotNull(GitBranch::from)

suspend fun ApiClient.gitCheckout(
    project: String,
    ref: String,
    mode: String? = null,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("ref", ref)
        if (!mode.isNullOrEmpty()) put("mode", mode)
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/checkout", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitCreateBranch(
    project: String,
    name: String,
    from: String? = null,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("name", name)
        if (!from.isNullOrEmpty()) put("from", from)
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/branch/create", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitOperation(project: String, action: String, repo: GitRepo? = null) {
    call("POST", "/api/project/${ApiClient.escape(project)}/git/operation/${ApiClient.escape(action)}", gitRepoScope(repo), buildJsonObject {})
}

suspend fun ApiClient.gitStashes(project: String, repo: GitRepo? = null): List<StashEntry> =
    call("GET", "/api/project/${ApiClient.escape(project)}/git/stashes", gitRepoScope(repo))
        .array.orEmpty().mapNotNull(StashEntry::from)

suspend fun ApiClient.gitStash(
    project: String,
    message: String? = null,
    includeUntracked: Boolean = true,
    repo: GitRepo? = null,
): StashEntry {
    val body = buildJsonObject {
        if (!message.isNullOrEmpty()) put("message", message)
        put("includeUntracked", includeUntracked)
    }
    val data = call("POST", "/api/project/${ApiClient.escape(project)}/git/stash", gitRepoScope(repo), body)
    return StashEntry.from(data) ?: StashEntry(0, "", message ?: "stash@{0}", "")
}

suspend fun ApiClient.gitStashAction(
    project: String,
    action: String,
    index: Int,
    hash: String,
    repo: GitRepo? = null,
): StashApplyResult {
    val body = buildJsonObject {
        put("index", index)
        put("hash", hash)
    }
    val data = call("POST", "/api/project/${ApiClient.escape(project)}/git/stash/${ApiClient.escape(action)}", gitRepoScope(repo), body)
    return StashApplyResult(indexRestored = data["indexRestored"].bool ?: true)
}

suspend fun ApiClient.gitWorktrees(project: String, repo: GitRepo? = null): List<GitWorktree> =
    call("GET", "/api/project/${ApiClient.escape(project)}/git/worktrees", gitRepoScope(repo))
        .array.orEmpty().mapNotNull(GitWorktree::from)

suspend fun ApiClient.gitWorktreeAdd(
    project: String,
    path: String,
    branch: String? = null,
    newBranch: String? = null,
    repo: GitRepo? = null,
): GitWorktree {
    val body = buildJsonObject {
        put("path", path)
        if (!branch.isNullOrEmpty()) put("branch", branch)
        if (!newBranch.isNullOrEmpty()) put("newBranch", newBranch)
    }
    val data = call("POST", "/api/project/${ApiClient.escape(project)}/git/worktree/add", gitRepoScope(repo), body)
    return GitWorktree.from(data) ?: GitWorktree(path = path, head = "", branch = newBranch ?: branch, isMain = false, isLocked = false, prunable = false)
}

suspend fun ApiClient.gitWorktreeRemove(
    project: String,
    path: String,
    force: Boolean = false,
    repo: GitRepo? = null,
) {
    val body = buildJsonObject {
        put("path", path)
        if (force) put("force", true)
    }
    call("POST", "/api/project/${ApiClient.escape(project)}/git/worktree/remove", gitRepoScope(repo), body)
}

suspend fun ApiClient.gitWorktreePrune(project: String, repo: GitRepo? = null) {
    call("POST", "/api/project/${ApiClient.escape(project)}/git/worktree/prune", gitRepoScope(repo), buildJsonObject {})
}
