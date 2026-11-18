package com.hienlh.ppm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.NamedTunnelLoginStep
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.design.share.ShareButton
import com.hienlh.ppm.app.ui.design.share.ShareProgressLine
import com.hienlh.ppm.app.ui.design.share.ShareText
import com.hienlh.ppm.app.ui.design.share.ShareTextInput
import com.hienlh.ppm.app.ui.design.share.shareCaption
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.ShareHostApplied
import com.hienlh.ppm.client.ShareHostStatus
import com.hienlh.ppm.client.namedTunnelCancelLogin
import com.hienlh.ppm.client.namedTunnelGetStatus
import com.hienlh.ppm.client.namedTunnelStartLogin
import com.hienlh.ppm.client.shareHostSetup
import com.hienlh.ppm.client.shareHostStatus
import com.hienlh.ppm.client.shareHostZone
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.design.share.appliedReport
import com.hienlh.ppm.state.design.share.buildHostname
import com.hienlh.ppm.state.design.share.reviewHostnameError
import com.hienlh.ppm.state.design.share.reviewPrefixFor
import com.hienlh.ppm.state.design.share.shareHostPollMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The review address's state (`use-share-host-status.ts`) and setting a domain up for it
 * (`review-address-setup.tsx`): Cloudflare sign-in when there is none or it cannot be used, the
 * zone, the hostname, then `POST /api/share-host/setup`. Shared by Settings → Remote Access →
 * Review links and the Share dialog's review card. The sign-in is PPM's own
 * (`/api/tunnel/named/login`); its progress is read from the named tunnel's status, as the app's
 * Public link card reads it.
 */

private fun Throwable.text(): String = message ?: toString()

/** The review address's settings and what runs, read while shown: on resume and on a timer. */
@Stable
class ShareHostStatusHolder(internal val api: ApiClient?) {
    var status by mutableStateOf<ShareHostStatus?>(null)
    var loadError by mutableStateOf<String?>(null)
        private set

    /** Switched on, not connected yet. */
    val connecting: Boolean get() = status?.let { it.enabled && !it.connected } == true

    /** Keeps the last known state on a failed read: a failed poll is not a state change. */
    suspend fun refresh() {
        val client = api ?: return
        try {
            status = client.shareHostStatus()
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.text()
        }
    }
}

