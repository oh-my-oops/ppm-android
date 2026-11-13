package com.hienlh.ppm.app.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * "Show me this account in Settings" (`account-focus.ts`): the chat's Switch account asks for one
 * account, and the Accounts pane opens that provider's tab and scrolls the account's card into
 * view. The request is kept until a pane takes it, because the pane is usually not composed yet
 * when it is made (Settings opens right after); a pane already up sees [pending] change instead.
 */
data class AccountFocusRequest(val provider: String, val accountId: String, val seq: Long)

object AccountFocus {
    private var seq = 0L

    /** The request not yet acted on. */
    var pending by mutableStateOf<AccountFocusRequest?>(null)
        private set

    fun request(provider: String, accountId: String) {
        pending = AccountFocusRequest(provider, accountId, ++seq)
    }

    /** The request not yet acted on, which is then cleared (`takeAccountFocus`). */
    fun take(): AccountFocusRequest? = pending.also { pending = null }
}

/** A card's part in [AccountFocus]: what scrolls it into view, and whether it is ringed now. */
class AccountReveal(val requester: BringIntoViewRequester, val flash: Boolean)

/**
 * Once the card of [id] is drawn while [focus] names it, it is scrolled into view and ringed for
 * 1.8 s (`revealAccountCard`), so the eye lands on it.
 */
@Composable
fun rememberAccountReveal(focus: AccountFocusRequest?, id: String): AccountReveal {
    val requester = remember { BringIntoViewRequester() }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(focus, id) {
        if (focus?.accountId != id) return@LaunchedEffect
        // After the frame that laid the card out, so it has a place to be brought to.
        delay(50)
        runCatching { requester.bringIntoView() }
        flash = true
        delay(1800)
        flash = false
    }
    return AccountReveal(requester, flash)
}
