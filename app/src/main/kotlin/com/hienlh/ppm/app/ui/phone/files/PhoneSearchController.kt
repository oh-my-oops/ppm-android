package com.hienlh.ppm.app.ui.phone.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.replaceInProjectFiles
import com.hienlh.ppm.client.searchProjectFiles
import com.hienlh.ppm.protocol.FileReplaceAnswer
import com.hienlh.ppm.protocol.FileReplaceFile
import com.hienlh.ppm.protocol.FileReplaceMatch
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchMatch
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.FileSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The phone's Search screen state, ported from the web's Search panel: `search-store.ts` (one entry
 * per project, so leaving the screen or switching projects loses nothing), `search-client.ts`
 * (debounce, a newer search cancels the older one) and the pure half of `search-rows.ts` (counts
 * without dismissed matches, the body of a replace). The list is grouped by file; the web's tree
 * view, history walk and keyboard focus are desktop affordances the phone leaves out.
 */
data class PhoneSearchFields(
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    /** "Files to include", comma-separated globs as typed. */
    val include: String = "",
    /** "Files to exclude", comma-separated globs as typed. */
    val exclude: String = "",
    /** "Use Exclude Settings and Ignore Files". */
    val useIgnore: Boolean = true,
    val replace: String = "",
    val preserveCase: Boolean = false,
    val showReplace: Boolean = false,
    /** The ··· details (include/exclude) are open. */
    val showDetails: Boolean = false,
) {
    /** The request for these fields, or null when there is nothing to search (`searchUrl`). */
    fun options(): FileSearchOptions? = if (query.isEmpty()) null else FileSearchOptions(
        query = query,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
        regex = regex,
        include = include,
        exclude = exclude,
        useIgnore = useIgnore,
        replace = if (showReplace) replace else null,
        preserveCase = preserveCase,
    )

    /** The dot on the ··· button: a filter still applies while the fields are folded (`filtersActive`). */
    val filtersActive: Boolean get() = hasGlob(include) || hasGlob(exclude) || !useIgnore

    private fun hasGlob(field: String) = field.split(',').any { it.isNotBlank() }
}

enum class PhoneSearchStatus { IDLE, SEARCHING, DONE, ERROR }

/** [status] is the HTTP status; 0 when the request never reached the server. */
data class PhoneSearchError(val message: String, val status: Int)

enum class PhoneSearchTone { SUCCESS, WARNING, ERROR }

data class PhoneSearchNote(val tone: PhoneSearchTone, val text: String)

data class PhoneSearchState(
    val project: String? = null,
    val fields: PhoneSearchFields = PhoneSearchFields(),
    val response: FileSearchAnswer? = null,
    val status: PhoneSearchStatus = PhoneSearchStatus.IDLE,
    val error: PhoneSearchError? = null,
    /** Match keys and file paths dismissed from the list. */
    val dismissed: Set<String> = emptySet(),
    /** Files collapsed in the list. */
    val collapsed: Set<String> = emptySet(),
    val replacing: Boolean = false,
    /** The status line's report of the last single replace. */
    val note: PhoneSearchNote? = null,
) {
    /** An engine's regex error (HTTP 400) marks the field instead of a banner. */
    val fieldError: String? get() = error?.takeIf { status == PhoneSearchStatus.ERROR && it.status == 400 }?.message
    val bannerError: PhoneSearchError? get() = error?.takeIf { status == PhoneSearchStatus.ERROR && it.status != 400 }
    val counts: PhoneSearchCounts get() = countSearchResults(response, dismissed)
    val canReplaceAll: Boolean get() = fields.showReplace && counts.total > 0 && !replacing
}

data class PhoneSearchCounts(val total: Int, val files: Int)

fun searchMatchKey(file: String, match: FileSearchMatch): String = "$file:${match.line}:${match.column}"

