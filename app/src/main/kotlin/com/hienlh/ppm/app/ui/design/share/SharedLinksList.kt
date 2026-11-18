package com.hienlh.ppm.app.ui.design.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.assistant.CardButtonVariant
import com.hienlh.ppm.app.ui.contextMenuGesture
import com.hienlh.ppm.app.ui.settings.SetupButtons
import com.hienlh.ppm.app.ui.settings.fillMaxButtonWidth
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.DesignShareAddress
import com.hienlh.ppm.client.DesignShareChangedFiles
import com.hienlh.ppm.client.DesignShareLimits
import com.hienlh.ppm.client.DesignShareLink
import com.hienlh.ppm.client.DesignSharePatch
import com.hienlh.ppm.client.designShareChanges
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.design.share.ShareChipTone
import com.hienlh.ppm.state.design.share.ShareExpiryResult
import com.hienlh.ppm.state.design.share.ShareLinkAction
import com.hienlh.ppm.state.design.share.choiceForExpiry
import com.hienlh.ppm.state.design.share.expiryFromChoice
import com.hienlh.ppm.state.design.share.shareChangedGroups
import com.hienlh.ppm.state.design.share.shareExpiryLine
import com.hienlh.ppm.state.design.share.shareLinkActions
import com.hienlh.ppm.state.design.share.shareLinkTitle
import com.hienlh.ppm.state.design.share.shareLinkUrl
import com.hienlh.ppm.state.design.share.shareStatusChip
import com.hienlh.ppm.state.design.share.shareVersionLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * The design's share links, newest first, each with its own actions (`shared-links-list.tsx`,
 * `shared-link-row.tsx`, `shared-link-action-panels.tsx`, `share-repin-panel.tsx`,
 * `share-changed-files.tsx`). A chosen action opens its step inline under the row, so a phone's
 * sheet never stacks another sheet.
 */

/** What the rows need from the dialog around them. */
internal class ShareRowEnv(
    val api: ApiClient?,
    val project: String,
    val slug: String,
    /** This PPM's own address, which a link opens at when there is no public one. */
    val localOrigin: String,
    val onToast: (ErrorToastMessage) -> Unit,
    val onCopy: (String) -> Boolean,
    val onOpenUrl: (String) -> Unit,
)

