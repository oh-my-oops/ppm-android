package com.hienlh.ppm.app.ui.phone.specs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.newSpec
import com.hienlh.ppm.app.openSpec
import com.hienlh.ppm.app.openSpecChat
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.specs.EmptyNote
import com.hienlh.ppm.app.ui.specs.PollWhileShown
import com.hienlh.ppm.app.ui.specs.SPECS_POLL_MS
import com.hienlh.ppm.app.ui.specs.SPEC_POLL_MS
import com.hienlh.ppm.app.ui.specs.SpecDetail
import com.hienlh.ppm.app.ui.specs.SpecListRow
import com.hienlh.ppm.app.ui.specs.SpecStartSheet
import com.hienlh.ppm.app.ui.specs.errorText
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.client.specs
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.BottomActionCard
import kotlinx.coroutines.CancellationException

/** How often a shown spec is read again: the server's `spec:changed` does not reach the app's socket handler. */
internal const val PHONE_SPEC_POLL_MS = SPEC_POLL_MS

/** How often the Specs list is read again while it is shown. */
internal const val PHONE_SPECS_POLL_MS = SPECS_POLL_MS

/**
 * The phone's Specs area (the web's sidebar Specs panel and the spec pane beside a chat): the
 * project's specs, and the one opened from them or from a chat.
 */
@Composable
internal fun PhoneSpecsAreaHost(model: AppModel, nav: PhoneNav) {
    val specs = model.specs
    val project = model.selectedProject
    val slug = specs.openSlug
    BackHandler(enabled = slug != null && specs.openedFromList) { specs.closeSpec() }
    when {
        project == null -> EmptyNote("Choose a project to see its specs.")
        slug == null -> PhoneSpecsList(
            model = model,
            project = project,
            onOpen = { model.openSpec(it.slug, fromList = true) },
            onNew = {
                model.newSpec()
                model.activeTabId?.let(nav::visitTab)
            },
        )
        else -> PhoneSpecScreen(
            model = model,
            project = project,
            slug = slug,
            onOpenChat = { sessionId, title ->
                model.openSpecChat(project, sessionId, title) { model.activeTabId?.let(nav::visitTab) }
            },
        )
    }
}

/** The project's specs, newest first: where each stands, its tasks done, its checks, and whether it waits for you. */
@Composable
internal fun PhoneSpecsList(model: AppModel, project: String, onOpen: (SpecSummary) -> Unit, onNew: () -> Unit) {
    val tokens = LocalPpmTokens.current
    var list by remember(project) { mutableStateOf<List<SpecSummary>?>(null) }
    var error by remember(project) { mutableStateOf<String?>(null) }
    PollWhileShown(model.api, project, periodMs = PHONE_SPECS_POLL_MS) {
        val api = model.api ?: return@PollWhileShown
        try {
            list = api.specs(project)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
            if (list == null) list = emptyList()
        }
    }
    Column(Modifier.fillMaxSize().testTag("phone-specs-screen")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = list
            when {
                shown == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text3.color, spinning = true)
                }
                shown.isEmpty() -> EmptyNote(error?.let { "Could not load the specs: $it" } ?: "No specs yet. Start a chat and pick Spec, or press New spec.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.slug }) { spec -> SpecListRow(spec) { onOpen(spec) } }
                }
            }
        }
        BottomActionCard {
            ActionPrimaryButton(icon = ProductIcon.Plus, label = "New spec", onClick = onNew, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One spec (`SpecPane`): where it stands (Requirements → Design → Tasks), the document of the step
 * it is on with Approve / Request changes, and once the tasks are approved the tasks by wave with
 * Run all / Stop, Start and each task's chat. Approvals are made here, never by the chat's AI.
 */
@Composable
internal fun PhoneSpecScreen(
    model: AppModel,
    project: String,
    slug: String,
    onOpenChat: (sessionId: String, title: String) -> Unit,
) {
    SpecDetail(model, project, slug, onOpenChat, onLoaded = { model.specs.openTitle = it.title })
}

/**
 * The workflow a chat starts in (spec-workflow.tsx's Workflow group): Spec, Quick Spec or Bug Fix,
 * where a Spec starts, and its task checks. A chat with no session yet can also stay a Chat.
 */
@Composable
internal fun PhoneSpecStartSheet(model: AppModel, chatTabId: String, onDismiss: () -> Unit) {
    SpecStartSheet(model, chatTabId, onDismiss, forceBottomSheet = true)
}
