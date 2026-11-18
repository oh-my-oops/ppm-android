package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.settings.DomainMode
import com.hienlh.ppm.app.ui.settings.ReviewAddressSetupView
import com.hienlh.ppm.app.ui.settings.SetupFlow
import com.hienlh.ppm.app.ui.settings.ShareHostStatusHolder
import com.hienlh.ppm.app.ui.settings.fillMaxButtonWidth
import com.hienlh.ppm.app.ui.settings.rememberReviewAddressSetup
import com.hienlh.ppm.app.ui.settings.reportApplied
import com.hienlh.ppm.client.shareHostSetEnabled
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * The review-only address, switched on and given a domain from the Share dialog itself
 * (`review-address-card.tsx`): the same switch and domain setup as Settings → Remote Access →
 * Review links, here because that is where people look when they share. The address block
 * above says where links open now.
 */

private val IN_USE = mapOf(
    "quick" to "On a temporary address.",
    "named" to "On your domain, with a tunnel of its own.",
    "shared" to "On your domain, on PPM's tunnel.",
)

@Composable
private fun Choice(title: String, description: String, testTag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(shape = shape, role = Role.Button, onClick = onClick)
            .testTag(testTag)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ShareText(title, color = tokens.text.color, weight = FontWeight.Medium)
        ShareText(description)
    }
}

/**
 * [onTurnOn] switches the address on and follows it coming up (the address block shows that);
 * [onChanged] reads where links open again.
 */
@Composable
internal fun ReviewAddressCard(
    shareHost: ShareHostStatusHolder,
    onTurnOn: suspend () -> Unit,
    onChanged: () -> Unit,
    onToast: (ErrorToastMessage) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val setup = rememberReviewAddressSetup(shareHost, onToast, onChanged)
    var pending by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    // With sign-in off the dialog says sharing is paused; there is nothing to switch here.
    val status = shareHost.status ?: return
    if (!status.authEnabled) return

    val enabled = status.enabled
    val mode = status.mode
    val ppmHostname = if (status.ppmTunnel.named) status.ppmTunnel.hostname else null
    val savedDomain = if (mode != "quick") status.hostname else null

    fun setEnabled(next: Boolean) {
        pending = true
        choosing = false
        if (!next) {
            if (setup.flow is SetupFlow.Login) setup.cancelLogin() else setup.cancel()
        }
        shareHost.status = status.copy(enabled = next)
        scope.launch {
            try {
                if (next) {
                    onTurnOn()
                } else {
                    val client = shareHost.api ?: return@launch
                    val applied = client.shareHostSetEnabled(false)
                    if (!applied.applied) reportApplied(applied, "Review-only address turned off", onToast)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onToast(ErrorToastMessage("Could not turn the review-only address ${if (next) "on" else "off"}", e.message ?: e.toString()))
            } finally {
                shareHost.refresh()
                onChanged()
                pending = false
            }
        }
    }

    // PPM's tunnel is an option only once PPM's own public link is on a domain.
    fun useDomain() {
        if (ppmHostname != null) choosing = true else setup.begin(DomainMode.NAMED)
    }

    fun choose(next: DomainMode) {
        choosing = false
        setup.begin(next)
    }

    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().testTag("share-review-card").cssBorder(tokens.border.color, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) { PpmIcon(ProductIcon.ShieldCheck, 16.dp, tokens.text3.color) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareText("Review-only address", color = tokens.text.color, weight = FontWeight.Medium)
                ShareText(
                    if (enabled) {
                        IN_USE[mode] ?: IN_USE.getValue("quick")
                    } else {
                        "Reviewers get an address of their own that opens the review pages and nothing else of PPM."
                    },
                    Modifier.testTag("share-review-state"),
                )
                if (!enabled && savedDomain != null) {
                    ShareText("Turning it on uses https://$savedDomain.", Modifier.testTag("share-review-saved-domain"))
                }
            }
            Row(
                Modifier.heightIn(min = if (LocalPpmMobile.current) 44.dp else 36.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pending) PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                Box(Modifier.testTag("share-review-switch").semantics { contentDescription = "Review-only address" }) {
                    PpmSwitch(enabled, ::setEnabled, enabled = !pending)
                }
            }
        }

        if (enabled && setup.flow == null && !choosing) {
            ShareButton(
                if (mode == "quick") "Use my domain" else "Change domain",
                ::useDomain,
                Modifier.fillMaxButtonWidth(),
                testTag = "share-review-domain",
            )
        }

        if (choosing && ppmHostname != null) {
            Column(
                Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Top).padding(top = 12.dp)
                    .testTag("share-review-choose"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareText("Which tunnel should carry it?", color = tokens.text.color, weight = FontWeight.Medium)
                Choice(
                    "PPM's tunnel",
                    "Added to the tunnel https://$ppmHostname uses. Review links keep working when PPM's public link is off.",
                    "share-review-choose-shared",
                ) { choose(DomainMode.SHARED) }
                Choice("A tunnel of its own", "PPM creates a Cloudflare tunnel just for review links.", "share-review-choose-named") {
                    choose(DomainMode.NAMED)
                }
                ShareButton("Cancel", { choosing = false }, Modifier.fillMaxButtonWidth(), variant = CardButtonVariant.GHOST, testTag = "share-review-choose-cancel")
            }
        }

        if (setup.flow != null) {
            Box(
                Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Top).padding(top = 12.dp)
                    .testTag("share-review-setup"),
            ) {
                ReviewAddressSetupView(setup, ppmHostname, onOpenUrl)
            }
        }
    }
}
