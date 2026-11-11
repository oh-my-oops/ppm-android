package com.hienlh.ppm.app.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareSheet
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareState
import com.hienlh.ppm.client.ApiClient
import kotlinx.coroutines.launch
import java.net.URI

/** True when the app reaches PPM through [shareUrl] itself, so stopping that link would cut this phone off. */
internal fun connectedThroughShareUrl(baseUrl: String?, shareUrl: String?): Boolean {
    if (baseUrl.isNullOrBlank() || shareUrl.isNullOrBlank()) return false
    val a = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
    val b = runCatching { URI(shareUrl).host }.getOrNull() ?: return false
    return a.equals(b, ignoreCase = true)
}

/**
 * Cloud & Share on phone, wired to the server as the pre-redesign `CloudSharePopover`: status from `/api/cloud/status`
 * and `/api/tunnel`, device-code sign-in, sign out, link / unlink, the permanent link name, Start and Stop sharing.
 */
@Composable
internal fun PhoneCloudShareAreaHost(
    model: AppModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = model.api
    val state = remember(api) { PhoneCloudShareState() }

    LaunchedEffect(api) {
        if (api != null) state.load(api) else state.loading = false
    }
    // Device-code sign-in: poll until the code is approved, expires or the sheet closes.
    LaunchedEffect(api, state.deviceCodeFlow.deviceCode, state.deviceCodeFlow.status) {
        if (api != null) state.pollDeviceCode(api)
    }

    fun perform(block: suspend PhoneCloudShareState.(ApiClient) -> Unit) {
        val client = model.api ?: return
        scope.launch { state.block(client) }
    }

    PhoneCloudShareSheet(
        state = state,
        onDismiss = onDismiss,
        onSignIn = { perform { signIn(it) } },
        onSignOut = { perform { signOut(it) } },
        onLink = { perform { link(it) } },
        onUnlink = { perform { unlink(it) } },
        onSaveSlug = { perform { saveSlug(it) } },
        onStartSharing = { perform { startSharing(it) } },
        onStopSharing = { perform { stopSharing(it) } },
        onStartTempLink = { perform { startTempLink(it) } },
        onStopTempLink = { perform { stopTempLink(it) } },
        onCopyUrl = { url ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("PPM URL", url))
            model.toast(ErrorToastMessage("Link copied", kind = ErrorToastMessage.Kind.SUCCESS))
        },
        onOpenUrl = { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { model.toast(ErrorToastMessage("No browser is available to open this link")) }
        },
        connectedThroughShare = connectedThroughShareUrl(api?.baseUrl?.toString(), state.shareUrl),
    )
}
