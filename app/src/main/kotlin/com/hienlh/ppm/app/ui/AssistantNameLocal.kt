package com.hienlh.ppm.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.assistant

/**
 * The PPM Assistant's name as an approval card heads with it (`useAssistantDisplayName`): the
 * persona's from Settings → PPM Assistant, else "PPM Assistant". Follows a save at once.
 */
@Composable
internal fun assistantDisplayName(model: AppModel): String {
    val store = model.assistant.store ?: return "PPM Assistant"
    return store.view.collectAsState().value.displayName
}
