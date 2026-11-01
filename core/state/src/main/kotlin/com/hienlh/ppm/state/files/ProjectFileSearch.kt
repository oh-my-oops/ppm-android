package com.hienlh.ppm.state.files

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.readProjectFileContent
import com.hienlh.ppm.client.searchProjectFiles
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.protocol.FileContent
import com.hienlh.ppm.protocol.FileSearchAnswer
import com.hienlh.ppm.protocol.FileSearchOptions
import com.hienlh.ppm.protocol.FileSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileSearchReplacement(val content: String, val count: Int)

/** Android supplies the shared JS engine. JVM tests supply the same operations independently. */
interface FileSearchRegex {
    suspend fun validate(options: FileSearchOptions): String?
    suspend fun replace(content: String, options: FileSearchOptions, replacement: String): FileSearchReplacement
}

interface FileSearchBackend {
    suspend fun search(project: String, options: FileSearchOptions): FileSearchAnswer
    suspend fun read(project: String, path: String): FileContent
    suspend fun write(project: String, path: String, content: String)

    companion object {
        fun api(api: ApiClient): FileSearchBackend = object : FileSearchBackend {
            override suspend fun search(project: String, options: FileSearchOptions) = api.searchProjectFiles(project, options)
            override suspend fun read(project: String, path: String) = api.readProjectFileContent(project, path)
            override suspend fun write(project: String, path: String, content: String) {
                require(!Regex("""^(/|[A-Za-z]:[/\\])""").containsMatchIn(path)) { "Search results must use project-relative paths." }
                api.writeFile(project, path, content)
            }
        }
    }
}

/** Only acknowledged writes contribute to count/paths. A failure never implies rollback. */
data class FileReplaceReport(
    val project: String,
    val count: Int = 0,
    val paths: List<String> = emptyList(),
    val failedPath: String? = null,
    val error: String? = null,
    val cancelled: Boolean = false,
)

data class ProjectFileSearchState(
    val project: String? = null,
    val options: FileSearchOptions = FileSearchOptions(),
    val replacement: String = "",
    val results: List<FileSearchResult> = emptyList(),
    val total: Int = 0,
    val collapsed: Set<String> = emptySet(),
    val pending: Boolean = false,
    val loading: Boolean = false,
    val searched: Boolean = false,
    val regexError: String? = null,
    val error: String? = null,
    val errorStatus: Int? = null,
    val replacing: Boolean = false,
    val replaceReport: FileReplaceReport? = null,
) {
    val canReplace: Boolean get() = project != null && searched && results.isNotEmpty() && !pending && !loading && !replacing && error == null && regexError == null
}

