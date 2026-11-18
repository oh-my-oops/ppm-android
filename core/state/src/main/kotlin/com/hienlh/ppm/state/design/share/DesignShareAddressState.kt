package com.hienlh.ppm.state.design.share

import com.hienlh.ppm.client.DesignShareAddress

/*
 * What the Share dialog's address block shows (`design-share-address-state.ts`), decided from the
 * address route, the start the owner asked for, and the clock. Pure, so every state is tested
 * without a server.
 */

/** Poll interval while an address is starting. */
const val SHARE_START_POLL_MS = 2_000L

/** How long a start may take before the dialog says it did not come up. */
const val SHARE_START_TIMEOUT_MS = 60_000L

const val SIGNIN_OFF_REASON = "Turn on PPM sign-in first"
const val QUICK_ADDRESS_WARNING =
    "This is a temporary address. It changes when PPM or the tunnel restarts, and links you already sent stop working; copy them again then."

/** `review`: the review-only address; `public`: PPM's own public link. */
enum class ShareStartTarget { REVIEW, PUBLIC }

/**
 * A start the owner pressed: when, which address, what the switch answered ([reload]: `sent`,
 * `busy` or `no-supervisor`, null until it answered) and whether the switch itself failed.
 */
data class ShareStartAttempt(val at: Long, val target: ShareStartTarget, val reload: String?, val error: String?)

sealed interface ShareAddressView {
    data object Loading : ShareAddressView
    data class Error(val message: String) : ShareAddressView
    data object SigninOff : ShareAddressView

    /** An address links open at; [reviewOnly]: it is the review-only one, so PPM itself is not public. */
    data class Address(val quick: Boolean, val url: String, val warning: String?, val reviewOnly: Boolean) : ShareAddressView
    data class Starting(val target: ShareStartTarget) : ShareAddressView
    data class Failed(val message: String, val target: ShareStartTarget) : ShareAddressView

    /** No public address; [enabled]: the switch is on but nothing serves it (a dark tunnel). */
    data class None(val enabled: Boolean, val warning: String?) : ShareAddressView

    /** The review-only address is switched on but has no address right now. */
    data class ReviewDown(val warning: String?) : ShareAddressView
}

fun shareAddressView(address: DesignShareAddress?, loadError: String?, start: ShareStartAttempt?, now: Long): ShareAddressView {
    if (address == null) return if (loadError != null) ShareAddressView.Error(loadError) else ShareAddressView.Loading
    if (!address.authEnabled) return ShareAddressView.SigninOff
    val reviewOnly = address.via == "share-host"
    val url = address.url
    if (url != null && address.kind != "none") {
        return ShareAddressView.Address(quick = address.kind != "named", url = url, warning = address.warning, reviewOnly = reviewOnly)
    }
    if (start != null) {
        val target = start.target
        if (start.error != null) return ShareAddressView.Failed(start.error, target)
        if (start.reload == "no-supervisor") {
            return ShareAddressView.Failed(
                "PPM is running without its supervisor, so nothing can start the tunnel now. It starts the next time PPM is started normally.",
                target,
            )
        }
        if (now - start.at <= SHARE_START_TIMEOUT_MS) return ShareAddressView.Starting(target)
        val late = if (target == ShareStartTarget.REVIEW) "The review-only address" else "The temporary address"
        return ShareAddressView.Failed(address.warning ?: "$late did not come up within a minute.", target)
    }
    if (reviewOnly) return ShareAddressView.ReviewDown(address.warning)
    return ShareAddressView.None(address.enabled, address.warning)
}

/** Why a new link cannot be created right now, or null when it can. */
fun shareBlockedReason(address: DesignShareAddress?, linkCount: Int, maxLinks: Int): String? = when {
    address == null -> "Reading the sharing state…"
    !address.authEnabled -> SIGNIN_OFF_REASON
    linkCount >= maxLinks -> "This design already has $maxLinks share links; delete one first"
    else -> null
}

/** The URL a link is copied as, and whether it is the public address's. */
data class ShareLinkTarget(val url: String, val isPublic: Boolean)

/**
 * The public address plus the link's path, or this PPM's own address when there is no public one
 * (it then opens only where this PPM is reachable).
 */
fun shareLinkUrl(address: DesignShareAddress?, path: String?, localOrigin: String): ShareLinkTarget? {
    if (path == null) return null
    val publicUrl = if (address != null && address.kind != "none") address.url else null
    if (publicUrl != null) return ShareLinkTarget(publicUrl.trimEnd('/') + path, true)
    return ShareLinkTarget(localOrigin.trimEnd('/') + path, false)
}