/** Read on every resume and polled, faster while the address is on but not connected. */
@Composable
fun rememberShareHostStatus(api: ApiClient?, active: Boolean = true): ShareHostStatusHolder {
    val holder = remember(api) { ShareHostStatusHolder(api) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(holder, active) {
        if (!active || api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { holder.refresh() }
    }
    val pollMs = shareHostPollMs(holder.status)
    LaunchedEffect(holder, active, pollMs) {
        if (!active || api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(pollMs)
                holder.refresh()
            }
        }
    }
    return holder
}

/** Toast a change the server saved, saying so when the supervisor could not apply it yet (`reportApplied`). */
fun reportApplied(applied: ShareHostApplied, done: String, onToast: (ErrorToastMessage) -> Unit) {
    val report = appliedReport(applied, done)
    onToast(
        ErrorToastMessage(
            report.title,
            report.description,
            kind = if (report.warning) ErrorToastMessage.Kind.WARNING else ErrorToastMessage.Kind.SUCCESS,
        ),
    )
}

/** `named`: a tunnel of the review address's own; `shared`: a second hostname on PPM's tunnel. */
enum class DomainMode(val wire: String) { NAMED("named"), SHARED("shared") }

sealed interface SetupFlow {
    val mode: DomainMode

    /** The saved Cloudflare sign-in cannot be used; signing in again moves it aside, so ask first. */
    data class Relogin(override val mode: DomainMode, val mismatch: Boolean) : SetupFlow
    data class Login(override val mode: DomainMode, val url: String?, val slow: Boolean) : SetupFlow
    data class Zone(override val mode: DomainMode) : SetupFlow
    data class Hostname(override val mode: DomainMode, val zone: String, val prefix: String, val error: String? = null) : SetupFlow
    data class Submitting(override val mode: DomainMode, val hostname: String) : SetupFlow
}

@Stable
class ReviewAddressSetup internal constructor(
    private val holder: ShareHostStatusHolder,
    private val scope: CoroutineScope,
    private val onToast: (ErrorToastMessage) -> Unit,
    private val onDone: () -> Unit,
) {
    /** The step on screen, or null when no setup runs. */
    var flow by mutableStateOf<SetupFlow?>(null)
        private set

    private val api get() = holder.api
    private val ppmHostname get() = holder.status?.ppmTunnel?.hostname

    private fun toast(title: String, description: String?) = onToast(ErrorToastMessage(title, description))

    private fun loadZone(mode: DomainMode) {
        val client = api ?: return
        flow = SetupFlow.Zone(mode)
        scope.launch {
            try {
                val zone = client.shareHostZone().zone
                val prefix = reviewPrefixFor(holder.status?.hostname, zone)
                if (flow is SetupFlow.Zone) flow = SetupFlow.Hostname(mode, zone, prefix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow = null
                toast("Could not read your Cloudflare domain", e.text())
            }
        }
    }

    internal fun onLoginState(mode: DomainMode, state: String?, detail: String?) {
        when (state) {
            "slow" -> (flow as? SetupFlow.Login)?.let { flow = it.copy(slow = true) }
            "success" -> {
                scope.launch { holder.refresh() }
                loadZone(mode)
            }
            "timeout", "error" -> {
                flow = null
                toast(if (state == "timeout") "The Cloudflare sign-in link expired" else "Cloudflare sign-in failed", detail)
            }
            "cancelled" -> flow = null
        }
    }

    internal fun onLoginUrl(url: String?) {
        if (url == null) return
        (flow as? SetupFlow.Login)?.let { flow = it.copy(url = url) }
    }

    private fun startLogin(mode: DomainMode, relogin: Boolean) {
        val client = api ?: return
        flow = SetupFlow.Login(mode, null, false)
        scope.launch {
            try {
                // `relogin` moves the unusable certificate aside for a fresh sign-in.
                val snap = client.namedTunnelStartLogin(relogin)
                onLoginUrl(snap["url"].string)
                onLoginState(mode, snap["state"].string, snap["message"].string)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow = null
                toast("Could not start the Cloudflare sign-in", e.text())
            }
        }
    }

    /** The sign-in's progress, read from the named tunnel's status while the sign-in step shows. */
    internal suspend fun followLogin() {
        val client = api ?: return
        while (true) {
            delay(LOGIN_POLL_MS)
            val login = flow as? SetupFlow.Login ?: return
            val snap = try {
                client.namedTunnelGetStatus()["login"]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (flow !== login && flow !is SetupFlow.Login) return
            onLoginUrl(snap["url"].string)
            onLoginState(login.mode, snap["state"].string, snap["message"].string)
        }
    }

    /** Set a domain up on [mode], signing in to Cloudflare first when needed. */
    fun begin(mode: DomainMode) {
        val status = holder.status ?: return
        when (status.certState) {
            "ok" -> loadZone(mode)
            "invalid", "mismatch" -> flow = SetupFlow.Relogin(mode, mismatch = status.certState == "mismatch")
            else -> startLogin(mode, false)
        }
    }

    fun cancel() {
        flow = null
    }

    fun cancelLogin() {
        flow = null
        val client = api ?: return
        scope.launch { runCatching { client.namedTunnelCancelLogin() } }
    }

    /** Move the unusable sign-in aside and sign in again. */
    fun relogin(mode: DomainMode) = startLogin(mode, true)

    fun setPrefix(prefix: String) {
        val f = flow as? SetupFlow.Hostname ?: return
        if (f.prefix == prefix) return
        flow = f.copy(prefix = prefix, error = reviewHostnameError(buildHostname(prefix, f.zone), f.zone, ppmHostname))
    }

    fun submit() {
        val f = flow as? SetupFlow.Hostname ?: return
        val client = api ?: return
        val hostname = buildHostname(f.prefix, f.zone)
        reviewHostnameError(hostname, f.zone, ppmHostname)?.let {
            flow = f.copy(error = it)
            return
        }
        flow = SetupFlow.Submitting(f.mode, hostname)
        scope.launch {
            try {
                val result = client.shareHostSetup(f.mode.wire, hostname)
                flow = null
                reportApplied(result.applied, "Review address set to https://${result.hostname}", onToast)
                holder.refresh()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow = f.copy(error = e.text())
            }
        }
    }

    companion object {
        const val LOGIN_POLL_MS = 2_000L
    }
}

/** [onDone] runs once a setup is saved and the status read again. */
@Composable
fun rememberReviewAddressSetup(
    holder: ShareHostStatusHolder,
    onToast: (ErrorToastMessage) -> Unit,
    onDone: () -> Unit = {},
): ReviewAddressSetup {
    val scope = rememberCoroutineScope()
    val toast by rememberUpdatedState(onToast)
    val done by rememberUpdatedState(onDone)
    val setup = remember(holder, scope) { ReviewAddressSetup(holder, scope, { toast(it) }, { done() }) }
    val following = setup.flow is SetupFlow.Login
    LaunchedEffect(setup, following) {
        if (following) setup.followLogin()
    }
    return setup
}

/** The setup's current step; nothing when no setup runs. */
@Composable
fun ReviewAddressSetupView(setup: ReviewAddressSetup, ppmHostname: String?, onOpenUrl: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    when (val flow = setup.flow ?: return) {
        is SetupFlow.Relogin -> Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("review-setup-relogin")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareText("Cloudflare sign-in needed", color = tokens.text.color, weight = FontWeight.Medium)
                ShareText(
                    if (flow.mismatch) "This certificate belongs to a different Cloudflare account — sign in again."
                    else "Your Cloudflare sign-in is no longer valid.",
                )
            }
            SetupButtons(
                cancel = { ShareButton("Cancel", setup::cancel, Modifier.fillMaxButtonWidth()) },
                confirm = { ShareButton("Sign in again", { setup.relogin(flow.mode) }, Modifier.fillMaxButtonWidth(), variant = CardButtonVariant.PRIMARY) },
            )
        }
        is SetupFlow.Login -> NamedTunnelLoginStep(url = flow.url, slow = flow.slow, onCancel = setup::cancelLogin, onOpenUrl = onOpenUrl)
        is SetupFlow.Zone -> ShareProgressLine("Reading your Cloudflare domain…")
        is SetupFlow.Submitting -> ShareProgressLine("Setting up https://${flow.hostname}… This takes a few seconds.")
        is SetupFlow.Hostname -> Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("review-setup-hostname")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareText(
                    if (flow.mode == DomainMode.NAMED) "Review address on its own tunnel" else "Review address on PPM's tunnel",
                    color = tokens.text.color,
                    weight = FontWeight.Medium,
                )
                ShareText(
                    if (flow.mode == DomainMode.NAMED) "PPM creates a Cloudflare tunnel for review links and points this name at it."
                    else "PPM adds this name to its tunnel, next to ${ppmHostname?.let { "https://$it" } ?: "its public link"}.",
                )
            }
            HostnameField(flow, setup::setPrefix)
            SetupButtons(
                cancel = { ShareButton("Cancel", setup::cancel, Modifier.fillMaxButtonWidth()) },
                confirm = {
                    ShareButton("Set up", setup::submit, Modifier.fillMaxButtonWidth(), variant = CardButtonVariant.PRIMARY, testTag = "review-links-submit")
                },
            )
        }
    }
}

