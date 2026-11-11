package com.hienlh.ppm.app.ui.phone.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ComposerAttachment
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.PREWARM_SETTLE_MILLIS
import com.hienlh.ppm.app.PrewarmAsk
import com.hienlh.ppm.app.ReviewTab
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.LocalSheetContentTop
import com.hienlh.ppm.app.ui.SwitchAccountView
import com.hienlh.ppm.app.ui.SwitchAccountsState
import com.hienlh.ppm.app.ui.rememberTurnChangesHost
import com.hienlh.ppm.chat.AgentSessionOpener
import com.hienlh.ppm.chat.AsyncQuestions
import com.hienlh.ppm.chat.ChatImages
import com.hienlh.ppm.chat.ChatTabs
import com.hienlh.ppm.chat.LocalAgentSessionOpener
import com.hienlh.ppm.chat.LocalAsyncQuestions
import com.hienlh.ppm.chat.LocalChatImages
import com.hienlh.ppm.chat.LocalChatTabs
import com.hienlh.ppm.chat.LocalMarkdownImages
import com.hienlh.ppm.chat.LocalRunInTerminal
import com.hienlh.ppm.chat.LocalToolImages
import com.hienlh.ppm.chat.LocalTurnChanges
import com.hienlh.ppm.chat.MarkdownImages
import com.hienlh.ppm.chat.ReviewPhone
import com.hienlh.ppm.chat.ReviewSheet
import com.hienlh.ppm.chat.ReviewTabContext
import com.hienlh.ppm.chat.ToolImages
import com.hienlh.ppm.client.chatUsage
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ChatReply
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.SessionFileChange
import com.hienlh.ppm.protocol.TurnOptions
import com.hienlh.ppm.protocol.agentTranscriptProvider
import com.hienlh.ppm.state.AgentSessionIdentity
import com.hienlh.ppm.state.ChatController
import com.hienlh.ppm.state.ChatState
import com.hienlh.ppm.state.FooterUsageSummary
import com.hienlh.ppm.state.RejectedSend
import com.hienlh.ppm.state.SessionChangeList
import com.hienlh.ppm.state.UsageCache
import com.hienlh.ppm.state.UsageReader
import com.hienlh.ppm.state.UsageScope
import com.hienlh.ppm.state.UsageSnapshot
import com.hienlh.ppm.state.workspace.SourceLine
import com.hienlh.ppm.state.workspace.WorkspaceTab
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What the tablet's ChatPane does for a chat tab besides drawing it, for the phone's chat: the
 * same state and calls, so the phone and the tablet behave alike.
 */

/**
 * ChatPane's effects on its tab: the server's title on the tab and in the lists, the opening
 * message taken, the new chat's prewarm, a refused message back in the box, and the files the
 * transcript's tools wrote counted for the session-changes chip.
 */
