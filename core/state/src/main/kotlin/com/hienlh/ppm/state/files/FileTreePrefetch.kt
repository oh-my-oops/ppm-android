package com.hienlh.ppm.state.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** file-tree-prefetch.ts: 20 candidates per scheduling call, three active, one level ahead. */
internal class FileTreePrefetch(
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val alreadyLoaded: (String) -> Boolean,
    private val load: (String) -> Job?,
) {
    private val queue = linkedSetOf<String>()
    private val active = mutableSetOf<Job>()
    private var timer: Job? = null
    private var generation = 0

    fun schedule(children: List<FileTreeNode>) {
        queue += children.asSequence().filter { it.isDirectory && !it.isIgnored && !alreadyLoaded(it.path) }.take(20).map { it.path }
        if (queue.isNotEmpty()) wake()
    }

    private fun wake() {
        if (timer?.isActive == true) return
        val gen = generation
        timer = scope.launch {
            delay(idleMillis)
            timer = null
            if (gen != generation) return@launch
            while (active.size < 3 && queue.isNotEmpty()) {
                val path = queue.first()
                queue.remove(path)
                if (alreadyLoaded(path)) continue
                val job = load(path) ?: continue
                active += job
                scope.launch {
                    job.join()
                    active -= job
                    if (gen == generation && queue.isNotEmpty()) wake()
                }
            }
        }
    }

    fun cancel() {
        generation++
        timer?.cancel()
        timer = null
        queue.clear()
        active.clear()
    }
}
