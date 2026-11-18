package com.hienlh.ppm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.design.share.ShareButton
import com.hienlh.ppm.app.ui.design.share.ShareText
import com.hienlh.ppm.app.ui.design.share.shareBody
import com.hienlh.ppm.app.ui.design.share.shareCaption
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.shareHostSetEnabled
import com.hienlh.ppm.client.shareHostUseTemporary
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.design.share.reviewCannotApplyText
import com.hienlh.ppm.state.design.share.reviewLinksStatusText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Settings → Remote Access → Review links (`review-links-pane.tsx`): a public address for design
 * review links that opens the review pages and nothing else of PPM — no editor, chats, files or
 * sign-in page answer there — so a design can be shared while PPM itself stays private. The
 * switch only writes config and the supervisor applies it, so the state is polled.
 */

@Composable
fun ReviewLinksPane(
    api: ApiClient?,
    onToast: (ErrorToastMessage) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenGeneral: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val holder = rememberShareHostStatus(api)
    val setup = rememberReviewAddressSetup(holder, onToast)
    var pending by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var confirmTemporary by remember { mutableStateOf(false) }

    fun setEnabled(next: Boolean) {
        val client = api ?: return
        pending = true
        holder.status = holder.status?.copy(enabled = next)
        scope.launch {
            try {
                val applied = client.shareHostSetEnabled(next)
                if (!applied.applied) reportApplied(applied, if (next) "Review address turned on" else "Review address turned off", onToast)
                holder.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                holder.status = holder.status?.copy(enabled = !next)
                onToast(ErrorToastMessage(if (next) "Could not turn the review address on" else "Could not turn the review address off", e.message))
            } finally {
                pending = false
            }
        }
    }

    fun switchToTemporary() {
        val client = api ?: return
        confirmTemporary = false
        switching = true
        scope.launch {
            try {
                reportApplied(client.shareHostUseTemporary(), "Switched to a temporary address", onToast)
                holder.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast(ErrorToastMessage("Could not switch to a temporary address", e.message))
            } finally {
                switching = false
            }
        }
    }

    val status = holder.status
    Column(modifier.fillMaxWidth().testTag("review-links-pane"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText("Review links", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color))
        if (status == null) {
            ShareText(
                holder.loadError?.let { "Could not read the review address's state: $it" } ?: "Reading the review address's state…",
                Modifier.testTag("review-links-loading"),
            )
            return@Column
        }
        ShareText(
            "Share design review links without making PPM public. Reviewers get an address of its own that " +
                "opens the review pages and nothing else: no editor, chats, files or sign-in page answer there.",
        )

        PaneCard(Modifier.testTag("review-links-switch-card")) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShareText("Review address", color = tokens.text.color, weight = FontWeight.Medium)
                    ShareText(reviewLinksStatusText(status), Modifier.testTag("review-links-status"))
                }
                Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pending || (holder.connecting && status.canApply)) PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                    Box(Modifier.testTag("review-links-switch").semantics { contentDescription = "Review address" }) {
                        PpmSwitch(status.enabled, ::setEnabled, enabled = !pending && (status.enabled || status.authEnabled))
                    }
                }
            }
            if (!status.authEnabled) {
                PaneNotice(ProductIcon.Lock, "review-links-auth-off") {
                    ShareText("PPM's password is off, and share links do not open while it is. Turn it on first.")
                    ShareButton("Open General", onOpenGeneral)
                }
            }
            if (status.enabled && !status.canApply) {
                PaneNotice(ProductIcon.AlertTriangle, "review-links-cannot-apply", warn = true) { ShareText(reviewCannotApplyText(status)) }
            }
        }

        Column(Modifier.testTag("review-links-address"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareText("Address", color = tokens.text.color, weight = FontWeight.Medium)
            val hostname = if (status.mode == "quick") null else status.hostname
            val ppmHostname = if (status.ppmTunnel.named) status.ppmTunnel.hostname else null
            if (setup.flow != null) {
                PaneCard(Modifier.testTag("review-links-setup")) { ReviewAddressSetupView(setup, ppmHostname, onOpenUrl) }
            } else {
                val shape = RoundedCornerShape(8.dp)
                Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, shape)) {
                    AddressOption(
                        title = "Temporary address",
                        inUse = status.mode == "quick",
                        description = "A random trycloudflare.com address of its own. It changes every time PPM restarts, and links already sent stop working then.",
                        testTag = "review-links-option-quick",
                    ) {
                        if (status.mode != "quick") ShareButton("Switch", { confirmTemporary = true }, busy = switching, testTag = "review-links-switch-quick")
                    }
                    OptionDivider()
                    AddressOption(
                        title = "Your domain, its own tunnel",
                        inUse = status.mode == "named",
                        description = if (status.mode == "named" && hostname != null) "https://$hostname, on a Cloudflare tunnel of its own."
                        else "A fixed address like share.example.com, on a Cloudflare tunnel of its own. PPM's public link is not involved.",
                        testTag = "review-links-option-named",
                    ) {
                        if (status.authEnabled) {
                            ShareButton(
                                if (status.mode == "named") "Change" else "Set up",
                                { setup.begin(DomainMode.NAMED) },
                                variant = if (status.mode == "named") CardButtonVariant.DEFAULT else CardButtonVariant.PRIMARY,
                                testTag = "review-links-setup-named",
                            )
                        }
                    }
                    OptionDivider()
                    AddressOption(
                        title = "Your domain, on PPM's tunnel",
                        inUse = status.mode == "shared",
                        description = if (status.mode == "shared" && hostname != null) "https://$hostname, on the tunnel of PPM's public link. PPM's own address answers only while that link is on."
                        else "A fixed address like share.example.com, added to the tunnel PPM's public link uses. PPM's own address answers only while that link is on.",
                        testTag = "review-links-option-shared",
                    ) {
                        if (status.authEnabled && status.ppmTunnel.named) {
                            ShareButton(
                                if (status.mode == "shared") "Change" else "Set up",
                                { setup.begin(DomainMode.SHARED) },
                                variant = if (status.mode == "shared") CardButtonVariant.DEFAULT else CardButtonVariant.PRIMARY,
                                testTag = "review-links-setup-shared",
                            )
                        }
                    }
                }
                if (!status.authEnabled) {
                    BasicText("Turn on PPM's password to use your own domain.", style = shareCaption.copy(color = tokens.text3.color))
                } else if (!status.ppmTunnel.named) {
                    BasicText(
                        "To use PPM's tunnel, set your domain up under Public link first. Its link can stay off.",
                        style = shareCaption.copy(color = tokens.text3.color),
                    )
                }
            }
            val warning = status.warning
            if (status.enabled && warning != null) {
                PaneNotice(ProductIcon.AlertTriangle, "review-links-warning", warn = true) { ShareText(warning) }
            }
        }
    }

    if (confirmTemporary) {
        val hostname = holder.status?.hostname
        ConfirmDialog(
            title = "Switch to a temporary address?",
            message = "Review links stop opening at https://$hostname and get a new random address instead. Links already sent with your domain stop working until you set it up again.",
            confirm = "Switch",
            confirmTestTag = "review-links-switch-confirm",
            onDismiss = { confirmTemporary = false },
            onConfirm = ::switchToTemporary,
        )
    }
}

@Composable
private fun PaneCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth().cssBorder(tokens.border.color, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** `Notice`: a muted box with an icon beside its lines. */
@Composable
private fun PaneNotice(icon: ProductIcon, testTag: String, warn: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().testTag(testTag).background(tokens.panel2.color, shape).cssBorder(tokens.border.color, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { PpmIcon(icon, 16.dp, (if (warn) tokens.warning else tokens.text3).color) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun OptionDivider() {
    val tokens = LocalPpmTokens.current
    Box(Modifier.fillMaxWidth().padding(0.dp).cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Top))
}

/** One address choice (`AddressOption`): its title, "In use" when it is, what it is, and its action. */
@Composable
private fun AddressOption(title: String, inUse: Boolean, description: String, testTag: String, action: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().testTag(testTag).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, style = shareBody.copy(color = tokens.text.color, fontWeight = FontWeight.Medium))
                if (inUse) {
                    BasicText(
                        "IN USE",
                        Modifier.background(tokens.accent.opacity(0.1).color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = ppmText(10f, FontWeight.Medium).copy(color = tokens.accent.color),
                    )
                }
            }
            ShareText(description)
        }
        action()
    }
}