@Composable
internal fun PhoneChatTabEffects(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState) {
    // A queued message asked back with Edit joins what is in the composer, its reply with it, the
    // way Claude Code's Up puts queued messages back into the input; one the AI already started on says so.
    LaunchedEffect(state.queueAnswer) {
        val answer = state.queueAnswer ?: return@LaunchedEffect
        val content = answer.content
        if (content != null) {
            val decoded = ChatReply.decode(content)
            val reply = decoded.replyTo?.let { quoted -> tab.session?.let { quoted.copy(sessionId = it.id) } ?: quoted }
            if (decoded.content.isNotEmpty()) {
                tab.restore(RejectedSend(decoded.content, reply ?: tab.replyTo, ""))
            } else if (reply != null) {
                tab.replyTo = reply
            }
        } else {
            answer.error?.let { model.toast(ErrorToastMessage(it, kind = ErrorToastMessage.Kind.INFO)) }
        }
        chat.queueAnswerTaken()
    }
    LaunchedEffect(state.title) {
        state.title?.takeIf { it.isNotEmpty() }?.let {
            tab.session?.let { session -> model.renameSession(session.id, it) }
            model.retitleTab(tab.tabId, it)
        }
    }
    LaunchedEffect(state.connected, state.sessionId) {
        if (state.connected && state.sessionId != null) model.pendingMessageTaken(tab)
    }
    val prewarm = if (tab.session == null && !tab.providerPending) {
        PrewarmAsk(tab.providerId, tab.permissionMode?.value, tab.claimedAccountId, chat.turnPicks)
    } else {
        null
    }
    DisposableEffect(tab, prewarm) {
        tab.prewarm = prewarm
        onDispose { if (tab.prewarm == prewarm) tab.prewarm = null }
    }
    LaunchedEffect(tab, prewarm) {
        if (prewarm == null) return@LaunchedEffect
        delay(PREWARM_SETTLE_MILLIS)
        model.newChats.prewarm(tab, prewarm)
    }
    LaunchedEffect(state.rejected) {
        val rejected = state.rejected ?: return@LaunchedEffect
        fun restore() {
            tab.restore(rejected)
            model.toast(ErrorToastMessage("Message not sent", "${rejected.message} Your text is back in the input."))
        }
        if (tab.draft.text.isBlank() && tab.replyTo == null) {
            restore()
        } else {
            model.toast(ErrorToastMessage("Message not sent", rejected.message, ErrorToastMessage.Action("Restore", ::restore)))
        }
        chat.takeRejected()
    }
    val sessionChanges = tab.sessionChanges
    val itemsWithLive = remember(state.items, state.live, state.liveStartedAt) { state.itemsWithLive() }
    LaunchedEffect(sessionChanges, itemsWithLive, state.isRunning) {
        if (sessionChanges != null) {
            val (paths) = tab.sessionWrites.of(itemsWithLive)
            sessionChanges.update(paths, state.isRunning)
        }
    }
}

/** This chat's usage reading (`useUsage`), as ChatPane reads it: the session's account, cost and windows. */
internal class PhoneChatUsage(val reader: UsageReader, val shown: UsageSnapshot?, val loading: Boolean)

@Composable
internal fun rememberPhoneChatUsage(model: AppModel, tab: ChatTab, state: ChatState): PhoneChatUsage {
    val fallbackCache = remember { UsageCache() }
    val cache = model.usageCache ?: fallbackCache
    val reader = remember(cache, tab) {
        UsageReader(cache) { scope, refresh ->
            model.api?.chatUsage(scope.project, scope.providerId, scope.sessionId, scope.previewAccountId, refresh)
        }
    }
    val scope = if (tab.providerPending) null else UsageScope(tab.project, tab.providerId, tab.session?.id, tab.claimedAccountId)
    LaunchedEffect(reader, scope) {
        if (scope != null) reader.run(scope, model.newChats.preparation(tab.tabId))
    }
    val snapshot by reader.snapshot.collectAsState()
    val loading by reader.loading.collectAsState()
    // The stream's report of who served (`liveAccount`), taken once each, as ChatPane takes it.
    val coroutines = rememberCoroutineScope()
    val live = state.liveAccount
    SideEffect {
        if (live !== tab.liveAccountTaken) {
            val moved = live != null && live.id != tab.liveAccountTaken?.id
            tab.liveAccountTaken = live
            if (live != null) tab.servingAccount = live
            if (moved) coroutines.launch { reader.read(refresh = false) }
        }
    }
    return PhoneChatUsage(reader, reader.shown(scope, snapshot), loading)
}

/** "This session $0.0123", as the ··· menu shows the cost; "—" before any is known. */
internal fun phoneCostLabel(costUsd: Double?): String = costUsd?.let { "$" + String.format(Locale.US, "%.4f", it) } ?: "—"