fun visibleSearchMatches(result: FileSearchResult, dismissed: Set<String>): List<FileSearchMatch> {
    if (result.file in dismissed) return emptyList()
    if (dismissed.isEmpty()) return result.matches
    return result.matches.filter { searchMatchKey(result.file, it) !in dismissed }
}

/** "N results in M files", counting only what is listed (dismissed matches left out). */
fun countSearchResults(response: FileSearchAnswer?, dismissed: Set<String>): PhoneSearchCounts {
    var total = 0
    var files = 0
    for (result in response?.results.orEmpty()) {
        val n = visibleSearchMatches(result, dismissed).size
        if (n == 0) continue
        total += n
        files++
    }
    return PhoneSearchCounts(total, files)
}

fun searchResultSummary(counts: PhoneSearchCounts): String =
    if (counts.total == 0) "No results found"
    else "${plural(counts.total, "result", "results")} in ${plural(counts.files, "file", "files")}"

internal fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

sealed interface PhoneReplaceScope {
    data object All : PhoneReplaceScope
    data class File(val file: String) : PhoneReplaceScope
    data class Match(val key: String) : PhoneReplaceScope
}

data class PhoneReplaceFiles(val files: List<FileReplaceFile>, val unsaved: List<String>, val matches: Int)

/**
 * The `files` of a replace for one match, one file or everything left (`buildReplaceFiles`):
 * dismissed matches and matches without a computed replacement are left out, and files with
 * unsaved changes are named in `unsaved` instead of sent.
 */
fun buildPhoneReplaceFiles(
    response: FileSearchAnswer?,
    dismissed: Set<String>,
    scope: PhoneReplaceScope,
    isUnsaved: (String) -> Boolean = { false },
): PhoneReplaceFiles {
    val files = mutableListOf<FileReplaceFile>()
    val unsaved = mutableListOf<String>()
    var count = 0
    for (result in response?.results.orEmpty()) {
        if (scope is PhoneReplaceScope.File && result.file != scope.file) continue
        val matches = visibleSearchMatches(result, dismissed).filter {
            it.replacement != null && it.column != null &&
                (scope !is PhoneReplaceScope.Match || searchMatchKey(result.file, it) == scope.key)
        }
        if (matches.isEmpty()) continue
        if (isUnsaved(result.file)) {
            unsaved += result.file
            continue
        }
        files += FileReplaceFile(result.file, matches.map { FileReplaceMatch(it.line, it.column!!, it.text, it.replacement!!) })
        count += matches.size
    }
    return PhoneReplaceFiles(files, unsaved, count)
}

interface PhoneSearchBackend {
    suspend fun search(project: String, options: FileSearchOptions): FileSearchAnswer
    suspend fun replace(project: String, files: List<FileReplaceFile>): FileReplaceAnswer

    companion object {
        fun api(api: ApiClient): PhoneSearchBackend = object : PhoneSearchBackend {
            override suspend fun search(project: String, options: FileSearchOptions) = api.searchProjectFiles(project, options)
            override suspend fun replace(project: String, files: List<FileReplaceFile>) = api.replaceInProjectFiles(project, files)
        }
    }
}

/**
 * Main-thread controller. [isUnsaved] says a project file is open with unsaved edits (never sent to
 * a replace); [onReplaced] hears the files a replace wrote, so open tabs and the tree refresh.
 */