@Composable
internal fun SharedLinksList(links: ShareLinksHolder, address: DesignShareAddress?, env: ShareRowEnv, reveal: String?, onRetry: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val list = links.links
    if (list == null) {
        val error = links.error
        if (error != null) {
            Column(Modifier.testTag("share-links-error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareText(error, color = tokens.error.color)
                ShareButton("Try again", onRetry)
            }
        } else {
            ShareProgressLine("Loading links…")
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("share-links"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShareSectionHeading("Shared links (${list.size} of ${DesignShareLimits.MAX_LINKS_PER_DESIGN})")
        if (list.isEmpty()) {
            ShareText("No links yet. A link opens one saved version of this design for reviewers to comment on.", color = tokens.text3.color)
        }
        for (link in list) {
            key(link.id) { SharedLinkRow(link, address, links, env, reveal == link.id) }
        }
    }
}

/** Where a row's inline step is: an action's, or the files a re-pin changed. */
internal sealed interface ShareRowPanel {
    data class Action(val action: ShareLinkAction) : ShareRowPanel
    data class Repinned(val files: DesignShareChangedFiles?) : ShareRowPanel
}

private fun toneColors(tone: ShareChipTone, tokens: com.hienlh.ppm.design.ThemeTokens): Pair<Rgba, Rgba> = when (tone) {
    ShareChipTone.OK -> tokens.success.opacity(0.15) to tokens.success
    ShareChipTone.WARN -> tokens.warning.opacity(0.15) to tokens.warning
    ShareChipTone.MUTED -> tokens.panel2 to tokens.text3
    ShareChipTone.DANGER -> tokens.error.opacity(0.15) to tokens.error
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedLinkRow(link: DesignShareLink, address: DesignShareAddress?, links: ShareLinksHolder, env: ShareRowEnv, reveal: Boolean) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var panel by remember(link.id) { mutableStateOf<ShareRowPanel?>(null) }
    var menuOpen by remember(link.id) { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val chip = shareStatusChip(link, now)
    val title = shareLinkTitle(link)
    val target = shareLinkUrl(address, link.path, env.localOrigin)
    val reviewerHref = link.path?.let { env.localOrigin.trimEnd('/') + it }
    val actions = shareLinkActions(link, address?.authEnabled ?: true)
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) { if (reveal) bringIntoView.bringIntoView() }
    fun choose(action: ShareLinkAction) {
        menuOpen = false
        panel = ShareRowPanel.Action(action)
    }
    fun copy() {
        val t = target ?: return
        if (env.onCopy(t.url)) {
            env.onToast(
                ErrorToastMessage(
                    "Link copied",
                    if (t.isPublic) null else "There is no public address, so this link opens only where this PPM is reachable from.",
                    kind = ErrorToastMessage.Kind.SUCCESS,
                ),
            )
        } else {
            env.onToast(ErrorToastMessage("Could not copy the link", t.url))
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .testTag("share-link-row-${link.id}")
            .cssBorder(tokens.border.color, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().contextMenuGesture {
                panel = null
                menuOpen = true
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    Modifier.weight(1f),
                    style = shareBody.copy(color = tokens.text.color, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val (fill, ink) = toneColors(chip.tone, tokens)
                BasicText(
                    chip.label,
                    Modifier.testTag("share-link-status").background(fill.color, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = shareCaption.copy(color = ink.color, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            if (target != null) {
                BasicText(target.url, Modifier.testTag("share-link-url"), style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color))
            } else {
                ShareText(
                    if (link.status == "needs-new-code") "The code for this link is gone. Issue a new code to share it again." else "This link no longer opens.",
                    color = tokens.text3.color,
                    style = shareCaption,
                )
            }
            ShareText("${shareVersionLine(link)} · ${shareExpiryLine(link)}", color = tokens.text3.color, style = shareCaption)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link.status == "needs-new-code" && ShareLinkAction.NEW_CODE in actions) {
                    ShareButton("New code", { panel = ShareRowPanel.Action(ShareLinkAction.NEW_CODE) }, icon = ProductIcon.KeyRound, variant = CardButtonVariant.PRIMARY)
                } else {
                    ShareButton("Copy link", ::copy, icon = ProductIcon.Copy, enabled = target != null, testTag = "share-link-copy")
                }
                ShareButton(
                    "Open as reviewer",
                    { reviewerHref?.let(env.onOpenUrl) },
                    icon = ProductIcon.ExternalLink,
                    enabled = reviewerHref != null,
                    testTag = "share-link-open",
                )
                Box(Modifier.semantics { contentDescription = "More actions for $title" }) {
                    ShareButton(
                        "More",
                        {
                            panel = null
                            menuOpen = !menuOpen
                        },
                        icon = ProductIcon.MoreHorizontal,
                        variant = CardButtonVariant.GHOST,
                        testTag = "share-link-more",
                    )
                }
            }
        }
        if (menuOpen) {
            Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Top).padding(top = 8.dp)) {
                val rows = if (mobile) actions.map { listOf(it) } else actions.chunked(2)
                for (row in rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (action in row) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = if (mobile) 44.dp else 32.dp)
                                    .ppmClickable(shape = RoundedCornerShape(6.dp)) { choose(action) }
                                    .testTag("share-action-${action.name.lowercase()}")
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicText(action.label, style = shareBody.copy(color = (if (action.destructive) tokens.error else tokens.text).color))
                            }
                        }
                        if (row.size == 1 && !mobile) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        panel?.let { current ->
            key(current) {
                SharedLinkActionPanel(current, link, links, env, onClose = { panel = null }, onShow = { panel = it })
            }
        }
    }
}

/** The frame of one inline step: its content, then Cancel and the primary action (lowest on a phone). */
@Composable
private fun RowPanel(
    confirm: String? = null,
    busy: Boolean = false,
    destructive: Boolean = false,
    cancelLabel: String = "Cancel",
    onCancel: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier.fillMaxWidth().testTag("share-row-panel").cssBorder(tokens.border.color, sides = com.hienlh.ppm.design.CssSides.Top).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        SetupButtons(
            cancel = { ShareButton(cancelLabel, onCancel, Modifier.fillMaxButtonWidth(), enabled = !busy, testTag = "share-panel-cancel") },
            confirm = {
                if (confirm != null && onConfirm != null) {
                    ShareButton(
                        confirm,
                        onConfirm,
                        Modifier.fillMaxButtonWidth(),
                        variant = if (destructive) CardButtonVariant.DANGER else CardButtonVariant.PRIMARY,
                        busy = busy,
                        testTag = "share-panel-confirm",
                    )
                }
            },
        )
    }
}

@Composable
private fun SharedLinkActionPanel(
    panel: ShareRowPanel,
    link: DesignShareLink,
    links: ShareLinksHolder,
    env: ShareRowEnv,
    onClose: () -> Unit,
    onShow: (ShareRowPanel) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(choiceForExpiry(link.expiresAt, System.currentTimeMillis())) }
    var expiryError by remember { mutableStateOf<String?>(null) }
    val label = rememberTextFieldState(link.label)
    var alsoComments by remember { mutableStateOf(false) }

    fun run(work: suspend () -> Boolean) {
        busy = true
        scope.launch {
            try {
                if (work()) onClose()
            } finally {
                busy = false
            }
        }
    }
    suspend fun purgeComments(): Boolean {
        if (!alsoComments) return true
        val ok = links.deleteComments(link.id)
        if (ok) env.onToast(ErrorToastMessage("The link's comments were deleted", kind = ErrorToastMessage.Kind.SUCCESS))
        return ok
    }
    val body = @Composable { text: String -> ShareText(text, color = tokens.text.color) }

    when (panel) {
        is ShareRowPanel.Repinned -> RowPanel(cancelLabel = "Done", onCancel = onClose) {
            ShareText("Reviewers now see the latest version. Files that differ from the one they had:", color = tokens.text.color, weight = FontWeight.Medium)
            ShareChangedFiles(panel.files)
        }
        is ShareRowPanel.Action -> when (panel.action) {
            ShareLinkAction.EXPIRY -> RowPanel("Save expiry", busy, onCancel = onClose, onConfirm = {
                when (val next = expiryFromChoice(expiry, System.currentTimeMillis())) {
                    is ShareExpiryResult.Invalid -> expiryError = next.error
                    is ShareExpiryResult.Ok -> run { links.update(link.id, DesignSharePatch(expiry = DesignSharePatch.Expiry(next.expiresAt)), "Could not change the expiry") != null }
                }
            }) {
                ShareExpiryPicker(expiry, { expiry = it; expiryError = null }, idPrefix = "share-${link.id}", error = expiryError)
            }
            ShareLinkAction.RENAME -> RowPanel("Save label", busy, onCancel = onClose, onConfirm = {
                run { links.update(link.id, DesignSharePatch(label = label.text.toString().trim()), "Could not rename the link") != null }
            }) {
                ShareTextInput(label, "Label", maxChars = DesignShareLimits.MAX_LABEL_CHARS, testTag = "share-rename-input")
            }
            ShareLinkAction.REPIN -> ShareRepinPanel(link, links, env, onClose, onRepinned = { onShow(ShareRowPanel.Repinned(it)) })
            ShareLinkAction.NEW_CODE -> RowPanel("Issue new code", busy, onCancel = onClose, onConfirm = {
                run { links.update(link.id, DesignSharePatch(newCode = true), "Could not issue a new code") != null }
            }) {
                body("The link's current address stops working. Send the new one to your reviewers.")
            }
            ShareLinkAction.DELETE_COMMENTS -> RowPanel("Delete comments", busy, destructive = true, onCancel = onClose, onConfirm = {
                run {
                    val ok = links.deleteComments(link.id)
                    if (ok) env.onToast(ErrorToastMessage("The link's comments were deleted", kind = ErrorToastMessage.Kind.SUCCESS))
                    ok
                }
            }) {
                body("Deletes every comment thread reviewers started on this link, with their replies. This cannot be undone.")
            }
            ShareLinkAction.REVOKE -> RowPanel("Revoke link", busy, destructive = true, onCancel = onClose, onConfirm = {
                run {
                    if (links.update(link.id, DesignSharePatch(revoke = true), "Could not revoke the link") == null) return@run false
                    // The revoke stands either way; a failed purge has already said so in a toast.
                    purgeComments()
                    true
                }
            }) {
                body("Revoking is final: the link stops opening and cannot be turned back on.")
                ShareCheck(alsoComments, { alsoComments = it }, "Also delete this link's comments", testTag = "share-also-comments")
            }
            ShareLinkAction.DELETE -> RowPanel("Delete link", busy, destructive = true, onCancel = onClose, onConfirm = {
                run {
                    // Comments first: once the row is gone it is no longer in this list to delete them from.
                    if (!purgeComments()) return@run false
                    links.remove(link.id)
                }
            }) {
                body("The link stops opening and leaves this list. Its comment threads stay on the canvas unless you delete them too.")
                ShareCheck(alsoComments, { alsoComments = it }, "Also delete this link's comments", testTag = "share-also-comments")
            }
        }
    }
}

private sealed interface RepinPreview {
    data object Loading : RepinPreview
    data class Ready(val files: DesignShareChangedFiles?) : RepinPreview
    data class Failed(val message: String) : RepinPreview
}

/**
 * "Update to latest version": lists the files reviewers would get that differ from the version
 * they have now, so anything that should not be in the design folder is seen before it is shared.
 * Confirm waits for the list; a list that could not be read is said so and does not block it.
 */
@Composable
private fun ShareRepinPanel(link: DesignShareLink, links: ShareLinksHolder, env: ShareRowEnv, onClose: () -> Unit, onRepinned: (DesignShareChangedFiles?) -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var preview by remember(link.id) { mutableStateOf<RepinPreview>(RepinPreview.Loading) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(env.api, link.id) {
        val client = env.api ?: return@LaunchedEffect
        preview = try {
            RepinPreview.Ready(client.designShareChanges(env.project, env.slug, link.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RepinPreview.Failed(e.message ?: "Could not list the changes")
        }
    }
    val ready = preview as? RepinPreview.Ready
    val nothingChanged = ready?.files != null && ready.files.count == 0
    RowPanel("Update to latest", busy || preview is RepinPreview.Loading, onCancel = onClose, onConfirm = {
        busy = true
        scope.launch {
            val result = links.update(link.id, DesignSharePatch(repin = true), "Could not update the version")
            busy = false
            if (result != null) onRepinned(result.changedFiles)
        }
    }) {
        ShareText(
            "Saves the design as it is now and pins this link to it. Reviewers will see the new version; their " +
                "older pins re-anchor where they can or show as detached.",
            color = tokens.text.color,
        )
        Column(Modifier.testTag("share-repin-preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareText(
                if (nothingChanged) "Nothing changed since this link's version." else "Files reviewers would get that differ from their version:",
                color = tokens.text.color,
                weight = FontWeight.Medium,
            )
            when (val p = preview) {
                RepinPreview.Loading -> ShareProgressLine("Comparing with the live design…")
                is RepinPreview.Failed -> ShareText(p.message, color = tokens.error.color)
                is RepinPreview.Ready -> if (!nothingChanged) ShareChangedFiles(p.files)
            }
        }
    }
}

/** The files reviewers now get that differ from the version they had (`ShareChangedFiles`). */
@Composable
internal fun ShareChangedFiles(files: DesignShareChangedFiles?) {
    val tokens = LocalPpmTokens.current
    if (files == null) {
        ShareText("The version this link was pinned to is no longer on disk, so the changes cannot be listed.")
        return
    }
    val groups = shareChangedGroups(files)
    if (groups.isEmpty()) {
        ShareText("No files changed since the previous version.")
        return
    }
    Column(Modifier.testTag("share-changed-files"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((title, names) in groups) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareText("$title (${names.size})", color = tokens.text3.color, weight = FontWeight.Medium, style = shareCaption)
                val shape = RoundedCornerShape(6.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 128.dp)
                        .background(tokens.bgSolid.color, shape)
                        .cssBorder(tokens.border.color, shape)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    for (name in names) {
                        BasicText(name, Modifier.padding(vertical = 2.dp), style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color))
                    }
                }
            }
        }
        if (files.truncated) ShareText("More files changed than are listed here.", color = tokens.text3.color, style = shareCaption)
    }
}
