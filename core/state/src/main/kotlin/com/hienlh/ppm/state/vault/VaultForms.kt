package com.hienlh.ppm.state.vault

import com.hienlh.ppm.protocol.VaultItemMeta
import com.hienlh.ppm.protocol.VaultTiers

/*
 * The pure halves of Settings → Vault and the vault's chat cards (`vault-password-strength.ts`,
 * `vault-new-password-fields.tsx`, `vault-move-dialog.tsx`), so they are tested without a screen.
 */

/** A rough strength reading for a new password, 0 (too short) to 4 (strong). A nudge, not a gate. */
data class PasswordStrength(val score: Int, val label: String)

private val STRENGTH_LABELS = listOf("Too short", "Weak", "Fair", "Good", "Strong")

/** `passwordStrength`: length is counted in code points, as `[...password]` counts it. */
fun vaultPasswordStrength(password: String, minLength: Int): PasswordStrength {
    val length = password.codePointCount(0, password.length)
    if (length < minLength) return PasswordStrength(0, STRENGTH_LABELS[0])
    val classes = listOf(Regex("[a-z]"), Regex("[A-Z]"), Regex("\\d"), Regex("[^A-Za-z0-9]")).count { it.containsMatchIn(password) }
    // Mostly one repeated character reads as weak whatever its length (`new Set(password)` counts UTF-16 units).
    val distinct = password.toSet().size
    var score = 1
    if (length >= minLength + 4 || classes >= 3) score++
    if (length >= minLength * 1.5 && classes >= 2) score++
    if (length >= minLength * 2 || (length >= minLength + 4 && classes >= 4)) score++
    if (distinct < minOf(6.0, length / 2.0)) score = 1
    val s = minOf(4, score)
    return PasswordStrength(s, STRENGTH_LABELS[s])
}

/** Why a new password typed twice cannot be saved yet, or null when it can (`newPasswordProblem`). */
fun vaultNewPasswordProblem(tier: String, password: String, confirm: String): String? {
    val min = VaultTiers.minPasswordLength(tier)
    if (password.codePointCount(0, password.length) < min) return "At least $min characters."
    if (password != confirm) return "The two passwords differ."
    return null
}

/**
 * Whether an action on [tier] asks for its password before it is tried: always for Sealed, for
 * Secure only while this app's Settings unlock is not open.
 */
fun vaultAsksFirst(tier: String, secureOpen: Boolean): Boolean = tier == VaultTiers.SEALED || !secureOpen

/** The tiers an item may move to: another tier that is on. */
fun vaultMovePickable(item: VaultItemMeta, tier: String, secureEnabled: Boolean, sealedEnabled: Boolean): Boolean =
    tier != item.tier && (tier == VaultTiers.PUBLIC || (tier == VaultTiers.SECURE && secureEnabled) || (tier == VaultTiers.SEALED && sealedEnabled))

/**
 * The locked tiers whose password a move asks for: the source's and the target's, Secure only
 * while it is not open here (or the server said the open unlock ended, [forceSecure]).
 */
fun vaultMoveAsks(from: String, to: String, secureOpen: Boolean, forceSecure: Boolean): List<String> =
    listOf(from, to).filter(VaultTiers::isLocked).distinct().filter { it == VaultTiers.SEALED || !secureOpen || forceSecure }

/** "Delete all 3 items in this tier for good." */
fun vaultDeleteTierAcknowledge(count: Int): String = "Delete all $count item${if (count == 1) "" else "s"} in this tier for good."
