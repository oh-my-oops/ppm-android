package com.hienlh.ppm.state

import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.ReleaseNotes
import com.hienlh.ppm.client.applyUpgrade
import com.hienlh.ppm.client.upgradeStatus
import com.hienlh.ppm.protocol.ChangelogSection
import com.hienlh.ppm.protocol.compareVersions
import com.hienlh.ppm.protocol.newestSectionVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The status bar's version chip for one server (upgrade-button.tsx): the version it runs and a
 * newer one, asked once a minute ([watch]) and afresh when the popover opens ([refresh]), the
 * release notes ([loadNotes]), Later ([dismiss]) and Update now ([update]) through the server's
 * restart, after which [reopen] reads the server again as the web reloads the page.
 *
 * [dismissed] holds the versions Later was chosen for while the app runs, as the web keeps them
 * in `sessionStorage`; it outlives this object, which a reopened server replaces.
 */
class ServerUpgrade(
    private val api: ApiClient,
    parent: CoroutineScope,
    private val dismissed: MutableSet<String>,
    private val notify: (UpgradeNotice) -> Unit,
    private val reopen: () -> Unit,
    private val releaseNotes: suspend () -> List<ChangelogSection> = { ReleaseNotes.recent() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(
        /** `/api/upgrade`'s `currentVersion`, once it has answered. */
        val currentVersion: String? = null,
        val availableVersion: String? = null,
        val dismissed: Boolean = false,
        /** The release notes, once the popover has asked for them. */
        val notes: List<ChangelogSection>? = null,
        val notesLoading: Boolean = false,
        val phase: Phase = Phase.IDLE,
    ) {
        /**
         * What the chip shows, given the version `/api/info` reported ([serverVersion]) for the
         * time before `/api/upgrade` answers.
         */
        fun offer(serverVersion: String?): UpgradeOffer {
            val current = currentVersion ?: serverVersion
            val offered = UpgradeOffer.reported(availableVersion, current) ?: UpgradeOffer.announced(notes, current)
            val hasUpdate = offered != null && !dismissed
            return UpgradeOffer(current, offered, hasUpdate, UpgradeOffer.visibleNotes(notes, hasUpdate, current))
        }
    }

    enum class Phase {
        IDLE,

        /** Update now was pressed and the install runs: "Updating…". */
        UPGRADING,

        /** The server restarts into the new version: "Updating, reconnecting…" until it answers. */
        RESTARTING,
    }

    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState

    /** The web's mount check and its interval, for as long as the caller runs it. */
    suspend fun watch() {
        while (true) {
            check()
            delay(POLL_MS)
        }
    }

    /** An opened popover asks again past the server's cache, so its notes and its button agree. */
    fun refresh(): Job = scope.launch { check(force = true) }

    /**
     * `check`: a failure leaves the chip as it was. A dismissal is read again only for a version
     * the server reports, so Later on an update only the changelog names holds until one is.
     */
    suspend fun check(force: Boolean = false) {
        val status = try {
            api.upgradeStatus(refresh = force)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        val available = status.availableVersion
        mutableState.update {
            if (available != null) {
                it.copy(currentVersion = status.currentVersion, availableVersion = available, dismissed = available in dismissed)
            } else {
                it.copy(currentVersion = status.currentVersion, availableVersion = null)
            }
        }
    }

    /** Each opening reads the notes again: a failed or early read must not stay for the session. */
    fun loadNotes(): Job {
        mutableState.update { it.copy(notesLoading = true) }
        return scope.launch {
            val notes = try {
                releaseNotes()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            mutableState.update { it.copy(notes = notes, notesLoading = false) }
        }
    }

    /** Later, for the version on offer, which may be known only from the changelog. */
    fun dismiss(offered: String?) {
        offered?.let { dismissed += it }
        mutableState.update { it.copy(dismissed = true) }
    }

    /** Update now, [offered] being the version the chip showed. */
    fun update(offered: String?): Job {
        mutableState.update { it.copy(phase = Phase.UPGRADING) }
        return scope.launch {
            try {
                val result = api.applyUpgrade()
                if (result.restart) {
                    awaitRestart()
                } else {
                    notify(UpgradeNotice(result.message?.takeIf { it.isNotEmpty() } ?: "Upgrade installed. Restart PPM manually."))
                    offered?.let { dismissed += it }
                    mutableState.update { it.copy(phase = Phase.IDLE, dismissed = true) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when (val failure = UpgradeOffer.failure(error)) {
                    UpgradeFailure.Restarting -> awaitRestart()
                    UpgradeFailure.NotPublished -> {
                        notify(UpgradeNotice("v$offered is announced but not published yet — try again shortly."))
                        mutableState.update { it.copy(phase = Phase.IDLE) }
                    }
                    is UpgradeFailure.Failed -> {
                        notify(UpgradeNotice("Upgrade failed: ${failure.message}", error = true))
                        mutableState.update { it.copy(phase = Phase.IDLE) }
                    }
                }
            }
        }
    }

    /**
     * `reloadWhenServerReady`: the server is asked every 2 s, for a minute at most, before it is
     * read again, so the app does not open a server that is not back yet.
     */
    private suspend fun awaitRestart() {
        mutableState.update { it.copy(phase = Phase.RESTARTING) }
        val deadline = now() + RESTART_WAIT_MS
        while (now() < deadline) {
            try {
                api.health()
                break
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Still restarting.
            }
            delay(RESTART_POLL_MS)
        }
        reopen()
    }

    /** The server was closed or replaced: nothing started here goes on. */
    fun close() = scope.cancel()

    companion object {
        const val POLL_MS = 60_000L
        const val RESTART_WAIT_MS = 60_000L
        const val RESTART_POLL_MS = 2_000L
    }
}

/** A toast Update now raises: `toast.info`, or `toast.error` when [error]. */
data class UpgradeNotice(val text: String, val error: Boolean = false)

/**
 * What the chip shows: the [current] version, the newer one [offered] (by the server, else by the
 * changelog), whether it is still on offer ([hasUpdate], until Later) and the release notes the
 * popover lists.
 */
data class UpgradeOffer(val current: String?, val offered: String?, val hasUpdate: Boolean, val visibleNotes: List<ChangelogSection>?) {
    companion object {
        /** `realUpdate`: the version the server reports, when it is newer than the one it runs. */
        fun reported(available: String?, current: String?): String? =
            available?.takeIf { current != null && compareVersions(it, current) > 0 }

        /**
         * `changelogUpdate`: the newest release in the notes, when it is newer than the one that
         * runs. The changelog is published as a release is pushed, before the registry has it.
         */
        fun announced(notes: List<ChangelogSection>?, current: String?): String? {
            if (notes == null || current == null) return null
            return newestSectionVersion(notes)?.takeIf { compareVersions(it, current) > 0 }
        }

        /** `visibleNotes`: with an update on offer only the releases it brings, or every one when none of them is newer. */
        fun visibleNotes(notes: List<ChangelogSection>?, hasUpdate: Boolean, current: String?): List<ChangelogSection>? {
            if (notes == null || !hasUpdate || current == null) return notes
            return notes.filter { compareVersions(it.version, current) > 0 }.ifEmpty { notes }
        }

        /**
         * `looksLikeServerRestart`, and what else a failed `POST /api/upgrade/apply` means. The
         * server going down for the restart loses the answer, or a proxy in front answers 502-504
         * (Cloudflare 520-529) while it is gone. An answer from PPM itself is a real failure,
         * whatever it says: the web also takes any message naming "fetch" or "network" for a
         * restart, so an install that failed on npm's network went by as a reload.
         */
        fun failure(error: Exception): UpgradeFailure = when {
            error is PpmError.Unreachable -> UpgradeFailure.Restarting
            error is PpmError.BadResponse && GATEWAY.containsMatchIn(error.reason) -> UpgradeFailure.Restarting
            NOT_PUBLISHED.containsMatchIn(error.message.orEmpty()) -> UpgradeFailure.NotPublished
            else -> UpgradeFailure.Failed(error.message ?: error.javaClass.simpleName)
        }

        private val GATEWAY = Regex("""HTTP 5(0[234]|2\d)\b""")
        private val NOT_PUBLISHED = Regex("already on latest", RegexOption.IGNORE_CASE)
    }
}

/** How Update now went wrong. */
sealed interface UpgradeFailure {
    /** The answer was lost to the server restarting into the new version: the upgrade went on. */
    data object Restarting : UpgradeFailure

    /** Offered from the changelog before the registry has it: it cannot be installed yet. */
    data object NotPublished : UpgradeFailure

    data class Failed(val message: String) : UpgradeFailure
}
