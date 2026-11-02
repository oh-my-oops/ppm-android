package org.connectbot.terminal

import android.os.Debug
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What a terminal's history costs in heap, on the real library: `-e only memory` prints the numbers and nothing passes or fails. The app's own
 * terminal is 23 rows by 105 columns on a tablet's dock, with 50,000 rows of history, its output arrives in frames that each run as a task of
 * the main thread, and its heap is capped at 192 MB.
 */
internal object MemoryChecks {
    private const val ROWS = 23
    private const val COLS = 105
    private const val CAP = 50_000
    private const val FRAME = 64 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val report = StringBuilder()

    private fun say(text: String) {
        report.append(text).append('\n')
    }

    /** What is reachable, in bytes, once the collector has run. */
    private fun settle(): Long {
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(60)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun stat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1

    private fun emulator(cap: Int = CAP, rows: Int = ROWS, cols: Int = COLS) =
        TerminalEmulatorFactory.create(initialRows = rows, initialCols = cols, autoDetectUrls = false, maxScrollbackLines = cap) as TerminalEmulatorImpl

    // ------------------------------------------------------------------------------------------------- what is written

    private const val ESC = "\u001b"
    private val BASE = "0123456789".repeat(11).take(COLS - 1)
    private val COLOURED_LINE = ("$ESC[1;34mdirectory$ESC[0m  $ESC[32mscript.sh$ESC[0m  plain.txt  $ESC[31merror.log$ESC[0m  $ESC[33mwarn$ESC[0m  ").repeat(2)

    /** Every cell in a colour of its own, as an image drawn in terminal cells is: the worst row there is for a layout that keeps colour in runs. */
    private fun rainbow(n: Int): String = buildString {
        for (i in 0 until COLS - 1) append(ESC).append("[38;2;").append((i * 2 + n) % 256).append(';').append(255 - i * 2).append(";128mx")
        append(ESC).append("[0m\r\n")
    }

    private enum class Kind(val label: String, val line: (Int) -> String) {
        SHORT("short, seq", { "$it\r\n" }),
        FULL("full width, no colour", { BASE + "\r\n" }),
        COLOURED("coloured, 20 runs", { COLOURED_LINE + "\r\n" }),
        RAINBOW("every cell its own colour", { rainbow(it) }),
    }

    /** [count] lines of [kind] numbered from [from], cut into frames of about [FRAME] bytes at line ends. */
    private fun frames(kind: Kind, count: Int, from: Int = 1): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val frame = StringBuilder()
        for (n in from until from + count) {
            frame.append(kind.line(n))
            if (frame.length >= FRAME) {
                frames += frame.toString().toByteArray(Charsets.UTF_8)
                frame.setLength(0)
            }
        }
        if (frame.isNotEmpty()) frames += frame.toString().toByteArray(Charsets.UTF_8)
        return frames
    }

    /** One frame per task of the main thread, as the controller gets them; the time it took, in nanoseconds, and what it threw. */
    private fun feed(emulator: TerminalEmulatorImpl, frames: List<ByteArray>, replay: Boolean = false): Pair<Long, Throwable?> {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val start = System.nanoTime()
        fun step(index: Int) {
            if (index == frames.size) {
                done.countDown()
                return
            }
            try {
                if (replay) emulator.writeReplay(frames[index]) else emulator.writeInput(frames[index])
            } catch (error: Throwable) {
                failure = error
                done.countDown()
                return
            }
            main.post { step(index + 1) }
        }
        main.post { step(0) }
        check(done.await(10, TimeUnit.MINUTES)) { "The frames were not written in ten minutes" }
        return (System.nanoTime() - start) to failure
    }

    private fun idle() = Thread.sleep(400)

    private val TerminalEmulatorImpl.history: Int get() = snapshot.value.scrollback.size

