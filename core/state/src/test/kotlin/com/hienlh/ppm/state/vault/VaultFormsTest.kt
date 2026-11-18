package com.hienlh.ppm.state.vault

import com.hienlh.ppm.protocol.VaultCardInput
import com.hienlh.ppm.protocol.VaultItemMeta
import com.hienlh.ppm.protocol.VaultTiers
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.vaultLooksSecretName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vault's pure rules: password strength, when a tier asks, moves, and card inputs. */
class VaultFormsTest {
    @Test
    fun `a new password needs the tier's length and the same twice`() {
        assertEquals("At least 12 characters.", vaultNewPasswordProblem(VaultTiers.SECURE, "short", "short"))
        assertEquals("At least 16 characters.", vaultNewPasswordProblem(VaultTiers.SEALED, "twelve chars", "twelve chars"))
        assertEquals("The two passwords differ.", vaultNewPasswordProblem(VaultTiers.SECURE, "long enough pw", "long enough px"))
        assertNull(vaultNewPasswordProblem(VaultTiers.SECURE, "long enough pw", "long enough pw"))
    }

    @Test
    fun `strength is a nudge read from length, character classes and repetition`() {
        assertEquals(PasswordStrength(0, "Too short"), vaultPasswordStrength("abc", 12))
        assertEquals(1, vaultPasswordStrength("aaaaaaaaaaaaaaaaaaaaaaaa", 12).score)
        assertEquals("Strong", vaultPasswordStrength("Correct-Horse-Battery-9", 12).label)
        assertTrue(vaultPasswordStrength("abcdefghijkl", 12).score in 1..2)
    }

    @Test
    fun `Sealed always asks, Secure only while Settings' unlock is closed`() {
        assertTrue(vaultAsksFirst(VaultTiers.SEALED, secureOpen = true))
        assertFalse(vaultAsksFirst(VaultTiers.SECURE, secureOpen = true))
        assertTrue(vaultAsksFirst(VaultTiers.SECURE, secureOpen = false))
    }

    @Test
    fun `a move asks for each locked tier involved that is not open`() {
        val item = VaultItemMeta("a", VaultTiers.SECURE, "NI", "ni_number", 0, 0)
        assertFalse(vaultMovePickable(item, VaultTiers.SECURE, true, true))
        assertFalse(vaultMovePickable(item, VaultTiers.SEALED, true, false))
        assertTrue(vaultMovePickable(item, VaultTiers.PUBLIC, true, false))
        assertEquals(listOf(VaultTiers.SEALED), vaultMoveAsks(VaultTiers.SECURE, VaultTiers.SEALED, secureOpen = true, forceSecure = false))
        assertEquals(listOf(VaultTiers.SECURE, VaultTiers.SEALED), vaultMoveAsks(VaultTiers.SECURE, VaultTiers.SEALED, secureOpen = true, forceSecure = true))
        assertEquals(emptyList<String>(), vaultMoveAsks(VaultTiers.PUBLIC, VaultTiers.SECURE, secureOpen = true, forceSecure = false))
        assertEquals("Delete all 1 item in this tier for good.", vaultDeleteTierAcknowledge(1))
        assertEquals("Delete all 3 items in this tier for good.", vaultDeleteTierAcknowledge(3))
    }

    @Test
    fun `a card's input is read field by field, and anything else is not a vault card`() {
        val secure = VaultCardInput.from(
            "ppm:vault",
            WireJson.parse("""{"kind":"read-secure","requester":{"sessionTitle":"Taxes","assistant":false,"fromTelegram":true},"purpose":"Fill the form","items":[{"id":"a","name":"NI","kind":"ni_number"},{"id":"b","name":"bad","kind":"x"}],"requested":["a","zz"],"attemptsLeft":4,"lockedUntil":0}"""),
        )
        secure as VaultCardInput.ReadSecure
        assertEquals("Taxes", secure.requester.label)
        assertTrue(secure.requester.fromTelegram)
        assertEquals(listOf("a"), secure.items.map { it.id })
        assertEquals(listOf("a", "zz"), secure.requested)

        val sealed = VaultCardInput.from("ppm:vault", WireJson.parse("""{"kind":"read-sealed","requester":{"assistant":true},"item":{"id":"p","name":"Passport","kind":"passport"},"allowGiveToAi":true}"""))
        sealed as VaultCardInput.ReadSealed
        assertEquals("PPM Assistant", sealed.requester.label)
        assertTrue(sealed.allowGiveToAi)

        val propose = VaultCardInput.from("ppm:vault", WireJson.parse("""{"kind":"propose","requester":{},"name":"Home","itemKind":"address","suggestedTier":"sealed","hasPrefill":true,"enabled":{"secure":true,"sealed":false}}"""))
        propose as VaultCardInput.Propose
        assertEquals("This chat", propose.requester.label)
        assertFalse(propose.tierOn(VaultTiers.SEALED))
        assertTrue(propose.tierOn(VaultTiers.PUBLIC))

        assertNull(VaultCardInput.from("ppm:db_execute", WireJson.parse("""{"kind":"read-secure"}""")))
        assertNull(VaultCardInput.from("ppm:vault", WireJson.parse("""{"kind":"read-sealed","item":{"id":"p"}}""")))
    }

    @Test
    fun `a name that looks like a secret is flagged for the public tier`() {
        assertTrue(vaultLooksSecretName("Bank PIN"))
        assertTrue(vaultLooksSecretName("Số CCCD"))
        assertFalse(vaultLooksSecretName("Home address"))
    }
}
