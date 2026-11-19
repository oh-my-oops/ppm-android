package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.GitBranchStatus
import com.hienlh.ppm.client.GitFileChange
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class FileGitScopeKind {
    REPO,
    NO_REPO,
}

/** Resolved git scope for a file path inside a project (`fileGitScope` in `git-repo-scope.ts`). */
data class FileGitScope(
    val repo: GitRepo?,
    val filePath: String,
    val kind: FileGitScopeKind,
)

/**
 * Pure repo resolution and path helpers for multi-repo projects (`src/web/lib/git-repo-scope.ts`).
 */
object GitRepoScope {
    /**
     * The repository one surface works in when it has a [pinned] one of its own (`useGitRepo`'s
     * `pinned`): the Branch Review tab the AI's `open_branch_review` opened on a named repository.
     * A pin no longer among the discovered repositories is ignored, so the surface falls back to the
     * device's [chosen] repository rather than asking for a pick its own picker could not satisfy.
     */
    fun pinnedOrChosen(discovery: GitRepos?, pinned: String?, chosen: String?): String? =
        if (!pinned.isNullOrEmpty() && discovery?.repos?.any { it.path == pinned } == true) pinned else chosen

    /**
     * Pick the active repo from discovery results + persisted choice.
     * - Root is a repo -> always use root (ignore choice).
     * - Single nested repo -> auto-select it.
     * - Multiple nested repos -> return the chosen one if it still exists, otherwise null (needs user pick).
     * - No repos -> null.
     */
    fun resolveRepo(discovery: GitRepos?, chosenPath: String?): GitRepo? {
        if (discovery == null || discovery.repos.isEmpty()) return null
        if (discovery.rootIsRepo) return discovery.repos[0]
        if (discovery.repos.size == 1) return discovery.repos[0]
        if (chosenPath.isNullOrEmpty()) return null
        return discovery.repos.firstOrNull { it.path == chosenPath }
    }

    /**
     * Find the discovered repo that contains a project-relative file path.
     * When nested repos overlap, the deepest (longest `relative`) prefix wins.
     */
    fun repoContainingFile(discovery: GitRepos?, projectRelative: String): GitRepo? {
        if (discovery == null || discovery.repos.isEmpty()) return null
        val norm = projectRelative.replace('\\', '/').removePrefix("./")
        var best: GitRepo? = null
        for (repo in discovery.repos) {
            if (repo.relative == ".") {
                if (best == null) best = repo
                continue
            }
            val prefix = repo.relative.replace('\\', '/')
            if (norm == prefix || norm.startsWith("$prefix/")) {
                if (best == null || best.relative == "." || prefix.length > best.relative.length) {
                    best = repo
                }
            }
        }
        return best
    }

    /**
     * Resolve the git repo and repo-relative path for a project-relative file (`fileGitScope`).
     */
    fun fileGitScope(discovery: GitRepos?, projectRelative: String): FileGitScope? {
        if (discovery == null) return null
        val repo = repoContainingFile(discovery, projectRelative)
        if (repo != null) {
            val filePath = repoRelativePath(projectRelative, repo)
                ?: return FileGitScope(repo = null, filePath = projectRelative, kind = FileGitScopeKind.NO_REPO)
            return FileGitScope(repo = repo, filePath = filePath, kind = FileGitScopeKind.REPO)
        }
        if (hasNoRepo(discovery)) {
            val filePath = repoRelativePath(projectRelative, null) ?: projectRelative
            return FileGitScope(repo = null, filePath = filePath, kind = FileGitScopeKind.REPO)
        }
        return FileGitScope(repo = null, filePath = projectRelative, kind = FileGitScopeKind.NO_REPO)
    }

    /** True when the project is a folder containing multiple repos and none is chosen yet. */
    fun needsPick(discovery: GitRepos?, chosenPath: String?): Boolean {
        if (discovery == null || discovery.rootIsRepo) return false
        if (discovery.repos.size <= 1) return false
        return resolveRepo(discovery, chosenPath) == null
    }

    /** True when the project has no git repo at root or one level down. */
    fun hasNoRepo(discovery: GitRepos?): Boolean =
        discovery != null && !discovery.rootIsRepo && discovery.repos.isEmpty()

    /**
     * Convert a project-relative file path (`apps/api/src/index.ts`) to a repo-relative path
     * (`src/index.ts`). Returns null if the file is outside the selected repo.
     */
    fun repoRelativePath(projectRelative: String, repo: GitRepo?): String? {
        if (repo == null || repo.relative == ".") return projectRelative
        val prefix = "${repo.relative.replace('\\', '/')}/"
        val norm = projectRelative.replace('\\', '/')
        if (norm == repo.relative) return ""
        return if (norm.startsWith(prefix)) norm.substring(prefix.length) else null
    }

    /**
     * Convert a repo-relative file path (`src/index.ts`) back to a project-relative path
     * (`apps/api/src/index.ts`) so Explorer and Editor tabs can locate the file from project root.
     */
    fun projectRelativePath(repoRelative: String, repo: GitRepo?): String {
        if (repo == null || repo.relative == "." || repoRelative.isEmpty()) return repoRelative
        return "${repo.relative}/$repoRelative"
    }

    /** Rewrite all file paths in a [GitBranchStatus] to be project-relative (`rebaseStatusPaths`). */
    fun rebaseStatusPaths(status: GitBranchStatus, repo: GitRepo?): GitBranchStatus {
        if (repo == null || repo.relative == ".") return status
        val mapChange = { c: GitFileChange ->
            c.copy(
                path = projectRelativePath(c.path, repo),
                oldPath = c.oldPath?.let { projectRelativePath(it, repo) },
            )
        }
        return status.copy(
            staged = status.staged.map(mapChange),
            unstaged = status.unstaged.map(mapChange),
            untracked = status.untracked.map { projectRelativePath(it, repo) },
        )
    }

    /** `commandRunsGit` in `src/web/lib/git-repo-scope.ts:215-217`. */
    fun commandRunsGit(command: String): Boolean = command.startsWith("git-graph.")
}

/**
 * Per-device repository selection for multi-repo projects (`src/web/stores/git-repo-store.ts`),
 * persisted under `"ppm-git-repo-choice"` in [prefs].
 */
class GitRepoChoices(private val prefs: KeyValueStore, private val storageKey: String = "ppm-git-repo-choice") {
    private var choices: Map<String, String> = load()

    fun chosen(project: String): String? = choices[project]

    fun choose(project: String, repoPath: String) {
        choices = choices + (project to repoPath)
        save()
    }

    fun clear(project: String) {
        if (project !in choices) return
        choices = choices - project
        save()
    }

    private fun load(): Map<String, String> {
        val raw = prefs.get(storageKey) ?: return emptyMap()
        val obj = WireJson.parseOrNull(raw).obj ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> v.string?.let { k to it } }.toMap()
    }

    private fun save() {
        val json = JsonObject(choices.mapValues { JsonPrimitive(it.value) }).toString()
        prefs.put(storageKey, json)
    }
}
