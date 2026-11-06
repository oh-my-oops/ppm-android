package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class HostUploadPhase { QUEUED, UPLOADING, DONE, SKIPPED, FAILED, CANCELLED }
data class HostUploadItem(val id: Int, val relativePath: String, val destination: String, val size: Long?,
                          val bytes: Long = 0, val phase: HostUploadPhase = HostUploadPhase.QUEUED, val error: String? = null) {
    val active get() = phase == HostUploadPhase.QUEUED || phase == HostUploadPhase.UPLOADING
}
data class HostUploadBatch(val id: Long, val directory: String, val items: List<HostUploadItem>) {
    val settled get() = items.none { it.active }
    val bytes get() = items.sumOf { it.bytes }
    val total: Long? get() = if (items.all { it.size != null }) items.sumOf { it.size!! } else null
}

internal interface HostUploadBackend {
    suspend fun upload(path: String, source: HostUploadSource, overwrite: Boolean, progress: (HostTransferProgress) -> Unit): HostUploadedFile
    suspend fun stat(path: String)
    suspend fun delete(path: String, permanent: Boolean)
}

/**
 * Main-thread owner, shared across explorer windows when hoisted by the caller. Three active
 * uploads across all batches. Prompt decisions are serialized, including NO_TRASH confirmation.
 * Retain until close() to keep progress alive when navigating directories. Sources stay lazy.
 */