/** Main-thread controller. Changing any search input invalidates results before the debounce. */
class ProjectFileSearch(
    private val backend: FileSearchBackend,
    private val regex: FileSearchRegex,
    scope: CoroutineScope,
    private val onReplacement: (FileReplaceReport) -> Unit = {},
) : AutoCloseable {
    constructor(api: ApiClient, regex: FileSearchRegex, scope: CoroutineScope, onReplacement: (FileReplaceReport) -> Unit = {}) :
        this(FileSearchBackend.api(api), regex, scope, onReplacement)

    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(ProjectFileSearchState())
    val state: StateFlow<ProjectFileSearchState> = mutable.asStateFlow()
    private val current get() = mutable.value
    private var epoch = 0
    private var searchJob: Job? = null
    private var replaceJob: Job? = null
    private var closed = false

    fun setProject(project: String?) {
        if (!alive() || project == current.project) return
        replaceJob?.cancel()
        mutable.value = current.copy(project = project, collapsed = emptySet(), replaceReport = null)
        schedule()
    }
    fun setQuery(query: String) = options(current.options.copy(query = query))
    fun setCaseSensitive(enabled: Boolean) = options(current.options.copy(caseSensitive = enabled))
    fun setWholeWord(enabled: Boolean) = options(current.options.copy(wholeWord = enabled, regex = if (enabled) false else current.options.regex))
    fun setRegex(enabled: Boolean) = options(current.options.copy(regex = enabled, wholeWord = if (enabled) false else current.options.wholeWord))
    fun setInclude(include: String) = options(current.options.copy(include = include))
    fun setReplacement(replacement: String) {
        if (alive()) mutable.value = current.copy(replacement = replacement, replaceReport = null)
    }
    fun toggleCollapse(file: String) {
        if (alive()) mutable.value = current.copy(collapsed = if (file in current.collapsed) current.collapsed - file else current.collapsed + file)
    }
    fun retry() { if (alive()) schedule(immediate = true) }

    private fun options(next: FileSearchOptions) {
        if (!alive() || next == current.options) return
        mutable.value = current.copy(options = next, replaceReport = null)
        schedule()
    }

    private fun schedule(immediate: Boolean = false) {
        val version = ++epoch
        searchJob?.cancel()
        val project = current.project
        val options = current.options
        val eligible = project != null && options.eligible
        mutable.value = current.copy(results = emptyList(), total = 0, pending = eligible && !immediate,
            loading = false, searched = false, regexError = null, error = null, errorStatus = null)
        if (!eligible) return
        searchJob = owner.launch {
            try {
                if (!immediate) delay(300)
                if (options.regex) {
                    val error = regex.validate(options)
                    if (!accepts(version)) return@launch
                    if (error != null) { mutable.value = current.copy(regexError = error, pending = false); return@launch }
                }
                if (!accepts(version)) return@launch
                mutable.value = current.copy(pending = false, loading = true)
                val answer = backend.search(project!!, options)
                if (accepts(version)) mutable.value = current.copy(results = answer.results, total = answer.total, searched = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (accepts(version)) mutable.value = current.copy(error = error.message ?: "Unable to search this project.", errorStatus = (error as? PpmError.Server)?.status)
            } finally {
                if (accepts(version)) mutable.value = current.copy(loading = false, pending = false)
            }
        }
    }

    /** Whole files are reread and replaced sequentially; result rows are only a five-line sample. */
    fun replaceAll() {
        if (!alive() || !current.canReplace || replaceJob != null) return
        val snapshot = current
        val project = snapshot.project ?: return
        val files = snapshot.results.map { it.file }.distinct()
        mutable.value = current.copy(replacing = true, replaceReport = FileReplaceReport(project))
        val job = owner.launch(start = CoroutineStart.LAZY) {
            var report = FileReplaceReport(project)
            var path: String? = null
            try {
                for (file in files) {
                    ensureActive()
                    path = file
                    val read = backend.read(project, file)
                    ensureActive()
                    check(read.isText) { "Cannot replace binary or unsupported text content." }
                    val replaced = regex.replace(read.content, snapshot.options, snapshot.replacement)
                    ensureActive()
                    if (replaced.count == 0) continue
                    // A PUT already sent may have committed. Wait for its answer before stopping,
                    // so cancellation/project switching cannot silently lose the written path.
                    withContext(NonCancellable) {
                        backend.write(project, file, replaced.content)
                        report = report.copy(count = report.count + replaced.count, paths = report.paths + file)
                    }
                    if (alive() && current.project == project) mutable.value = current.copy(replaceReport = report)
                }
                ensureActive()
            } catch (cancel: CancellationException) {
                report = report.copy(cancelled = true)
            } catch (error: Exception) {
                report = report.copy(failedPath = path, error = error.message ?: "Unable to replace this file.")
            } finally {
                replaceJob = null
                mutable.value = current.copy(replacing = false,
                    replaceReport = if (current.project == project) report else current.replaceReport)
                onReplacement(report)
                if (alive() && current.project == project && current.options == snapshot.options) schedule(immediate = true)
            }
        }
        replaceJob = job
        // LAZY jobs cancelled before their first dispatch never enter the body/finally.
        job.invokeOnCompletion {
            if (replaceJob === job) {
                replaceJob = null
                val report = FileReplaceReport(project, cancelled = true)
                mutable.value = current.copy(replacing = false,
                    replaceReport = if (current.project == project) report else current.replaceReport)
                onReplacement(report)
            }
        }
        job.start()
    }

    private fun alive() = !closed && owner.isActive
    private fun accepts(version: Int) = alive() && epoch == version
    override fun close() {
        if (closed) return
        closed = true
        epoch++
        owner.cancel()
        mutable.value = current.copy(loading = false, pending = false)
    }
}
