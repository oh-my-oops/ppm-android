package com.hienlh.ppm.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSafetyTest {
    @Test
    fun `loopback addresses do not trigger plain HTTP warning`() {
        val loopbacks = listOf(
            "localhost:8080",
            "http://localhost:8080",
            "http://dev.localhost:3210",
            "127.0.0.1:8080",
            "http://127.0.0.1:8080",
            "http://127.255.255.254:8080",
            "http://[::1]:8080",
            "http://0.0.0.0:8080",
        )
        for (address in loopbacks) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals("Expected LOOPBACK for $address", AddressSafety.HostKind.LOOPBACK, inspection!!.hostKind)
            assertFalse("Loopback $address should not warn on HTTP", inspection.needsPlainHttpWarning)
            assertFalse(AddressSafety.needsPlainHttpWarning(address))
            assertTrue(AddressSafety.isSafeForPlainHttp(address))
        }
    }

    @Test
    fun `private RFC1918 LAN addresses do not trigger plain HTTP warning`() {
        val privateLan = listOf(
            "10.0.0.1:8080",
            "http://10.255.255.254:8080",
            "http://172.16.0.1:8080",
            "http://172.24.10.5:8080",
            "http://172.31.255.254:8080",
            "192.168.1.42:8080",
            "http://192.168.0.10:3210",
            "http://169.254.10.20:8080",
            "http://macbook.local:8080",
            "http://server.home.arpa:8080",
        )
        for (address in privateLan) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals("Expected PRIVATE_LAN for $address", AddressSafety.HostKind.PRIVATE_LAN, inspection!!.hostKind)
            assertFalse("Private LAN $address should not warn on HTTP", inspection.needsPlainHttpWarning)
            assertFalse(AddressSafety.needsPlainHttpWarning(address))
        }
    }

    @Test
    fun `addresses just outside RFC1918 172_16_12 and 192_168_16 are treated as public`() {
        val publicIps = listOf(
            "http://172.15.255.254:8080",
            "http://172.32.0.1:8080",
            "http://192.169.1.1:8080",
            "http://11.0.0.1:8080",
        )
        for (address in publicIps) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals("Expected PUBLIC for $address", AddressSafety.HostKind.PUBLIC, inspection!!.hostKind)
            assertTrue("Public IP $address over HTTP must warn", inspection.needsPlainHttpWarning)
            assertTrue(AddressSafety.needsPlainHttpWarning(address))
        }
    }

    @Test
    fun `tailscale CGNAT 100_64_10 and ts_net addresses do not trigger plain HTTP warning`() {
        val tailscaleAddresses = listOf(
            "100.64.0.1:8080",
            "http://100.101.44.8:8080",
            "http://100.127.255.254:8080",
            "http://workstation.tailnet-123.ts.net:8080",
            "workstation.ts.net:3210",
            "http://[fd7a:115c:a1e0::1]:8080",
        )
        for (address in tailscaleAddresses) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals("Expected TAILSCALE for $address", AddressSafety.HostKind.TAILSCALE, inspection!!.hostKind)
            assertFalse("Tailscale $address should not warn on HTTP", inspection.needsPlainHttpWarning)
            assertFalse(AddressSafety.needsPlainHttpWarning(address))
        }
    }

    @Test
    fun `addresses outside 100_64_10 CGNAT range are treated as public`() {
        val nonTailscale100 = listOf(
            "http://100.63.255.255:8080",
            "http://100.128.0.1:8080",
            "http://100.200.1.1:8080",
        )
        for (address in nonTailscale100) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals("Expected PUBLIC for $address", AddressSafety.HostKind.PUBLIC, inspection!!.hostKind)
            assertTrue("Non-CGNAT $address over HTTP must warn", inspection.needsPlainHttpWarning)
        }
    }

    @Test
    fun `public HTTP addresses warn while HTTPS addresses do not`() {
        val publicHttp = listOf(
            "http://203.0.113.24:8080",
            "203.0.113.24:8080",
            "http://build.nxsys.dev:8080",
            "http://ppm.example.com",
        )
        for (address in publicHttp) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals(AddressSafety.HostKind.PUBLIC, inspection!!.hostKind)
            assertFalse(inspection.isHttps)
            assertTrue("Expected plain HTTP warning for $address", inspection.needsPlainHttpWarning)
            assertTrue(AddressSafety.isInsecurePublicHttp(address))
            assertFalse(AddressSafety.isSafeForPlainHttp(address))
        }

        val publicHttps = listOf(
            "https://203.0.113.24:8080",
            "https://build.nxsys.dev",
            "https://quiet-river-41.trycloudflare.com",
        )
        for (address in publicHttps) {
            val inspection = AddressSafety.inspect(address)
            assertNotNull("Expected valid inspection for $address", inspection)
            assertEquals(AddressSafety.HostKind.PUBLIC, inspection!!.hostKind)
            assertTrue(inspection.isHttps)
            assertFalse("HTTPS address $address must not warn", inspection.needsPlainHttpWarning)
            assertFalse(AddressSafety.isInsecurePublicHttp(address))
            assertTrue(AddressSafety.isSafeForPlainHttp(address))
        }
    }

    @Test
    fun `invalid or empty addresses return null inspection and do not warn`() {
        assertNull(AddressSafety.inspect(""))
        assertNull(AddressSafety.inspect("   "))
        assertNull(AddressSafety.inspect("ftp://example.com"))
        assertNull(AddressSafety.inspect("http://"))
        assertFalse(AddressSafety.needsPlainHttpWarning(""))
        assertFalse(AddressSafety.needsPlainHttpWarning("not a url"))
    }
}
