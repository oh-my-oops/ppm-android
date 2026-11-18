package com.hienlh.ppm.app.ui.design.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.state.GlobalEvents
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.settings.rememberShareHostStatus
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.DesignShareLimits
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.state.design.share.SHARE_START_POLL_MS
import com.hienlh.ppm.state.design.share.ShareAddressView
import com.hienlh.ppm.state.design.share.shareBlockedReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Share for review (`design-share-dialog.tsx`): where links open, the review-only address's
 * switch and domain, a new link (label, expiry, version), and the design's existing links with
 * their actions. A centred dialog on a tablet, a bottom sheet on a phone, with Create in the
 * footer. While PPM sign-in is off nothing can be created and existing links read as paused,
 * because the server opens none of them.
 */

private const val DESCRIPTION =
    "Reviewers open a saved version of this design and pin comments on it. They never see the live copy."

/**
 * [localOrigin] is the address this app reaches PPM at, used for a link when there is no public
 * address. [onOpenSettings] takes a Settings category id ("remote-access", "general").
 */
@Composable
fun DesignShareDialog(
    api: ApiClient?,
    events: GlobalEvents?,
    projectName: String,
    slug: String,
    localOrigin: String,
    onDismiss: () -> Unit,
    onToast: (ErrorToastMessage) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        DesignShareSheet(api, events, projectName, slug, localOrigin, onDismiss, onToast, onOpenUrl, onOpenSettings)
    }
}

/** The dialog's sheet, without the window: what the tests render. */
@Composable
internal fun DesignShareSheet(
    api: ApiClient?,
    events: GlobalEvents?,
    projectName: String,
    slug: String,
    localOrigin: String,
    onDismiss: () -> Unit,
    onToast: (ErrorToastMessage) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val toast by rememberUpdatedState(onToast)

    val address = remember(api) { ShareAddressHolder(api) }
    val links = remember(api, projectName, slug) { ShareLinksHolder(api, projectName, slug) { toast(it) } }
    val form = remember(links) { NewShareLinkForm(links) }
    val shareHost = rememberShareHostStatus(api)
    var reveal by remember { mutableStateOf<String?>(null) }

    // Read on open and on every return to the app (the web's window focus).
    LaunchedEffect(address) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { address.refresh() }
    }
    LaunchedEffect(links) { links.refresh() }
    // While an address comes up, poll until it is there or the attempt times out.
    val starting = address.starting
    LaunchedEffect(address, starting) {
        while (starting) {
            delay(SHARE_START_POLL_MS)
            address.refresh()
        }
    }
    // The card polls the review address while it comes up; where links open follows each change.
    val reviewKey = shareHost.status?.let { "${it.enabled}|${it.mode}|${it.connected}|${it.url}" }
    var lastReviewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reviewKey) {
        if (reviewKey == null) return@LaunchedEffect
        if (lastReviewKey != null && lastReviewKey != reviewKey) address.refresh()
        lastReviewKey = reviewKey
    }

    val view = address.view
    val signinOff = view is ShareAddressView.SigninOff
    val blocked = shareBlockedReason(address.address, links.links?.size ?: 0, DesignShareLimits.MAX_LINKS_PER_DESIGN)

    val env = remember(api, projectName, slug, localOrigin, context) {
        ShareRowEnv(
            api = api,
            project = projectName,
            slug = slug,
            localOrigin = localOrigin,
            onToast = { toast(it) },
            onCopy = { text -> copyText(context, text) },
            onOpenUrl = onOpenUrl,
        )
    }

    fun create() {
        if (blocked != null || form.creating) return
        scope.launch {
            val link = form.submit() ?: return@launch
            toast(ErrorToastMessage("Link created", null, kind = ErrorToastMessage.Kind.SUCCESS))
            reveal = link.id
        }
    }

    val mobile = LocalPpmMobile.current
    Sheet(
        title = "Share for review",
        onDismiss = onDismiss,
        forceBottomSheet = mobile,
        tabletWidth = 512.dp,
        footer = {
            if (!mobile) Spacer(Modifier.weight(1f))
            ShareButton("Close", onDismiss, if (mobile) Modifier.weight(1f) else Modifier, testTag = "share-close")
            ShareButton(
                label = if (signinOff) blocked ?: "Create link" else "Create link",
                onClick = ::create,
                modifier = if (mobile) Modifier.weight(1f) else Modifier,
                variant = CardButtonVariant.PRIMARY,
                enabled = blocked == null,
                busy = form.creating,
                testTag = "share-create",
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("share-dialog"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShareText(DESCRIPTION)
            ShareAddressBlock(
                view = view,
                onStart = { target -> scope.launch { address.startAddress(target) } },
                onRetryLoad = { scope.launch { address.refresh() } },
                onOpenSettings = onOpenSettings,
            )
            if (!signinOff) {
                ReviewAddressCard(
                    shareHost = shareHost,
                    onTurnOn = { address.startAddress(com.hienlh.ppm.state.design.share.ShareStartTarget.REVIEW) },
                    onChanged = { scope.launch { address.refresh() } },
                    onToast = { toast(it) },
                    onOpenUrl = onOpenUrl,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareSectionHeading("New link")
                    NewShareLinkFields(form, api, events, projectName, slug)
                    if (blocked != null && form.error == null && address.address != null) {
                        ShareText(blocked, Modifier.testTag("share-blocked"), style = shareCaption)
                    }
                }
            }
            SharedLinksList(links, address.address, env, reveal) { scope.launch { links.refresh() } }
        }
    }
}

private fun copyText(context: Context, text: String): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return try {
        cm.setPrimaryClip(ClipData.newPlainText("Share link", text))
        true
    } catch (_: Exception) {
        false
    }
}