class PhoneSearchController(
    private val backend: PhoneSearchBackend,
    scope: CoroutineScope,
    private val isUnsaved: (project: String, file: String) -> Boolean = { _, _ -> false },
    private val onReplaced: (project: String, files: List<String>) -> Unit = { _, _ -> },
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
) : AutoCloseable {
    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val projects = mutableMapOf<String, PhoneSearchState>()
    private val mutable = MutableStateFlow(PhoneSearchState())
    val state: StateFlow<PhoneSearchState> = mutable.asStateFlow()
    private val running = mutableMapOf<String, Job>()

    /** The Search screen is showing in the Files area (kept here so opening a match and coming back returns to it). */
    private val openFlow = MutableStateFlow(false)
    val open: StateFlow<Boolean> = openFlow.asStateFlow()
    fun setOpen(value: Boolean) { openFlow.value = value }

    fun setProject(project: String?) {
        if (project == mutable.value.project) return
        mutable.value = project?.let { projects[it] } ?: PhoneSearchState(project = project)
    }

    private fun stateOf(project: String) = projects[project] ?: PhoneSearchState(project = project)

    private fun update(project: String, transform: (PhoneSearchState) -> PhoneSearchState) {
        val next = transform(stateOf(project))
        projects[project] = next
        if (mutable.value.project == project) mutable.value = next
    }

    /** A change the server's answer does not depend on (··· open, Preserve Case while replace is closed). */
    fun updateFields(transform: (PhoneSearchFields) -> PhoneSearchFields) {
        val project = mutable.value.project ?: return
        update(project) { it.copy(fields = transform(it.fields)) }
    }

    /** Change something the server's answer depends on, and search again after the debounce. */
    fun change(transform: (PhoneSearchFields) -> PhoneSearchFields) {
        val project = mutable.value.project ?: return
        val before = stateOf(project).fields
        val after = transform(before)
        if (after == before) return
        update(project) { it.copy(fields = after, note = null) }
        schedule(project)
    }

    fun toggleCaseSensitive() = change { it.copy(caseSensitive = !it.caseSensitive) }
    fun toggleWholeWord() = change { it.copy(wholeWord = !it.wholeWord) }
    fun toggleRegex() = change { it.copy(regex = !it.regex) }
    fun toggleUseIgnore() = change { it.copy(useIgnore = !it.useIgnore) }
    fun toggleReplace() = change { it.copy(showReplace = !it.showReplace) }
    fun toggleDetails() = updateFields { it.copy(showDetails = !it.showDetails) }

    /** Preserve Case only changes the server's replacements, which it computes only while replace is open. */
    fun togglePreserveCase() {
        if (mutable.value.fields.showReplace) change { it.copy(preserveCase = !it.preserveCase) }
        else updateFields { it.copy(preserveCase = !it.preserveCase) }
    }

    fun toggleCollapsed(file: String) {
        val project = mutable.value.project ?: return
        update(project) { it.copy(collapsed = if (file in it.collapsed) it.collapsed - file else it.collapsed + file) }
    }

    fun dismiss(id: String) {
        val project = mutable.value.project ?: return
        update(project) { it.copy(dismissed = it.dismissed + id) }
    }

    /** Clear Search Results: empty the query and the list, keep options and filters. */
    fun clear() {
        val project = mutable.value.project ?: return
        stop(project)
        update(project) {
            it.copy(fields = it.fields.copy(query = ""), response = null, status = PhoneSearchStatus.IDLE, error = null,
                dismissed = emptySet(), collapsed = emptySet(), note = null)
        }
    }

    /** Search at once (Retry, Search again, the keyboard's Search key). */
    fun searchNow() {
        val project = mutable.value.project ?: return
        update(project) { it.copy(note = null) }
        run(project, immediate = true)
    }

    private fun schedule(project: String) = run(project, immediate = false)

    private fun stop(project: String) {
        running.remove(project)?.cancel()
    }

    private fun run(project: String, immediate: Boolean) {
        stop(project)
        val options = stateOf(project).fields.options()
        if (options == null) {
            update(project) { it.copy(response = null, status = PhoneSearchStatus.IDLE, error = null, dismissed = emptySet(), collapsed = emptySet()) }
            return
        }
        update(project) { it.copy(status = PhoneSearchStatus.SEARCHING, error = null) }
        val job = owner.launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext.job
            if (!immediate) delay(debounceMs)
            try {
                val answer = backend.search(project, options)
                if (running[project] !== job) return@launch
                running.remove(project)
                update(project) {
                    it.copy(response = answer, status = PhoneSearchStatus.DONE, error = null, dismissed = emptySet(), collapsed = emptySet())
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (running[project] !== job) return@launch
                running.remove(project)
                val status = (error as? PpmError.Server)?.status ?: 0
                update(project) {
                    it.copy(response = null, status = PhoneSearchStatus.ERROR,
                        error = PhoneSearchError(error.message ?: "Search failed", status))
                }
            }
        }
        running[project] = job
        job.start()
    }

    /** Replace one match or one file at once; drops the replaced rows and reports in the status line. */
    fun replace(scope: PhoneReplaceScope) {
        val project = mutable.value.project ?: return
        val current = stateOf(project)
        if (current.replacing) return
        val built = buildPhoneReplaceFiles(current.response, current.dismissed, scope) { isUnsaved(project, it) }
        val unsavedNote = built.unsaved.takeIf { it.isNotEmpty() }?.let { "Skipped ${it.joinToString(", ")}: unsaved changes in an open tab." }
        if (built.files.isEmpty()) {
            if (unsavedNote != null) update(project) { it.copy(note = PhoneSearchNote(PhoneSearchTone.WARNING, unsavedNote)) }
            return
        }
        update(project) { it.copy(replacing = true) }
        owner.launch {
            try {
                val report = backend.replace(project, built.files)
                val notDone = (report.skipped + report.failed).map { it.file }.toSet()
                update(project) { state ->
                    val dismissed = state.dismissed.toMutableSet()
                    for (f in built.files) {
                        if (f.file in notDone) continue
                        for (m in f.matches) dismissed += "${f.file}:${m.line}:${m.column}"
                    }
                    val parts = mutableListOf<String>()
                    if (report.replaced > 0 || notDone.isEmpty()) parts += "${plural(report.replaced, "replacement", "replacements")} made"
                    unsavedNote?.let { parts += it }
                    report.skipped.forEach { parts += "Skipped ${it.file}: changed on disk since the search." }
                    report.failed.forEach { parts += "Failed ${it.file}: ${it.reason}." }
                    val tone = when {
                        report.failed.isNotEmpty() -> PhoneSearchTone.ERROR
                        notDone.isNotEmpty() || unsavedNote != null -> PhoneSearchTone.WARNING
                        else -> PhoneSearchTone.SUCCESS
                    }
                    state.copy(dismissed = dismissed, note = PhoneSearchNote(tone, parts.joinToString(" ")))
                }
                written(project, built.files, notDone)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                update(project) { it.copy(note = PhoneSearchNote(PhoneSearchTone.ERROR, "Replace failed: ${error.message ?: "unknown error"}")) }
            } finally {
                update(project) { it.copy(replacing = false) }
            }
        }
    }

    /**
     * Replace All, for the confirm sheet: what to send now (exactly the listed matches), and the
     * request itself, which throws when the server refuses it so the sheet can keep its question open.
     */
    fun replaceAllFiles(): PhoneReplaceFiles {
        val project = mutable.value.project ?: return PhoneReplaceFiles(emptyList(), emptyList(), 0)
        val current = stateOf(project)
        return buildPhoneReplaceFiles(current.response, current.dismissed, PhoneReplaceScope.All) { isUnsaved(project, it) }
    }

    suspend fun replaceAll(project: String, files: List<FileReplaceFile>): FileReplaceAnswer {
        if (files.isEmpty()) return FileReplaceAnswer(0, 0, emptyList(), emptyList())
        update(project) { it.copy(replacing = true) }
        try {
            val report = backend.replace(project, files)
            written(project, files, (report.skipped + report.failed).map { it.file }.toSet())
            return report
        } finally {
            update(project) { it.copy(replacing = false) }
        }
    }

    private fun written(project: String, files: List<FileReplaceFile>, notDone: Set<String>) {
        val paths = files.map { it.file }.filter { it !in notDone }
        if (paths.isNotEmpty()) onReplaced(project, paths)
    }

    override fun close() {
        owner.cancel()
        running.clear()
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
