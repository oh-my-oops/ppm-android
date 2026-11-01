package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.chat.ChatTabs
import com.hienlh.ppm.chat.LocalChatTabs
import com.hienlh.ppm.chat.LocalMarkdownImages
import com.hienlh.ppm.chat.MarkdownImages
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.state.workspace.SourceLine

/** Uses the same host/project link resolution and absolute-only local images as web Markdown. */
@Composable
internal fun FileMarkdownView(model: AppModel, project: String?, content: String) {
    val images = remember(model, project, model.api) {
        object : MarkdownImages {
            override suspend fun local(path: String) = model.toolImage(project.orEmpty(), path)
            override suspend fun web(url: String) = model.webImage(url)
        }
    }
    val tabs = remember(model, project) {
        object : ChatTabs {
            override fun openFile(path: String) = openFileOrSearch(path, null)
            override fun openDiff(path: String, original: String, modified: String) {
                if (project != null) model.openDiffTab(project, path, original, modified)
            }
            override fun openToolFile(tool: String, path: String, line: Int?) =
                openFileOrSearch(path, line?.let { SourceLine(it.toLong()) })
            override fun openFileOrSearch(path: String, line: SourceLine?) = model.openFileOrSearch(project.orEmpty(), path, line)
        }
    }
    CompositionLocalProvider(LocalMarkdownImages provides images, LocalChatTabs provides tabs) {
        MarkdownView(content, Modifier.fillMaxSize().cssScrollY().padding(16.dp))
    }
}