/** Prefix input with the zone fixed beside it (`NamedTunnelHostnameField`). */
@Composable
private fun HostnameField(flow: SetupFlow.Hostname, onChange: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val state = rememberTextFieldState(flow.prefix)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { change(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Prefix", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
        ShareTextInput(state, "", capitalize = false, testTag = "review-setup-prefix") {
            Box(
                Modifier.fillMaxHeight().background(tokens.panel2.color).cssBorderStart().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(".${flow.zone}", style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color), maxLines = 1)
            }
        }
        BasicText("The suffix is fixed by the account you just signed in with.", style = shareCaption.copy(color = tokens.text3.color))
        flow.error?.let { BasicText(it, Modifier.testTag("review-setup-error"), style = shareCaption.copy(color = tokens.error.color)) }
    }
}

@Composable
private fun Modifier.cssBorderStart(): Modifier = cssBorder(LocalPpmTokens.current.border.color, sides = com.hienlh.ppm.design.CssSides.Start)

/** Stacked on a phone, the primary lowest; side by side at the end on a tablet. */
@Composable
internal fun SetupButtons(cancel: @Composable () -> Unit, confirm: @Composable () -> Unit) {
    if (LocalPpmMobile.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cancel()
            confirm()
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            cancel()
            confirm()
        }
    }
}

/** Full width in a phone's stack, its own width on a tablet. */
@Composable
internal fun Modifier.fillMaxButtonWidth(): Modifier = if (LocalPpmMobile.current) fillMaxWidth() else this
