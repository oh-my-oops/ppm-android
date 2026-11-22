package com.hienlh.ppm.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.hienlh.ppm.design.LocalTextFieldFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A text field on a chat card (the question card's "Other", a password): reported to the app as the focused
 * field ([LocalTextFieldFocus]), and brought into view once the keyboard has opened. Without the first, the
 * tap that focused it was taken straight back as a tap on nothing, so the keyboard closed or typed into
 * nothing; without the second, a transcript the keyboard shortens (the phone's) left the field under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cardTextField(focused: Boolean): Modifier {
    val bring = remember { BringIntoViewRequester() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // The keyboard slides in over a few hundred ms: bring the field up once it has stopped.
        snapshotFlow { ime.getBottom(density) }.collectLatest { bottom ->
            if (bottom > 0) {
                delay(80)
                bring.bringIntoView()
            }
        }
    }
    return this.bringIntoViewRequester(bring).then(LocalTextFieldFocus.current)
}