/** A turn's running time as the stop bar shows it: `m:ss`, or `h:mm:ss` past an hour. */
internal fun phoneElapsedLabel(start: Instant, now: Instant): String {
    val seconds = (now.epochSecond - start.epochSecond).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * How long the turn on screen has run, ticking each second: from when it started streaming
 * ([ChatState.liveStartedAt]) or, before that, from when this screen saw it start; null while idle.
 */
@Composable
internal fun rememberTurnElapsed(running: Boolean, liveStartedAt: Instant?): String? {
    var seenStart by remember { mutableStateOf<Instant?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(running) {
        if (!running) {
            seenStart = null
            return@LaunchedEffect
        }
        if (seenStart == null) seenStart = Instant.now()
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }
    if (!running) return null
    val start = listOfNotNull(liveStartedAt, seenStart).minOrNull() ?: return null
    return phoneElapsedLabel(start, now)
}

/** The composer's files as the phone's tray shows them: a picture or a file, and how its upload goes. */
internal fun phoneDraftAttachments(attachments: List<ComposerAttachment>): List<PhoneDraftAttachment> = attachments.map { a ->
    val status = when (a.status) {
        ComposerAttachment.Status.UPLOADING -> "Uploading…"
        ComposerAttachment.Status.ERROR -> "Upload failed"
        ComposerAttachment.Status.READY -> a.file?.length()?.takeIf { it > 0 }?.let(::phoneFileSize) ?: "Ready"
    }
    PhoneDraftAttachment(a.id, a.name, status, a.isImage)
}

internal fun phoneFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** The session-changes chip as `SessionChangesChip` counts it: before the list is read, the files the transcript names. */
internal fun phoneChangesChip(files: List<SessionFileChange>, loaded: Boolean, unloadedCount: Int): PhoneChangesChipState? {
    if (!loaded) return if (unloadedCount > 0) PhoneChangesChipState(unloadedCount) else null
    if (files.isEmpty()) return null
    val (pending, reviewed) = SessionChangeList.splitReviewed(files)
    if (pending.isEmpty()) return PhoneChangesChipState(reviewed.size, allReviewed = true)
    val (added, removed) = SessionChangeList.totals(pending)
    return PhoneChangesChipState(pending.size, added, removed)
}

/** The session's changed files for the sheet, still-to-review first, each path relative to the project. */
internal fun phoneChangedFiles(files: List<SessionFileChange>, projectPath: String?): List<PhoneChangedFileItem> {
    val (pending, reviewed) = SessionChangeList.splitReviewed(files)
    return (pending + reviewed).map { file ->
        PhoneChangedFileItem(
            path = file.path,
            additions = file.additions ?: 0,
            deletions = file.deletions ?: 0,
            reviewed = file.reviewed,
            displayPath = SessionChangeList.displayPath(file.path, projectPath),
        )
    }
}

/**
 * The attach sheet's three ways in (Phone 1 E2), each handing what was picked to `model.attach`
 * as the composer's paperclip does: the photo picker, the camera writing into the cache's
 * `camera/` (which the images provider serves), and the system's file picker.
 */
internal class PhoneAttachPickers(val photos: () -> Unit, val camera: (() -> Unit)?, val files: () -> Unit)

@Composable
internal fun rememberPhoneAttachPickers(onPicked: (List<Uri>) -> Unit): PhoneAttachPickers {
    val context = LocalContext.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    // Where the camera was told to write: kept across the activity being recreated meanwhile.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val photo = photoPath?.let(::File)
        photoPath = null
        if (taken && photo != null && photo.length() > 0) onPicked(listOf(Uri.fromFile(photo)))
    }
    val hasCamera = remember(context) { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }
    return PhoneAttachPickers(
        photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        camera = if (hasCamera) {
            {
                val photo = cameraFile(context)
                photoPath = photo.path
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.images", photo))
            }
        } else {
            null
        },
        files = { files.launch("*/*") },
    )
}

/** A fresh file for the camera, the last one gone (it was copied into the message box), named as Chrome names its own. */
private fun cameraFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply {
        deleteRecursively()
        mkdirs()
    }
    return File.createTempFile(System.currentTimeMillis().toString(), ".jpg", dir)
}

/**
 * What ChatPane hands the transcript around it: the chat's pictures, the tabs its cards open, the
 * terminal its code runs in, its agents, the async questions' answer and the turns' change pills.
 */
