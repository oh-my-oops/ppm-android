package com.hienlh.ppm.state.preview

import com.hienlh.ppm.protocol.TabOpenAnswer
import com.hienlh.ppm.protocol.TabOpenRequest
import com.hienlh.ppm.state.workspace.AiTabTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The preview a file's tab shows it under, and the last load already seen there when the tab was asked for it. */
data class PreviewTicket(val key: String, val before: Int)

/** A page that has not fired `load` by then (a slow CDN) is checked anyway. */
const val PREVIEW_LOAD_WAIT_MS = 12_000L

/** After `load`, the page's own scripts still draw: charts, fonts, a framework's first render. */
const val PREVIEW_SETTLE_MS = 1_500L

/** Whether a file opens as a page (`isHtml` in code-editor.tsx). */
fun isHtmlFile(filePath: String): Boolean = filePath.substringAfterLast('.').lowercase().let { it == "html" || it == "htm" }

/** Why this app refuses a request it cannot open yet; null when it can. */
fun tabOpenRefusal(request: TabOpenRequest): String? = when (request) {
    is TabOpenRequest.Url -> null
    is TabOpenRequest.File -> when {
        request.line != null -> "the PPM Android app cannot show code yet"
        !isHtmlFile(request.filePath) -> "the PPM Android app can show only HTML pages so far"
        else -> null
    }
    is TabOpenRequest.Query -> "the PPM Android app cannot open database queries yet"
    is TabOpenRequest.Terminal -> "the PPM Android app has no terminal yet"
    is TabOpenRequest.Other -> "the PPM Android app does not know the ${request.tool ?: "requested"} tool"
}

/**
 * Answers the server's `tab_open` (`answerTabOpen`): opens the tab and, for a page the AI made,
 * waits for it to load and settle and returns the check of how it rendered. Every outcome is an
 * answer — a silent device would leave the AI waiting for the server's timeout.
 *
 * [openFile] opens (or brings back) the file's tab beside the chat and says which preview shows
 * it; [openUrl] opens a web-preview tab; both throw, with what to tell the AI, when they cannot.
 * [frame] is the device frame the page is seen in: "Phone" in the phone layout, else "Desktop".
 */
suspend fun answerTabOpen(
    request: TabOpenRequest,
    previews: PreviewLoads,
    openFile: suspend (AiTabTarget) -> PreviewTicket,
    openUrl: suspend (TabOpenRequest.Url) -> Unit,
    frame: () -> String,
    settle: suspend (Long) -> Unit = { delay(it) },
): TabOpenAnswer {
    tabOpenRefusal(request)?.let { return TabOpenAnswer(opened = false, error = it) }
    val ticket = try {
        when (request) {
            is TabOpenRequest.Url -> {
                openUrl(request)
                return TabOpenAnswer(opened = true)
            }
            is TabOpenRequest.File -> openFile(AiTabTarget(request.tool, request.filePath, request.projectName, request.line))
            else -> return TabOpenAnswer(opened = false)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return TabOpenAnswer(opened = false, error = reason(e))
    }
    val check = (request as TabOpenRequest.File).check ?: return TabOpenAnswer(opened = true)
    return try {
        val load = previews.waitFor(ticket.key, ticket.before, PREVIEW_LOAD_WAIT_MS)
            ?: return TabOpenAnswer(opened = true, error = "the page did not start loading")
        settle(PREVIEW_SETTLE_MS)
        TabOpenAnswer(opened = true, report = load.check.run(check.screenshot, frame()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TabOpenAnswer(opened = true, error = reason(e))
    }
}

private fun reason(e: Exception): String = e.message ?: e.toString()
