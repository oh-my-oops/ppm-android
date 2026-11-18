package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.settings.fillMaxButtonWidth
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.design.share.QUICK_ADDRESS_WARNING
import com.hienlh.ppm.state.design.share.SIGNIN_OFF_REASON
import com.hienlh.ppm.state.design.share.ShareAddressView
import com.hienlh.ppm.state.design.share.ShareStartTarget

/*
 * Where share links open (`share-address-block.tsx`): the stable address, the temporary one with
 * its warning, or no public address with the ways to make PPM itself public. The review-only
 * address is switched and set up in the card below this block; this block only says where links
 * open. With PPM sign-in off it says only that sharing waits for sign-in.
 */

private const val REVIEW_ONLY_NOTE = "Only the review pages answer at this address. The rest of PPM stays private."
private const val PPM_ITSELF_NOTE = "This address opens PPM itself, behind its sign-in."

/** Buttons side by side on a tablet, stacked full width on a phone (`flex-col sm:flex-row`). */
@Composable
private fun BlockActions(content: @Composable () -> Unit) {
    if (LocalPpmMobile.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SetUpPermanent(onOpenSettings: (String) -> Unit) {
    ShareButton("Set up a permanent address", { onOpenSettings("remote-access") }, Modifier.fillMaxButtonWidth(), testTag = "share-address-permanent")
}

@Composable
internal fun ShareAddressBlock(
    view: ShareAddressView,
    onStart: (ShareStartTarget) -> Unit,
    onRetryLoad: () -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    when (view) {
        ShareAddressView.Loading -> ShareNoticeBox(ProductIcon.Loader2, "share-address-loading", spinning = true) {
            ShareText("Reading the public address…", color = tokens.text.color)
        }
        is ShareAddressView.Error -> ShareNoticeBox(ProductIcon.AlertTriangle, "share-address-error", NoticeTone.WARN) {
            ShareText("Could not read the public address: ${view.message}", color = tokens.text.color)
            ShareButton("Try again", onRetryLoad, Modifier.fillMaxButtonWidth(), testTag = "share-address-retry")
        }
        ShareAddressView.SigninOff -> ShareNoticeBox(ProductIcon.Lock, "share-address-signin-off", NoticeTone.WARN) {
            ShareText(SIGNIN_OFF_REASON, color = tokens.text.color, weight = FontWeight.Medium)
            ShareText(
                "A share link reaches PPM itself, so with sign-in off anyone holding one could open all of PPM. " +
                    "Existing links are paused until sign-in is back on.",
            )
            ShareButton("Open General", { onOpenSettings("general") }, Modifier.fillMaxButtonWidth(), testTag = "share-address-open-general")
        }
        is ShareAddressView.Address -> ShareNoticeBox(
            if (view.quick) ProductIcon.AlertTriangle else ProductIcon.Globe,
            if (view.quick) "share-address-quick" else "share-address-named",
            if (view.quick || view.warning != null) NoticeTone.WARN else NoticeTone.PLAIN,
        ) {
            ShareText("Links open at", color = tokens.text.color)
            ShareText(view.url, Modifier.testTag("share-address-url"), color = tokens.text.color, weight = FontWeight.Medium)
            view.warning?.let { ShareText(it) }
            if (view.reviewOnly) {
                ShareText(REVIEW_ONLY_NOTE, Modifier.testTag("share-address-review-only"))
            } else {
                ShareText(PPM_ITSELF_NOTE, Modifier.testTag("share-address-ppm-itself"))
            }
            if (view.quick) ShareText(QUICK_ADDRESS_WARNING)
            if (view.quick && !view.reviewOnly) SetUpPermanent(onOpenSettings)
        }
        is ShareAddressView.Starting -> ShareNoticeBox(ProductIcon.Loader2, "share-address-starting", spinning = true) {
            val what = if (view.target == ShareStartTarget.REVIEW) "Starting a review-only address…" else "Starting a temporary public address…"
            ShareText("$what This usually takes a few seconds.", color = tokens.text.color)
        }
        is ShareAddressView.ReviewDown -> ShareNoticeBox(ProductIcon.AlertTriangle, "share-address-review-down", NoticeTone.WARN) {
            ShareText("The review-only address is switched on but not connected right now.", color = tokens.text.color)
            view.warning?.let { ShareText(it) }
            ShareText("Until it is, links open only where this PPM is reachable from.")
        }
        is ShareAddressView.Failed -> ShareNoticeBox(ProductIcon.AlertTriangle, "share-address-failed", NoticeTone.WARN) {
            ShareText(view.message, color = tokens.text.color)
            BlockActions {
                ShareButton(
                    "Try again",
                    { onStart(view.target) },
                    Modifier.fillMaxButtonWidth(),
                    variant = CardButtonVariant.PRIMARY,
                    testTag = "share-address-try-again",
                )
                if (view.target != ShareStartTarget.REVIEW) SetUpPermanent(onOpenSettings)
            }
        }
        is ShareAddressView.None -> {
            val darkTunnel = view.enabled
            ShareNoticeBox(ProductIcon.AlertTriangle, "share-address-none", if (darkTunnel) NoticeTone.WARN else NoticeTone.PLAIN) {
                ShareText(
                    if (darkTunnel) "The public address is switched on but not connected right now." else "There is no public address yet.",
                    color = tokens.text.color,
                )
                view.warning?.let { ShareText(it) }
                ShareText("Until there is one, links open only where this PPM is reachable from.")
                if (!darkTunnel) {
                    ShareText(
                        "A review-only address opens the review pages and nothing else of PPM. A public address " +
                            "opens PPM itself, behind its sign-in.",
                    )
                }
                BlockActions {
                    if (darkTunnel) {
                        ShareButton(
                            "Reconnect the public address",
                            { onStart(ShareStartTarget.PUBLIC) },
                            Modifier.fillMaxButtonWidth(),
                            variant = CardButtonVariant.PRIMARY,
                            testTag = "share-address-reconnect",
                        )
                    } else {
                        ShareButton(
                            "Start a temporary public address",
                            { onStart(ShareStartTarget.PUBLIC) },
                            Modifier.fillMaxButtonWidth(),
                            testTag = "share-address-start-public",
                        )
                    }
                    SetUpPermanent(onOpenSettings)
                }
            }
        }
    }
}