    // ------------------------------------------------------------------------------------------------- what is held

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /** How many rows [list] holds that the live history no longer has: what a stale snapshot keeps alive. */
    private fun pinned(emulator: TerminalEmulatorImpl, list: List<TerminalLine>): Int {
        val lock = field(emulator, "damageLock")!!
        synchronized(lock) {
            val live = Collections.newSetFromMap(IdentityHashMap<TerminalLine, Boolean>())
            @Suppress("UNCHECKED_CAST")
            live.addAll(field(emulator, "scrollback") as Collection<TerminalLine>)
            return list.count { it !in live }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cachedSnapshot(emulator: TerminalEmulatorImpl) = field(emulator, "scrollbackSnapshot") as List<TerminalLine>

    // ------------------------------------------------------------------------------------------------- the numbers

    /** What an empty terminal costs: the emulator, its screen and the library's own, without any history. */
    private fun empty() {
        val before = settle()
        val emulators = List(10) { emulator() }
        val after = settle()
        say("empty terminal: ${(after - before) / 10} B each (heap), native ${Debug.getNativeHeapAllocatedSize() / 1024} KB in the process")
        emulators.forEach { it.close() }
    }

    /** What a row of the history costs, for a terminal that is full: the heap that stays once the collector has run, over the rows it holds. */
    private fun perRow(kind: Kind, cap: Int = CAP) {
        val before = settle()
        val emulator = emulator(cap)
        val (nanos, failure) = feed(emulator, frames(kind, cap + ROWS))
        idle()
        val rows = emulator.history
        val after = settle()
        say("row, ${kind.label}: $rows rows, ${if (rows > 0) (after - before) / rows else -1} B each (${(after - before) / 1_048_576} MB), written in ${nanos / 1_000_000} ms${failure?.let { ", FAILED: $it" } ?: ""}")
        emulator.close()
    }

    /** What a replay of [megabyte] bytes of [kind] costs: the server's whole buffer, written as one frame, as a reconnect does. */
    private fun replay(kind: Kind) {
        val frames = frames(kind, 1_100_000 / kind.line(100_000).length)
        val bytes = frames.fold(ByteArray(0)) { all, next -> all + next }.copyOf(1024 * 1024)
        val before = settle()
        val emulator = emulator()
        val (nanos, failure) = feed(emulator, listOf(bytes), replay = true)
        idle()
        val rows = emulator.history
        val after = settle()
        say("replay of 1 MB, ${kind.label}: $rows rows of history, ${(after - before) / 1_048_576} MB, written in ${nanos / 1_000_000} ms${failure?.let { ", FAILED: $it" } ?: ""}")
        emulator.close()
    }

    /**
     * A flood at the cap, twice: the second one only replaces rows, so what stays should not move. What it takes while it runs is sampled (heap in use,
     * garbage included), and what the history's cached snapshot still holds that the history has let go of is counted when the last frame is in.
     */
    private fun flood(kind: Kind, cap: Int) {
        val emulator = emulator(cap)
        feed(emulator, frames(kind, cap + ROWS))
        idle()
        val first = settle()
        val runtime = Runtime.getRuntime()
        val peak = AtomicLong()
        val sampling = AtomicBoolean(true)
        val snapshots = AtomicInteger()
        var lastSequence = emulator.snapshot.value.sequenceNumber
        val sampler = Thread {
            while (sampling.get()) {
                peak.set(maxOf(peak.get(), runtime.totalMemory() - runtime.freeMemory()))
                val sequence = emulator.snapshot.value.sequenceNumber
                if (sequence != lastSequence) {
                    snapshots.incrementAndGet()
                    lastSequence = sequence
                }
                Thread.sleep(4)
            }
        }.also { it.start() }
        val gcBefore = stat("art.gc.gc-count") to stat("art.gc.blocking-gc-count")
        val (nanos, failure) = feed(emulator, frames(kind, cap + ROWS, from = cap + ROWS + 1))
        val stale = pinned(emulator, cachedSnapshot(emulator))
        val published = pinned(emulator, emulator.snapshot.value.scrollback)
        sampling.set(false)
        sampler.join()
        val gcAfter = stat("art.gc.gc-count") to stat("art.gc.blocking-gc-count")
        idle()
        val second = settle()
        say(
            "flood at the cap (${kind.label}, cap $cap): first fill $first B (${first / 1_048_576} MB), second ${nanos / 1_000_000} ms, " +
                "peak in use ${peak.get() / 1_048_576} MB, after it $second B (${second / 1_048_576} MB); " +
                "rows the cached snapshot holds that the history let go of: $stale, the published one: $published, snapshots published meanwhile: ${snapshots.get()}; " +
                "collections ${gcAfter.first - gcBefore.first} (${gcAfter.second - gcBefore.second} blocking)${failure?.let { ", FAILED: $it" } ?: ""}",
        )
        emulator.close()
    }

    fun run(): String {
        report.setLength(0)
        say("heap limit ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, terminal ${ROWS}x$COLS, cap $CAP rows")
        empty()
        for (kind in Kind.entries) perRow(kind, if (kind == Kind.RAINBOW) 10_000 else CAP)
        for (kind in Kind.entries) replay(kind)
        flood(Kind.SHORT, 20_000)
        flood(Kind.COLOURED, 20_000)
        say("flood at the app's cap, which needs about twice what the rows keep if the snapshot holds the old ones:")
        flood(Kind.SHORT, CAP)
        return report.toString()
    }
}
