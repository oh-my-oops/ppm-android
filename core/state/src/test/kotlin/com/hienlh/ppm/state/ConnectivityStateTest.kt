package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityStateTest {
    @Test
    fun `device offline shows This phone is offline on phone and This tablet is offline on tablet without polling server`() {
        val state = ConnectivityState(DeviceNetworkState(online = false))
        state.recordServerDown(nowMs = 1_000L)

        val phoneSnap = state.snapshot(nowMs = 2_000L, host = "192.168.1.42", isTablet = false)
        assertEquals(OutageKind.DEVICE_OFFLINE, phoneSnap.kind)
        assertTrue(phoneSnap.showOverlay)
        assertFalse(phoneSnap.shouldPollServer)
        assertEquals("This phone is offline", phoneSnap.title)
        assertEquals(ConnectivityState.DEVICE_OFFLINE_BODY, phoneSnap.body)
        assertEquals("Open network settings", phoneSnap.primaryActionLabel)
        assertEquals("Waiting for network", phoneSnap.statusNote)

        val tabletSnap = state.snapshot(nowMs = 2_000L, host = "192.168.1.42", isTablet = true)
        assertEquals(OutageKind.DEVICE_OFFLINE, tabletSnap.kind)
        assertEquals("This tablet is offline", tabletSnap.title)
        assertFalse(tabletSnap.shouldPollServer)
    }

    @Test
    fun `server down under 15 seconds stays in quiet reconnecting without showing overlay`() {
        val state = ConnectivityState(DeviceNetworkState(online = true, wifi = true))
        state.recordServerDown(nowMs = 10_000L)

        val at5s = state.snapshot(nowMs = 15_000L, host = "192.168.1.42")
        assertEquals(OutageKind.QUIET_RECONNECTING, at5s.kind)
        assertFalse(at5s.showOverlay)
        assertTrue(at5s.showQuietReconnecting)
        assertTrue(at5s.shouldPollServer)
        assertEquals(2_000L, at5s.pollIntervalMs)
        assertEquals("Reconnecting…", at5s.title)

        val at14_9s = state.snapshot(nowMs = 24_999L, host = "192.168.1.42")
        assertFalse(at14_9s.showOverlay)
        assertTrue(at14_9s.showQuietReconnecting)
    }

    @Test
    fun `server down at 15 seconds shows overlay according to host type`() {
        val state = ConnectivityState(DeviceNetworkState(online = true, wifi = true))
        state.recordServerDown(nowMs = 0L)

        // 1. Direct LAN / Tailscale host -> Server Unreachable (A1)
        val lan = state.snapshot(nowMs = 15_000L, host = "192.168.1.42")
        assertEquals(OutageKind.SERVER_UNREACHABLE, lan.kind)
        assertTrue(lan.showOverlay)
        assertEquals("Server Unreachable", lan.title)
        assertEquals(ConnectivityState.SERVER_UNREACHABLE_ADVICE, lan.advice)
        assertFalse(lan.offerOpenLocalNetwork)
        assertFalse(lan.offerOpenPpmCloud)

        // 2. Quick tunnel (*.trycloudflare.com) -> Connection Lost (A2)
        val quick = state.snapshot(
            nowMs = 15_000L,
            host = "quiet-river-41.trycloudflare.com",
            localUrl = "http://192.168.1.42:8080",
        )
        assertEquals(OutageKind.QUICK_TUNNEL_CLOSED, quick.kind)
        assertTrue(quick.showOverlay)
        assertEquals("Connection Lost", quick.title)
        assertTrue(quick.offerOpenLocalNetwork)
        assertTrue(quick.offerOpenPpmCloud)

        // 3. Named tunnel (host == namedHostname) -> Domain Not Responding (A3)
        val named = state.snapshot(
            nowMs = 15_000L,
            host = "ppm.hienlh.dev",
            namedHostname = "ppm.hienlh.dev",
        )
        assertEquals(OutageKind.NAMED_TUNNEL_DOWN, named.kind)
        assertTrue(named.showOverlay)
        assertEquals("Domain Not Responding", named.title)
        assertNull(named.advice)
        assertFalse(named.offerOpenLocalNetwork)
    }

    @Test
    fun `upgrading state shows restarting strip under 15s and falls back to overlay at 15s`() {
        val state = ConnectivityState(DeviceNetworkState(online = true, wifi = true))
        state.setUpgrading(active = true, targetVersion = "0.23.24")
        state.recordServerDown(nowMs = 1_000L)

        val duringRestart = state.snapshot(
            nowMs = 8_000L,
            host = "192.168.1.42",
            serverName = "Workstation",
        )
        assertEquals(OutageKind.UPGRADING_RECONNECTING, duringRestart.kind)
        assertFalse(duringRestart.showOverlay)
        assertTrue(duringRestart.showUpgradingStrip)
        assertEquals("Updating, reconnecting…", duringRestart.title)
        assertEquals("PPM v0.23.24 is starting on Workstation.", duringRestart.body)

        // Past 15 s of outage, the Connection Lost / Server Unreachable overlay takes over
        val past15s = state.snapshot(
            nowMs = 16_000L,
            host = "192.168.1.42",
            serverName = "Workstation",
        )
        assertEquals(OutageKind.SERVER_UNREACHABLE, past15s.kind)
        assertTrue(past15s.showOverlay)
        assertFalse(past15s.showUpgradingStrip)

        // Once server answers again, everything clears
        state.recordServerUp()
        val healthy = state.snapshot(nowMs = 17_000L, host = "192.168.1.42")
        assertEquals(OutageKind.NONE, healthy.kind)
        assertFalse(healthy.showOverlay)
        assertFalse(healthy.showUpgradingStrip)
        assertEquals(10_000L, healthy.pollIntervalMs)
    }
}
