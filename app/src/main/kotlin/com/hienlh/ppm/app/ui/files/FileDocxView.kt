package com.hienlh.ppm.app.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.web.DocxSurface
import kotlinx.coroutines.CancellationException

@Composable
internal fun FileDocxView(model: AppModel, html: String) {
    val context = LocalContext.current
    var safe by remember(html) { mutableStateOf<String?>(null) }
    var error by remember(html) { mutableStateOf<String?>(null) }
    LaunchedEffect(html) {
        try { safe = SyntaxHighlighter.get(context).sanitizeDocumentHtml(html) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not prepare the document." }
    }
    when {
        error != null -> FileMessage("Document unavailable", error)
        safe == null -> FileMessage("Preparing document…")
        else -> DocxSurface(safe!!, onLink = { url ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: ActivityNotFoundException) { model.toast(ErrorToastMessage("No app can open this link.")) }
        }, onCrash = { error = "The document viewer stopped. Refresh to try again." }, modifier = Modifier.fillMaxSize())
    }
}
