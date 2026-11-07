package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.gitCommitDraft
import com.hienlh.ppm.client.putGitCommitDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Shared commit message draft synced with `GET / PUT /git/commit-draft` and `/ws/global`
 * `git:commit-draft` broadcasts (`src/web/stores/commit-draft-store.ts` + `use-commit-draft.ts`).
 */
class CommitDraftSync(
    private val scope: CoroutineScope,
    val clientId: String = UUID.randomUUID().toString(),
    private val debounceMs: Long = 300L,
) {
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private val dirty = HashSet<String>()
    private val timers = HashMap<String, Job>()

    fun message(project: String, repo: GitRepo?): String =
        _drafts.value[draftKey(project, repo?.path)] ?: ""

    fun load(api: ApiClient, project: String, repo: GitRepo?) {
        val key = draftKey(project, repo?.path)
        scope.launch {
            try {
                val res = api.gitCommitDraft(project, repo)
                if (key !in dirty) {
                    _drafts.value = _drafts.value + (key to res.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun setMessage(api: ApiClient?, project: String, repo: GitRepo?, message: String) {
        val key = draftKey(project, repo?.path)
        dirty += key
        _drafts.value = _drafts.value + (key to message)
        timers.remove(key)?.cancel()
        if (api == null) return
        timers[key] = scope.launch {
            delay(debounceMs)
            timers.remove(key)
            try {
                api.putGitCommitDraft(project, message, clientId, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    suspend fun flush(api: ApiClient?, project: String, repo: GitRepo?) {
        val key = draftKey(project, repo?.path)
        val job = timers.remove(key) ?: return
        job.cancel()
        if (api == null) return
        val msg = _drafts.value[key] ?: ""
        try {
            api.putGitCommitDraft(project, msg, clientId, repo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun consumed(project: String, repo: GitRepo?, committedMessage: String) {
        val key = draftKey(project, repo?.path)
        timers.remove(key)?.cancel()
        dirty -= key
        val current = _drafts.value[key] ?: ""
        if (current == committedMessage) {
            _drafts.value = _drafts.value + (key to "")
        }
    }

    fun onRemoteDraft(
        project: String,
        repoPath: String?,
        message: String,
        senderClientId: String?,
        activeRepo: GitRepo?,
    ) {
        if (senderClientId != null && senderClientId == clientId) return
        val normalizedRepoPath = repoPath ?: activeRepo?.takeIf { it.relative == "." }?.path
        val key = draftKey(project, normalizedRepoPath)
        val rootKey = draftKey(project, null)
        timers.remove(key)?.cancel()
        dirty -= key
        val next = LinkedHashMap(_drafts.value)
        next[key] = message
        if (repoPath == null && activeRepo != null && activeRepo.relative == ".") {
            val activeKey = draftKey(project, activeRepo.path)
            timers.remove(activeKey)?.cancel()
            dirty -= activeKey
            next[activeKey] = message
        } else if (repoPath != null && activeRepo != null && activeRepo.path == repoPath && activeRepo.relative == ".") {
            timers.remove(rootKey)?.cancel()
            dirty -= rootKey
            next[rootKey] = message
        }
        _drafts.value = next
    }

    companion object {
        fun draftKey(project: String, repoPath: String?): String =
            "$project::${repoPath.orEmpty()}"
    }
}
