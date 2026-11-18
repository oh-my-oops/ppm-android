package com.hienlh.ppm.state.design.share

import com.hienlh.ppm.client.ShareHostStatus

/*
 * The review-only address's words and rules (`review-links-pane.tsx`, `review-address-card.tsx`,
 * `review-address-setup.tsx`), and the hostname shape the server enforces
 * (`services/named-tunnel/hostname-rules.ts`), so the prefix field validates without a round trip.
 */

/** The prefix a review hostname starts at when none is saved for the zone. */
const val DEFAULT_REVIEW_PREFIX = "share"

/** Combine a typed prefix with the pinned zone (`buildHostname`). */
fun buildHostname(prefix: String, zone: String): String = "${prefix.trim().lowercase()}.${zone.trim().lowercase()}"

private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/** Why [hostname] cannot be used under [zone], or null when it can (`validateHostname`). */
fun validateHostname(hostname: String, zone: String): String? {
    val host = hostname.trim().lowercase()
    val z = zone.trim().lowercase()
    if (host.isEmpty() || z.isEmpty()) return "hostname and zone are required"
    if (host.length > 253) return "hostname exceeds 253 characters"
    if (host == z) return "hostname cannot be the zone apex — a PPM token must never rewrite the zone's own record"
    if (host == "www.$z") return "www is reserved"
    if (!host.endsWith(".$z")) return "hostname must be a subdomain of $z — cloudflared silently mis-routes anything else"
    val prefix = host.substring(0, host.length - z.length - 1)
    if ('.' in prefix) return "hostname must be exactly one label above the zone"
    for (label in host.split('.')) {
        if (!LABEL.matches(label)) return "invalid DNS label: \"$label\""
    }
    return null
}

/** The review setup's own check: the server's rule, and never PPM's own hostname. */
fun reviewHostnameError(hostname: String, zone: String, ppmHostname: String?): String? =
    validateHostname(hostname, zone) ?: if (hostname == ppmHostname) "That is PPM's own address — pick another prefix" else null

/** The prefix a saved hostname in [zone] had, or the default one. */
fun reviewPrefixFor(savedHostname: String?, zone: String): String =
    if (savedHostname != null && savedHostname.endsWith(".$zone")) savedHostname.dropLast(zone.length + 1) else DEFAULT_REVIEW_PREFIX

/** Settings' status line under the switch. */
fun reviewLinksStatusText(status: ShareHostStatus): String = when {
    !status.enabled -> "Off."
    status.connected -> "On. Review links open at ${status.url.orEmpty()}"
    status.running -> "On. Connecting to Cloudflare…"
    else -> "On. Starting…"
}

/** The Share dialog card's line for an address in use (`IN_USE`). */
fun reviewModeInUse(mode: String): String = when (mode) {
    "named" -> "On your domain, with a tunnel of its own."
    "shared" -> "On your domain, on PPM's tunnel."
    else -> "On a temporary address."
}

/** Polled faster while the address is on but not connected: it usually is within seconds. */
fun shareHostPollMs(status: ShareHostStatus?): Long = if (status != null && status.enabled && !status.connected) 2_000L else 10_000L

/** The notice when the switch is on but nothing can apply it. */
fun reviewCannotApplyText(status: ShareHostStatus): String =
    if (status.supervised) "This PPM was started before review addresses existed. Run `ppm restart` to apply these settings."
    else "Review addresses run under `ppm start`. This PPM was started without it, so nothing serves the address."

/** What a change the server saved says, with "not applied yet" when the supervisor did not take it. */
data class AppliedReport(val title: String, val description: String?, val warning: Boolean)

fun appliedReport(applied: com.hienlh.ppm.client.ShareHostApplied, done: String): AppliedReport =
    if (applied.applied) AppliedReport(done, null, warning = false) else AppliedReport("$done — not applied yet", applied.message, warning = true)
