package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string

/** One repository under a project. [relative] is `.` when it is the project folder itself. */
data class GitRepo(val path: String, val name: String, val relative: String)

/** The repositories under a project (`GET /git/repos`): a project folder is often a container of checkouts. */
data class GitRepos(val rootIsRepo: Boolean, val repos: List<GitRepo>) {
    /**
     * The repository to run git in without asking (`resolveRepo`): the project itself, or the only
     * one under it. Null when there is none, or several to choose from.
     */
    val resolved: GitRepo? get() = if (rootIsRepo) repos.firstOrNull() else repos.singleOrNull()
}

/** What the status bar shows of `GET /git/status`: the branch and how far it is from its upstream. */
data class GitBranchStatus(val branch: String?, val ahead: Int, val behind: Int, val tracking: String?)

suspend fun ApiClient.gitRepos(project: String): GitRepos {
    val data = call("GET", "/api/project/${ApiClient.escape(project)}/git/repos")
    return GitRepos(
        rootIsRepo = data["rootIsRepo"].bool ?: false,
        repos = data["repos"].array.orEmpty().mapNotNull { repo ->
            val path = repo["path"].string ?: return@mapNotNull null
            GitRepo(path, repo["name"].string ?: path, repo["relative"].string ?: ".")
        },
    )
}

/** [repo] is scoped with `repo=` only when it is not the project folder, so an ordinary project's address stays as the web's. */
suspend fun ApiClient.gitStatus(project: String, repo: GitRepo?): GitBranchStatus {
    val scope = if (repo == null || repo.relative == ".") emptyList() else listOf("repo" to repo.path)
    val data = call("GET", "/api/project/${ApiClient.escape(project)}/git/status", scope)
    return GitBranchStatus(data["current"].string, data["ahead"].int ?: 0, data["behind"].int ?: 0, data["tracking"].string)
}
