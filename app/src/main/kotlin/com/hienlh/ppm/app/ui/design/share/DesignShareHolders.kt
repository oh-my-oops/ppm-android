package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.DesignShareAddress
import com.hienlh.ppm.client.DesignShareLink
import com.hienlh.ppm.client.DesignSharePatch
import com.hienlh.ppm.client.DesignShareUpdateResult
import com.hienlh.ppm.client.designShareAddress
import com.hienlh.ppm.client.designShareCreate
import com.hienlh.ppm.client.designShareDelete
import com.hienlh.ppm.client.designShareDeleteComments
import com.hienlh.ppm.client.designShareUpdate
import com.hienlh.ppm.client.designShares
import com.hienlh.ppm.client.shareHostSetEnabled
import com.hienlh.ppm.client.tunnelSetEnabled
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.design.share.CURRENT_SHARE_VERSION
import com.hienlh.ppm.state.design.share.ShareAddressView
import com.hienlh.ppm.state.design.share.ShareExpiryChoice
import com.hienlh.ppm.state.design.share.ShareExpiryResult
import com.hienlh.ppm.state.design.share.ShareStartAttempt
import com.hienlh.ppm.state.design.share.ShareStartTarget
import com.hienlh.ppm.state.design.share.defaultShareExpiry
import com.hienlh.ppm.state.design.share.expiryFromChoice
import com.hienlh.ppm.state.design.share.newestFirst
import com.hienlh.ppm.state.design.share.shareAddressView
import kotlinx.coroutines.CancellationException

/*
 * The Share dialog's state while it is open (`use-share-address.ts`, `use-share-links.ts`,
 * `new-share-link-form.tsx`). Every action updates the list from the server's answer, so a change
 * shows without a reload; a failure is a toast and the action answers null (create throws
 * instead, so the form can show the reason).
 */

private fun Throwable.text(): String = message ?: toString()

/**
 * Where links open. Starting an address is the same switch Remote Access uses — Review links for
 * the review-only address, Public link for PPM's own: it only writes config and asks the
 * supervisor to act, so the address arrives seconds later and is polled until then.
 */
@Stable
class ShareAddressHolder(private val api: ApiClient?, private val clock: () -> Long = System::currentTimeMillis) {
    var address by mutableStateOf<DesignShareAddress?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var start by mutableStateOf<ShareStartAttempt?>(null)
        private set

    /** The clock the view reads, moved on by every poll so a start that never came up times out. */
    var now by mutableLongStateOf(clock())
        private set

    val view: ShareAddressView get() = shareAddressView(address, loadError, start, now)
    val starting: Boolean get() = view is ShareAddressView.Starting

    suspend fun refresh() {
        val client = api ?: return
        now = clock()
        try {
            val next = client.designShareAddress()
            address = next
            loadError = null
            if (next.url != null && next.kind != "none") start = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the last known address: a failed poll is not a state change.
            loadError = e.text().ifEmpty { "Could not read the public address" }
        }
        now = clock()
    }

    /** Turn the review-only address or PPM's public link on, and wait for its address. */
    suspend fun startAddress(target: ShareStartTarget) {
        val client = api ?: return
        val at = clock()
        now = at
        start = ShareStartAttempt(at, target, null, null)
        try {
            start = if (target == ShareStartTarget.REVIEW) {
                val result = client.shareHostSetEnabled(true)
                ShareStartAttempt(at, target, "sent", if (result.applied) null else result.message)
            } else {
                ShareStartAttempt(at, target, client.tunnelSetEnabled(true)["reload"].string, null)
            }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val what = if (target == ShareStartTarget.REVIEW) "the review-only address" else "the public address"
            start = ShareStartAttempt(at, target, null, "Could not turn $what on: ${e.text()}")
        }
    }

    fun clearStart() {
        start = null
    }
}

/** One design's links, newest first. */
@Stable
class ShareLinksHolder(
    private val api: ApiClient?,
    private val project: String,
    private val slug: String,
    private val onToast: (ErrorToastMessage) -> Unit,
) {
    var links by mutableStateOf<List<DesignShareLink>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private fun toast(title: String, e: Throwable) = onToast(ErrorToastMessage(title, e.text()))

    suspend fun refresh() {
        val client = api ?: return
        try {
            links = client.designShares(project, slug).newestFirst()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.text().ifEmpty { "Could not load the share links" }
        }
    }

    private fun replace(link: DesignShareLink) {
        links = links.orEmpty().map { if (it.id == link.id) link else it }
    }

    /** Throws, so the form can say why. */
    suspend fun create(label: String?, expiresAt: Long?, version: String): DesignShareLink {
        val client = api ?: throw IllegalStateException("Not connected to a server")
        val link = client.designShareCreate(project, slug, label, expiresAt, version)
        links = listOf(link) + links.orEmpty().filter { it.id != link.id }
        return link
    }

    suspend fun update(id: String, patch: DesignSharePatch, failure: String): DesignShareUpdateResult? {
        val client = api ?: return null
        return try {
            client.designShareUpdate(project, slug, id, patch).also { replace(it.link) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(failure, e)
            null
        }
    }

    suspend fun remove(id: String): Boolean {
        val client = api ?: return false
        return try {
            client.designShareDelete(project, slug, id)
            links = links.orEmpty().filter { it.id != id }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Could not delete the link", e)
            false
        }
    }

    suspend fun deleteComments(id: String): Boolean {
        val client = api ?: return false
        return try {
            client.designShareDeleteComments(project, slug, id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Could not delete the link's comments", e)
            false
        }
    }
}

/** The New link part: label, expiry and version. The Create button lives in the dialog's footer. */
@Stable
class NewShareLinkForm(private val links: ShareLinksHolder, private val clock: () -> Long = System::currentTimeMillis) {
    val label = TextFieldState()
    var expiry by mutableStateOf(defaultShareExpiry(clock()))
        private set
    var version by mutableStateOf(CURRENT_SHARE_VERSION)
    var error by mutableStateOf<String?>(null)
        private set
    var creating by mutableStateOf(false)
        private set

    fun chooseExpiry(choice: ShareExpiryChoice) {
        expiry = choice
        error = null
    }

    suspend fun submit(): DesignShareLink? {
        val expiresAt = when (val result = expiryFromChoice(expiry, clock())) {
            is ShareExpiryResult.Invalid -> {
                error = result.error
                return null
            }
            is ShareExpiryResult.Ok -> result.expiresAt
        }
        creating = true
        error = null
        return try {
            links.create(label.text.toString().trim().ifEmpty { null }, expiresAt, version).also {
                label.clearText()
                version = CURRENT_SHARE_VERSION
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.text().ifEmpty { "Could not create the link" }
            null
        } finally {
            creating = false
        }
    }
}