class HostUploads internal constructor(parent: CoroutineScope, private val separator: String,
                                      private val backend: HostUploadBackend, private val onChanged: (Set<String>) -> Unit) {
    constructor(parent: CoroutineScope, api: ApiClient, separator: String, onChanged: (Set<String>) -> Unit = {}) :
        this(parent, separator, object : HostUploadBackend {
            override suspend fun upload(path: String, source: HostUploadSource, overwrite: Boolean, progress: (HostTransferProgress) -> Unit) =
                api.uploadHostFile(path, source, overwrite, progress)
            override suspend fun stat(path: String) { api.fsInfo(path) }
            override suspend fun delete(path: String, permanent: Boolean) { api.fsDelete(path, permanent) }
        }, onChanged)

    init { require(separator == "/" || separator == "\\") }
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val permits = Semaphore(3)
    private val promptsLock = Mutex()
    private val paths = mutableMapOf<String, Mutex>()
    private val jobs = mutableMapOf<Pair<Long, Int>, Job>()
    private val mutable = MutableStateFlow<List<HostUploadBatch>>(emptyList())
    val state: StateFlow<List<HostUploadBatch>> = mutable.asStateFlow()
    private var nextId = 0L

    /** All paths validated before scheduling. finished runs once, including immediate cancel. */
    fun enqueue(directory: String, sources: List<HostUploadSource>, prompts: HostTransferPrompts = HostTransferPrompts(),
                finished: () -> Unit = {}): Long {
        check(owner.isActive) { "The upload queue is closed." }
        require(directory.isNotEmpty() && sources.isNotEmpty()) { "Choose files and an upload directory." }
        val items = sources.mapIndexed { index, source ->
            require(source.size?.let { it >= 0 } != false) { "Invalid upload size." }
            val path = requireNotNull(hostUploadRelativePath(source.relativePath)) { "Unsafe upload path: ${source.relativePath}" }
            HostUploadItem(index, path, hostUploadDestination(directory, path, separator), source.size)
        }
        val id = ++nextId
        val affected = linkedSetOf(directory)
        var remaining = items.size
        mutable.value += HostUploadBatch(id, directory, items)
        prompts.startBatch()
        items.forEach { item ->
            val key = id to item.id
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val progress = MutableStateFlow(HostTransferProgress(0, item.size))
                var collector: Job? = null
                try {
                    permits.withPermit {
                        paths.getOrPut(item.destination) { Mutex() }.withLock {
                            update(id, item.id) { it.copy(phase = HostUploadPhase.UPLOADING) }
                            affected += hostUploadParent(item.destination, separator)
                            collector = launch { progress.collect { value -> update(id, item.id) { it.copy(bytes = value.bytes) } } }
                            val destination = upload(item.destination, sources[item.id], prompts, { (remaining - 1).coerceAtLeast(0) }) { progress.value = it }
                            update(id, item.id) { it.copy(destination = destination ?: it.destination, bytes = progress.value.bytes,
                                phase = if (destination == null) HostUploadPhase.SKIPPED else HostUploadPhase.DONE) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    update(id, item.id) { it.copy(phase = HostUploadPhase.CANCELLED, bytes = progress.value.bytes) }
                    throw cancelled
                } catch (error: Exception) {
                    update(id, item.id) { it.copy(phase = HostUploadPhase.FAILED, bytes = progress.value.bytes, error = hostUploadError(error)) }
                } finally {
                    collector?.cancel()
                    remaining--
                    if (remaining == 0) {
                        try { prompts.endBatch() }
                        finally { try { onChanged(affected.toSet()) } finally { finished() } }
                    }
                }
            }
            jobs[key] = job
            job.invokeOnCompletion { jobs.remove(key); if (jobs.isEmpty()) paths.clear() }
        }
        return id
    }

    fun cancelItem(batchId: Long, itemId: Int) { jobs[batchId to itemId]?.cancel() }
    fun cancelBatch(batchId: Long) { jobs.filterKeys { it.first == batchId }.values.toList().forEach { it.cancel() } }
    fun dismiss(batchId: Long) { mutable.value = mutable.value.filterNot { it.id == batchId && it.settled } }
    fun close() { owner.cancel() }

    private fun update(batchId: Long, itemId: Int, change: (HostUploadItem) -> HostUploadItem) {
        mutable.value = mutable.value.map { batch -> if (batch.id != batchId) batch else
            batch.copy(items = batch.items.map { if (it.id == itemId) change(it) else it }) }
    }

    private suspend fun upload(destination: String, source: HostUploadSource, prompts: HostTransferPrompts,
                               remainingAfter: () -> Int, progress: (HostTransferProgress) -> Unit): String? {
        try { return backend.upload(destination, source, false, progress).path }
        catch (error: PpmError.Server) { if (error.code != "EEXIST") throw error }
        // Keep the whole decision/trash/probe sequence in order; two prompts cannot replace one another.
        val retry = promptsLock.withLock {
            val collision = HostCollision(source.relativePath, destination, source.relativePath.replace('\\', '/').substringAfterLast('/'), remainingAfter())
            when (prompts.resolveCollision(collision)) {
                HostCollisionChoice.SKIP -> null
                HostCollisionChoice.KEEP_BOTH -> freePath(destination, collision.name) to false
                HostCollisionChoice.REPLACE -> {
                    try { backend.delete(destination, false) }
                    catch (error: PpmError.Server) {
                        when (error.code) {
                            "ENOENT" -> Unit
                            "NO_TRASH" -> {
                                if (!prompts.confirmPermanentOverwrite(collision)) return@withLock null
                                backend.delete(destination, true)
                            }
                            else -> throw error
                        }
                    }
                    destination to true
                }
            }
        } ?: return null
        currentCoroutineContext().ensureActive()
        return backend.upload(retry.first, source, retry.second, progress).path
    }

    private suspend fun freePath(destination: String, name: String): String {
        val parent = hostUploadParent(destination, separator)
        for (number in 2..99) {
            currentCoroutineContext().ensureActive()
            val candidate = hostUploadDestination(parent, hostUploadSuffixedName(name, number), separator)
            try { backend.stat(candidate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if ((error as? PpmError.Server)?.code == "ENOENT") return candidate }
        }
        error("No free name available")
    }
}

private fun hostUploadError(error: Exception): String = if (error is PpmError.Server)
    listOfNotNull(error.code, error.message, error.hint).joinToString(": ") else error.message ?: "Upload failed."
