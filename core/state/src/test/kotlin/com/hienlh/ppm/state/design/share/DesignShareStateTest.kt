package com.hienlh.ppm.state.design.share

import com.hienlh.ppm.client.DesignShareAddress
import com.hienlh.ppm.client.DesignShareChangedFiles
import com.hienlh.ppm.client.DesignShareLink
import com.hienlh.ppm.client.DesignShareVersion
import com.hienlh.ppm.client.ShareHostApplied
import com.hienlh.ppm.client.ShareHostStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-share-address-state.test.ts, design-share-expiry.test.ts and design-share-link-status.test.ts, case by case. */
class DesignShareStateTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    private fun address(kind: String = "none", url: String? = null, via: String = "ppm", enabled: Boolean = false, auth: Boolean = true, warning: String? = null) =
        DesignShareAddress(kind, url, warning, via, enabled, auth)

    private fun link(status: String = "active", label: String = "", expiresAt: Long? = null, snapshotCreatedAt: String? = "2026-11-18T01:05:00Z") =
        DesignShareLink("l1", label, "s1", snapshotCreatedAt, now - day, now, expiresAt, null, status, "/s/abc")

    @Test fun addressView() {
        assertEquals(ShareAddressView.Loading, shareAddressView(null, null, null, now))
        assertEquals(ShareAddressView.Error("boom"), shareAddressView(null, "boom", null, now))
        assertEquals(ShareAddressView.SigninOff, shareAddressView(address(auth = false, kind = "named", url = "https://x"), null, null, now))
        assertEquals(
            ShareAddressView.Address(quick = false, url = "https://ppm.example.com", warning = null, reviewOnly = false),
            shareAddressView(address("named", "https://ppm.example.com"), null, null, now),
        )
        assertEquals(
            ShareAddressView.Address(quick = true, url = "https://x.trycloudflare.com", warning = null, reviewOnly = true),
            shareAddressView(address("quick", "https://x.trycloudflare.com", via = "share-host"), null, null, now),
        )
        val start = ShareStartAttempt(now - 1_000, ShareStartTarget.REVIEW, "sent", null)
        assertEquals(ShareAddressView.Starting(ShareStartTarget.REVIEW), shareAddressView(address(), null, start, now))
        assertEquals(
            ShareAddressView.Failed("The review-only address did not come up within a minute.", ShareStartTarget.REVIEW),
            shareAddressView(address(), null, start.copy(at = now - 61_000), now),
        )
        assertEquals(ShareAddressView.Failed("nope", ShareStartTarget.PUBLIC), shareAddressView(address(), null, ShareStartAttempt(now, ShareStartTarget.PUBLIC, null, "nope"), now))
        assertTrue((shareAddressView(address(), null, ShareStartAttempt(now, ShareStartTarget.PUBLIC, "no-supervisor", null), now) as ShareAddressView.Failed).message.startsWith("PPM is running without its supervisor"))
        assertEquals(ShareAddressView.ReviewDown("down"), shareAddressView(address(via = "share-host", warning = "down"), null, null, now))
        assertEquals(ShareAddressView.None(true, null), shareAddressView(address(enabled = true), null, null, now))
    }

    @Test fun blockedAndCopyUrl() {
        assertEquals("Reading the sharing state…", shareBlockedReason(null, 0, 20))
        assertEquals(SIGNIN_OFF_REASON, shareBlockedReason(address(auth = false), 0, 20))
        assertEquals("This design already has 20 share links; delete one first", shareBlockedReason(address(), 20, 20))
        assertNull(shareBlockedReason(address(), 19, 20))
        assertEquals(ShareLinkTarget("https://pub.example.com/s/abc", true), shareLinkUrl(address("named", "https://pub.example.com/"), "/s/abc", "http://10.0.2.2:8081/"))
        assertEquals(ShareLinkTarget("http://10.0.2.2:8081/s/abc", false), shareLinkUrl(address(), "/s/abc", "http://10.0.2.2:8081/"))
        assertNull(shareLinkUrl(address(), null, "http://x"))
    }

    @Test fun expiry() {
        assertEquals(ShareExpiryResult.Ok(null), expiryFromChoice(ShareExpiryChoice(ShareExpiryPreset.NEVER, LocalDate.now()), now, zone))
        assertEquals(ShareExpiryResult.Ok(now + 7 * day), expiryFromChoice(defaultShareExpiry(now, zone), now, zone))
        assertEquals(LocalDate.of(2026, 12, 2), defaultShareExpiry(now, zone).customDate)
        val endOfToday = ZonedDateTime.of(2026, 11, 18, 23, 59, 59, 999_000_000, zone).toInstant().toEpochMilli()
        assertEquals(ShareExpiryResult.Ok(endOfToday), expiryFromChoice(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, LocalDate.of(2026, 11, 18)), now, zone))
        assertEquals(ShareExpiryResult.Invalid("Pick a date that is still ahead"), expiryFromChoice(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, LocalDate.of(2026, 11, 17)), now, zone))
        assertEquals(ShareExpiryResult.Invalid("A link can stay open for at most a year"), expiryFromChoice(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, LocalDate.of(2027, 11, 20)), now, zone))
        assertEquals(LocalDate.of(2026, 11, 18), minCustomDate(now, zone))
        // 366 days ahead is 2027-11-19 10:00; that day's end is past the limit, so the day before.
        assertEquals(LocalDate.of(2027, 11, 18), maxCustomDate(now, zone))
        assertTrue(expiryFromChoice(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, maxCustomDate(now, zone)), now, zone) is ShareExpiryResult.Ok)
        assertEquals(ShareExpiryPreset.NEVER, choiceForExpiry(null, now, zone).preset)
        assertEquals(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, LocalDate.of(2026, 11, 25)), choiceForExpiry(now + 7 * day, now, zone))
        assertEquals(ShareExpiryChoice(ShareExpiryPreset.CUSTOM, LocalDate.of(2026, 11, 18)), choiceForExpiry(now - day, now, zone))
        assertNull(expiresInLabel(null, now))
        assertEquals("Expired", expiresInLabel(now, now))
        assertEquals("Expires in under an hour", expiresInLabel(now + 59 * 60_000, now))
        assertEquals("Expires in 1 hour", expiresInLabel(now + 90 * 60_000 - 1, now))
        assertEquals("Expires in 2 hours", expiresInLabel(now + 90 * 60_000, now))
        assertEquals("Expires in 7 days", expiresInLabel(now + 7 * day - 5_000, now))
        assertEquals("Expires in 1 day", expiresInLabel(now + 24 * 60 * 60_000, now))
    }

    @Test fun linkStatusTitlesAndActions() {
        assertEquals(ShareStatusChip("Expires in 7 days", ShareChipTone.OK), shareStatusChip(link(expiresAt = now + 7 * day), now))
        assertEquals(ShareStatusChip("Active", ShareChipTone.OK), shareStatusChip(link(), now))
        assertEquals(ShareStatusChip("Revoked", ShareChipTone.MUTED), shareStatusChip(link("revoked"), now))
        assertEquals(ShareStatusChip("Needs new code", ShareChipTone.WARN), shareStatusChip(link("needs-new-code"), now))
        assertEquals(ShareStatusChip("Design missing", ShareChipTone.DANGER), shareStatusChip(link("design-missing"), now))
        assertEquals(ShareStatusChip("Paused: sign-in is off", ShareChipTone.WARN), shareStatusChip(link("signin-off"), now))
        assertEquals("Link from Nov 17", shareLinkTitle(link(), zone))
        assertEquals("Client", shareLinkTitle(link(label = "Client"), zone))
        assertEquals("Version from Nov 18, 08:05 AM", shareVersionLine(link(), zone))
        assertEquals("The pinned version is no longer on disk", shareVersionLine(link(snapshotCreatedAt = null), zone))
        assertEquals("Never expires", shareExpiryLine(link(), zone))
        assertEquals("Open until Nov 25, 2026, 10:00 AM", shareExpiryLine(link(expiresAt = now + 7 * day), zone))
        assertEquals(ShareLinkAction.entries, shareLinkActions(link(), authEnabled = true))
        assertEquals(
            listOf(ShareLinkAction.EXPIRY, ShareLinkAction.RENAME, ShareLinkAction.DELETE_COMMENTS, ShareLinkAction.REVOKE, ShareLinkAction.DELETE),
            shareLinkActions(link(), authEnabled = false),
        )
        assertEquals(listOf(ShareLinkAction.RENAME, ShareLinkAction.DELETE_COMMENTS, ShareLinkAction.DELETE), shareLinkActions(link("revoked"), true))
        assertEquals("Nov 18, 04:00 PM, after an AI turn", shareVersionLabel(DesignShareVersion("s", "2026-11-18T09:00:00Z", "turn"), zone))
        assertEquals("Nov 18, 04:00 PM, odd", shareVersionLabel(DesignShareVersion("s", "2026-11-18T09:00:00Z", "odd"), zone))
        assertEquals(
            listOf("Added" to listOf("a"), "Removed" to listOf("r")),
            shareChangedGroups(DesignShareChangedFiles(listOf("a"), listOf("r"), emptyList(), false)),
        )
        val newer = link().copy(id = "l2", createdAt = now)
        assertEquals(listOf("l2", "l1"), listOf(link(), newer).newestFirst().map { it.id })
    }

    @Test fun reviewAddressRules() {
        assertEquals("share.example.com", buildHostname(" Share ", "Example.com"))
        assertNull(validateHostname("share.example.com", "example.com"))
        assertEquals("www is reserved", validateHostname("www.example.com", "example.com"))
        assertEquals("hostname must be exactly one label above the zone", validateHostname("a.b.example.com", "example.com"))
        assertTrue(validateHostname("example.com", "example.com")!!.startsWith("hostname cannot be the zone apex"))
        assertTrue(validateHostname("x.other.com", "example.com")!!.startsWith("hostname must be a subdomain of example.com"))
        assertEquals("invalid DNS label: \"-bad\"", validateHostname("-bad.example.com", "example.com"))
        assertEquals("That is PPM's own address — pick another prefix", reviewHostnameError("ppm.example.com", "example.com", "ppm.example.com"))
        assertEquals("review", reviewPrefixFor("review.example.com", "example.com"))
        assertEquals("share", reviewPrefixFor("review.other.com", "example.com"))
        val status = ShareHostStatus(
            enabled = true, mode = "quick", hostname = null, tunnelName = null, canApply = true, supervised = true,
            running = false, connected = false, url = null, warning = null,
            ppmTunnel = ShareHostStatus.PpmTunnel(false, null, false), certState = "none", authEnabled = true,
        )
        assertEquals("On. Starting…", reviewLinksStatusText(status))
        assertEquals("On. Connecting to Cloudflare…", reviewLinksStatusText(status.copy(running = true)))
        assertEquals("On. Review links open at https://a.b", reviewLinksStatusText(status.copy(connected = true, url = "https://a.b")))
        assertEquals("Off.", reviewLinksStatusText(status.copy(enabled = false)))
        assertEquals(2_000L, shareHostPollMs(status))
        assertEquals(10_000L, shareHostPollMs(status.copy(connected = true)))
        assertEquals("On your domain, on PPM's tunnel.", reviewModeInUse("shared"))
        assertTrue(reviewCannotApplyText(status).contains("ppm restart"))
        assertEquals(AppliedReport("Done — not applied yet", "later", true), appliedReport(ShareHostApplied(false, "later"), "Done"))
        assertEquals(AppliedReport("Done", null, false), appliedReport(ShareHostApplied(true, null), "Done"))
    }
}
