package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ui.QrCodeBox
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.ConfirmDialogCard
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.SectionLabel
import com.hienlh.ppm.design.kit.Sheet

/**
 * Cloud & Share bottom sheet on phone (`designs/ppm-mobile-tablet-redesign/phone/7-tools/index.html` Section 5,
 * Requirement 14.2), drawn from [PhoneCloudShareState], which holds only what the server answered:
 * - PPM Cloud account (device-code sign-in, sign out with confirmation)
 * - this machine's link to the cloud (link, unlink with confirmation) and its permanent link name
 * - the named tunnel's own domain and a temporary link beside it, when the named tunnel serves PPM
 * - the local network address, the public URL with its QR code, and Start / Stop sharing.
 */
@Composable
fun PhoneCloudShareSheet(
    state: PhoneCloudShareState,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onSaveSlug: () -> Unit,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    onStartTempLink: () -> Unit,
    onStopTempLink: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    /** The phone reaches PPM through the public URL itself, so stopping it would cut this phone off. */
    connectedThroughShare: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val busy = state.busy != null

    Sheet(
        title = "Cloud & Share",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        applyWindowInsets = false,
        modifier = modifier.testTag("phone-cloud-share-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                BasicText(
                    text = "Loading…",
                    style = ppmText(13f, FontWeight.Normal).copy(color = tokens.text2.color),
                    modifier = Modifier.testTag("phone-cloud-loading"),
                )
                return@Column
            }

            // Account row
            val flow = state.deviceCodeFlow
            when {
                state.loggedIn -> ListRow(
                    title = state.cloud?.email?.takeIf { it.isNotBlank() } ?: "Signed in",
                    subtitle = "Signed in to PPM Cloud",
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-cloud-account-row"),
                    trailingContent = {
                        Chip(
                            label = if (state.busy == "signout") "Signing out…" else "Sign out",
                            enabled = !busy,
                            onClick = { state.confirmSignOut = true },
                            modifier = Modifier.testTag("phone-cloud-signout-btn"),
                        )
                    },
                )
                flow.userCode.isNotBlank() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone-cloud-device-code"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        text = "Open the link below and enter the code:",
                        style = ppmText(12.5f, FontWeight.Normal).copy(color = tokens.text2.color),
                    )
                    BasicText(
                        text = flow.userCode,
                        style = ppmText(24f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.accent.color),
                    )
                    if (flow.verificationUri.isNotBlank()) {
                        BasicText(
                            text = flow.verificationUri,
                            style = ppmText(12.5f, FontWeight.Medium).copy(color = tokens.accent.color),
                            modifier = Modifier
                                .ppmClickable(onClickLabel = "Open sign-in page", onClick = { onOpenUrl(flow.verificationUri) })
                                .testTag("phone-cloud-verification-link"),
                        )
                    }
                    if (flow.status == "waiting") {
                        BasicText(
                            text = "Waiting for verification…",
                            style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text2.color),
                        )
                    }
                }
                else -> ListRow(
                    title = "PPM Cloud account",
                    subtitle = when {
                        state.shareUrl != null && state.namedLive -> "This link lives on your own domain. Sign in to keep your devices in sync."
                        state.shareUrl != null -> "This link changes when PPM restarts. Sign in for a permanent one."
                        else -> "Sign in with a device code"
                    },
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-cloud-account-row"),
                    trailingContent = {
                        Chip(
                            label = if (state.busy == "signin") "Starting…" else "Sign in",
                            enabled = !busy,
                            onClick = onSignIn,
                            modifier = Modifier.testTag("phone-cloud-signin-btn"),
                        )
                    },
                )
            }

            if (state.confirmSignOut) {
                ConfirmDialogCard(
                    title = "Sign out of PPM Cloud?",
                    message = if (state.linked) {
                        "This will also unlink this machine from your cloud dashboard."
                    } else {
                        "You can sign in again at any time."
                    },
                    confirmLabel = "Sign out",
                    destructive = true,
                    onConfirm = onSignOut,
                    onDismiss = { state.confirmSignOut = false },
                    modifier = Modifier.testTag("phone-cloud-signout-confirm"),
                )
            }

            // Linked device row
            if (state.loggedIn) {
                if (state.linked) {
                    ListRow(
                        title = state.cloud?.deviceName?.takeIf { it.isNotBlank() } ?: "This machine",
                        subtitle = "Linked to PPM Cloud",
                        dividerBottom = true,
                        modifier = Modifier.testTag("phone-cloud-device-row"),
                        trailingContent = {
                            Chip(
                                label = if (state.busy == "unlink") "Unlinking…" else "Unlink",
                                enabled = !busy,
                                onClick = { state.confirmUnlink = true },
                                modifier = Modifier.testTag("phone-cloud-unlink-btn"),
                            )
                        },
                    )
                } else {
                    ActionPrimaryButton(
                        label = if (state.busy == "link") "Linking…" else "Link this machine",
                        enabled = !busy,
                        onClick = onLink,
                        modifier = Modifier.testTag("phone-cloud-link-btn"),
                    )
                }
            }

            if (state.confirmUnlink) {
                ConfirmDialogCard(
                    title = "Unlink this device?",
                    message = "It will no longer appear on your cloud dashboard.",
                    confirmLabel = "Unlink",
                    destructive = true,
                    onConfirm = onUnlink,
                    onDismiss = { state.confirmUnlink = false },
                    modifier = Modifier.testTag("phone-cloud-unlink-confirm"),
                )
            }

            // Permanent link row + inline editor (only a linked machine has one)
            if (state.linked) {
                when {
                    state.aliasUnreadable -> BasicText(
                        text = "Couldn't check this machine's permanent link. Open the cloud dashboard, or sign in again.",
                        style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text2.color),
                        modifier = Modifier.testTag("phone-cloud-slug-unreadable"),
                    )
                    state.editingSlug -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phone-cloud-slug-editor"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            text = "${state.cloudHost}/",
                            style = ppmText(12f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text2.color),
                        )
                        PhoneBottomSearchField(
                            value = state.slugDraft,
                            placeholder = "my-mac",
                            onValueChange = { state.slugDraft = it },
                            modifier = Modifier.testTag("phone-cloud-slug-input"),
                        )
                        BasicText(
                            text = state.slugError ?: state.slugValidationMessage,
                            style = ppmText(11.5f, FontWeight.Normal).copy(
                                color = if (state.slugError == null && state.isSlugDraftValid) tokens.text2.color else tokens.error.color,
                            ),
                            modifier = Modifier.testTag("phone-cloud-slug-hint"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                label = "Cancel",
                                onClick = { state.cancelEditSlug() },
                                modifier = Modifier.testTag("phone-cloud-slug-cancel"),
                            )
                            ActionPrimaryButton(
                                label = if (state.busy == "slug") "Saving…" else "Save",
                                enabled = state.isSlugDraftValid && !busy,
                                onClick = onSaveSlug,
                                modifier = Modifier.testTag("phone-cloud-slug-save"),
                            )
                        }
                    }
                    else -> {
                        val permanent = state.permanentUrl
                        ListRow(
                            title = "Permanent link",
                            subtitle = state.slug?.takeIf { it.isNotBlank() }?.let { "${state.cloudHost}/$it" }
                                ?: "Choose a permanent link name",
                            subtitleMono = permanent != null,
                            dividerBottom = true,
                            onClick = permanent?.let { url -> { onOpenUrl(url) } },
                            modifier = Modifier.testTag("phone-cloud-permanent-row"),
                            trailingContent = {
                                Chip(
                                    label = if (permanent != null) "Edit" else "Choose",
                                    enabled = !busy,
                                    onClick = { state.startEditSlug() },
                                    modifier = Modifier.testTag("phone-cloud-slug-edit-btn"),
                                )
                            },
                        )
                    }
                }
            }

            // The named tunnel's own domain, and a temporary quick link beside it
            val named = state.namedHostname
            if (state.namedLive && named != null) {
                ListRow(
                    title = "Your domain",
                    subtitle = named,
                    subtitleMono = true,
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-cloud-named-row"),
                    trailingContent = {
                        if (state.tempUrl.isNullOrBlank()) {
                            Chip(
                                label = if (state.busy == "temp") "Starting…" else "Temp link",
                                enabled = !busy && state.localUrl != null,
                                onClick = onStartTempLink,
                                modifier = Modifier.testTag("phone-cloud-temp-start"),
                            )
                        } else {
                            Chip(
                                label = if (state.busy == "temp") "Stopping…" else "Stop temp link",
                                enabled = !busy,
                                onClick = onStopTempLink,
                                modifier = Modifier.testTag("phone-cloud-temp-stop"),
                            )
                        }
                    },
                )
            }

            // Local network URL row
            state.localUrl?.let { local ->
                ListRow(
                    title = "Local network",
                    subtitle = local,
                    subtitleMono = true,
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-cloud-local-row"),
                    trailingContent = {
                        Chip(
                            label = "Copy",
                            onClick = { onCopyUrl(local) },
                            modifier = Modifier.testTag("phone-cloud-copy-local"),
                        )
                    },
                )
            }

            // Public URL + QR code
            SectionLabel(text = "Public URL")
            val shareUrl = state.shareUrl
            if (shareUrl != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QrCodeBox(
                        value = shareUrl,
                        size = 168.dp,
                        modifier = Modifier.testTag("phone-cloud-qr-box"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = shareUrl,
                            style = ppmText(12.5f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.accent.color),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phone-cloud-public-url"),
                        )
                        Chip(
                            label = "Copy",
                            onClick = { onCopyUrl(shareUrl) },
                            modifier = Modifier.testTag("phone-cloud-copy-public"),
                        )
                    }
                    when {
                        // A named tunnel belongs to the supervisor; the quick-tunnel stop does not apply to it.
                        state.namedLive -> Unit
                        connectedThroughShare -> BasicText(
                            text = "This phone is connected through this link. Stop sharing from another device.",
                            style = ppmText(12f, FontWeight.Normal).copy(color = tokens.text2.color),
                            modifier = Modifier.testTag("phone-cloud-stop-unavailable"),
                        )
                        else -> ActionPrimaryButton(
                            label = if (state.busy == "stop") "Stopping…" else "Stop Sharing",
                            destructive = true,
                            enabled = !busy,
                            onClick = { state.confirmStopSharing = true },
                            modifier = Modifier.testTag("phone-cloud-stop-sharing-btn"),
                        )
                    }
                }
            } else {
                ActionPrimaryButton(
                    label = if (state.busy == "start") "Starting tunnel…" else "Start Sharing",
                    enabled = !busy,
                    onClick = onStartSharing,
                    modifier = Modifier.testTag("phone-cloud-start-sharing-btn"),
                )
            }

            if (state.confirmStopSharing) {
                ConfirmDialogCard(
                    title = "Stop sharing?",
                    message = "The public link stops working for everyone using it.",
                    confirmLabel = "Stop sharing",
                    destructive = true,
                    onConfirm = onStopSharing,
                    onDismiss = { state.confirmStopSharing = false },
                    modifier = Modifier.testTag("phone-cloud-stop-confirm"),
                )
            }

            val tempUrl = state.tempUrl
            if (!tempUrl.isNullOrBlank() && tempUrl != shareUrl) {
                ListRow(
                    title = "Temporary link",
                    subtitle = tempUrl,
                    subtitleMono = true,
                    dividerBottom = true,
                    modifier = Modifier.testTag("phone-cloud-temp-row"),
                    trailingContent = {
                        Chip(label = "Copy", onClick = { onCopyUrl(tempUrl) })
                    },
                )
            }

            if (state.linked) {
                val dashboard = (state.cloud?.cloudUrl ?: "").trimEnd('/') + "/dashboard"
                Chip(
                    label = "Open Cloud Dashboard",
                    onClick = { onOpenUrl(dashboard) },
                    modifier = Modifier.testTag("phone-cloud-dashboard"),
                )
            }

            state.error?.let { msg ->
                BasicText(
                    text = msg,
                    style = ppmText(12.5f, FontWeight.Normal).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("phone-cloud-error"),
                )
            }
        }
    }
}