@Composable
internal fun PhoneTranscriptLocals(model: AppModel, tab: ChatTab, chat: ChatController, state: ChatState, content: @Composable () -> Unit) {
    val images = remember(tab.project) {
        object : ChatImages {
            override suspend fun load(path: String) = model.chatImage(tab.project, path)
            override suspend fun read(path: String) = model.chatImageFile(tab.project, path)
        }
    }
    val toolImages = remember(tab.project) { ToolImages { model.toolImage(tab.project, it) } }
    val markdownImages = remember(tab.project) {
        object : MarkdownImages {
            override suspend fun local(path: String) = model.toolImage(tab.project, path)
            override suspend fun web(url: String) = model.webImage(url)
        }
    }
    val tabs = remember(tab.project, tab.session?.id) {
        object : ChatTabs {
            override fun openFile(path: String) = model.openFileTab(tab.project, path)
            override fun openDiff(path: String, original: String, modified: String) = model.openDiffTab(tab.project, path, original, modified)
            override fun openToolFile(tool: String, path: String, line: Int?) {
                tab.session?.let { model.openToolFileTab(tab.project, it.id, tool, path, line) }
            }
            override fun openFileOrSearch(path: String, line: SourceLine?) = model.openFileOrSearch(tab.project, path, line)
            override fun openFilePath(path: String) = model.openFilePath(path, tab.project)
        }
    }
    val sessionId = tab.session?.id ?: state.sessionId
    val identity = AgentSessionIdentity(tab.project, agentTranscriptProvider(tab.providerId), sessionId.orEmpty())
    // A phone opens an agent's session in its sheet, as ChatPane does on a phone.
    val agents = remember(identity) { AgentSessionOpener(identity) { payload, fallback -> if (payload.sessionId.isNotEmpty()) model.openAgentSession(payload, fallback) } }
    val turnChanges = rememberTurnChangesHost(model, tab, state)
    CompositionLocalProvider(
        LocalChatImages provides images,
        LocalToolImages provides toolImages,
        LocalMarkdownImages provides markdownImages,
        LocalChatTabs provides tabs,
        LocalRunInTerminal provides { command -> model.runInTerminal(command, tab.project) },
        LocalAgentSessionOpener provides agents,
        LocalAsyncQuestions provides AsyncQuestions(
            state.items,
            if (state.connected) { text -> chat.send(text, TurnOptions(permissionMode = tab.permissionMode?.value)) } else null,
        ),
        LocalTurnChanges provides turnChanges,
        content = content,
    )
}

/**
 * The session's Review, opened from the changes sheet on [path] (`openSessionReview`): the
 * phone's Review layout over the chat. The workspace's session-review tab is not used, since a
 * phone shows that tab type as "opens on a tablet"; this holds its own review of the same session.
 */
internal class PhoneSessionReview(val review: ReviewTab)

internal fun openPhoneSessionReview(model: AppModel, tab: ChatTab, title: String?, paths: List<String>, path: String?): PhoneSessionReview? {
    val session = tab.session ?: return null
    val api = model.api ?: return null
    val review = ReviewTab("phone-session-review:${session.id}", tab.project, session.id)
    review.follow(
        WorkspaceTab(
            id = review.tabId,
            type = "session-review",
            title = if (!title.isNullOrEmpty()) "Changes: $title" else "Session changes",
            projectId = tab.project,
            metadata = buildJsonObject {
                put("projectName", tab.project)
                put("sessionId", session.id)
                put("providerId", tab.providerId)
                if (!title.isNullOrEmpty()) put("chatTitle", title)
                put("paths", JsonArray(paths.map(::JsonPrimitive)))
                if (path != null) {
                    put("select", buildJsonObject { put("path", path); put("at", System.currentTimeMillis()) })
                }
            },
        ),
    )
    // An answer in the Review changes what the chip's list holds, as `reviewAnswered` tells the chat.
    review.start(api, model.scope, model.sessionTurns) { tab.sessionChanges?.takeIf { it.loaded.value }?.refresh() }
    return PhoneSessionReview(review)
}

@Composable
internal fun PhoneSessionReviewOverlay(model: AppModel, opened: PhoneSessionReview, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val review = opened.review
    DisposableEffect(review) { onDispose { review.stop() } }
    val shown = review.review ?: return
    val entries by model.sessionTurns.entries.collectAsState()
    val statusBar = WindowInsets.statusBars.getTop(LocalDensity.current)
    val listSheet: ReviewSheet = remember(statusBar) {
        val sheetMax = Modifier.layout { measurable, constraints ->
            val max = ((constraints.maxHeight - statusBar) * 0.85f).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(maxHeight = max))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val open: ReviewSheet = { dismiss, content -> BottomSheet(dismiss, sheetMax) { content(LocalSheetContentTop.current) } }
        open
    }
    val sheet: ReviewSheet = remember {
        val open: ReviewSheet = { dismiss, content -> BottomSheet(dismiss) { content(LocalSheetContentTop.current) } }
        open
    }
    val ctx = ReviewTabContext(
        projectPath = model.projects.firstOrNull { it.name == review.project }?.path,
        providerId = review.providerId,
        chatTitle = review.chatTitle,
        turns = entries[review.sessionId]?.byCall.orEmpty(),
        openChat = onClose,
        openFile = {
            model.openFileTab(review.project, it)
            onClose()
        },
        // The chat already showing this session takes the jump (`ChatTranscriptView.jumpRequest`).
        showInChat = { turn, call ->
            model.showInChat(review, turn.messageId, call)
            onClose()
        },
        typing = { TextInputFocus.focused },
        ownsKeys = { true },
        sheet = sheet,
        listSheet = listSheet,
    )
    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color).testTag("phone-session-review")) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .ppmClickable(shape = CircleShape, onClickLabel = "Back to chat", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ArrowLeft, 18.dp, tokens.text.color)
            }
            BasicText(
                review.chatTitle?.let { "Changes: $it" } ?: "Session changes",
                Modifier.weight(1f),
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ReviewPhone(shown, ctx)
        }
    }
}

/** "5h 34% · Wk 12%", the account chip's second line, from the usage the footer's rings show. */
internal fun phoneAccountUsageText(summary: FooterUsageSummary): String? =
    listOfNotNull(summary.fiveHourPct?.let { "5h $it%" }, summary.weekPct?.let { "Wk $it%" }).joinToString(" · ").ifEmpty { null }

/**
 * The account chip's sheet: the ··· menu's "Switch account" view (`switch-account-view.tsx`), on
 * the same accounts and the same pick (`model.selectAccount`), as a phone's footer popover is a sheet.
 */
@Composable
internal fun PhoneAccountSheet(
    model: AppModel,
    tab: ChatTab,
    usage: PhoneChatUsage,
    summary: FooterUsageSummary,
    switcher: SwitchAccountsState,
    onDismiss: () -> Unit,
) {
    val providerName = if (tab.providerId == "codex") "Codex" else "Claude"
    val select: (suspend (String, String) -> String?)? = if (!tab.providerPending && !tab.firstSendPending) {
        { id, label ->
            model.selectAccount(tab, id, label) { usage.reader.read(refresh = false) }.also { error -> if (error == null) switcher.reload() }
        }
    } else {
        null
    }
    val accounts = {
        onDismiss()
        model.openSettings("accounts")
    }
    BottomSheet(onDismiss, Modifier.testTag("phone-account-sheet")) {
        SwitchAccountView(
            accounts = switcher.accounts,
            loading = switcher.loading,
            currentId = summary.currentId,
            providerName = providerName,
            onBack = onDismiss,
            onPick = select,
            onPicked = onDismiss,
            onReauth = { accounts() },
            onAddAccount = accounts,
            onManage = accounts,
        )
    }
}

/** The server's queue as the stack shows it: the typed text without the reply it carries. */
internal fun phoneQueuedMessages(queue: List<QueuedMessage>): List<PhoneQueuedMessage> = queue.map {
    PhoneQueuedMessage(it.id, ChatReply.decode(it.content).content.trim(), it.imageCount)
}
